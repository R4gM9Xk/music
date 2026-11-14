package com.local.music.core.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.local.music.core.ui.theme.GlassIcons
import com.local.music.core.ui.theme.GlassMotion
import com.local.music.core.ui.theme.GlassText
import com.local.music.core.ui.theme.LocalGlassColors
import com.local.music.core.ui.theme.R
import com.local.music.core.ui.theme.S
import com.local.music.core.ui.theme.auroraAccentColor

/**
 * 玻璃输入框：GlassSurface 外壳 + BasicTextField（白字 / 强调色光标）。
 * 搜索页真输入、新建歌单命名共用；onSearch = 键盘搜索键回调。
 *
 * - imeAction（P3-76）：键盘动作键语义按调用方定（搜索=Search、命名=Done），
 *   默认 Search 保持搜索页既有行为；动作键回调仍统一走 [onSearch]（命名场景它是"确认"）。
 * - hint 走 decorationBox（P3-77）：旧实现 hint Text 与输入框并排叠在同一个 Box 里，
 *   空值+聚焦时光标（width≈2dp）压在 hint 首字上；decorationBox 内 hint 与正文互斥排版，
 *   光标画在 innerTextField 自己的层，不再穿透占位文本。
 *
 * ## 本轮（搜索框打磨）新增两件事
 *
 * ### 1. 清除按钮
 * 改动前清空输入的唯一办法是**逐字退格**，或者点「取消」退出搜索（那会连带清空并离开页面）。
 * 搜了三个字发现第一个字打错，用户要按三次退格——这是搜索框最基础的一处缺失。
 *
 * 现在非空时右侧出现圆底叉（[GlassIcons.Close]）：
 * - 只在有内容时出现（空框右侧挂一个点了没反应的叉是噪音）；
 * - 点击只清文本、**不收键盘**——用户通常是要重打，收键盘等于逼他再点一次输入框；
 * - 逻辑上等价于「全选 + 删除」，但少两次操作。
 *
 * ### 2. 焦点态描边
 * 改动前输入框**没有任何聚焦反馈**：点进去只有光标在闪，玻璃外壳的描边与未聚焦时
 * 完全一样。在玻璃材质上这尤其糟——外壳本身就是半透明的，没有一条明确的「当前
 * 输入位置」边界。
 *
 * 现在聚焦时描边换成强调色（[auroraAccentColor]）。为什么不做发光/加粗：
 * 玻璃语言里「发光」已被波形与歌词辉光占用，输入框发光会读成「有消息」；
 * 换色是最克制且明确的表达。颜色走 `animateColorAsState`，键盘弹起时不突兀。
 */
