package com.local.music.core.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

/**
 * 液态玻璃流 · 设计 token
 * 一比一映射 player-ui-preview-glass.html 的 :root 变量与规范面板。
 */

// ── 封面取色（极光调色板）──────────────────────────────────
@Immutable
data class AuroraPalette(
    val seed: Color,    // 主色
    val seed2: Color,   // 强调色（辉光、进度渐变起点）
    val seed3: Color,   // 浅强调（高亮文字、tab 文字）
    val deep: Color,    // 极光底色（上）
    val deep2: Color,   // 极光底色（下）
) {
    /** 浅色模式底色：deep 系拉到高明度低饱和（取色仍生效，只是变浅色极光） */
    fun light(): AuroraPalette = copy(
        deep = lerp(deep, Color.White, 0.86f),
        deep2 = lerp(deep2, Color.White, 0.92f),
    )
}

object GlassPalettes {
    val Purple = AuroraPalette(
        seed = Color(0xFF7F77DD), seed2 = Color(0xFFAFA9EC), seed3 = Color(0xFFCECBF6),
        deep = Color(0xFF241C4A), deep2 = Color(0xFF0E0A20),
    )
    val Teal = AuroraPalette(
        seed = Color(0xFF1D9E75), seed2 = Color(0xFF5DCAA5), seed3 = Color(0xFF9FE1CB),
        deep = Color(0xFF062E26), deep2 = Color(0xFF04140F),
    )
    val Coral = AuroraPalette(
        seed = Color(0xFFD85A30), seed2 = Color(0xFFF0997B), seed3 = Color(0xFFF5C4B3),
        deep = Color(0xFF3D1408), deep2 = Color(0xFF1A0803),
    )
    val Blue = AuroraPalette(
        seed = Color(0xFF378ADD), seed2 = Color(0xFF85B7EB), seed3 = Color(0xFFB5D4F4),
        deep = Color(0xFF0A2540), deep2 = Color(0xFF04121F),
    )
    val All = listOf(Purple, Teal, Coral, Blue)
}

// ── 玻璃 token ────────────────────────────────────────────
@Immutable
data class GlassColors(
    val hi: Color = Color.White.copy(alpha = 0.16f),   // 高层玻璃：迷你条
    val md: Color = Color.White.copy(alpha = 0.10f),   // 中层：控制台 / 导航 / 卡片
    val lo: Color = Color.White.copy(alpha = 0.07f),   // 低层：圆钮 / 芯片 / 卡片底
    val stroke: Color = Color.White.copy(alpha = 0.28f),       // 高光描边
    val strokeSoft: Color = Color.White.copy(alpha = 0.14f),   // 常规描边
    val topLine: Color = Color.White.copy(alpha = 0.55f),      // 顶部亮线
    val textPrimary: Color = Color.White,
    val textSecondary: Color = Color.White.copy(alpha = 0.60f),
    val textTertiary: Color = Color.White.copy(alpha = 0.50f),
    val textFaint: Color = Color.White.copy(alpha = 0.42f),
    val lyricFar: Color = Color.White.copy(alpha = 0.34f),
    val lyricAdjacent: Color = Color.White.copy(alpha = 0.55f),
) {
    companion object {
        /** 浅色令牌：玻璃改实白面板、描边与文字转近黑 */
        fun light(): GlassColors = GlassColors(
            hi = Color.White.copy(alpha = 0.60f),
            md = Color.White.copy(alpha = 0.48f),
            lo = Color.White.copy(alpha = 0.32f),
            stroke = Color.White.copy(alpha = 0.65f),
            strokeSoft = Color.White.copy(alpha = 0.38f),
            topLine = Color.White.copy(alpha = 0.90f),
            textPrimary = Color(0xFF23232B),
            textSecondary = Color(0xFF23232B).copy(alpha = 0.62f),
            textTertiary = Color(0xFF23232B).copy(alpha = 0.52f),
            textFaint = Color(0xFF23232B).copy(alpha = 0.42f),
            lyricFar = Color(0xFF23232B).copy(alpha = 0.34f),
            lyricAdjacent = Color(0xFF23232B).copy(alpha = 0.55f),
        )
    }
}

/** GlassColors 全字段插值（深浅主题 400ms 交叉渐变用） */
fun lerpGlassColors(from: GlassColors, to: GlassColors, fraction: Float): GlassColors = GlassColors(
    hi = lerp(from.hi, to.hi, fraction),
    md = lerp(from.md, to.md, fraction),
    lo = lerp(from.lo, to.lo, fraction),
    stroke = lerp(from.stroke, to.stroke, fraction),
    strokeSoft = lerp(from.strokeSoft, to.strokeSoft, fraction),
    topLine = lerp(from.topLine, to.topLine, fraction),
    textPrimary = lerp(from.textPrimary, to.textPrimary, fraction),
    textSecondary = lerp(from.textSecondary, to.textSecondary, fraction),
    textTertiary = lerp(from.textTertiary, to.textTertiary, fraction),
    textFaint = lerp(from.textFaint, to.textFaint, fraction),
    lyricFar = lerp(from.lyricFar, to.lyricFar, fraction),
    lyricAdjacent = lerp(from.lyricAdjacent, to.lyricAdjacent, fraction),
)

/** AuroraPalette 全字段插值（深浅主题各自动画后按进度混合，避免中途整包跳变） */
fun lerpPalette(from: AuroraPalette, to: AuroraPalette, fraction: Float): AuroraPalette = AuroraPalette(
    seed = lerp(from.seed, to.seed, fraction),
    seed2 = lerp(from.seed2, to.seed2, fraction),
    seed3 = lerp(from.seed3, to.seed3, fraction),
    deep = lerp(from.deep, to.deep, fraction),
    deep2 = lerp(from.deep2, to.deep2, fraction),
)

