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
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.local.music.core.ui.components.ArtStyle
import com.local.music.core.ui.components.ArtThumb
import com.local.music.core.ui.components.ChromeBottomInset
import com.local.music.core.ui.components.CollectionActionsSheet
import com.local.music.core.ui.components.DemoCatalog
import com.local.music.core.ui.components.DemoSong
import com.local.music.core.ui.components.DetailTarget
import com.local.music.core.ui.components.GlassSurface
import com.local.music.core.ui.components.GlassLevel
import com.local.music.core.ui.components.GlassTextField
import com.local.music.core.ui.components.LocalModalState
import com.local.music.core.ui.components.ModalSheet
import com.local.music.core.ui.components.PageHeader
import com.local.music.core.ui.components.PlaylistCard
import com.local.music.core.ui.components.SectionHeader
import com.local.music.core.ui.components.SheetAction
import com.local.music.core.ui.components.rowPressFeedback
import com.local.music.core.ui.state.AppState
import com.local.music.core.ui.state.GlassScreen
import com.local.music.core.ui.theme.GlassIcons
import com.local.music.core.ui.theme.GlassText
import com.local.music.core.ui.theme.LocalGlassColors
import com.local.music.core.ui.theme.R

/**
 * 歌单库：2×2 玻璃封面墙（常驻 + 用户自建，各自带参进详情，长按 = 集合操作面板）
 * + 「我喜欢的 / 最近播放」动态卡；＋ 新建歌单（命名面板）。
 * 全部面板经 LocalModalState.show 挂 GlassApp 根层宿主（屏内不再渲染 GlassSheet）。
 */
