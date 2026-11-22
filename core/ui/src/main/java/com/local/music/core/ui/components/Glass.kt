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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
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
import com.local.music.core.ui.theme.LocalSkeuomorphColors
import com.local.music.core.ui.theme.LocalThemeFraction
import com.local.music.core.ui.theme.LocalVisualStyle
import com.local.music.core.ui.theme.R
import com.local.music.core.ui.theme.SkeuoElevation
import com.local.music.core.ui.theme.SkeuoRole
import com.local.music.core.ui.theme.SkeuomorphColors
import com.local.music.core.ui.theme.onSeedColor

/**
 * 玻璃层级。
 *
 * 前三档沿用既有语义（hi 白16% / md 白10% / lo 白7%，对应 blur 26 / 20 / 12 的视觉重量）；
 * 第四档 [Liquid] 是**本轮新增**，它不是「更亮的一档」，而是**另一套装饰语言**。
 *
 * ## 为什么新增一档，而不是把 Hi 改强
 *
 * Hi 已被迷你条 / 底部导航 / 下拉浮层占用，且这三处都叠着 `chromeScrim`（透字可读性的
 * 兜底）。改 Hi 等于连带动到全 App 最敏感的三块表面——那是可读性红线。
 * 新增一档则对现有 15 个调用点**零影响**（默认值仍是 Md），只在显式写
 * `level = GlassLevel.Liquid` 的地方生效。
 *
 * ## Liquid 与 Hi 的差别（两笔，都是「新增装饰」而非「加亮」）
 *
 * 1. **方向性斜面**替代四边均匀描边：顶亮 / 侧中 / 底暗（[com.local.music.core.ui.theme.GlassColors.liquidEdge*]）。
 *    均匀描边说「这里有个边界」，方向性斜面说「光从上面来，这块玻璃有厚度」。
 * 2. **内部 specular**：右上角一块大半径极淡椭圆亮斑（[GlassColors.liquidSpecular]），
 *    模拟环境光在表面的反射。
 *
 * ## 这一档**做不到**什么（务必读完再用）
 *
 * 真实「液态玻璃」（iOS 26 那类）最抓眼的性质是**边缘对背后内容做折射/畸变**——
 * 因为玻璃是厚介质。本档做不到，且不是参数问题：
 * - 项目唯一的糊化能力是 haze 1.6.10，它只做「高斯模糊 + tint」，**没有位移/折射采样**；
 * - 真要做折射只有 `RenderEffect.createRuntimeShaderEffect`（AGSL）一条路，且要拿到
 *   背景层纹理做重采样——成本与风险远高于「加两笔装饰」，本轮明确不做。
 *
 * 所以本档的准确描述是「**更有厚度、更有立体感的玻璃**」，不是「液态玻璃」。
 * 见 `docs/proto/glass-3tiers.png` 与本档的渲染标定说明。
 *
 * ## 「动态光影」怎么落地（本轮做法）
 *
 * 不引入传感器（全仓零传感器代码，且原始陀螺仪数据抖动大、需要滤波）。
 * specular 的位置与强度由**已有的连续动画值**驱动（`liquidMotion` 参数），
 * 调用方可以传播放进度、歌词滚动、或极光的 infinite transition —— 见 [GlassSurface] 的
 * `liquidMotion` 形参。这样「光影在流动」不需要任何新基础设施。
 */
enum class GlassLevel { Hi, Md, Lo, Liquid }

internal fun GlassColors.fill(level: GlassLevel): Color = when (level) {
    GlassLevel.Hi -> hi
    GlassLevel.Md -> md
    GlassLevel.Lo -> lo
    // Liquid 的「填充」取 hi 的**低浓度版**：控制台背后现在是真模糊的极光/照片
    // （GlassApp 把覆盖层背景挂进了 playerHaze 源），玻璃的色彩与纵深应来自被模糊的
    // 背景本身。旧值直接取 hi（浅 60% 白 / 深 16% 白）会把模糊背景罩成奶白/灰平板
    // （用户反馈「太偏黑/偏白」）；降到 35% 浓度后背景色相透出、读作有色玻璃，
    // 透字由真模糊承担而非靠厚填充压死。差异化装饰（斜面+specular）不受影响。
    GlassLevel.Liquid -> hi.copy(alpha = hi.alpha * 0.35f)
}

/**
 * Liquid 档的填充色（跨模块 public：app 模块的飞行替身必须画同一个端点）。
 *
 * 真身（[GlassSurface] 的 `level = Liquid`）与替身（`FlightGlassPanel` 的 t=1 端）
 * 一旦填充色不一致，落地交叉淡化窗内玻璃明度会跳半档——这正是替身层 KDoc 警告的
 * 「任何一层差一档」。所以配方只留这一份，替身读它而不是复制 0.35f。
 */
val GlassColors.liquidFill: Color get() = fill(GlassLevel.Liquid)

/**
 * 玻璃面板的统一「内衬」(veil)：深色压黑、浅色提白，按主题进度连续插值。
 *
 * 队列面板与底部 chrome（迷你条 / 导航）共用这一份配方，保证「悬浮玻璃」与「面板玻璃」
 * 读作同一种材质。它叠在 haze 模糊结果之上、内容之下，只压背景透入、不影响自身文字。
 * 比 chromeScrim 轻得多——背后模糊内容会透出来，这正是通透玻璃感的来源；
 * 代价是滚动列表的透字会隐约可见（雾里的轮廓），属有意取舍。
 *
 * 单一来源：真身（[GlassSurface] 的 scrim 入参）与飞行替身（FlightGlassPanel）都读它，
 * 避免「改一处忘另一处」的端点漂移。
 */
