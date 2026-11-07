package com.local.music.core.ui.state

/**
 * 音效链设置：响度归一 / 低音增强 / 虚拟环绕。
 *
 * ## 为什么单独开一个文件而不是塞进 AppState.kt
 * `AppState.kt` 有 2000+ 行且多人同时改（专辑分组、快照、引擎同步）。把音效状态独立成
 * 一个纯数据类，AppState 只加「一个属性 + 一个 setter + 快照传参」三处，冲突面最小。
 *
 * ## 为什么三个开关默认全关（这不是偷懒，是产品判断）
 * 这三项**都会改变用户听到的声音**。用户装上一个本地播放器，第一件事是把音量调到自己
 * 舒服的位置；此时我们再叠一层增益，等于替他改掉了刚调好的听感，而他在设置页找不到
 * 原因（这些开关在「播放」分组里，不在首屏）。所以：
 * - 默认全关 → 装上后听到的就是文件本来的声音，与系统播放器一致；
 * - 想用的人自己去开，且开着的时候设置页有明确的「已开启」回显。
 * 对照本工程 EQ 的历史坑：EQ 预设默认 `Off` 是同一个道理，保持一致。
 *
 * ## 与 EQ（[EqPreset]）的关系
 * EQ 是「用户主动选一个音色风格」，本类三项是「系统级音频处理」。两者都挂在同一个播放器
 * 音频会话上，互不冲突（Android 上 Equalizer / BassBoost / Virtualizer / LoudnessEnhancer
 * 是四个独立的效果实例，可同时 enabled）。
 *
 * @property loudnessNorm 响度归一：按曲目内嵌的 REPLAYGAIN_TRACK_GAIN 标签做增益补偿，
 *   解决「老歌 CD 母带比新歌小 6dB，用户每切一首都要拧一次音量」。标签读不到时**不补偿**
 *   （不是给一个固定增益）——给固定增益会把本来就响的歌推爆。
 * @property bassBoost 低音增强（android.media.audiofx.BassBoost）
 * @property bassLevel 低音档位索引，见 [BassBoostStrengths]；越界时按 [DEFAULT_LEVEL] 处理
 * @property virtualizer 虚拟环绕（android.media.audiofx.Virtualizer，耳机下才有可感效果）
 * @property virtualLevel 环绕档位索引，见 [VirtualizerStrengths]
 */
data class AudioFxSettings(
    val loudnessNorm: Boolean = false,
    val bassBoost: Boolean = false,
    val bassLevel: Int = DEFAULT_LEVEL,
    val virtualizer: Boolean = false,
    val virtualLevel: Int = DEFAULT_LEVEL,
) {
    /** 归一化后的低音档位（快照里可能存着上一版本更大的档位索引，越界一律落回默认档） */
    val safeBassLevel: Int get() = bassLevel.coerceIn(0, BassBoostStrengths.lastIndex)

    /** 归一化后的环绕档位 */
    val safeVirtualLevel: Int get() = virtualLevel.coerceIn(0, VirtualizerStrengths.lastIndex)

    /** 当前生效的低音强度（0..1000，直接喂给 BassBoost.setStrength） */
    val bassStrength: Int get() = BassBoostStrengths[safeBassLevel]

    /** 当前生效的环绕强度（0..1000） */
    val virtualStrength: Int get() = VirtualizerStrengths[safeVirtualLevel]

    companion object {
        /** 默认档位 = 「中」：被用户打开时先给一个能听出区别又不浮夸的量 */
        const val DEFAULT_LEVEL = 1
    }
}

/**
 * 低音增强 3 档强度。
 *
 * 取值域是 android.media.audiofx.BassBoost 的 0..1000（线性 0%..100%，越界由系统钳制）。
 * 为什么是 300/600/900 而不是 0/500/1000：
 * - 0 档等于没开，用户会以为开关坏了；
 * - 1000 档在大多数手机上会把中低频糊成一片（BassBoost 是「低频增益 + 限幅」的粗暴实现，
 *   不是分频段 EQ），多数耳机会明显破音；
 * - 900 已经足够「轰」，留 100 的余量给不同设备的差异。
 */
val BassBoostStrengths: List<Int> = listOf(300, 600, 900)

/** 低音档位展示名（与 [BassBoostStrengths] 一一对应） */
val BassBoostLevelLabels: List<String> = listOf("轻", "中", "强")

/**
 * 虚拟环绕 3 档强度。
 *
 * Virtualizer 的 strength 同样是 0..1000，但它主要做「左右声道串扰 + 轻微延迟」，
 * 效果**只在耳机上明显**；外放时几乎无感（有些设备还会把单声道内容弄得更虚）。
 * 档位给到 400/700/1000：这一项开太大时声场会明显变糊，需要让用户能停在中间。
 */
val VirtualizerStrengths: List<Int> = listOf(400, 700, 1000)

/** 环绕档位展示名 */
val VirtualizerLevelLabels: List<String> = listOf("轻", "中", "强")

/** 响度归一开关的副标题（设置页回显；文案要说明「开了会发生什么」而不只是状态） */
fun loudnessNormSubtitle(settings: AudioFxSettings): String = if (settings.loudnessNorm) {
    "已开启 · 按曲目响度标签自动补偿，切歌音量更一致"
} else {
    "已关闭 · 不同年代/来源的曲目音量会不一致"
}

/** 低音增强副标题 */
fun bassBoostSubtitle(settings: AudioFxSettings): String = if (settings.bassBoost) {
    "已开启 · ${BassBoostLevelLabels[settings.safeBassLevel]}"
} else {
    "已关闭 · 不改变原始低频"
}

/** 虚拟环绕副标题 */
fun virtualizerSubtitle(settings: AudioFxSettings): String = if (settings.virtualizer) {
    "已开启 · ${VirtualizerLevelLabels[settings.safeVirtualLevel]}（耳机下更明显）"
} else {
    "已关闭 · 保持原始声场"
}
