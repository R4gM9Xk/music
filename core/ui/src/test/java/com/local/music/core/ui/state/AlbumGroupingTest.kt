package com.local.music.core.ui.state

import com.local.music.core.ui.components.ArtStyle
import com.local.music.core.ui.components.DemoSong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 专辑模型单测（core:ui 模块）：审计 02 报告最致命缺口② 的回归防线。
 *
 * 为什么必须测这三条：它们都是「数据层面完全正确、UI 层一错就全盘皆输」的语义，
 * 而且全部**没有编译期保护**——分组键从单键换成二元组、排序从字母序换成曲序，
 * 任何一处写错都不会编译报错，只会让用户看到错的东西：
 *
 * - 同名不同艺术家的专辑被合成一张 → 用户丢了半张专辑（数据还在，但组织方式错了）
 * - 专辑内曲目是扫库顺序 → 打开专辑曲目乱序，是「一看就不是正经播放器」的缺陷
 * - 曲序编号 0（未知）被当成第 0 首 → 无编号的歌跑到专辑最前面，比不改还离谱
 *
 * 被测对象是 [AppState] 的**公开**接口（albumGroups / detailData / albumIdOf），
 * 不依赖 Android 与 MediaStore：[AppState.loadLibrary] 是曲库的注入点，传纯内存
 * [DemoSong] 即可让整条「分组 → 曲序 → 详情定位」链路跑起来，不需要 Context。
 */
class AlbumGroupingTest {

    private fun song(
        title: String,
        album: String,
        artist: String,
        albumArtist: String = "",
        discNo: Int = 0,
        trackNo: Int = 0,
    ) = DemoSong(
        id = "f/$artist/$album/$title.mp3",
        title = title,
        artist = artist,
        album = album,
        durationSec = 200,
        art = ArtStyle.Night,
        albumArtist = albumArtist,
        discNo = discNo,
        trackNo = trackNo,
    )

    /** 注入曲库并返回已装载的 AppState（绕开 MediaStore，纯内存） */
    private fun stateWith(vararg songs: DemoSong): AppState = AppState().apply {
        loadLibrary(songs.toList())
    }

    // ── 硬要求 1：同名专辑不合并 ───────────────────────────────

    @Test
    fun `同名不同艺术家的专辑必须拆成两张`() {
        val state = stateWith(
            song("A", album = "精选集", artist = "周杰伦"),
            song("B", album = "精选集", artist = "陈奕迅"),
        )
        val groups = state.albumGroups()
        assertEquals("同名不同艺术家的两张专辑必须各成一组", 2, groups.size)
    }

    @Test
    fun `同名不同艺术家分组后每组只含自己的曲目`() {
        val state = stateWith(
            song("A", album = "精选集", artist = "周杰伦"),
            song("B", album = "精选集", artist = "陈奕迅"),
        )
        val groups = state.albumGroups()
        assertEquals(listOf("A"), groups.first { it.songs.first().artist == "周杰伦" }.songs.map { it.title })
        assertEquals(listOf("B"), groups.first { it.songs.first().artist == "陈奕迅" }.songs.map { it.title })
    }

    @Test
    fun `UI 展示的专辑名仍然只有专辑名不带艺术家`() {
        // 这是本改造的核心约束：分组键变复杂了，但**展示名不能被污染**。
        // 旧实现若图省事把 key 改成 "$album|$artist"，界面上就会出现「精选集|周杰伦」。
        val state = stateWith(
            song("A", album = "精选集", artist = "周杰伦"),
            song("B", album = "精选集", artist = "陈奕迅"),
        )
        val keys = state.albumGroups().map { it.key }
        assertEquals(listOf("精选集", "精选集"), keys)
        assertTrue("展示名里不得出现用于内部定位的分隔符", keys.none { it.contains('\u0000') })
    }

    @Test
    fun `两张同名专辑的展示名相同但定位 id 不同`() {
        // key 负责展示（可以重复），id 负责定位（必须唯一）——二者的分离是整套设计的地基
        val state = stateWith(
            song("A", album = "精选集", artist = "周杰伦"),
            song("B", album = "精选集", artist = "陈奕迅"),
        )
        val groups = state.albumGroups()
        assertEquals("展示名应当相同", 1, groups.map { it.key }.distinct().size)
        assertEquals("定位 id 必须互异", 2, groups.map { it.id }.distinct().size)
    }

    // ── 硬要求 2：详情页能唯一定位到正确的那张专辑 ─────────────

