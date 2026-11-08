package com.local.music.core.ui.components

import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
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

    // 展开时箭头翻转：这是「可展开」这件事最轻的动效表达，不新增任何布局节点。
    // 时长走 tweenHover（250ms，tab/开关等微交互同档），自动被动效强度乘过。
    val arrowFlip by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = GlassMotion.tweenHover(),
        label = "dropdownArrow",
    )

    Box(modifier) {
        Row(
            Modifier
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

        if (expanded) {
            val offsetY = with(androidx.compose.ui.platform.LocalDensity.current) { 40.dp.roundToPx() }
            Popup(
                // 对齐到胶囊的右下角向下偏移 40dp（胶囊高约 28dp + 12dp 间隙）：
                // 贴着触发器长出来，而不是屏幕居中弹。Popup 的 offset 是 IntOffset（px），
                // 所以这里用 LocalDensity 显式换算——直接传 DpOffset 编译不过。
                alignment = Alignment.TopEnd,
                offset = IntOffset(0, offsetY),
                onDismissRequest = { expanded = false },
                properties = PopupProperties(focusable = true),
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

/** 下拉项：显示文本 + 可选副标题 + 载荷 */
data class DropdownOption<T>(
    val label: String,
    val subtitle: String? = null,
    val payload: T,
)

/**
 * 下拉列表本体：玻璃容器 + 逐项（选中项带对勾）。
 *
 * 宽度用 `widthIn(min = 168.dp)` 而不是撑满：三档的文本长度相近
 * （「通透/标准/浓郁」），固定下限能让三行等宽、对齐，不会因副标题长短不一把
 * 容器拉成参差的形状。
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
        modifier = Modifier.widthIn(min = 168.dp).wrapContentWidth(),
        level = GlassLevel.Hi,
        shape = RoundedCornerShape(R.Card14.dp),
        // 下拉列表浮在设置行**之上**，是真正的悬浮层 → Hi 档（描边 + 顶线全保留），
        // 与面板同一逻辑。不加 scrim：它下面就是设置页本身，不需要压透字。
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
