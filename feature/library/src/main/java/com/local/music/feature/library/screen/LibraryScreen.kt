package com.local.music.feature.library.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.local.music.core.ui.components.ArtStyle
import com.local.music.core.ui.components.ArtThumb
import com.local.music.core.ui.components.CollectionActionsSheet
import com.local.music.core.ui.components.DemoCatalog
import com.local.music.core.ui.components.DemoSong
import com.local.music.core.ui.components.DetailTarget
import com.local.music.core.ui.components.GlassSearchBox
import com.local.music.core.ui.components.GlassSheet
import com.local.music.core.ui.components.GlassTabs
import com.local.music.core.ui.components.PageHeader
import com.local.music.core.ui.components.PlaylistCard
import com.local.music.core.ui.components.SongActionsSheet
import com.local.music.core.ui.components.SheetAction
import com.local.music.core.ui.components.SongRow
import com.local.music.core.ui.components.pressScale
import com.local.music.core.ui.state.AppState
import com.local.music.core.ui.state.GlassScreen
import com.local.music.core.ui.state.SongSort
import com.local.music.core.ui.theme.GlassIcons
import com.local.music.core.ui.theme.LocalGlassColors

/** 卡片长按的集合菜单数据：目标 + 展示标题 + 曲目（播放/插队用） */
private data class CollectionMenu(
    val target: DetailTarget,
    val title: String,
    val tracks: List<DemoSong>,
)

/**
 * 首页曲库：大标题 + 玻璃搜索 + tab 胶囊；6 个 tab 均接真实内容——
 * 歌曲 / 专辑 / 艺术家 / 文件夹（尊重排除设置）/ 流派 / 歌单，分组入口进参数化详情。
 * 歌曲行 ⋯/长按 = 单曲操作面板；专辑/艺术家/歌单卡长按 = 集合操作面板。
 */