    @Test
    fun `点进同名专辑的第二张会命中它自己而不是第一张`() {
        val state = stateWith(
            song("A", album = "精选集", artist = "周杰伦"),
            song("B", album = "精选集", artist = "陈奕迅"),
        )
        val target = state.albumGroups().first { it.songs.first().artist == "陈奕迅" }
        val data = state.detailData(com.local.music.core.ui.components.DetailTarget(
            com.local.music.core.ui.components.DetailTarget.Kind.Album, target.id,
        ))
        assertEquals("必须命中陈奕迅那本《精选集》", listOf("B"), data?.tracks?.map { it.title })
    }

    @Test
    fun `按纯专辑名定位会退化成命中其中一张所以必须用 id`() {
        // 记录这个陷阱本身：这是「为什么调用方不能传 album.key」的可执行说明。
        // 两个 key 完全相同 → 用 key 当 DetailTarget.id 必然只能命中其中一个。
        val state = stateWith(
            song("A", album = "精选集", artist = "周杰伦"),
            song("B", album = "精选集", artist = "陈奕迅"),
        )
        val groups = state.albumGroups()
        assertEquals("两个分组的 key 相同（所以 key 不能当定位键）", groups[0].key, groups[1].key)
        assertNotEquals("两个分组的 id 必须不同", groups[0].id, groups[1].id)
    }

    @Test
    fun `albumIdOf 反查出的 id 与分组 id 完全一致`() {
        // 「长按歌曲 → 查看专辑」走 albumIdOf 那条路，它必须与分组用同一套编码，
        // 否则从歌单里点进专辑会得到与曲库 tab 点进去不同的 id → 定位失败
        val s = song("A", album = "精选集", artist = "周杰伦")
        val state = stateWith(s, song("B", album = "精选集", artist = "陈奕迅"))
        val group = state.albumGroups().first { it.songs.first().artist == "周杰伦" }
        assertEquals(group.id, state.albumIdOf(s))
    }

    @Test
    fun `专辑艺术家缺失时回退到曲目艺术家参与分组`() {
        // 绝大多数普通专辑没有 ALBUM_ARTIST 标签（空串）→ 回退 artist，等价于改造前行为。
        // 这条保证「没打标签的库升级后不会被无端拆开或合并」。
        val state = stateWith(
            song("A", album = "叶惠美", artist = "周杰伦"), // albumArtist 默认空串
            song("B", album = "叶惠美", artist = "周杰伦"),
        )
        val groups = state.albumGroups()
        assertEquals("同专辑同艺术家 → 仍是一张", 1, groups.size)
        assertEquals(2, groups.first().songs.size)
    }

    @Test
    fun `有专辑艺术家标签时以标签为准而不是曲目艺术家`() {
        // 合辑场景：各曲 artist 不同，但 albumArtist 统一 = "群星" → 必须是一张专辑
        val state = stateWith(
            song("A", album = "经典合辑", artist = "歌手甲", albumArtist = "群星"),
            song("B", album = "经典合辑", artist = "歌手乙", albumArtist = "群星"),
        )
        assertEquals("合辑不该被拆成两张", 1, state.albumGroups().size)
    }

    @Test
    fun `专辑艺术家不同的合辑是同名也要拆开`() {
        val state = stateWith(
            song("A", album = "精选", artist = "甲", albumArtist = "群星"),
            song("B", album = "精选", artist = "乙", albumArtist = "某某唱片"),
        )
        assertEquals(2, state.albumGroups().size)
    }

    // ── 硬要求 3：专辑内按曲序排序，未知编号排末尾 ─────────────

    @Test
    fun `专辑内按音轨号升序而不是字母序`() {
        val state = stateWith(
            song("C 第三首", album = "专辑", artist = "甲", trackNo = 3),
            song("A 第一首", album = "专辑", artist = "甲", trackNo = 1),
            song("B 第二首", album = "专辑", artist = "甲", trackNo = 2),
        )
        assertEquals(
            listOf("A 第一首", "B 第二首", "C 第三首"),
            state.albumGroups().first().songs.map { it.title },
        )
    }

    @Test
    fun `音轨号为 0 表示未知必须排在末尾而不是排最前`() {
        // 这是最容易写反的一条：写成 `sortedBy { it.trackNo }` 会让 0 排最前，
        // 于是这张专辑里「恰好没编号」的歌会跑到正数第 1 首之前，比改造前的字母序更离谱
        val state = stateWith(
            song("未知轨", album = "专辑", artist = "甲", trackNo = 0),
            song("第二首", album = "专辑", artist = "甲", trackNo = 2),
            song("第一首", album = "专辑", artist = "甲", trackNo = 1),
        )
        assertEquals(
            listOf("第一首", "第二首", "未知轨"),
            state.albumGroups().first().songs.map { it.title },
        )
    }