@Composable
fun glassVeil(): Color = lerp(
    Color.Black.copy(alpha = 0.32f),
    Color.White.copy(alpha = 0.62f),
    LocalThemeFraction.current,
)

/**
 * 液态档装饰的笔刷集：只在 `drawWithCache` 的 cache 块里构造一次，绘制期帧间复用。
 *
 * 为什么抽成独立类型：飞行替身（app 模块的 `PlayerFlightOverlay.FlightGlassPanel`）的
 * 落点端也是 Liquid，需要画**同一套**方向性斜面 + specular。如果替身自己复制一份笔刷
 * 构造，就又是「改真身忘改对端」的温床；共用这个 holder 后，两处只共享反证过的事实。
 *
 * 跨模块 public（而非 internal）的唯一理由：真身在本模块、替身在 app 模块，两者必须
 * 同源。它不是给业务代码直接调用的绘制 API，普通调用方请用 [GlassSurface]。
 */
class LiquidGlassBrushes(
    val top: Brush,
    val bottom: Brush,
    val left: Brush,
    val right: Brush,
    val specular: Brush,
)

/** 构造 [LiquidGlassBrushes]；**必须在 drawWithCache 的 cache 块里调用**（零 Shader 分配红线）。
 *
 * 参数是 `size` 而不是 DrawScope receiver：drawWithCache 的 cache 块 receiver 是
 * `CacheDrawScope`（只提供 size/density，不是 DrawScope），把尺寸显式传入可以让真身
 * （liquidGlassDecor）与飞行替身（FlightGlassPanel）共用同一个构造器。
 */
fun liquidGlassBrushes(glass: GlassColors, size: Size): LiquidGlassBrushes {
    val w = size.width
    val h = size.height
    val sideInset = w * 0.10f
    // 顶/侧/底三笔的方向性渐变：中间亮、两端渐隐，收在圆角之前结束，
    // 避免在四角与弧线交叉成「直角套圆角」的脏边。
    val topBrush = Brush.horizontalGradient(
        listOf(Color.Transparent, glass.liquidEdgeTop, Color.Transparent),
        startX = sideInset, endX = w - sideInset,
    )
    val bottomBrush = Brush.horizontalGradient(
        listOf(Color.Transparent, glass.liquidEdgeBottom, Color.Transparent),
        startX = sideInset, endX = w - sideInset,
    )
    val leftBrush = Brush.verticalGradient(
        listOf(Color.Transparent, glass.liquidEdgeSide, Color.Transparent),
        startY = h * 0.12f, endY = h * 0.88f,
    )
    val rightBrush = Brush.verticalGradient(
        listOf(Color.Transparent, glass.liquidEdgeSide, Color.Transparent),
        startY = h * 0.12f, endY = h * 0.88f,
    )
    // specular：单位空间径向渐变（center=0, radius=1），绘制时用变换定位，
    // Shader 本身全程不变 —— 与 AuroraBackground 的 blob 同一手法。
    val specularBrush = Brush.radialGradient(
        0f to glass.liquidSpecular,
        0.45f to glass.liquidSpecular.copy(alpha = glass.liquidSpecular.alpha * 0.45f),
        1f to Color.Transparent,
        center = Offset.Zero,
        radius = 1f,
    )
    return LiquidGlassBrushes(topBrush, bottomBrush, leftBrush, rightBrush, specularBrush)
}

/**
 * 画液态档的 specular + 方向性斜面（1px 贴边）。
 *
 * [alpha] 用于飞行替身的落地端淡入（真身恒 1）；[motion] 是 0..1 的连续光影驱动值，
 * 必须来自绘制期可读的 lambda。
 */
fun DrawScope.drawLiquidGlass(brushes: LiquidGlassBrushes, motion: Float, alpha: Float) {
    val w = size.width
    val h = size.height
    val strokeWidth = 1.dp.toPx()
    // ── 1. specular（最先画，在边缘之下）──
    // 亮斑中心在右上偏内；motion 让它在横向小幅漂移（±6% 宽）并轻微呼吸。
    val t = motion.coerceIn(0f, 1f)
    val cx = w * (0.74f + 0.06f * (t - 0.5f) * 2f)
    val cy = h * 0.16f
    val rx = w * (0.42f + 0.05f * t)
    val ry = h * 0.30f
    withTransform({
        translate(cx, cy)
        scale(rx, ry, pivot = Offset.Zero)
    }) {
        drawCircle(brush = brushes.specular, radius = 1f, center = Offset.Zero, alpha = alpha)
    }

    // ── 2. 方向性斜面（顶亮 / 侧中 / 底暗）──
    drawLine(brushes.top, Offset(0f, strokeWidth / 2f), Offset(w, strokeWidth / 2f), strokeWidth, alpha = alpha)
    drawLine(
        brushes.bottom,
        Offset(0f, h - strokeWidth / 2f), Offset(w, h - strokeWidth / 2f),
        strokeWidth, alpha = alpha,
    )
    drawLine(brushes.left, Offset(strokeWidth / 2f, 0f), Offset(strokeWidth / 2f, h), strokeWidth, alpha = alpha)
    drawLine(
        brushes.right,
        Offset(w - strokeWidth / 2f, 0f), Offset(w - strokeWidth / 2f, h),
        strokeWidth, alpha = alpha,
    )
}

