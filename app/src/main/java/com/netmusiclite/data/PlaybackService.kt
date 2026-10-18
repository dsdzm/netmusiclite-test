package com.netmusiclite.data

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
import coil.ImageLoader
import coil.request.ImageRequest
import com.netmusiclite.MainActivity
import com.netmusiclite.R

/**
 * 播放保活前台服务（foregroundServiceType=mediaPlayback）。
 *
 * ★「播放中被杀后台」根因（2026-09-26）：全应用此前没有任何前台服务 —— ExoPlayer 跑在
 *   Activity 进程里，退到桌面/熄屏后进程被降为缓存进程，系统内存紧张时直接回收，播放中断。
 *   修法：PlayerEngine 在有播放意图（播放中/缓冲中）时经 [ensureStarted] 拉起本服务
 *   startForeground，把整个进程提升到前台服务优先级（ExoPlayer 与本服务同进程，一并受保护）；
 *   暂停/空闲即撤 —— 播放进度/队列随 player_state.json 持续落盘，进程被杀重进可续播。
 *   MediaSession 仍由 PlayerEngine 持有（手表表盘媒体控件/耳机线控不变），
 *   通知栏按键经 onStartCommand 直接驱动 PlayerEngine。Android 10+ 用 MediaStyle
 *   关联同一会话；Android 9 及以下改用自定义 RemoteViews 紧凑控制条（部分手表
 *   通知中心不渲染 MediaStyle 的 action）。
 */
class PlaybackService : Service() {

    /** 通知封面专用解码器：关闭硬件位图（Notification 大图需软件 Bitmap） */
    private val artLoader by lazy {
        ImageLoader.Builder(applicationContext).allowHardware(false).build()
    }
    private val artHandler by lazy { Handler(Looper.getMainLooper()) }

    /** 手表判定：Wear OS 与部分国产手表不声明 FEATURE_WATCH，再看 uiMode 与小屏宽度 */
    private val isWatchDevice: Boolean
        get() = packageManager.hasSystemFeature(PackageManager.FEATURE_WATCH) ||
            (resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK) ==
            Configuration.UI_MODE_TYPE_WATCH ||
            resources.configuration.smallestScreenWidthDp <= 240

    /**
     * Android 9 及以下的兼容通道：这类设备多为老手表/定制 ROM，
     * 通知中心会把 MediaStyle 渲染成「只有封面+歌名的媒体卡」，并丢弃 action 按钮。
     * 这类设备改用自定义 RemoteViews 紧凑控制条（封面 + 歌名 + 切歌/暂停/关闭）。
     */
    private val legacyCompact: Boolean
        get() = Build.VERSION.SDK_INT <= 28

