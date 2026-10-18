package com.ncm.watch.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.widget.RemoteViews
import androidx.media3.common.Player
import androidx.media3.ui.PlayerNotificationManager
import coil.ImageLoader
import coil.request.ImageRequest
import com.ncm.watch.MainActivity
import com.ncm.watch.R

/**
 * 播放保活前台服务（foregroundServiceType=mediaPlayback）。
 *
 * 通知按设备分三条路径：
 *  - Wear OS：PlayerNotificationManager（RemoteViews 在 Wear 上受限，不能自绘）；
 *  - Android 9 及以下非 Wear 手表：自定义 RemoteViews 紧凑控制条；
 *  - 其余：MediaStyle 关联同一会话。
 *
 * 移植自 1.0.3 分支的同名文件，已按 1.32 的接口对齐：
 *  - 包名 com.ncm.watch.data，入口 MainActivity 在 com.ncm.watch；
 *  - playAction 需要来源参数，这里传 "notification"；
 *  - 通知图标跟随 M3E 主题（AppearancePrefs.isM3e()）；
 *  - 封面地址直接取 PlayerEngine.coverUriFor(song)，不再额外加 artworkModelFor。
 */
class PlaybackService : Service() {

    /** 通知封面专用解码器：关闭硬件位图（Notification 大图需软件 Bitmap） */
    private val artLoader by lazy {
        ImageLoader.Builder(applicationContext).allowHardware(false).build()
    }
    private val artHandler by lazy { Handler(Looper.getMainLooper()) }

    /** Wear OS 专用媒体通知管理器；非 Wear 设备为 null */
    private var wearNotificationManager: PlayerNotificationManager? = null
    private var wearPlayer: Player? = null
    private var wearManagerCreating = false

    /**
     * Wear OS 判定：只认 FEATURE_WATCH / uiMode。
     * 不能用屏幕宽度判断 —— 部分国产 Android 9 手表是小屏但不是 Wear OS，
     * 它们走 RemoteViews 兼容分支；真正的 Wear OS 走 PlayerNotificationManager。
     */
    private val isWearOs: Boolean
        get() = packageManager.hasSystemFeature(PackageManager.FEATURE_WATCH) ||
            (resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK) ==
            Configuration.UI_MODE_TYPE_WATCH

    /** 手表判定：Wear OS 与部分国产手表不声明 FEATURE_WATCH，再看 uiMode 与小屏宽度 */
    private val isWatchDevice: Boolean
        get() = isWearOs || resources.configuration.smallestScreenWidthDp <= 240

    /**
     * Android 9 及以下的兼容通道：这类设备多为老手表/定制 ROM，
     * 通知中心会把 MediaStyle 渲染成「只有封面+歌名的媒体卡」，并丢弃 action 按钮。
     * 这类设备改用自定义 RemoteViews 紧凑控制条（封面 + 歌名 + 切歌/暂停/关闭）。
     */
    private val legacyCompact: Boolean
        get() = Build.VERSION.SDK_INT <= 28

    /**
     * 通知渠道：
     *  - Wear OS：playback_wear，由 PlayerNotificationManager 使用
     *  - Android 9 及以下/小屏手表：playback_watch，DEFAULT，避免被通知中心折叠
     *  - 其余设备：playback，LOW
     */
    private val channelId: String
        get() = when {
            isWearOs -> CHANNEL_ID_WEAR
            legacyCompact || isWatchDevice -> CHANNEL_ID_WATCH
            else -> CHANNEL_ID
        }

    // ---------- 通知图标：跟随 M3E 主题 ----------

