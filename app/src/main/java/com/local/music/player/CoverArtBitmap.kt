package com.local.music.player

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.ByteArrayOutputStream

/**
 * 把封面文件压成**可内嵌进 MediaMetadata 的小 JPEG**（F2）。
 *
 * ## 为什么不能继续下发 file:// 原路径
 * 封面落在 `externalCacheDir/covers/`，即 `/storage/emulated/0/Android/data/<pkg>/cache/covers`
 * 下面的 png —— **应用私有**目录。MediaMetadata.artworkUri 的消费方是系统进程
 * （MediaDataManager / SystemUI 的 ImageLoader，uid 10xxx），跨 uid 打开这个路径必被拒：
 * 真机稳定刷 `EACCES`、模拟器刷 `ENOENT`（本轮 logcat 连片复现，每次切歌都刷）。
 *
 * 控制中心与锁屏「看起来正常」容易让人误判这条 bug 无害 —— 它们走的是通知里的 bitmap 通道：
 * media3 的 DefaultMediaNotificationProvider 在**本进程内**解码 artworkUri 后塞进
 * NotificationCompat，所以读得到私有文件。真正受影响的是一切**只读 metadata** 的下游：
 * 车载、手表、第三方「正在播放」面板 —— 它们拿到的是那个永远读不到的路径。
 *
 * ## 为什么不用 FileProvider content://（本轮试过，已否决）
 * metadata 里的 uri 只是一段**纯数据**，media3 转发它时不带（也无法带）
 * `FLAG_GRANT_READ_URI_PERMISSION` —— 该 flag 只对显式传递 Intent 的路径
 * （startActivity / sendBroadcast）生效。实测证据：
 * ```
 * W ContentProviderHelper: Permission Denial: opening provider androidx.core.content.FileProvider
 *   from ProcessRecord{... 956:com.android.systemui/u0a186} ... not exported from UID 10219
 * ```
 * content:// 与 file:// 同样读不到，只是把 EACCES 换成了 SecurityException（刷屏更凶）。
 * 把 provider 改成 exported 等于把封面目录对整个系统开放，不可接受。
 *
 * ## 为什么是内嵌而不是别的
 * `setArtworkData` 的字节随 MediaItem 走 Binder，而本工程一队列可能有上千首。
 * 所以必须**控总量**：单张压到长边 [MAX_EDGE_PX] 的 JPEG（实测 5–15KB），
 * 并由调用方按 [BUDGET_BYTES] 做队列级预算裁剪（见 MediaControllerEngine.withArtwork）。
 * 这一步不做的话，整队内嵌必然撞 1MB 的 TransactionTooLargeException ——
 * 那会让 setMediaItems 整个失败，播放直接不可用，比没有封面严重得多。
 */
object CoverArtBitmap {
    /** 长边上限：通知栏/锁屏/车载封面显示尺寸远小于此，再大纯属浪费 Binder 与内存 */
    private const val MAX_EDGE_PX = 192

    /**
     * 单条队列内嵌封面的**字节总预算**。Binder 事务上限 1MB 是整队共享的，
     * 而 MediaItem 里还装着 uri/title/artist 等字段 —— 取 256KB 给封面，
     * 给其余字段留出充足余量。
     */
    const val BUDGET_BYTES = 128 * 1024

    /**
     * 单次 `withArtwork` 最多尝试解码多少首。
     *
     * 与 [BUDGET_BYTES] 配合把复杂度钉在 O(容量) 而不是 O(队列长度)：预算 128KB 实际只能装
     * 十几到二十几首，再多扫一眼都是白解码（每次解码+压缩 8~20ms，跑在 UI/服务主线程）。
     * 取值留了一倍余量，覆盖「小封面能多装几首」的情况。
     */
    const val MAX_ATTEMPTS = 48

    /** 超过即放弃这张（宁可这首没封面，也不让整队装载失败） */
    private const val MAX_ONE_BYTES = 24 * 1024

    /**
     * 读取 [raw]（file 协议）并压成小 JPEG；任何异常/超限返回 null。
     * 只处理**本应用 covers 目录下**的 file uri（content/http 等原样不管）。
     */
    fun smallJpeg(context: Context, raw: String?): ByteArray? {
        val path = filePathInCovers(context, raw) ?: return null
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= MAX_EDGE_PX ||
                bounds.outHeight / (sample * 2) >= MAX_EDGE_PX
            ) {
                sample *= 2
            }
            val bitmap = BitmapFactory.decodeFile(
                path,
                BitmapFactory.Options().apply { inSampleSize = sample },
            ) ?: return null
            val bytes = ByteArrayOutputStream().use { out ->
                // quality 逐档下调：绝大多数封面第一档就过，只有高细节图会迭代
                var q = 82
                var data: ByteArray
                do {
                    out.reset()
                    bitmap.compress(Bitmap.CompressFormat.JPEG, q, out)
                    data = out.toByteArray()
                    q -= 18
                } while (data.size > MAX_ONE_BYTES && q > 20)
                data
            }
            bitmap.recycle()
            bytes.takeIf { it.size <= MAX_ONE_BYTES }
        }.getOrNull()
    }

    /** 仅当 [raw] 指向本应用 `externalCacheDir/covers/` 下的文件时返回其路径 */
    private fun filePathInCovers(context: Context, raw: String?): String? {
        if (raw.isNullOrEmpty()) return null
        val parsed = runCatching { Uri.parse(raw) }.getOrNull() ?: return null
        if (parsed.scheme != "file") return null
        val coversDir = context.externalCacheDir?.let { java.io.File(it, "covers") } ?: return null
        val path = parsed.path ?: return null
        if (!path.startsWith(coversDir.absoluteDirPrefix())) return null
        return path
    }

    private fun java.io.File.absoluteDirPrefix(): String = absolutePath.trimEnd('/') + "/"
}
