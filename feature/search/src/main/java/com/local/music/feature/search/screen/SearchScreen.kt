package com.local.music.feature.search.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Color
import com.local.music.core.ui.components.DemoCatalog
import com.local.music.core.ui.components.DemoSong
import com.local.music.core.ui.components.DetailTarget
import com.local.music.core.ui.components.GlassChrome
import com.local.music.core.ui.components.GlassLevel
import com.local.music.core.ui.components.GlassSurface
import com.local.music.core.ui.components.GlassTextField
import com.local.music.core.ui.components.LocalHazeState
import com.local.music.core.ui.components.LocalModalState
import com.local.music.core.ui.components.LocalPlayerFlight
import com.local.music.core.ui.components.PlaylistCard
import com.local.music.core.ui.components.SectionHeader
import com.local.music.core.ui.components.SongActionsSheet
import com.local.music.core.ui.components.SongRow
import com.local.music.core.ui.components.pressScale
import com.local.music.core.ui.state.AppState
import com.local.music.core.ui.state.GlassScreen
import com.local.music.core.ui.theme.LocalGlassColors
import com.local.music.core.ui.theme.auroraAccentColor
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState

/**
 * 搜索：真输入框（实时过滤标题/艺术家/专辑）+ 历史芯片 + 带数据的结果
 * （歌曲行播放入队、专辑卡进专辑详情；歌曲行 ⋯/长按 = 单曲操作面板）。
 */
@Composable
fun SearchScreen(state: AppState, modifier: Modifier = Modifier) {
    val query = state.searchQuery
    val results = state.searchSongs(query)
    val albums = state.searchAlbumGroups(query)
    // 展开态跟随查询词重置（remember 以 query 为 key，换词自动收起）
    var showAllAlbums by remember(query) { mutableStateOf(false) }
    var actionSong by remember { mutableStateOf<DemoSong?>(null) }
    val flight = LocalPlayerFlight.current

    val hazeState = rememberHazeState()
    CompositionLocalProvider(LocalHazeState provides hazeState) {
        Column(modifier.fillMaxSize().statusBarsPadding().imePadding()) {
            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .hazeSource(hazeState),
            ) {
            // 搜索框 + 取消
            Row(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 2.dp, bottom = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                GlassTextField(
                    value = query,
                    onValueChange = { state.searchQuery = it },
                    hint = "搜索歌曲、专辑、艺术家",
                    autoFocus = true,
                    onSearch = { state.addSearchHistory(query) },
                    modifier = Modifier.weight(1f),
                )
                val cancelInteraction = remember { MutableInteractionSource() }
                Text(
                    "取消", color = auroraAccentColor(), fontSize = 13.sp,
                    modifier = Modifier
                        .pressScale(cancelInteraction, pressed = 0.92f)
                        .clickable(interactionSource = cancelInteraction, indication = null) {
                            state.searchQuery = ""
                            state.screen = GlassScreen.Library
                        }
                        .padding(4.dp),
                )
            }

            if (query.isBlank()) {
                SectionHeader("最近搜索")
                // FlowRow 自动换行：长历史词不再挤出屏幕
                FlowRow(
                    Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    state.searchHistory.take(4).forEach { keyword -> GlassChip(keyword) {
                        state.searchQuery = keyword
                    } }
                }
            } else {
                val empty = results.isEmpty() && albums.isEmpty()
                if (empty) {
                    Text(
                        "没有找到「${query.trim()}」相关内容",
                        color = LocalGlassColors.current.textTertiary, fontSize = 14.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(top = 56.dp),
                    )
                }
                if (results.isNotEmpty()) {
                    SectionHeader("歌曲 · ${results.size} 条结果")
                    results.forEach { song ->
                        SongRow(
                            song = song,
                            playing = song.id == state.currentSong.id,
                            wavePlaying = state.playing,
                            onMoreClick = { actionSong = song },
                            onLongClick = { actionSong = song },
                            onClick = {
                                state.addSearchHistory(query)
                                state.playSong(song, results, "搜索")
                                state.screen = GlassScreen.Player
                            },
                        )
                    }
                }
                if (albums.isNotEmpty()) {
                    SectionHeader("专辑 · ${albums.size} 张")
                    val visibleAlbums = if (showAllAlbums) albums else albums.take(2)
                    visibleAlbums.chunked(2).forEach { pair ->
                        Row(
                            Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            pair.forEach { album ->
                                PlaylistCard(
                                    title = album.key, meta = album.meta, art = album.art,
                                    onClick = { state.openDetail(DetailTarget(DetailTarget.Kind.Album, album.key)) },
                                    modifier = Modifier.weight(1f),
                                    bitmap = album.coverBitmap,
                                )
                            }
                        }
                    }
                    if (albums.size > 2) {
                        val expandInteraction = remember { MutableInteractionSource() }
                        Text(
                            if (showAllAlbums) "收起" else "全部 ${albums.size} 张专辑",
                            color = auroraAccentColor(), fontSize = 12.5.sp, fontWeight = FontWeight.Medium,
                            modifier = Modifier
                                .padding(start = 20.dp, end = 20.dp, bottom = 14.dp)
                                .pressScale(expandInteraction, pressed = 0.92f)
                                .clickable(
                                    interactionSource = expandInteraction, indication = null,
                                ) { showAllAlbums = !showAllAlbums },
                        )
                    }
                }
            }
            Spacer(Modifier.height(18.dp))
            }
        // 单曲面板打开时 Chrome 让位（与 PlayerScreen 控制台同策略）：
        // graphicsLayer alpha 只隐不卸——keepOnDispose 的迷你条飞行几何保持冻结；
        // ModalState 注销已延迟到退场动画结束，面板滑出全程 Chrome 保持让位
        val modal = LocalModalState.current
        GlassChrome(
            modifier = Modifier.graphicsLayer { alpha = if (modal.isOpen) 0f else 1f },
            song = state.currentSong, playing = state.playing, progress = state.progressFraction,
            navSelected = 0,
            onNavSelect = {
                when (it) {
                    0 -> state.screen = GlassScreen.Library
                    1 -> state.screen = GlassScreen.Playlist
                    2 -> state.screen = GlassScreen.Settings
                }
            },
            onOpenPlayer = {
                // 与 GlassApp 的 Chrome 同走共享元素飞行（忙时兜底直切）
                val f = flight
                if (f != null && !f.isActive) {
                    f.beginEntry(state.currentSong, 0) { state.screen = it }
                } else {
                    state.screen = GlassScreen.Player
                }
            },
            onPlayPause = state::togglePlayPause,
            onNext = { state.next() },
        )
        }

        // 单曲操作面板（搜索结果的 ⋯ / 长按入口）
        actionSong?.let { song ->
            SongActionsSheet(
                visible = true,
                onDismiss = { actionSong = null },
                state = state,
                song = song,
            )
        }
    }
}

/** 玻璃芯片（.chip），点击写入搜索词 */
@Composable
private fun GlassChip(text: String, onClick: () -> Unit) {
    GlassSurface(level = GlassLevel.Lo, shape = RoundedCornerShape(50), onClick = onClick) {
        Text(
            text, color = LocalGlassColors.current.textSecondary,
            fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 13.dp, vertical = 7.dp),
        )
    }
}