@Composable
fun PlaylistScreen(state: AppState, modifier: Modifier = Modifier) {
    // 命名草稿：remember 于本屏，面板 show 后经闭包活读写（重开保留上次草稿，同旧行为）
    var newName by remember { mutableStateOf("") }
    // P3-39：滚动位置改 rememberSaveable（进程内重建可恢复）。注意 AnimatedContent 换屏
    // 会销毁内容、rememberSaveable 不保留——真正跨屏保留需把该状态提到 AppState，属后续项
    val scrollState = rememberSaveable(saver = ScrollState.Saver) { ScrollState(0) }
    // 模态面板宿主（GlassApp 根层）：新建歌单/集合面板经 show 挂载
    val modal = LocalModalState.current

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
                    state.openPlayer()
                },
            )
        }
    }

    // 无自有 haze source：本屏内容与面板均无 hazeEffect 消费（面板挂 app 级），整屏录制纯开销
    Column(modifier.fillMaxSize().statusBarsPadding()) {
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(scrollState),
        ) {
            // 读屏（无障碍 P0-4）：**必须传 actionLabel**。
            // 旧实现只给了 actionIcon + onAction，actionLabel 为 null，于是 PageHeader 内部
            // 连 semantics 都不挂 —— 「新建歌单」这个入口对读屏是一个哑节点（只念「按钮」）。
            // PageHeader 现在有「标题，操作 1」的兜底描述，所以不漏但难听；
            // 这里补上精确文案才是正解（兜底是防遗漏的网，不是终态方案）。
            PageHeader(title = "歌单", actionIcon = GlassIcons.Plus, onAction = {
                // 新建歌单面板（含输入框：GlassSheet 的 imePadding 让其浮在键盘之上，机制不动）
                modal.show {
                    ModalSheet(modal, "新建歌单") {
                        // 重名预检（trim 忽略大小写，含内建歌单标题）：重名时副标题提示且不可提交
                        val duplicate = newName.trim().isNotEmpty() && state.playlistTitleExists(newName)
                        GlassTextField(
                            value = newName,
                            onValueChange = { newName = it },
                            hint = "歌单名称",
                            leading = null,
                            onSearch = {
                                if (newName.isNotBlank() && !duplicate) {
                                    val created = state.createPlaylist(newName)
                                    newName = ""
                                    modal.requestDismiss()
                                    // 增-U1：创建后**直达新歌单详情**（旧实现只收面板，
                                    // 用户在歌单墙里要自己找到新卡再点开才有「＋添加歌曲」，
                                    // 空歌单尤其没有下一步提示）。详情页顶栏 ＋ 就是唯一的
                                    // 「加歌」入口，直达等于把下一步摆在用户面前。
                                    // detailReturn 由 openDetail 记为当前屏（歌单页），返回即回原位。
                                    state.openDetail(DetailTarget(DetailTarget.Kind.Playlist, created.id))
                                }
                            },
                        )
                        Spacer(Modifier.height(8.dp))
                        SheetAction(
                            icon = GlassIcons.Plus,
                            label = "创建歌单",
                            subtitle = when {
                                newName.isBlank() -> "先输入名称"
                                duplicate -> "已有同名歌单"
                                else -> "「${newName.trim()}」"
                            },
                            emphasized = newName.isNotBlank() && !duplicate,
                            onClick = {
                                if (newName.isNotBlank() && !duplicate) {
                                    val created = state.createPlaylist(newName)
                                    newName = ""
                                    modal.requestDismiss()
                                    // 同 onSearch 分支：创建后直达详情（见上注释）
                                    state.openDetail(DetailTarget(DetailTarget.Kind.Playlist, created.id))
                                }
                            },
                        )
                    }
                }
            }, actionLabel = "新建歌单")
            // 玻璃封面墙（内建歌单已被删除的不再显示）。P2-22：userPlaylists 先经 resolvePlaylist
            // 实时解析——计数与详情页同口径、失效 id 被过滤；内建歌单 visibleBuiltInPlaylists()
            // 已 resolve 过，重复 resolve 幂等（songIndex 缓存 O(1)），可接受
            val wallPlaylists = (state.visibleBuiltInPlaylists() + state.userPlaylists)
                .map { state.resolvePlaylist(it.id) ?: it } // 计数实时化 + 失效 id 过滤
            wallPlaylists.chunked(2).forEach { pair ->
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
                    // P2-2：奇数行右列补等重占位，避免唯一卡 weight 撑满整行（封面高度翻倍）
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }

            SectionHeader("我喜欢的")
            GlassSurface(
                modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 14.dp),
                level = GlassLevel.Md,
                shape = RoundedCornerShape(R.Card18.dp),
            ) {
                Column {
                    FavoriteRow(
                        "我喜欢的音乐", "${state.resolvedFavorites.size} 首", ArtStyle.Room,
                        onClick = { state.openDetail(DetailTarget(DetailTarget.Kind.Playlist, DemoCatalog.FavoritesId)) },
                        onLongClick = {
                            collectionActions(
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
                            collectionActions(
                                DetailTarget(DetailTarget.Kind.Playlist, DemoCatalog.RecentId),
                                "最近播放",
                                state.resolvePlaylist(DemoCatalog.RecentId)?.tracks.orEmpty(),
                            )
                        },
                    )
                    FavoriteRow(
                        // D1：按完播次数取前 50，计数同源 resolvePlaylist（失效 id 已过滤）
                        "最常播放", "${state.resolvePlaylist(DemoCatalog.MostPlayedId)?.tracks?.size ?: 0} 首", ArtStyle.Tape,
                        onClick = { state.openDetail(DetailTarget(DetailTarget.Kind.Playlist, DemoCatalog.MostPlayedId)) },
                        onLongClick = {
                            collectionActions(
                                DetailTarget(DetailTarget.Kind.Playlist, DemoCatalog.MostPlayedId),
                                "最常播放",
                                state.resolvePlaylist(DemoCatalog.MostPlayedId)?.tracks.orEmpty(),
                            )
                        },
                    )
                }
            }
            // 底部避让：悬浮 Chrome + 手势条；高度取自 core/ui 单点常量（原先与另外两个主屏
            // 各自硬编码 146.dp，Chrome 高度一变就得同步三处，漏改一处最后一行被迷你条盖住）
            Spacer(Modifier.navigationBarsPadding().height(ChromeBottomInset))
            }
    }
}

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
            .clip(RoundedCornerShape(R.Card14.dp))
            // A6：行级缩放改按压底色（与 SongRow/SettingRow/GroupRow 统一，见 rowPressFeedback
            // 的 KDoc）。旧 pressScale(0.98) 在嵌于 GlassSurface 的短行组里尤其突兀——
            // 缩放的是行，卡片外壳不动，按下时行像是从卡里 "浮起来" 再落回去。
            .rowPressFeedback(interactionSource)
            .then(rowClickable)
            // 读屏（无障碍 P0-4）：整行可点（= 进入该动态歌单详情），但 clickable /
            // combinedClickable 不带 role，读屏只会念「收藏，12 首」而不会提示「按钮」。
            // 补 role 后读屏念「收藏，12 首，按钮，双击激活」。
            // 不写 contentDescription：title/meta 已是可见文字，mergeDescendants 自动合并。
            .semantics { this.role = Role.Button }
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ArtThumb(art, Modifier.size(48.dp), RoundedCornerShape(R.Thumb10.dp), bordered = true)
        Column(Modifier.weight(1f)) {
            // A1 字号 token：15sp Medium 三连 → GlassText.Row（15 Medium 21）。
            // 与曲库页 GroupRow / 详情页曲目行同档——动态歌单行本质就是列表行。
            // 有意不加 maxLines：原实现允许换行，本轮只做 token 接线，不夹带行为变化
            //（FavoriteRow 无 maxLines 而 GroupRow 有，属跨组件不一致，已登记在报告里待下一轮）
            Text(title, color = LocalGlassColors.current.textPrimary, style = GlassText.Row)
            Spacer(Modifier.height(2.dp))
            // A1：meta 12sp → GlassText.Caption（12 Normal 16）
            Text(meta, color = LocalGlassColors.current.textTertiary, style = GlassText.Caption)
        }
        // 装饰性 chevron，**有意 null、非遗漏**：语义由整行的 role = Button 表达
        Icon(GlassIcons.ChevronRight, null, Modifier.size(16.dp),
            tint = LocalGlassColors.current.textTertiary)
    }
}
