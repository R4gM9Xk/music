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

    /**
     * 源 uri 记录文件名（与 [FILE_NAME] 同目录）。
     *
     * 存在的唯一理由：`background.jpg` 是固定名、换图即覆盖，**文件名本身无法表达
     * 「这份图是从哪张来的」**，而 `loadPersisted` 必须靠它判断用户是否换了图。
     * 内容是一行源 uri 原文；不参与渲染，删除/损坏都只是退化成「按旧版行为处理」。
     */
    private const val SOURCE_FILE = "background.src"

    /** JPEG 质量：背景图不参与文字渲染，85 已看不出压缩痕迹 */
    private const val JPEG_QUALITY = 85

    /**
     * 从任意 uri 载入背景：**复制到私有目录 + 降采样解码 + 取色**，一次做完。
     *
     * 必须在 IO 线程调用（内部直接读流）。失败返回 null —— 调用方应回落极光。
     */
    fun load(context: Context, uri: String): PhotoBacking? {
        val file = copyToPrivate(context, uri) ?: return null
        return decode(context, file.absolutePath, uri)
    }

    /**
     * 从**已落盘**的私有文件解码，但会先确认「私有文件对应的源 uri 就是请求的这个」。
     *
     * **本函数是「换图不生效」的修复点**（本轮）：旧实现只要 `background.jpg` 存在就
     * 直接解码它，**完全不看传入的 uri**。而私有文件名是固定常量（见 [FILE_NAME]，
     * 「换图即覆盖」），于是第二次选图时：
     *
     *   用户选 B → loadPersisted(B) → 私有文件存在 → 解出的是**上一张 A** → 界面不变。
     *
     * 用户只能先「恢复极光」（删掉私有文件）再选 B 才生效 —— 这是本轮新增功能的主路径缺陷。
     *
     * 修法：把「这份私有文件是从哪个 uri 复制的」记在旁边的小文本里（[SOURCE_FILE]），
     * 读盘时比对：
     * - 一致（或没有记录 = 旧版本遗留，按一致处理以保持兼容）→ 直接解私有文件，
     *   仍然满足「启动时不依赖原 uri 还有效」这个原始设计目标；
     * - 不一致 → 说明用户换图了，**重新复制并覆盖**再解。
     *
     * 为什么不用 mtime / 文件大小判断：用户完全可能重新选同一张图（换回 A），
     * 大小相同、时间不同，任何「猜」的口径都会在其中一边出错；源 uri 是唯一确定的事实。
     */
    fun loadPersisted(context: Context, uri: String): PhotoBacking? {
        val file = privateFile(context)
        val recorded = readSourceUri(context)
        // 私有不存 → 复制；有存但来源不符 → 重新复制（用户换了图）；来源相同 → 直接解
        val usable = file.exists() && (recorded == null || recorded == uri)
        return if (usable) {
            decode(context, file.absolutePath, uri)
        } else {
            load(context, uri)
        }
    }

    /** 清除已落盘的背景（用户「恢复极光」时调用，省掉一份常驻文件） */
    fun clear(context: Context) {
        runCatching { privateFile(context).delete() }
        runCatching { sourceFile(context).delete() }
    }

    // ── 内部 ──────────────────────────────────────────────

    private fun privateFile(context: Context): File = File(File(context.filesDir, DIR), FILE_NAME)

    /** 记录「当前私有文件由哪个源 uri 复制而来」的小文本（与私有文件同目录） */
    private fun sourceFile(context: Context): File = File(File(context.filesDir, DIR), SOURCE_FILE)

    /** 读源 uri 记录：不存在 / 读失败一律 null（= 旧版本遗留，按「来源一致」兼容处理） */
    private fun readSourceUri(context: Context): String? = runCatching {
        val f = sourceFile(context)
        if (!f.isFile) return@runCatching null
        f.readText().trim().takeIf { it.isNotEmpty() }
    }.getOrNull()

    /**
     * 只在复制成功后写源 uri（顺序很重要）：
     * 先落图、再落记录 —— 反过来会出现「记录说 B、图还是 A」的错配，正是本函数要防的。
     */
    private fun writeSourceUri(context: Context, uri: String) {
        runCatching { sourceFile(context).writeText(uri) }
    }

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
        // 图已就位，再记来源（顺序见 [writeSourceUri]）
        writeSourceUri(context, uri)
        target
    }.getOrNull()

    /**
     * 解码并组装 [PhotoBacking]。
     *
     * [sourceUri] 是**用户选的那个 uri**（不是私有文件路径）—— 它是 `PhotoBacking.uri`
     * 的语义载体，见该类 KDoc。旧实现这里传 `path`（私有文件绝对路径），与
     * `AppState.backgroundPhotoUri`（SAF content:// 串）永远不相等，导致
     * GlassApp 里「已就绪且是同一张就不重复解」的守卫恒为 false。
     */
    private fun decode(context: Context, path: String, sourceUri: String): PhotoBacking? = runCatching {
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
            // 存**源 uri**而不是私有路径：调用方靠它判断「是不是同一张图」，
            // 私有路径对所有照片都是同一个常量，用作判据等于没有判据。
            uri = sourceUri,
            bitmap = bitmap.asImageBitmap(),
            tint = tint,
        )
    }.getOrNull()
}
