package com.local.music.feature.playlist.screen

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
import androidx.compose.runtime.CompositionLocalProvider
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
import com.local.music.core.ui.components.DemoCatalog
import com.local.music.core.ui.components.DetailTarget
import com.local.music.core.ui.components.GlassSheet
import com.local.music.core.ui.components.GlassSurface
import com.local.music.core.ui.components.GlassLevel
import com.local.music.core.ui.components.GlassTextField
import com.local.music.core.ui.components.LocalHazeState
import com.local.music.core.ui.components.PageHeader
import com.local.music.core.ui.components.PlaylistCard
import com.local.music.core.ui.components.SectionHeader
import com.local.music.core.ui.components.SheetAction
import com.local.music.core.ui.components.pressScale
import com.local.music.core.ui.state.AppState
import com.local.music.core.ui.state.GlassScreen
import com.local.music.core.ui.theme.GlassIcons
import com.local.music.core.ui.theme.LocalGlassColors
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState

/**
 * 歌单库：2×2 玻璃封面墙（常驻 + 用户自建，各自带参进详情）
 * + 「我喜欢的 / 最近播放」动态卡；＋ 新建歌单（命名面板）。
 */
@Composable
fun PlaylistScreen(state: AppState, modifier: Modifier = Modifier) {
    var showCreate by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }

    val hazeState = rememberHazeState()
    CompositionLocalProvider(LocalHazeState provides hazeState) {
        Column(modifier.fillMaxSize().statusBarsPadding()) {
            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .hazeSource(hazeState),
            ) {
            PageHeader(title = "歌单", actionIcon = GlassIcons.Plus, onAction = { showCreate = true })
            // 玻璃封面墙
            (DemoCatalog.playlists + state.userPlaylists).chunked(2).forEach { pair ->
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
                        "我喜欢的音乐", "${state.favorites.size} 首", ArtStyle.Room,
                    ) { state.openDetail(DetailTarget(DetailTarget.Kind.Playlist, DemoCatalog.FavoritesId)) }
                    FavoriteRow(
                        "最近播放", "${state.playedIds.size} 首", ArtStyle.Afternoon,
                    ) { state.openDetail(DetailTarget(DetailTarget.Kind.Playlist, DemoCatalog.RecentId)) }
                }
            }
            Spacer(Modifier.height(18.dp))
            }
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
}

@Composable
private fun FavoriteRow(title: String, meta: String, art: ArtStyle, onClick: () -> Unit) {
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
