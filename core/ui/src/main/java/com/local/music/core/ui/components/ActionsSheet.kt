package com.local.music.core.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import com.local.music.core.ui.state.AppState
import com.local.music.core.ui.state.songFormatText
import com.local.music.core.ui.theme.GlassIcons
import com.local.music.core.ui.theme.LocalGlassColors
import com.local.music.core.ui.theme.auroraAccentColor

/**
 * 动作面板统一组件：全 App 的单曲菜单 / 集合菜单唯一实现——
 * 任何页面要菜单 = 挂组件，禁止复制面板代码（防屎山红线）。
 */

/** 单曲操作面板的二级面板 */
private enum class SongActionSub { None, AddTo, Info }

/**
 * 单曲操作面板：下一首播放 / 最后播放 / 收藏 / 加入歌单 / 查看专辑 / 查看艺术家 /
 * 从歌单移除（仅歌单详情内的自建歌单提供）/ 歌曲信息。
 * 曲库、搜索、歌单详情、播放页 More 共用；二级面板（加入歌单 / 歌曲信息）内聚于此。
 */
@Composable
fun SongActionsSheet(
    visible: Boolean,
    onDismiss: () -> Unit,
    state: AppState,
    song: DemoSong,
    playlistContextId: String? = null,
) {
    var sub by remember { mutableStateOf(SongActionSub.None) }
    LaunchedEffect(visible) {
        if (!visible) sub = SongActionSub.None
    }
    val canRemove = playlistContextId != null && playlistContextId.startsWith("user-") &&
        state.isSongInPlaylist(playlistContextId, song.id)
    val favorite = state.isFavorite(song.id)
    GlassSheet(visible = visible && sub == SongActionSub.None, onDismiss = onDismiss, title = song.title) {
        SheetAction(
            icon = GlassIcons.PlayNext,
            label = "下一首播放",
            onClick = {
                state.playNext(song)
                onDismiss()
            },
        )
        SheetAction(
            icon = GlassIcons.PlayLast,
            label = "最后播放",
            onClick = {
                state.playLast(song)
                onDismiss()
            },
        )
        SheetAction(
            icon = if (favorite) GlassIcons.HeartFilled else GlassIcons.Heart,
            label = if (favorite) "取消收藏" else "收藏",
            emphasized = favorite,
            onClick = {
                state.toggleFavorite(song.id)
                onDismiss()
            },
        )
        SheetAction(
            icon = GlassIcons.Plus,
            label = "加入歌单",
            subtitle = "添加到已有歌单",
            onClick = { sub = SongActionSub.AddTo },
        )
        SheetAction(
            icon = GlassIcons.Disc,
            label = "查看专辑",
            subtitle = song.album,
            onClick = {
                state.openDetail(DetailTarget(DetailTarget.Kind.Album, song.album))
                onDismiss()
            },
        )
        SheetAction(
            icon = GlassIcons.User,
            label = "查看艺术家",
            subtitle = song.artist,
            onClick = {
                state.openDetail(DetailTarget(DetailTarget.Kind.Artist, song.artist))
                onDismiss()
            },
        )
        if (canRemove) {
            SheetAction(
                icon = GlassIcons.MinusCircle,
                label = "从歌单移除",
                onClick = {
                    state.removeFromPlaylist(playlistContextId!!, song.id)
                    onDismiss()
                },
            )
        }
        SheetAction(
            icon = GlassIcons.Book,
            label = "歌曲信息",
            subtitle = songFormatText(
                song,
                if (song.id == state.currentSong.id) state.liveFormat else null,
            ),
            onClick = { sub = SongActionSub.Info },
        )
    }
    AddToPlaylistSheet(visible = visible && sub == SongActionSub.AddTo, onDismiss = onDismiss, state = state, song = song)
    SongInfoSheet(visible = visible && sub == SongActionSub.Info, onDismiss = onDismiss, state = state, song = song)
}

/** 「加入歌单」选择面板：列出内建 + 自建歌单，已含该曲的行显示"已添加"且点击不生效 */
@Composable
fun AddToPlaylistSheet(
    visible: Boolean,
    onDismiss: () -> Unit,
    state: AppState,
    song: DemoSong,
) {
    GlassSheet(visible = visible, onDismiss = onDismiss, title = "加入歌单") {
        (state.visibleBuiltInPlaylists() + state.userPlaylists).forEach { playlist ->
            val added = state.isSongInPlaylist(playlist.id, song.id)
            PlaylistPickRow(playlist = playlist, added = added, onClick = {
                // 已在歌单中的曲目点击不生效（保持面板打开，展示"已添加"反馈）
                if (state.addToPlaylist(playlist.id, song.id)) onDismiss()
            })
        }
    }
}

