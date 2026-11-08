package com.local.music.feature.search.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.semantics.Role
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
import com.local.music.core.ui.state.AppState
import com.local.music.core.ui.state.GlassScreen
import com.local.music.core.ui.state.LibraryGroup
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
    // P3-40：LazyColumn 的 key 必须唯一，数据异常出现重复 id 会直接崩溃——
    // 展示序列先按 id 去重（索引仍是连续的，不影响任何按位判定）；
    // 去重序列同时作为表头计数与播放队列的口径，避免「看到的条数」与「实际入队」错位
    val listedSongs = remember(results) { results.distinctBy { it.id } }
    // 展开态跟随查询词重置（remember 以 query 为 key，换词自动收起）
    var showAllAlbums by remember(query) { mutableStateOf(false) }
    // A2-06：换查询词重置滚动位置——历史态/结果态/空态三态共用一个 LazyColumn，滚动位置
    // 不重置会让新结果从旧偏移处展示（index 未超界时看不出换了内容），结果变少时被 clamp
    // 跳到尾部。每键归零与主流搜索一致（输入即新查询）
    val resultListState = rememberLazyListState()
    LaunchedEffect(query) { resultListState.scrollToItem(0) }
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
                    autoFocus = true,
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
                        item(key = "history-header") { SectionHeader("最近搜索") }
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
                    val empty = listedSongs.isEmpty() && albums.isEmpty()
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
                                        ) { showAllAlbums = !showAllAlbums }
                                        // 读屏（无障碍 P0-4）：纯 Text + clickable，无 role。
                                        // 这行文字本身在「全部 N 张专辑」/「收起」之间切换，
                                        // 文字已完整表达当前可做的动作，所以只补 role 不写死描述
                                        // （写死「展开专辑」会在收起态下念错，正是双源漂移的典型）。
                                        .semantics { this.role = Role.Button },
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
            )
        }
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
