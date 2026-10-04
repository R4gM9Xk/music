package com.local.music.player

import android.content.Intent
import android.media.audiofx.Equalizer
import android.os.Bundle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * 前台播放服务：ExoPlayer + MediaSession 在此托管。
 * - 播放开始即自动进入前台 + 媒体通知（media3 DefaultMediaNotificationProvider）
 * - 锁屏媒体控制 / ColorOS 流体云胶囊读取的就是这个系统媒体会话
 * - 整条播放队列在服务端播放器上：退后台、锁屏、界面销毁后完播仍自动切换
 * - 均衡器：android.media.audiofx.Equalizer 挂在播放器音频会话上，预设经自定义命令下发
 */
class MusicPlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private var equalizer: Equalizer? = null
    private var lastPreset = "Off" // 最近一次请求的预设（音频会话就绪后重放）

    override fun onCreate() {
        super.onCreate()
        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true) // 拔耳机自动暂停
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
        mediaSession = MediaSession.Builder(this, player)
            .setCallback(
                object : MediaSession.Callback {
                    override fun onCustomCommand(
                        session: MediaSession,
                        controller: MediaSession.ControllerInfo,
                        customCommand: SessionCommand,
                        args: Bundle,
                    ): ListenableFuture<SessionResult> {
                        if (customCommand.customAction == CMD_SET_EQ) {
                            applyEqPreset(args.getString(KEY_PRESET))
                        }
                        return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                    }
                },
            )
            .build()
        // 音频会话 id 在解码器首次初始化（首个 READY）才有值：到点补挂均衡器并重放最后预设
        player.addListener(
            object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_READY) {
                        runCatching { ensureEqualizer(player.audioSessionId) }
                        applyEqPreset(lastPreset)
                    }
                }
            },
        )
        // 均衡器挂播放器音频会话；音频会话未就绪时由首次 EQ 命令/首个 READY 延迟创建
        runCatching { ensureEqualizer(player.audioSessionId) }
    }

    /** 按预设名应用频段增益（5 段增益表 → 实际频段数线性重采样）；Off = 关闭音效 */
    private fun applyEqPreset(presetName: String?) {
        if (presetName != null) lastPreset = presetName
        runCatching { ensureEqualizer(mediaSession?.player?.audioSessionId ?: 0) }
        val eq = equalizer ?: return
        val gains = eqGainsFor(presetName)
        if (gains == null) {
            runCatching { eq.enabled = false }
            return
        }
        runCatching {
            val bands = eq.numberOfBands
            val range = eq.bandLevelRange
            for (band in 0 until bands) {
                val t = if (bands <= 1) 0f else band.toFloat() * (GAIN_STEPS - 1) / (bands - 1)
                val i = t.toInt().coerceIn(0, GAIN_STEPS - 1)
                val j = (i + 1).coerceAtMost(GAIN_STEPS - 1)
                val db = gains[i] + (gains[j] - gains[i]) * (t - i)
                val level = (db * 100).toInt().coerceIn(range[0].toInt(), range[1].toInt())
                eq.setBandLevel(band.toShort(), level.toShort())
            }
            eq.enabled = true
        }
    }

    /** 音频会话 id 就绪后创建均衡器（幂等） */
    private fun ensureEqualizer(audioSessionId: Int) {
        if (equalizer != null || audioSessionId <= 0) return
        equalizer = Equalizer(0, audioSessionId)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        runCatching { equalizer?.release() }
        equalizer = null
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }

    companion object {
        /** UI → 服务的均衡器预设命令（MediaControllerEngine.setEqPreset 经 MediaSession 下发） */
        const val CMD_SET_EQ = "com.local.music.SET_EQ_PRESET"
        const val KEY_PRESET = "preset"

        private const val GAIN_STEPS = 5

        /** 5 频段（低→高）增益表，单位 dB，幅度控制在 ±6 内 */
        private fun eqGainsFor(presetName: String?): FloatArray? = when (presetName) {
            "Pop" -> floatArrayOf(2f, 4f, 2f, 0f, 1f)
            "Rock" -> floatArrayOf(4f, 2f, -1f, 2f, 3f)
            "Classic" -> floatArrayOf(3f, 1f, 0f, 2f, 3f)
            "Vocal" -> floatArrayOf(-2f, 0f, 4f, 2f, -1f)
            else -> null // Off / 未知预设：禁用
        }
    }
}
