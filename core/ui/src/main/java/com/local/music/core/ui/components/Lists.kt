package com.local.music.core.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import com.local.music.core.ui.theme.GlassIcons
import com.local.music.core.ui.theme.GlassMotion
import com.local.music.core.ui.theme.LocalAuroraPalette
import com.local.music.core.ui.theme.LocalGlassColors
import com.local.music.core.ui.theme.auroraAccentColor

/** SongRow 固定行高（9+48+9dp）：队列滚动定位与拖拽重排共享的数学基准 */
val SongRowHeight = 66.dp

/** 大标题页头：30sp 粗体 + 可选右侧玻璃圆钮（.hd），可挂第二动作（渲染在主动作左侧）；actionLabel 作为按钮读屏描述 */
@Composable
fun PageHeader(
    title: String,
    modifier: Modifier = Modifier,
    actionIcon: ImageVector? = null,
    onAction: (() -> Unit)? = null,
    actionLabel: String? = null,
    action2Icon: ImageVector? = null,
    onAction2: (() -> Unit)? = null,
    action2Label: String? = null,
) {
    Row(
        modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 12.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(title, color = LocalGlassColors.current.textPrimary, fontSize = 30.sp, fontWeight = FontWeight.Bold,
            letterSpacing = (-0.6).sp)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (action2Icon != null && onAction2 != null) {
                GlassIconButton(
                    onClick = onAction2,
                    modifier = if (action2Label != null) {
                        Modifier.semantics { contentDescription = action2Label }
                    } else {
                        Modifier
                    },
                ) {
                    Icon(action2Icon, null, Modifier.size(20.dp), tint = LocalGlassColors.current.textPrimary)
                }
            }
            if (actionIcon != null && onAction != null) {
                GlassIconButton(
                    onClick = onAction,
                    modifier = if (actionLabel != null) {
                        Modifier.semantics { contentDescription = actionLabel }
                    } else {
                        Modifier
                    },
                ) {
                    Icon(actionIcon, null, Modifier.size(20.dp), tint = LocalGlassColors.current.textPrimary)
                }
            }
        }
    }
}

