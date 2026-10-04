package com.local.music.core.ui.components

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.local.music.core.ui.theme.LocalAuroraPalette

/** CSS ease-in-out 曲线（cubic-bezier(0.42, 0, 0.58, 1)） */
private val CssEaseInOut = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)

/**
 * 极光流体背景（全屏共用）：
 * - 底色：deep → deep2 斜向渐变（CSS 200deg）
 * - 3 团封面取色光源，46px 柔边（径向渐变近似 blur(46px)），screen 混合，opacity .55
 * - 漂移周期 14 / 17 / 20s 交错（ease-in-out alternate）
 * - 底部径向暗角（80%×50% @ 50% 110%，黑 35%）
 */
@Composable
fun AuroraBackground(modifier: Modifier = Modifier) {
    val palette = LocalAuroraPalette.current
    val isLight = palette.deep.luminance() > 0.5f // 浅色极光：混合/暗角策略反转

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
        modifier.fillMaxSize().drawBehind {
            val w = size.width
            val h = size.height
            fun px(dp: Int) = with(density) { dp.dp.toPx() }

            // 底色：linear-gradient(200deg, deep, deep2)
            drawRect(
                Brush.linearGradient(
                    listOf(palette.deep, palette.deep2),
                    start = Offset(w * 0.15f, 0f),
                    end = Offset(w * 0.85f, h),
                ),
            )

            // 光源：径向渐变的柔边圆近似 46px 高斯模糊；深色 Screen 提亮、浅色 Multiply 染色
            fun blob(
                color: Color, radiusPx: Float, cx: Float, cy: Float,
                dx: Float, dy: Float, scale: Float,
            ) {
                val r = radiusPx * scale
                drawCircle(
                    brush = Brush.radialGradient(
                        0f to color.copy(alpha = 0.55f),
                        0.55f to color.copy(alpha = 0.28f),
                        1f to Color.Transparent,
                        center = Offset(cx + dx, cy + dy),
                        radius = r,
                    ),
                    radius = r,
                    center = Offset(cx + dx, cy + dy),
                    blendMode = if (isLight) BlendMode.Multiply else BlendMode.Screen,
                )
            }

            // CSS 的 left/top/right/bottom 定位的是光源圆的外接盒，圆心 = 盒角 + 半径
            // a1：340px @ left -70 top -90 → 圆心 (100, 80)，漂移 (46,30) scale 1.12，周期 14s
            blob(palette.seed, px(170), px(100), px(80), px(46) * p1, px(30) * p1, 1f + 0.12f * p1)
            // a2：300px @ right -110 top 26% → 圆心 (w-40, 26%h+150)，漂移 (-40,44) scale .94，周期 17s
            blob(palette.seed2, px(150), w - px(40), h * 0.26f + px(150), -px(40) * p2, px(44) * p2, 1f - 0.06f * p2)
            // a3：260px @ left 14% bottom -80 → 圆心 (14%w+130, h-50)，漂移 (30,-36) scale 1.08，周期 20s
            blob(palette.seed, px(130), w * 0.14f + px(130), h - px(50), px(30) * p3, -px(36) * p3, 1f + 0.08f * p3)

            // 底部径向暗角：radial-gradient(80% 50% at 50% 110%, 黑35%, transparent 70%)；浅色模式收敛为轻影
            drawRect(
                Brush.radialGradient(
                    0f to Color.Black.copy(alpha = if (isLight) 0.10f else 0.35f),
                    0.7f to Color.Transparent,
                    1f to Color.Transparent,
                    center = Offset(w / 2f, h * 1.1f),
                    radius = maxOf(w * 0.8f, h * 0.5f),
                ),
            )
        },
    )
}
