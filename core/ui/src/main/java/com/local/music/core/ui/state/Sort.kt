package com.local.music.core.ui.state

import com.local.music.core.ui.components.DemoSong
import java.text.Collator
import java.util.Locale

/**
 * 歌曲 tab 排序（曲库页右上角排序面板选择，快照持久化）。
 * 比较器：主键 + 标题升序兜底；文本键用 Collator 中文拼音序（本地曲库的常规预期）。
 */
enum class SongSort(val label: String) {
    Title("按标题"),
    Artist("按艺术家"),
    Album("按专辑"),
    Recent("最近添加"),
    ;

    fun comparator(): Comparator<DemoSong> {
        val collator = Collator.getInstance(Locale.CHINA)
        val byTitle = compareBy<DemoSong, String>(collator) { it.title }
        return when (this) {
            Title -> byTitle
            Artist -> compareBy<DemoSong, String>(collator) { it.artist }.then(byTitle)
            Album -> compareBy<DemoSong, String>(collator) { it.album }.then(byTitle)
            Recent -> compareByDescending<DemoSong> { it.dateAddedSec }.then(byTitle)
        }
    }
}
