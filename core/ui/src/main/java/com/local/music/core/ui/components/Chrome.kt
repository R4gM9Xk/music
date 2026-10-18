package com.local.music.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.draw.drawBehind
import com.local.music.core.ui.theme.GlassIcons
import com.local.music.core.ui.theme.LocalAuroraPalette
import com.local.music.core.ui.theme.LocalGlassColors

/**
 * 播放进度轨：5px 高圆角轨道，seed2→文字色 渐变填充 + 端点 12dp 发光圆点。
 * 点击直接 seek；拖动本地预览、松手提交；24dp 触摸热区 + 读屏进度语义。
 */
@Composable
fun ProgressTrack(
    progress: Float,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalAuroraPalette.current
    val glass = LocalGlassColors.current
    var dragProgress by remember { mutableStateOf<Float?>(null) }
    val shown = (dragProgress ?: progress).coerceIn(0f, 1f)

    Box(
        modifier
            .fillMaxWidth()
            .height(24.dp) // 触摸热区（视觉轨道仍 5dp 居中）
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    onSeek((offset.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f))
                }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        dragProgress = (change.position.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                    },
                    onDragEnd = {
                        dragProgress?.let(onSeek)
                        dragProgress = null
                    },
                    onDragCancel = { dragProgress = null },
                )
            }
            .semantics {
                contentDescription = "播放进度"
                progressBarRangeInfo = ProgressBarRangeInfo(shown, 0f..1f)
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(5.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(glass.textPrimary.copy(alpha = 0.20f)),
        )
        Box(
            Modifier
                .fillMaxWidth(shown)
                .height(5.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(Brush.horizontalGradient(listOf(palette.seed2, glass.textPrimary))),
        )
        // 端点圆点 + 辉光（.fill::after）
        Row(Modifier.fillMaxWidth()) {
            Spacer(Modifier.fillMaxWidth().weight(shown.coerceIn(0.001f, 1f)))
            Box(
                Modifier
                    .size(12.dp)
                    .drawBehind {
                        drawCircle(glass.textPrimary.copy(alpha = 0.35f), radius = size.minDimension * 0.9f)
                    }
                    .clip(CircleShape)
                    .background(glass.textPrimary),
            )
            Spacer(Modifier.fillMaxWidth().weight(1f - shown.coerceIn(0f, 0.999f)))
        }
    }
}

/** 悬浮迷你条：62h / 圆角 20 / hi 玻璃，底部 2.5px 进度线 */
@Composable
fun MiniPlayerBar(
    song: DemoSong,
    playing: Boolean,
    progress: Float,
    onClick: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalAuroraPalette.current
    val glass = LocalGlassColors.current
    val flight = LocalPlayerFlight.current
    val interactionSource = remember { MutableInteractionSource() }
    GlassSurface(
        modifier = modifier
            .fillMaxWidth()
            .height(62.dp)
            .trackFlight(FlightKeys.MiniGlass, keepOnDispose = true)
            .graphicsLayer { alpha = flight?.miniSharedAlpha() ?: 1f },
        level = GlassLevel.Hi,
        shape = RoundedCornerShape(20.dp),
        blur = GlassLevel.Hi,
        onClick = onClick,
    ) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            ArtThumb(song.art, Modifier.trackFlight(FlightKeys.MiniCover, keepOnDispose = true).size(42.dp), RoundedCornerShape(11.dp), bordered = true, bitmap = song.coverBitmap)
            Column(Modifier.weight(1f)) {
                Text(song.title, color = glass.textPrimary, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.trackFlight(FlightKeys.MiniTitle, keepOnDispose = true))
                Text(song.artist, color = glass.textSecondary, fontSize = 11.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.trackFlight(FlightKeys.MiniArtist, keepOnDispose = true))
            }
            MiniBarButton(onPlayPause, Modifier.trackFlight(FlightKeys.MiniPlay, keepOnDispose = true)) {
                Icon(if (playing) GlassIcons.Pause else GlassIcons.Play, null,
                    Modifier.size(22.dp), tint = glass.textPrimary)
            }
            MiniBarButton(onNext, Modifier.trackFlight(FlightKeys.MiniNext, keepOnDispose = true)) {
                Icon(GlassIcons.Next, null, Modifier.size(22.dp), tint = glass.textPrimary)
            }
        }
        // 底部进度线（.mini .ml）
        Box(
            Modifier
                .align(Alignment.BottomStart)
                .padding(horizontal = 12.dp)
                .fillMaxWidth()
                .height(2.5.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(glass.textPrimary.copy(alpha = 0.15f)),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(progress.coerceIn(0f, 1f))
                    .fillMaxHeight()
                    .background(Brush.horizontalGradient(listOf(palette.seed2, glass.textPrimary))),
            )
        }
    }
}

