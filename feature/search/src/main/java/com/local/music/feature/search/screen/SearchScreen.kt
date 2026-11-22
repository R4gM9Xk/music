package com.local.music.feature.search.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
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
import com.local.music.core.ui.theme.GlassMotion
import com.local.music.core.ui.components.SongRow
import com.local.music.core.ui.components.pressScale
import com.local.music.core.ui.components.rowPressFeedback
import com.local.music.core.ui.state.AppState
import com.local.music.core.ui.state.GlassScreen
import com.local.music.core.ui.state.LibraryGroup
import com.local.music.core.ui.theme.GlassIcons
import com.local.music.core.ui.theme.GlassText
import com.local.music.core.ui.theme.LocalGlassColors
import com.local.music.core.ui.theme.R
import com.local.music.core.ui.theme.S
import com.local.music.core.ui.theme.auroraAccentColor
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState

/**
 * 搜索：真输入框（实时过滤标题/艺术家/专辑）+ 历史芯片 + 带数据的结果
 * （歌曲行播放入队、专辑卡进专辑详情；歌曲行 ⋯/长按 = 单曲操作面板）。
 * 面板经 LocalModalState.show 挂 GlassApp 根层宿主（屏内不再渲染 GlassSheet）；
 * 自绘 Chrome 的让位（modal.isOpen 时 alpha=0，保留组合护 keepOnDispose 几何）维持不变。
 *
 * 本轮结构：结果区由 Column+verticalScroll 改为 LazyColumn——单字查询可命中数百行
 * SongRow，而每行都要按需解码封面，原先一次性组合的代价在低端机上足够肉眼可见地卡一下。
 */