    private val iconPrev: Int
        get() = if (AppearancePrefs.isM3e()) R.drawable.ic_m3e_notif_prev else R.drawable.ic_notif_prev
    private val iconNext: Int
        get() = if (AppearancePrefs.isM3e()) R.drawable.ic_m3e_notif_next else R.drawable.ic_notif_next
    private val iconPlay: Int
        get() = if (AppearancePrefs.isM3e()) R.drawable.ic_m3e_notif_play else R.drawable.ic_notif_play
    private val iconPause: Int
        get() = if (AppearancePrefs.isM3e()) R.drawable.ic_m3e_notif_pause else R.drawable.ic_notif_pause

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onDestroy() {
        // Wear：先摘掉 Player，PlayerNotificationManager 会自行取消媒体通知
        wearNotificationManager?.setPlayer(null)
        wearNotificationManager = null
        wearPlayer = null
        if (isWearOs) {
            runCatching { getSystemService(NotificationManager::class.java).cancel(NOTIF_ID) }
        }
        instance = null
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY -> PlayerEngine.playAction("notification")
            ACTION_PAUSE -> PlayerEngine.pauseAction()
            ACTION_NEXT -> PlayerEngine.next()
            ACTION_PREV -> PlayerEngine.prev()
            ACTION_STOP -> {
                // 通知右上角 X：暂停并撤掉通知（不销毁队列/当前歌，下次播放可续）
                PlayerEngine.pauseAction()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
        }
        if (PlayerEngine.playbackActive) {
            startForeground(NOTIF_ID, foregroundNotification())
            if (isWearOs) ensureWearNotificationManager()
        } else {
            // ★ 5 秒契约：ensureStarted() 判定播放意图成立才 startForegroundService，但意图可能在
            //   本回调执行前就翻转（用户立刻暂停 / 起播失败撤销 fgWanted / 队列被替换）。
            //   旧实现这时直接 stopForeground+stopSelf，从未调用 startForeground()，系统随后抛
            //   RemoteServiceException: "Context.startForegroundService() did not then call
            //   Service.startForeground()" 杀掉整个进程。
            //   契约只对 startForegroundService 拉起的这次启动（ACTION_START）生效：
            //   先补一次 startForeground 满足契约，再立刻撤回（通知一闪即消，无残留）。
            if (intent?.action == ACTION_START) {
                startForeground(NOTIF_ID, foregroundNotification())
            }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun buildNotification(): Notification {
        if (isWearOs) return buildWearPlaceholder()
        val chId = channelId
        val importance = if (legacyCompact || isWatchDevice) {
            // 手表/老设备：DEFAULT 才不会被部分通知中心折叠；声音/震动仍关闭
            NotificationManager.IMPORTANCE_DEFAULT
        } else {
            NotificationManager.IMPORTANCE_LOW
        }
        ensureChannel(chId, importance)
        val song = PlayerEngine.current
        val playing = PlayerEngine.isPlaying
        val contentPi = contentPendingIntent()
        fun cmd(action: String): PendingIntent = PendingIntent.getService(
            this, action.hashCode(),
            Intent(this, PlaybackService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = Notification.Builder(this, chId)
            .setSmallIcon(R.drawable.ic_notif_w)
            .setContentTitle(song?.title ?: "NetMusicLite")
            .setContentText(song?.artist ?: "")
            .setContentIntent(contentPi)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            // 老设备上不锁常驻：部分通知中心会把 ongoing 通知排除在「消息」列表之外；
            // 新设备仍保持常驻，避免被误滑掉。
            .setOngoing(!legacyCompact)
            // 媒体通知不显示「几点发的」；时间戳对正在播放的卡片没有意义
            .setShowWhen(false)
            .addAction(Notification.Action.Builder(
                Icon.createWithResource(this, iconPrev), "上一首", cmd(ACTION_PREV)).build())
            .addAction(Notification.Action.Builder(
                Icon.createWithResource(this, if (playing) iconPause else iconPlay),
                if (playing) "暂停" else "播放",
                cmd(if (playing) ACTION_PAUSE else ACTION_PLAY),
            ).build())
            .addAction(Notification.Action.Builder(
                Icon.createWithResource(this, iconNext), "下一首", cmd(ACTION_NEXT)).build())
        val art = song?.let { notificationArt(it) }
        if (song != null) {
            // 专辑名放 subText（展开态显示），小屏上不挤占歌手行
            if (song.album.isNotBlank()) builder.setSubText(song.album)
            // 封面大图：首次构建时通常还没解码完，先出文字版，图到了再原地刷新
            art?.let { builder.setLargeIcon(it) }
        }
        if (legacyCompact) {
            // ★ 紧凑控制条：
            //   部分 Android 9 手表/定制 ROM 的通知中心会把普通通知画成大卡片、且不渲染 action；
            //   唯一能自定义的就是 RemoteViews —— 自己画一行「封面 + 歌名/歌手 + 上一首/
            //   播放暂停/下一首/关闭」，点按由 PendingIntent 直接驱动播放服务。
            val rv = RemoteViews(packageName, R.layout.notification_media_compact)
            rv.setTextViewText(R.id.notif_title, song?.title ?: "NetMusicLite")
            rv.setTextViewText(
                R.id.notif_artist,
                listOfNotNull(
                    song?.artist?.takeIf { it.isNotBlank() },
                    song?.album?.takeIf { it.isNotBlank() },
                ).joinToString(" · "),
            )
            if (art != null) {
                rv.setImageViewBitmap(R.id.notif_cover, art)
            } else {
                rv.setImageViewResource(R.id.notif_cover, R.drawable.ic_notif_w)
            }
            rv.setImageViewResource(R.id.notif_toggle, if (playing) iconPause else iconPlay)
            rv.setImageViewResource(R.id.notif_prev, iconPrev)
            rv.setImageViewResource(R.id.notif_next, iconNext)
            rv.setOnClickPendingIntent(R.id.notif_root, contentPi)
            rv.setOnClickPendingIntent(R.id.notif_prev, cmd(ACTION_PREV))
            rv.setOnClickPendingIntent(R.id.notif_toggle, cmd(if (playing) ACTION_PAUSE else ACTION_PLAY))
            rv.setOnClickPendingIntent(R.id.notif_next, cmd(ACTION_NEXT))
            rv.setOnClickPendingIntent(R.id.notif_close, cmd(ACTION_STOP))
            builder.setCustomContentView(rv)
            builder.setCustomBigContentView(rv)
        } else {
            PlayerEngine.mediaSessionToken?.let { token ->
                builder.setStyle(
                    Notification.MediaStyle().setMediaSession(token).setShowActionsInCompactView(0, 1, 2),
                )
            }
        }
        if (Build.VERSION.SDK_INT >= 31) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }
        return builder.build()
    }

    /** 前台通知内容：Wear 用占位通知（PNR 随后覆盖），其他设备直接构建完整通知 */
    private fun foregroundNotification(): Notification =
        if (isWearOs) buildWearPlaceholder() else buildNotification()

    /**
     * Wear OS 占位通知。
     *
     * PlayerNotificationManager 构建/更新通知是异步的，而 startForegroundService
     * 要求 5 秒内必须调用 startForeground。这里先用同一 ID / 渠道发一个最小通知满足契约，
     * PNR 随后会用同一 ID 把它覆盖成完整的媒体通知。
     */
    private fun buildWearPlaceholder(): Notification {
        ensureChannel(CHANNEL_ID_WEAR, NotificationManager.IMPORTANCE_DEFAULT)
        val song = PlayerEngine.current
        return Notification.Builder(this, CHANNEL_ID_WEAR)
            .setSmallIcon(R.drawable.ic_notif_w)
            .setContentTitle(song?.title ?: "NetMusicLite")
            .setContentText(song?.artist ?: "")
            .setContentIntent(contentPendingIntent())
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setShowWhen(false)
            .build()
    }

    /**
     * Wear OS 的媒体通知交给 PlayerNotificationManager：
     *  - 它内部走 NotificationCompat/MediaStyle，Wear OS 会按系统媒体模板渲染；
     *  - RemoteViews 在 Wear OS 上受限，不能沿用 Android 9 手表那套紧凑布局；
     *  - 它自己监听 Player，切歌/播放状态变化会自动刷新通知。
     *
     * 前台实体仍然是本服务：PNR 通过 NotificationListener.onNotificationPosted
     * 把通知交回来，我们再 startForeground 接管为前台通知。
     */
    private fun ensureWearNotificationManager(): PlayerNotificationManager? {
        if (!isWearOs) return null
        wearNotificationManager?.let { return it }
        if (wearManagerCreating) return null
        wearManagerCreating = true
        try {
            ensureChannel(CHANNEL_ID_WEAR, NotificationManager.IMPORTANCE_DEFAULT)
            val player = PlayerEngine.sessionPlayerOrNull(this) ?: return null
            val manager = PlayerNotificationManager.Builder(
                this,
                NOTIF_ID,
                CHANNEL_ID_WEAR,
            )
                .setMediaDescriptionAdapter(WearDescriptionAdapter())
                .setChannelImportance(NotificationManager.IMPORTANCE_DEFAULT)
                .setSmallIconResourceId(R.drawable.ic_notif_w)
                .setPlayActionIconResourceId(iconPlay)
                .setPauseActionIconResourceId(iconPause)
                .setPreviousActionIconResourceId(iconPrev)
                .setNextActionIconResourceId(iconNext)
                .setNotificationListener(object : PlayerNotificationManager.NotificationListener {
                    override fun onNotificationPosted(
                        notificationId: Int,
                        notification: Notification,
                        ongoing: Boolean,
                    ) {
                        // PNR 只负责内容；前台实体仍是本服务。同一 ID 接管，避免两条通知。
                        // 回调线程不保证是主线程，startForeground 统一回主线程。
                        artHandler.post {
                            if (PlayerEngine.playbackActive) {
                                runCatching { startForeground(notificationId, notification) }
                            }
                        }
                    }

                    override fun onNotificationCancelled(notificationId: Int, dismissedByUser: Boolean) {
                        artHandler.post {
                            if (dismissedByUser) PlayerEngine.pauseAction()
                            stopForeground(STOP_FOREGROUND_REMOVE)
                            stopSelf()
                        }
                    }
                })
                .build()
            manager.setUsePreviousAction(true)
            manager.setUseNextAction(true)
            manager.setUsePlayPauseActions(true)
            manager.setUseStopAction(false)
            manager.setUseRewindAction(false)
            manager.setUseFastForwardAction(false)
            manager.setUsePreviousActionInCompactView(true)
            manager.setUseNextActionInCompactView(true)
            // 平台 MediaSession token：让 PNR 生成的 MediaStyle 与系统媒体面板/蓝牙共用同一会话
            PlayerEngine.mediaSessionToken?.let { manager.setMediaSessionToken(it) }
            val wrapped = EnginePlayer(player)
            wearPlayer = wrapped
            manager.setPlayer(wrapped)
            wearNotificationManager = manager
            return manager
        } finally {
            wearManagerCreating = false
        }
    }

    /** 播放状态/歌曲变化时刷新：Wear 交给 PNR，其他设备重建前台通知 */
    private fun refreshNotification() {
        if (isWearOs) {
            wearNotificationManager?.invalidate() ?: ensureWearNotificationManager()
        } else {
            startForeground(NOTIF_ID, buildNotification())
        }
    }

    private fun contentPendingIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0,
        Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun ensureChannel(id: String, importance: Int) {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(id) == null) {
            nm.createNotificationChannel(
                NotificationChannel(id, "正在播放", importance).apply {
                    description = "显示当前歌曲信息，并提供上一首 / 播放暂停 / 下一首控制"
                    setShowBadge(false)
                    // 媒体通知不该发声/震动：控制按键的反馈由播放器自己负责。
                    // 注意通道属性只在首次创建时生效，老安装升级后仍沿用原来的配置。
                    setSound(null, null)
                    enableVibration(false)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                },
            )
        }
    }

    /**
     * PNR 的文案/封面来源。
     * 封面复用 [requestArtwork] 的 Coil 缓存；未命中时先返回 null，图到了用
     * [PlayerNotificationManager.BitmapCallback.onBitmap] 异步补上。
     */
    private inner class WearDescriptionAdapter : PlayerNotificationManager.MediaDescriptionAdapter {
        override fun getCurrentContentTitle(player: Player): CharSequence =
            PlayerEngine.current?.title ?: "NetMusicLite"

        override fun createCurrentContentIntent(player: Player): PendingIntent =
            contentPendingIntent()

        override fun getCurrentContentText(player: Player): CharSequence? =
            PlayerEngine.current?.artist

        override fun getCurrentSubText(player: Player): CharSequence? =
            PlayerEngine.current?.album

        override fun getCurrentLargeIcon(
            player: Player,
            callback: PlayerNotificationManager.BitmapCallback,
        ): Bitmap? {
            val song = PlayerEngine.current ?: return null
            val key = artworkKey(song)
            if (key == artKey) return artBitmap
            requestArtwork(song, key) { bitmap -> if (bitmap != null) callback.onBitmap(bitmap) }
            return null
        }
    }

    /**
     * 通知封面：同步取缓存，未命中就交给 Coil 异步解码，完成后原地刷新通知。
     *
     * 为什么不在主线程同步解码：封面可达数百 KB，网络图还要等下载/解码，通知构建是主线程路径
     * （onStartCommand / startForeground），在这里做 I/O 会直接卡住表盘滑动。
     * 为什么用静态缓存：暂停会销毁服务，恢复时新实例重新建通知 —— 缓存留在 companion
     * 里可避免每次续播都闪一下无封面。
     */
    private fun notificationArt(song: Song): Bitmap? {
        val key = artworkKey(song)
        if (key == artKey) return artBitmap
        if (key == artLoadingKey || key == artFailedKey) return null
        requestArtwork(song, key) { /* 标准通知由 finishArt 统一刷新 */ }
        return null
    }

    private fun artworkKey(song: Song) = "${song.id}:${song.coverUrl.orEmpty()}"

    /**
     * 统一的封面加载入口：标准通知、Wear 的 PNR 适配器都走这里。
     * 同一首歌并发请求会合并成一次网络/解码，回调按顺序在主线程派发。
     */
    private fun requestArtwork(song: Song, key: String, onResult: (Bitmap?) -> Unit) {
        if (key == artKey && artBitmap != null) {
            onResult(artBitmap)
            return
        }
        if (key == artFailedKey) {
            onResult(null)
            return
        }
        synchronized(artCallbacks) {
            if (key == artLoadingKey) {
                artCallbacks.getOrPut(key) { mutableListOf() }.add(onResult)
                return
            }
            artLoadingKey = key
            artCallbacks[key] = mutableListOf(onResult)
        }
        val model = runCatching { PlayerEngine.coverUriFor(song) }.getOrNull()
        if (model == null) {
            artHandler.post { finishArt(key, null) }
            return
        }
        val request = ImageRequest.Builder(applicationContext)
            .data(model)
            .size(512)            // 通知大图上限约 64dp@xxxhdpi，512 足够且省内存
            .allowHardware(false) // Notification 大图必须是软件位图
            .listener(
                onSuccess = { _, result ->
                    val bitmap = (result.drawable as? BitmapDrawable)?.bitmap
                    artHandler.post { finishArt(key, bitmap) }
                },
                onError = { _, _ ->
                    artHandler.post { finishArt(key, null) }
                },
            )
            .build()
        artLoader.enqueue(request)
    }

    private fun finishArt(key: String, bitmap: Bitmap?) {
        val callbacks: List<(Bitmap?) -> Unit>
        synchronized(artCallbacks) {
            artLoadingKey = null
            if (bitmap != null) {
                artKey = key
                artBitmap = bitmap
                artFailedKey = null
            } else {
                // 失败也记下来，避免每次播放/暂停都重新发起同一个坏链接
                artFailedKey = key
            }
            callbacks = artCallbacks.remove(key) ?: emptyList()
        }
        callbacks.forEach { it(bitmap) }
        // 非 Wear 的标准通知：图到了原地刷新（Wear 由 PNR 的 BitmapCallback 自己更新）
        if (bitmap != null && !isWearOs && PlayerEngine.playbackActive) {
            instance?.let { runCatching { it.startForeground(NOTIF_ID, it.buildNotification()) } }
        }
    }

    companion object {
        private const val CHANNEL_ID = "playback"
        private const val CHANNEL_ID_WATCH = "playback_watch"
        private const val CHANNEL_ID_WEAR = "playback_wear"
        private const val NOTIF_ID = 42

        // 前四个沿用 1.32 既有取值，保证升级安装后旧 PendingIntent 仍能落到同一分支
        const val ACTION_START = "com.ncm.watch.playback.START"
        const val ACTION_PLAY = "com.ncm.watch.playback.PLAY"
        const val ACTION_PAUSE = "com.ncm.watch.playback.PAUSE"
        const val ACTION_NEXT = "com.ncm.watch.playback.NEXT"
        const val ACTION_PREV = "com.ncm.watch.playback.PREV"
        const val ACTION_STOP = "com.ncm.watch.playback.STOP"

        /** 运行中服务实例（同进程直引用：通知原地刷新用） */
        @Volatile
        private var instance: PlaybackService? = null

        // 封面缓存：键 = 歌曲 id + 封面地址。服务暂停会被销毁，恢复时新实例靠这里避免闪白。
        @Volatile private var artKey: String? = null
        @Volatile private var artBitmap: Bitmap? = null
        @Volatile private var artLoadingKey: String? = null
        @Volatile private var artFailedKey: String? = null

        // 同一首歌的并发封面请求回调（标准通知 + Wear PNR 适配器），只发一次网络/解码
        private val artCallbacks = mutableMapOf<String, MutableList<(Bitmap?) -> Unit>>()

        /**
         * PlayerEngine 在播放意图出现/歌曲切换时调用（主线程）。
         * 服务已运行：同进程直调 startForeground 原地刷新通知 —— 不经 start 系调用，
         * 后台自动切歌的路径也放行（31+ 的后台 FGS 启动限制只拦「启动」，不拦「已运行」）。
         * 未运行：startForegroundService 拉起（正常都在用户前台操作时发生）；极端时序
         * （蓝牙耳机从后台唤起播放）可能被系统拒绝，捕获后仅本次失去保护，不影响出声。
         */
        fun ensureStarted(ctx: Context) {
            instance?.let { s ->
                if (PlayerEngine.playbackActive) s.refreshNotification()
                return
            }
            runCatching {
                ctx.startForegroundService(Intent(ctx, PlaybackService::class.java).setAction(ACTION_START))
            }
        }

        /** 暂停/空闲即撤：stopService 对运行中前台服务无后台限制；未运行时为无害 no-op */
        fun stop(ctx: Context) {
            runCatching { ctx.stopService(Intent(ctx, PlaybackService::class.java)) }
        }
    }
}
