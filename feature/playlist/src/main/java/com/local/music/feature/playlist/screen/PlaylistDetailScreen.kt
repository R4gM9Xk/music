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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.local.music.core.ui.components.AddSongsSheet
import com.local.music.core.ui.components.ArtThumb
import com.local.music.core.ui.components.CollectionActionsSheet
import com.local.music.core.ui.components.DemoCatalog
import com.local.music.core.ui.components.DetailTarget
import com.local.music.core.ui.components.GlassChrome
import com.local.music.core.ui.components.GlassIconButton
import com.local.music.core.ui.components.GlassLevel
import com.local.music.core.ui.components.GlassSurface
import com.local.music.core.ui.components.LocalHazeState
import com.local.music.core.ui.components.LocalModalState
import com.local.music.core.ui.components.LocalPlayerFlight
import com.local.music.core.ui.components.ReorderableColumn
import com.local.music.core.ui.components.SongActionsSheet
import com.local.music.core.ui.components.SongRow
import com.local.music.core.ui.components.SongRowHeight
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
 * 数据由 AppState.detailData(state.detail) 解析；播放 / 随机经 playCollection 入队，
 * 曲目行 ⋯/长按 = 单曲操作面板，顶栏 ＋ = 添加歌曲面板（仅非动态歌单），
 * 顶栏更多 = 集合操作面板。
 * 面板经 LocalModalState.show 挂 GlassApp 根层宿主（屏内不再渲染 GlassSheet）；
 * 自绘 Chrome 的让位（modal.isOpen 时 alpha=0，保留组合护 keepOnDispose 几何）维持不变。
 */
