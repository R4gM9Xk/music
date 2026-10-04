package com.local.music.feature.widget.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.local.music.core.ui.components.ArtThumb
import com.local.music.core.ui.components.DemoCatalog
import com.local.music.core.ui.components.GlassIconButton
import com.local.music.core.ui.components.GlassLevel
import com.local.music.core.ui.components.GlassSurface
import com.local.music.core.ui.components.ProgressTrack
import com.local.music.core.ui.components.WaveIndicator
import com.local.music.core.ui.components.asTime
import com.local.music.core.ui.components.pressScale
import com.local.music.core.ui.state.AppState
import com.local.music.core.ui.state.GlassScreen
import com.local.music.core.ui.theme.GlassIcons
import com.local.music.core.ui.theme.LocalGlassColors

/**
 * 流体云 / 小部件展示：全部控件接入 AppState——
 * 进度轨可 seek、控制键可控播、收藏可切换、状态栏胶囊点击回播放页、
 * 状态栏歌词实时显示当前行。左上补返回按钮。
 */
@Composable
fun FluidShowcaseScreen(state: AppState, modifier: Modifier = Modifier) {
    val song = state.currentSong

    Column(
        modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState()),
    ) {
        // ── 状态栏区：返回 + 玻璃胶囊（时间/信号由系统状态栏承担）──
        Row(
            Modifier.fillMaxWidth().padding(start = 14.dp, end = 22.dp, top = 0.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GlassIconButton(onClick = { state.screen = GlassScreen.Library }) {
                Icon(GlassIcons.Back, null, Modifier.size(20.dp), tint = Color.White)
            }
            Spacer(Modifier.weight(1f))
            StatusCapsule(song, onClick = { state.screen = GlassScreen.Player })
        }

        FloatLabel("状态栏胶囊 · 玻璃材质")
        // ── 流体云控制卡 ──────────────────────────────────
        GlassSurface(
            modifier = Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp),
            level = GlassLevel.Md,
            shape = RoundedCornerShape(26.dp),
        ) {
            Column(Modifier.padding(14.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    ArtThumb(song.art, Modifier.size(48.dp), RoundedCornerShape(12.dp), bordered = true, bitmap = song.coverBitmap)
                    Column(Modifier.weight(1f)) {
                        Text(song.title, color = Color.White, fontSize = 14.5.sp,
                            fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(song.subtitle, color = Color.White.copy(alpha = 0.6f), fontSize = 11.5.sp)
                    }
                    FluidIcon(onClick = { state.toggleFavorite(song.id) }) {
                        Icon(
                            if (state.isFavorite(song.id)) GlassIcons.HeartFilled else GlassIcons.Heart,
                            null, Modifier.size(20.dp),
                            tint = if (state.isFavorite(song.id)) {
                                Color.White
                            } else {
                                Color.White.copy(alpha = 0.75f)
                            },
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                ProgressTrack(progress = state.progressFraction, onSeek = state::seekToFraction)
                Row(
                    Modifier.fillMaxWidth().padding(top = 7.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(state.progressSec.toInt().asTime(), color = Color.White.copy(alpha = 0.55f), fontSize = 11.sp)
                    Text(song.duration, color = Color.White.copy(alpha = 0.55f), fontSize = 11.sp)
                }
                Row(
                    Modifier.fillMaxWidth().padding(top = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FluidIcon(onClick = { state.prev() }) {
                        Icon(GlassIcons.Prev, null, Modifier.size(22.dp), tint = Color.White.copy(alpha = 0.9f))
                    }
                    // .pbtn：hi 玻璃 + stroke-soft 描边
                    val pbtnInteraction = remember { MutableInteractionSource() }
                    Box(
                        Modifier
                            .pressScale(pbtnInteraction, pressed = 0.92f)
                            .size(46.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.16f))
                            .border(1.dp, LocalGlassColors.current.strokeSoft, CircleShape)
                            .clickable(
                                interactionSource = pbtnInteraction, indication = null,
                                onClick = state::togglePlayPause,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            if (state.playing) GlassIcons.Pause else GlassIcons.Play,
                            null, Modifier.size(22.dp), tint = Color.White,
                        )
                    }
                    FluidIcon(onClick = { state.next() }) {
                        Icon(GlassIcons.Next, null, Modifier.size(22.dp), tint = Color.White.copy(alpha = 0.9f))
                    }
                }
            }
        }

        FloatLabel("桌面小部件 · 4×2 玻璃卡", top = 18)
        // ── 4×2 小部件 ────────────────────────────────────
        GlassSurface(
            modifier = Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, bottom = 14.dp),
            level = GlassLevel.Md,
            shape = RoundedCornerShape(22.dp),
        ) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 13.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(11.dp),
                ) {
                    ArtThumb(song.art, Modifier.size(44.dp), RoundedCornerShape(11.dp), bordered = true, bitmap = song.coverBitmap)
                    Column(Modifier.weight(1f)) {
                        Text(song.title, color = Color.White, fontSize = 13.5.sp,
                            fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(song.artist, color = Color.White.copy(alpha = 0.6f), fontSize = 11.5.sp)
                    }
                    FluidIcon(onClick = state::togglePlayPause) {
                        Icon(
                            if (state.playing) GlassIcons.Pause else GlassIcons.Play,
                            null, Modifier.size(20.dp), tint = Color.White.copy(alpha = 0.8f),
                        )
                    }
                    FluidIcon(onClick = { state.next() }) {
                        Icon(GlassIcons.Next, null, Modifier.size(20.dp), tint = Color.White.copy(alpha = 0.8f))
                    }
                }
                Spacer(Modifier.height(10.dp))
                ProgressTrack(progress = state.progressFraction, onSeek = state::seekToFraction)
            }
        }

        FloatLabel("状态栏歌词", top = 18)
        // ── 状态栏歌词胶囊（实时当前行）─────────────────────
        GlassSurface(
            modifier = Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp),
            level = GlassLevel.Md,
            shape = RoundedCornerShape(19.dp),
        ) {
            Box(
                Modifier.fillMaxWidth().height(38.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    state.currentLyrics?.getOrNull(state.currentLyricIndex)?.text ?: song.title,
                    color = Color.White, fontSize = 13.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

/** 状态栏玻璃胶囊：18dp 封面 + 5 条跳动波形（.capsule），点击回播放页 */
@Composable
private fun StatusCapsule(song: com.local.music.core.ui.components.DemoSong, onClick: () -> Unit) {
    GlassSurface(level = GlassLevel.Hi, shape = RoundedCornerShape(13.dp), onClick = onClick) {
        Row(
            Modifier.height(26.dp).padding(start = 4.dp, end = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ArtThumb(song.art, Modifier.size(18.dp), RoundedCornerShape(6.dp), bitmap = song.coverBitmap)
            WaveIndicator(barCount = 5)
        }
    }
}

/** 流体卡小图标按钮：按压回弹 */
@Composable
private fun FluidIcon(onClick: () -> Unit, content: @Composable () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        Modifier
            .pressScale(interactionSource, pressed = 0.88f)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .padding(4.dp),
    ) { content() }
}

@Composable
private fun FloatLabel(text: String, top: Int = 0) {
    Text(
        text, color = Color.White.copy(alpha = 0.5f), fontSize = 11.sp,
        modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = top.dp, bottom = 8.dp),
    )
}
