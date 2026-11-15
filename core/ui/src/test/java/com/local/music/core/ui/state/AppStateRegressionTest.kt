package com.local.music.core.ui.state

import com.local.music.core.ui.components.ArtStyle
import com.local.music.core.ui.components.DemoSong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 本轮审计修复的回归测试（P0/P1/P2 各自的最小可断言面）。
 *
 * ## 为什么这些测试值得单独一个文件
 *
 * 本轮修的缺陷有一个共同特征：**它们都不是「算法算错了」，而是「两个模块对同一件事的
 * 理解不一致」**——队列面板以为曲目 id 唯一、UI 以为空队列也能进播放页、服务端以为
 * 「播完当前首」只有 AUTO 一种触发、设置页以为自己的二级层级只有自己知道。
 * 这类缺陷的单测价值极高：它们能在纯 JVM 下把「契约」钉死，而真机验证只能事后发现。
 *
 * ## 覆盖范围（每条对应一份审计结论）
 *
 * | 测试 | 对应缺陷 |
 * |---|---|
 * | `openPlayer 队列为空时拒绝切屏` | P1 空曲库/空歌单进入占位播放页 |
 * | `openPlayer 有队列时正常切屏并记录来源` | 同上（正向对照，防「修过头」） |
 * | `playCollection 空集合不做任何事` | P1 空歌单点播放 |
 * | `playCollection 随机时把 repeatMode 抬到 All` | P2 随机入口不统一四态 |
 * | `playCollection 非随机不动 repeatMode` | 防「顺手把用户的循环偏好抹掉」 |
 * | `playCollection 透传来源目标` | P2 集合入口的来源回跳失效 |
 * | `队列允许重复曲目` | P0 队列面板重复 key 崩溃的**前提**（状态层必须继续允许） |
 * | `searchArtistGroups 按艺术家名与曲目标题匹配` | P2 搜索承诺艺术家但无结果 |
 * | `searchArtistGroups 空查询返回空表` | 防整库分组泄漏到空搜索 |
 * | `backInSettings 逐层退并最终返回 false` | P1 设置二级页系统返回 |
 * | `openSettingsGroup 记录三级页的返回目标` | 同一处的三层导航 |
 * | `navigationState 往返保住曲库 tab 与滚动位置` | P2 导航状态跨屏/重建丢失 |
 * | `rememberLibraryScroll 钳负值` | 防御性：坏数据不应让列表崩 |
 * | `visualStyle 默认玻璃且可切换` | 新功能：拟物风的持久化基元 |
 * | `snapshot 往返保住 visualStyle` | 快照 v12 新增字段 |
 *
 * 刻意**没有**测的（避免假覆盖）：拟物风的实际渲染（像素级，属真机/截图验收）、
 * 服务端 sleepTimer 的 REPEAT/AUTO 分支（在 :app 模块且依赖 Media3 Player，
 * 纯 JVM 无 Player 实现）。
 */
class AppStateRegressionTest {

    // ── 夹具 ──────────────────────────────────────────────

    private fun song(id: String, durationSec: Int = 180): DemoSong = DemoSong(
        id = "f/$id.mp3",
        title = id,
        artist = "歌手$id",
        album = "专辑$id",
        durationSec = durationSec,
        art = ArtStyle.Night,
        mediaUri = "content://media/$id",
    )

    /** 空队列状态（曲库也没装载）：openPlayer 必须拒绝 */
    private fun emptyState(): AppState = AppState()

    private fun stateWith(vararg songs: DemoSong): AppState = AppState().apply {
        loadLibrary(songs.toList())
    }

    // ── P1：空队列不得进入播放页 ─────────────────────────────

    @Test
    fun `openPlayer 队列为空时拒绝切屏`() {
        val state = emptyState()
        state.screen = GlassScreen.Library

        val opened = state.openPlayer()

        assertFalse("空队列时 openPlayer 必须返回 false", opened)
        assertEquals(
            "空队列时不得切到播放页（否则用户落进「暂无歌曲」的占位页，控制项全死）",
            GlassScreen.Library,
            state.screen,
        )
    }

    @Test
    fun `openPlayer 有队列时正常切屏并记录来源`() {
        val state = stateWith(song("A"))
        state.screen = GlassScreen.Library

        val opened = state.openPlayer()

        assertTrue("有队列时必须能进播放页", opened)
        assertEquals("进播放页", GlassScreen.Player, state.screen)
        assertEquals("来源页记为进入前那一页", GlassScreen.Library, state.playerOrigin)
    }

    // ── P1/P2：playCollection 的三条语义 ──────────────────────

    @Test
    fun `playCollection 空集合不做任何事`() {
        val state = stateWith(song("A"))
        val before = state.queue.map { it.id }
        val beforeIndex = state.queueIndex

        state.playCollection(emptyList(), source = "空歌单", shuffle = false)

        assertEquals("空集合不得改动队列", before, state.queue.map { it.id })
        assertEquals("空集合不得改动索引", beforeIndex, state.queueIndex)
        assertNull("空集合不得写入来源标签", state.queueSource)
    }

