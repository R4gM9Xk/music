package com.local.music.core.ui.components

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.local.music.core.ui.theme.GlassIcons
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
    GlassSurface(
        modifier = modifier.fillMaxWidth().height(44.dp),
        level = GlassLevel.Md,
        shape = RoundedCornerShape(R.Full),
    ) {
        Row(
            Modifier
                .fillMaxSize()
                .padding(horizontal = S.Gap16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(S.Gap8.dp),
        ) {
            if (leading != null) {
                Icon(
                    leading, null, Modifier.size(18.dp),
                    tint = if (value.isEmpty()) LocalGlassColors.current.textSecondary else LocalGlassColors.current.textPrimary,
                )
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
                        .focusRequester(focusRequester),
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
        }
    }
    LaunchedEffect(autoFocus) {
        if (autoFocus) focusRequester.requestFocus()
    }
}
