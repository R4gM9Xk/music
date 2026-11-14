package com.local.music.core.ui.state

import com.local.music.core.ui.components.LyricLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 歌词域的两个**纯函数** + 一个开关的契约。
 *
 * ## 为什么这组测试价值高
 * `indexOf` 与 `visibleLyricsOf` 原来藏在 AppState 的两个 getter 里，
 * 只能通过「构造 AppState、装载曲库、播放、读下标」这条长链路间接验证——
 * 实际上从未被单独测过。拆到 holder 后它们成了可以直接喂输入的纯函数，
 * 于是那些**边界**第一次变得可断言：
 * - 进度落在第一行之前（开头 0.5 秒）；
 * - 进度落在最后一行之后（尾部）；
 * - 多行时间戳完全相同（LRC 里常见：同一句两个 `[00:12.00]` 标记）；
 * - 空歌词 / 空队列 / 占位曲。
 *
 * 这些边界正是「App 内显示第 12 行、桌面歌词显示第 11 行」那类差一错误的来源，
 * 而二分查找改写后**必须逐条与旧的 `indexOfLast` 语义对齐**——本文件就是那份对照表。
 */
class LyricsHolderTest {

    private fun line(sec: Float, text: String = "t$sec") = LyricLine(text, sec)

    // ── indexOf：等价性（对照旧的 indexOfLast 语义）──────────

    /** 回归基准：二分实现必须与 `indexOfLast { it.timeSec <= pos }` 逐条一致 */
    @Test
    fun `二分结果与线性扫描完全一致`() {
        val lines = listOf(line(0f), line(5f), line(12.5f), line(30f), line(61.25f))
        // 覆盖：第一行之前、正好落在行上、行之间、末行之后、负数进度
        val probes = listOf(-1f, 0f, 0.01f, 4.99f, 5f, 5.01f, 12.5f, 29.9f, 30f, 60f, 61.25f, 999f)
        probes.forEach { pos ->
            val expected = lines.indexOfLast { it.timeSec <= pos }.coerceAtLeast(0)
            assertEquals("pos=$pos 时下标不一致", expected, LyricsHolder.indexOf(lines, pos))
        }
    }

    @Test
    fun `空歌词返回 0 而不是 -1`() {
        // 调用方（播放页 LyricRail）用返回值直接索引行列表，
        // 返回 -1 会在「无歌词」路径上抛越界——旧实现的 coerceAtLeast(0) 就是这个用途
        assertEquals(0, LyricsHolder.indexOf(emptyList(), 10f))
    }

    @Test
    fun `进度在第一行之前仍返回 0`() {
        val lines = listOf(line(10f), line(20f))
        // 舞台永远高亮一行，「无高亮」不是合法视觉态
        assertEquals(0, LyricsHolder.indexOf(lines, 0f))
        assertEquals(0, LyricsHolder.indexOf(lines, 9.99f))
    }

    @Test
    fun `进度在末行之后返回末行下标`() {
        val lines = listOf(line(1f), line(2f), line(3f))
        assertEquals(2, LyricsHolder.indexOf(lines, 100f))
    }

    /**
     * 时间戳相同的多行取**最后一个**。
     *
     * 这是二分实现最容易写错的一处：若条件写成 `<` 而不是 `<=`，
     * 结果会取到第一个，于是「同一句的两个时间标记」只显示前半句。
     * LRC 里重复时间戳很常见（合唱句、双语字幕）。
     */
    @Test
    fun `重复时间戳取最后一行`() {
        val lines = listOf(line(5f, "第一句"), line(5f, "第二句"), line(5f, "第三句"))
        assertEquals("重复时间戳应取最后一个（与 indexOfLast 一致）", 2, LyricsHolder.indexOf(lines, 5f))
    }

    /** 单行歌词（纯文本降级形态）：任何进度都返回 0 */
    @Test
    fun `单行歌词恒返回 0`() {
        val lines = listOf(line(0f, "整段纯文本"))
        assertEquals(0, LyricsHolder.indexOf(lines, 0f))
        assertEquals(0, LyricsHolder.indexOf(lines, 9999f))
    }

    /** 大列表：确认中点推进不会死循环（lo/hi 边界写错是二分的经典故障） */
    @Test
    fun `大列表不越界且收敛`() {
        val lines = (0 until 500).map { line(it.toFloat()) }
        assertEquals(0, LyricsHolder.indexOf(lines, -5f))
        assertEquals(0, LyricsHolder.indexOf(lines, 0f))
        assertEquals(250, LyricsHolder.indexOf(lines, 250.5f))
        assertEquals(499, LyricsHolder.indexOf(lines, 499f))
        assertEquals(499, LyricsHolder.indexOf(lines, 1_000f))
    }

    // ── visibleLyricsOf：三条判据 ──────────────────────

    @Test
    fun `正常真曲返回歌词`() {
        val lines = listOf(line(0f))
        assertEquals(
            lines,
            LyricsHolder.visibleLyricsOf(queueIsEmpty = false, hasMediaUri = true, lyrics = lines),
        )
    }

    @Test
    fun `空队列不给歌词`() {
        // 空曲库时队列为空，currentSong 是占位曲——它的 lyricLines 只会是残留数据
        assertNull(LyricsHolder.visibleLyricsOf(queueIsEmpty = true, hasMediaUri = true, lyrics = listOf(line(0f))))
    }

    @Test
    fun `占位曲无 mediaUri 不给歌词`() {
        assertNull(LyricsHolder.visibleLyricsOf(queueIsEmpty = false, hasMediaUri = false, lyrics = listOf(line(0f))))
    }

    @Test
    fun `真曲但没有 lrc 返回 null`() {
        // 这是「纯音乐，请欣赏」空态的唯一来源：不是「查询失败」，是「确实没有词」
        assertNull(LyricsHolder.visibleLyricsOf(queueIsEmpty = false, hasMediaUri = true, lyrics = null))
    }

    // ── 开关 ─────────────────────────────────────────

    @Test
    fun `默认开启歌词`() {
        // 默认关会让新用户以为不支持歌词——这是产品约束，改成 false 必须先让本条红
        assertTrue(LyricsHolder().enabled)
    }

    @Test
    fun `切换开关触发存盘且去重`() {
        var saves = 0
        val h = LyricsHolder(onChanged = { saves++ })
        h.applyEnabled(false)
        assertFalse(h.enabled)
        assertEquals("真实变化要存盘", 1, saves)
        // 设成同一个值不应再存盘
        h.applyEnabled(false)
        assertEquals("重复设置不存盘", 1, saves)
        h.toggle()
        assertTrue(h.enabled)
        assertEquals("取反也要存盘", 2, saves)
    }

    @Test
    fun `恢复不触发存盘`() {
        var saves = 0
        val h = LyricsHolder(onChanged = { saves++ })
        h.restore(true)
        assertTrue(h.enabled)
        // 恢复路径是纯回写：若这里触发存盘，启动时会多写一次磁盘
        assertEquals("restore 不应存盘", 0, saves)
    }
}
