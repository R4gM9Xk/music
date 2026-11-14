package com.local.music.core.ui.state

import com.local.music.core.ui.components.ArtStyle
import com.local.music.core.ui.components.DemoSong
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 排序面板接线回归防线（core:ui 模块）。
 *
 * 真机报告曾记录「点按艺术家后面板关闭但列表顺序不变」。该症状的接线是
 * 面板 onClick → [AppState.selectSongSort] → songSort → 曲库页 remember key →
 * [AppState.sortedSongs]。比较器本身有 SortTest 覆盖，但「选择是否真的驱动
 * 列表重排」这一段没有编译期保护：remember key 漏掉 songSort、比较器缓存
 * 键写错、selectSongSort 只持久化不写状态，都会让面板「点了没反应」。
 * 这里直接断言公开接口：切换排序后 sortedSongs 的顺序必须随之改变。
 */
class SortWiringTest {

    private fun song(title: String, artist: String, album: String, added: Long) = DemoSong(
        id = "f/$title.mp3",
        title = title,
        artist = artist,
        album = album,
        durationSec = 200,
        art = ArtStyle.Night,
        dateAddedSec = added,
    )

    // 标题拼音序 阿(a) < 八(ba)；艺术家/专辑/最近添加序均与之相反，便于区分档位
    private val a = song("阿歌", artist = "Zed", album = "Zalbum", added = 100)
    private val b = song("八歌", artist = "Amy", album = "Aalbum", added = 200)

    private fun stateWith(vararg songs: DemoSong): AppState = AppState().apply {
        loadLibrary(songs.toList())
    }

    @Test
    fun `默认按标题`() {
        val state = stateWith(a, b)
        assertEquals(SongSort.Title, state.songSort)
        assertEquals(listOf("阿歌", "八歌"), state.sortedSongs().map { it.title })
    }

    @Test
    fun `选择按艺术家后列表立即重排`() {
        val state = stateWith(a, b)
        state.selectSongSort(SongSort.Artist)
        assertEquals(SongSort.Artist, state.songSort)
        assertEquals(listOf("八歌", "阿歌"), state.sortedSongs().map { it.title })
    }

    @Test
    fun `选择按专辑后列表立即重排`() {
        val state = stateWith(a, b)
        state.selectSongSort(SongSort.Album)
        assertEquals(listOf("八歌", "阿歌"), state.sortedSongs().map { it.title })
    }

    @Test
    fun `最近添加按入库时间倒序`() {
        val state = stateWith(a, b)
        state.selectSongSort(SongSort.Recent)
        assertEquals(listOf("八歌", "阿歌"), state.sortedSongs().map { it.title })
    }

    @Test
    fun `切回标题时比较器缓存不得残留上一档`() {
        val state = stateWith(a, b)
        state.selectSongSort(SongSort.Artist)
        assertEquals(listOf("八歌", "阿歌"), state.sortedSongs().map { it.title })
        state.selectSongSort(SongSort.Title)
        assertEquals(listOf("阿歌", "八歌"), state.sortedSongs().map { it.title })
    }

    @Test
    fun `切换排序不丢元素`() {
        val state = stateWith(a, b)
        SongSort.entries.forEach { sort ->
            state.selectSongSort(sort)
            assertEquals("排序 $sort 不得丢元素", 2, state.sortedSongs().size)
        }
    }
}