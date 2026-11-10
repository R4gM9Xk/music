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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
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
import com.local.music.core.ui.components.EmptyPlaylistCard
import com.local.music.core.ui.components.GlassEmptyState
import com.local.music.core.ui.components.GlassSearchBox
import com.local.music.core.ui.components.GlassTabs
import com.local.music.core.ui.components.LocalModalState
import com.local.music.core.ui.components.ModalSheet
import com.local.music.core.ui.components.PageHeader
import com.local.music.core.ui.components.PlaylistCard
import com.local.music.core.ui.components.SongActionsSheet
import com.local.music.core.ui.components.SheetAction
import com.local.music.core.ui.components.SongRow
import com.local.music.core.ui.components.rowPressFeedback
import com.local.music.core.ui.state.AppState
import com.local.music.core.ui.state.GlassScreen
import com.local.music.core.ui.state.SongSort
import com.local.music.core.ui.theme.GlassIcons
import com.local.music.core.ui.theme.GlassText
import com.local.music.core.ui.theme.LocalGlassColors
import com.local.music.core.ui.theme.R
import com.local.music.core.ui.theme.S

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
    // A2-07：六个 tab 共用一份 LazyListState——在「歌曲」滚到深处后切「专辑」，item 数变少时
    // firstVisibleItemIndex 被 clamp、视口跳到列表尾部，切回也不复原。tab 变化即归零（跳过
    // 首组合：rememberSaveable 恢复的滚动位置不被冲掉）；lastTab 用普通 remember——本组合实例
    // 内比较前后值，换屏重进后从零起算正好不误伤恢复
    var lastTab by remember { mutableIntStateOf(tab) }
    LaunchedEffect(tab) {
        if (tab != lastTab) {
            lastTab = tab
            listState.scrollToItem(0)
        }
    }
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
                    state.openPlayer()
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
                    modifier = Modifier.padding(
                        // S 间距 token 接线：20 = PageMargin、12 = Gap12。
                        // top = 2 属 0–6dp 微缝（搜索框与页头之间的光学缝），按规范保持裸值
                        start = S.PageMargin.dp, end = S.PageMargin.dp, top = 2.dp, bottom = S.Gap12.dp,
                    ),
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
                        // 「去设置页扫描」：直接切到设置屏（等价于点底部导航的「设置」）。
                        // 本轮写域不含 GlassApp（导航宿主），所以用 state.screen 这个既有
                        // 单点入口，而不是往 LibraryScreen 加一个新回调参数——那要么改
                        // GlassApp 的调用点，要么留一个没人传的空默认值（假入口）。
                        // 扫描入口确实只在设置页（「扫描音乐」行），没有更近的目的地
                        LibraryEmptyState(
                            audioDenied = audioDenied,
                            onRequestAudioPermission = onRequestAudioPermission,
                            onOpenSettings = { state.screen = GlassScreen.Settings },
                        )
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
                        item(key = "tab-empty-1") {
                            // 图标复用既有 Disc（唱片）：专辑就是唱片，不需要新画一枚
                            TabEmptyState(GlassIcons.Disc, "还没有专辑", "扫描到的本地音乐会自动按专辑归类")
                        }
                    } else {
                        groups.chunked(2).forEach { pair ->
                            // LazyColumn 的 item key 必须唯一，而**专辑名**不唯一：两张同名
                            // 不同艺术家的专辑会有相同的 key → Compose 直接抛
                            // "Key ... was already used" 崩溃（不是显示错乱，是崩）。
                            // 所以 item key 用分组唯一标识 id，而不是展示名 key。
                            item(key = "album:${pair.first().id}") {
                                Row(
                                    // S 间距 token：20 = PageMargin（页边距）、12 = Gap12（卡片行距）。
                                    // 两列卡片的水平缝与下方卡片行距同取 12——它们表达的是同一件事
                                    //「相邻卡片之间的呼吸」，不该是两个独立的数
                                    Modifier.fillMaxWidth().padding(
                                        start = S.PageMargin.dp, end = S.PageMargin.dp, bottom = S.Gap12.dp,
                                    ),
                                    horizontalArrangement = Arrangement.spacedBy(S.Gap12.dp),
                                ) {
                                    pair.forEach { album ->
                                        PlaylistCard(
                                            // 卡片标题仍是**纯专辑名**（album.key），不是定位用的 id：
                                            // id 里带着 NUL + 专辑艺术家，是内部实现对，不能漏到界面上
                                            title = album.key, meta = album.meta, art = album.art,
                                            // 详情定位走 album.id（专辑名 + 专辑艺术家二元组）：
                                            // 旧实现传 album.key，两只同名专辑会进同一张详情页
                                            onClick = { state.openDetail(DetailTarget(DetailTarget.Kind.Album, album.id)) },
                                            onLongClick = {
                                                collectionActions(
                                                    // 第一个参数是**定位目标** → 用 id；
                                                    // 第二个参数是集合操作面板的**标题文案** → 必须是专辑名，
                                                    // 换成 id 会把 NUL 串显示在面板标题上
                                                    DetailTarget(DetailTarget.Kind.Album, album.id), album.key, album.songs,
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
                        item(key = "tab-empty-2") {
                            // 图标复用既有 User（人形）：艺术家 = 人，同样不需要新画
                            TabEmptyState(GlassIcons.User, "还没有艺术家", "本地音乐扫描后会按艺术家自动归类")
                        }
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
                    val groups = state.folderGroups()
                    if (groups.isEmpty()) {
                        item(key = "tab-empty-3") {
                            // 图标复用既有 Folder：这一档语义最直白
                            TabEmptyState(GlassIcons.Folder, "还没有文件夹", "扫描到的音乐所在目录会显示在这里")
                        }
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
                        item(key = "tab-empty-4") {
                            // 图标：新画的 Tag（标签）。流派没有既有图标可用，而「随便挑一枚
                            // 近似的」会让空态图标退化成装饰纹样——五个 tab 必须逐档可辨
                            TabEmptyState(GlassIcons.Tag, "还没有流派", "曲目文件里的流派标签会汇总到这里")
                        }
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
                        item(key = "tab-empty-5") {
                            // 图标：新画的 NoteList（双音符 + 符杠）。刻意不用「清单」构图——
                            // 那与既有 Queue（播放队列）撞形，会让两个不同概念在图标层混淆
                            TabEmptyState(GlassIcons.NoteList, "还没有歌单", "点右上角 ＋ 可以新建歌单")
                        }
                    } else {
                        wallPlaylists.chunked(2).forEach { pair ->
                            item(key = "playlist:${pair.first().id}") {
                                Row(
                                    // S 间距 token：20 = PageMargin（页边距）、12 = Gap12（卡片行距）。
                                    // 两列卡片的水平缝与下方卡片行距同取 12——它们表达的是同一件事
                                    //「相邻卡片之间的呼吸」，不该是两个独立的数
                                    Modifier.fillMaxWidth().padding(
                                        start = S.PageMargin.dp, end = S.PageMargin.dp, bottom = S.Gap12.dp,
                                    ),
                                    horizontalArrangement = Arrangement.spacedBy(S.Gap12.dp),
                                ) {
                                    pair.forEach { playlist ->
                                        // §11.1 ⑤：0 首歌的歌单卡走空态特化（封面中央一枚
                                        // 小图标 + 「去添加歌曲」）。1:1 封面改版后一张空卡
                                        // 有一半面积是纯占位纹样，读起来像「封面没加载出来」
                                        // 而不是「这个歌单还没加歌」。
                                        // 点击行为不变（仍进详情页），不新增交互路径。
                                        if (playlist.tracks.isEmpty()) {
                                            EmptyPlaylistCard(
                                                title = playlist.title, meta = playlist.meta, art = playlist.art,
                                                onClick = { state.openDetail(DetailTarget(DetailTarget.Kind.Playlist, playlist.id)) },
                                                onLongClick = {
                                                    collectionActions(
                                                        DetailTarget(DetailTarget.Kind.Playlist, playlist.id),
                                                        playlist.title,
                                                        playlist.tracks,
                                                    )
                                                },
                                                modifier = Modifier.weight(1f),
                                            )
                                            return@forEach
                                        }
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
            // 6dp：整行外边距，属 0–6dp 微缝豁免（它让按压底色落在卡片圆角内），保持裸值
            .padding(horizontal = 6.dp)
            // C4：14dp 即 R 刻度里的 Card14（列表行容器），不再裸写数值
            .clip(RoundedCornerShape(R.Card14.dp))
            // A6：行级缩放改按压底色（与 SongRow/SettingRow 统一，见 rowPressFeedback 的 KDoc）。
            // 旧实现 pressScale(0.98) 缩放整行——文字、封面、chevron 一起向中心位移，长列表里
            // 相邻行静止而当前行缩小，人眼把这种相对位移读成「列表在抖」而不是「我按下了」。
            // 底色只改明度、不动任何像素，而行的 6dp 外边距保证反馈底色落在卡片圆角内。
            .rowPressFeedback(interactionSource)
            .then(rowClickable)
            // 读屏（无障碍 P0-4）：整行可点（= 进入该分组的详情页），但 clickable /
            // combinedClickable 只提供 onClick 动作、**不带 role**，读屏只会把
            // title + meta 当一段文字念出来，盲用户无法判断这行能进。
            // 补 role = Button 后读屏念「周杰伦，12 首，按钮，双击激活」。
            //
            // 不写 contentDescription：title/meta 是可见文字，clickable 自带
            // mergeDescendants 会按子树顺序拼出描述，写死反而形成双源漂移。
            .semantics { this.role = Role.Button }
            // S 接线：横向 = PageMargin − 行外边距 6dp，**这是几何推导量而非自由间距** ——
            // 6 + 14 = 20 正是全 App 的页边距，行文字因此与页头大标题、搜索框左缘落在同一条线上。
            // 直接归位到 Gap12 会把这行文字推到 18dp，与同屏 SongRow（Lists.kt，同样是 6+14）
            // 差 2dp：同一个 LazyColumn 里两种左缘，比不归位更难解释。
            // 纵向 9 → 8：标准归位（就近向下），行高仍由 48dp 缩略图撑开，观感不变
            .padding(horizontal = (S.PageMargin - 6).dp, vertical = S.Gap8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(S.Gap12.dp),
    ) {
        ArtThumb(
            art, Modifier.size(48.dp), RoundedCornerShape(R.Thumb10.dp),
            bordered = true, bitmap = bitmap, artworkUri = artworkUri,
        )
        Column(Modifier.weight(1f)) {
            // A1 字号 token：15sp Medium 三连 → GlassText.Row（15 Medium 21）。
            // 这是全 App 出现密度最高的文本档，改前与 SongRow / SettingRow 各写一遍字面量，
            // 任一处调档就会与其余列表行错位
            Text(title, style = GlassText.Row, maxLines = 1,
                overflow = TextOverflow.Ellipsis, color = LocalGlassColors.current.textPrimary)
            Spacer(Modifier.height(2.dp))
            // A1：meta 12sp → GlassText.Caption（12 Normal 16）
            Text(meta, style = GlassText.Caption, color = LocalGlassColors.current.textTertiary,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        // 装饰性 chevron，**有意 null、非遗漏**：只是「这行能进去」的视觉提示，
        // 同一件事已由整行的 role = Button 表达，读屏再念一次「右箭头」纯属噪声
        Icon(GlassIcons.ChevronRight, null, Modifier.size(16.dp), tint = LocalGlassColors.current.textTertiary)
    }
}

/**
 * 各 tab 通用空态：图标 / 标题 / 副标题三件套。
 *
 * **为什么每个 tab 必须给各自不同的图标**（而不是五个 tab 共用一枚「空盒子」）：
 * 空态图标是用户唯一的「我是哪个 tab、这里本该有什么」的线索。曲库六个 tab 都长着
 * 同一张脸时，切 tab 的反馈就只剩标题文字一行——而那正是 E2 要消灭的「一句灰字」。
 *
 * **为什么这里没有 CTA**（三件套的第三件在五个 tab 上都是空的）：
 * 这五个 tab 的空态都没有一个**能直达**的动作。新建歌单在歌单页、加歌在详情页、
 * 扫描在设置页——硬塞一个「去设置页」等于每条空态都指向同一个地方，
 * 用户点进去还要自己找对应入口。空态 CTA 的原则是「一枚、且它真的解决问题」，
 * 做不到就宁可不给（见 [GlassEmptyState] 的 KDoc）。副标题已经把「怎么会有内容」
 * 说清楚了，这是本档能给的最诚实的引导。
 */
@Composable
private fun TabEmptyState(icon: ImageVector, title: String, subtitle: String) {
    GlassEmptyState(icon = icon, title = title, subtitle = subtitle)
}

/**
 * 歌曲 tab 的空态——两档，且必须在图标/标题/CTA 三处都不同。
 *
 * **为什么需要它**：READ_MEDIA_AUDIO 被拒后 GlassApp 只做 abandonPendingPlayback +
 * markLibrarySettled，曲库页就只是一个空列表。若此时显示成「曲库里还没有歌曲」，
 * 用户会去找歌、去换目录、去怀疑软件坏了——而真正要做的是授权。**状态错报比没有状态更糟。**
 *
 * 授权成功后的重扫由 GlassApp 的权限回调负责，这里只负责发起请求，不做本地状态猜测。
 *
 * ## 两档的差异（E2 要求「三处都要不同」）
 * | | 图标 | 标题 | CTA |
 * |---|---|---|---|
 * | 权限被拒 | Mic（麦克风=音频权限） | 未获得音乐与音频访问权限 | 去授权（seed 实底胶囊） |
 * | 库里没歌 | MusicNote（音符=没有歌） | 曲库里还没有歌曲 | 去设置页扫描 |
 *
 * **为什么权限被拒档去掉了原来的 SettingRow 玻璃卡外壳**：那句话与动作是并排的两段
 * 玻璃行文字，没有图标层级也没有唯一可点的形状——它是「没有空态组件时的替代品」。
 * 三件套成立之后，同一个信息用标题 + 副标题 + 胶囊表达，层级更清楚。
 * 标题文案逐字保留（「未获得音乐与音频访问权限」），副标题去掉「点击」二字
 * ——旧文案里的「点击」是在教用户点**那一行**，而现在动作已经是一枚有明确形状的按钮，
 * 再把「点击」写进副标题会与按钮自身的语义重复。
 * 代价是它与设置页那枚权限行不再长得一样；这可以接受，因为两者此刻**职责不同**
 * （设置页那行是「一个设置项」，这里是「一屏为什么空」）。
 *
 * **「去设置页扫描」这枚 CTA 是本轮新接的交互**（增-U7：空态引导死文案）：
 * 旧实现是一句不可点的祈使句。它落在设置页「扫描音乐」行 —— 那是唯一的扫描入口。
 */
@Composable
private fun LibraryEmptyState(
    audioDenied: Boolean,
    onRequestAudioPermission: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    if (audioDenied) {
        GlassEmptyState(
            icon = GlassIcons.Mic,
            title = "未获得音乐与音频访问权限",
            subtitle = "授权后即可扫描并播放本地音乐",
            // 应用内唯一的授权入口，历史审计明确记录过不可移除
            actionLabel = "去授权",
            onAction = onRequestAudioPermission,
        )
    } else {
        GlassEmptyState(
            icon = GlassIcons.MusicNote,
            title = "曲库里还没有歌曲",
            subtitle = "可能还没有扫描，或扫描结果被过滤条件排除了",
            actionLabel = "去设置页扫描",
            onAction = onOpenSettings,
        )
    }
}
