package com.local.music.core.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.local.music.core.ui.theme.LocalGlassColors
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect

/** 屏幕级背景采样状态：内容区 hazeSource 采样，面板 hazeEffect 消费（兄弟关系，禁止嵌套） */
val LocalHazeState = compositionLocalOf<HazeState?> { null }

/**
 * 玻璃面板挂接背景真模糊（haze 1.6）：读取 [LocalHazeState]，无状态时退化为普通玻璃。
 * blur 半径按玻璃层级 26/20/12dp（对齐设计稿 blur 30/20/12 的视觉重量），tint 复用玻璃填充色。
 */
@Composable
fun Modifier.glassHaze(level: GlassLevel): Modifier {
    val state = LocalHazeState.current ?: return this
    val glass = LocalGlassColors.current
    val style = remember(level, glass) {
        HazeStyle(
            backgroundColor = Color.Transparent,
            tints = listOf(HazeTint(glass.fill(level))),
            blurRadius = when (level) {
                GlassLevel.Hi -> 26.dp
                GlassLevel.Md -> 20.dp
                GlassLevel.Lo -> 12.dp
            },
            noiseFactor = 0.05f,
            fallbackTint = HazeTint(glass.fill(level)),
        )
    }
    return hazeEffect(state = state, style = style)
}
