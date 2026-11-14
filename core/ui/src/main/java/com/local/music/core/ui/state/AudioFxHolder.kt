package com.local.music.core.ui.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 音效域状态持有者（本轮从 [AppState] 拆出）。
 *
 * ## 为什么拆它，而不是拆「歌词」「更新」
 * AppState 约 2900 行，其中真正**自成一体**的域只有音效：
 * - 它的状态（均衡器预设 + 三条音效链）全部集中在一个 `AudioFxSettings` 值对象 + 一个枚举；
 * - 它的动作（6 个 setter）全部收敛到**唯一提交口** `commitAudioFx`，没有任何跨域耦合；
 * - 它的下游只有两件事：下发 [PlayerEngine] 与触发存盘快照。
 *
 * 反观另外两个候选域，都不适合在本轮拆：
 * - **歌词**：表面上有独立的 `lyricEnabled`，但真正的歌词数据（`currentLyrics`）来自
 *   `queue[currentIndex]` 的扫描产物，与队列/曲库强耦合；拆出去会变成一个
 *   「只能转发、没有自己状态」的空壳。
 * - **更新**：`UpdateManager` 本来就住在 `:app`，从未进过 AppState（见该文件与
 *   `UpdateUiState` 的说明）——它已经是拆好的状态，没有可拆的东西。
 *
 * 所以「把三个域都拆出去」这个目标里，真正有收益且低风险的只有音效一处。
 * 强行拆另外两个会制造转发层，反而让调用链更长。
 *
 * ## 边界约定（拆域后必须守住）
 * 本类**不持有** engine 与存盘能力：它们由 [AppState] 经构造参数注入成两个回调。
 * 这样做的理由：
 * - 若本类直接持 engine，它就要处理「engine 重建」的所有时序细节，
 *   而那些细节（`attachPlayer` 时重放 EQ/音效链）本来就属于 AppState 的连接逻辑；
 * - 若本类直接调 `scheduleSave()`，它就要知道快照的节流/门控规则，
 *   而那是 AppState 的持久化职责。
 *
 * 一句话：**本类只管「当前音效是什么」，不管「怎么让它生效」与「怎么存下来」。**
 */
class AudioFxHolder(
    /** 下发服务端（由 AppState 注入；engine 未连接时它是空操作） */
    private val onApply: (AudioFxSettings) -> Unit = {},
    /** 请求存盘（由 AppState 注入；内部有节流与 hydrated 门控） */
    private val onChanged: () -> Unit = {},
) {

    /**
     * 均衡器预设。**与 [AudioFxSettings] 分开放**，因为它是 Media3 侧的预设名
     * （`Equalizer.Preset`），而音效链是 Android `audiofx` 的三个独立效果器——
     * 两者在服务端是两条不同的命令（`setEqPreset` vs `setAudioFx`），合并成一个值对象
     * 会让「只改 EQ 也要重发全部音效链」。
     */
    var eqPreset by mutableStateOf(EqPreset.Off)
        private set

    /**
     * 音效链设置：**默认全关**（见 [AudioFxSettings] 的「为什么不默认开启」）。
     * `private set` 是刻意的：所有改动必须经下面 5 个 setter，才能保证
     * 「状态变了 → 服务端一定收到」这条不变量（旧实现的 EQ 就有过直接赋值导致
     * 「界面显示切了、实际没下发」的窗口，这里从类型上堵死）。
     */
    var settings by mutableStateOf(AudioFxSettings())
        private set

    /** 均衡器预设：立即下发（Off = 关闭音效），不等下一首歌装载 */
    fun selectEqPreset(preset: EqPreset) {
        if (preset == eqPreset) return
        eqPreset = preset
        onEqChanged(preset)
        onChanged()
    }

    /** 均衡器预设变化的下发（与音效链分开两条命令，见 [eqPreset] 的说明） */
    private var onEqChanged: (EqPreset) -> Unit = {}

    /** 供 AppState 注入「EQ 下发」回调（构造期 engine 尚不可用，故二次赋值） */
    internal fun bindEqApplier(apply: (EqPreset) -> Unit) {
        onEqChanged = apply
    }

    /** 响度归一开关 */
    fun setLoudnessNorm(enabled: Boolean) {
        commit(settings.copy(loudnessNorm = enabled))
    }

    /** 低音增强开关 */
    fun setBassBoost(enabled: Boolean) {
        commit(settings.copy(bassBoost = enabled))
    }

    /** 低音强度档位（索引，越界自动钳制到合法档） */
    fun selectBassLevel(level: Int) {
        commit(settings.copy(bassLevel = level.coerceIn(0, BassBoostStrengths.lastIndex)))
    }

    /** 虚拟环绕开关 */
    fun setVirtualizer(enabled: Boolean) {
        commit(settings.copy(virtualizer = enabled))
    }

    /** 虚拟环绕强度档位 */
    fun selectVirtualLevel(level: Int) {
        commit(settings.copy(virtualLevel = level.coerceIn(0, VirtualizerStrengths.lastIndex)))
    }

    /**
     * 唯一提交口：去重 → 落状态 → 下发 → 存盘。
     *
     * **内容未变时直接返回**：拨同一档不该产生 binder 流量，也让快照指纹在无实际
     * 变化时保持稳定（否则每次点同一个档位都会触发一次多余存盘）。
     */
    private fun commit(next: AudioFxSettings) {
        if (next == settings) return
        settings = next
        onApply(next)
        onChanged()
    }

    /**
     * 从快照恢复（[AppState.restore] 调用）。
     *
     * 为什么恢复**不触发** [onApply] / [onChanged]：
     * - 下发由 `attachPlayer` 的重放统一负责（服务重连时要重放一次，那时才是正确时机）；
     * - 存盘在恢复路径上是纯回写，会让「刚读进来的值」立刻再写一遍磁盘。
     *
     * 档位归一（`safeBassLevel` / `safeVirtualLevel`）也在这里做一次：读取侧已经兜过，
     * 但把归一后的值存回去能保证下一次读到的就是合法值，不再依赖读取侧的兜底。
     */
    fun restore(preset: EqPreset, fx: AudioFxSettings) {
        eqPreset = preset
        settings = fx.copy(
            bassLevel = fx.safeBassLevel,
            virtualLevel = fx.safeVirtualLevel,
        )
    }

    /**
     * 快照指纹片段（[AppState.settingsKey] 用）。
     *
     * 把「哪些字段参与判脏」这件事留在本类，而不是让 AppState 去拼字符串——
     * 将来音效域加字段时，只需改这一处，不会漏进指纹导致「改了但不存盘」。
     */
    fun keyPart(): String = "${eqPreset.name}|$settings"
}