    @Test
    fun `playCollection 随机时把 repeatMode 抬到 All`() {
        val a = song("A")
        val b = song("B")
        val state = stateWith(a, b)
        // 顺序播放态：shuffle=false + repeat=Off
        state.shuffleOn = false
        state.repeatMode = RepeatMode.Off

        state.playCollection(listOf(a, b), source = "歌单", shuffle = true)

        assertTrue("随机入口必须打开 shuffle", state.shuffleOn)
        assertEquals(
            "随机入口必须把 repeat 抬到 All——否则播完队列即停，与四态按钮里「随机播放」的定义不符",
            RepeatMode.All,
            state.repeatMode,
        )
    }

    @Test
    fun `playCollection 非随机不动 repeatMode`() {
        val a = song("A")
        val b = song("B")
        val state = stateWith(a, b)
        state.repeatMode = RepeatMode.One

        state.playCollection(listOf(a, b), source = "歌单", shuffle = false)

        assertFalse("播放全部不得偷偷开随机", state.shuffleOn)
        assertEquals(
            "播放全部不得抹掉用户已有的循环偏好（单曲循环应原样保留）",
            RepeatMode.One,
            state.repeatMode,
        )
    }

    @Test
    fun `playCollection 透传来源目标`() {
        val a = song("A")
        val state = stateWith(a)
        val target = com.local.music.core.ui.components.DetailTarget(
            com.local.music.core.ui.components.DetailTarget.Kind.Playlist,
            "pl-midnight",
        )

        state.playCollection(listOf(a), source = "深夜巡航", shuffle = false, sourceTarget = target)

        assertEquals(
            "集合播放必须透传 sourceTarget——否则播放页「来自『…』」不可点回详情，" +
                "而单曲行播放可以，同一入口两种行为",
            target,
            state.queueSourceTarget,
        )
    }

    // ── P0 前提：队列必须继续允许重复曲目 ─────────────────────
    //
    // 队列面板的 LazyColumn key 已从「曲目 id」改成「段内下标」来承受重复。
    // 这条测试守住**状态层不能倒退成去重**——一旦有人为了「让 key 唯一」而在
    // insertQueueItems 里去重，用户「下一首播放两次同一首歌」的意图就被静默吞掉了，
    // 而 UI 侧修复会被掩盖成「看起来好了」。

    @Test
    fun `队列允许重复曲目`() {
        val a = song("A")
        val b = song("B")
        val state = stateWith(a, b)
        state.playSong(a, listOf(a, b))

        state.playNext(a)
        state.playNext(a)

        assertEquals(
            "同一首歌可以被多次「下一首播放」（状态层不得去重）",
            listOf(a.id, a.id, a.id, b.id),
            state.queue.map { it.id },
        )
    }

    // ── P2：搜索艺术家分组 ───────────────────────────────────

    @Test
    fun `searchArtistGroups 按艺术家名与曲目标题匹配`() {
        val a = DemoSong(
            id = "f/a.mp3", title = "晴天", artist = "周杰伦", album = "叶惠美",
            durationSec = 200, art = ArtStyle.Night, mediaUri = "content://media/a",
        )
        val b = DemoSong(
            id = "f/b.mp3", title = "七里香", artist = "周杰伦", album = "七里香",
            durationSec = 220, art = ArtStyle.Night, mediaUri = "content://media/b",
        )
        val c = DemoSong(
            id = "f/c.mp3", title = "夜曲", artist = "其他歌手", album = "十一月的萧邦",
            durationSec = 240, art = ArtStyle.Night, mediaUri = "content://media/c",
        )
        val state = stateWith(a, b, c)

        val byArtist = state.searchArtistGroups("周杰伦")
        assertEquals("按艺术家名命中", listOf("周杰伦"), byArtist.map { it.key })

        val byTitle = state.searchArtistGroups("晴天")
        assertEquals(
            "按曲目标题命中时该艺术家也要出现（搜到一首歌时顺着进艺术家详情是常见意图）",
            listOf("周杰伦"),
            byTitle.map { it.key },
        )

        assertTrue("未命中任何分组", state.searchArtistGroups("不存在的词").isEmpty())
    }

    @Test
    fun `searchArtistGroups 空查询返回空表`() {
        val state = stateWith(song("A"))
        assertTrue("空查询不得返回整库分组", state.searchArtistGroups("").isEmpty())
        assertTrue("纯空白查询同样不得返回整库分组", state.searchArtistGroups("   ").isEmpty())
    }

    // ── P1：设置二级页返回 ────────────────────────────────────

