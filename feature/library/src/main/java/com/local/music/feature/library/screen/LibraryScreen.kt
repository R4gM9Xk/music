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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.local.music.core.ui.components.ArtStyle
import com.local.music.core.ui.components.ArtThumb
import com.local.music.core.ui.components.ChromeBottomInset
import com.local.music.core.ui.components.CollectionActionsSheet
import com.local.music.core.ui.components.DemoCatalog
import com.local.music.core.ui.components.DemoSong
import com.local.music.core.ui.components.DetailTarget
import com.local.music.core.ui.components.GlassLevel
import com.local.music.core.ui.components.GlassSearchBox
import com.local.music.core.ui.components.GlassSurface
import com.local.music.core.ui.components.GlassTabs
import com.local.music.core.ui.components.LocalModalState
import com.local.music.core.ui.components.ModalSheet
import com.local.music.core.ui.components.PageHeader
import com.local.music.core.ui.components.PlaylistCard
import com.local.music.core.ui.components.SettingRow
import com.local.music.core.ui.components.SongActionsSheet
import com.local.music.core.ui.components.SheetAction
import com.local.music.core.ui.components.SongRow
import com.local.music.core.ui.components.pressScale
import com.local.music.core.ui.state.AppState
import com.local.music.core.ui.state.GlassScreen
import com.local.music.core.ui.state.SongSort
import com.local.music.core.ui.theme.GlassIcons
import com.local.music.core.ui.theme.LocalGlassColors

/**
 * 首页曲库：大标题 + 玻璃搜索 + tab 胶囊；6 个 tab 均接真实内容——
 * 歌曲 / 专辑 / 艺术家 / 文件夹（尊重排除设置）/ 流派 / 歌单，分组入口进参数化详情。
 * 歌曲行 ⋯/长按 = 单曲操作面板；专辑/艺术家/歌单卡长按 = 集合操作面板。
 * 全部面板经 LocalModalState.show 挂 GlassApp 根层宿主（屏内不再渲染 GlassSheet）。
 *
 * [audioDenied] / [onRequestAudioPermission]：READ_MEDIA_AUDIO 被拒后的应用内再引导入口。
 * 默认值让其它调用点（预览、测试）零改动；GlassApp 会传入真实权限态与系统授权弹窗。
 */