/**
 * 液态档的**方向性斜面 + specular**，一笔画完（`drawWithCache` 保 Brush 帧间复用）。
 *
 * ## 层序为什么必须在这里
 *
 * 本修饰符要挂在 `clip(shape)` **之后**、内容**之前**（与 [glassTopLine]/haze 同一段）。
 * 边缘四笔用 `drawLine` 贴着节点边界画，被 clip 裁掉圆角外的部分——这正是要的：
 * 圆角处斜面自然跟着弧线走，不需要为 RoundedCornerShape 单独算路径。
 *
 * ## specular 的「动态」来源
 *
 * [motion] 是 0..1 的连续值（播放进度 / 歌词滚动 / 无限漂移都可以），只用来平移亮斑
 * 的横向位置与缩放半径。**必须由调用方以绘制期可读的形式提供**（lambda 或
 * `() -> Float`），不要传一个组合期读的动画值——那会让整块玻璃每次动画帧都重组。
 * 这里接收的是 `() -> Float`，在 `onDrawBehind` 内逐帧读取（绘制期），零重组。
 *
 * ## 性能红线
 *
 * 所有 Brush 都在 `drawWithCache` 的 cache 块里构造（只依赖 size/颜色），帧间复用；
 * `onDrawBehind` 内只做 draw 调用。这是本仓既定红线（见 [glassTopLine]、
 * [com.local.music.core.ui.components.AuroraBackground] 的同款说明）。
 */
