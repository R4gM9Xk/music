package com.local.music.player

import android.content.Intent
import android.media.audiofx.Equalizer
import android.net.Uri
import android.os.Bundle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ShuffleOrder
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.local.music.core.ui.state.SongSpec

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
    private var eqSessionId = 0 // 均衡器绑定的音频会话（设备切换会变化，需重建）
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
                    // 自定义命令必须在此声明，否则控制器 sendCustomCommand 被拒（EQ 预设 / shuffle 插入修正共用此通道）
                    override fun onConnect(
                        session: MediaSession,
                        controller: MediaSession.ControllerInfo,
                    ): MediaSession.ConnectionResult {
                        val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                            .add(SessionCommand(CMD_SET_EQ, Bundle.EMPTY))
                            .add(SessionCommand(CMD_INSERT_SHUFFLE_FIX, Bundle.EMPTY))
                            .build()
                        return MediaSession.ConnectionResult.accept(
                            sessionCommands,
                            MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS,
                        )
                    }

                    override fun onCustomCommand(
                        session: MediaSession,
                        controller: MediaSession.ControllerInfo,
                        customCommand: SessionCommand,
                        args: Bundle,
                    ): ListenableFuture<SessionResult> {
                        // 自定义命令仅接受本应用（服务保持 exported 供系统/锁屏会话使用）
                        if (controller.packageName != packageName) {
                            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_PERMISSION_DENIED))
                        }
                        if (customCommand.customAction == CMD_SET_EQ) {
                            applyEqPreset(args.getString(KEY_PRESET))
                        } else if (customCommand.customAction == CMD_INSERT_SHUFFLE_FIX) {
                            @Suppress("DEPRECATION", "UNCHECKED_CAST")
                            val specs = args.getSerializable(KEY_SPECS) as? ArrayList<SongSpec>
                            if (specs != null) {
                                insertWithShuffleFix(
                                    args.getInt(KEY_INDEX, 0),
                                    specs.map { it.toMediaItem() },
                                    args.getString(KEY_PIN_MODE, PIN_NEXT) == PIN_LAST,
                                )
                            }
                        } else {
                            // 服务保持 exported：未知命令返回错误码而非 SUCCESS，暴露真实命令边界
                            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_BAD_VALUE))
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

    /**
     * 插入并修正随机播放顺序：shuffle 开启时 DefaultShuffleOrder 会把新增项随机放置，
     * 「下一首播放/最后播放」因此不成立。这里在插入后重建播放序——
     * next 模式 = 当前曲 → 插入项 → 其余曲目（重新随机）；last 模式 = 当前曲 → 其余 → 插入项。
     * 关闭随机时 ExoPlayer 原生插入即符合窗口序，无需修正。
     */
    private fun insertWithShuffleFix(index: Int, items: List<MediaItem>, pinLast: Boolean) {
        val player = mediaSession?.player ?: return
        if (items.isEmpty()) return
        val current = player.currentMediaItemIndex
        player.addMediaItems(index, items)
        if (!player.shuffleModeEnabled) return
        val exo = player as? ExoPlayer ?: return
        val count = player.mediaItemCount
        val inserted = index.coerceIn(0, count - 1)..(index + items.size - 1).coerceIn(0, count - 1)
        if (current in inserted) return // 防御：插入点不应落在当前曲
        val rest = (0 until count).filter { it != current && it !in inserted }.shuffled(java.util.Random())
        val sequence = if (pinLast) {
            listOf(current) + rest + inserted.toList()
        } else {
            listOf(current) + inserted.toList() + rest
        }
        exo.setShuffleOrder(ShuffleOrder.DefaultShuffleOrder(sequence.toIntArray(), System.nanoTime()))
    }

    private fun SongSpec.toMediaItem(): MediaItem =
        MediaItem.Builder()
            .setMediaId(id)
            .setUri(uri)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setArtist(artist)
                    .setAlbumTitle(album)
                    .setArtworkUri(artworkUri?.let(Uri::parse))
                    .build(),
            )
            .build()

    /** 按预设名应用频段增益（5 段增益表 → 实际频段数线性重采样）；Off = 关闭音效 */    private fun applyEqPreset(presetName: String?) {
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

    /** 音频会话 id 就绪后创建均衡器（幂等；会话 id 变化时重建并重放预设） */
    private fun ensureEqualizer(audioSessionId: Int) {
        if (audioSessionId <= 0) return
        if (equalizer != null && eqSessionId == audioSessionId) return
        runCatching { equalizer?.release() }
        equalizer = runCatching { Equalizer(0, audioSessionId) }.getOrNull()
        eqSessionId = if (equalizer != null) audioSessionId else 0
        if (equalizer != null) applyEqPreset(lastPreset)
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
            release() // 先释放 session（解除控制器/通知绑定），再释放底层播放器
            player.release()
        }
        mediaSession = null
        super.onDestroy()
    }

    companion object {
        /** UI → 服务的均衡器预设命令（MediaControllerEngine.setEqPreset 经 MediaSession 下发） */
        const val CMD_SET_EQ = "com.local.music.SET_EQ_PRESET"
        const val KEY_PRESET = "preset"

        /** UI → 服务的队列插入命令：shuffle 下修正随机播放序（下一首播放/最后播放用） */
        const val CMD_INSERT_SHUFFLE_FIX = "com.local.music.INSERT_SHUFFLE_FIX"
        const val KEY_SPECS = "specs"
        const val KEY_INDEX = "index"
        const val KEY_PIN_MODE = "pinMode"
        const val PIN_NEXT = "next"
        const val PIN_LAST = "last"

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