/** 玻璃搜索胶囊：44h / 圆角 22（.searchbox） */
@Composable
fun GlassSearchBox(
    text: String,
    placeholder: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    GlassSurface(
        modifier = modifier.fillMaxWidth().height(44.dp),
        level = GlassLevel.Md,
        shape = RoundedCornerShape(22.dp),
        onClick = onClick,
    ) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(GlassIcons.Search, null, Modifier.size(18.dp),
                tint = if (placeholder) LocalGlassColors.current.textSecondary else LocalGlassColors.current.textPrimary)
            Text(
                text, fontSize = 14.sp,
                color = if (placeholder) LocalGlassColors.current.textSecondary else LocalGlassColors.current.textPrimary,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** tab 胶囊行：选中填充 seed + 投影（.tabs/.tab） */
@Composable
fun GlassTabs(
    tabs: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalAuroraPalette.current
    val glass = LocalGlassColors.current
    Row(
        modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, bottom = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        tabs.forEachIndexed { index, label ->
            val on = index == selected
            val interactionSource = remember { MutableInteractionSource() }
            Box(
                Modifier
                    .pressScale(interactionSource, pressed = 0.95f)
                    .then(
                        // .tab.on：box-shadow 0 6px 16px seed 45%
                        if (on) {
                            Modifier.shadow(
                                6.dp, RoundedCornerShape(50), clip = false,
                                ambientColor = palette.seed.copy(alpha = 0.45f),
                                spotColor = palette.seed.copy(alpha = 0.45f),
                            )
                        } else {
                            Modifier
                        },
                    )
                    .clip(RoundedCornerShape(50))
                    .background(if (on) palette.seed else glass.lo)
                    .border(1.dp, if (on) Color.Transparent else glass.strokeSoft, RoundedCornerShape(50))
                    .clickable(interactionSource = interactionSource, indication = null) { onSelect(index) }
                    .semantics {
                        // 读屏语义：tab 角色 + 选中态（与 Chrome.kt 底导航 GlassNavBar 同款）
                        this.role = Role.Tab
                        this.selected = on
                    }
                    .padding(horizontal = 15.dp, vertical = 7.dp),
            ) {
                Text(
                    label, fontSize = 13.sp,
                    color = if (on) Color.White else LocalGlassColors.current.textSecondary,
                    fontWeight = if (on) FontWeight.Medium else FontWeight.Normal,
                )
            }
        }
    }
}

/** 歌曲行：48dp 圆角12 缩略图 + 标题/副标题 + 时长或播放波形（.row）
 *  onMoreClick 提供时"更多"是独立按钮；onRemoveClick（队列行）与 onLongClick（操作菜单）同理。
 *  onRemoveClick 优先级高于 onMoreClick（队列行不需要菜单，只要移除）。
 *  selected = 多选选中态（添加歌曲面板）：标题走强调色、尾部「已选」标记替换时长。 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun SongRow(
    song: DemoSong,
    modifier: Modifier = Modifier,
    playing: Boolean = false,
    squareThumb: Boolean = false,
    trailing: String? = song.duration,
    onMoreClick: (() -> Unit)? = null,
    onRemoveClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit = {},
    wavePlaying: Boolean = true,
    selected: Boolean = false,
) {
    val palette = LocalAuroraPalette.current
    val interactionSource = remember { MutableInteractionSource() }
    val actionInteractionSource = remember { MutableInteractionSource() }
    val rowClickable = if (onLongClick != null) {
        Modifier.combinedClickable(
            interactionSource = interactionSource,
            indication = null,
            onClick = onClick,
            onLongClick = onLongClick,
        )
    } else {
        Modifier.clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
    }
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp)
            .clip(RoundedCornerShape(14.dp))
            .pressScale(interactionSource, pressed = 0.98f)
            .then(rowClickable)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ArtThumb(
            song.art, Modifier.size(48.dp),
            RoundedCornerShape(if (squareThumb) 8.dp else 12.dp),
            bordered = true,
            bitmap = song.coverBitmap,
        )
        Column(Modifier.weight(1f)) {
            Text(
                song.title, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                // 播放/选中态强调色用 auroraAccentColor()（浅色回退 seed）：seed3 过浅压不住浅色背景
                color = if (playing || selected) auroraAccentColor() else LocalGlassColors.current.textPrimary,
            )
            Spacer(Modifier.height(2.dp))
            Text(song.subtitle, fontSize = 12.sp, color = LocalGlassColors.current.textTertiary,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (playing) {
            WaveIndicator(barCount = 4, playing = wavePlaying)
        } else if (selected) {
            Text("已选", fontSize = 12.sp, color = auroraAccentColor(), fontWeight = FontWeight.Medium)
        } else if (onMoreClick != null) {
            // "更多"是独立触点：不再吞进整行的播放点击
            Box(
                Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(interactionSource = actionInteractionSource, indication = null, onClick = onMoreClick)
                    .semantics { contentDescription = "更多操作" },
                contentAlignment = Alignment.Center,
            ) {
                Icon(GlassIcons.More, null, Modifier.size(16.dp),
                    tint = LocalGlassColors.current.textFaint)
            }
        } else if (onRemoveClick != null) {
            // 队列行移除：36dp 独立触点
            Box(
                Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(interactionSource = actionInteractionSource, indication = null, onClick = onRemoveClick)
                    .semantics { contentDescription = "从队列移除" },
                contentAlignment = Alignment.Center,
            ) {
                Icon(GlassIcons.MinusCircle, null, Modifier.size(16.dp),
                    tint = LocalGlassColors.current.textFaint)
            }
        } else if (trailing != null) {
            Text(trailing, fontSize = 12.sp, color = LocalGlassColors.current.textFaint)
        }
    }
}

/** 分组小标题（.sec） */
@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text, fontSize = 12.sp, color = LocalGlassColors.current.textTertiary, letterSpacing = 0.3.sp,
        modifier = modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 8.dp),
    )
}

/** 歌单卡：md 玻璃卡 + 封面 1.6:1 + 底部 lo 玻璃文字区（.pcard glass / .pt）；onLongClick = 操作菜单 */
@Composable
fun PlaylistCard(
    title: String,
    meta: String,
    art: ArtStyle,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    bitmap: androidx.compose.ui.graphics.ImageBitmap? = null,
    onLongClick: (() -> Unit)? = null,
) {
    val glass = LocalGlassColors.current
    GlassSurface(
        modifier = modifier,
        level = GlassLevel.Md,
        shape = RoundedCornerShape(18.dp),
        onClick = onClick,
        onLongClick = onLongClick,
    ) {
        Column {
            ArtThumb(art, Modifier.fillMaxWidth().aspectRatio(1.6f), RoundedCornerShape(0.dp), bitmap = bitmap)
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(glass.lo)
                    .padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 12.dp),
            ) {
                Text(title, color = LocalGlassColors.current.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(meta, color = LocalGlassColors.current.textTertiary, fontSize = 11.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** 设置行：32dp 图标块 + 主副标题 + 尾部控件（.setrow） */
@Composable
fun SettingRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    trailing: @Composable () -> Unit = {
        Icon(GlassIcons.ChevronRight, null, Modifier.size(16.dp),
            tint = LocalGlassColors.current.textFaint)
    },
) {
    val glass = LocalGlassColors.current
    val interactionSource = remember { MutableInteractionSource() }
    Row(
        modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(interactionSource, indication = null, onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(glass.md),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, Modifier.size(18.dp), tint = LocalGlassColors.current.textPrimary)
        }
        Column(Modifier.weight(1f)) {
            Text(title, color = LocalGlassColors.current.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(2.dp))
            Text(subtitle, color = LocalGlassColors.current.textTertiary, fontSize = 11.5.sp)
        }
        trailing()
    }
}
