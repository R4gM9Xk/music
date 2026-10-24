package com.local.music.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.local.music.core.ui.theme.AuroraPalette
import com.local.music.core.ui.theme.LocalGlassColors

/**
 * 主题色色板（F2）：圆形色点网格，选中项带描边 + 对勾语义。
 * 只负责「选颜色」，派生逻辑在 AuroraPalette.fromSeed（调用方传 seed 即可）。
 */
@Composable
fun ThemeColorSwatches(
    seeds: List<Color>,
    selected: Color?,
    onSelect: (Color) -> Unit,
    modifier: Modifier = Modifier,
) {
    val glass = LocalGlassColors.current
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        seeds.chunked(5).forEach { row ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                row.forEach { color ->
                    val on = selected != null && color.toArgbValue() == selected.toArgbValue()
                    Box(
                        Modifier
                            .weight(1f)
                            .height(44.dp)
                            .clip(CircleShape)
                            .background(color)
                            .border(
                                width = if (on) 3.dp else 1.dp,
                                color = if (on) glass.textPrimary else glass.strokeSoft,
                                shape = CircleShape,
                            )
                            .semantics {
                                this.role = Role.RadioButton
                                this.selected = on
                                contentDescription = "主题色"
                            }
                            .then(
                                // 选中态内圈白点：纯视觉指示（读屏走 selected 语义）
                                if (on) {
                                    Modifier.border(2.dp, Color.White.copy(alpha = 0.85f), CircleShape)
                                } else {
                                    Modifier
                                },
                            ),
                    ) {
                        // 色点内不放内容：纯色块 + 描边已足够识别
                    }
                }
                // 不足 5 个时补空位，保持网格对齐
                repeat(5 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** 极光实时小预览（F2）：用派生出的五色画一条迷你极光渐变，选色即时可见 */
@Composable
fun AuroraMiniPreview(
    palette: AuroraPalette,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .fillMaxWidth()
            .height(72.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(
                Brush.linearGradient(
                    listOf(palette.deep, palette.deep2, palette.seed, palette.seed2),
                ),
            ),
    ) {
        Text(
            "极光预览",
            color = Color.White.copy(alpha = 0.9f),
            fontSize = 12.sp,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 12.dp, bottom = 10.dp),
        )
    }
}

private fun Color.toArgbValue(): Int = toArgb()
