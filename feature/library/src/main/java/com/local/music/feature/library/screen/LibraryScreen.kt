package com.local.music.feature.library.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.local.music.core.ui.components.ArtStyle
import com.local.music.core.ui.components.ArtThumb
import com.local.music.core.ui.components.DemoCatalog
import com.local.music.core.ui.components.DemoData
import com.local.music.core.ui.components.DetailTarget
import com.local.music.core.ui.components.GlassSearchBox
import com.local.music.core.ui.components.GlassTabs
import com.local.music.core.ui.components.PageHeader
import com.local.music.core.ui.components.PlaylistCard
import com.local.music.core.ui.components.SongRow
import com.local.music.core.ui.components.pressScale
import com.local.music.core.ui.state.AppState
import com.local.music.core.ui.state.GlassScreen
import com.local.music.core.ui.theme.GlassIcons
import com.local.music.core.ui.theme.LocalGlassColors

/**
 * 首页曲库：大标题 + 玻璃搜索 + tab 胶囊；6 个 tab 均接真实内容——
 * 歌曲 / 专辑 / 艺术家 / 文件夹（尊重排除设置）/ 流派 / 歌单，分组入口进参数化详情。
 */
@Composable
fun LibraryScreen(state: AppState, modifier: Modifier = Modifier) {
    var tab by remember { mutableIntStateOf(0) }
    val librarySongs = state.library

    Column(modifier.fillMaxSize().statusBarsPadding()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            PageHeader(
                title = "音乐",
                actionIcon = if (state.themeMode == com.local.music.core.ui.state.ThemeMode.Dark) {
                    GlassIcons.Gear // 深色下的"太阳"造型
                } else {
                    GlassIcons.Moon
                },
                onAction = state::toggleTheme,
                actionLabel = "切换深浅色模式",
            )
            GlassSearchBox(
                text = "搜索歌曲、专辑、艺术家", placeholder = true,
                onClick = { state.screen = GlassScreen.Search },
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 2.dp, bottom = 12.dp),
            )
            GlassTabs(
                tabs = listOf("歌曲", "专辑", "艺术家", "文件夹", "流派", "歌单"),
                selected = tab, onSelect = { tab = it },
            )
            when (tab) {
                // 歌曲
                0 -> librarySongs.forEach { song ->
                    SongRow(
                        song = song,
                        playing = song == state.currentSong,
                        onClick = { state.playSong(song, librarySongs) },
                    )
                }
                // 专辑
                1 -> state.albumGroups().chunked(2).forEach { pair ->
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
                // 艺术家
                2 -> state.artistGroups().forEach { group ->
                    GroupRow(
                        art = group.art,
                        title = group.key,
                        meta = "${group.songs.size} 首 · ${state.albumGroups().count { it.songs.first().artist == group.key }} 张专辑",
                        bitmap = group.coverBitmap,
                        onClick = { state.openDetail(DetailTarget(DetailTarget.Kind.Artist, group.key)) },
                    )
                }
                // 文件夹（排除项不显示，与设置联动）
                3 -> state.folderGroups(state.excludedFolders).forEach { group ->
                    GroupRow(
                        art = group.art,
                        title = group.key,
                        meta = "${group.songs.size} 首",
                        bitmap = group.coverBitmap,
                        onClick = { state.openDetail(DetailTarget(DetailTarget.Kind.Folder, group.key)) },
                    )
                }
                // 流派
                4 -> state.genreGroups().forEach { group ->
                    GroupRow(
                        art = group.art,
                        title = group.key,
                        meta = "${group.songs.size} 首",
                        bitmap = group.coverBitmap,
                        onClick = { state.openDetail(DetailTarget(DetailTarget.Kind.Genre, group.key)) },
                    )
                }
                // 歌单
                5 -> (DemoCatalog.playlists + state.userPlaylists).chunked(2).forEach { pair ->
                    Row(
                        Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        pair.forEach { playlist ->
                            PlaylistCard(
                                title = playlist.title, meta = playlist.meta, art = playlist.art,
                                onClick = { state.openDetail(DetailTarget(DetailTarget.Kind.Playlist, playlist.id)) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(18.dp))
        }
    }
}

/** 分组行（艺术家/文件夹/流派共用） */
@Composable
private fun GroupRow(
    art: ArtStyle,
    title: String,
    meta: String,
    onClick: () -> Unit,
    bitmap: androidx.compose.ui.graphics.ImageBitmap? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp)
            .clip(RoundedCornerShape(14.dp))
            .pressScale(interactionSource, pressed = 0.98f)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
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
