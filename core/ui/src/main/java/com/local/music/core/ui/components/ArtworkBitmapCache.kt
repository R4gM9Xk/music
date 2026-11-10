package com.local.music.core.ui.components

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

/**
 * 封面位图按需解码 + LRU 常驻（上限按字节计）。
 *
 * 存在理由：曲库扫描（[com.local.music.player.MediaLibrary]）过去会在首帧前把**全库**封面
 * 解码进 `DemoSong.coverBitmap` 并随 `AppState.library` 常驻——几千首即数百 MB，无 LRU、
 * 无按需、无回收，是所有路径里最先撞 OOM 的地方。改为：扫描只落 `artworkUri`（磁盘 PNG 路径），
 * 位图在真正要画时（[ArtThumb]）才解码，并受本缓存的总字节上限约束。
 *
 * 线程约定：只在主线程（Composition / 绘制）调用——解码 160px 级 PNG 约 1~3ms，
 * 命中缓存则是纯查表；不做后台线程投递以免引入占位闪烁。
 */
object ArtworkBitmapCache {

    /** 总字节预算：24MB ≈ 约 380 张 160×160 位图（160×160×4 ≈ 100KB/张） */
    private const val MAX_BYTES = 24L * 1024L * 1024L

    /** 单张解码上限边长：全库缩略图最大显示尺寸约 80dp（≈ 240px @3x），取 256 留余量 */
    private const val MAX_EDGE_PX = 256

    /**
     * 负缓存（[failed]）容量上限。
     *
     * 取 1024：正常曲库里「有封面但文件缺失」的条目远小于此（真到 1024 说明缓存目录被整体清空，
     * 此时清空集合重试一轮比继续记住更有意义）。上限存在只为防止极端情况下集合无界增长。
     */
    private const val MAX_FAILED = 1024

    private val lru = object : LinkedHashMap<String, ImageBitmap>(64, 0.75f, true) {
        // **本 override 实际永不触发，这是刻意的（不要误以为它在做驱逐）**：
        // put() 结尾的手动收紧循环维持「进入 put 时 sizeBytes ≤ MAX_BYTES」这一不变量，
        // 因此 removeEldestEntry 被调用时返回值恒为 false。真正的驱逐在 put 里，
        // 那段循环还顺带处理了「单张特别大、一次 put 就超额」的情况（override 做不到）。
        // 保留它没有坏处（防御性），但把结论写在这里，免得下一个人以为改 MAX_BYTES 就够了。
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?): Boolean =
            sizeBytes > MAX_BYTES
    }

    private var sizeBytes = 0L

    /**
     * 解码失败的 uri（负缓存）：避免「封面文件已不存在 / 解码失败」时**每一帧都重解一次**。
     *
     * 为什么必须有：`getOrDecode` 只在成功时入 LRU。而 `MediaLibrary` 的封面链有多个
     * 会主动删除 PNG 的路径（`reusableFolderCover` 在源图消失时 `cache.delete()`、
     * `sweepCovers` 清孤儿、系统/清理助手回收 externalCacheDir），删完之后缓存 JSON 里的
     * `artworkUri` 仍指向那个已不存在的 `file://`。此时该曲目在曲库页**每次滚动进视口**
     * 都会走一次 `BitmapFactory.decodeFile` 的两趟（bounds + 真解）并失败 ——
     * 主线程 IO，滚动越流畅越明显。
     *
     * 失效策略：只增不减会被「扫描后恢复封面」永久钉死在失败态，所以
     * 1) [clearFailure] 供扫描/缓存刷新时主动清；
     * 2) 容量上限 + 插满即整体清空（失败集合的规模上限 = 当前曲库中无封面的条目数，
     *    真到上限说明曲库整体异常，清空重试一次是合理的）。
     */
    private val failed = object : LinkedHashMap<String, Unit>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Unit>?): Boolean =
            size > MAX_FAILED
    }

    /** 只查缓存，不解码（供需要「已经就绪就别抖」的场景）。失败过的 uri 也返回 null。 */
    fun get(uri: String?): ImageBitmap? {
        if (uri.isNullOrEmpty()) return null
        return lru[uri]
    }

    /**
     * 取位图：缓存命中直接返回；未命中则从 `uri`（file:// 或裸路径）解码一次并入缓存。
     * 解码失败返回 null（调用方退化为渐变占位，不留半张图），**并记入负缓存**，
     * 后续同一 uri 直接返回 null 不再重解。
     *
     * @param retryFailed 传 true 时忽略负缓存重试一次（供「刚重扫完/用户手动刷新」这类
     *   明确知道磁盘已变化的场景；默认 false，即常态下失败过一次就不再重试）
     */
    fun getOrDecode(uri: String?, retryFailed: Boolean = false): ImageBitmap? {
        if (uri.isNullOrEmpty()) return null
        lru[uri]?.let { return it }
        if (!retryFailed && uri in failed) return null
        val bitmap = runCatching { decodeSampled(uri.removePrefix("file://")) }.getOrNull()
        if (bitmap == null) {
            failed[uri] = Unit
            return null
        }
        failed.remove(uri)
        put(uri, bitmap)
        return bitmap
    }

    /** 让某个 uri 的失败记录失效（重扫后封面可能已恢复，由调用方在曲库刷新时清） */
    fun clearFailure(uri: String) {
        failed.remove(uri)
    }

    /** 清空全部失败记录（曲库整体刷新 / 重扫完成时调用） */
    fun clearFailures() {
        failed.clear()
    }

    /** 显式放入（扫描期已解出、不想再解一次时用） */
    fun put(uri: String, bitmap: ImageBitmap) {
        lru.put(uri, bitmap)?.let { sizeBytes -= it.byteSize() }
        sizeBytes += bitmap.byteSize()
        // 手动收紧：LinkedHashMap 的 removeEldestEntry 只在 put 内部被调用一次，
        // 若单张特别大可能仍超额，这里再兜一层
        val it = lru.entries.iterator()
        while (sizeBytes > MAX_BYTES && it.hasNext()) {
            val e = it.next()
            sizeBytes -= e.value.byteSize()
            it.remove()
        }
    }

    /** 清空位图缓存与失败记录（换库 / 重扫完成后调用，见 AppState.loadLibrary 的接线） */
    fun clear() {
        lru.clear()
        sizeBytes = 0L
        failed.clear()
    }

    private fun ImageBitmap.byteSize(): Long = width.toLong() * height.toLong() * 4L

    /** 按 [MAX_EDGE_PX] 长的边降采样解码（两趟：先读 bounds 算 inSampleSize，再真解） */
    private fun decodeSampled(path: String): ImageBitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= MAX_EDGE_PX ||
            bounds.outHeight / (sample * 2) >= MAX_EDGE_PX
        ) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeFile(path, opts)?.asImageBitmap()
    }
}
