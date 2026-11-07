package com.local.music.core.ui.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 睡眠定时的**确定性**测试（用注入的假时钟驱动）。
 *
 * ## 这个文件为什么存在
 *
 * 04 工程报告的原话是：「零测试**不是没时间写，是写不出来**」——
 * 因为 `AppState` 用无参构造直接 new，内部又裸调 `System.currentTimeMillis()`，
 * 于是任何跟时间有关的逻辑（睡眠定时到点、队列快照 20s 节流）都**无法断言**：
 * 你没法让真实时钟快进 60 分钟，也没法卡在 19.9s / 20.1s 的边界上。
 *
 * 给 `AppState` 加了一个带默认值的 `clock: () -> Long` 参数后（一行改动），
 * 这些场景第一次变得可测。本文件就是证明这次改造成立的「活证据」——
 * 如果哪天有人把 `clock` 参数删掉，这个测试文件会立刻编译失败。
 *
 * ## 为什么不用 mock 框架
 *
 * 本工程没有 mockk/mockito（离线环境也拉不到），而这里需要的是「控制时钟」这种
 * 极简能力，一个 `var now = 0L` + lambda 就够了。引入一整套 mock 框架去测
 * 一个纯状态机是负收益：它会让构建变慢、依赖变脆，而表达力并不比手写假对象强。
 */
class AppStateClockTest {

    /** 可手动推进的假时钟：测试自己决定「现在几点」 */
    private class FakeClock(var now: Long = 0L) {
        fun advance(ms: Long) { now += ms }
        fun asFunction(): () -> Long = { now }
    }

    @Test
    fun `分钟档定时_剩余秒数随假时钟推进而递减`() {
        val clk = FakeClock(0L)
        val state = AppState(clock = clk.asFunction())

        state.setSleepTimer(30) // 30 分钟

        // 刚设定：整 30 分钟（1800 秒）
        assertEquals(1800, state.sleepTimerRemainingSec())

        // 推进 10 分钟 → 应剩 20 分钟
        clk.advance(10 * 60_000L)
        assertEquals(1200, state.sleepTimerRemainingSec())

        // 再推进 19 分 59 秒 → 应剩 1 秒（验证没有整除误差）
        clk.advance(19 * 60_000L + 59_000L)
        assertEquals(1, state.sleepTimerRemainingSec())
    }

    @Test
    fun `分钟档定时_到期后剩余秒数钳到0而不是负数`() {
        val clk = FakeClock(0L)
        val state = AppState(clock = clk.asFunction())

        state.setSleepTimer(15)
        // 越过期限 5 分钟：实际剩余是 -300 秒
        clk.advance(20 * 60_000L)

        // 必须钳到 0：UI 每秒读这个值驱动倒计时，出现负数会渲染成 "00:-5"
        // （这正是上一轮 asTime() 那个显示 bug 的同源问题）
        assertEquals(0, state.sleepTimerRemainingSec())
    }

    @Test
    fun `分钟档定时_到期后生效模式归零`() {
        val clk = FakeClock(0L)
        val state = AppState(clock = clk.asFunction())

        state.setSleepTimer(15)
        assertEquals(15, state.effectiveSleepTimerMode())

        // 差 1 毫秒到期：仍应视为生效中
        clk.advance(15 * 60_000L - 1)
        assertEquals(15, state.effectiveSleepTimerMode())

        // 到点：归零（服务端到点自行暂停且无回执，本地按期限推导）
        clk.advance(1)
        assertEquals(0, state.effectiveSleepTimerMode())
    }

    @Test
    fun `播完档不写期限_剩余秒数返回null`() {
        val clk = FakeClock(0L)
        val state = AppState(clock = clk.asFunction())

        // -1 = 播完当前首、-2 = 播完整个队列：这两档的语义是「放完就停」，
        // 没有时间戳，所以不该有倒计时
        state.setSleepTimer(-1)
        assertNull(state.sleepTimerRemainingSec())
        assertEquals(-1, state.effectiveSleepTimerMode())

        state.setSleepTimer(-2)
        assertNull(state.sleepTimerRemainingSec())
        assertEquals(-2, state.effectiveSleepTimerMode())
    }

    @Test
    fun `取消定时_模式与期限一起归零`() {
        val clk = FakeClock(0L)
        val state = AppState(clock = clk.asFunction())

        state.setSleepTimer(60)
        assertEquals(60, state.effectiveSleepTimerMode())

        state.setSleepTimer(0) // 0 = 取消
        assertEquals(0, state.effectiveSleepTimerMode())
        assertNull(state.sleepTimerRemainingSec())
    }

    @Test
    fun `重新设定定时_以新的时钟基准重新计算期限`() {
        val clk = FakeClock(0L)
        val state = AppState(clock = clk.asFunction())

        state.setSleepTimer(30)
        clk.advance(25 * 60_000L)
        assertEquals(300, state.sleepTimerRemainingSec()) // 剩 5 分钟

        // 用户改成 60 分钟：期限应基于**当前**时钟重算，而不是累加旧的
        state.setSleepTimer(60)
        assertEquals(3600, state.sleepTimerRemainingSec())
    }
}
