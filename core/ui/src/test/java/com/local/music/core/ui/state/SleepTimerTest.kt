package com.local.music.core.ui.state

import com.local.music.core.ui.components.asTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 定时关闭 / 时间格式化等纯函数单测（core:ui 模块）。
 *
 * 为什么测这个：定时关闭的档位语义（分钟 / 播完当前首 / 播完队列 / 取消）
 * 散落在两个面板（播放页 + 设置页）与三个函数里，文案与语义必须一致。
 * 历史 bug：副标题曾写成「当前曲播完后暂停」，让用户以为手动切歌也会停。
 */
class SleepTimerTest {

    // ── 档位标签 ────────────────────────────────────────────

    @Test
    fun `分钟档标签带单位`() {
        assertEquals("15 分钟", sleepTimerLabel(15))
        assertEquals("90 分钟", sleepTimerLabel(90))
    }

    @Test
    fun `播完当前首档标签`() {
        assertEquals("播完当前首", sleepTimerLabel(-1))
    }

    @Test
    fun `播完队列档标签`() {
        assertEquals("播完整个队列", sleepTimerLabel(-2))
    }

    @Test
    fun `取消档标签`() {
        assertEquals("取消定时", sleepTimerLabel(0))
    }

    @Test
    fun `未知负数档位回退到取消文案而不是崩溃`() {
        // 防御性：档位是 Int，若未来加新档位而这里没同步，至少不崩
        assertEquals("取消定时", sleepTimerLabel(-99))
    }

    // ── 副标题（只在生效档位显示）──────────────────────────

    @Test
    fun `非生效档位无副标题`() {
        assertNull(sleepTimerSubtitle(option = 15, activeMode = 30, remainingSec = 100))
    }

    @Test
    fun `分钟档生效时显示剩余时间`() {
        val s = sleepTimerSubtitle(option = 15, activeMode = 15, remainingSec = 125)
        assertEquals("剩余 02:05", s)
    }

    @Test
    fun `播完当前首的副标题必须说明手动切歌不触发`() {
        // 这是历史 bug 的回归测试：旧文案「当前曲播完后暂停」会误导用户
        val s = sleepTimerSubtitle(option = -1, activeMode = -1, remainingSec = 0)
        assertEquals("当前曲自然播完后暂停 · 手动切歌不触发", s)
    }

    @Test
    fun `播完队列的副标题`() {
        val s = sleepTimerSubtitle(option = -2, activeMode = -2, remainingSec = 0)
        assertEquals("整个队列播完后暂停", s)
    }

    @Test
    fun `取消档生效时显示未开启`() {
        assertEquals("未开启", sleepTimerSubtitle(option = 0, activeMode = 0, remainingSec = 0))
    }

    // ── 档位列表本身 ────────────────────────────────────────

    @Test
    fun `档位列表包含全部四个语义`() {
        assertEquals(listOf(15, 30, 60, 90, -1, -2, 0), SleepTimerOptions)
    }

    @Test
    fun `档位列表末尾是取消项`() {
        // UI 依赖「最后一项是取消」来做默认高亮逻辑
        assertEquals(0, SleepTimerOptions.last())
    }

    // ── 时间格式化 ──────────────────────────────────────────

    @Test
    fun `时长格式化补零到两位分钟`() {
        // P3-26 回归：旧实现 "%d:%02d" 不补零，9 分以内的歌显示 "3:25" 而不是 "03:25"
        assertEquals("03:25", 205.asTime())
        assertEquals("00:05", 5.asTime())
        assertEquals("59:59", 3599.asTime())
    }

    @Test
    fun `超过一小时仍按分钟显示而不是进位到小时`() {
        // 当前口径是 mm:ss；一小时以上显示 60:00 这类。若未来改成 h:mm:ss，这条会红，是刻意的提醒
        assertEquals("60:00", 3600.asTime())
    }

    @Test
    fun `负数时长被钳到零而不是渲染成负数`() {
        // 真实缺陷记录：修复前 -1.asTime() 得到 "00:-1"（分钟取整为 0，秒为负），
        // 引擎时长未知（C.TIME_UNSET 转换残留）时会显示成 "00:-1" 这种明显是 bug 的串。
        // 修复：asTime() 内部对负值钳到 0。
        assertEquals("00:00", (-1).asTime())
        assertEquals("00:00", (-999).asTime())
    }
}
