package com.local.music.core.ui.components

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.local.music.core.ui.theme.LocalAuroraPalette
import com.local.music.core.ui.theme.LocalIsDark

/** CSS ease-in-out 曲线（cubic-bezier(0.42, 0, 0.58, 1)） */
private val CssEaseInOut = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)

/**
 * 极光流体背景（全屏共用）：
 * - 底色：deep → deep2 斜向渐变（CSS 200deg）
 * - 3 团封面取色光源，46px 柔边（径向渐变近似 blur(46px)），screen 混合，opacity .55
 * - 漂移周期 14 / 17 / 20s 交错（ease-in-out alternate）
 * - 底部径向暗角（80%×50% @ 50% 110%，黑 35%）
 *
 * 性能：整屏是无限动画，每秒 60 帧重绘；**每帧不允许新建 Brush/Shader**。
 * - 底色与暗角只依赖 (palette, isLight, size)：放 drawWithCache 的 block，尺寸/配色变化
 *   才重算，帧间复用同一对象；
 * - 3 团光源的圆心与半径每帧随漂移变化（Brush 构造参数不能复用）。这里不按帧重建
 *   Brush，而是把光源 Brush 建成**单位空间**（center=0, radius=1，只依赖配色），
 *   绘制时用 withTransform 平移到圆心、缩放到半径——几何由画布变换承担，
 *   Shader 本身全程不变，帧间零分配。
 */
@Composable
fun AuroraBackground(modifier: Modifier = Modifier) {
    val palette = LocalAuroraPalette.current
    // 明暗策略跟随主题标记（带滞回），不用动画中的颜色判亮度——过渡中途混合模式会翻转
    val isLight = !LocalIsDark.current

    val transition = rememberInfiniteTransition(label = "aurora")
    // CSS ease-in-out = cubic-bezier(0.42, 0, 0.58, 1)；alternate 往返
    val p1 by transition.animateFloat(
        0f, 1f, infiniteRepeatable(tween(14_000, easing = CssEaseInOut), RepeatMode.Reverse), label = "d1",
    )
    val p2 by transition.animateFloat(
        0f, 1f, infiniteRepeatable(tween(17_000, easing = CssEaseInOut), RepeatMode.Reverse), label = "d2",
    )
    val p3 by transition.animateFloat(
        0f, 1f, infiniteRepeatable(tween(20_000, easing = CssEaseInOut), RepeatMode.Reverse), label = "d3",
    )

    val density = LocalDensity.current
    Box(
        modifier.fillMaxSize().drawWithCache {
            val w = size.width
            val h = size.height
            fun px(dp: Int) = with(density) { dp.dp.toPx() }

            // ── 仅依赖 (palette, isLight, size) 的静态图层：这里算一次，帧间复用 ──
            // 底色：linear-gradient(200deg, deep, deep2)
            val baseBrush = Brush.linearGradient(
                listOf(palette.deep, palette.deep2),
                start = Offset(w * 0.15f, 0f),
                end = Offset(w * 0.85f, h),
            )
            // 底部径向暗角：radial-gradient(80% 50% at 50% 110%, 黑35%, transparent 70%)；
            // 浅色模式收敛为轻影
            val vignetteBrush = Brush.radialGradient(
                0f to Color.Black.copy(alpha = if (isLight) 0.10f else 0.35f),
                0.7f to Color.Transparent,
                1f to Color.Transparent,
                center = Offset(w / 2f, h * 1.1f),
                radius = maxOf(w * 0.8f, h * 0.5f),
            )
            // 光源柔边：单位空间径向渐变（center=0、radius=1），绘制时按光源半径缩放。
            // 颜色停靠点与漂移无关（alpha 固定），所以能整帧复用——这是本文件「零每帧分配」
            // 的关键：若在 onDrawBehind 里按新圆心 new Brush，就是每帧 3 个 Shader。
            fun blobBrush(c: Color) = Brush.radialGradient(
                0f to c.copy(alpha = 0.55f),
                0.55f to c.copy(alpha = 0.28f),
                1f to Color.Transparent,
                center = Offset.Zero,
                radius = 1f,
            )
            val seedBrush = blobBrush(palette.seed)
            val seed2Brush = blobBrush(palette.seed2)
            val blend = if (isLight) BlendMode.Multiply else BlendMode.Screen

            // 固定几何（px）：半径基准与 CSS 定位偏移
            val r1 = px(170)
            val r2 = px(150)
            val r3 = px(130)
            val cx1 = px(100)
            val cy1 = px(80)
            val dx1 = px(46)
            val dy1 = px(30)
            val cx2 = w - px(40)
            val cy2 = h * 0.26f + px(150)
            val dx2 = px(40)
            val dy2 = px(44)
            val cx3 = w * 0.14f + px(130)
            val cy3 = h - px(50)
            val dx3 = px(30)
            val dy3 = px(36)

            onDrawBehind {
                drawRect(baseBrush)

                // 光源：径向渐变的柔边圆近似 46px 高斯模糊；深色 Screen 提亮、浅色 Multiply 染色。
                // 圆心/半径每帧变 → 由画布变换表达，Brush 仍是上面缓存的单位空间对象
                fun blob(brush: Brush, center: Offset, radiusPx: Float) {
                    withTransform({
                        translate(center.x, center.y)
                        scale(radiusPx, radiusPx, pivot = Offset.Zero)
                    }) {
                        // 单位圆 × 变换 = 直径 2r 的圆；brush 的 radius=1 同步放大成 r
                        drawCircle(brush = brush, radius = 1f, center = Offset.Zero, blendMode = blend)
                    }
                }

                // CSS 的 left/top/right/bottom 定位的是光源圆的外接盒，圆心 = 盒角 + 半径
                // a1：340px @ left -70 top -90 → 圆心 (100, 80)，漂移 (46,30) scale 1.12，周期 14s
                blob(
                    seedBrush,
                    Offset(cx1 + dx1 * p1, cy1 + dy1 * p1),
                    r1 * (1f + 0.12f * p1),
                )
                // a2：300px @ right -110 top 26% → 圆心 (w-40, 26%h+150)，漂移 (-40,44) scale .94，周期 17s
                blob(
                    seed2Brush,
                    Offset(cx2 - dx2 * p2, cy2 + dy2 * p2),
                    r2 * (1f - 0.06f * p2),
                )
                // a3：260px @ left 14% bottom -80 → 圆心 (14%w+130, h-50)，漂移 (30,-36) scale 1.08，周期 20s
                blob(
                    seedBrush,
                    Offset(cx3 + dx3 * p3, cy3 - dy3 * p3),
                    r3 * (1f + 0.08f * p3),
                )

                drawRect(vignetteBrush)
            }
        },
    )
}
