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
 * 未命中项汇总返回给 UI 提示（不静默丢弃）。
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
            // #EXTINF:<duration>,<title>；艺术家信息放进标题便于其他播放器展示。
            // 显示文本/路径含换行会破坏 M3U 行结构：写出前压成空格并 trim（标题中的逗号无碍——导入按首个逗号取显示文本）
            sb.append("#EXTINF:").append(s.durationSec).append(',')
                .append(oneLine(s.artist)).append(" - ").append(oneLine(s.title)).append('\n')
            sb.append(oneLine(pathOf(s))).append('\n')
        }
        context.contentResolver.openOutputStream(uri)?.use { out ->
            out.write(sb.toString().toByteArray(Charsets.UTF_8))
        } ?: error("无法写入所选位置")
        return ExportResult(songs.size, songs.size)
    }

    /** 导入结果：命中的曲目 id（按文件内顺序去重）+ 未命中行（供 UI 汇总提示） */
    data class ImportResult(
        val matchedIds: List<String>,
        val unmatched: List<String>,
        val title: String?,
    )

    /**
     * 解析 M3U/M3U8：[library] 是当前曲库（用绝对路径建索引）。
     * 支持 #EXTM3U/#PLAYLIST 头、#EXTINF 行、注释行（# 开头）、空行、相对路径（忽略）。
     */
    fun import(context: Context, uri: Uri, library: List<DemoSong>): ImportResult {
        val text = context.contentResolver.openInputStream(uri)?.use {
            it.readBytes().toString(Charsets.UTF_8)
        } ?: error("无法读取所选文件")
        return parse(text, library)
    }

    /** 纯解析（可单测）：从文本还原曲目 id 列表 */
    fun parse(text: String, library: List<DemoSong>): ImportResult {
        // 路径 → 曲目（id = "f" + 绝对路径）
        val byPath = library.associateBy { pathOf(it) }
        var title: String? = null
        val matched = LinkedHashSet<String>()
        val unmatched = mutableListOf<String>()
        text.lineSequence().forEach { raw ->
            val line = raw.trim().removePrefix("\uFEFF")
            when {
                line.isEmpty() -> Unit
                line.startsWith("#EXTM3U", ignoreCase = true) -> Unit
                line.startsWith("#PLAYLIST:", ignoreCase = true) -> title = line.substringAfter(':').trim()
                line.startsWith("#EXTINF", ignoreCase = true) -> Unit // 时长/标题仅展示用，匹配靠下一行路径；若日后取显示文本，须按「首个逗号」分隔（其后全部归显示文本），含逗号标题才不会截断
                line.startsWith("#") -> Unit // 其他注释
                else -> {
                    val song = byPath[line]
                    if (song != null) matched += song.id else unmatched += line
                }
            }
        }
        return ImportResult(matched.toList(), unmatched, title)
    }

    /** 曲目的文件路径：id = "f" + 绝对路径；无 mediaUri 的条目用 id 兜底 */
    private fun pathOf(song: DemoSong): String =
        song.id.removePrefix("f").ifEmpty { song.mediaUri ?: "" }

    /** 单行化：\n/\r 压成空格并 trim——M3U 行结构不容忍换行（#EXTINF 显示文本与路径行写出前统一走这里） */
    private fun oneLine(s: String): String = s.replace(Regex("[\\r\\n]+"), " ").trim()

    /** 从 content uri 猜文件名（导入时给新歌单取名用） */
    fun displayNameOf(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
        }
    }.getOrNull()

    /** 兜底：uri 末段（某些 provider 不返回 DISPLAY_NAME） */
    fun fallbackNameOf(uri: Uri): String = File(uri.lastPathSegment ?: "导入歌单").nameWithoutExtension
}
