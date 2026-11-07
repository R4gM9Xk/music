package com.local.music.widget

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.media3.session.MediaController

/**
 * 小部件要展示的**一次性快照**。
 *
 * ## 为什么是「快照」而不是直接读 controller 的 getter
 * RemoteViews 的每一次刷新都是一次**跨进程的序列化下发**（AppWidgetHost 在桌面上，
 * 我们的数据要经 Binder 过去）。如果渲染时对着 controller 逐个 getter 读，会出现
 * 「标题读到了新的、封面还是旧的」这种撕裂 —— 因为 provideContent 与点击事件之间
 * 播放器随时在变。先把当前状态一次性抠成不可变快照，再用同一份快照渲染所有字段，
 * 至少保证**这一次刷新内部是自洽的**。
 *
 * ## 空态（[hasCurrent] == false）是一条独立分支
 * 桌面小部件最常见的投诉是「显示的是上一次播放的歌，点哪都没反应」。所以
 * 无曲目时**绝不**复用上一帧的标题/封面，而是走 [EMPTY] 空态：
 * 只显示「未在播放」+ 一句引导，控制按钮整组隐藏（见 MusicWidgetViews）。
 * 宁可看起来「空」，也不能让用户以为还在播。
 */
internal data class WidgetState(
    /** 是否有当前曲目。false ⇒ 走空态，其余字段一律无意义 */
    val hasCurrent: Boolean,
    val title: String,
    val artist: String,
    val isPlaying: Boolean,
    /** 封面位图（来自 MediaItem 内嵌的小 JPEG，见 CoverArtBitmap）；null 用占位图 */
    val art: Bitmap?,
    /** 当前进度（毫秒）；未知为 0 */
    val positionMs: Long,
    /** 总时长（毫秒）；未知/直播流为 0 */
    val durationMs: Long,
) {
    /** 时长可用（>0）才显示进度条，否则进度条整条隐藏而不是画个空槽 */
    val hasDuration: Boolean get() = durationMs > 0L

    companion object {
        /** 空态单例：字段全空，避免各处构造出「半个空态」 */
        val EMPTY = WidgetState(
            hasCurrent = false,
            title = "",
            artist = "",
            isPlaying = false,
            art = null,
            positionMs = 0L,
            durationMs = 0L,
        )

        /** 兜底文案：曲目存在但元数据缺标题时**不要**显示空字符串（会让整行塌掉） */
        private const val UNKNOWN_TITLE = "未知歌曲"

        /**
         * 从 controller 抠一份快照。controller 为 null（未连接 / 服务没起来）⇒ 空态。
         *
         * 全部字段都 runCatching 兜住：controller 是跨进程代理，服务恰好在销毁时
         * 任何一个 getter 都可能抛 IllegalStateException / DeadObjectException。
         * 小部件这种「后台随手被拉起」的场景撞上这个概率不低，不能让它冒到
         * AppWidgetHost 那边（那会表现为小部件卡在加载态）。
         */
        fun read(controller: MediaController?): WidgetState {
            if (controller == null) return EMPTY
            return runCatching {
                val item = controller.currentMediaItem ?: return EMPTY
                val metadata = item.mediaMetadata
                val rawTitle = metadata.title?.toString()?.trim().orEmpty()
                val artist = metadata.artist?.toString()?.trim().orEmpty()
                WidgetState(
                    hasCurrent = true,
                    title = rawTitle.ifEmpty { UNKNOWN_TITLE },
                    artist = artist,
                    isPlaying = controller.isPlaying,
                    art = decodeArt(metadata.artworkData),
                    positionMs = controller.currentPosition.coerceAtLeast(0L),
                    // C.TIME_UNSET(-9223372036854775807) / 直播流会给出非正数，
                    // 统一折算成 0 ⇒ hasDuration=false ⇒ 进度条隐藏
                    durationMs = controller.duration.coerceAtLeast(0L),
                )
            }.getOrDefault(EMPTY)
        }

        /**
         * 内嵌封面字节 → Bitmap。
         *
         * **为什么要 inSampleSize 再解一次**：MediaItem 里的封面是长边 192px 的 JPEG
         * （CoverArtBitmap.MAX_EDGE_PX），解码后 192*192*4 ≈ 147KB。单个 RemoteViews
         * 的 Binder 事务上限是 1MB，桌面同时有多个小部件实例时，逐个实例下发几十 KB
         * 的位图是可控的；但若哪天 MAX_EDGE_PX 被调大，这里没有降采样就会直接撞
         * TransactionTooLargeException —— 表现为「小部件整个不刷新」，且不会崩溃，
     * 极难排查。所以这里主动再设一道 [TARGET_EDGE_PX] 闸门。
         */
        private fun decodeArt(bytes: ByteArray?): Bitmap? {
            if (bytes == null || bytes.isEmpty()) return null
            return runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= TARGET_EDGE_PX ||
                    bounds.outHeight / (sample * 2) >= TARGET_EDGE_PX
                ) {
                    sample *= 2
                }
                BitmapFactory.decodeByteArray(
                    bytes, 0, bytes.size,
                    BitmapFactory.Options().apply { inSampleSize = sample },
                )
            }.getOrNull()
        }

        /** 小部件封面显示尺寸上限（4x2 大封面 72dp，2 倍密度下也远小于 192） */
        private const val TARGET_EDGE_PX = 192
    }
}

/**
 * 毫秒 → "m:ss"（超过一小时给 "h:mm:ss"）。
 *
 * 不用 String.format("%02d:%02d")：小部件刷新跑在主线程，每次走一遍 Formatter
 * 纯属浪费；而且 %02d 对 locale 有依赖（阿拉伯语数字等），手拼更稳。
 */
internal fun formatClock(ms: Long): String {
    if (ms <= 0L) return "0:00"
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "$h:${pad2(m)}:${pad2(s)}" else "$m:${pad2(s)}"
}

private fun pad2(v: Long): String = if (v < 10) "0$v" else "$v"
