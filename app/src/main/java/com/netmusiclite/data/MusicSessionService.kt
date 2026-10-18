package com.netmusiclite.data

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.Player
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/**
 * 系统媒体界面接入点（2026-10-01 对齐 OPPO 手表音乐包 `com.allsaints.wearmusic`）。
 *
 * ## 为什么需要它
 * NetMusicLite 早前已注册**平台** `android.media.session.MediaSession`（供 AVRCP 蓝牙按键与系统
 * 媒体面板），但 ColorOS Watch 的表盘音乐卡片 / 「正在播放」页**不认**那条通道 ——
 * 系统是按 action `androidx.media3.session.MediaSessionService` 去扫服务的
 * （实测 `pm query-services -a androidx.media3.session.MediaSessionService`
 * 只列出 OPPO 示例包、没有本 App）。本类就是补上这个「锚点」。
 *
 * ## 所有权边界（关键）
 * ExoPlayer 归 [PlayerEngine] 所有，本服务只**借用引用**：
 *  - 绝不 `player.release()`（只在 [onDestroy] 里 release 自己的会话）
 *  - 不建第二条播放链路，系统 UI 的操作全部翻译回 [PlayerEngine] 的业务方法
 *
 * ## 与既有平台会话共存
 * 两条会话读的是同一个 Player、写的是同一套业务方法（[PlayerEngine.playAction] /
 * [pauseAction] / [next] / [prev] / [seekTo]），因此谁被系统选中行为都一致，
 * 不存在「抢按键」问题。
 */
class MusicSessionService : MediaSessionService() {

    private var session: MediaSession? = null

    /**
     * 系统/外部控制器连接时由此拿到会话。
     * ★ Media3 要求本方法在主线程返回；`PlayerEngine` 的 ExoPlayer 也是主线程 Looper 构建的，
     *   两边一致才能建会话。
     */
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        obtainSession()

    /**
     * 幂等取会话。系统可能在本 App 尚未预热播放器时就 bind 过来
     * （例如用户先点系统媒体卡片），此时 [PlayerEngine.sessionPlayerOrNull] 会用
     * 传入的 Context 补一次初始化并同步建播放器，语义等价于冷启动后直接点歌。
     */
    private fun obtainSession(): MediaSession? {
        session?.let { return it }
        val player: Player = PlayerEngine.sessionPlayerOrNull(this) ?: return null
        return MediaSession.Builder(this, EnginePlayer(player))
            .setSessionActivity(openAppIntent())
            .build()
            .also { session = it }
    }

    /** 点击系统媒体卡片/通知回到 App（OPPO 示例包漏了这步，点了没反应） */
    private fun openAppIntent(): PendingIntent {
        val intent = Intent(this, com.netmusiclite.MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /**
     * ★ 故意**只覆盖单参重载、且不调 super** —— 这是让 Media3 完全不发通知的开关。
     *
     * Media3 1.5.1 的字节码实现是：
     * ```
     * public void onUpdateNotification(MediaSession s) { defaultMethodCalled = true; }   // 默认体
     * public void onUpdateNotification(MediaSession s, boolean startInForegroundRequired) {
     *     onUpdateNotification(s);                       // 覆盖后这里进的是空实现
     *     if (defaultMethodCalled) mediaNotificationManager.updateNotification(s, ...);
     * }
     * ```
     * 覆盖成空实现 → `defaultMethodCalled` 永远为 false → MediaNotificationManager 一次都不会被调，
     * 既不发通知也不拉前台。保活与媒体通知统一由 [PlaybackService] 负责
     * （非 Wear：MediaStyle / Android 9 RemoteViews；Wear OS：PlayerNotificationManager），
     * 否则手表上会出现两条一模一样的媒体通知。
     */
    override fun onUpdateNotification(session: MediaSession) = Unit

    override fun onDestroy() {
        session?.release()
        session = null
        super.onDestroy()
        // 注意：ExoPlayer 不在这里 release —— 它归 PlayerEngine，服务只借了引用
    }
}
