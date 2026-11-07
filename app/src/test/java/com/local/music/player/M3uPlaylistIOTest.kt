package com.local.music.player

import com.local.music.core.ui.components.ArtStyle
import com.local.music.core.ui.components.DemoSong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M3U/M3U8 解析单测。
 *
 * 为什么测这个：`parse` 是纯函数（无 Context、无 IO），但它的正确性直接决定
 * 「用户从别的播放器导入歌单能不能成功」——这是换机迁移的唯一通道，坏了用户不会报错，
 * 只会觉得「导入没反应」。历史上有 P2-21（路径归一）和 P3-34（未命中重复计数）两个真 bug。
 */
class M3uPlaylistIOTest {

    private fun song(path: String, title: String = path.substringAfterLast('/')) = DemoSong(
        id = "f$path", // id 约定 = "f" + 绝对路径；pathOf() 从这里还原
        title = title,
        artist = "artist",
        album = "album",
        durationSec = 200,
        art = ArtStyle.Night,
        folder = path.substringBeforeLast('/', "音乐").substringAfterLast('/'),
    )

    private val lib = listOf(
        song("/storage/emulated/0/Music/a.mp3", "A"),
        song("/storage/emulated/0/Music/b.flac", "B"),
        song("/storage/emulated/0/Music/sub/c.m4a", "C"),
    )

    @Test
    fun `标准 M3U8 能解析出匹配曲目与标题`() {
        val text = """
            #EXTM3U
            #PLAYLIST:我的歌单
            #EXTINF:200,Artist - A
            /storage/emulated/0/Music/a.mp3
            #EXTINF:180,Artist - B
            /storage/emulated/0/Music/b.flac
        """.trimIndent()

        val r = M3uPlaylistIO.parse(text, lib)

        assertEquals(listOf("f/storage/emulated/0/Music/a.mp3", "f/storage/emulated/0/Music/b.flac"), r.matchedIds)
        assertEquals("我的歌单", r.title)
        assertTrue(r.unmatched.isEmpty())
    }

    @Test
    fun `sdcard 别名与 storage 路径应视为同一文件`() {
        // P2-21 回归：这是「导入基本不可用」的历史根因
        val text = """
            #EXTM3U
            /sdcard/Music/a.mp3
            /storage/emulated/0/Music/b.flac
        """.trimIndent()

        val r = M3uPlaylistIO.parse(text, lib)

        assertEquals(2, r.matchedIds.size)
        assertTrue(r.unmatched.isEmpty())
    }

    @Test
    fun `反斜杠分隔符应被归一`() {
        val text = "#EXTM3U\n/storage/emulated/0/Music/sub\\c.m4a"
        val r = M3uPlaylistIO.parse(text, lib)
        assertEquals(listOf("f/storage/emulated/0/Music/sub/c.m4a"), r.matchedIds)
    }

    @Test
    fun `file 协议前缀应被剥离`() {
        val text = "#EXTM3U\nfile:///storage/emulated/0/Music/a.mp3"
        val r = M3uPlaylistIO.parse(text, lib)
        assertEquals(listOf("f/storage/emulated/0/Music/a.mp3"), r.matchedIds)
    }

    @Test
    fun `未命中的绝对路径进 unmatched`() {
        val text = "#EXTM3U\n/storage/emulated/0/Music/nothere.mp3"
        val r = M3uPlaylistIO.parse(text, lib)
        assertTrue(r.matchedIds.isEmpty())
        assertEquals(listOf("/storage/emulated/0/Music/nothere.mp3"), r.unmatched)
    }

    @Test
    fun `相对路径单独归类到 ignored 而不是 unmatched`() {
        // P3-34：相对路径无法对位本机曲库，与「绝对路径未命中」是两种问题，UI 提示不同
        val text = "#EXTM3U\nMusic/a.mp3"
        val r = M3uPlaylistIO.parse(text, lib)
        assertEquals(listOf("Music/a.mp3"), r.ignored)
        assertTrue(r.unmatched.isEmpty())
    }

    @Test
    fun `同一缺失路径重复出现时只计一次`() {
        // P3-34 回归：LinkedHashSet 去重
        val text = """
            #EXTM3U
            /storage/emulated/0/Music/ghost.mp3
            /storage/emulated/0/Music/ghost.mp3
            /storage/emulated/0/Music/ghost.mp3
        """.trimIndent()

        val r = M3uPlaylistIO.parse(text, lib)
        assertEquals(1, r.unmatched.size)
    }

    @Test
    fun `同一曲目重复出现时只匹配一次`() {
        val text = "#EXTM3U\n/storage/emulated/0/Music/a.mp3\n/storage/emulated/0/Music/a.mp3"
        val r = M3uPlaylistIO.parse(text, lib)
        assertEquals(1, r.matchedIds.size)
    }

    @Test
    fun `注释行与空行被忽略`() {
        val text = """
            #EXTM3U
            # 这是一行注释
            #EXTINF:100,Someone - Something

            /storage/emulated/0/Music/a.mp3
        """.trimIndent()

        val r = M3uPlaylistIO.parse(text, lib)
        assertEquals(1, r.matchedIds.size)
    }

    @Test
    fun `空歌单返回全空结果而不是崩溃`() {
        val r = M3uPlaylistIO.parse("", lib)
        assertTrue(r.matchedIds.isEmpty())
        assertTrue(r.unmatched.isEmpty())
        assertEquals(null, r.title)
    }

    @Test
    fun `BOM 前缀不应破坏首行解析`() {
        val text = "\uFEFF#EXTM3U\n/storage/emulated/0/Music/a.mp3"
        val r = M3uPlaylistIO.parse(text, lib)
        assertEquals(1, r.matchedIds.size)
    }
}
