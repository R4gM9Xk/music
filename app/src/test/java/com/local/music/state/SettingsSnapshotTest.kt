package com.local.music.state

import com.local.music.core.ui.state.AppStateSnapshot
import com.local.music.core.ui.state.PlaylistSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 快照编解码 / 备份格式单测。
 *
 * 为什么测这个：这是**用户数据安全**的唯一防线。
 * - 快照解码错 = 用户攒了两年的歌单/收藏/播放次数全丢；
 * - 版本号判断错 = 新版数据被旧版静默拆解成半空快照（P3-62 修的就是这个）；
 * - 备份格式错 = 换机迁移直接失效（P0 缺口）。
 *
 * `SettingsStore.Companion` 的 encode/decode 不依赖 Context（DataStore 的读写才需要），
 * 所以可以直接单测——这是本项目里少数「架构上已经可测」的部分。
 */
class SettingsSnapshotTest {

    /** 最小合法快照：只填必填字段，其余走默认值 */
    private val empty = AppStateSnapshot(
        followCover = true,
        lyricEnabled = true,
        glassLevel = "Standard",
        motionLevel = "Standard",
        eqPreset = "Off",
        themeMode = "Dark",
        excludedFolders = emptySet(),
        favorites = emptySet(),
        searchHistory = emptyList(),
        playedIds = emptyList(),
        userPlaylists = emptyList(),
        addedTracks = emptyMap(),
        queueIds = emptyList(),
        queueIndex = 0,
        progressSec = 0f,
    )

    private fun roundTrip(s: AppStateSnapshot): AppStateSnapshot? =
        SettingsStore.decodeBackup(SettingsStore.encodeForBackup(s))

    // ── 基本往返 ────────────────────────────────────────────

    @Test
    fun `空快照往返不丢字段`() {
        val back = roundTrip(empty)
        assertNotNull(back)
        assertEquals(empty.followCover, back!!.followCover)
        assertEquals(empty.lyricEnabled, back.lyricEnabled)
        assertEquals(empty.glassLevel, back.glassLevel)
        assertEquals(empty.themeMode, back.themeMode)
    }

    @Test
    fun `收藏与歌单往返正确`() {
        val s = empty.copy(
            favorites = setOf("f/a.mp3", "f/b.mp3"),
            userPlaylists = listOf(
                PlaylistSnapshot(id = "p1", title = "通勤", trackIds = listOf("f/a.mp3")),
            ),
        )
        val back = roundTrip(s)
        assertNotNull(back)
        assertEquals(setOf("f/a.mp3", "f/b.mp3"), back!!.favorites)
        assertEquals(1, back.userPlaylists.size)
        assertEquals("通勤", back.userPlaylists[0].title)
        assertEquals(listOf("f/a.mp3"), back.userPlaylists[0].trackIds)
    }

    @Test
    fun `播放次数往返正确`() {
        val s = empty.copy(playCounts = mapOf("f/a.mp3" to 42, "f/b.mp3" to 1))
        val back = roundTrip(s)
        assertNotNull(back)
        assertEquals(42, back!!.playCounts!!["f/a.mp3"])
        assertEquals(1, back.playCounts!!["f/b.mp3"])
    }

    @Test
    fun `循环随机与变速往返正确`() {
        val s = empty.copy(shuffleOn = true, repeatMode = "All", playbackSpeed = 1.5f)
        val back = roundTrip(s)
        assertNotNull(back)
        assertTrue(back!!.shuffleOn)
        assertEquals("All", back.repeatMode)
        assertEquals(1.5f, back.playbackSpeed!!, 0.001f)
    }

    @Test
    fun `排除文件夹往返正确`() {
        val s = empty.copy(excludedFolders = setOf("Download", "微信"))
        val back = roundTrip(s)
        assertEquals(setOf("Download", "微信"), back!!.excludedFolders)
    }

    // ── 版本号防线（P3-62）────────────────────────────────────

    @Test
    fun `来自更新版本的快照必须拒绝解码而不是静默拆解`() {
        // 这是最关键的一条：未来 schema 的备份被旧版 App 恢复 = 用户数据被半空快照覆盖
        val future = """{"v": 9999, "favorites": ["f/a.mp3"]}"""
        assertNull(SettingsStore.decodeBackup(future))
    }

    @Test
    fun `当前版本号的快照能正常解码`() {
        val json = SettingsStore.encodeForBackup(empty)
        assertNotNull(SettingsStore.decodeBackup(json))
    }

    @Test
    fun `缺少 v 号的旧格式走 legacy 分支且不崩溃`() {
        // v1 是纯文本 legacy 格式（非 JSON）。这里验证「非 JSON 输入不会抛异常」
        val legacy = "followCover=true\nlyricEnabled=true\n"
        // 能解就解，不能解也必须返回 null 而不是抛异常
        val r = runCatching { SettingsStore.decodeBackup(legacy) }
        assertTrue("legacy 解析不应抛异常", r.isSuccess)
    }

    // ── 坏数据防线 ───────────────────────────────────────────

    @Test
    fun `完全不是 JSON 的输入返回 null 而不是崩溃`() {
        assertNull(SettingsStore.decodeBackup("这不是备份文件，随便一段文字"))
    }

    @Test
    fun `空字符串返回 null 而不是崩溃`() {
        assertNull(SettingsStore.decodeBackup(""))
    }

    @Test
    fun `JSON 但字段类型错乱时给默认值而不是崩溃`() {
        val broken = """{"v": 1, "favorites": "不是数组", "glassLevel": 123}"""
        val r = runCatching { SettingsStore.decodeBackup(broken) }
        assertTrue("类型错乱不应抛异常", r.isSuccess)
        // 解出来是什么无所谓，关键是：不崩、不覆盖、返回 null 或带默认值的快照
    }

    @Test
    fun `截断的 JSON 返回 null 而不是崩溃`() {
        val truncated = """{"v": 9, "favorites": ["f/a.mp3" """
        val r = runCatching { SettingsStore.decodeBackup(truncated) }
        assertTrue(r.isSuccess)
        assertNull(r.getOrNull())
    }

    // ── 向后兼容（缺字段）────────────────────────────────────

    @Test
    fun `旧版备份缺少 playCounts 字段时保留为 null 而不是清零`() {
        // 语义：null = 「备份没这个字段」→ 恢复时保留现有计数，不能当成「显式清零」
        val v6 = """{"v": 6, "favorites": []}"""
        val back = SettingsStore.decodeBackup(v6)
        assertNotNull(back)
        assertNull(back!!.playCounts)
    }

    @Test
    fun `旧版备份缺少 shuffleOn 时给默认值 false`() {
        val v3 = """{"v": 3}"""
        val back = SettingsStore.decodeBackup(v3)
        assertNotNull(back)
        assertEquals(false, back!!.shuffleOn)
    }
}