private fun Modifier.liquidGlassDecor(
    glass: GlassColors,
    motion: () -> Float,
): Modifier = drawWithCache {
    val brushes = liquidGlassBrushes(glass, size)
    onDrawBehind {
        drawLiquidGlass(brushes, motion(), 1f)
    }
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
 *
 * ## 为什么**不能**改成「加大高斯模糊、降低 scrim」（2026-11-08 定量结案）
 *
 * 用户问过这条路线，上一轮只回了一句「加大半径肉眼不可辨」——那句话是从 scrim 已达
 * 0.80 倒推的，**没有算式**。本轮补上了，结论是**不可行**，且不是因为 GPU 太贵，
 * 而是三个独立的、更硬的原因：
 *
 * ### ① 高斯模糊是保均值滤波，存在不可约下界
 *
 * 透字可见度可以正交分解成两部分：
 * ```
 *     透字信号 = 高频（笔画形状/边缘）  +  低频（整行文字把这块玻璃整体提亮）
 *                ↑ blur 能衰减              ↑ blur **完全无法**改变（保均值）
 * ```
 * 低频项就是下界。实测反解（见 [com.local.music.core.ui.theme.GlassColors.chromeScrim]）：
 * 迷你条在 scrim=0.80 时，把半径推到**无穷大**（模糊成一个平色场）也只能从 1.40:1
 * 降到 1.20:1；导航栏 1.80:1 → 1.33:1。花无穷大的代价，买 0.2–0.5 的对比度。
 *
 * ### ② 半径是线性增益：Δc ≈ 0.1，不值得
 *
 * 残余调制 m ∝ 1/r（详见 [glassHaze] 的 KDoc）。26→48dp（1.85 倍）在 scrim=0.80 下
 * 只能把对比度从 1.40 拉到 1.18。而把 scrim 从 0.80 提到 0.86（GPU 零增量）能拉到 1.25
 * ——**同样的效果，一个要翻倍 GPU 开销，一个不要**。
 *
 * ### ③ 退化路径下 blur 的收益恒为 0，这是封口条件
 *
 * 软件渲染 / 无 RenderEffect 的设备上 haze 静默退化成纯 tint（零模糊）。此时
 * 「加大半径」的收益是**严格的 0**，而「降低 scrim」的代价是**全部兑现**。
 * 反解退化路径：要守住 2:1 需 scrim ≥ **0.772**（导航栏先封口）。
 * 也就是说 scrim 最多只能从 0.80 降到 0.78 —— 而 0.78 相对 0.80 在 blur 生效时
 * 只买回 0.04 的对比度（1.40→1.44），**肉眼不可辨**。
 * 结论：这条路线的可行域宽度几乎为零，不存在有意义的实现空间。
 *
 * ## [liquidMotion]：只有 [GlassLevel.Liquid] 使用的「光影驱动」
 *
 * Liquid 档的 specular 需要「随时间变化」才有液态感。这里**不引入传感器**（全仓零
 * 传感器代码，且原始陀螺仪数据需滤波才能用），而是让调用方把一个**已有的**连续量
 * 传进来——播放进度、歌词滚动位置、滚动偏移、`rememberInfiniteTransition` 的输出都行。
 *
 * 契约（重要）：这个 lambda 会在**绘制期**被逐帧调用，所以它内部**必须只读取动画值**
 * （`Animatable.value` / `State.value` / 自定义 provider），不能触发组合。传一个
 * `animateFloatAsState` 的 `by` 解构值会让整块玻璃每帧重组——那是本仓反复点名的错误写法
 * （见 `PlayerScreen` 的 `graphicsLayer` lambda 注释）。
 *
 * 默认 `{ 0f }` 表示静止（不漂移），此时 Liquid 档仍保留方向性斜面与静态 specular。
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
    liquidMotion: () -> Float = { 0f },
    /**
     * 描边颜色覆盖（本轮新增，null = 用层级 token 的默认值）。
     *
     * 为什么需要这个口子：输入框要在**聚焦时**把描边换成强调色，而描边颜色原本由
     * 层级 token（strokeSoft）在内部决定，调用方无从影响。
     *
     * 为什么做成参数而不是让调用方在外部 `.border(...)`：外层再画一圈会得到
     * **两条描边**（一条在 clip 内、一条在 clip 外），圆角处会出现双线错位——
     * 玻璃卡片的描边必须只有一处来源。
     *
     * **默认 null 保证零回归**：现有所有调用点都不传，行为逐像素不变。
     * 只有 Hi/Md 两档真正绘制描边（Lo/Liquid 不画），所以本参数对那两档无效——
     * 这与「Lo 靠形状自证、Liquid 用方向性斜面」的既有规则一致，不是遗漏。
     */
    strokeColor: Color? = null,
    /**
     * 用**「靠模糊挡字」配方**替代 [blur] 的常规层级模糊（默认 null = 零回归）。
     *
     * ## 为什么需要这个口子（2026-11-21 用户反馈，两轮迭代）
     *
     * 用户原话：「Chrome 太暗/亮了，遮住下方文字，我要的是**模糊**下方文字，不是遮挡」，
     * 随后：「模糊程度再低一点」「队列面板、下拉浮层也一样改」。
     *
     * 旧配方是「haze tint + scrim」两层叠遮光，以 chrome 为例：
     * | 层 | 深色 | 浅色 |
     * |---|---|---|
     * | haze tint（Hi = `hi`） | 白 16% | 白 **60%** |
     * | `scrim = glassVeil()` | 黑 32% | 白 62% |
     * | **合计** | ≈43% | ≈**85%** |
     *
     * 浅色档只剩 15% 的模糊内容能透出来——玻璃读作一块白板。模糊一直在跑，只是被遮死了。
     *
     * 传非 null 时切到 [blurVeil] 配方：tint 只定调、遮挡层整体去掉、遮挡职责交给模糊。
     * **同时调用方必须传 `scrim = null`**——遮挡层是问题的另一半，只换 haze 不撤 scrim
     * 等于没改（本实现会在 haze 真挂上时忽略 scrim，但调用点仍应显式传 null 以免误读）。
     *
     * @param blurVeil 档位（Chrome / Panel / Popup），见 [BlurVeilKind] 的对比表。
     *   退化路径（无 haze 源）下仍走普通 fill + scrim，否则整块表面会变透明。
     */
    blurVeil: BlurVeilKind? = null,
    /**
     * 拟物材质角色（仅 [com.local.music.core.ui.state.VisualStyle.Skeuomorph] 生效）。
     *
     * 为什么需要显式声明：拟物档下「这个面是机身、按键、凹槽还是封套」决定了它该不该
     * 投影、该不该有斜面、按下时该怎么动——这些语义无法从 [level] 推出。
     * 默认 null 时按 level 推导（见函数体内），保证既有调用点零改动。
     */
    skeuoRole: SkeuoRole? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val glass = LocalGlassColors.current
    val interactionSource = remember { MutableInteractionSource() }

    // ── 材质语言分流（快照 v12）──
    //
    // 拟物档在这里**整条换路径**，不是在半透明公式上叠东西。理由见 Skeuomorph 的 KDoc：
    // 拟物的定义就是「不透背景」，而下面所有玻璃笔（haze tint / fill / scrim / border /
    // 顶线）都在表达「我透出你并压住你」——它们与拟物是互斥的两套语言，混在一起会得到
    // 一块「不透明但还在采样背景」的怪面板（性能白付、观感也不对）。
    //
    // **只有点击手势是两条路径共享的**（combinedClickable 在下面统一挂）：
    // 语义与命中区与材质无关，分流不能把可点性分掉。
    val skeuoStyle = LocalVisualStyle.current
    if (skeuoStyle == com.local.music.core.ui.state.VisualStyle.Skeuomorph) {
        val skeuo = LocalSkeuomorphColors.current
        // 角色决定画什么（见 [SkeuoRole]）：机身不投影、按键投影且按下位移、
        // 凹槽反光方向相反、封套用静置投影。
        //
        // 未显式声明角色时的推导规则（保持既有调用点零改动）：
        // - Liquid / Hi  → Sleeve（播放页控制台、迷你条这类「浮起来的实体」）
        // - Lo           → Well（低层底、芯片：压在面上的浅槽）
        // - Md           → Body（设置组卡、卡片：平放的机身面板）
        val role = skeuoRole ?: when (level) {
            GlassLevel.Hi, GlassLevel.Liquid -> SkeuoRole.Sleeve
            GlassLevel.Lo -> SkeuoRole.Well
            GlassLevel.Md -> SkeuoRole.Body
        }
        // 按下位移只对 Key 生效：读取 interactionSource 的按下态
        val pressed by interactionSource.collectIsPressedAsState()
        val isKey = role == SkeuoRole.Key
        // **投影挂在 clip 之前**：它要画到节点边界以外（这是「厚度」信号），
        // 而 clip 会裁掉越界部分。这也是本仓「clip 是内部链第一笔」规则的唯一例外。
        var sm = modifier
            .skeuomorphShadow(skeuo, shape, role, pressed = pressed && isKey)
            .clip(shape)
            .skeuomorphFace(skeuo, role, pressed = pressed && isKey)
        // 按键的按下位移：只移动自身，不缩放（缩放在长列表里读作抖动）。
        // 用 graphicsLayer 的 translationY——它在绘制期生效，不参与布局。
        if (isKey) {
            val travel = if (pressed) SkeuoElevation.PressTravel.dp else 0.dp
            sm = sm.graphicsLayer {
                translationY = travel.toPx()
            }
        }
        if (onClick != null || onLongClick != null) {
            sm = sm.combinedClickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick ?: {},
                onLongClick = onLongClick,
            )
            // 与玻璃档同一处补齐：拟物档的可点面按下同样必须有色层反馈。
            // Key 角色已经有位移 + 压暗，不再叠一层底色（会过重）。
            if (!isKey) sm = sm.rowPressFeedback(interactionSource)
        }
        Box(sm, content = content)
        return
    }

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
    if (blurVeil != null && LocalHazeState.current != null) {
        // 「靠模糊挡字」配方：三个表面共用（见 BlurVeilKind 的对比表）
        m = m.blurVeil(blurVeil)
        hazed = true
    } else if (blur != null && LocalHazeState.current != null) {
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
    // 复算（2026-11-08，本轮把上式的坐标系补齐为「峰-谷 ±w 振幅」，因为单用线性残值
    // 会低估：透字不是一个值，而是一条从底到峰的调制带）：
    //     透字峰 P0 = 240, 底 Q0 = 52, 半幅 h = (240−52)/2 = 94
    //     叠加 tint 与 scrim 后残幅 A = (1−a)·(1−t)·2h，要 A ≤ 判据
    // 实测口径下 scrim 0.80 时 A ≈ 21（sRGB），对比 1.40:1；0.86 时 A ≈ 15，对比 1.25:1。
    // 也就是说 0.86 让「深色下最刺眼的浅色幽灵字」再多压一档，代价是玻璃通透度再降 6%。
    //
    // **scrim 之后不能再补一层 fill**：补了等于把遮罩「提亮回半透明」，
    // 残值又被抬到 46–50 与底色拉开，字形重新浮现（实测第三版就是这么被打回 1.2:1
    // 仍可辨）。scrim 必须是这一层**最后一笔**背景，玻璃的通透色调由 scrim 自身的
    // 深色 + 上方的描边/顶光线承担，不再靠白色 fill 提亮。
    if (!hazed) m = m.background(glass.fill(level))
    // blurVeil 生效时**不叠 scrim**：遮挡层正是用户抱怨的那一半（见 blurVeil 的 KDoc）。
    // 但退化路径（无 haze 源 / 无 RenderEffect）必须保留——那时模糊恒为 0，不叠就等于
    // 一条透明玻璃上压着列表文字，可读性直接崩掉。判据用 hazed（= 真的挂上了 haze）。
    if (scrim != null && !(blurVeil != null && hazed)) m = m.background(scrim)
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
    //
    // - Liquid（播放页封面周围 / 重要交互浮层）：**第四档，本轮新增**。它不用「均匀
    //   描边 + 顶线」这套，而换成方向性斜面 + 内部 specular（见 [liquidGlassDecor]）。
    //   注意这里**不额外加 border**：方向性四笔已经画满整圈边线，再叠一层均匀 border
    //   会把「光从上面来」的方向性重新抹平成四边等亮。
    m = when (level) {
        GlassLevel.Hi -> m
            .border(1.dp, strokeColor ?: glass.strokeSoft, shape)
            .glassTopLine(glass.topLine)
        GlassLevel.Md -> m.border(1.dp, strokeColor ?: glass.strokeSoft, shape)
        GlassLevel.Lo -> m
        GlassLevel.Liquid -> m.liquidGlassDecor(glass, liquidMotion)
    }
    if (onClick != null || onLongClick != null) {
        m = m.combinedClickable(
            interactionSource = interactionSource,
            indication = null,
            onClick = onClick ?: {},
            onLongClick = onLongClick,
        )
        // 按压反馈（本轮补齐）：上面明确 `indication = null`（Material 的水波纹与玻璃
        // 材质不兼容），而**这之后没有任何替代反馈**——于是全 App 所有可点玻璃面
        // （专辑卡 / 歌单卡 / 搜索胶囊 / 搜索历史芯片 / 空歌单卡）按下时毫无变化，
        // 与同仓 SettingRow（rowPressFeedback 底色层）、SheetAction（pressScale 0.98）、
        // ConsoleIcon（pressScale 0.88）的按压语言完全断裂。
        //
        // 复用 SettingRow 那一档「底色层」而不是 pressScale：玻璃卡内部有封面与文字，
        // 整块等比缩放会让内容一起位移（A6 已在 SettingRow 上踩过同一个坑）。
        // 底色层只在绘制期读动画值，按下/松手不产生重组（rowPressFeedback 的 KDoc）。
        //
        // 顺序在 combinedClickable **之后**：rowPressFeedback 只读 interactionSource，
        // 而 interactionSource 必须先被 clickable 订阅才会有按压事件。
        m = m.rowPressFeedback(interactionSource)
    }
    Box(m, content = content)
}

