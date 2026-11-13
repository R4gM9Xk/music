package com.local.music.player

import android.content.Context
import android.net.Uri
import com.local.music.core.ui.components.DemoPlaylist
import com.local.music.core.ui.components.DemoSong
import java.io.File

/**
 * M3U / M3U8 歌单导入导出（D2）。
 *
 * 导出：标准 M3U8（UTF-8 + #EXTM3U），每首 `#EXTINF:<秒>,<艺术家> - <标题>` + 绝对路径。
 * 纯本地播放器用绝对路径最实用——同设备换播放器可直接用；跨设备由对方播放器自行匹配。
 *
 * 导入：按路径经「f<绝对路径>」id 体系匹配（id 前缀 f 后即文件绝对路径），
 * 路径两侧先归一化（file:// 前缀 / 反斜杠 / /sdcard 与 legacy 别名 → /storage/emulated/0），
 * 编码先 UTF-8、检出乱码按 GBK 重解；未命中项与相对路径分别汇总返回给 UI 提示（不静默丢弃）。
 */
object M3uPlaylistIO {

    /** 导出结果：成功条数 / 总条数（空歌单也允许导出，UI 自行拦） */
    data class ExportResult(val written: Int, val total: Int)

    /**
     * 导出为 M3U8 文本并写入 [uri]（ACTION_CREATE_DOCUMENT 拿到的 uri）。
     * 返回写出的曲目数；IO 异常抛给调用方（UI 显示失败）。
     */
    fun export(context: Context, uri: Uri, playlist: DemoPlaylist): ExportResult {
        val songs = playlist.tracks
        val sb = StringBuilder()
        sb.append("#EXTM3U\n")
        sb.append("#PLAYLIST:").append(oneLine(playlist.title)).append('\n')
        songs.forEach { s ->
            // #EXTINF:<时长>,<显示文本>；艺术家拼进显示文本便于其他播放器展示。
            // 显示文本/路径含换行会破坏 M3U 行结构：写出前压成空格并 trim。
            // **注意：本工程的导入完全不读这段显示文本**（见 parse 的 #EXTINF 分支 ——
            // 匹配只靠下一行路径，显示文本纯属给**第三方播放器**看的）。
            // 所以标题里的逗号对本工程无害，但若将来要解析它，必须按「首个逗号」切分
            // （其后全部归显示文本），否则含逗号的标题会被截断。
            sb.append("#EXTINF:").append(s.durationSec).append(',')
                .append(oneLine(s.artist)).append(" - ").append(oneLine(s.title)).append('\n')
            sb.append(oneLine(pathOf(s))).append('\n')
        }
        context.contentResolver.openOutputStream(uri)?.use { out ->
            out.write(sb.toString().toByteArray(Charsets.UTF_8))
        } ?: error("无法写入所选位置")
        return ExportResult(songs.size, songs.size)
    }

    /** 导入结果：命中的曲目 id（按文件内顺序去重）+ 未命中行 + 相对路径行（供 UI 汇总提示）。
     *  [ignored] 与 [unmatched] 的区别：unmatched 是「库里找不到的绝对路径」（歌曲可能
     *  尚未入库/已被移动），ignored 是「本来就是相对路径」（无法与本机曲库对位，语义上
     *  不是未命中，统计未命中率时不应计入）。带默认值：既有构造点不改也编译。 */
    data class ImportResult(
        val matchedIds: List<String>,
        val unmatched: List<String>,
        val title: String?,
        val ignored: List<String> = emptyList(),
    )

    /**
     * 解析 M3U/M3U8：[library] 是当前曲库（索引与查找两侧都过 [normalizePath] 归一）。
     * 支持 #EXTM3U/#PLAYLIST 头、#EXTINF 行、注释行（# 开头）、空行；
     * 相对路径（既不以 / 开头、也不含盘符冒号）归入 ignored（P3-71：与注释语义一致，
     * 不计入 unmatched——它不是「没匹配上」，是「根本无法对位」）。
     */
    fun import(context: Context, uri: Uri, library: List<DemoSong>): ImportResult {
        val text = context.contentResolver.openInputStream(uri)?.use { stream ->
            // P3-70：先按 UTF-8 解，若出现 U+FFFD（非法序列的替换符）说明多半是 GBK 编码的
            // 旧歌单（国内播放器导出常见），按 GBK 重解兜底；仍失败则退回 UTF-8 结果。
            // 不能直接 toString(UTF_8) 完事：GBK 中文经该解码全是乱码，路径对不上曲库
            val bytes = stream.readBytes()
            val utf8 = bytes.toString(Charsets.UTF_8)
            if (utf8.contains('\uFFFD')) {
                runCatching { bytes.toString(charset("GBK")) }.getOrDefault(utf8)
            } else {
                utf8
            }
        } ?: error("无法读取所选文件")
        return parse(text, library)
    }

