package com.netmusiclite.data

import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player

/**
 * 把系统/通知下发的 Player 命令翻译回 [PlayerEngine] 的业务动作。
 *
 * 为什么不能直接让 Media3 / PlayerNotificationManager 操作 ExoPlayer：
 * NetMusicLite 的队列、播放模式、一起听协调全在 PlayerEngine 里，而 ExoPlayer
 * 每次只 `setMediaItem` 载入**一首**（见 `PlayerEngine.load`），直接
 * `seekToNextMediaItem()` 只会原地不动。
 *
 * 使用方：
 *  - [MusicSessionService]：系统媒体界面的 Media3 会话
 *  - [PlaybackService]：Wear OS 的 PlayerNotificationManager
 */
internal class EnginePlayer(delegate: Player) : ForwardingPlayer(delegate) {

    override fun play() = PlayerEngine.playAction()

    override fun pause() = PlayerEngine.pauseAction()

    /** 系统可能直接写 playWhenReady（媒体键/局域网控制器），同样走业务路径 */
    override fun setPlayWhenReady(playWhenReady: Boolean) {
        if (playWhenReady) play() else pause()
    }

    override fun seekTo(positionMs: Long) = PlayerEngine.seekTo(positionMs)

    // 上/下一首：新老控制器的命令 ID 不同，四个入口都要接
    override fun seekToNext() = PlayerEngine.next()
    override fun seekToPrevious() = PlayerEngine.prev()
    override fun seekToNextMediaItem() = PlayerEngine.next()
    override fun seekToPreviousMediaItem() = PlayerEngine.prev()

    /** 系统媒体面板的关闭键：与平台会话的 onStop 保持一致，只暂停不销毁会话 */
    override fun stop() = PlayerEngine.pauseAction()

    /**
     * 追加「上/下一首」命令位：ExoPlayer 里始终只有一首歌（队列在 PlayerEngine），
     * 默认命令集不含这几个位，系统 UI / 通知就不会画出切歌按钮。
     */
    override fun getAvailableCommands(): Player.Commands =
        super.getAvailableCommands().buildUpon()
            .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
            .add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
            .add(Player.COMMAND_SEEK_TO_NEXT)
            .add(Player.COMMAND_SEEK_TO_PREVIOUS)
            .build()
}
