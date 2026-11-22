package com.local.music.core.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.local.music.core.ui.theme.GlassIcons
import com.local.music.core.ui.theme.GlassMotion
import com.local.music.core.ui.theme.GlassText
import com.local.music.core.ui.theme.LocalGlassColors
import com.local.music.core.ui.theme.R
import com.local.music.core.ui.theme.S
import com.local.music.core.ui.theme.auroraAccentColor

/**
 * 设置行尾的**行内下拉选择器**（真机反馈新增，2026-11-08）。
 *
 * ## 它替掉的是什么，为什么
 *
 * 旧实现：玻璃强度/动效强度这两行点开一个 **ModalSheet 面板**，面板里列三档、当前档
 * 高亮。用户反馈要改成系统设置里「自动息屏」那种样子——**行尾直接显示当前值，
 * 点它原地展开一个短列表，选中项打勾**。
 *
 * 这不是纯审美偏好，两种形态的成本差是结构性的：
 * - 面板 = 一次**模态跳转**：整屏变暗、底部滑出、选完再滑回。为「三选一」付两次
 *   转场动画 + 两次遮挡上下文，用户在面板打开期间看不到自己正在改哪一行。
 * - 行内下拉 = **零转场**：列表就浮在那行下面，改完立刻能看到当前值的文本变了。
 *   三档这种量级的选择，模态的仪式感是纯开销。
 *
 * 这也与系统设置（用户参照的对象）的语言一致：行尾「30 分钟 ⌄」→ 弹出六项 → 打勾。
 *
 * ## 为什么不用 Material 的 DropdownMenu
 *
 * 本 App 的弹层语言是**液态玻璃**（GlassSurface + haze + 描边 + 顶线），而
 * Material `DropdownMenu` 自带 Surface 填充与阴影，材质对不上——会出现「一块
 * 不属于这个 App 的白板浮在玻璃卡上」。这里用 Popup + 自绘玻璃容器，与
 * [ModalSheet] 同一套材质来源。
 *
 * ## 交互与可访问性
 *
 * - 触发器是一枚**胶囊形按钮**（`.value + ⌄`），不是整行——整行点击留给「进入详情」
 *   这类真正需要跳转的行。行内选择器与整行点击**职责分离**，避免误触。
 * - `role = Role.Button` + `stateDescription` = 当前档位，读屏能答「现在是什么」。
 * - 列表项 `role = Role.RadioButton` + `selected` 语义：这是**单选**，不是普通按钮。
 * - 选中项右侧带 [GlassIcons.Check]——视觉确认信号，不能只靠文字高亮
 *   （高亮在浅色/低对比环境下不总是可辨）。
 * - 点外部任何位置关闭（`PopupProperties.focusable` + dismissRequest），
 *   不引入「必须点确定」的二次确认——单选立即生效，没有可失败的提交。
 *
 * @param value 当前档位显示文本
 * @param options 全部档位（显示文本 + 可选副标题），顺序即视觉顺序
 * @param onSelect 选中回调；调用即生效
 * @param label 这一行的语义名（「玻璃强度」），用于组合 contentDescription
 */
