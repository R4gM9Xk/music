package com.local.music.core.ui.state

import com.local.music.core.ui.components.ArtStyle
import com.local.music.core.ui.components.DemoSong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 歌曲排序单测（core:ui 模块）。
 *
 * 为什么测这个：排序比较器用中文拼音 Collator，是「曲库能不能按预期呈现」的基础。
 * 历史 bug：切换排序时若比较器不稳定，列表会抖动；「最近添加」用倒序，
 * 与其它三种的升序语义相反，容易写反。
 */
class SortTest {

    private fun song(
        title: String,
        artist: String = "artist",
        album: String = "album",
        dateAddedSec: Long = 0,
    ) = DemoSong(
        id = "f/$title.mp3",
        title = title,
        artist = artist,
        album = album,
        durationSec = 100,
        art = ArtStyle.Night,
        dateAddedSec = dateAddedSec,
    )

    @Test
    fun `按标题排序是升序`() {
        val list = listOf(song("C"), song("A"), song("B"))
        val sorted = list.sortedWith(SongSort.Title.comparator())
        assertEquals(listOf("A", "B", "C"), sorted.map { it.title })
    }

    @Test
    fun `按艺术家排序时同名艺术家内按标题兜底`() {
        val list = listOf(
            song("Z", artist = "小明"),
            song("A", artist = "小明"),
            song("M", artist = "小红"),
        )
        val sorted = list.sortedWith(SongSort.Artist.comparator())
        // 艺术家按拼音升序：小红(xiaohong) < 小明(xiaoming)，因 h < m
        assertEquals(listOf("小红", "小明", "小明"), sorted.map { it.artist })
        // 同艺术家内标题升序
        assertEquals(listOf("M", "A", "Z"), sorted.map { it.title })
    }

    @Test
    fun `按专辑排序时同名专辑内按标题兜底`() {
        val list = listOf(
            song("B", album = "专辑一"),
            song("A", album = "专辑一"),
            song("C", album = "专辑二"),
        )
        val sorted = list.sortedWith(SongSort.Album.comparator())
        // 专辑按拼音升序：专辑二(zhuánjī èr) 的「二」= er < 「一」= yi，所以专辑二在前
        assertEquals(listOf("C", "A", "B"), sorted.map { it.title })
    }

    @Test
    fun `最近添加是倒序`() {
        // 语义：最新添加的排最前。这是四种排序里唯一倒序的一种，最容易写反
        val list = listOf(
            song("old", dateAddedSec = 100),
            song("newest", dateAddedSec = 300),
            song("mid", dateAddedSec = 200),
        )
        val sorted = list.sortedWith(SongSort.Recent.comparator())
        assertEquals(listOf("newest", "mid", "old"), sorted.map { it.title })
    }

    @Test
    fun `中文标题按拼音序而不是 Unicode 码位序`() {
        // 用 Collator(Locale.CHINA) 后「从」(cong) 应排在「重」(zhong) 之前；
        // 而 Unicode 码位序恰好相反：从 U+4ECE < 重 U+91CD —— 两者都是「从」在前，
        // 所以换一对能真正区分两种排序的字：「阿」(a) vs 「八」(ba)。
        // 码位：八 U+516B < 阿 U+963F（八在前）；拼音：阿 a < 八 ba（阿在前）。
        val list = listOf(song("八"), song("阿"))
        val sorted = list.sortedWith(SongSort.Title.comparator())
        assertEquals("拼音序下『阿』(a) 应排在『八』(ba) 之前", listOf("阿", "八"), sorted.map { it.title })
    }

    @Test
    fun `排序对空列表安全`() {
        val sorted = emptyList<DemoSong>().sortedWith(SongSort.Title.comparator())
        assertTrue(sorted.isEmpty())
    }

    @Test
    fun `排序不丢元素也不改元素内容`() {
        val list = listOf(song("B"), song("A"), song("C"))
        val sorted = list.sortedWith(SongSort.Title.comparator())
        assertEquals(list.size, sorted.size)
        assertEquals(list.map { it.id }.toSet(), sorted.map { it.id }.toSet())
    }

    @Test
    fun `相同排序键时结果稳定不抖动`() {
        // 完全相同的键（同艺术家同标题）：比较器必须返回 0，否则列表会随机重排
        val a = song("同名", artist = "同人")
        val b = song("同名", artist = "同人")
        val cmp = SongSort.Title.comparator()
        assertEquals(0, cmp.compare(a, b))
        assertEquals(0, cmp.compare(b, a))
    }
}
