package com.local.music.core.ui.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * 音效链状态单测。
 *
 * 为什么测这个：这里的核心风险是**数组越界崩溃**。档位索引来自持久化快照，
 * 而快照可能来自任何版本（改过档位数的旧版、手工编辑过的备份、类型错乱的损坏数据）。
 * 一旦越界，`BassBoostLevelLabels[safeBassLevel]` 就会在设置页渲染时抛
 * IndexOutOfBoundsException —— 那不是「音效不生效」，是**用户进设置页就崩**。
 * 所以 `safeBassLevel` / `safeVirtualLevel` 的钳制是必须钉死的。
 *
 * 这个测试不依赖 org.json / android.jar，纯 Kotlin 值语义，在任何环境都能跑。
 */
class AudioFxStateTest {

    @Test
    fun `默认设置三项全关`() {
        // 这是本轮最重要的产品约束：装上后听到的声音不应该被改变。
        // 任何让默认值变成 true 的改动都必须让这条测试先红。
        val defaults = AudioFxSettings()
        assertFalse("响度归一默认必须关", defaults.loudnessNorm)
        assertFalse("低音增强默认必须关", defaults.bassBoost)
        assertFalse("虚拟环绕默认必须关", defaults.virtualizer)
    }

    @Test
    fun `默认档位落在合法区间内`() {
        val defaults = AudioFxSettings()
        assertEquals(AudioFxSettings.DEFAULT_LEVEL, defaults.safeBassLevel)
        assertEquals(AudioFxSettings.DEFAULT_LEVEL, defaults.safeVirtualLevel)
    }

    @Test
    fun `负档位被钳制到最低档而不是崩溃`() {
        val s = AudioFxSettings(bassLevel = -5, virtualLevel = -99)
        assertEquals(0, s.safeBassLevel)
        assertEquals(0, s.safeVirtualLevel)
        // 强度也必须能取到（不能越界）
        assertEquals(BassBoostStrengths[0], s.bassStrength)
        assertEquals(VirtualizerStrengths[0], s.virtualStrength)
    }

    @Test
    fun `超大档位被钳制到最高档而不是崩溃`() {
        // 场景：备份来自「上一版有 5 档」的 App，回滚到本版只有 3 档 → 索引 4 越界
        val s = AudioFxSettings(bassLevel = 999, virtualLevel = Int.MAX_VALUE)
        assertEquals(BassBoostStrengths.lastIndex, s.safeBassLevel)
        assertEquals(VirtualizerStrengths.lastIndex, s.safeVirtualLevel)
        assertEquals(BassBoostStrengths.last(), s.bassStrength)
        assertEquals(VirtualizerStrengths.last(), s.virtualStrength)
    }

    @Test
    fun `每个合法档位都能取到对应强度`() {
        // 覆盖全部档位，确保 labels/strengths 两个列表长度一致（长度不一致会让 UI 越界）
        assertEquals(BassBoostStrengths.size, BassBoostLevelLabels.size)
        assertEquals(VirtualizerStrengths.size, VirtualizerLevelLabels.size)
        BassBoostStrengths.indices.forEach { level ->
            val s = AudioFxSettings(bassBoost = true, bassLevel = level)
            assertEquals(BassBoostStrengths[level], s.bassStrength)
        }
        VirtualizerStrengths.indices.forEach { level ->
            val s = AudioFxSettings(virtualizer = true, virtualLevel = level)
            assertEquals(VirtualizerStrengths[level], s.virtualStrength)
        }
    }

    @Test
    fun `强度值落在 audiofx 的 0 到 1000 取值域内`() {
        // BassBoost/Virtualizer.setStrength 的合法域是 0..1000（越界由系统钳制，
        // 但我们不该依赖系统钳制把明显无意义的值传下去）
        BassBoostStrengths.forEach {
            assertEquals(true, it in 0..1000)
        }
        VirtualizerStrengths.forEach {
            assertEquals(true, it in 0..1000)
        }
    }

    @Test
    fun `开关与档位相互独立`() {
        // 关掉开关不应改变已选档位：用户关掉再打开，应该回到他上次选的档
        val off = AudioFxSettings(bassBoost = false, bassLevel = 2)
        assertEquals(2, off.safeBassLevel)
        val on = off.copy(bassBoost = true)
        assertEquals(2, on.safeBassLevel)
    }

    @Test
    fun `copy 单个字段不影响其余字段`() {
        val base = AudioFxSettings(loudnessNorm = true, bassBoost = true, bassLevel = 2)
        val next = base.copy(virtualizer = true)
        assertEquals(true, next.loudnessNorm)
        assertEquals(true, next.bassBoost)
        assertEquals(2, next.bassLevel)
    }
}