@Composable
fun GlassTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    hint: String = "",
    leading: ImageVector? = GlassIcons.Search,
    autoFocus: Boolean = false,
    onSearch: (() -> Unit)? = null,
    imeAction: ImeAction = ImeAction.Search,
) {
    val focusRequester = remember { FocusRequester() }
    val accent = auroraAccentColor()
    val glass = LocalGlassColors.current
    // 焦点态：BasicTextField 的 onFocusChanged 是唯一可靠来源（自己记点击态会在
    // 「点输入框外收起键盘」「返回键收键盘」等路径上失同步）
    var focused by remember { mutableStateOf(false) }
    val strokeColor by androidx.compose.animation.animateColorAsState(
        targetValue = if (focused) accent else glass.stroke,
        animationSpec = com.local.music.core.ui.theme.GlassMotion.tweenHover(),
        label = "fieldStroke",
    )
    GlassSurface(
        modifier = modifier.fillMaxWidth().height(44.dp),
        level = GlassLevel.Md,
        shape = RoundedCornerShape(R.Full),
        // 描边随焦点变色：1dp 与 GlassSurface 默认一致，不改变几何
        strokeColor = strokeColor,
    ) {
        Row(
            Modifier
                .fillMaxSize()
                .padding(horizontal = S.Gap16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (leading != null) {
                Icon(
                    leading, null, Modifier.size(18.dp),
                    tint = if (value.isEmpty()) LocalGlassColors.current.textSecondary else LocalGlassColors.current.textPrimary,
                )
                // 间距改成显式 Spacer（Row 不再用 spacedBy）：清除按钮的 8dp 间距必须
                // 参与宽度动画，否则 Row 的 spacedBy 会在按钮宽度为 0 的退出帧仍留出
                // 8dp 缝，等节点移除时再跳掉这 8dp——等于把「瞬跳 18dp」换成「瞬跳 8dp」。
                Spacer(Modifier.width(S.Gap8.dp))
            }
            Box(Modifier.weight(1f)) {
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    singleLine = true,
                    textStyle = GlassText.Row.copy(color = LocalGlassColors.current.textPrimary),
                    // 光标强调色用 auroraAccentColor()（浅色回退 seed）：seed3 过浅在浅色下近不可见
                    cursorBrush = SolidColor(auroraAccentColor()),
                    keyboardOptions = KeyboardOptions(imeAction = imeAction), // P3-76：不再硬编码 Search
                    keyboardActions = KeyboardActions(onSearch = { onSearch?.invoke() }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                        // 焦点态来源：onFocusChanged 比「自己记点击」可靠——
                        // 点输入框外收起键盘、返回键收键盘、系统弹窗抢焦点等路径
                        // 都不会经过输入框自己的 clickable，只有焦点回调能覆盖全。
                        .onFocusChanged { focused = it.isFocused },
                    // P3-77：hint 进 decorationBox 与正文互斥排版——空值+聚焦时光标不再压在
                    // hint 首字上（旧实现两者叠在同一个 Box，光标恒画在文本层之上）
                    decorationBox = { innerTextField ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            // A3-05：hint 随输入淡出/清空淡入（tweenHover）——旧实现 if 硬切，
                            // Material3 占位符自带 fade 而此处没有
                            val hintAlpha by androidx.compose.animation.core.animateFloatAsState(
                                targetValue = if (value.isEmpty()) 1f else 0f,
                                animationSpec = com.local.music.core.ui.theme.GlassMotion.tweenHover(),
                                label = "hintAlpha",
                            )
                            if (hintAlpha > 0f && hint.isNotEmpty()) {
                                Text(
                                    hint,
                                    color = LocalGlassColors.current.textSecondary,
                                    style = GlassText.Row,
                                    maxLines = 1,
                                    modifier = Modifier.graphicsLayer { alpha = hintAlpha },
                                )
                            }
                            innerTextField()
                        }
                    },
                )
            }
            // ── 清除按钮（本轮新增，A4 审计 Minor-8 修正）──
            //
            // 只在有内容时出现：空框右侧挂一个点了没反应的叉是纯噪音。
            // 出现/消失走 fade + **水平展开/收窄**而不是 if 硬切：搜索页每敲一个字都重组，
            // 旧实现只动画了 alpha/scale，18dp 图标节点仍随 if 瞬现瞬隐，输入区宽度在
            // 首字符/清空瞬间跳 18dp —— 注释承诺的「收窄平滑」并没有兑现。
            // AnimatedVisibility 让宽度真正参与动画；8dp 间距被包进动画内容里（配合上方
            // 显式 Spacer），所以 26dp 是**整体**展开/收窄，中间与结束都没有残余跳变。
            //
            // 为什么点击只清文本、**不收键盘**：用户按清除的意图几乎总是「重打」，
            // 收键盘等于逼他再点一次输入框。键盘收起只在「取消」与返回键发生。
            AnimatedVisibility(
                visible = value.isNotEmpty(),
                enter = fadeIn(GlassMotion.tweenHover()) +
                    expandHorizontally(
                        animationSpec = GlassMotion.tweenHover(),
                        // 内容右对齐：图标右缘钉在输入框右内缘，空间从左侧（文字那一侧）让出，
                        // 而不是让图标一边淡入一边横向生长
                        expandFrom = Alignment.End,
                    ),
                exit = fadeOut(GlassMotion.tweenHover()) +
                    shrinkHorizontally(
                        animationSpec = GlassMotion.tweenHover(),
                        shrinkTowards = Alignment.End,
                    ),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.width(S.Gap8.dp))
                    val clearInteraction = remember { MutableInteractionSource() }
                    Icon(
                        GlassIcons.Close,
                        // 读屏：可见图形没有文字，必须显式给描述。
                        // 用「清空搜索内容」而不是「清除」——脱离上下文时后者依然歧义。
                        contentDescription = "清空输入内容",
                        Modifier
                            .size(18.dp)
                            .clip(CircleShape)
                            .clickable(interactionSource = clearInteraction, indication = null) {
                                onValueChange("")
                            }
                            .semantics { this.role = Role.Button },
                        tint = LocalGlassColors.current.textSecondary,
                    )
                }
            }
        }
    }
    LaunchedEffect(autoFocus) {
        if (autoFocus) focusRequester.requestFocus()
    }
}
