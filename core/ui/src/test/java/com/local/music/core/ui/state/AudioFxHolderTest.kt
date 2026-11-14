package com.local.music.core.ui.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [AudioFxHolder] 的行为契约（本轮拆域新增）。
 *
 * ## 为什么拆域必须配一组测试
 * 把逻辑从 AppState 搬到 holder，**编译能过不代表行为等价**。这组测试钉住三条
 * 拆域时最容易悄悄改坏的语义：
 *
 * 1. **去重**：拨到当前已生效的同一档，不应再下发一次（否则每次点同一档都产生
 *    binder 流量 + 一次多余存盘）；
 * 2. **下发与存盘成对**：任何真实变化都必须**同时**触发两者——漏掉下发就是
 *    「界面变了、声音没变」（旧 EQ 的经典 bug），漏掉存盘就是「重启后回弹」；
 * 3. **恢复路径不触发下发/存盘**：`restore` 只灌值，下放给 `attachPlayer` 的重放，
 *    存盘更是纯回写（否则启动时会多写一次磁盘）。
 *
 * 这三条在 AppState 里是隐式的（混在 2900 行里看不清），拆出来之后可以逐条断言。
 */
class AudioFxHolderTest {

    /** 记录所有下发与存盘动作，供断言 */
    private class Recorder {
        val applied = mutableListOf<AudioFxSettings>()
        var saveCount = 0
        val appliedPresets = mutableListOf<EqPreset>()
    }

    private fun holder(r: Recorder) = AudioFxHolder(
        onApply = { r.applied += it },
        onChanged = { r.saveCount++ },
    ).apply {
        bindEqApplier { preset -> r.appliedPresets += preset }
    }

    @Test
    fun `默认全关且档位取默认值`() {
        val r = Recorder()
        val h = holder(r)
        assertEquals(EqPreset.Off, h.eqPreset)
        assertFalse(h.settings.loudnessNorm)
        assertFalse(h.settings.bassBoost)
        assertFalse(h.settings.virtualizer)
        assertEquals(AudioFxSettings.DEFAULT_LEVEL, h.settings.safeBassLevel)
        // 构造后不应有任何副作用
        assertTrue(r.applied.isEmpty())
        assertEquals(0, r.saveCount)
    }

    @Test
    fun `改变音效时同时下发与存盘`() {
        val r = Recorder()
        val h = holder(r)
        h.setBassBoost(true)
        assertTrue("状态必须更新", h.settings.bassBoost)
        assertEquals("必须下发且只有一次", 1, r.applied.size)
        assertTrue("下发的就是新值", r.applied.last().bassBoost)
        assertEquals("必须请求存盘", 1, r.saveCount)
    }

    /**
     * 去重：拨到与当前相同的值不产生任何副作用。
     *
     * 这条在真机上的表现是「点同一个档位时不会有一闪的重复下发」；更重要的是
     * 它让 `settingsKey()` 的指纹保持稳定，避免无意义存盘。
     */
    @Test
    fun `重复设置相同值不下发也不存盘`() {
        val r = Recorder()
        val h = holder(r)
        h.setLoudnessNorm(true)
        val appliedAfterFirst = r.applied.size
        val savesAfterFirst = r.saveCount
        // 再设同一个值
        h.setLoudnessNorm(true)
        assertEquals("重复设置不应再下发", appliedAfterFirst, r.applied.size)
        assertEquals("重复设置不应再存盘", savesAfterFirst, r.saveCount)
    }

    @Test
    fun `选择均衡器预设走独立下发通道`() {
        val r = Recorder()
        val h = holder(r)
        h.selectEqPreset(EqPreset.Rock)
        assertEquals(EqPreset.Rock, h.eqPreset)
        assertEquals("EQ 走自己的下发通道", listOf(EqPreset.Rock), r.appliedPresets)
        // EQ 不该触发音效链下发（两条是不同命令）
        assertTrue("EQ 变化不应重发音效链", r.applied.isEmpty())
        assertEquals("但必须存盘", 1, r.saveCount)
    }

    /** 重复选同一个 EQ 预设同样应被去重 */
    @Test
    fun `重复选择同一均衡器预设不下发`() {
        val r = Recorder()
        val h = holder(r)
        h.selectEqPreset(EqPreset.Off) // 默认就是 Off → 一次都不该下发
        assertTrue("默认值重复设置应被去重", r.appliedPresets.isEmpty())
        assertEquals(0, r.saveCount)
    }

    @Test
    fun `越界档位被钳制而不是崩溃`() {
        val r = Recorder()
        val h = holder(r)
        h.selectBassLevel(999)
        assertEquals("应钳到最高档", BassBoostStrengths.lastIndex, h.settings.bassLevel)
        h.selectVirtualLevel(-5)
        assertEquals("应钳到最低档", 0, h.settings.virtualLevel)
    }

    /**
     * 恢复路径：只灌值，不触发下发与存盘。
     *
     * 这是拆域时最容易写错的一条——如果把 `restore` 实现成「逐个调 setter」，
     * 启动时会广播一串下发命令并写好几次盘（服务还没连上时那些下发全是丢掉的）。
     */
    @Test
    fun `恢复快照不触发下发与存盘`() {
        val r = Recorder()
        val h = holder(r)
        h.restore(
            preset = EqPreset.Vocal,
            fx = AudioFxSettings(
                loudnessNorm = true,
                bassBoost = true,
                bassLevel = 2,
                virtualizer = true,
                virtualLevel = 1,
            ),
        )
        assertEquals(EqPreset.Vocal, h.eqPreset)
        assertTrue(h.settings.loudnessNorm)
        assertTrue(h.settings.bassBoost)
        assertTrue("恢复不应下发", r.applied.isEmpty())
        assertTrue("恢复不应下发 EQ", r.appliedPresets.isEmpty())
        assertEquals("恢复不应存盘", 0, r.saveCount)
    }

    /** 恢复时越界档位也要归一（避免把坏索引反复写回磁盘） */
    @Test
    fun `恢复时越界档位被归一`() {
        val r = Recorder()
        val h = holder(r)
        h.restore(
            preset = EqPreset.Off,
            fx = AudioFxSettings(bassLevel = 999, virtualLevel = -1),
        )
        assertEquals(BassBoostStrengths.lastIndex, h.settings.bassLevel)
        assertEquals(0, h.settings.virtualLevel)
    }

    /** 指纹必须随音效变化：否则「只拨音效开关」不会存盘，重启后回弹 */
    @Test
    fun `keyPart 随音效变化`() {
        val r = Recorder()
        val h = holder(r)
        val before = h.keyPart()
        h.setVirtualizer(true)
        val after = h.keyPart()
        assertTrue("音效变化后指纹必须不同", before != after)
        // 同一份状态重复取指纹必须稳定（否则每帧都会判定为「脏」）
        assertEquals(after, h.keyPart())
    }
}