// ── 动效（统一弹簧曲线）───────────────────────────────────
object GlassMotion {
    /** cubic-bezier(.32,.72,.28,1) —— 全 app 唯一缓动曲线 */
    val Curve = CubicBezierEasing(0.32f, 0.72f, 0.28f, 1f)

    /** 动效强度乘数（设置页三档），只作用于过渡动画；极光/波形等无限动画不受控 */
    var scale = 1f

    const val Press = 180     // 按压回弹 180ms scale(.92)
    const val Palette = 400   // 取色交叉渐变 400ms
    const val Lyric = 500     // 歌词滚动 / 高亮 500ms
    const val Page = 360      // 页面切换 360ms 淡入上移
    const val Hover = 250     // tab / 开关等微交互
    const val Flight = 500    // 共享元素飞行（迷你条 ⇄ 播放页）500ms

    fun <T> tweenPress() = tween<T>((Press * scale).toInt().coerceAtLeast(1), easing = Curve)
    fun <T> tweenPalette() = tween<T>((Palette * scale).toInt().coerceAtLeast(1), easing = Curve)
    fun <T> tweenLyric() = tween<T>((Lyric * scale).toInt().coerceAtLeast(1), easing = Curve)
    fun <T> tweenPage() = tween<T>((Page * scale).toInt().coerceAtLeast(1), easing = Curve)
    fun <T> tweenHover() = tween<T>((Hover * scale).toInt().coerceAtLeast(1), easing = Curve)
    fun <T> tweenFlight() = tween<T>((Flight * scale).toInt().coerceAtLeast(1), easing = Curve)
}

/** 玻璃强度（设置页三档）：按系数派生填充与描边透明度 */
enum class GlassIntensity(val label: String, val fillScale: Float, val strokeScale: Float) {
    Transparent("通透", 0.6f, 0.7f),
    Standard("标准", 1f, 1f),
    Rich("浓郁", 1.5f, 1.2f),
    ;

    fun derive(base: GlassColors): GlassColors = base.copy(
        hi = base.hi.copy(alpha = (base.hi.alpha * fillScale).coerceAtMost(1f)),
        md = base.md.copy(alpha = (base.md.alpha * fillScale).coerceAtMost(1f)),
        lo = base.lo.copy(alpha = (base.lo.alpha * fillScale).coerceAtMost(1f)),
        stroke = base.stroke.copy(alpha = (base.stroke.alpha * strokeScale).coerceAtMost(1f)),
        strokeSoft = base.strokeSoft.copy(alpha = (base.strokeSoft.alpha * strokeScale).coerceAtMost(1f)),
    )
}

/** 动效强度（设置页三档） */
enum class MotionIntensity(val label: String, val factor: Float) {
    Restrained("克制", 0.6f),
    Standard("标准", 1f),
    Enhanced("增强", 1.4f),
}

// ── CompositionLocal ──────────────────────────────────────
val LocalAuroraPalette = compositionLocalOf { GlassPalettes.Purple }
val LocalGlassColors = compositionLocalOf { GlassColors() }

/** 取色切换时 5 个颜色分别做 400ms 交叉渐变（CSS transition 的 Compose 等价） */
@Composable
private fun animatePalette(target: AuroraPalette): AuroraPalette {
    val spec = GlassMotion.tweenPalette<Color>()
    val s by animateColorAsState(target.seed, spec, label = "seed")
    val s2 by animateColorAsState(target.seed2, spec, label = "seed2")
    val s3 by animateColorAsState(target.seed3, spec, label = "seed3")
    val d by animateColorAsState(target.deep, spec, label = "deep")
    val d2 by animateColorAsState(target.deep2, spec, label = "deep2")
    return AuroraPalette(s, s2, s3, d, d2)
}

/**
 * 液态玻璃主题根：注入动画化后的取色 + 玻璃 token。
 * 所有页面共用一个极光底，取色随封面主色变化；isDark 切换深浅令牌并做 400ms 交叉渐变。
 */
@Composable
fun GlassTheme(
    palette: AuroraPalette = GlassPalettes.Purple,
    isDark: Boolean = true,
    glassIntensity: GlassIntensity = GlassIntensity.Standard,
    content: @Composable () -> Unit,
) {
    val themeFraction by animateFloatAsState(
        targetValue = if (isDark) 0f else 1f,
        animationSpec = tween(GlassMotion.Palette, easing = GlassMotion.Curve),
        label = "themeFraction",
    )
    // 深浅两套取色各自动画后按主题进度混合（浅色 = deep 系拉高明度的派生）
    val darkPalette = animatePalette(palette)
    val lightPalette = animatePalette(palette.light())
    val effectivePalette = lerpPalette(darkPalette, lightPalette, themeFraction)
    val darkGlass = glassIntensity.derive(GlassColors())
    val lightGlass = glassIntensity.derive(GlassColors.light())
    val glass = lerpGlassColors(darkGlass, lightGlass, themeFraction)

    CompositionLocalProvider(
        LocalAuroraPalette provides animatePalette(effectivePalette),
        LocalGlassColors provides glass,
        LocalIsDark provides (themeFraction < 0.5f),
    ) {
        content()
    }
}

/** 深浅模式标记：极光背景混合/暗角、系统栏外观等处使用 */
val LocalIsDark = compositionLocalOf { true }

/** 强调色（选中/开启态）：深色用浅强调 seed3，浅色下 seed3 太浅改用主色 seed */
@Composable
fun auroraAccentColor(): Color {
    val palette = LocalAuroraPalette.current
    return if (LocalIsDark.current) palette.seed3 else palette.seed
}
