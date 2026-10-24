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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.local.music.core.ui.theme.LocalGlassColors
import com.local.music.core.ui.theme.auroraAccentColor

/**
 * 玻璃输入框：GlassSurface 外壳 + BasicTextField（白字 / 强调色光标）。
 * 搜索页真输入、新建歌单命名共用；onSearch = 键盘搜索键回调。
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
) {
    val focusRequester = remember { FocusRequester() }
    GlassSurface(
        modifier = modifier.fillMaxWidth().height(44.dp),
        level = GlassLevel.Md,
        shape = RoundedCornerShape(22.dp),
    ) {
        Row(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (leading != null) {
                Icon(
                    leading, null, Modifier.size(18.dp),
                    tint = if (value.isEmpty()) LocalGlassColors.current.textSecondary else LocalGlassColors.current.textPrimary,
                )
            }
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty() && hint.isNotEmpty()) {
                    Text(hint, color = LocalGlassColors.current.textSecondary, fontSize = 14.sp, maxLines = 1)
                }
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    singleLine = true,
                    textStyle = TextStyle(color = LocalGlassColors.current.textPrimary, fontSize = 14.sp),
                    // 光标强调色用 auroraAccentColor()（浅色回退 seed）：seed3 过浅在浅色下近不可见
                    cursorBrush = SolidColor(auroraAccentColor()),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { onSearch?.invoke() }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                )
            }
        }
    }
    LaunchedEffect(autoFocus) {
        if (autoFocus) focusRequester.requestFocus()
    }
}
