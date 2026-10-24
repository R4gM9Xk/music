package com.local.music.player

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.local.music.core.ui.state.PlayerEngine
import com.local.music.core.ui.state.SongFormat
import com.local.music.core.ui.state.SongSpec

/**
 * 引擎实现：MediaController 代理——真正的 ExoPlayer 活在 MusicPlaybackService 里，
 * 通知 / 锁屏 / 流体云 / 后台播放由 MediaSessionService 统一承担。
 * 整条队列经 setMediaItems 下发（稳定 mediaId），循环 / 随机用播放器原生模式；
 * 全部调用异步转发，播放状态经 Player.Listener 回流 AppState。
 */
class MediaControllerEngine(private val controller: MediaController) : PlayerEngine {

    override var onPlayingChanged: ((Boolean) -> Unit)? = null
    override var onFormatChanged: ((SongFormat?) -> Unit)? = null
    override var onMediaItemChanged: ((String?) -> Unit)? = null
    override var onError: ((String) -> Unit)? = null

    /** 断连已上报标记（重连后清位，避免每个调用都重复刷错误横幅） */
    private var reportedDisconnected = false

    init {
        controller.addListener(
            object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    onPlayingChanged?.invoke(isPlaying)
                }

                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    onMediaItemChanged?.invoke(mediaItem?.mediaId)
                }

                override fun onPlayerError(error: PlaybackException) {
                    onError?.invoke(
                        when (error.errorCode) {
                            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
                            PlaybackException.ERROR_CODE_IO_NO_PERMISSION,
                            PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE,
                            -> "音频文件已丢失或不可读"

                            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
                            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
                            PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
                            PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
                            PlaybackException.ERROR_CODE_DECODING_FAILED,
                            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
                            -> "音频格式不支持播放"

                            else -> "播放出错（${error.errorCodeName}）"
                        },
                    )
                }

