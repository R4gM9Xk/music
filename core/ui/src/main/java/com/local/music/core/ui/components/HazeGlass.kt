package com.local.music.core.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.local.music.core.ui.theme.LocalGlassColors
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect

/** 屏幕级背景采样状态：内容区 hazeSource 采样，面板 hazeEffect 消费（兄弟关系，禁止嵌套） */
val LocalHazeState = compositionLocalOf<HazeState?> { null }

/**
 * 玻璃面板挂接背景真模糊（haze 1.6）：读取 [LocalHazeState]，无状态时退化为普通玻璃。
 * blur 半径按玻璃层级 26/20/12dp（对齐设计稿 blur 30/20/12 的视觉重量），tint 复用玻璃填充色。
 *
 * ## 为什么 chrome 层**不再**单独加「更大半径的模糊档」（2026-11-08 定量结案）
 *
 * 上一轮的结论是一句没有数学支撑的断言：「scrim 到 0.80 后模糊贡献只剩 20%，
 * 加大半径肉眼不可辨」。本轮把它算清楚了，**并且验证了「显著加大 blur + 降低 scrim」
 * 这条组合路线走不通**。算式详见 [com.local.music.core.ui.theme.GlassColors.chromeScrim]
 * 的 KDoc，这里只留与**本文件**（半径选择）相关的结论：
 *
 * ### 一、半径是线性系统的增益，不是可无限加码的旋钮
 *
 * 高斯模糊是**保均值线性滤波**。对一维矩形笔画（宽 w）与高斯核（σ）卷积，残余调制深度为
 * ```
 *     m(σ) = erf( w / (2√2·σ) )        // σ ≫ w 时  m ≈ w / (√(2π)·σ)  ∝ 1/σ ∝ 1/r
 * ```
 * 也就是说 **残余调制与半径成反比**：半径翻倍只能把残值砍一半，砍不到十分之一。
 * 用真机实测反解：迷你条 r=26dp 时残余调制 m=0.547，对应 σ_eff≈7px；
 * 要让 m 降到 0.1（肉眼基本抹平）需 r≈155dp，**约 6 倍于现值**。
 *
 * ### 二、半径还有一条**几何上限**，比 GPU 开销更早封死
 *
 * haze 采样前会把背景图层按模糊半径外扩（`Rect.inflate(blurRadiusPx)`，
 * 见 HazeEffectNode 的 layer 计算）。导航栏只有 58dp 高，当 r 超过条高时，
 * 采样窗已经越过这条 bar 的上下边界，把**更远处**的列表内容一起卷进来 ——
 * 透字没被压掉，反而混入了本来不该出现在条内的内容。所以 r 不能随意超过条高量级。
 *
 * ### 三、退化路径下 blur 的贡献恰好是 0
 *
 * 软件渲染 / 无 RenderEffect 的设备上 haze 静默退化为纯 tint（[ScrimBlurEffect] 路径），
 * 此时半径是多少**完全不影响像素**。任何指望「用半径换 scrim」的方案，
 * 在这些设备上都会直接退回「没有 scrim」的裸值（实测可辨）。
 * 这就是本轮判定不可行的硬约束，详见 chromeScrim 的 KDoc。
 *
 * 结论：**半径维持在 26/20/12dp**。它不是「懒得调」，而是
 * ①再大的半径在保均值约束下边际收益递减（Δ 对比度 <0.1）；
 * ②导航栏受条高约束先撞几何天花板；
 * ③退化设备上收益恒为 0。
 * 可读性保障仍在 [com.local.music.core.ui.theme.GlassColors.chromeScrim]。
 */
@Composable
fun Modifier.glassHaze(level: GlassLevel): Modifier {
    val state = LocalHazeState.current ?: return this
    val glass = LocalGlassColors.current
    val style = remember(level, glass) {
        HazeStyle(
            backgroundColor = Color.Transparent,
            tints = listOf(HazeTint(glass.fill(level))),
            blurRadius = when (level) {
                GlassLevel.Hi -> 26.dp
                GlassLevel.Md -> 20.dp
                GlassLevel.Lo -> 12.dp
            },
            noiseFactor = 0.05f,
            fallbackTint = HazeTint(glass.fill(level)),
        )
    }
    return hazeEffect(state = state, style = style)
}
