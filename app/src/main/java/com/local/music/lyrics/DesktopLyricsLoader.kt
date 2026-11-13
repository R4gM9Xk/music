package com.local.music.lyrics

import android.content.Context
import android.util.Log
import com.local.music.core.ui.components.LyricLine
import com.local.music.player.MediaLibrary

/**
 * 桌面歌词的 .lrc 装载器（P0-8）。
 *
 * ## 为什么不复用 MediaLibrary.readLrc 而要「平移」候选路径逻辑
 * `readLrc` 是 private，且 MediaLibrary.kt 已封板（历史任务锁定，改动会破坏已验证的
 * 扫描/缓存行为）。所以这里做一次**有注释的平移**：
 *
 * 1. 候选路径与读取上限逐行照搬（音频同目录 sibling 优先 → 应用专属外部目录
 *    files/lrc/ 兜底 + 512KB 上限）。两处逻辑将来若要改，必须同步改——下方「同步清单」。
 * 2. **解析直接调 [MediaLibrary.parseLrc]**（public）——那是 P3-28/P3-29/P3-30 一系列
 *    修复后的权威实现（offset 全文两趟、BOM、多时间戳、增强标签剥离），悬浮窗歌词
 *    必须与播放页 LyricRail 出自同一个解析器，否则两边高亮行会对不上。
 *
 * 同步清单（改 MediaLibrary.readLrc 语义时同步这里）：
 * - sibling / files/lrc 两个候选与优先级
 * - LRC_MAX_BYTES = 512 KiB
 * - 「无时间戳 → 纯文本单行降级」的三态语义（与 parseLrcOrPlainText 一致）
 */
internal object DesktopLyricsLoader {

    private const val TAG = "DesktopLyrics"

    /** 与 MediaLibrary.LRC_MAX_BYTES 同值：超大 .lrc 视同不存在（几百 MB 的误下载/二进制同名文件） */
    private const val LRC_MAX_BYTES = 512 * 1024

    /**
     * 装载当前曲的歌词。
     *
     * @param audioPath 音频文件绝对路径（mediaId = "f" + 路径 的约定，服务端解出后传入）。
     *                  null / 空 = 拿不到路径（未播放 / 演示曲目）→ 返回空列表。
     * @return 解析结果（可能为空 = 无歌词；调用方显示「暂无歌词」空态而非隐藏窗：
     *         开关开着却什么都看不到，用户只会以为功能坏了，见任务第 7 条的取舍）
     */
    fun load(context: Context, audioPath: String?): List<LyricLine> {
        if (audioPath.isNullOrBlank()) return emptyList()
        // 文件名取 basename 后再剥扩展名——不能对整条路径用 substringBeforeLast('.')，
        // 否则「无扩展名文件名 + 含点目录」会算到目录的点上（同 MediaLibrary.readLrc 的注释）
        val fileName = audioPath.substringAfterLast('/').substringBeforeLast('.')
        val sibling = audioPath.substringBeforeLast('/') + "/" + fileName + ".lrc"
        val candidates = listOfNotNull(
            java.io.File(sibling),
            context.getExternalFilesDir(null)?.let { java.io.File(it, "lrc/$fileName.lrc") },
        )
        // **逐候选「读取+解析」，一个候选读不出有效歌词就继续试下一个**（本轮修复）。
        // 旧实现只做 firstNotNullOfOrNull { readText() }、解析放在循环外：于是同目录
        // sibling 存在但是**坏文件/空文件**时，它把文本一取走就 return 了，永远不会回退到
        // files/lrc/ 兜底候选 —— 与 MediaLibrary.readLrc 的语义分叉，
        // 而本文件头部「同步清单」明确要求两处候选与优先级行为一致。
        val parsed = candidates.asSequence()
            .filter { it.isFile }
            .filter { it.length() <= LRC_MAX_BYTES }
            .firstNotNullOfOrNull { file ->
                // 解析全程 runCatching：坏歌词文件绝不能把调用线程搞崩（控制器在读盘线程调它）
                runCatching {
                    val text = file.readText()
                    val lines = MediaLibrary.parseLrc(text)
                    if (lines.isNotEmpty()) {
                        lines
                    } else {
                        // 无时间戳的纯文本 .lrc：与 MediaLibrary.parseLrcOrPlainText 同语义——
                        // 至少显示这首歌自己的文字，而不是「暂无歌词」；单行截断 2000 字符防撑爆。
                        // 两个候选都为空/全空行时返回 null，让序列继续找下一个候选。
                        text.trim().takeIf { it.isNotEmpty() }
                            ?.let { listOf(LyricLine(it.take(2000), 0f)) }
                    }
                }
                    .onFailure { Log.w(TAG, "lrc read failed: ${file.absolutePath}", it) }
                    .getOrNull()
            }
        return parsed.orEmpty()
    }
}
