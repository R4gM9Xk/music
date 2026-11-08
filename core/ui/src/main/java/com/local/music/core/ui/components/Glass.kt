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
import androidx.compose.ui.draw.drawWithCache
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
import com.local.music.core.ui.theme.R

/** 玻璃层级：hi(白16%) / md(白10%) / lo(白7%)，对应 blur 30 / 20 / 12 的视觉重量 */
enum class GlassLevel { Hi, Md, Lo }

internal fun GlassColors.fill(level: GlassLevel): Color = when (level) {
    GlassLevel.Hi -> hi
    GlassLevel.Md -> md
    GlassLevel.Lo -> lo
}

/**
 * 顶部高光线：left/right 8%，1px，transparent → 白55% → transparent。
 *
 * 用 drawWithCache 而不是 drawBehind：玻璃卡在滚动/动画里会频繁重绘，而这条线的 Brush
 * 只依赖 (color, width) —— 宽度在 cache 块里已知，颜色变了 cache 自然失效重算，
 * 帧间复用同一个 Brush，不再每帧 new 一个 horizontalGradient。
 */
private fun Modifier.glassTopLine(color: Color): Modifier = drawWithCache {
    val startX = size.width * 0.08f
    val endX = size.width * 0.92f
    val brush = Brush.horizontalGradient(
        listOf(Color.Transparent, color, Color.Transparent),
        startX = startX,
        endX = endX,
    )
    val strokeWidth = 1.dp.toPx()
    onDrawBehind {
        drawLine(
            brush = brush,
            start = Offset(startX, 0.5f),
            end = Offset(endX, 0.5f),
            strokeWidth = strokeWidth,
        )
    }
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
 * 玻璃面板：填充 + **按层级递减的装饰**（描边 / 顶部亮线）+ 柔和投影。
 * （backdrop-filter 的跨版本近似：半透明填充 + saturate 视觉补偿由极光底色保证；
 *   滚动列表内禁用实时模糊是规范面板的性能预算要求）
 *
 * ## 装饰的层级递减规则（视觉审计 D3，2026-11-08）——全 App 唯一的装饰分配表
 *
 * | 层级 | 代表表面 | 填充 | 描边 | 顶部亮线 |
 * |------|----------|------|------|----------|
 * | Hi | 迷你播放条、顶部横幅（悬浮 chrome） | ✓ | ✓ | ✓ |
 * | Md | 面板 / 控制台 / 卡片 / 导航栏 / 输入框 | ✓ | ✓ | ✗ |
 * | Lo | 芯片、低层底 | ✓ | ✗ | ✗ |
 *
 * **为什么要递减，而不是「每块玻璃都做全套」**：描边 + 顶部亮线这两个装饰合起来是一句
 * 「我悬浮在别的内容之上」的宣言，它只有对**少数表面**才成立。改前 GlassSurface 对任意
 * level 一律上全套，于是 26dp 的搜索历史芯片、横幅、面板、卡片全都长着同一套高光——
 * 整屏都是「小玻璃片」，层级反而被抹平。token-baseline R7 要求「层级差需 ≥2 维度
 * （明度 + 形状/内容）」，而当所有层级共享同一套装饰时，层级差只剩明度一维，
 * 且 16%/10%/7% 的填充差在深色极光底上几乎读不出来。
 *
 * **每一档的取舍理由（这是规则，不是偏好）**：
 * - **Hi 保留全套**：它是唯一**真正悬浮**的表面（mini bar / 横幅压在滚动内容之上）。
 *   顶线在这里表达的是一个真实的空间事实——「这块玻璃的顶边在这里」。
 * - **Md 去掉顶线、只留描边**：面板/卡片贴在内容流里，不是浮在别的内容上面。描边已足够
 *   定义轮廓（浅色模式尤其关键：描边已反转为近黑 9%，是卡片唯一的边缘定义），
 *   在描边之上再画一条 55% 白的顶线纯属装饰噪声——它不表达任何东西，只跟卡片内容抢注意力。
 * - **Lo 连描边也去掉**：芯片是**控件**不是**容器**，它靠自身的文字/图标定义存在感；
 *   再套描边会让每个 26dp 的小块变成一张独立玻璃片，正是 D3 要消灭的观感。
 *
 * **怎么选 level**：按**语义**选，不按尺寸选（小不等于 Lo，大不等于 Hi）。同一屏上
 * 最多出现相邻两档；新增表面拿不准就选 Md。**不要**为了让某个元素「更亮眼」而升档——
 * 视觉权重应该由内容（封面/文字）承担，这正是不递减时丢掉的判断。
 *
 * 本规则**不外推**到 [GlassIconButton]：它是控件且自带圆形容器，描边是它唯一的形状定义
 * （浅色模式 fill=白 32% 压在近白极光上，去掉描边按钮就消失），且它本来就没有顶线——
 * 装饰强度已等同于 Md 档，无需再降。详见该函数 KDoc。
 *
 * 契约（P3-75）：onLongClick 必须与 onClick **同时**提供——只传 onLongClick 时点击事件
 * 会被 combinedClickable 消费（onClick 兜底为空 lambda），卡片看起来"点不动"却吞掉了点击，
 * 上层列表的点击滚动/透传全被截断。
 *
 * [scrim]（F3 / G2 透字修复）：默认 null = 原观感，**全 app 共用组件
 * 保持向后兼容**，只有迷你播放条与底部导航栏显式传入（见 Chrome.kt）。
 *
 * 为什么需要：这两个条悬在滚动列表之上，列表行文字滑到其下方时会「透上来」。真机量化
 * （一加13）证明 haze **没有失效**——透字笔画边缘过渡 14–29px，而同字号清晰文字仅 2px，
 * 模糊确实生效；问题是「有模糊 ≠ 不可辨」：经 blur + 白 tint 之后，透字仍保留
 * 迷你条 4.3:1 / 导航栏 11.4:1 的局部对比，达到 chrome 自身文字的 50%–96%，
 * 与 chrome 文案双重显影（深色模式尤其刺眼：浅色幽灵字压在深玻璃上）。
 *
 * 为什么叠 scrim 而不是直接加深 fill：fill 是**白色系**（dark 下 hi=白16%、md=白10%），
 * 再叠白只会把背景提亮，浅色幽灵字反而更明显。scrim 用「与主题同向」的低 alpha 底
 * （深色模式压黑、浅色模式提白），把幽灵字笔画与玻璃底色**一起**推向同一极；
 * WCAG 对比度里的 +0.05 常数项在两端会压缩比值，透字对比因此塌陷，
 * 而 chrome 自身文字画在 scrim **之上**、完全不受衰减（深色下白字对比反而略升）。
 *
 * 为什么同时保留 haze：scrim 只压对比、不抹细节，单靠它会退化成「半透明板」——
 * 那正是历史审计的错误推测方向，也会毁掉玻璃通透感。两者叠加才是
 * 「透字不可辨 + 玻璃仍有模糊通透」：haze 负责抹掉字形语义，scrim 负责压掉残余对比。
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    level: GlassLevel = GlassLevel.Md,
    shape: Shape = androidx.compose.foundation.shape.RoundedCornerShape(R.Player28.dp),
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    blur: GlassLevel? = null,
    scrim: Color? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val glass = LocalGlassColors.current
    val interactionSource = remember { MutableInteractionSource() }
    // A6：容器不再缩放（旧 pressScale(0.97) 见下）。这里**直接**从 modifier 接到 clip(shape)，
    // 层序与改前逐节点等价：clip 仍是内部链的第一笔，haze/fill/scrim/描边全部在它之内。
    var m = modifier.clip(shape)
    // 模糊必须落在 clip(shape) 之后：hazeEffect 的背景涂抹以其矩形节点 bounds 为界，
    // 不裁形的话毛玻璃会溢出胶囊/卡片的圆角（白色矩形框）
    //
    // P3-78 双重填充修复：haze tint（HazeGlass.glassHaze）已上过一层 glass.fill(level) 色，
    // 旧实现 blur 面板在 tint 之上又无条件 .background(glass.fill(level))，同一层色叠两次
    // → 带 blur 的玻璃比不带 blur 的亮近一倍。现在有 blur 时填充交给 haze tint 承担；
    // 但 LocalHazeState 为 null 时 glassHaze 是 no-op（HazeGlass.kt 退化路径），
    // 此时必须回退到普通 background，否则整块面板变透明（底色直接透出）。
    var hazed = false
    if (blur != null && LocalHazeState.current != null) {
        m = m.glassHaze(blur)
        hazed = true
    }
    // scrim 的落点很关键：必须叠在「haze 结果 / fill」**之上**、Box 内容**之下**。
    // 这样它只衰减背景（含透上来的列表文字），chrome 自身文字与图标完全不受影响。
    // 反过来先 background(scrim) 再 hazeEffect，scrim 会被 haze 采样/覆盖掉，等于没加。
    //
    // 两段独立判断而不是 if/else：退化路径（LocalHazeState 为 null）也要保留 fill，
    // 否则一旦没有 haze，带 scrim 的面板就只剩一层半透明底、玻璃填充丢失。
    //
    // **不要指望 blur 一定生效**（F3 实测教训）：haze 的 blur 走 RenderEffect，
    // 软件渲染 / 无 RenderEffect 的设备上会静默退化为纯 tint（零模糊）。模拟器
    // （ro.hardware.egl=emulation）实测就是这种退化：透字裁剪后 grad_p97 仍 55.9–60.0，
    // 与清晰文字同量级。所以 scrim 必须**独立**把透字压到不可辨。
    //
    // 为什么 alpha 要给到 0.86 这个量级（实测推算，不是拍脑袋）：
    // 深色模式下列表文字是**白色**（lum≈240），chrome 底 lum≈52。透字经 alpha=a 的
    // 黑色遮罩后残值 = 240×(1−a)。要它与底色**无法区分**，需
    //     240×(1−a) ≤ 52  →  a ≥ 0.78
    // 实测 a=0.62 时残值仍有 91（vs 底 52），对比 ≈1.57 —— 仍是一眼可读的白斑，
    // 这正是本轮 0.34 与 0.62 两版都没过的原因。取 0.86 → 残值 34，已低于底色。
    //
    // **scrim 之后不能再补一层 fill**：补了等于把遮罩「提亮回半透明」，
    // 残值又被抬到 46–50 与底色拉开，字形重新浮现（实测第三版就是这么被打回 1.2:1
    // 仍可辨）。scrim 必须是这一层**最后一笔**背景，玻璃的通透色调由 scrim 自身的
    // 深色 + 上方的描边/顶光线承担，不再靠白色 fill 提亮。
    if (!hazed) m = m.background(glass.fill(level))
    if (scrim != null) m = m.background(scrim)
    // ── 装饰按层级递减（视觉审计 D3，本轮全 App 视觉收敛的最高杠杆点）──
    //
    // 改前：任何 level 一律 `border(strokeSoft) + glassTopLine(topLine)`。
    // 于是 26dp 高的搜索历史芯片、40dp 圆钮、横幅、面板、内容卡**全部**带着
    // 「1dp 描边 + 55% 白顶线」——顶线与描边本是**悬浮玻璃**的语言（光从上方来、
    // 边缘折射出亮线），当每一层都带它时，整屏都是「小玻璃片」，层级反被抹平。
    //
    // token-baseline R7 自己定了「层级差需 ≥2 维度（明度+形状/内容）」，而装饰一致
    // 恰恰是把这个差抹掉的元凶。所以这里按层级递减：
    //
    // - Hi（迷你条 / 顶部横幅）：常驻悬浮于滚动内容之上，最需要「我在你之上」的
    //   物理暗示 → 描边 + 顶线全保留。
    // - Md（面板 / 卡片 / 控制台）：容器层级。描边保留（它负责划定边界，尤其是
    //   浅色模式下的边缘定义），顶线去掉（顶线是「浮起」的暗示，面板坐在地上）。
    // - Lo（圆钮 / 芯片 / 低层底）：最低层级。两者都不要——纯色块，靠明度区分即可。
    //   实测：去掉后搜索历史芯片从「小玻璃片」变成「安静的标签」，这正是芯片该有的样子。
    //
    // 已知影响面：Lo 层的可视化变化最大（GlassChip / GlassIconButton 内部 / 各种
    // 低层容器底色）。这是有意的——「克制」的第一层含义就是少装饰。
    m = when (level) {
        GlassLevel.Hi -> m
            .border(1.dp, glass.strokeSoft, shape)
            .glassTopLine(glass.topLine)
        GlassLevel.Md -> m.border(1.dp, glass.strokeSoft, shape)
        GlassLevel.Lo -> m
    }
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

/** 玻璃圆形按钮：40dp，lo 填充 + 细描边，按压 scale(.92)
 *
 *  **为什么它保留描边，而 [GlassSurface] 的 Lo 层不再画描边**（视觉审计 D3 的边界澄清）：
 * 两者都是 lo 填充，但语义不同——
 * - 本组件是**独立可点控件**（页头圆钮、详情页顶栏的返回/添加/更多）。描边在这里不是
 *   装饰，而是「这是一个可以按的东西」的边界定义；去掉后它会退化成一块漂浮的色斑，
 *   在浅色模式下（lo = 白 32%）尤其难以辨认。
 * - GlassSurface 的 Lo 层使用者目前只有搜索历史芯片——那是**纯标签**，它的可点性由
 *   胶囊形状与整块底色表达，不需要再加一圈描边去「加粗」自己。
 *
 * 一句话规则：**描边定义边界，不是增加层级**。需要边界的（按钮）保留，靠形状已能
 * 自证的（芯片）去掉。
 */
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
    val track = androidx.compose.foundation.shape.RoundedCornerShape(R.Full)
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
