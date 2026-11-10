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
import com.local.music.core.ui.theme.R

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
    // Liquid 的「填充」取 hi：它的差异化**全部在装饰层**（方向性斜面 + specular），
    // 不在填充浓度。若这里另取一个更亮的值，会与「Liquid 是装饰语言而非亮度档」
    // 这条定义冲突，也让「玻璃强度」设置的缩放失去可比性。
    GlassLevel.Liquid -> hi
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
    val strokeWidth = 1.dp.toPx()
    onDrawBehind {
        // ── 1. specular（最先画，在边缘之下）──
        // 亮斑中心在右上偏内；motion 让它在横向小幅漂移（±6% 宽）并轻微呼吸。
        val t = motion().coerceIn(0f, 1f)
        val cx = w * (0.74f + 0.06f * (t - 0.5f) * 2f)
        val cy = h * 0.16f
        val rx = w * (0.42f + 0.05f * t)
        val ry = h * 0.30f
        withTransform({
            translate(cx, cy)
            scale(rx, ry, pivot = Offset.Zero)
        }) {
            drawCircle(brush = specularBrush, radius = 1f, center = Offset.Zero)
        }

        // ── 2. 方向性斜面（顶亮 / 侧中 / 底暗）──
        drawLine(topBrush, Offset(0f, strokeWidth / 2f), Offset(w, strokeWidth / 2f), strokeWidth)
        drawLine(
            bottomBrush,
            Offset(0f, h - strokeWidth / 2f), Offset(w, h - strokeWidth / 2f), strokeWidth,
        )
        drawLine(leftBrush, Offset(strokeWidth / 2f, 0f), Offset(strokeWidth / 2f, h), strokeWidth)
        drawLine(
            rightBrush,
            Offset(w - strokeWidth / 2f, 0f), Offset(w - strokeWidth / 2f, h), strokeWidth,
        )
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
    //
    // - Liquid（播放页封面周围 / 重要交互浮层）：**第四档，本轮新增**。它不用「均匀
    //   描边 + 顶线」这套，而换成方向性斜面 + 内部 specular（见 [liquidGlassDecor]）。
    //   注意这里**不额外加 border**：方向性四笔已经画满整圈边线，再叠一层均匀 border
    //   会把「光从上面来」的方向性重新抹平成四边等亮。
    m = when (level) {
        GlassLevel.Hi -> m
            .border(1.dp, glass.strokeSoft, shape)
            .glassTopLine(glass.topLine)
        GlassLevel.Md -> m.border(1.dp, glass.strokeSoft, shape)
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