/**
 * 拟物贴面的绘制修饰符：按**角色**选笔，而不是按「凸起/凹陷」二分。
 *
 * ## 为什么必须按角色（2026-11 重设计）
 *
 * 上一版只有 `raised: Boolean` 一个维度，于是设置组卡（Card）与播放键（Key）走同一条
 * 绘制路径，只是尺寸不同——它们看起来一样厚，只是大小不同。这正是「整屏浮雕」的来源。
 *
 * 新方案由调用方声明意图（[SkeuoRole]），材质层按角色决定画什么：
 *
 * | 角色 | 面 | 斜面 | 面内高光 | 投影 |
 * |---|---|---|---|---|
 * | [SkeuoRole.Body] | face | **无**（只有一圈 1dp 边） | 极淡 | **无** |
 * | [SkeuoRole.Key] | keyFace | 上亮下弱亮 | 有 | 短投影（由调用方挂） |
 * | [SkeuoRole.Well] | wellFace | **上暗下亮** | 无 | **无**（凹槽不投外影） |
 * | [SkeuoRole.Sleeve] | faceRaised | **无** | 有 | 静置投影（由调用方挂） |
 *
 * 关键判断：**机身面板不投影**。真实桌面上，一张平放的板子不会在自己周围投出影子——
 * 只有离开表面的东西才会。上一版给所有面都挂投影，是「所有东西都浮起来」的元凶。
 *
 * ## 为什么在 `drawWithCache` 里建 Brush
 *
 * 本仓红线：绘制期零 Shader 分配。这里所有笔刷只依赖 (skeuo, size)，尺寸/配色变化才重算，
 * 帧间复用——与液态档的 liquidGlassDecor 完全同一套手法。
 *
 * @param role 材质角色，见 [SkeuoRole]
 * @param pressed 按下态（仅 [SkeuoRole.Key] 有意义）：按下时斜面反转、面压暗
 */