@Composable
fun LibraryScreen(state: AppState, modifier: Modifier = Modifier) {
    var tab by remember { mutableIntStateOf(0) }
    var showSort by remember { mutableStateOf(false) }
    // 排序结果缓存：library 引用（重扫/缓存刷新才换新列表）或排序变化才重算，
    // 播放/暂停、切歌等高频重组不再触发整库 Collator 重排
    val songs = remember(state.library, state.songSort) { state.sortedSongs() }
    // 艺术家行的专辑数：整库一次性预计算（每专辑归其首曲艺术家，再按艺术家计数），
    // 避免艺术家 tab 每行重新整库分组（O(n×m)）；songSort 非必要键，与 songs 缓存同刻失效便于理解
    val albumCountByArtist = remember(state.library, state.songSort) {
        state.library.groupBy { it.album }
            .mapValues { (_, albumSongs) -> albumSongs.first().artist }
            .values.groupingBy { it }.eachCount()
    }
    var actionSong by remember { mutableStateOf<DemoSong?>(null) }
    var collectionMenu by remember { mutableStateOf<CollectionMenu?>(null) }

    Column(modifier.fillMaxSize().statusBarsPadding()) {
        // 整页懒加载：页头/搜索/tab 与各 tab 内容统一进 LazyColumn（原 Column+verticalScroll
        // 一次性组合整库行，长曲库下卡顿）；页头随内容滚动，视觉结构与原实现一致
        LazyColumn(Modifier.weight(1f)) {
            item {
                PageHeader(
                    title = "音乐",
                    actionIcon = if (state.themeMode == com.local.music.core.ui.state.ThemeMode.Dark) {
                        GlassIcons.Gear // 深色下的"太阳"造型
                    } else {
                        GlassIcons.Moon
                    },
                    onAction = state::toggleTheme,
                    actionLabel = "切换深浅色模式",
                    // 排序入口只对歌曲 tab 有意义：仅歌曲 tab 显示
                    action2Icon = if (tab == 0) GlassIcons.Sort else null,
                    onAction2 = { showSort = true },
                    action2Label = "排序歌曲",
                )
            }
            item {
                GlassSearchBox(
                    text = "搜索歌曲、专辑、艺术家", placeholder = true,
                    onClick = { state.screen = GlassScreen.Search },
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 2.dp, bottom = 12.dp),
                )
            }
            item {
                GlassTabs(
                    tabs = listOf("歌曲", "专辑", "艺术家", "文件夹", "流派", "歌单"),
                    selected = tab, onSelect = { tab = it },
                )
            }
            when (tab) {
                // 歌曲（按当前排序展示，播放入队同序）：key = 稳定歌曲 id，行可复用、排序变化随键移动
                0 -> items(songs, key = { it.id }) { song ->
                    SongRow(
                        song = song,
                        playing = song == state.currentSong,
                        wavePlaying = state.playing,
                        onMoreClick = { actionSong = song },
                        onLongClick = { actionSong = song },
                        onClick = { state.playSong(song, songs, "曲库") },
                    )
                }
                // 专辑
                1 -> state.albumGroups().chunked(2).forEach { pair ->
                    item(key = "album:${pair.first().key}") {
                        Row(
                            Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            pair.forEach { album ->
                                PlaylistCard(
                                    title = album.key, meta = album.meta, art = album.art,
                                    onClick = { state.openDetail(DetailTarget(DetailTarget.Kind.Album, album.key)) },
                                    onLongClick = {
                                        collectionMenu = CollectionMenu(
                                            DetailTarget(DetailTarget.Kind.Album, album.key), album.key, album.songs,
                                        )
                                    },
                                    modifier = Modifier.weight(1f),
                                    bitmap = album.coverBitmap,
                                )
                            }
                        }
                    }
                }
                // 艺术家（专辑数读整屏预计算的 albumCountByArtist，不再每行整库分组）
                2 -> state.artistGroups().forEach { group ->
                    item(key = "artist:${group.key}") {
                        GroupRow(
                            art = group.art,
                            title = group.key,
                            meta = "${group.songs.size} 首 · ${albumCountByArtist[group.key] ?: 0} 张专辑",
                            bitmap = group.coverBitmap,
                            onClick = { state.openDetail(DetailTarget(DetailTarget.Kind.Artist, group.key)) },
                            onLongClick = {
                                collectionMenu = CollectionMenu(
                                    DetailTarget(DetailTarget.Kind.Artist, group.key), group.key, group.songs,
                                )
                            },
                        )
                    }
                }
                // 文件夹（排除项不显示，与设置联动）
                3 -> state.folderGroups(state.excludedFolders).forEach { group ->
                    item(key = "folder:${group.key}") {
                        GroupRow(
                            art = group.art,
                            title = group.key,
                            meta = "${group.songs.size} 首",
                            bitmap = group.coverBitmap,
                            onClick = { state.openDetail(DetailTarget(DetailTarget.Kind.Folder, group.key)) },
                            onLongClick = {
                                collectionMenu = CollectionMenu(
                                    DetailTarget(DetailTarget.Kind.Folder, group.key), group.key, group.songs,
                                )
                            },
                        )
                    }
                }
                // 流派
                4 -> state.genreGroups().forEach { group ->
                    item(key = "genre:${group.key}") {
                        GroupRow(
                            art = group.art,
                            title = group.key,
                            meta = "${group.songs.size} 首",
                            bitmap = group.coverBitmap,
                            onClick = { state.openDetail(DetailTarget(DetailTarget.Kind.Genre, group.key)) },
                            onLongClick = {
                                collectionMenu = CollectionMenu(
                                    DetailTarget(DetailTarget.Kind.Genre, group.key), group.key, group.songs,
                                )
                            },
                        )
                    }
                }
                // 歌单
                5 -> (state.visibleBuiltInPlaylists() + state.userPlaylists).chunked(2).forEach { pair ->
                    item(key = "playlist:${pair.first().id}") {
                        Row(
                            Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            pair.forEach { playlist ->
                                PlaylistCard(
                                    title = playlist.title, meta = playlist.meta, art = playlist.art,
                                    onClick = { state.openDetail(DetailTarget(DetailTarget.Kind.Playlist, playlist.id)) },
                                    onLongClick = {
                                        collectionMenu = CollectionMenu(
                                            DetailTarget(DetailTarget.Kind.Playlist, playlist.id),
                                            playlist.title,
                                            state.resolvePlaylist(playlist.id)?.tracks.orEmpty(),
                                        )
                                    },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                }
            }
            // 底部避让：悬浮 Chrome（迷你条+导航 ≈ 138dp）+ 手势条
            item { Spacer(Modifier.navigationBarsPadding().height(146.dp)) }
        }
    }

    // 单曲操作面板（曲库歌曲 tab 的 ⋯ / 长按入口）
    actionSong?.let { song ->
        SongActionsSheet(
            visible = true,
            onDismiss = { actionSong = null },
            state = state,
            song = song,
        )
    }
    // 集合操作面板（专辑/艺术家/文件夹/流派/歌单卡的长按入口）
    collectionMenu?.let { menu ->
        CollectionActionsSheet(
            visible = true,
            onDismiss = { collectionMenu = null },
            state = state,
            target = menu.target,
            title = menu.title,
            tracks = menu.tracks,
            onPlayAll = { shuffle ->
                state.playCollection(menu.tracks, menu.title, shuffle)
                state.screen = GlassScreen.Player
            },
        )
    }

    // 排序面板（歌曲 tab，单选 emphasized 与玻璃强度/动效面板同语言）
    GlassSheet(visible = showSort, onDismiss = { showSort = false }, title = "排序歌曲") {
        SongSort.entries.forEach { sort ->
            SheetAction(
                icon = GlassIcons.Sort,
                label = sort.label,
                emphasized = state.songSort == sort,
                onClick = {
                    state.selectSongSort(sort)
                    showSort = false
                },
            )
        }
    }
}

/** 分组行（艺术家/文件夹/流派共用）；onLongClick = 集合操作菜单 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GroupRow(
    art: ArtStyle,
    title: String,
    meta: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    bitmap: androidx.compose.ui.graphics.ImageBitmap? = null,
    onLongClick: (() -> Unit)? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
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
        ArtThumb(art, Modifier.size(48.dp), RoundedCornerShape(12.dp), bordered = true, bitmap = bitmap)
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1,
                overflow = TextOverflow.Ellipsis, color = LocalGlassColors.current.textPrimary)
            Spacer(Modifier.height(2.dp))
            Text(meta, fontSize = 12.sp, color = LocalGlassColors.current.textTertiary,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(GlassIcons.ChevronRight, null, Modifier.size(16.dp), tint = LocalGlassColors.current.textFaint)
    }
}