@Composable
fun SearchScreen(state: AppState, modifier: Modifier = Modifier) {
    val query = state.searchQuery
    // 搜索结果缓存：过滤本身是 O(n) 的整库扫描（groupingBy 那层状态层已缓存，但 filter 没有），
    // 屏内任何一次重组（切歌高亮、播放态、面板开合…）都会把它重跑一遍。
    // 依赖只有「曲库引用」与「查询词」：library 换引用（重扫/缓存刷新）或改词才需要重算。
    // 另两个派生函数都自带 query 空判，空串直接返回空表，不会退化成整库拷贝。
    val results = remember(state.library, query, state.scanFilterShort) { state.searchSongs(query) }
    val albums = remember(state.library, query, state.scanFilterShort) { state.searchAlbumGroups(query) }
    // 艺术家分组（P2）：「搜索歌曲、专辑、艺术家」这个承诺的第三项。与 albums 同款缓存键
    // （曲库引用 + 查询词 + 过滤开关）——改词/重扫/拨过滤才重算，切歌高亮不重跑。
    val artists = remember(state.library, query, state.scanFilterShort) { state.searchArtistGroups(query) }
    // P3-40：LazyColumn 的 key 必须唯一，数据异常出现重复 id 会直接崩溃——
    // 展示序列先按 id 去重（索引仍是连续的，不影响任何按位判定）；
    // 去重序列同时作为表头计数与播放队列的口径，避免「看到的条数」与「实际入队」错位
    val listedSongs = remember(results) { results.distinctBy { it.id } }
    // 展开态提升到 AppState（跨屏存活）：与查询词配对，换词即自然失效收起。
    // 旧实现是屏内 remember(query)，从专辑详情返回时组合被重建 → 展开态丢失。
    val showAllAlbums = state.searchAlbumsExpanded && state.searchExpandedQuery == query
    // A2-06：换查询词重置滚动位置——历史态/结果态/空态三态共用一个 LazyColumn，滚动位置
    // 不重置会让新结果从旧偏移处展示（index 未超界时看不出换了内容），结果变少时被 clamp
    // 跳到尾部。每键归零与主流搜索一致（输入即新查询）。
    //
    // **判据必须是「词真的变了」而不是「effect 跑了」**：旧实现无条件 scrollToItem(0)，
    // 而从详情返回时组合重建也会跑一次 effect（词没变）→ 用户被弹回列表顶部。
    val resultListState = rememberLazyListState(initialFirstVisibleItemIndex = state.searchScrollIndex)
    LaunchedEffect(query) {
        if (state.searchScrollQuery != query) {
            state.searchScrollQuery = query
            state.searchScrollIndex = 0
            resultListState.scrollToItem(0)
        }
    }
    LaunchedEffect(resultListState) {
        snapshotFlow { resultListState.firstVisibleItemIndex }
            .collect { state.searchScrollIndex = it }
    }
    val flight = LocalPlayerFlight.current
    // 模态面板宿主（GlassApp 根层）；Chrome 让位同读此状态
    val modal = LocalModalState.current

    val hazeState = rememberHazeState()
    CompositionLocalProvider(LocalHazeState provides hazeState) {
        // imePadding / statusBarsPadding 留在最外层：LazyColumn 只承担内容滚动，
        // 键盘避让与状态栏避让的作用域保持不变（否则键盘弹出时列表会被顶出可视区）
        Column(modifier.fillMaxSize().statusBarsPadding().imePadding()) {
            // 搜索框 + 取消（P2-31：从 LazyColumn 首项提为 Column 直接子级——原先搜索框
            // 滚出视口即被销毁 → 焦点丢失、键盘收起；滚回来 LaunchedEffect(autoFocus) 重跑
            // 又会自己弹键盘。常驻组合后两个问题一起消失，Field.kt 无需改动）
            Row(
                Modifier.fillMaxWidth().padding(start = S.Gap20.dp, end = S.Gap20.dp, top = 2.dp, bottom = S.Gap16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(S.Gap8.dp), // 与 GlassTabs 筛选行同族同距（原 10）
            ) {
                GlassTextField(
                    value = query,
                    onValueChange = { state.searchQuery = it },
                    hint = "搜索歌曲、专辑、艺术家",
                    // 只在**空查询**时自动聚焦：从专辑详情返回时查询词非空，用户是在
                    // 浏览结果而不是准备打字——旧实现恒 true，返回即 requestFocus，
                    // 键盘自动弹起遮住半屏，还得先收键盘才能看结果。
                    autoFocus = query.isEmpty(),
                    onSearch = { state.addSearchHistory(query) },
                    modifier = Modifier.weight(1f),
                )
                val cancelInteraction = remember { MutableInteractionSource() }
                Text(
                    // A1：13sp 裸字号 → GlassText.BodyMedium（13 Medium 18）。
                    // 用 Medium 而非 Body：这是顶栏唯一的强调动作（退出搜索），
                    // 与下方「全部 N 张专辑」同档，视觉上是同一类"可点的强调文字"
                    "取消", color = auroraAccentColor(), style = GlassText.BodyMedium,
                    modifier = Modifier
                        .pressScale(cancelInteraction, pressed = 0.92f)
                        .clickable(interactionSource = cancelInteraction, indication = null) {
                            state.searchQuery = ""
                            state.screen = GlassScreen.Library
                        }
                        // 读屏（无障碍 P0-4）：纯 Text + clickable，没有 role，
                        // 读屏念「取消」但不提示可点。补 role = Button 即可，
                        // 可见文字「取消」本身就是最好的描述（不另写 contentDescription）。
                        .semantics { this.role = Role.Button }
                        .padding(4.dp),
                )
            }
            // imePadding / statusBarsPadding 留在最外层：LazyColumn 只承担内容滚动，
            // 键盘避让与状态栏避让的作用域保持不变（否则键盘弹出时列表会被顶出可视区）；
            // hazeSource 保持在列表上（毛玻璃取样范围不变）
            LazyColumn(
                Modifier
                    .weight(1f)
                    .hazeSource(hazeState),
                state = resultListState,
            ) {
                if (query.isBlank()) {
                    // P2-8：无历史时不再渲染「最近搜索」标题，给一条居中的空态提示占位
                    if (state.searchHistory.isEmpty()) {
                        item(key = "history-empty") {
                            Text(
                                "暂无搜索历史",
                                // A1：13sp 裸字号 → GlassText.Body（13 Normal 18）
                                color = LocalGlassColors.current.textTertiary, style = GlassText.Body,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth().padding(top = S.Gap24.dp),
                            )
                        }
                    } else {
                        // ── 历史标题行 + 清空入口（P2 修复）──
                        //
                        // 搜索历史是**唯一**会自己增长的用户数据，而它承载的往往是
                        // 「不想被别人看到搜过什么」的隐私语义（在手机上搜歌名经常等于
                        // 暴露心事/意图）。旧实现没有任何清理入口：用户只能连搜 8 个
                        // 无意义词把它顶掉——一个功能缺口被用户用「污染自己的数据」绕过。
                        //
                        // 清空放在标题行右侧而不是每条 chip 上：单条删除需要长按或 × 号，
                        // 8 个 chip 各挂一个 × 会让这一片变吵（芯片本来是最安静的元素）。
                        // 而本屏是**唯一的**历史消费场景，「一次清空」覆盖绝大多数意图。
                        item(key = "history-header") {
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                // SectionHeader 自带左右边距与整行宽度，这里用 weight 让它占满剩余。
                                // 注意 SectionHeader 内部是 fillMaxWidth 的，所以它必须 weight(1f)
                                // 而不是裸调用——否则会把右侧的「清空」挤出屏幕。
                                Box(Modifier.weight(1f)) { SectionHeader("最近搜索") }
                                val clearInteraction = remember { MutableInteractionSource() }
                                Text(
                                    "清空",
                                    color = LocalGlassColors.current.textTertiary,
                                    style = GlassText.CaptionMedium,
                                    modifier = Modifier
                                        // 44dp 热区（全 App 统一下限）：纯文字「清空」只有约 24×16dp，
                                        // 而它的后果是删数据——不能让它又小又好误触。
                                        // sizeIn 保证热区，视觉文字位置不变（内容居中）。
                                        .sizeIn(minWidth = 44.dp, minHeight = 44.dp)
                                        .clickable(
                                            interactionSource = clearInteraction,
                                            indication = null,
                                        ) { state.clearSearchHistory() }
                                        // 读屏：可见文字只有「清空」，脱离上下文有歧义（清空什么？）。
                                        // 用 contentDescription 补作用域，并补 role = Button
                                        // （纯 Text + clickable 不会自动获得按钮角色）。
                                        .semantics {
                                            this.role = Role.Button
                                            this.contentDescription = "清空搜索历史"
                                        }
                                        .padding(horizontal = S.Gap20.dp),
                                )
                            }
                        }
                        // FlowRow 自动换行：长历史词不再挤出屏幕。
                        // P3-11：历史存 8 条，原先只 take(4) 展示——去掉截断，8 条 FlowRow 换行后并不长；
                        // 点击 chip 写回历史（addSearchHistory 会去重并移到最前）
                        item(key = "history-chips") {
                            FlowRow(
                                Modifier.fillMaxWidth().padding(start = S.Gap20.dp, end = S.Gap20.dp, bottom = S.Gap16.dp),
                                horizontalArrangement = Arrangement.spacedBy(S.Gap8.dp),
                            ) {
                                state.searchHistory.forEach { keyword -> GlassChip(keyword) {
                                    state.addSearchHistory(keyword)
                                    state.searchQuery = keyword
                                } }
                            }
                        }
                    }
                } else {
                    // 空态判据必须包含艺术家（P2）：漏了的话，只命中艺术家的查询
                    // 会同时显示「没有找到相关内容」**和**下面的艺术家分组——自相矛盾。
                    val empty = listedSongs.isEmpty() && albums.isEmpty() && artists.isEmpty()
                    if (empty) {
                        item(key = "search-empty") {
                            Text(
                                "没有找到「${query.trim()}」相关内容",
                                // A1：13sp 裸字号 → GlassText.Body（13 Normal 18）
                                color = LocalGlassColors.current.textTertiary, style = GlassText.Body,
                                textAlign = TextAlign.Center,
                                // 空态顶部偏移 56dp **刻意不入 S 刻度**：它不是「段落之间的间距」，
                                // 而是把那一行字推到视口视觉中心附近的一次性几何量。刻度最高档 32，
                                // 就近取 32 会让空态贴在筛选行下方、读作「列表第一行」而不是「没有结果」；
                                // 取 64 又会被软键盘/横屏压到看不见。这类定位量按几何推导保留裸值。
                                modifier = Modifier.fillMaxWidth().padding(top = 56.dp),
                            )
                        }
                    }
                    if (listedSongs.isNotEmpty()) {
                        item(key = "songs-header") { SectionHeader("歌曲 · ${listedSongs.size} 条结果") }
                        // key = 稳定歌曲 id：换词后列表可按 id 复用/移动行，避免整片重建
                        items(listedSongs, key = { it.id }) { song ->
                            SongRow(
                                song = song,
                                playing = song.id == state.currentSong.id,
                                wavePlaying = state.playing,
                                onMoreClick = {
                                    modal.show {
                                        SongActionsSheet(
                                            visible = modal.visible,
                                            onDismiss = modal::requestDismiss,
                                            state = state,
                                            song = song,
                                        )
                                    }
                                },
                                onLongClick = {
                                    modal.show {
                                        SongActionsSheet(
                                            visible = modal.visible,
                                            onDismiss = modal::requestDismiss,
                                            state = state,
                                            song = song,
                                        )
                                    }
                                },
                                onClick = {
                                    state.addSearchHistory(query)
                                    // 队列口径同展示序列：与用户看到的列表一致
                                    state.playSong(song, listedSongs, "搜索")
                                    state.openPlayer()
                                },
                            )
                        }
                    }
                    if (albums.isNotEmpty()) {
                        item(key = "albums-header") { SectionHeader("专辑 · ${albums.size} 张") }
                        val visibleAlbums = if (showAllAlbums) albums else albums.take(2)
                        // 专辑卡是两列一行：每个 LazyColumn item 承担一整行（item 内不能再懒）
                        visibleAlbums.chunked(2).forEachIndexed { rowIndex, pair ->
                            // item key 用专辑分组的唯一标识 id，而不是展示名 key：
                            // 两张同名不同艺术家的专辑 key 相同，LazyColumn 会因 key 重复崩溃
                            // （rowIndex 只区分行，同一行内两张同名专辑仍会撞）
                            item(key = "album-row-$rowIndex-${pair.first().id}") {
                                AlbumRow(pair, state)
                            }
                        }
                        if (albums.size > 2) {
                            item(key = "albums-expand") {
                                val expandInteraction = remember { MutableInteractionSource() }
                                Text(
                                    if (showAllAlbums) "收起" else "全部 ${albums.size} 张专辑",
                                    // A1：13sp Medium 三连 → GlassText.BodyMedium（13 Medium 18）。
                                    // 与「取消」同档：两者都是可点的强调文字，视觉应当等重
                                    color = auroraAccentColor(), style = GlassText.BodyMedium,
                                    modifier = Modifier
                                        .padding(start = S.Gap20.dp, end = S.Gap20.dp, bottom = S.Gap16.dp)
                                        .pressScale(expandInteraction, pressed = 0.92f)
                                        .clickable(
                                            interactionSource = expandInteraction, indication = null,
                                        ) {
                                            state.searchAlbumsExpanded = !showAllAlbums
                                            state.searchExpandedQuery = query
                                        }
                                        // 读屏（无障碍 P0-4）：纯 Text + clickable，无 role。
                                        // 这行文字本身在「全部 N 张专辑」/「收起」之间切换，
                                        // 文字已完整表达当前可做的动作，所以只补 role 不写死描述
                                        // （写死「展开专辑」会在收起态下念错，正是双源漂移的典型）。
                                        .semantics { this.role = Role.Button },
                                )
                            }
                        }
                    }
                    // ── 艺术家分组（P2 修复）──
                    //
                    // 搜索框的 hint 写「搜索歌曲、专辑、艺术家」——承诺了三类结果，
                    // 而旧实现只渲染歌曲与专辑。用户搜「周杰伦」得到的是一屏**散落的歌曲行**，
                    // 没有任何入口进「周杰伦」这个艺术家本身（他在曲库里有整块艺术家页：
                    // 专辑数、全部曲目、按专辑分组）。承诺与交付不一致，是明确的功能缺口。
                    //
                    // 顺序放在专辑之后：用户搜歌手名时最想看的通常是「这个人」，
                    // 但歌名/专辑命中率更高、也更常是精确意图，所以「歌曲 → 专辑 → 艺术家」
                    // 的降序符合「从具体到集合」的阅读习惯。不折叠（艺术家数量天然远小于歌曲，
                    // 且每位一行，不像专辑卡那样占高度）。
                    if (artists.isNotEmpty()) {
                        item(key = "artists-header") { SectionHeader("艺术家 · ${artists.size} 位") }
                        artists.forEach { group ->
                            // key 用艺术家分组 id（= 艺术家名，与 DetailTarget 编码同源）：
                            // 展示名与 id 在这里恰好相同，但仍走 id——将来若分组键加上
                            // 「艺术家 + 专辑艺术家」之类的复合口径，这里不需要改。
                            item(key = "artist-${group.id}") {
                                ArtistResultRow(
                                    name = group.key,
                                    // 专辑数走状态层的分组缓存（与曲库艺术家 tab / 详情页
                                    // meta 同源同口径），不在屏内自行 groupBy
                                    albumCount = state.albumCountByArtist()[group.key] ?: 0,
                                    songCount = group.songs.size,
                                    onClick = {
                                        state.addSearchHistory(query)
                                        // 直接进艺术家详情。不先播放：用户点的是「这个人」这个
                                        // 上下文，进详情才能看到他的全部专辑与曲目。
                                        state.openDetail(state.artistTargetOf(group.songs.first()))
                                    },
                                )
                            }
                        }
                    }
                }
                item(key = "search-bottom-inset") { Spacer(Modifier.height(S.Gap16.dp)) } // 原 18，归 16 刻度
            }
            // 单曲面板打开时 Chrome 让位（与 PlayerScreen 控制台同策略）：
            // graphicsLayer alpha 只隐不卸——keepOnDispose 的迷你条飞行几何保持冻结；
            // ModalState 语义保持「退场动画结束（content 清空）后 Chrome 才复位」。
            // A3-08：让位 alpha 走 tweenPage 渐变——旧实现 0/1 硬切，与面板 360ms 进出场节奏脱节
            val chromeAlpha by animateFloatAsState(
                targetValue = if (modal.isOpen) 0f else 1f,
                animationSpec = GlassMotion.tweenPage(),
                label = "searchChromeAlpha",
            )
            GlassChrome(
                modifier = Modifier.graphicsLayer { alpha = chromeAlpha },
                song = state.currentSong,
                playing = state.playing,
                // 进度以 lambda 下钻：本屏 body 不再读 progressFraction，播放进度每 100ms 变化
                // 只失效 Chrome 的绘制，不会把整个结果列表拖着重组
                progressProvider = { state.progressFraction },
                navSelected = 0,
                onNavSelect = {
                    when (it) {
                        0 -> state.screen = GlassScreen.Library
                        1 -> state.screen = GlassScreen.Playlist
                        2 -> state.screen = GlassScreen.Settings
                    }
                },
                onOpenPlayer = {
                    // 与 GlassApp 的 Chrome 同走共享元素飞行；忙时兜底先 abort 再直切
                    //（否则跑着的 exit 尾部仍会 navigate，把用户从播放页拽回来源屏）
                    val f = flight
                    if (f != null && !f.isActive) {
                        // 传来源屏：退出飞行按 entrySource 回搜索，不再硬编码曲库（P2-14）。
                        // 注意第 4 参 source 不是函数类型，尾 lambda 只能写在括号内
                        f.beginEntry(
                            state.currentSong, 0,
                            { state.openPlayer() }, GlassScreen.Search,
                        )
                    } else {
                        f?.abort()
                        state.openPlayer()
                    }
                },
                onPlayPause = state::togglePlayPause,
                onNext = { state.next() },
                // 空曲库时迷你条不渲染播放/下一首（否则是可点但无反应的死按钮）。
                // 旧实现漏传本参数 → 取默认 true → 搜索页在权限被拒时照样显示两个死按钮。
                hasSongs = state.hasSongs,
            )
        }
    }
}

