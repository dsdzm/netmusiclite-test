// ============================================================================
// PlayerEngine.kt —— 需要改动/新增的片段（对照 1.32 现有实现）
//
// 1.32 里这几个成员已经存在，不用重复添加：
//   - initMediaSession()            line ≈729（已有 setSessionActivity）
//   - updateMediaSession()          line ≈1890（已有 ALBUM_ART_URI）
//   - mediaSessionToken             line ≈1497（getMediaSessionToken）
//   - playbackActive                line ≈（getPlaybackActive）
//   - warmUp() / ensurePlayer()     line ≈1938 / 576
//
// 下面是 1.32 里**仍缺**的四段，按顺序补进去。
// ============================================================================

// ---------------------------------------------------------------------------
// 【1】ensurePlayer：同步补建播放器时也要建会话（1.32 仍存在此竞态）
//
// 1.32 现状：
//     private fun ensurePlayer(): ExoPlayer {
//         warmUp()
//         player?.let { return it }
//         return buildExoPlayer().also { configPlayer(it); player = it }
//     }
//     // PlayerEngine$warmUp$1 里：
//     //   val p = buildExoPlayer()
//     //   if (player == null) { configPlayer(p); player = p; initMediaSession() }
//     //   else { p.release() }      ← 这里直接 return，永远不建会话
//
// 触发条件：系统媒体服务提前 bind、或冷启动 300ms 内点歌 —— ensurePlayer 抢先同步建好
// player，随后预热协程发现 player 非空，走 release 分支，MediaSession 始终为 null。
// 结果：通知退化成普通通知（没有 MediaStyle）、蓝牙线控与手表「正在播放」卡片全失效。
// ---------------------------------------------------------------------------

private fun ensurePlayer(): ExoPlayer {
    warmUp()
    return player ?: buildExoPlayer().also { exo ->
        configPlayer(exo)
        player = exo
        // 同步补建同样要配会话：否则通知/线控/系统媒体面板全程拿不到 token。
        // 调用方（load / sessionPlayerOrNull）都在主线程，符合 MediaSession 的线程要求。
        ensureMediaSession()
    }
}

/** 幂等建会话：warmUp 与 ensurePlayer 两条初始化路径共用，避免任一路径漏建 */
private fun ensureMediaSession() {
    if (mediaSession == null) initMediaSession()
}

// 同时把 PlayerEngine$warmUp$1 的 else 分支改为显式兜底（双保险）：
//     if (player == null) {
//         configPlayer(p); player = p; ensureMediaSession()
//     } else {
//         p.release()
//         ensureMediaSession()   // ← 新增：即便这次构建被丢弃，也保证会话已建
//     }

// ---------------------------------------------------------------------------
// 【2】initMediaSession：补媒体键 flags + 幂等保护
//
// 1.32 现状有 setCallback / setSessionActivity / setActive，缺 setFlags。
// 缺 flags 时部分车机、部分手表只显示「正在播放」但不派发按键。
// ---------------------------------------------------------------------------

private fun initMediaSession() {
    if (mediaSession != null) return          // ← 新增：幂等，防两条路径重复建
    val ms = MediaSession(appCtx, "NetMusicLite")
    // ← 新增下面这段
    ms.setFlags(
        MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or
            MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS
    )
    ms.setCallback(object : MediaSession.Callback() {
        override fun onPlay() = playAction("mediaSession")
        override fun onPause() = pauseAction()
        override fun onSkipToNext() { next(); updateMediaSession() }
        override fun onSkipToPrevious() { prev(); updateMediaSession() }
        override fun onSeekTo(pos: Long) { seekTo(pos); updateMediaSession() }
        override fun onStop() = pauseAction()
    })
    // 1.32 已有，保持不动
    runCatching {
        ms.setSessionActivity(
            PendingIntent.getActivity(
                appCtx, 0,
                Intent(appCtx, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        )
    }
    ms.isActive = true
    mediaSession = ms
    updateMediaSession()
}

// ---------------------------------------------------------------------------
// 【3】updateMediaSession：补 ART_URI（1.32 已有 ALBUM_ART_URI，再加一行更稳）
//
// 1.32 现状：
//     val uri = coverUriFor(current)
//     if (uri != null) builder.putString(METADATA_KEY_ALBUM_ART_URI, uri.toString())
//
// 有些系统 UI（含部分手表「正在播放」卡片）只读 ART_URI，两个都写才不会出现
// 「锁屏有封面、手表卡片没有」。另外切歌时元数据要整体替换，不能只改 title。
// ---------------------------------------------------------------------------

current?.let { s ->
    val builder = MediaMetadata.Builder()
        .putString(MediaMetadata.METADATA_KEY_TITLE, s.title)
        .putString(MediaMetadata.METADATA_KEY_ARTIST, s.artist)
        .putString(MediaMetadata.METADATA_KEY_ALBUM, s.album)
        .putLong(MediaMetadata.METADATA_KEY_DURATION, durationMs)
    coverUriFor(s)?.let { uri ->
        builder.putString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI, uri.toString())
        builder.putString(MediaMetadata.METADATA_KEY_ART_URI, uri.toString())   // ← 新增
    }
    ms.setMetadata(builder.build())
} ?: run {
    // 无当前歌曲时清掉旧元数据，防止系统面板残留上一首
    ms.setMetadata(MediaMetadata.Builder().build())
}

// ---------------------------------------------------------------------------
// 【4】新增 sessionPlayerOrNull：把 ExoPlayer 引用借给 PlayerNotificationManager
//
// 1.32 的 player 是 private，PlaybackService 拿不到，Wear 的 PNR 需要它。
// 注意：只交出**引用**，不交出所有权 —— 生命周期、监听器、解码配置仍归 PlayerEngine，
// 服务侧绝不可 release。
// ---------------------------------------------------------------------------

fun sessionPlayerOrNull(ctx: Context): Player? {
    if (!::appCtx.isInitialized) init(ctx)
    return runCatching { ensurePlayer() }.getOrNull()
}
