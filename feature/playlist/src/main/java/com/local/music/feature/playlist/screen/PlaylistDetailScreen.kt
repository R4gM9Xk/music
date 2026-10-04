package com.local.music.feature.playlist.screen

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.local.music.core.ui.components.ArtThumb
import com.local.music.core.ui.components.GlassChrome
import com.local.music.core.ui.components.GlassIconButton
import com.local.music.core.ui.components.GlassLevel
import com.local.music.core.ui.components.GlassSheet
import com.local.music.core.ui.components.GlassSurface
import com.local.music.core.ui.components.LocalHazeState
import com.local.music.core.ui.components.SheetAction
import com.local.music.core.ui.components.SongRow
import com.local.music.core.ui.components.pressScale
import com.local.music.core.ui.state.AppState
import com.local.music.core.ui.state.GlassScreen
import com.local.music.core.ui.theme.GlassIcons
import com.local.music.core.ui.theme.LocalAuroraPalette
import com.local.music.core.ui.theme.LocalGlassColors
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState

/**
 * 详情页（歌单 / 专辑 / 艺术家 / 文件夹 / 流派 共用模板）：
 * 数据由 AppState.detailData(state.detail) 解析；播放 / 随机 / 曲目行全部接入播放队列。
 */
@Composable
fun PlaylistDetailScreen(state: AppState, modifier: Modifier = Modifier) {
    val palette = LocalAuroraPalette.current
    val data = state.detailData(state.detail)
    var showMore by remember { mutableStateOf(false) }

    if (data == null) {
        // 目标已被删除（如用户歌单）：退回歌单库
        LaunchedEffect(Unit) { state.screen = GlassScreen.Playlist }
        Box(modifier.fillMaxSize())
        return
    }

    fun playAll(shuffle: Boolean) {
        if (data.tracks.isEmpty()) return
        state.shuffleOn = shuffle
        state.playSong(if (shuffle) data.tracks.random() else data.tracks.first(), data.tracks)
        state.screen = GlassScreen.Player
    }

    val hazeState = rememberHazeState()
    CompositionLocalProvider(LocalHazeState provides hazeState) {
        Column(modifier.fillMaxSize().statusBarsPadding()) {
            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .hazeSource(hazeState),
            ) {
            // 顶栏：返回 / 类别 / 更多
            Row(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 2.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                GlassIconButton(onClick = { state.backFromDetail() }) {
                    Icon(GlassIcons.Back, null, Modifier.size(20.dp), tint = LocalGlassColors.current.textPrimary)
                }
                Text(data.header, color = LocalGlassColors.current.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                GlassIconButton(onClick = { showMore = true }, size = 36.dp) {
                    Icon(GlassIcons.More, null, Modifier.size(19.dp), tint = LocalGlassColors.current.textPrimary)
                }
            }

            // 沉浸头图
            Row(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 16.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                ArtThumb(
                    data.art,
                    Modifier
                        .size(132.dp)
                        .shadow(18.dp, RoundedCornerShape(24.dp))
                        .border(1.dp, Color.White.copy(alpha = 0.28f), RoundedCornerShape(24.dp)),
                    RoundedCornerShape(24.dp),
                    bitmap = data.cover,
                )
                Column(Modifier.weight(1f)) {
                    Text(data.title, color = LocalGlassColors.current.textPrimary, fontSize = 20.sp,
                        fontWeight = FontWeight.Bold, lineHeight = 26.sp,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(6.dp))
                    Text(data.meta, color = LocalGlassColors.current.textSecondary, fontSize = 12.sp)
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        // 播放：seed 填充胶囊 + seed 色投影
                        val playInteraction = remember { MutableInteractionSource() }
                        Row(
                            Modifier
                                .pressScale(playInteraction, pressed = 0.95f)
                                .shadow(
                                    8.dp, CircleShape, clip = false,
                                    ambientColor = palette.seed.copy(alpha = 0.45f),
                                    spotColor = palette.seed.copy(alpha = 0.45f),
                                )
                                .clip(CircleShape)
                                .background(palette.seed)
                                .clickable(interactionSource = playInteraction, indication = null) { playAll(false) }
                                .padding(horizontal = 20.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Icon(GlassIcons.Play, null, Modifier.size(16.dp), tint = Color.White)
                            Text("播放", color = Color.White, fontSize = 13.sp) // seed 实底上保持白字
                        }
                        // 随机：玻璃胶囊（玻璃底上用主题文字色，浅色下保持对比度）
                        GlassSurface(level = GlassLevel.Md, shape = CircleShape, onClick = { playAll(true) }) {
                            Row(
                                Modifier.padding(horizontal = 15.dp, vertical = 9.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Icon(GlassIcons.Shuffle, null, Modifier.size(16.dp), tint = LocalGlassColors.current.textPrimary)
                                Text("随机", color = LocalGlassColors.current.textPrimary, fontSize = 13.sp)
                            }
                        }
                    }
                }
            }

            // 曲目玻璃卡（.card glass → md 层级）
            GlassSurface(
                modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 14.dp),
                level = GlassLevel.Md,
                shape = RoundedCornerShape(18.dp),
            ) {
                Column(Modifier.padding(vertical = 6.dp)) {
                    data.tracks.forEach { track ->
                        SongRow(
                            song = track,
                            squareThumb = true,
                            playing = track.id == state.currentSong.id,
                            onClick = {
                                state.playSong(track, data.tracks)
                                state.screen = GlassScreen.Player
                            },
                        )
                    }
                    if (data.tracks.isEmpty()) {
                        Text(
                            "这里还没有歌曲",
                            color = LocalGlassColors.current.textTertiary, fontSize = 13.sp,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                    }
                }
            }
            Spacer(Modifier.height(18.dp))
            }
        GlassChrome(
            song = state.currentSong, playing = state.playing, progress = state.progressFraction,
            navSelected = 1,
            onNavSelect = {
                when (it) {
                    0 -> state.screen = GlassScreen.Library
                    1 -> state.screen = GlassScreen.Playlist
                    2 -> state.screen = GlassScreen.Settings
                }
            },
            onOpenPlayer = { state.screen = GlassScreen.Player },
            onPlayPause = state::togglePlayPause,
            onNext = { state.next() },
        )
        }
    }

    // 更多面板：播放全部 / 随机播放 / 删除歌单（仅用户自建）
    GlassSheet(visible = showMore, onDismiss = { showMore = false }, title = data.title) {
        SheetAction(
            icon = GlassIcons.Play, label = "全部播放", subtitle = "${data.tracks.size} 首",
            onClick = {
                showMore = false
                playAll(false)
            },
        )
        SheetAction(
            icon = GlassIcons.Shuffle, label = "随机播放", subtitle = "从随机一首开始",
            onClick = {
                showMore = false
                playAll(true)
            },
        )
        if (state.detail.id.startsWith("user-")) {
            SheetAction(
                icon = GlassIcons.Folder, label = "删除歌单",
                onClick = {
                    showMore = false
                    state.deletePlaylist(state.detail.id)
                    state.screen = GlassScreen.Playlist
                },
            )
        }
    }
}
