package com.local.music.core.ui.components

import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 播放页**大封面**专用的高分辨率解码 + 极小 LRU。
 *
 * ## 为什么不能复用 [ArtworkBitmapCache]
 * 全 App 只有一条封面管线：扫描期把内嵌封面压到长边 160–319px
 * （`MediaLibrary.loadMetadata` 的 `>= 160` 降采样）、目录封面压到 256px
 * （`COVER_MAX_EDGE_PX`），落盘成 PNG，再由 [ArtworkBitmapCache] 以 256px 上限解码。
 * 那条管线的两处注释都写着「全库缩略图最大显示约 80dp」——这个前提对列表/迷你条/
 * 通知栏成立，**对播放页不成立**：`PlayerScreen` 的大封面可达 360dp，
 * @3x 屏上是 1080px。于是 App 的视觉主角是一张被放大 3–4 倍插值的糊图。
 *
 * ## 为什么不直接把扫描分辨率调高
 * 落盘 PNG 是**全库**的：把长边从 256 抬到 1024，单张从 ~100KB 涨到 ~1–2MB，
 * 千首曲库就是 1–2GB 磁盘占用。列表缩略图根本不需要那个精度，为了一张大封面
 * 让整库付出十倍磁盘代价是错的取舍。
 *
 * 所以这里走**按需、单曲、内存内**的高分辨率通道：只在播放页真正要画大封面时，
 * 从当前曲的音频文件里重新取一次内嵌封面，解到 ≤1080px，缓存最近 2 张。
 * 磁盘上的缩略图管线一个字节都不动。
 *
 * ## 已知边界
 * 只覆盖**内嵌封面**。封面来自目录 cover.jpg 的曲目（扫描期走 folderCover 分支）
 * 拿不到更高分辨率的源——DemoSong 里没有原始图片路径，只有已降采样的 PNG。
 * 这类曲目回落到缩略图，与改造前一致，不会更差。
 *
 * ## 线程
 * [load] 的解码在 IO；LRU 与负缓存的读写都在**主线程**（调用方是 Compose 的
 * LaunchedEffect），因此不需要加锁。不要在后台线程直接调 [peek]/[load] 的缓存段。
 */
object PlayerArtworkCache {

    /** 长边上限：播放页大封面最大 360dp，@3x = 1080px，取同值即「不再放大插值」 */
    private const val MAX_EDGE_PX = 1080

    /**
     * 只缓存最近 2 张（当前曲 + 上一首）。
     * 1080×1080×4 ≈ 4.7MB/张，2 张 ≈ 9.4MB——与缩略图缓存的 24MB 预算同量级可控。
     * 切歌回来时上一首仍在缓存里，来回切不会重复解码。
     */
    private const val MAX_ENTRIES = 2

    /** 负缓存上限：没有内嵌封面的曲目不该每次切回去都重开一次 MediaMetadataRetriever */
    private const val MAX_FAILED = 64

    private val lru = object : LinkedHashMap<String, ImageBitmap>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?): Boolean =
            size > MAX_ENTRIES
    }

    private val failed = LinkedHashSet<String>()

    /** 纯查表、零副作用：命中即返回，未命中返回 null（调用方先画缩略图，不闪空） */
    fun peek(mediaUri: String?): ImageBitmap? = mediaUri?.let { lru[it] }

    /**
     * 取当前曲的高分辨率封面。命中缓存直接返回；未命中在 IO 解一次。
     * 无内嵌封面 / 解码失败返回 null 并记入负缓存，调用方回落缩略图。
     */
    suspend fun load(context: Context, mediaUri: String?): ImageBitmap? {
        if (mediaUri.isNullOrEmpty()) return null
        lru[mediaUri]?.let { return it }
        if (mediaUri in failed) return null
        val bitmap = withContext(Dispatchers.IO) {
            runCatching { decode(context, mediaUri) }.getOrNull()
        }
        if (bitmap == null) {
            if (failed.size >= MAX_FAILED) failed.clear()
            failed.add(mediaUri)
            return null
        }
        failed.remove(mediaUri)
        lru[mediaUri] = bitmap
        return bitmap
    }

    /** 曲库整体刷新时清缓存（与 [ArtworkBitmapCache.clear] 同一时机调用） */
    fun clear() {
        lru.clear()
        failed.clear()
    }

    private fun decode(context: Context, mediaUri: String): ImageBitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, Uri.parse(mediaUri))
            val bytes = retriever.embeddedPicture ?: return null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= MAX_EDGE_PX ||
                bounds.outHeight / (sample * 2) >= MAX_EDGE_PX
            ) {
                sample *= 2
            }
            BitmapFactory.decodeByteArray(
                bytes, 0, bytes.size,
                BitmapFactory.Options().apply { inSampleSize = sample },
            )?.asImageBitmap()
        } finally {
            runCatching { retriever.release() }
        }
    }
}