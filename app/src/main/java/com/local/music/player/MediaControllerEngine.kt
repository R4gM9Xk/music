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

    /** 整条队列下发给服务端播放器：起始位置随 setMediaItems 原子下发（独立 seekTo 会在装载窗口被丢弃） */
    override fun loadQueue(specs: List<SongSpec>, startIndex: Int, startSec: Float, autoPlay: Boolean) {
        val items = specs.map { spec ->
            MediaItem.Builder()
                .setMediaId(spec.id)
                .setUri(spec.uri)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(spec.title)
                        .setArtist(spec.artist)
                        .setAlbumTitle(spec.album)
                        .setArtworkUri(spec.artworkUri?.let(Uri::parse))
                        .build(),
                )
                .build()
        }
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
            controller.sendCustomCommand(
                SessionCommand(MusicPlaybackService.CMD_SET_EQ, Bundle.EMPTY),
                Bundle().apply { putString(MusicPlaybackService.KEY_PRESET, presetName) },
            )
        }
    }

    override fun play() = controller.play()

    override fun pause() = controller.pause()

    override fun seekTo(sec: Float) = controller.seekTo((sec * 1000).toLong())

    override fun skipTo(index: Int) = controller.seekToDefaultPosition(index)

    override fun skipToNext() = controller.seekToNextMediaItem()

    override fun skipToPrevious() = controller.seekToPreviousMediaItem()

    override fun hasNextMediaItem(): Boolean = controller.hasNextMediaItem()

    override fun hasPreviousMediaItem(): Boolean = controller.hasPreviousMediaItem()

    override fun currentMediaId(): String? = controller.currentMediaItem?.mediaId

    override fun isEnginePlaying(): Boolean = controller.isPlaying

    override fun positionSec(): Float = (controller.currentPosition.coerceAtLeast(0)) / 1000f

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
