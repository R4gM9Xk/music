package com.local.music.feature.playlist.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.local.music.core.ui.components.ArtStyle
import com.local.music.core.ui.components.ArtThumb
import com.local.music.core.ui.components.CollectionActionsSheet
import com.local.music.core.ui.components.DemoCatalog
import com.local.music.core.ui.components.DemoSong
import com.local.music.core.ui.components.DetailTarget
import com.local.music.core.ui.components.GlassSheet
import com.local.music.core.ui.components.GlassSurface
import com.local.music.core.ui.components.GlassLevel
import com.local.music.core.ui.components.GlassTextField
import com.local.music.core.ui.components.PageHeader
import com.local.music.core.ui.components.PlaylistCard
import com.local.music.core.ui.components.SectionHeader
import com.local.music.core.ui.components.SheetAction
import com.local.music.core.ui.components.pressScale
import com.local.music.core.ui.state.AppState
import com.local.music.core.ui.state.GlassScreen
import com.local.music.core.ui.theme.GlassIcons
import com.local.music.core.ui.theme.LocalGlassColors

/**
 * 歌单库：2×2 玻璃封面墙（常驻 + 用户自建，各自带参进详情，长按 = 集合操作面板）
 * + 「我喜欢的 / 最近播放」动态卡；＋ 新建歌单（命名面板）。
 */
@Composable
fun PlaylistScreen(state: AppState, modifier: Modifier = Modifier) {
    var showCreate by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var collectionMenu by remember { mutableStateOf<CollectionMenuData?>(null) }

    // 无自有 haze source：本屏内容与面板均无 hazeEffect 消费（面板挂 app 级），整屏录制纯开销
    Column(modifier.fillMaxSize().statusBarsPadding()) {
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
            PageHeader(title = "歌单", actionIcon = GlassIcons.Plus, onAction = { showCreate = true })
            // 玻璃封面墙（内建歌单已被删除的不再显示）
            (state.visibleBuiltInPlaylists() + state.userPlaylists).chunked(2).forEach { pair ->
                Row(
                    Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    pair.forEach { playlist ->
                        PlaylistCard(
                            title = playlist.title, meta = playlist.meta, art = playlist.art,
                            onClick = {
                                state.openDetail(DetailTarget(DetailTarget.Kind.Playlist, playlist.id))
                            },
                            onLongClick = {
                                collectionMenu = CollectionMenuData(
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

            SectionHeader("我喜欢的")
            GlassSurface(
                modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 14.dp),
                level = GlassLevel.Md,
                shape = RoundedCornerShape(18.dp),
            ) {
                Column {
                    FavoriteRow(
                        "我喜欢的音乐", "${state.resolvedFavorites.size} 首", ArtStyle.Room,
                        onClick = { state.openDetail(DetailTarget(DetailTarget.Kind.Playlist, DemoCatalog.FavoritesId)) },
                        onLongClick = {
                            collectionMenu = CollectionMenuData(
                                DetailTarget(DetailTarget.Kind.Playlist, DemoCatalog.FavoritesId),
                                "我喜欢的音乐",
                                state.resolvedFavorites,
                            )
                        },
                    )
                    FavoriteRow(
                        // 计数与详情页同源（resolvePlaylist 已过滤失效 id）：playedIds 未过滤会虚高
                        "最近播放", "${state.resolvePlaylist(DemoCatalog.RecentId)?.tracks?.size ?: 0} 首", ArtStyle.Afternoon,
                        onClick = { state.openDetail(DetailTarget(DetailTarget.Kind.Playlist, DemoCatalog.RecentId)) },
                        onLongClick = {
                            collectionMenu = CollectionMenuData(
                                DetailTarget(DetailTarget.Kind.Playlist, DemoCatalog.RecentId),
                                "最近播放",
                                state.resolvePlaylist(DemoCatalog.RecentId)?.tracks.orEmpty(),
                            )
                        },
                    )
                }
            }
            Spacer(Modifier.navigationBarsPadding().height(146.dp))
            }
    }

    // 新建歌单面板
    GlassSheet(visible = showCreate, onDismiss = { showCreate = false }, title = "新建歌单") {
        GlassTextField(
            value = newName,
            onValueChange = { newName = it },
            hint = "歌单名称",
            leading = null,
            onSearch = {
                if (newName.isNotBlank()) {
                    state.createPlaylist(newName)
                    newName = ""
                    showCreate = false
                }
            },
        )
        Spacer(Modifier.height(8.dp))
        SheetAction(
            icon = GlassIcons.Plus,
            label = "创建歌单",
            subtitle = if (newName.isBlank()) "先输入名称" else "「${newName.trim()}」",
            emphasized = newName.isNotBlank(),
            onClick = {
                if (newName.isNotBlank()) {
                    state.createPlaylist(newName)
                    newName = ""
                    showCreate = false
                }
            },
        )
    }

    // 集合操作面板（歌单卡 / 动态卡的长按入口）
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
}

/** 卡片长按的集合菜单数据（同 LibraryScreen 的局部形态，歌单墙独立持有） */
private data class CollectionMenuData(
    val target: DetailTarget,
    val title: String,
    val tracks: List<DemoSong>,
)

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun FavoriteRow(
    title: String,
    meta: String,
    art: ArtStyle,
    onClick: () -> Unit,
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
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp)
            .clip(RoundedCornerShape(14.dp))
            .pressScale(interactionSource, pressed = 0.98f)
            .then(rowClickable)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ArtThumb(art, Modifier.size(48.dp), RoundedCornerShape(8.dp), bordered = true)
        Column(Modifier.weight(1f)) {
            Text(title, color = LocalGlassColors.current.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(2.dp))
            Text(meta, color = LocalGlassColors.current.textTertiary, fontSize = 12.sp)
        }
        Icon(GlassIcons.ChevronRight, null, Modifier.size(16.dp),
            tint = LocalGlassColors.current.textFaint)
    }
}