/** 「加入歌单」选择行：封面 + 歌单名 + 计数；added = 已含该曲目 */
@Composable
private fun PlaylistPickRow(playlist: DemoPlaylist, added: Boolean, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .pressScale(interactionSource, pressed = 0.98f)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ArtThumb(playlist.art, Modifier.size(40.dp), RoundedCornerShape(10.dp), bordered = true)
        Column(Modifier.weight(1f)) {
            Text(
                playlist.title, color = LocalGlassColors.current.textPrimary, fontSize = 15.sp,
                fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(playlist.meta, color = LocalGlassColors.current.textTertiary, fontSize = 11.5.sp)
        }
        if (added) {
            Text("已添加", color = LocalGlassColors.current.textFaint, fontSize = 11.5.sp)
        }
    }
}

/** 歌曲信息面板：标题 / 艺术家 / 专辑 / 时长 / 格式 / 文件夹 */
@Composable
fun SongInfoSheet(
    visible: Boolean,
    onDismiss: () -> Unit,
    state: AppState,
    song: DemoSong,
) {
    val live = if (song.id == state.currentSong.id) state.liveFormat else null
    GlassSheet(visible = visible, onDismiss = onDismiss, title = "歌曲信息") {
        InfoRow("标题", song.title)
        InfoRow("艺术家", song.artist)
        InfoRow("专辑", song.album)
        InfoRow("时长", song.duration)
        InfoRow("格式", songFormatText(song, live))
        InfoRow("文件夹", song.folder)
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = LocalGlassColors.current.textTertiary, fontSize = 13.sp, modifier = Modifier.width(72.dp))
        Text(value, color = LocalGlassColors.current.textPrimary, fontSize = 13.sp, modifier = Modifier.weight(1f))
    }
}

/**
 * 集合操作面板（歌单 / 专辑 / 艺术家 / 文件夹 / 流派详情与卡片长按共用）：
 * 全部播放 / 随机播放 / 下一首播放全部 / 最后播放全部 / 重命名歌单（仅自建）/
 * 删除歌单（歌单类，收藏/最近播放动态歌单除外）。
 * 播放行为经 [onPlayAll] 回调由调用方决定（含页面跳转）；删除后的兜底导航由详情页
 * data==null 分支自然完成，面板不感知路由。
 */
@Composable
fun CollectionActionsSheet(
    visible: Boolean,
    onDismiss: () -> Unit,
    state: AppState,
    target: DetailTarget,
    title: String,
    tracks: List<DemoSong>,
    onPlayAll: (shuffle: Boolean) -> Unit,
) {
    var showRename by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    LaunchedEffect(visible) {
        if (!visible) showRename = false
    }
    LaunchedEffect(showRename) {
        if (showRename) {
            newName = state.userPlaylists.firstOrNull { it.id == target.id }?.title.orEmpty()
        }
    }
    fun commitRename() {
        if (newName.isNotBlank()) {
            state.renamePlaylist(target.id, newName)
            onDismiss()
        }
    }
    GlassSheet(visible = visible && !showRename, onDismiss = onDismiss, title = title) {
        SheetAction(
            icon = GlassIcons.Play,
            label = "全部播放",
            subtitle = "${tracks.size} 首",
            onClick = {
                onDismiss()
                onPlayAll(false)
            },
        )
        SheetAction(
            icon = GlassIcons.Shuffle,
            label = "随机播放",
            subtitle = "从随机一首开始",
            onClick = {
                onDismiss()
                onPlayAll(true)
            },
        )
        SheetAction(
            icon = GlassIcons.PlayNext,
            label = "下一首播放全部",
            subtitle = "${tracks.size} 首插到当前曲之后",
            onClick = {
                state.playNextAll(tracks)
                onDismiss()
            },
        )
        SheetAction(
            icon = GlassIcons.PlayLast,
            label = "最后播放全部",
            subtitle = "${tracks.size} 首追加到队尾",
            onClick = {
                state.playLastAll(tracks)
                onDismiss()
            },
        )
        if (target.kind == DetailTarget.Kind.Playlist && target.id.startsWith("user-")) {
            SheetAction(
                icon = GlassIcons.Edit,
                label = "重命名歌单",
                onClick = { showRename = true },
            )
        }
        val isDynamic = target.kind == DetailTarget.Kind.Playlist &&
            (target.id == DemoCatalog.FavoritesId || target.id == DemoCatalog.RecentId)
        if (target.kind == DetailTarget.Kind.Playlist && !isDynamic) {
            SheetAction(
                icon = GlassIcons.Trash,
                label = "删除歌单",
                onClick = {
                    state.deletePlaylist(target.id)
                    onDismiss()
                },
            )
        }
    }
    // 重命名子面板（输入当前名，空白名不可提交）
    GlassSheet(visible = visible && showRename, onDismiss = onDismiss, title = "重命名歌单") {
        GlassTextField(
            value = newName,
            onValueChange = { newName = it },
            hint = "歌单名称",
            leading = null,
            onSearch = { commitRename() },
        )
        Spacer(Modifier.height(8.dp))
        SheetAction(
            icon = GlassIcons.Edit,
            label = "保存",
            subtitle = if (newName.isBlank()) "先输入名称" else "「${newName.trim()}」",
            emphasized = newName.isNotBlank(),
            onClick = { commitRename() },
        )
    }
}