    /**
     * 手表单独用一条渠道，重要性 DEFAULT：
     * 部分手表通知中心会把 IMPORTANCE_LOW 的渠道折叠掉，媒体通知就完全看不见。
     */
    private val channelId: String
        get() = if (legacyCompact || isWatchDevice) CHANNEL_ID_WATCH else CHANNEL_ID

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY -> PlayerEngine.playAction()
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
            startForeground(NOTIF_ID, buildNotification())
        } else {
            // ★ 2026-09-30 修「点播放后马上暂停 → 进程被杀」：
            //   ensureStarted() 判定播放意图成立时才调 startForegroundService()，但意图可能在
            //   本回调执行前就翻转（用户立刻暂停 / 起播失败撤销 fgWanted / 队列被替换）——
            //   旧版这时直接 stopForeground+stopSelf，从未调用 startForeground()，系统在 5s 后
            //   抛 RemoteServiceException: "Context.startForegroundService() did not then call
            //   Service.startForeground()" 杀掉整个进程（真机实测必崩，crash buffer 可查）。
            //   契约只对 startForegroundService 拉起的这次启动（本类走 ACTION_START）生效：
            //   先补一次 startForeground 满足契约，再立刻撤回（通知一闪即消，无残留）。
            if (intent?.action == ACTION_START) {
                startForeground(NOTIF_ID, buildNotification())
            }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun buildNotification(): Notification {
        val chId = channelId
        getSystemService(NotificationManager::class.java).let { nm ->
            if (nm.getNotificationChannel(chId) == null) {
                val importance = if (legacyCompact || isWatchDevice) {
                    // 手表/老设备：DEFAULT 才不会被部分通知中心折叠；声音/震动仍关闭
                    NotificationManager.IMPORTANCE_DEFAULT
                } else {
                    NotificationManager.IMPORTANCE_LOW
                }
                nm.createNotificationChannel(
                    NotificationChannel(chId, "正在播放", importance).apply {
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
        val song = PlayerEngine.current
        val playing = PlayerEngine.isPlaying
        val contentPi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
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
                Icon.createWithResource(this, R.drawable.ic_notif_prev), "上一首", cmd(ACTION_PREV)).build())
            .addAction(Notification.Action.Builder(
                Icon.createWithResource(
                    this, if (playing) R.drawable.ic_notif_pause else R.drawable.ic_notif_play),
                if (playing) "暂停" else "播放",
                cmd(if (playing) ACTION_PAUSE else ACTION_PLAY),
            ).build())
            .addAction(Notification.Action.Builder(
                Icon.createWithResource(this, R.drawable.ic_notif_next), "下一首", cmd(ACTION_NEXT)).build())
        val art = song?.let { notificationArt(it) }
        if (song != null) {
            // 专辑名放 subText（展开态显示），小屏上不挤占歌手行
            if (song.album.isNotBlank()) builder.setSubText(song.album)
            // 封面大图：首次构建时通常还没解码完，先出文字版，图到了再原地刷新
            art?.let { builder.setLargeIcon(it) }
        }
        if (legacyCompact) {
            // ★ 紧凑控制条（2026-10-18）：
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
                rv.setImageViewResource(R.id.notif_cover, R.mipmap.ic_launcher)
            }
            rv.setImageViewResource(
                R.id.notif_toggle,
                if (playing) R.drawable.ic_notif_pause else R.drawable.ic_notif_play,
            )
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

    /**
     * 通知封面：同步取缓存，未命中就交给 Coil 异步解码，完成后原地刷新通知。
     *
     * 为什么不在主线程同步解码：封面可达数百 KB，网络图还要等下载/解码，通知构建是主线程路径
     * （onStartCommand / startForeground），在这里做 I/O 会直接卡住表盘滑动。
     * 为什么用静态缓存：暂停会销毁服务，恢复时新实例重新建通知 —— 缓存留在 companion
     * 里可避免每次续播都闪一下无封面。
     */
    private fun notificationArt(song: Song): Bitmap? {
        val key = "${song.id}:${song.coverUrl.orEmpty()}"
        if (key == artKey) return artBitmap
        if (key == artLoadingKey || key == artFailedKey) return null
        val model = PlayerEngine.artworkModelFor(song) ?: return null
        artLoadingKey = key
        val request = ImageRequest.Builder(applicationContext)
            .data(model)
            .size(512)            // 通知大图上限约 64dp@xxxhdpi，512 足够且省内存
            .allowHardware(false) // Notification 大图必须是软件位图
            .listener(
                onSuccess = { _, result ->
                    val bitmap = (result.drawable as? BitmapDrawable)?.bitmap
                    artHandler.post {
                        artLoadingKey = null
                        if (bitmap != null) {
                            artKey = key
                            artBitmap = bitmap
                            // 图到时若已切歌/已暂停，就不要把旧图刷到新状态上
                            if (PlayerEngine.current?.id == song.id && PlayerEngine.playbackActive) {
                                instance?.let { runCatching { it.startForeground(NOTIF_ID, it.buildNotification()) } }
                            }
                        } else {
                            artFailedKey = key
                        }
                    }
                },
                onError = { _, _ ->
                    artHandler.post {
                        artLoadingKey = null
                        // 失败也记下来，避免每次播放/暂停都重新发起同一个坏链接
                        artFailedKey = key
                    }
                },
            )
            .build()
        artLoader.enqueue(request)
        return null
    }

    companion object {
        private const val CHANNEL_ID = "playback"
        private const val CHANNEL_ID_WATCH = "playback_watch"
        private const val NOTIF_ID = 42
        const val ACTION_START = "com.netmusiclite.playback.START"
        const val ACTION_PLAY = "com.netmusiclite.playback.PLAY"
        const val ACTION_PAUSE = "com.netmusiclite.playback.PAUSE"
        const val ACTION_NEXT = "com.netmusiclite.playback.NEXT"
        const val ACTION_PREV = "com.netmusiclite.playback.PREV"
        const val ACTION_STOP = "com.netmusiclite.playback.STOP"

        /** 运行中服务实例（同进程直引用：通知原地刷新用） */
        @Volatile
        private var instance: PlaybackService? = null

        // 封面缓存：键 = 歌曲 id + 封面地址。服务暂停会被销毁，恢复时新实例靠这里避免闪白。
        @Volatile private var artKey: String? = null
        @Volatile private var artBitmap: Bitmap? = null
        @Volatile private var artLoadingKey: String? = null
        @Volatile private var artFailedKey: String? = null

        /**
         * PlayerEngine 在播放意图出现/歌曲切换时调用（主线程）。
         * 服务已运行：同进程直调 startForeground 原地刷新通知 —— 不经 start 系调用，
         * 后台自动切歌的路径也放行（31+ 的后台 FGS 启动限制只拦「启动」，不拦「已运行」）。
         * 未运行：startForegroundService 拉起（正常都在用户前台操作时发生）；极端时序
         * （蓝牙耳机从后台唤起播放）可能被系统拒绝，捕获后仅本次失去保护，不影响出声。
         */
        fun ensureStarted(ctx: Context) {
            instance?.let { s ->
                if (PlayerEngine.playbackActive) s.startForeground(NOTIF_ID, s.buildNotification())
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