@Composable
fun LibraryScreen(
    state: AppState,
    modifier: Modifier = Modifier,
    audioDenied: Boolean = false,
    onRequestAudioPermission: () -> Unit = {},
) {
    // P3-39：tab 与滚动位置改 rememberSaveable（进程内重建可恢复）。注意 AnimatedContent 换屏
    // 会销毁内容、rememberSaveable 不保留——真正跨屏保留需把这两个状态提到 AppState，属后续项
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val listState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }
    // 模态面板宿主（GlassApp 根层）：排序/单曲/集合面板经 show 挂载
    val modal = LocalModalState.current
    // 排序结果缓存：library 引用（重扫/缓存刷新才换新列表）或排序变化才重算，
    // 播放/暂停、切歌等高频重组不再触发整库 Collator 重排。
    // **P2-④：scanFilterShort 必须进 key** —— sortedSongs() 走 visibleLibrary()，
    // 而后者按该开关做输出层过滤；漏了它，拨动「扫描过滤」后曲库页仍显示旧列表
    // （设置页开关与列表互相打脸），直到某次重扫换了 library 引用才"忽然"生效。
    val songs = remember(state.library, state.songSort, state.scanFilterShort) { state.sortedSongs() }
    // 艺术家行的专辑数：改读 AppState 的分组缓存（与艺术家详情页 meta 同源同口径），
    // 不再在屏内自行整库 groupBy——一份派生两处各算一遍既费时又可能与详情页显示不一致。
    // 仍需 remember 一次：albumCountByArtist() 每次调用要做一次 `cachedLib === lib` 校验，
    // 让它进 LazyColumn item 的每次组合不划算。
    val albumCountByArtist = remember(state.library) { state.albumCountByArtist() }

    // 单曲操作面板（面板挂根层宿主；song 锁定动作触发时刻的曲目，语义同各屏）
    fun songActions(song: DemoSong) {
        modal.show {
            SongActionsSheet(
                visible = modal.visible,
                onDismiss = modal::requestDismiss,
                state = state,
                song = song,
            )
        }
    }
    // 集合操作面板（目标/标题/曲目锁定长按时刻值，播放行为与旧实现一致：入队后进播放页）
    fun collectionActions(target: DetailTarget, title: String, tracks: List<DemoSong>) {
        modal.show {
            CollectionActionsSheet(
                visible = modal.visible,
                onDismiss = modal::requestDismiss,
                state = state,
                target = target,
                title = title,
                tracks = tracks,
                onPlayAll = { shuffle ->
                    state.playCollection(tracks, title, shuffle)
                    state.screen = GlassScreen.Player
                },
            )
        }
    }

    Column(modifier.fillMaxSize().statusBarsPadding()) {
        // 整页懒加载：页头/搜索/tab 与各 tab 内容统一进 LazyColumn（原 Column+verticalScroll
        // 一次性组合整库行，长曲库下卡顿）；页头随内容滚动，视觉结构与原实现一致
        LazyColumn(Modifier.weight(1f), state = listState) {
            item {
                PageHeader(
                    title = "音乐",
                    // P3-4/P3-27：两种主题统一用 Moon（月亮=当前深浅态，点击切另一态）；
                    // 原 Gear 实为太阳形，深色下语义混乱。理想是新增 Sun 图标，属后续项
                    actionIcon = GlassIcons.Moon,
                    onAction = state::toggleTheme,
                    actionLabel = "切换深浅色模式",
                    // 排序入口只对歌曲 tab 有意义：仅歌曲 tab 显示
                    action2Icon = if (tab == 0) GlassIcons.Sort else null,
                    onAction2 = {
                        modal.show {
                            ModalSheet(modal, "排序歌曲") {
                                SongSort.entries.forEach { sort ->
                                    SheetAction(
                                        icon = GlassIcons.Sort,
                                        label = sort.label,
                                        emphasized = state.songSort == sort,
                                        onClick = {
                                            state.selectSongSort(sort)
                                            modal.requestDismiss()
                                        },
                                    )
                                }
                            }
                        }
                    },
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
                // 歌曲（按当前排序展示，播放入队同序）：key = 稳定歌曲 id，行可复用、排序变化随键移动。
                // 空曲库单独给一条空态（权限被拒时是应用内唯一的「去授权」入口）
                0 -> if (songs.isEmpty()) {
                    item(key = "library-empty") {
                        LibraryEmptyState(audioDenied, onRequestAudioPermission)
                    }
                } else {
                    items(songs, key = { it.id }) { song ->
                        SongRow(
                            song = song,
                            playing = song.id == state.currentSong.id,
                            wavePlaying = state.playing,
                            onMoreClick = { songActions(song) },
                            onLongClick = { songActions(song) },
                            onClick = { state.playSong(song, songs, "曲库") },
                        )
                    }
                }
                // 专辑（P3-38：空 tab 给空态；P2-2：奇数行补 Spacer，唯一卡不再撑满整行）
                1 -> {
                    val groups = state.albumGroups()
                    if (groups.isEmpty()) {
                        item(key = "tab-empty-1") { TabEmptyState("还没有专辑") }
                    } else {
                        groups.chunked(2).forEach { pair ->
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
                                                collectionActions(
                                                    DetailTarget(DetailTarget.Kind.Album, album.key), album.key, album.songs,
                                                )
                                            },
                                            modifier = Modifier.weight(1f),
                                            bitmap = album.coverBitmap,
                                            artworkUri = album.coverUri,
                                        )
                                    }
                                    // 奇数行：右列补等重占位，避免唯一卡 weight 撑满整行（封面高度翻倍）
                                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }
                // 艺术家（专辑数读整屏预计算的 albumCountByArtist，不再每行整库分组）
                2 -> {
                    val groups = state.artistGroups()
                    if (groups.isEmpty()) {
                        item(key = "tab-empty-2") { TabEmptyState("还没有艺术家") }
                    } else {
                        groups.forEach { group ->
                            item(key = "artist:${group.key}") {
                                GroupRow(
                                    art = group.art,
                                    title = group.key,
                                    meta = "${group.songs.size} 首 · ${albumCountByArtist[group.key] ?: 0} 张专辑",
                                    bitmap = group.coverBitmap,
                                    artworkUri = group.coverUri,
                                    onClick = { state.openDetail(DetailTarget(DetailTarget.Kind.Artist, group.key)) },
                                    onLongClick = {
                                        collectionActions(
                                            DetailTarget(DetailTarget.Kind.Artist, group.key), group.key, group.songs,
                                        )
                                    },
                                )
                            }
                        }
                    }
                }
                // 文件夹（排除项不显示，与设置联动）
                3 -> {
                    val groups = state.folderGroups(state.excludedFolders)
                    if (groups.isEmpty()) {
                        item(key = "tab-empty-3") { TabEmptyState("还没有文件夹") }
                    } else {
                        groups.forEach { group ->
                            item(key = "folder:${group.key}") {
                                GroupRow(
                                    art = group.art,
                                    title = group.key,
                                    meta = "${group.songs.size} 首",
                                    bitmap = group.coverBitmap,
                                    artworkUri = group.coverUri,
                                    onClick = { state.openDetail(DetailTarget(DetailTarget.Kind.Folder, group.key)) },
                                    onLongClick = {
                                        collectionActions(
                                            DetailTarget(DetailTarget.Kind.Folder, group.key), group.key, group.songs,
                                        )
                                    },
                                )
                            }
                        }
                    }
                }
                // 流派
                4 -> {
                    val groups = state.genreGroups()
                    if (groups.isEmpty()) {
                        item(key = "tab-empty-4") { TabEmptyState("还没有流派") }
                    } else {
                        groups.forEach { group ->
                            item(key = "genre:${group.key}") {
                                GroupRow(
                                    art = group.art,
                                    title = group.key,
                                    meta = "${group.songs.size} 首",
                                    bitmap = group.coverBitmap,
                                    artworkUri = group.coverUri,
                                    onClick = { state.openDetail(DetailTarget(DetailTarget.Kind.Genre, group.key)) },
                                    onLongClick = {
                                        collectionActions(
                                            DetailTarget(DetailTarget.Kind.Genre, group.key), group.key, group.songs,
                                        )
                                    },
                                )
                            }
                        }
                    }
                }
                // 歌单（P2-22：userPlaylists 先经 resolvePlaylist 实时解析——计数同详情页口径、
                // 失效 id 被过滤；内建歌单 visibleBuiltInPlaylists() 已 resolve 过，幂等可重复）
                5 -> {
                    val wallPlaylists = (state.visibleBuiltInPlaylists() + state.userPlaylists)
                        .map { state.resolvePlaylist(it.id) ?: it } // 计数实时化 + 失效 id 过滤
                    if (wallPlaylists.isEmpty()) {
                        item(key = "tab-empty-5") { TabEmptyState("还没有歌单") }
                    } else {
                        wallPlaylists.chunked(2).forEach { pair ->
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
                                                collectionActions(
                                                    DetailTarget(DetailTarget.Kind.Playlist, playlist.id),
                                                    playlist.title,
                                                    playlist.tracks, // resolve 过的 tracks，与 meta 计数同源
                                                )
                                            },
                                            modifier = Modifier.weight(1f),
                                            // P3-43：封面取首曲（详情页同口径），不再恒为占位
                                            artworkUri = playlist.tracks.firstOrNull()?.artworkUri,
                                        )
                                    }
                                    // 奇数行：右列补等重占位，避免唯一卡 weight 撑满整行（封面高度翻倍）
                                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }
            }
            // 底部避让：悬浮 Chrome（迷你条+导航）+ 手势条。高度取自 core/ui 单点常量，
            // Chrome 高度调整时三个主屏自动跟随（原先各自硬编码 146.dp，漏改一处最后一行就被盖住）
            item { Spacer(Modifier.navigationBarsPadding().height(ChromeBottomInset)) }
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
    artworkUri: String? = null,
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
        ArtThumb(
            art, Modifier.size(48.dp), RoundedCornerShape(12.dp),
            bordered = true, bitmap = bitmap, artworkUri = artworkUri,
        )
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

/**
 * 各 tab 通用空态（P3-38）：居中提示，样式对齐歌曲 tab 的中性空态。
 */
@Composable
private fun TabEmptyState(text: String) {
    Text(
        text,
        color = LocalGlassColors.current.textTertiary,
        fontSize = 13.sp,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(28.dp),
    )
}

/**
 * 歌曲 tab 的空态。
 *
 * 为什么需要它：READ_MEDIA_AUDIO 被拒后 GlassApp 只做 abandonPendingPlayback + markLibrarySettled，
 * 曲库页就只是一个空列表——用户看不到任何「为什么空」和「怎么补救」，唯一的再授权入口在设置页，
 * 而这正是权限被拒后最容易流失的路径。所以在空态里直接给出可点的授权入口。
 *
 * 授权成功后的重扫由 GlassApp 的权限回调负责，这里只负责发起请求，不做本地状态猜测。
 */
@Composable
private fun LibraryEmptyState(audioDenied: Boolean, onRequestAudioPermission: () -> Unit) {
    if (audioDenied) {
        // 复用设置页同款 SettingRow：同一件事（音频权限）在两个入口的视觉与文案保持一致
        GlassSurface(
            modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 12.dp),
            level = GlassLevel.Md,
            shape = RoundedCornerShape(18.dp),
        ) {
            SettingRow(
                GlassIcons.Mic,
                "未获得音乐与音频访问权限",
                "点击授权后即可扫描本地曲库",
                onClick = onRequestAudioPermission,
            )
        }
    } else {
        // 中性空态：没权限问题、就是库里没歌（新装/全被过滤）。不新造交互，只提示去设置页扫描
        Text(
            "曲库里还没有歌曲 · 去设置页扫描音乐",
            color = LocalGlassColors.current.textTertiary,
            fontSize = 13.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 28.dp),
        )
    }
}