private fun Modifier.skeuomorphFace(
    skeuo: SkeuomorphColors,
    role: SkeuoRole,
    pressed: Boolean = false,
): Modifier = drawWithCache {
    val h = size.height.coerceAtLeast(1f)
    val w = size.width.coerceAtLeast(1f)

    // 面内高光：只有「有厚度的实体」（Key / Sleeve）才画。
    // Body 是平放的机身，面上不该有自上而下的渐变；Well 是凹的，光不会照进底部。
    val drawInner = role == SkeuoRole.Key || role == SkeuoRole.Sleeve
    val innerBrush = if (drawInner) {
        // 按下时内高光收弱：实体被压进机身，迎光面变小
        val topAlpha = if (pressed) skeuo.innerLight.alpha * 0.4f else skeuo.innerLight.alpha
        Brush.verticalGradient(
            0f to skeuo.innerLight.copy(alpha = topAlpha),
            // 上半 55% 衰减到透明：真实反射衰减很快，整高渐变会读作「整块浮起」
            0.55f to Color.Transparent,
            endY = h,
        )
    } else {
        null
    }

    val bevelPx = 1.5.dp.toPx()
    val sideInset = w * 0.06f
    // 斜面颜色按角色取：
    // - Key：凸起（上亮下弱亮）；按下时反转成「被压平」——上沿失去高光
    // - Well：凹陷（上暗下亮），方向与凸起相反
    // - Body / Sleeve：不画斜面（Body 只画一圈细边，Sleeve 是纸张，边缘靠投影）
    val (topColor, bottomColor) = when (role) {
        SkeuoRole.Key -> if (pressed) {
            skeuo.recessTop.copy(alpha = skeuo.recessTop.alpha * 0.5f) to
                skeuo.recessBottom.copy(alpha = skeuo.recessBottom.alpha * 0.5f)
        } else {
            skeuo.bevelTop to skeuo.bevelBottom
        }
        SkeuoRole.Well -> skeuo.recessTop to skeuo.recessBottom
        SkeuoRole.Body, SkeuoRole.Sleeve -> Color.Transparent to Color.Transparent
    }
    val drawBevel = role == SkeuoRole.Key || role == SkeuoRole.Well
    val topBrush = if (drawBevel) {
        Brush.horizontalGradient(
            listOf(Color.Transparent, topColor, Color.Transparent),
            startX = sideInset, endX = w - sideInset,
        )
    } else {
        null
    }
    val bottomBrush = if (drawBevel) {
        Brush.horizontalGradient(
            listOf(Color.Transparent, bottomColor, Color.Transparent),
            startX = sideInset, endX = w - sideInset,
        )
    } else {
        null
    }

    // Body 的「边」：一圈 1dp 低对比线，用来把面板与背景分开，但不制造厚度
    val bodyEdgeBrush = if (role == SkeuoRole.Body) {
        Brush.verticalGradient(
            listOf(skeuo.bevelTop.copy(alpha = skeuo.bevelTop.alpha * 0.5f), Color.Transparent),
            endY = h,
        )
    } else {
        null
    }

    val faceColor = when (role) {
        SkeuoRole.Key -> if (pressed) {
            // 按下时压暗一档：这是「按下去」的第二重信号（第一重是位移）
            lerp(skeuo.keyFace, skeuo.faceRecessed, 0.35f)
        } else {
            skeuo.keyFace
        }
        SkeuoRole.Well -> skeuo.wellFace
        SkeuoRole.Sleeve -> skeuo.faceRaised
        SkeuoRole.Body -> skeuo.face
    }

    onDrawBehind {
        // 1. 面（不透明）——拟物与玻璃的分水岭
        drawRect(color = faceColor)
        // 2. 面内高光（仅 Key / Sleeve）
        innerBrush?.let { drawRect(brush = it) }
        // 3. Body 的顶边：一条极淡的上亮渐变，不是完整描边
        if (bodyEdgeBrush != null) {
            drawRect(brush = bodyEdgeBrush, size = androidx.compose.ui.geometry.Size(w, 1.dp.toPx()))
        }
        // 4. 斜面（仅 Key / Well）
        if (topBrush != null && bottomBrush != null) {
            drawLine(topBrush, Offset(0f, bevelPx / 2f), Offset(w, bevelPx / 2f), bevelPx)
            drawLine(bottomBrush, Offset(0f, h - bevelPx / 2f), Offset(w, h - bevelPx / 2f), bevelPx)
        }
    }
}

