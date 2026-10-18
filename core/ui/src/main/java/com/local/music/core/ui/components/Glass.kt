package com.local.music.core.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.local.music.core.ui.theme.GlassColors
import com.local.music.core.ui.theme.GlassMotion
import com.local.music.core.ui.theme.LocalGlassColors

/** 玻璃层级：hi(白16%) / md(白10%) / lo(白7%)，对应 blur 30 / 20 / 12 的视觉重量 */
enum class GlassLevel { Hi, Md, Lo }

internal fun GlassColors.fill(level: GlassLevel): Color = when (level) {
    GlassLevel.Hi -> hi
    GlassLevel.Md -> md
    GlassLevel.Lo -> lo
}

/** 顶部高光线：left/right 8%，1px，transparent → 白55% → transparent */
private fun Modifier.glassTopLine(color: Color): Modifier = drawBehind {
    val y = 0.5f
    drawLine(
        brush = Brush.horizontalGradient(
            listOf(Color.Transparent, color, Color.Transparent),
            startX = size.width * 0.08f,
            endX = size.width * 0.92f,
        ),
        start = Offset(size.width * 0.08f, y),
        end = Offset(size.width * 0.92f, y),
        strokeWidth = 1.dp.toPx(),
    )
}

/** 按压回弹：180ms scale(.92)，统一弹簧曲线（CSS :active 的 Compose 等价） */
@Composable
fun Modifier.pressScale(
    interactionSource: MutableInteractionSource,
    pressed: Float = 0.92f,
): Modifier {
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) pressed else 1f,
        animationSpec = GlassMotion.tweenPress(),
        label = "pressScale",
    )
    return graphicsLayer { scaleX = scale; scaleY = scale }
}

/**
 * 玻璃面板：填充 + 高光描边 + 顶部亮线 + 柔和投影。
 * （backdrop-filter 的跨版本近似：半透明填充 + saturate 视觉补偿由极光底色保证；
 *   滚动列表内禁用实时模糊是规范面板的性能预算要求）
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    level: GlassLevel = GlassLevel.Md,
    shape: Shape = androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    blur: GlassLevel? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val glass = LocalGlassColors.current
    val interactionSource = remember { MutableInteractionSource() }
    var m = modifier
        .pressScale(interactionSource, pressed = if (onClick != null) 0.97f else 1f)
        .clip(shape)
    // 模糊必须落在 clip(shape) 之后：hazeEffect 的背景涂抹以其矩形节点 bounds 为界，
    // 不裁形的话毛玻璃会溢出胶囊/卡片的圆角（白色矩形框）
    if (blur != null) m = m.glassHaze(blur)
    m = m
        .background(glass.fill(level))
        .border(1.dp, glass.strokeSoft, shape)
        .glassTopLine(glass.topLine)
    if (onClick != null || onLongClick != null) {
        m = m.combinedClickable(
            interactionSource = interactionSource,
            indication = null,
            onClick = onClick ?: {},
            onLongClick = onLongClick,
        )
    }
    Box(m, content = content)
}

/** 玻璃圆形按钮：40dp，lo 玻璃 + 细描边，按压 scale(.92) */
@Composable
fun GlassIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    content: @Composable BoxScope.() -> Unit,
) {
    val glass = LocalGlassColors.current
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier
            .pressScale(interactionSource)
            .size(size)
            .clip(CircleShape)
            .background(glass.lo)
            .border(1.dp, glass.strokeSoft, CircleShape)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
        content = content,
    )
}

/** 玻璃开关：42×25，开启填充 seed 色，滑块 19dp 弹性移动 */
@Composable
fun GlassToggle(
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val glass = LocalGlassColors.current
    val palette = com.local.music.core.ui.theme.LocalAuroraPalette.current
    val interactionSource = remember { MutableInteractionSource() }
    val fraction by animateFloatAsState(
        targetValue = if (checked) 1f else 0f,
        animationSpec = GlassMotion.tweenHover(),
        label = "toggle",
    )
    val track = androidx.compose.foundation.shape.RoundedCornerShape(50)
    Box(
        modifier
            .size(42.dp, 25.dp)
            .clip(track)
            .background(
                androidx.compose.ui.graphics.lerp(
                    LocalGlassColors.current.textPrimary.copy(alpha = 0.18f), palette.seed, fraction,
                ),
            )
            .clickable(interactionSource = interactionSource, indication = null) { onToggle(!checked) }
            .semantics {
                this.role = Role.Switch
                this.toggleableState = if (checked) ToggleableState.On else ToggleableState.Off
            },
    ) {
        val thumbSize = 19.dp
        val travel = 42.dp - thumbSize - 6.dp
        Box(
            Modifier
                .graphicsLayer { translationX = (3.dp + travel * fraction).toPx() }
                .align(Alignment.CenterStart)
                .size(thumbSize)
                .clip(CircleShape)
                .background(Color.White),
        )
    }
}
