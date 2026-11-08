package com.local.music.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import com.local.music.core.ui.theme.GlassText
import com.local.music.core.ui.theme.LocalGlassColors
import com.local.music.core.ui.theme.R
import com.local.music.core.ui.theme.S

/**
 * 主题色色板（F2）：圆形色点网格，选中项带描边 + 对勾语义。
 * 只负责「选颜色」，派生逻辑在 AuroraPalette.fromSeed（调用方传 seed 即可）。
 *
 * P3-56：选中环只用一层 3dp textPrimary 描边——旧实现外面 3dp 强调圈再叠一圈 2dp 白圈，
 * 深色下白圈贴着浅色描边只剩约 0.5dp 的"白牙"，反而把选中态弄糊；读屏 description
 * 也从清一色「主题色」改为带全局序号（"主题色 N"），逐项可区分。
 */
@Composable
fun ThemeColorSwatches(
    seeds: List<Color>,
    selected: Color?,
    onSelect: (Color) -> Unit,
    modifier: Modifier = Modifier,
) {
    val glass = LocalGlassColors.current
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(S.Gap12.dp)) {
        seeds.chunked(5).forEachIndexed { rowIndex, row ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(S.Gap12.dp),
            ) {
                row.forEachIndexed { colIndex, color ->
                    // 全局索引（跨行连续）：读屏序号与色板展示顺序一一对应
                    val index = rowIndex * 5 + colIndex
                    val on = selected != null && color.toArgbValue() == selected.toArgbValue()
                    Box(
                        Modifier
                            .weight(1f)
                            .height(44.dp)
                            .clip(CircleShape)
                            .background(color)
                            // A3-04：选中环宽度/颜色走 tweenHover——旧实现双重瞬变
                            // （border 不参与布局，动画只影响描边绘制）
                            .border(
                                width = androidx.compose.animation.core.animateDpAsState(
                                    if (on) 3.dp else 1.dp,
                                    com.local.music.core.ui.theme.GlassMotion.tweenHover(),
                                    label = "swatchBorder",
                                ).value,
                                color = androidx.compose.animation.animateColorAsState(
                                    if (on) glass.textPrimary else glass.strokeSoft,
                                    com.local.music.core.ui.theme.GlassMotion.tweenHover(),
                                    label = "swatchBordercolor",
                                ).value,
                                shape = CircleShape,
                            )
                            // clickable 在 semantics 之前：读屏把 role/selected 合并到同一可点击节点
                            .clickable(onClickLabel = "选择主题色") { onSelect(color) }
                            .semantics {
                                this.role = Role.RadioButton
                                this.selected = on
                                contentDescription = "主题色 ${index + 1}"
                            },
                    ) {
                        // 色点内不放内容：纯色块 + 3dp 描边已足够识别（P3-56：不再叠白圈）
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
            .clip(RoundedCornerShape(R.Card14.dp))
            .background(
                Brush.linearGradient(
                    listOf(palette.deep, palette.deep2, palette.seed, palette.seed2),
                ),
            ),
    ) {
        Text(
            "极光预览",
            color = Color.White.copy(alpha = 0.9f),
            style = GlassText.Caption,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = S.Gap12.dp, bottom = S.Gap12.dp),
        )
    }
}

private fun Color.toArgbValue(): Int = toArgb()