    @Test
    fun `多碟专辑先按碟号再按音轨号`() {
        val state = stateWith(
            song("碟2轨1", album = "双碟", artist = "甲", discNo = 2, trackNo = 1),
            song("碟1轨2", album = "双碟", artist = "甲", discNo = 1, trackNo = 2),
            song("碟1轨1", album = "双碟", artist = "甲", discNo = 1, trackNo = 1),
        )
        assertEquals(
            listOf("碟1轨1", "碟1轨2", "碟2轨1"),
            state.albumGroups().first().songs.map { it.title },
        )
    }

    @Test
    fun `碟号为 0 未知也排在有碟号的之后`() {
        val state = stateWith(
            song("未标碟", album = "双碟", artist = "甲", discNo = 0, trackNo = 1),
            song("碟1", album = "双碟", artist = "甲", discNo = 1, trackNo = 2),
        )
        assertEquals(
            listOf("碟1", "未标碟"),
            state.albumGroups().first().songs.map { it.title },
        )
    }

    @Test
    fun `同碟同轨时按中文标题拼音序稳定排序`() {
        // 稳定性要求：曲库重扫、列表重组都不能让同键曲目顺序抖动
        val a = song("阿歌", album = "专辑", artist = "甲", discNo = 1, trackNo = 1)
        val b = song("八歌", album = "专辑", artist = "甲", discNo = 1, trackNo = 1)
        // 拼音序：阿(a) < 八(ba)；若按 Unicode 码位则相反
        assertEquals(listOf("阿歌", "八歌"), stateWith(b, a).albumGroups().first().songs.map { it.title })
        assertEquals("排序必须与输入顺序无关", listOf("阿歌", "八歌"), stateWith(a, b).albumGroups().first().songs.map { it.title })
    }

    @Test
    fun `全部未知编号时退化为标题序而不是保持扫描序`() {
        val state = stateWith(
            song("C", album = "专辑", artist = "甲"),
            song("A", album = "专辑", artist = "甲"),
            song("B", album = "专辑", artist = "甲"),
        )
        assertEquals(listOf("A", "B", "C"), state.albumGroups().first().songs.map { it.title })
    }

    // ── 详情页曲目顺序：专辑详情的 tracks 必须是排序后的 ───────

    @Test
    fun `专辑详情页的 tracks 是曲序而不是扫描序`() {
        // PlaylistDetailScreen 直接吃 data.tracks（不做二次排序），
        // 所以排序必须发生在 detailData 这一层，否则详情页又是乱序
        val state = stateWith(
            song("第三", album = "专辑", artist = "甲", trackNo = 3),
            song("第一", album = "专辑", artist = "甲", trackNo = 1),
            song("第二", album = "专辑", artist = "甲", trackNo = 2),
        )
        val target = state.albumGroups().first()
        val data = state.detailData(com.local.music.core.ui.components.DetailTarget(
            com.local.music.core.ui.components.DetailTarget.Kind.Album, target.id,
        ))
        assertEquals(listOf("第一", "第二", "第三"), data?.tracks?.map { it.title })
    }

    // ── albumCountByArtist 口径自洽 ───────────────────────────

    @Test
    fun `艺术家专辑数按专辑分组结果统计不会多算`() {
        // 同名不同艺术家的两张专辑，各自只能算到各自艺术家头上 1 张
        val state = stateWith(
            song("A", album = "精选集", artist = "周杰伦"),
            song("B", album = "精选集", artist = "陈奕迅"),
        )
        val counts = state.albumCountByArtist()
        assertEquals(1, counts["周杰伦"])
        assertEquals(1, counts["陈奕迅"])
    }

    @Test
    fun `同一艺术家的两张不同专辑会被计入两张`() {
        val state = stateWith(
            song("A", album = "专辑一", artist = "周杰伦"),
            song("B", album = "专辑二", artist = "周杰伦"),
        )
        assertEquals(2, state.albumCountByArtist()["周杰伦"])
    }

    @Test
    fun `空曲库分组安全不崩溃`() {
        val state = AppState()
        assertTrue(state.albumGroups().isEmpty())
        assertTrue(state.albumCountByArtist().isEmpty())
    }
}