                override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                    onFormatChanged?.invoke(readAudioFormat())
                }
            },
        )
    }

    /** 当前选中音轨的实时参数（解码输出 PCM 编码即位深来源） */
    private fun readAudioFormat(): SongFormat? {
        val format = controller.currentTracks.groups
            .firstOrNull { it.isSelected && it.type == C.TRACK_TYPE_AUDIO }
            ?.getTrackFormat(0) ?: return null
        val mime = format.sampleMimeType ?: return null
        val bitDepth = if (format.pcmEncoding != androidx.media3.common.Format.NO_VALUE) {
            when (format.pcmEncoding) {
                android.media.AudioFormat.ENCODING_PCM_8BIT -> 8
                android.media.AudioFormat.ENCODING_PCM_16BIT -> 16
                android.media.AudioFormat.ENCODING_PCM_24BIT_PACKED -> 24
                android.media.AudioFormat.ENCODING_PCM_32BIT,
                android.media.AudioFormat.ENCODING_PCM_FLOAT,
                -> 32
                else -> 0
            }
        } else {
            0
        }
        return SongFormat(
            codec = MediaLibrary.codecFromMime(mime),
            sampleRateHz = format.sampleRate.takeIf { it > 0 } ?: 0,
            bitDepth = bitDepth,
            bitrateKbps = format.bitrate.takeIf { it > 0 }?.div(1000) ?: 0,
        )
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

    /**
     * 断连检测：MediaController.Listener 只能在 Builder 注册，而引擎拿到的是成品 controller，
     * 无处挂 onDisconnected——改用轻量心跳：play/pause/seek/skip/positionSec 等入口检查 isConnected，
     * 断连即上报并置位（重连后清位）；tick 周期调 positionSec，断连一个心跳内可见，
     * UI 走既有错误横幅而非假死。返回 false = 已断连（调用方 no-op 返回）。
     */
    private fun checkConnected(): Boolean {
        if (controller.isConnected) {
            reportedDisconnected = false
            return true
        }
        if (!reportedDisconnected) {
            reportedDisconnected = true
            onError?.invoke("播放服务连接已断开")
        }
        return false
    }

    /** 整条队列下发给服务端播放器：起始位置随 setMediaItems 原子下发（独立 seekTo 会在装载窗口被丢弃） */
    override fun loadQueue(specs: List<SongSpec>, startIndex: Int, startSec: Float, autoPlay: Boolean) {
        val items = specs.map { it.toMediaItem() }
        val startPositionMs = if (startSec > 0f) (startSec * 1000).toLong() else C.TIME_UNSET
        controller.setMediaItems(
            items,
            /* startIndex = */ startIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0)),
            /* startPositionMs = */ startPositionMs,
        )
        controller.prepare()
        controller.playWhenReady = autoPlay
    }

    override fun setRepeatMode(repeatAll: Boolean, repeatOne: Boolean) {
        controller.repeatMode = when {
            repeatOne -> Player.REPEAT_MODE_ONE
            repeatAll -> Player.REPEAT_MODE_ALL
            else -> Player.REPEAT_MODE_OFF
        }
    }

    override fun setShuffleMode(enabled: Boolean) {
        controller.shuffleModeEnabled = enabled
    }

    /** 均衡器预设：经自定义命令下发服务端（android.media.audiofx.Equalizer） */
    override fun setEqPreset(presetName: String) {
        runCatching {
            val future = controller.sendCustomCommand(
                SessionCommand(MusicPlaybackService.CMD_SET_EQ, Bundle.EMPTY),
                Bundle().apply { putString(MusicPlaybackService.KEY_PRESET, presetName) },
            )
            // 异步结果留痕：EQ 曾静默失效（历史坑），失败只打日志供诊断、不打扰用户
            future.addListener(
                {
                    val result = runCatching { future.get() }.getOrNull()
                    if (result == null || result.resultCode != SessionResult.RESULT_SUCCESS) {
                        android.util.Log.w(
                            "MediaControllerEngine",
                            "自定义命令下发失败 cmd=${MusicPlaybackService.CMD_SET_EQ} " +
                                "resultCode=${result?.resultCode}",
                        )
                    }
                },
                Runnable::run, // 直接执行器：仅记日志，无需线程切换
            )
        }
    }

    override fun play() {
        if (checkConnected()) controller.play()
    }

    override fun pause() {
        if (checkConnected()) controller.pause()
    }

    override fun seekTo(sec: Float) {
        if (checkConnected()) controller.seekTo((sec * 1000).toLong())
    }

    override fun skipTo(index: Int) {
        if (checkConnected()) controller.seekToDefaultPosition(index)
    }

    override fun skipToNext() {
        if (checkConnected()) controller.seekToNextMediaItem()
    }

    override fun skipToPrevious() {
        if (checkConnected()) controller.seekToPreviousMediaItem()
    }

    override fun hasNextMediaItem(): Boolean = controller.hasNextMediaItem()

    override fun hasPreviousMediaItem(): Boolean = controller.hasPreviousMediaItem()

    // 增量队列编辑：MediaController 原生 API 即可原地改队列，当前曲不受影响；
    // move 语义（toIndex 以移除后的列表坐标计）与 AppState 侧 Kotlin removeAt/add 一致。
    // shuffle 开启时插入走服务端命令（DefaultShuffleOrder 会把新项随机放置，
    // 服务端重建播放序保证「下一首播放 = 紧跟当前曲、最后播放 = 队尾」；
    // pinLast 由调用方显式传入意图——不再用 index >= mediaItemCount 猜位置，
    // 当前曲在队尾时「下一首播放」曾被误判成「最后播放」）
    override fun addQueueItems(index: Int, specs: List<SongSpec>, pinLast: Boolean) {
        if (controller.shuffleModeEnabled && specs.isNotEmpty()) {
            runCatching {
                controller.sendCustomCommand(
                    SessionCommand(MusicPlaybackService.CMD_INSERT_SHUFFLE_FIX, Bundle.EMPTY),
                    Bundle().apply {
                        putInt(MusicPlaybackService.KEY_INDEX, index)
                        putSerializable(MusicPlaybackService.KEY_SPECS, ArrayList(specs))
                        putString(
                            MusicPlaybackService.KEY_PIN_MODE,
                            if (pinLast) MusicPlaybackService.PIN_LAST else MusicPlaybackService.PIN_NEXT,
                        )
                    },
                )
            }.onFailure { controller.addMediaItems(index, specs.map { it.toMediaItem() }) }
        } else {
            controller.addMediaItems(index, specs.map { it.toMediaItem() })
        }
    }

    override fun moveQueueItem(fromIndex: Int, toIndex: Int) = controller.moveMediaItem(fromIndex, toIndex)

    override fun removeQueueItems(fromIndex: Int, toIndex: Int) {
        controller.removeMediaItems(fromIndex, toIndex)
    }

    override fun currentMediaId(): String? = controller.currentMediaItem?.mediaId

    override fun mediaIds(): List<String> =
        (0 until controller.mediaItemCount).map { controller.getMediaItemAt(it).mediaId }

    override fun isEnginePlaying(): Boolean = controller.isPlaying

    override fun positionSec(): Float {
        checkConnected() // 心跳入口：断连在此上报；返回值仍取 controller 缓存的当前进度
        return (controller.currentPosition.coerceAtLeast(0)) / 1000f
    }

    override fun durationSec(): Float {
        val d = controller.duration
        return if (d > 0) d / 1000f else 0f
    }

    /** 停止并清空装载（切到演示曲目 / 服务残留失效曲目时用） */
    override fun stopAndClear() {
        controller.pause()
        controller.clearMediaItems()
    }

    override fun release() {
        controller.release()
    }
}
