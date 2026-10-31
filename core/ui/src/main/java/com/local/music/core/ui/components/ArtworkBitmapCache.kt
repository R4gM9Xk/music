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

    private val lru = object : LinkedHashMap<String, ImageBitmap>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?): Boolean =
            sizeBytes > MAX_BYTES
    }

    private var sizeBytes = 0L

    /** 只查缓存，不解码（供需要「已经就绪就别抖」的场景） */
    fun get(uri: String?): ImageBitmap? {
        if (uri.isNullOrEmpty()) return null
        return lru[uri]
    }

    /**
     * 取位图：缓存命中直接返回；未命中则从 `uri`（file:// 或裸路径）解码一次并入缓存。
     * 解码失败返回 null（调用方退化为渐变占位，不留半张图）。
     */
    fun getOrDecode(uri: String?): ImageBitmap? {
        if (uri.isNullOrEmpty()) return null
        lru[uri]?.let { return it }
        val bitmap = runCatching { decodeSampled(uri.removePrefix("file://")) }.getOrNull()
            ?: return null
        put(uri, bitmap)
        return bitmap
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

    /** 清空（换库/退出时可选调用；当前无调用点，保留给后续换库场景） */
    fun clear() {
        lru.clear()
        sizeBytes = 0L
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