/**
 * 拟物主按键的绘制修饰符：**主题色实体键**（本轮新增）。
 *
 * ## 为什么单独抽出来，而不复用 [skeuomorphFace]
 *
 * 材质层的 Key 角色画的是**机身色**的按键（圆钮、开关滑块）——它们是设备的一部分，
 * 与机身同族。而主播放键是**唯一一个被涂成主题色的部件**：它是整屏的视觉中心，
 * 不能是「又一颗灰色小圆钮」。所以它需要一套独立的笔：
 *
 * 1. 面：`faceColor`（主题色）而不是 keyFace；
 * 2. 内高光：顶部一层白 12%，让树脂有厚度；
 * 3. 底边：一层暗色（不是亮色），表达「圆角转进侧面」；
 * 4. 投影：短而紧，按下时归零 + 整体下移 [SkeuoElevation.PressTravel]。
 *
 * 这套笔与材质层的 Key 角色共享同一个物理模型，只是换了一套颜色来源。
 *
 * @param faceColor 按键面色（通常是 `palette.seed`）
 * @param pressed 按下态：面压暗、内高光收弱、投影归零、整体下移
 */
@Composable
fun Modifier.skeuomorphKey(
    skeuo: SkeuomorphColors,
    faceColor: Color,
    pressed: Boolean,
    shape: Shape,
): Modifier {
    // 投影必须挂在 clip 之前（要画到边界之外）
    val shadowPart = shadow(
        elevation = if (pressed) 0.dp else SkeuoElevation.KeyBlur.dp,
        shape = shape,
        clip = false,
        ambientColor = skeuo.shadow,
        spotColor = skeuo.shadow,
    )
    // 按下时面压暗 12%：与位移配合，形成「按进去了」的双重信号
    val face = if (pressed) lerp(faceColor, Color.Black, 0.12f) else faceColor
    return this
        .then(shadowPart)
        .clip(shape)
        .drawWithCache {
            val h = size.height.coerceAtLeast(1f)
            // 内高光：顶部白 12% → 55% 处透明
            val inner = Brush.verticalGradient(
                0f to Color.White.copy(alpha = if (pressed) 0.05f else 0.12f),
                0.55f to Color.Transparent,
                endY = h,
            )
            // 底边：暗色而非亮色——圆角转向侧面时背光
            val bottomBrush = Brush.horizontalGradient(
                listOf(Color.Transparent, Color.Black.copy(alpha = 0.16f), Color.Transparent),
                startX = size.width * 0.08f,
                endX = size.width * 0.92f,
            )
            val bevelPx = 1.5.dp.toPx()
            onDrawBehind {
                drawRect(color = face)
                drawRect(brush = inner)
                drawLine(
                    bottomBrush,
                    Offset(0f, h - bevelPx / 2f),
                    Offset(size.width, h - bevelPx / 2f),
                    bevelPx,
                )
            }
        }
        .graphicsLayer {
            translationY = if (pressed) SkeuoElevation.PressTravel.dp.toPx() else 0f
        }
}

/**
 * 拟物投影：**只有离开表面的东西才投影**（本轮重设计）。
 *
 * 上一版对所有凸起面都挂 [SkeuoElevation.FarBlur] 的投影，包括设置组卡与列表卡，
 * 于是整屏每块东西都「浮」在背景上——那是廉价拟物最典型的症状。
 *
 * 现在按角色分档：
 * - [SkeuoRole.Key]：短而紧（[SkeuoElevation.KeyBlur]），按下时归零；
 * - [SkeuoRole.Sleeve]：静置的双层投影（近锐远柔）；
 * - [SkeuoRole.Body] / [SkeuoRole.Well]：**完全不投影**。
 *
 * **必须在 clip 之前挂**：投影要画到节点边界以外，而 clip 会把越界部分裁掉。
 */