    @Test
    fun `backInSettings 逐层退并最终返回 false`() {
        val state = emptyState()
        assertFalse("设置首页时返回 false（交给宿主走「设置 → 曲库」）", state.backInSettings())

        state.openSettingsGroup("外观")
        assertEquals("进入二级页", "外观", state.settingsGroup)

        assertTrue("二级页时返回 true（已消费）", state.backInSettings())
        assertNull("退出到设置首页", state.settingsGroup)

        assertFalse("回到首页后再次返回应返回 false", state.backInSettings())
    }

    @Test
    fun `openSettingsGroup 记录三级页的返回目标`() {
        val state = emptyState()
        state.openSettingsGroup("关于")
        // 从「关于」再进「支持开发者」：返回要回「关于」而不是首页
        state.openSettingsGroup("支持开发者", returnGroup = "关于")
        assertEquals("当前在三级页", "支持开发者", state.settingsGroup)

        assertTrue("三级页返回被消费", state.backInSettings())
        assertEquals("返回目标是「关于」，不是设置首页", "关于", state.settingsGroup)
        assertNull("返回目标是一次性的，已清空", state.settingsReturnGroup)

        assertTrue("再退一层", state.backInSettings())
        assertNull("回到设置首页", state.settingsGroup)
    }

    // ── P2：导航状态跨屏/重建保留 ────────────────────────────

    @Test
    fun `navigationState 往返保住曲库 tab 与滚动位置`() {
        val state = stateWith(song("A"))
        state.libraryTab = 4
        state.rememberLibraryScroll(index = 37, offset = 512)
        state.playlistScroll = 88

        val saved = state.navigationState()

        // 模拟 Activity 重建：全新 AppState 只靠这份快照还原
        val restored = AppState().apply { restoreNavigationState(saved) }

        assertEquals("曲库 tab 必须跨重建保留", 4, restored.libraryTab)
        assertEquals("曲库滚动下标必须保留", 37, restored.libraryScrollIndex)
        assertEquals("曲库滚动像素偏移必须保留（只存下标会让高行列表差半屏）", 512, restored.libraryScrollOffset)
        assertEquals("歌单页滚动位置必须保留", 88, restored.playlistScroll)
    }

    @Test
    fun `rememberLibraryScroll 钳负值`() {
        val state = emptyState()
        state.rememberLibraryScroll(index = -5, offset = -1)
        assertEquals("负下标钳 0", 0, state.libraryScrollIndex)
        assertEquals("负偏移钳 0", 0, state.libraryScrollOffset)
    }

    @Test
    fun `restoreNavigationState 容忍短列表`() {
        val state = stateWith(song("A"))
        state.libraryTab = 3
        // 旧版本只存了 6 项（没有 tab/滚动字段）：短列表必须按位还原已认识的项，
        // 而不是整份作废
        state.restoreNavigationState(
            listOf(GlassScreen.Library.name, "Playlist", "pl-midnight", "Playlist", "Library", "关键词"),
        )
        assertEquals("已认识的项照常还原", "关键词", state.searchQuery)
        assertEquals("未知字段保留当前值（不清零）", 3, state.libraryTab)
    }

    // ── 新功能：拟物风的持久化基元 ────────────────────────────

    @Test
    fun `visualStyle 默认玻璃且可切换`() {
        val state = emptyState()
        assertEquals("默认必须是液态玻璃（老用户升级观感逐像素不变）", VisualStyle.Glass, state.visualStyle)

        state.selectVisualStyle(VisualStyle.Skeuomorph)
        assertEquals("切换生效", VisualStyle.Skeuomorph, state.visualStyle)
    }

    @Test
    fun `snapshot 往返保住 visualStyle`() {
        val state = stateWith(song("A"))
        state.selectVisualStyle(VisualStyle.Skeuomorph)

        val snap = state.snapshot()
        assertEquals("快照必须带上视觉风格（v12）", VisualStyle.Skeuomorph.name, snap.visualStyle)

        val restored = AppState().apply { restore(snap) }
        assertEquals("恢复后仍是拟物", VisualStyle.Skeuomorph, restored.visualStyle)
    }

    @Test
    fun `restore 对未知 visualStyle 回落玻璃`() {
        val state = stateWith(song("A"))
        val snap = state.snapshot().copy(visualStyle = "NoSuchStyle")
        state.restore(snap)
        assertEquals(
            "坏数据回落玻璃而不是让整份快照解码失败（风格是装饰，不是用户数据）",
            VisualStyle.Glass,
            state.visualStyle,
        )
    }

    // ── P2：搜索历史清空 ─────────────────────────────────────

    @Test
    fun `搜索历史可清空与单条删除`() {
        val state = emptyState()
        state.addSearchHistory("晴天")
        state.addSearchHistory("七里香")
        state.addSearchHistory("夜曲")
        assertEquals("新词在前", listOf("夜曲", "七里香", "晴天"), state.searchHistory.toList())

        state.removeSearchHistory("七里香")
        assertEquals("单条删除", listOf("夜曲", "晴天"), state.searchHistory.toList())

        state.clearSearchHistory()
        assertTrue("全部清空", state.searchHistory.isEmpty())
    }
}