@Composable
private fun MiniBarButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier
            .pressScale(interactionSource, pressed = 0.88f)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .padding(4.dp),
    ) { content() }
}

/** 底导航胶囊：58h / 圆角 99 / md 玻璃 + hi 模糊层级，3 项，选中项白色 + 强调色辉光 */
@Composable
fun GlassNavBar(
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalAuroraPalette.current
    val glass = LocalGlassColors.current
    val items = listOf(
        Triple("曲库", GlassIcons.Home, 0),
        Triple("发现", GlassIcons.Compass, 1),
        Triple("我的", GlassIcons.User, 2),
    )
    GlassSurface(
        modifier = modifier.fillMaxWidth().height(58.dp),
        level = GlassLevel.Md,
        shape = CircleShape,
        blur = GlassLevel.Md,
    ) {
        Row(Modifier.fillMaxSize()) {
            items.forEach { (label, icon, index) ->
                val on = selected == index
                val interactionSource = remember { MutableInteractionSource() }
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .pressScale(interactionSource, pressed = 0.94f)
                        .clickable(interactionSource = interactionSource, indication = null) { onSelect(index) }
                        .semantics {
                            this.role = Role.Tab
                            this.selected = on
                        },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    // 选中图标：白色 + drop-shadow(0 0 8px seed2) 辉光（径向光晕近似）
                    Box(contentAlignment = Alignment.Center) {
                        if (on) {
                            Box(
                                Modifier
                                    .size(30.dp)
                                    .drawBehind {
                                        drawCircle(
                                            Brush.radialGradient(
                                                0f to palette.seed2.copy(alpha = 0.9f),
                                                1f to Color.Transparent,
                                            ),
                                        )
                                    },
                            )
                        }
                        Icon(
                            icon, null, Modifier.size(21.dp),
                            tint = if (on) glass.textPrimary else glass.textTertiary,
                        )
                    }
                    Spacer(Modifier.height(3.dp))
                    Text(
                        label, fontSize = 10.5.sp,
                        color = if (on) glass.textPrimary else glass.textTertiary,
                    )
                }
            }
        }
    }
}

/** 屏幕底部 chrome：迷你条 + 胶囊导航（左右 12、下 10，悬浮于极光之上） */
@Composable
fun GlassChrome(
    song: DemoSong,
    playing: Boolean,
    progress: Float,
    navSelected: Int,
    onNavSelect: (Int) -> Unit,
    onOpenPlayer: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val flight = LocalPlayerFlight.current
    Column(
        modifier
            .fillMaxWidth()
            .navigationBarsPadding() // 手势条悬浮于 chrome 之下（对应设计稿 homebar）
            .padding(start = 12.dp, end = 12.dp, bottom = 10.dp),
    ) {
        MiniPlayerBar(song, playing, progress, onOpenPlayer, onPlayPause, onNext)
        Spacer(Modifier.height(8.dp))
        // 导航胶囊：飞行期间随 t 消隐/复原；rect 供进入飞行的覆盖层渐隐替身使用
        GlassNavBar(
            navSelected, onNavSelect,
            Modifier
                .trackFlight(FlightKeys.MiniNav, keepOnDispose = true)
                .graphicsLayer { alpha = flight?.navAlpha() ?: 1f },
        )
    }
}