    /** 纯解析（可单测）：从文本还原曲目 id 列表 */
    fun parse(text: String, library: List<DemoSong>): ImportResult {
        // 路径 → 曲目（id = "f" + 绝对路径）；索引侧同样归一（P2-21），
        // 否则库里存 /storage/emulated/0/... 而歌单写 /sdcard/... 时逐字匹配必失配
        val byPath = library.associateBy { normalizePath(pathOf(it)) }
        var title: String? = null
        val matched = LinkedHashSet<String>()
        val unmatched = LinkedHashSet<String>() // P3-34：同一缺失路径在歌单里重复出现时，提示不该重复计数
        val ignored = LinkedHashSet<String>()
        text.lineSequence().forEach { raw ->
            val line = raw.trim().removePrefix("\uFEFF")
            when {
                line.isEmpty() -> Unit
                line.startsWith("#EXTM3U", ignoreCase = true) -> Unit
                line.startsWith("#PLAYLIST:", ignoreCase = true) -> title = line.substringAfter(':').trim()
                line.startsWith("#EXTINF", ignoreCase = true) -> Unit // 时长/标题仅展示用，匹配靠下一行路径；若日后取显示文本，须按「首个逗号」分隔（其后全部归显示文本），含逗号标题才不会截断
                line.startsWith("#") -> Unit // 其他注释
                else -> {
                    // P2-21：归一后匹配——/sdcard、/storage/emulated/legacy、反斜杠分隔符、
                    // file:// 前缀都是同一文件的不同写法，逐字匹配会全部失配
                    val normalized = normalizePath(line)
                    val song = byPath[normalized]
                    if (song != null) {
                        matched += song.id
                    } else if (isRelativePath(normalized)) {
                        ignored += line // 相对路径：无法对位本机曲库，与「绝对路径但未命中」分开统计
                    } else {
                        unmatched += line
                    }
                }
            }
        }
        return ImportResult(matched.toList(), unmatched.toList(), title, ignored.toList())
    }

    /**
     * P2-21 路径归一：M3U 是纯文本歌单，同一文件的路径写法五花八门——
     *   /sdcard/...（/sdcard 是 /storage/emulated/0 的符号链接别名）
     *   /storage/emulated/legacy/...（旧版多用户主存储别名）
     *   Windows 编辑器写出的反斜杠分隔符、file:// URI 前缀
     * 逐字匹配对以上全部失配，导入「基本不可用」的根因即此。索引与查找**两侧**都过此函数。
     */
    private fun normalizePath(raw: String): String = raw.trim()
        .removePrefix("file://")
        .replace('\\', '/') // 反斜杠 → 斜杠
        .replace(Regex("^/sdcard/"), "/storage/emulated/0/") // 符号链接写法
        .replace(Regex("^/storage/emulated/legacy/"), "/storage/emulated/0/")

    /** 相对路径判定：不以 / 开头（非 POSIX 绝对）且不含 `:`（无 Windows 盘符）→ 无法与本机曲库对位 */
    private fun isRelativePath(normalized: String): Boolean =
        !normalized.startsWith("/") && !normalized.contains(':')

    /** 曲目的文件路径：id = "f" + 绝对路径；无 mediaUri 的条目用 id 兜底 */
    private fun pathOf(song: DemoSong): String =
        song.id.removePrefix("f").ifEmpty { song.mediaUri ?: "" }

    /** 单行化：\n/\r 压成空格并 trim——M3U 行结构不容忍换行（#EXTINF 显示文本与路径行写出前统一走这里） */
    private fun oneLine(s: String): String = s.replace(Regex("[\\r\\n]+"), " ").trim()

    /**
     * 导出时建议的文件名（SAF `ACTION_CREATE_DOCUMENT` 的默认名）。
     *
     * 歌单名完全由用户输入（新建与重命名都不限制字符），而 `/ \ : * ? " < > |` 是非法或需转义的
     * 文件名字符：原实现直接 `"$title.m3u8"` 交给 SAF，部分 ROM 的文件选择器会拒绝，或把名字截断到
     * 第一个 `/` 之后（用户看到半截名）。这里统一替换成下划线，空名回退固定名。
     * P3-73 再加两道：长度截到 80 字符（文件名过长部分 ROM/云盘同步静默失败或截断），
     * 且避开 Windows 保留名（CON/PRN/AUX/NUL/COM1-9/LPT1-9——Windows 共享此文件时无法创建），
     * 追加下划线使其合法。
     */
    fun suggestedFileName(title: String): String {
        val safe = title
            .replace(Regex("[\\\\/:*?\"<>|]"), "_") // 文件名非法字符
            .replace(Regex("[\\u0000-\\u001F]"), "_") // 控制字符（含换行）
            .trim()
            .trim('.') // 以点结尾时部分 ROM/传输工具会丢扩展名
            .take(80)
            .trim()
            .trim('.') // take 截断可能又以点/空格结尾，再清一次
        val base = safe.ifEmpty { "歌单" }
        // Windows 保留设备名（大小写不敏感，含「保留名.任意后缀」形态，如 CON.m3u8）：
        // 主名追加下划线规避（CON → CON_），这是资源管理器/部分 SAF 实现认可的合法名
        val reserved = Regex("^(?i)(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])$")
        val stem = base.substringBeforeLast('.')
        val fixed = if (reserved.matches(stem)) "${stem}_" else stem
        val ext = if ('.' in base) "." + base.substringAfterLast('.') else ""
        return "$fixed$ext.m3u8"
    }

    /** 从 content uri 猜文件名（导入时给新歌单取名用）；P3-72：剥掉 .m3u/.m3u8 扩展名——
     *  歌单标题不该带文件后缀（兜底名 fallbackNameOf 本来就剥了，两者应一致） */
    fun displayNameOf(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            val name = if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
            // 纯 ".m3u" 这类剥完为空的极端名保留原样，避免返回空串标题
            name?.let { n -> n.substringBeforeLast('.').takeIf { it.isNotEmpty() } ?: n }
        }
    }.getOrNull()

    /** 兜底：uri 末段（某些 provider 不返回 DISPLAY_NAME） */
    fun fallbackNameOf(uri: Uri): String = File(uri.lastPathSegment ?: "导入歌单").nameWithoutExtension
}