private fun Modifier.skeuomorphShadow(
    skeuo: SkeuomorphColors,
    shape: Shape,
    role: SkeuoRole,
    pressed: Boolean = false,
): Modifier = when (role) {
    // 按键：短投影，按下时收掉（东西被压进机身，不再高于表面）
    SkeuoRole.Key -> shadow(
        elevation = if (pressed) 0.dp else SkeuoElevation.KeyBlur.dp,
        shape = shape,
        clip = false,
        ambientColor = skeuo.shadow,
        spotColor = skeuo.shadow,
    )
    // 封套：静置的双层感——Compose 的 shadow 用同一 elevation 画 ambient/spot，
    // 这里用远投影的半径，近投影的锐度由材质面的高光承担
    SkeuoRole.Sleeve -> shadow(
        elevation = SkeuoElevation.FarBlur.dp,
        shape = shape,
        clip = false,
        ambientColor = skeuo.shadow.copy(alpha = skeuo.shadow.alpha * 0.7f),
        spotColor = skeuo.shadow,
    )
    // 机身与凹槽：不投影
    SkeuoRole.Body, SkeuoRole.Well -> this
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
    // 44dp：iOS HIG 最小触达。旧 40dp 视觉够但手指落点偏小，
    // 头部/控制台等纯图标按钮密集处误触率偏高。
    size: Dp = 44.dp,
    content: @Composable BoxScope.() -> Unit,
) {
    val glass = LocalGlassColors.current
    val interactionSource = remember { MutableInteractionSource() }
    // 拟物档：圆钮是**凸起的小实体**（注塑按键），不是「lo 填充的圆形色斑」。
    // 玻璃档靠描边定义边界；拟物档靠斜面 + 投影定义「这是个能按的东西」——
    // 两者目的相同（边界 + 可点性），手段必须各自成立，所以这里分流而不是叠加。
    if (LocalVisualStyle.current == com.local.music.core.ui.state.VisualStyle.Skeuomorph) {
        val skeuo = LocalSkeuomorphColors.current
        // 圆钮是**按键**角色（不是封套）：它要能被按下去，所以有短投影 + 按下位移。
        // 上一版这里用 raised=true 的通用路径，与卡片同材质；现在用 Key 角色区分开。
        val pressed by interactionSource.collectIsPressedAsState()
        Box(
            modifier
                .size(size)
                .skeuomorphShadow(skeuo, CircleShape, SkeuoRole.Key, pressed = pressed)
                .clip(CircleShape)
                .skeuomorphFace(skeuo, SkeuoRole.Key, pressed = pressed)
                .graphicsLayer {
                    translationY = if (pressed) SkeuoElevation.PressTravel.dp.toPx() else 0f
                }
                .clickable(interactionSource = interactionSource, indication = null, onClick = onClick),
            contentAlignment = Alignment.Center,
            content = content,
        )
        return
    }
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
    // A4 视觉审计：开启态轨道是 palette.seed 实底，滑块必须读 onSeedColor()（其 KDoc
    // 明确把「开关滑块」列为消费点，web 参考 Glass.jsx 用的是 var(--on-seed)）。
    // 旧实现恒 Color.White：琥珀 #E0A800 轨道上的白滑块只有 2.15:1，「开/关」这个
    // 二态控制的状态指示反而变弱。随 fraction 插值而不是 if(checked) 硬切——
    // 轨道本身是渐变的（textPrimary 18% → seed），滑块若硬切会在中途与轨道撞色。
    val onSeed = onSeedColor()
    val interactionSource = remember { MutableInteractionSource() }
    val fraction by animateFloatAsState(
        targetValue = if (checked) 1f else 0f,
        animationSpec = GlassMotion.tweenHover(),
        label = "toggle",
    )
    val track = androidx.compose.foundation.shape.RoundedCornerShape(R.Full)
    // 拟物档：开关是**凹槽 + 凸起滑块**——这是拟物语言最经典也最有说服力的一对：
    // 轨道必须读作「凹进去」（上沿暗、下沿亮），滑块必须读作「凸起来」（投影 + 斜面）。
    // 玻璃档的轨道是半透明色块 + 白滑块，两个世界。
    val skeuomorphToggle = LocalVisualStyle.current == com.local.music.core.ui.state.VisualStyle.Skeuomorph
    Box(
        modifier
            .size(46.dp, 28.dp)
            .then(
                if (skeuomorphToggle) {
                    val skeuo = LocalSkeuomorphColors.current
                    Modifier
                        .clip(track)
                        // 凹槽底色：开态仍然吃 seed 色（状态信号不能丢），
                        // 但把它压成「凹槽里的色光」而不是「轨道本色」
                        .background(
                            androidx.compose.ui.graphics.lerp(
                                skeuo.wellFace,
                                palette.seed.copy(alpha = 0.85f),
                                fraction,
                            ),
                        )
                        .skeuomorphFace(skeuo, SkeuoRole.Well)
                } else {
                    Modifier
                        .clip(track)
                        .background(
                            androidx.compose.ui.graphics.lerp(
                                LocalGlassColors.current.textPrimary.copy(alpha = 0.18f), palette.seed, fraction,
                            ),
                        )
                },
            )
            .clickable(interactionSource = interactionSource, indication = null) { onToggle(!checked) }
            .semantics {
                this.role = Role.Switch
                this.toggleableState = if (checked) ToggleableState.On else ToggleableState.Off
            },
    ) {
        val thumbSize = 22.dp
        val travel = 46.dp - thumbSize - 6.dp
        Box(
            Modifier
                .graphicsLayer { translationX = (3.dp + travel * fraction).toPx() }
                .align(Alignment.CenterStart)
                .size(thumbSize)
                .clip(CircleShape)
                .then(
                    if (skeuomorphToggle) {
                        // 拟物滑块：凸起实体 + 投影（投影是「滑块高于轨道」的唯一信号）
                        val skeuo = LocalSkeuomorphColors.current
                        Modifier
                            .skeuomorphShadow(skeuo, CircleShape, SkeuoRole.Key)
                            .skeuomorphFace(skeuo, SkeuoRole.Key)
                    } else {
                        Modifier.background(androidx.compose.ui.graphics.lerp(Color.White, onSeed, fraction))
                    },
                ),
        )
    }
}