@Composable
fun PlaylistDetailScreen(state: AppState, modifier: Modifier = Modifier) {
    val palette = LocalAuroraPalette.current
    val data = state.detailData(state.detail)
    // 模态面板宿主（GlassApp 根层）；Chrome 让位同读此状态
    val modal = LocalModalState.current

    if (data == null) {
        // 目标已被删除（如用户歌单）：退回歌单库
        LaunchedEffect(Unit) { state.screen = GlassScreen.Playlist }
        Box(modifier.fillMaxSize())
        return
    }

    fun playAll(shuffle: Boolean) {
        state.playCollection(data.tracks, data.title, shuffle)
        state.screen = GlassScreen.Player
    }
    val flight = LocalPlayerFlight.current

    val hazeState = rememberHazeState()
    val pageScroll = rememberScrollState()
    CompositionLocalProvider(LocalHazeState provides hazeState) {
        Column(modifier.fillMaxSize().statusBarsPadding()) {
            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(pageScroll)
                    .hazeSource(hazeState),
            ) {
            // 顶栏：返回 / 类别 / ＋添加歌曲（仅非动态歌单） / 更多
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
                // 添加歌曲入口（顶栏 ＋，PageHeader 双动作钮同款惯例）：仅歌单类且非动态歌单——
                // 收藏 / 最近播放是动态解析歌单，addToPlaylist 写入的叠加层不参与其解析，
                // 显示入口只会做出无效操作（CollectionActionsSheet 为固定列表组件且冻结，
                // 动作项无法内插，入口上移到顶栏）
                if (state.detail.kind == DetailTarget.Kind.Playlist &&
                    state.detail.id != DemoCatalog.FavoritesId && state.detail.id != DemoCatalog.RecentId
                ) {
                    GlassIconButton(
                        onClick = {
                            // 添加歌曲面板：目标与现有曲目取打开时刻的详情快照（同集合面板惯例）
                            val playlistId = state.detail.id
                            modal.show {
                                AddSongsSheet(
                                    visible = modal.visible,
                                    onDismiss = modal::requestDismiss,
                                    state = state,
                                    playlistId = playlistId,
                                    existingTracks = data.tracks,
                                    onAdd = { songs -> songs.forEach { state.addToPlaylist(playlistId, it.id) } },
                                )
                            }
                        },
                        modifier = Modifier.semantics { contentDescription = "添加歌曲" },
                        size = 36.dp,
                    ) {
                        Icon(GlassIcons.Plus, null, Modifier.size(19.dp), tint = LocalGlassColors.current.textPrimary)
                    }
                }
                GlassIconButton(
                    onClick = {
                        // 集合操作面板（顶栏更多）：目标/标题/曲目取打开时刻的详情快照
                        modal.show {
                            CollectionActionsSheet(
                                visible = modal.visible,
                                onDismiss = modal::requestDismiss,
                                state = state,
                                target = state.detail,
                                title = data.title,
                                tracks = data.tracks,
                                onPlayAll = { shuffle -> playAll(shuffle) },
                            )
                        }
                    },
                    size = 36.dp,
                ) {
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

            // 曲目玻璃卡（.card glass → md 层级）；自建歌单且无失效引用时曲目行可长按拖拽重排
            val reorderable = state.detail.kind == DetailTarget.Kind.Playlist &&
                state.canReorderPlaylist(state.detail.id)
            val renderTrack: @Composable (Int) -> Unit = { index ->
                val track = data.tracks[index]
                SongRow(
                    song = track,
                    squareThumb = true,
                    playing = track.id == state.currentSong.id,
                    wavePlaying = state.playing,
                    onMoreClick = {
                        // 单曲面板：歌单语境提供「从歌单移除」（语境 id 读当下 detail，与旧判定一致）
                        modal.show {
                            SongActionsSheet(
                                visible = modal.visible,
                                onDismiss = modal::requestDismiss,
                                state = state,
                                song = track,
                                playlistContextId = if (state.detail.kind == DetailTarget.Kind.Playlist) state.detail.id else null,
                            )
                        }
                    },
                    // 可重排列表里长按专属于拖拽手势，菜单只走 ⋯ 按钮（避免两种长按语义打架）
                    onLongClick = if (reorderable) null else ({
                        modal.show {
                            SongActionsSheet(
                                visible = modal.visible,
                                onDismiss = modal::requestDismiss,
                                state = state,
                                song = track,
                                playlistContextId = if (state.detail.kind == DetailTarget.Kind.Playlist) state.detail.id else null,
                            )
                        }
                    }),
                    onClick = {
                        state.playSong(track, data.tracks, data.title)
                        state.screen = GlassScreen.Player
                    },
                )
            }
            GlassSurface(
                modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 14.dp),
                level = GlassLevel.Md,
                shape = RoundedCornerShape(18.dp),
            ) {
                Column(Modifier.padding(vertical = 6.dp)) {
                    if (reorderable) {
                        // 拖拽域 = 自建歌单的 addedTracks 序；列表随页面滚动（autoscroll 关闭：
                        // 页面滚动容器含头图等额外内容，视口估算不成立，长歌单 v1 可接受）
                        ReorderableColumn(
                            count = data.tracks.size,
                            rowHeight = SongRowHeight,
                            scrollState = pageScroll,
                            onMove = { from, to -> state.movePlaylistTrack(state.detail.id, from, to) },
                        ) { index -> renderTrack(index) }
                    } else {
                        repeat(data.tracks.size) { index -> renderTrack(index) }
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
        // 单曲/集合面板打开时 Chrome 让位（与 PlayerScreen 控制台同策略）：
        // graphicsLayer alpha 只隐不卸——keepOnDispose 的迷你条飞行几何保持冻结；
        // ModalState 语义保持「退场动画结束（content 清空）后 Chrome 才复位」，
        // 面板滑出全程 Chrome 保持让位
        GlassChrome(
            modifier = Modifier.graphicsLayer { alpha = if (modal.isOpen) 0f else 1f },
            song = state.currentSong, playing = state.playing, progress = state.progressFraction,
            navSelected = 1,
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
                    f.beginEntry(state.currentSong, 1) { state.screen = it }
                } else {
                    state.screen = GlassScreen.Player
                }
            },
            onPlayPause = state::togglePlayPause,
            onNext = { state.next() },
        )
        }
    }
}