@Composable
fun <T> SettingDropdown(
    value: String,
    options: List<DropdownOption<T>>,
    selected: T,
    onSelect: (T) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
) {
    val glass = LocalGlassColors.current
    val accent = auroraAccentColor()
    var expanded by remember { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }
    /**
     * 触发器实测高度（px）：下拉浮层的纵向偏移**必须**由它推导，而不是写死一个 dp。
     *
     * ## 为什么不能沿用固定的 40dp（P2 修复）
     *
     * 旧实现写 `offsetY = 40.dp`，注释自述是「胶囊高约 28dp + 12dp 间隙」。但胶囊的
     * 真实高度是**由内容撑出来的**：`GlassText.Body`（13sp/18 行高）+ 上下各 6dp padding
     * → 正常字号约 30dp；而系统字体放大到 1.3×/2.0× 时，行高随 sp 增长，
     * 胶囊会变成 34dp、40dp 甚至更高——浮层顶边就被压在触发器**身上**（遮住当前值），
     * 或反过来离得太远。这正是「大字体下浮层压住触发器」那条反馈的成因。
     *
     * 取值用 `onGloballyPositioned` 的 `size.height`：它是**布局后**的真实高度，
     * 天然跟着 fontScale / density / 主题排版走，不需要我们再复制一份推导式
     * （复制推导 = 双源漂移，本仓已登记多起同型缺陷）。
     *
     * 初值给 0：首帧还没测量时浮层不可能已经展开（expanded 初值 false），
     * 所以不存在「用 0 偏移闪一下」的窗口。
     */
    var triggerHeightPx by remember { mutableStateOf(0) }

    // 展开时箭头翻转：这是「可展开」这件事最轻的动效表达，不新增任何布局节点。
    // 时长走 tweenHover（250ms，tab/开关等微交互同档），自动被动效强度乘过。
    val arrowFlip by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = GlassMotion.tweenHover(),
        label = "dropdownArrow",
    )
    // 列表的进/出场也走同一条 tweenHover（A4 审计 Minor-9）：旧实现把 Popup 包在
    // `if (expanded)` 里，箭头在 250ms 里平滑翻转、列表却瞬现瞬隐——同一个控件两种语言。
    //
    // 为什么不能直接 `if (expanded) { Popup { AnimatedVisibility(...) } }`：Popup 是
    // 独立 window，expanded 一翻 false 窗口立刻销毁，退场动画永远没有机会执行。
    // 因此用 MutableTransitionState 同时持有目标态与动画当前态：
    // - target=true / current=false：进场中 → Popup 必须继续挂载；
    // - target=false / current=true：退场中 → Popup 必须继续挂载；
    // - 两者都 false：彻底收起 → 才移除 Popup（此时窗口不留任何空壳）。
    val listTransition = remember { MutableTransitionState(false) }
    listTransition.targetState = expanded

    Box(modifier) {
        Row(
            Modifier
                // 命中区下限 44dp（与 GlassIconButton / MiniBarButton 同档）：
                // 胶囊视觉高度由 Body 行高 + 6dp padding 撑出（约 30dp），而它是
                // 「玻璃强度 / 动效强度」这类行**唯一**的可点区域——整行没有 onClick，
                // 30dp 的命中区在拇指操作下偏小。sizeIn 只抬下限，视觉尺寸不变。
                .sizeIn(minHeight = 44.dp)
                .clip(RoundedCornerShape(R.Full))
                // lo 填充 + strokeSoft 描边：与 GlassIconButton 同一套「可点控件的边界定义」，
                // 但形状取胶囊（它承载文字，不承载图标）
                .background(glass.lo)
                .border(1.dp, glass.strokeSoft, RoundedCornerShape(R.Full))
                .clickable(interactionSource = interactionSource, indication = null) {
                    expanded = !expanded
                }
                .semantics {
                    this.role = Role.Button
                    this.contentDescription = label
                    this.stateDescription = value
                }
                // 实测触发器高度：浮层偏移由它推导（见 triggerHeightPx 的 KDoc）。
                // 不用 onSizeChanged 是因为 Popup 的 offset 是 IntOffset(px)，
                // 这里本来就要在 px 空间里算，绕一次 dp 再转回来只增加舍入误差。
                .onGloballyPositioned { triggerHeightPx = it.size.height }
                .padding(horizontal = S.Gap12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(S.Gap4.dp),
        ) {
            androidx.compose.material3.Text(
                value,
                color = glass.textSecondary,
                style = GlassText.Body,
                maxLines = 1,
            )
            androidx.compose.material3.Icon(
                GlassIcons.ChevronUpDown,
                // 语义由外层 Box 承担（同 ConsoleIcon 纪律），此处 null 避免重复播报
                contentDescription = null,
                Modifier
                    .size(14.dp)
                    .graphicsLayer { rotationZ = arrowFlip },
                tint = glass.textTertiary,
            )
        }

        if (listTransition.currentState || listTransition.targetState) {
            // 偏移 = 触发器实测高度 + 12dp 间隙（与触发器下缘贴齐再让 12dp 呼吸）。
            // 间隙取 S.Gap12：它与胶囊内 padding 同档，是「这块控件自己留的缝」的量级。
            // 见 triggerHeightPx 的 KDoc —— 这里绝不能写死总高度。
            val gapPx = with(androidx.compose.ui.platform.LocalDensity.current) { S.Gap12.dp.roundToPx() }
            val offsetY = triggerHeightPx + gapPx
            Popup(
                // 对齐到胶囊的右下角向下偏移 40dp（胶囊高约 28dp + 12dp 间隙）：
                // 贴着触发器长出来，而不是屏幕居中弹。Popup 的 offset 是 IntOffset（px），
                // 所以这里用 LocalDensity 显式换算——直接传 DpOffset 编译不过。
                alignment = Alignment.TopEnd,
                offset = IntOffset(0, offsetY),
                onDismissRequest = { expanded = false },
                properties = PopupProperties(focusable = true),
            ) {
                AnimatedVisibility(
                    visibleState = listTransition,
                    // 从触发器下缘「长出来」：顶边锚定（列表中轴不跳），高度展开 + 淡入。
                    // 不用 scaleIn：Popup 的 window 尺寸随内容测，缩放只改绘制不改窗口，
                    // 展开高度才是「贴着行尾下拉」应有的运动。
                    enter = fadeIn(GlassMotion.tweenHover()) +
                        expandVertically(
                            animationSpec = GlassMotion.tweenHover(),
                            expandFrom = Alignment.Top,
                        ),
                    exit = fadeOut(GlassMotion.tweenHover()) +
                        shrinkVertically(
                            animationSpec = GlassMotion.tweenHover(),
                            shrinkTowards = Alignment.Top,
                        ),
                ) {
                    DropdownList(
                        options = options,
                        selected = selected,
                        accent = accent,
                        onPick = {
                            onSelect(it)
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}

/** 下拉项：显示文本 + 可选副标题 + 载荷 */
data class DropdownOption<T>(
    val label: String,
    val subtitle: String? = null,
    val payload: T,
)

/**
 * 下拉列表本体：玻璃容器 + 逐项（选中项带对勾）。
 *
 * ## 宽度：必须同时给上下界，只给 min 会撑满全屏（真机/模拟器实测踩到）
 *
 * 初版写的是 `widthIn(min = 168.dp).wrapContentWidth()`，意图是「按内容自适应、
 * 至少 168dp」。但实测下拉弹出后**几乎占满屏宽**，原因有两层：
 * 1. `widthIn` 只约束了**下界**，上界仍是父约束上限；
 * 2. 内部 `Row` 用了 `fillMaxWidth()` —— 在 Popup 的宽松约束下它会一路填到父上限，
 *    于是「自适应」被内部子项反推成了「撑满」。`wrapContentWidth` 挡不住：
 *    它只调整自身在**已测量尺寸**内的对齐，不改变子项要求的宽度。
 *
 * 修法：给 `widthIn` 加上 `max`，并在内部改用 `fillMaxWidth()` 之外的写法——
 * 这里保留 `fillMaxWidth()` 但它现在被外层的 max 约束住了（宽度上限唯一由外层决定，
 * 内部只负责「填满外层给的宽度」，职责清晰）。
 *
 * 尺寸取值的理由：
 * - **232dp 上限**：「填充与描边减淡，更通透」这类副标题最长，按 GlassText.Micro（11sp）
 *   单行约需 `168dp` 正文 + 16dp×2 内边距 + 18dp 对勾位 ≈ 218dp，取 232dp 留一点余量。
 *   相比屏宽 411dp 只占 56%，读作「挂在行尾的一块小板」而不是「整屏抽屉」。
 * - **168dp 下限**：短文案（「通透/标准/浓郁」）下三行等宽、对勾列对齐，不会缩成窄条。
 * - 副标题允许折行（maxLines = 2）：宁可让最长的两条折成两行，也不用被最长的副标题
 *   反向撑宽整个列表——**宽度应由视觉意图决定，而不是由最长的那条文案决定**。
 */
@Composable
private fun <T> DropdownList(
    options: List<DropdownOption<T>>,
    selected: T,
    accent: androidx.compose.ui.graphics.Color,
    onPick: (T) -> Unit,
) {
    val glass = LocalGlassColors.current
    GlassSurface(
        // 上下界都要给：只写 min 时上界=父上限，会被内部 fillMaxWidth 撑满全屏
        modifier = Modifier.widthIn(min = 168.dp, max = 232.dp),
        level = GlassLevel.Hi,
        shape = RoundedCornerShape(R.Card14.dp),
        // 下拉列表浮在设置行**之上**，是真正的悬浮层 → Hi 档（描边 + 顶线全保留），
        // 与面板同一逻辑。
        //
        // ## 从「chromeScrim 重遮罩」改为「靠模糊挡字」（2026-11-21 用户反馈）
        //
        // 旧实现 `scrim = glass.chromeScrim`（深色 #1A1A24 @0.72 / 浅色 #F2F2F6 @0.66）。
        // 那是本仓最早期的透字兜底方案，代价是浮层读作一块**实色板**——用户明确说
        // 「我要的是模糊，不是遮挡」，并要求「下拉浮层也一样改」。
        //
        // 现在：blurVeil = Popup（24dp 均匀模糊、tint 只定调），遮挡职责交给模糊。
        // **不传 scrim**：GlassSurface 在 blurVeil 生效时会忽略它，但显式不传更清楚。
        //
        // 为什么 Popup 不用 progressive：面积只有 ~200×150dp，竖向渐变在这个尺度上
        // 看不出方向感，反而像「上清下糊」的瑕疵（见 BlurVeilKind.Popup 的注释）。
        blurVeil = BlurVeilKind.Popup,
    ) {
        Column(Modifier.padding(vertical = 6.dp)) {
            options.forEach { option ->
                val isSelected = option.payload == selected
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { onPick(option.payload) }
                        .semantics {
                            this.role = Role.RadioButton
                            this.selected = isSelected
                        }
                        .padding(horizontal = S.Gap16.dp, vertical = S.Gap8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        androidx.compose.material3.Text(
                            option.label,
                            color = if (isSelected) accent else glass.textPrimary,
                            style = GlassText.Body,
                        )
                        if (option.subtitle != null) {
                            Spacer(Modifier.height(2.dp))
                            androidx.compose.material3.Text(
                                option.subtitle,
                                color = glass.textTertiary,
                                style = GlassText.Micro,
                                maxLines = 2,
                            )
                        }
                    }
                    // 对勾只出现在选中项：这是单选语义的视觉确认。
                    // 未选中项保留等宽占位（不是 Spacer 而是 if 分支里的 Box），
                    // 否则选中项会因为多出 18dp 图标而把文字挤窄，三行文本左缘错位。
                    Box(Modifier.width(18.dp), contentAlignment = Alignment.CenterEnd) {
                        if (isSelected) {
                            androidx.compose.material3.Icon(
                                GlassIcons.Check,
                                contentDescription = null,
                                Modifier.size(16.dp),
                                tint = accent,
                            )
                        }
                    }
                }
            }
        }
    }
}