/**
 * 艺术家搜索结果的一行：圆形首字母徽标 + 艺术家名 + 「N 张专辑 · M 首」。
 *
 * ## 为什么不复用 LibraryScreen 的 GroupRow
 * `GroupRow` 是 private 的，且它带 `ArtStyle` 渐变封面（那是给文件夹/流派这种
 * 「有颜色分组」用的）。艺术家在曲库里**没有封面**——真实曲库里 `albumGroups` 的
 * `ArtStyle` 是给专辑卡兜底用的设计稿遗留；给艺术家配一个假渐变封面会读成
 * 「这个歌手有张图没加载出来」。所以这里用**首字母徽标**：它是中性的、
 * 不假装有图，而且对中文名（取首字）与英文名（取首字母）都成立。
 *
 * ## 为什么无头像位而不是占位图
 * 本地曲库没有任何艺术家图片数据源（MediaStore 不提供）。放一个灰色圆占位会让用户
 * 长期等待一个永远不会来的图；首字母徽标本身就是**完整**的呈现，不是降级。
 */
@Composable
private fun ArtistResultRow(
    name: String,
    albumCount: Int,
    songCount: Int,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val glass = LocalGlassColors.current
    val accent = auroraAccentColor()
    Row(
        Modifier
            .fillMaxWidth()
            // 与 SongRow 同款外边距节奏：6dp 让按压底色落在圆角内，12dp 内边距 = PageMargin
            .padding(horizontal = 6.dp)
            .clip(RoundedCornerShape(R.Card14.dp))
            .rowPressFeedback(interactionSource)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            // 读屏：clickable 不带 role，补 Button 让读屏念「周杰伦，3 张专辑 · 42 首，按钮」
            .semantics { this.role = Role.Button }
            .padding(horizontal = S.Gap12.dp, vertical = S.Gap8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(S.Gap12.dp),
    ) {
        // 首字母徽标：44dp（与 GlassIconButton 同档，保证触控与视觉重量一致）。
        // 取 firstOrNull 而不是 first()：空串艺术家名不该崩（扫描可能给出空 artist）。
        Box(
            Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(accent.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                // 取首个「字」（对中文）或首字母（对英文）。trim 后取，避免空格开头拿到空白
                name.trim().take(1).ifEmpty { "?" },
                style = GlassText.RowStrong,
                color = accent,
            )
        }
        Column(Modifier.weight(1f)) {
            Text(
                name, style = GlassText.Row, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = glass.textPrimary,
            )
            Spacer(Modifier.height(2.dp))
            // 两个计数都用真值：专辑数为 0 时只说「M 首」（单曲艺人没有专辑），
            // 不要写「0 张专辑」——那读起来像个错误
            Text(
                if (albumCount > 0) "$albumCount 张专辑 · $songCount 首" else "$songCount 首",
                style = GlassText.Caption, color = glass.textTertiary,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(GlassIcons.ChevronRight, null, Modifier.size(16.dp), tint = glass.textTertiary)
    }
}

/** 专辑结果的一行（两列）；抽出来是为了让 LazyColumn 的 item 内容保持单表达式可读 */
@Composable
private fun AlbumRow(pair: List<LibraryGroup>, state: AppState) {
    Row(
        Modifier.fillMaxWidth().padding(start = S.Gap20.dp, end = S.Gap20.dp, bottom = S.Gap12.dp),
        horizontalArrangement = Arrangement.spacedBy(S.Gap12.dp),
    ) {
        pair.forEach { album ->
            PlaylistCard(
                // 标题仍是纯专辑名；定位用 id（专辑名 + 专辑艺术家），否则同名专辑会进错详情页
                title = album.key, meta = album.meta, art = album.art,
                onClick = { state.openDetail(DetailTarget(DetailTarget.Kind.Album, album.id)) },
                modifier = Modifier.weight(1f),
                bitmap = album.coverBitmap,
                artworkUri = album.coverUri,
            )
        }
        // P2-2：奇数收尾行只有一张卡时补一个同权 Spacer——否则单卡会被 weight 撑满整行
        // （与另一列的卡不等宽，视觉上"一张专辑占了整排"）
        if (pair.size == 1) Spacer(Modifier.weight(1f))
    }
}

/**
 * 玻璃芯片（.chip）：点历史词条回填搜索框。
 *
 * 读屏（无障碍 P0-4）：GlassSurface 内部走 combinedClickable，只提供动作、**不带 role**。
 * 于是 TalkBack 把芯片念成一句普通文字「周杰伦」，盲用户完全不知道它是可点的
 * —— 而「最近搜索」列表里这排芯片正是回填历史词条的唯一入口。
 * 补 role = Button 后读屏念「周杰伦，按钮，双击激活」。
 *
 * 不写 contentDescription：`text` 本身就是可见文字，写死会与之双源漂移。
 */
@Composable
private fun GlassChip(text: String, onClick: () -> Unit) {
    GlassSurface(
        level = GlassLevel.Lo,
        // C4：胶囊圆角走 R.Full（50），不再裸写 50——刻度里"胶囊"只有这一档
        shape = RoundedCornerShape(R.Full),
        onClick = onClick,
        modifier = Modifier.semantics { this.role = Role.Button },
    ) {
        Text(
            text,
            // A4 跳过（有意）：芯片是 lo 玻璃底 + textSecondary，**不是** seed 实底，
            // 因此不适用 onSeedColor()。浅色模式下 textSecondary 已是近黑，对比由 token 保证。
            color = LocalGlassColors.current.textSecondary,
            // A1：12sp 裸字号 → GlassText.Caption（12 Normal 16）。芯片与列表副标题同字号，
            // 但视觉上更小：它靠胶囊形与低对比度退到背景，不靠字号
            style = GlassText.Caption,
            modifier = Modifier.padding(horizontal = S.Gap12.dp, vertical = S.Gap8.dp),
        )
    }
}
