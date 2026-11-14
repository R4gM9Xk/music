package com.local.music.core.ui.state

import com.local.music.core.ui.components.ArtStyle
import com.local.music.core.ui.components.DemoSong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A-3「清空接下来」的撤销语义。
 *
 * ## 为什么需要这组测试
 * 撤销窗口的**过期判定**依赖注入时钟（见 AppState 的 undoDeadlineMs KDoc）——
 * 这正是构造参数 `clock` 存在的意义（该 KDoc 写着「你没法让真实时钟快进 60 分钟」）。
 * 若不测，「4 秒内可撤销」「4 秒后不可撤销」「队列改过就不可撤销」这三条
 * 只能靠人手掐秒表验证，而实际上从未验证过。
 *
 * ## 覆盖的四条边界
 * 1. 正常撤销：曲目原样回到当前曲之后；
 * 2. 窗口过期：撤销失败且不动队列；
 * 3. 现场失效：窗口内队列被改过 → 撤销失败（防「第三种状态」）；
 * 4. 无可清内容：不产生撤销窗口（避免一个点了无事发生的入口）。
 */
class QueueUndoClearTest {

    /** 纯内存曲目（mediaUri 非空 = 可进引擎队列的真曲目） */
    private fun song(id: String): DemoSong = DemoSong(
        id = "f/$id.mp3",
        title = id,
        artist = "歌手$id",
        album = "专辑$id",
        durationSec = 200,
        art = ArtStyle.Night,
        mediaUri = "content://media/$id",
    )

    private fun songs(n: Int): List<DemoSong> = (1..n).map { song("s$it") }

    /** 清空 → 撤销：曲目原样回到当前曲之后 */
    @Test
    fun undoRestoresClearedUpNext() {
        var now = 1_000L
        val state = AppState(clock = { now })
        val list = songs(5)
        // 停在第 2 首 → 待播 = 第 3、4、5 首
        state.playSong(list[1], list, source = "测试")

        state.clearUpNext()
        assertEquals("清空后只剩前缀与当前曲", 2, state.queue.size)
        assertTrue("窗口内应可撤销", state.canUndoClearUpNext)
        assertEquals("撤销条应报 3 首", 3, state.undoClearUpNextCount)

        now += 1_000L // 仍未到 4s 窗口边界
        assertTrue("窗口内撤销应成功", state.undoClearUpNext())

        assertEquals("撤销后队列应恢复", 5, state.queue.size)
        assertEquals("当前曲不变", "f/s2.mp3", state.queue[1].id)
        assertEquals(
            "待播段原样插回",
            listOf("f/s3.mp3", "f/s4.mp3", "f/s5.mp3"),
            state.queue.drop(2).map { it.id },
        )
        assertFalse("撤销后窗口应关闭", state.canUndoClearUpNext)
    }

    /** 超过 4 秒窗口：撤销失败且不改变队列 */
    @Test
    fun undoFailsAfterWindowExpires() {
        var now = 0L
        val state = AppState(clock = { now })
        val list = songs(5)
        state.playSong(list[0], list, source = "测试")

        state.clearUpNext()
        now += 4_001L // 越过 4s 窗口

        assertFalse("过期后不应可撤销", state.canUndoClearUpNext)
        assertFalse("过期后撤销应返回 false", state.undoClearUpNext())
        assertEquals("队列应保持清空后的状态", 1, state.queue.size)
    }

    /**
     * 撤销窗口期内队列被改过：撤销必须失败。
     *
     * 这是「现场失效」保护的核心场景——若不校验，插回去会得到一个
     * 「既不等于清空前、也不等于当前」的第三种状态（见 AppState.ClearedUpNext 的 KDoc）。
     */
    @Test
    fun undoFailsWhenQueueChangedInWindow() {
        var now = 0L
        val state = AppState(clock = { now })
        val list = songs(6)
        state.playSong(list[0], list, source = "测试")

        state.clearUpNext() // 待播 s2..s6 被清，队列只剩 s1
        assertEquals(1, state.queue.size)
        // 窗口内再加一首（用公开的 playLast，队列指纹随之变化）
        state.playLast(list[1])
        assertEquals("队列确实变了", 2, state.queue.size)

        assertFalse("队列已变化，撤销应失败", state.undoClearUpNext())
        assertFalse("撤销失败后窗口关闭", state.canUndoClearUpNext)
        assertEquals("失败不应改动队列", 2, state.queue.size)
    }

    /** 没有可清内容时（当前曲已是队尾）不产生撤销窗口 */
    @Test
    fun noUndoWindowWhenNothingToClear() {
        val state = AppState(clock = { 0L })
        val list = songs(3)
        state.playSong(list[2], list, source = "测试") // 队尾，待播为空

        state.clearUpNext()
        assertEquals("队列不应被改动", 3, state.queue.size)
        assertFalse("没有可清的内容就不该给撤销入口", state.canUndoClearUpNext)
        assertEquals(0, state.undoClearUpNextCount)
    }

    /** 撤销一次之后不能重复撤销 */
    @Test
    fun undoIsOneShot() {
        var now = 0L
        val state = AppState(clock = { now })
        val list = songs(4)
        state.playSong(list[0], list, source = "测试")

        state.clearUpNext()
        assertTrue(state.undoClearUpNext())
        assertEquals(4, state.queue.size)
        // 再撤销一次：窗口已关，必须返回 false 且不动队列
        assertFalse(state.undoClearUpNext())
        assertEquals(4, state.queue.size)
    }

    /**
     * 撤销窗口在**读取时**判定过期，而不依赖某个计时器跑过。
     *
     * 这条性质很重要：UI 可能长时间不重组（用户停在播放页没动），若过期依赖
     * 「有人恰好跑了计时器」，那么撤销按钮会一直亮着，点下去才发现失败。
     */
    @Test
    fun expiryIsEvaluatedLazilyOnRead() {
        var now = 0L
        val state = AppState(clock = { now })
        val list = songs(3)
        state.playSong(list[0], list, source = "测试")
        state.clearUpNext()

        assertTrue("刚清空时可撤销", state.canUndoClearUpNext)
        now += 3_999L
        assertTrue("3.999s 仍在窗口内", state.canUndoClearUpNext)
        now += 2L
        assertFalse("4.001s 已过期（且期间没有任何计时器被调度）", state.canUndoClearUpNext)
    }

    /** SongSpec 构造（生产代码的 toSpec 是 private，测试按同语义造一份） */
    private fun specOf(song: DemoSong): SongSpec? = song.mediaUri?.let {
        SongSpec(
            id = song.id,
            uri = it,
            title = song.title,
            artist = song.artist,
            album = song.album,
            artworkUri = song.artworkUri,
            durationMs = song.durationSec * 1000L,
        )
    }
}