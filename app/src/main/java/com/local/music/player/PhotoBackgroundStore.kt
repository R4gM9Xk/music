package com.local.music.player

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color as AndroidColor
import android.net.Uri
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.palette.graphics.Palette
import com.local.music.core.ui.components.PhotoBacking
import java.io.File

/**
 * 照片背景的**解码与落盘**（app 层，因为要 Context + 真正的 IO）。
 *
 * ## 为什么单独一个对象，不塞进 AppState
 *
 * `AppState` 在 `core:ui`，且它的测试是**纯 JVM 单测**（`FakePlayerEngine` 那套）。
 * 引入 `BitmapFactory` / `ContentResolver` 会让它一编译就要 Android 运行时，
 * 那些测试全得改。所以边界这样切：
 * - `AppState` 只持有**结果**（`photoBacking`）与**设置**（mode / uri）；
 * - 解码、降采样、落盘、取色全部留在这里，由 GlassApp 在合适的时机调用。
 *
 * ## 为什么先把 uri 复制到私有目录
 *
 * Photo Picker / SAF 给的 uri 是**临时授权**的（`takePersistableUriPermission` 只对
 * ACTION_OPEN_DOCUMENT 且要显式申请）。用户重启 App、或授权被系统回收后，那个 uri
 * 直接读不了 —— 表现为「昨天选的照片今天变回极光了」。
 *
 * 复制到 `filesDir/background/` 就彻底绕开授权生命周期。代价是一份文件（降采样后的
 * JPEG，通常 300KB~1.5MB），对「一张全屏背景」这个用途完全可接受。
 *
 * ## 降采样
 *
 * 全屏照片原始可达 4000×3000（约 48MB 位图）。屏幕最宽也就 1440px，
 * 按 `inSampleSize` 两趟解到长边 ≤ [MAX_EDGE_PX] 即可。**不解原图**是硬要求：
 * 解一张 48MB 位图会直接在中低端机上 OOM。
 */
object PhotoBackgroundStore {

    /**
     * 解码目标长边。
     *
     * 取 2048 而不是「屏幕宽」：屏幕旋转、分屏、自由窗口都会变宽高，
     * 按当前屏宽解会在横屏时糊掉。2048 覆盖到 2K 屏全屏显示，
     * 位图内存 ≈ 2048×1536×4 ≈ 12MB —— 一张常驻背景可以接受（且只有一张）。
     */
    private const val MAX_EDGE_PX = 2048

    /** 落盘子目录（相对 filesDir） */
    private const val DIR = "background"

    /** 落盘文件名：固定名，换图即覆盖（不留历史，避免用户反复选图后堆一堆全屏图） */
    private const val FILE_NAME = "background.jpg"

    /** JPEG 质量：背景图不参与文字渲染，85 已看不出压缩痕迹 */
    private const val JPEG_QUALITY = 85

    /**
     * 从任意 uri 载入背景：**复制到私有目录 + 降采样解码 + 取色**，一次做完。
     *
     * 必须在 IO 线程调用（内部直接读流）。失败返回 null —— 调用方应回落极光。
     */
    fun load(context: Context, uri: String): PhotoBacking? {
        val file = copyToPrivate(context, uri) ?: return null
        return decode(context, file.absolutePath)
    }

    /** 从**已落盘**的私有文件解码（App 启动时走这条，不需要原始 uri 还有效） */
    fun loadPersisted(context: Context, uri: String): PhotoBacking? {
        // 私有文件存在就直接解；不存在（用户清过数据 / 从没成功复制过）则试一次原 uri
        val file = privateFile(context)
        return if (file.exists()) {
            decode(context, file.absolutePath)
        } else {
            load(context, uri)
        }
    }

    /** 清除已落盘的背景（用户「恢复极光」时调用，省掉一份常驻文件） */
    fun clear(context: Context) {
        runCatching { privateFile(context).delete() }
    }

    // ── 内部 ──────────────────────────────────────────────

    private fun privateFile(context: Context): File = File(File(context.filesDir, DIR), FILE_NAME)

    private fun copyToPrivate(context: Context, uri: String): File? = runCatching {
        val dir = File(context.filesDir, DIR).apply { mkdirs() }
        val target = File(dir, FILE_NAME)
        // 先写临时文件再 rename：解码中途被杀进程时，不会留下一个「半张图」的
        // background.jpg 让下次启动解出半截画面
        val tmp = File(dir, "$FILE_NAME.tmp")
        context.contentResolver.openInputStream(Uri.parse(uri))?.use { input ->
            tmp.outputStream().use { output -> input.copyTo(output) }
        } ?: return null
        if (!tmp.renameTo(target)) {
            // rename 失败（跨分区/被占用）：退化为直接覆盖写
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
        target
    }.getOrNull()

    private fun decode(context: Context, path: String): PhotoBacking? = runCatching {
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
        val bitmap = BitmapFactory.decodeFile(path, opts) ?: return null

        // 氛围色：复用项目既有的调色板能力（Palette 已在依赖里，MediaLibrary 用它做封面取色）。
        // 取不到就返回 null —— 调用方传 null 给 PhotoBackground 时会跳过氛围色叠加，
        // 不会因此显示异常。
        val tint = runCatching {
            val palette = Palette.from(bitmap).generate()
            val argb = palette.getVibrantColor(
                palette.getMutedColor(AndroidColor.TRANSPARENT),
            )
            if (argb == AndroidColor.TRANSPARENT) null else Color(argb)
        }.getOrNull()

        PhotoBacking(
            uri = path,
            bitmap = bitmap.asImageBitmap(),
            tint = tint,
        )
    }.getOrNull()
}
