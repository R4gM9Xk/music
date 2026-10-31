package com.local.music.feature.playlist.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.local.music.core.ui.components.AddSongsSheet
import com.local.music.core.ui.components.ArtThumb
import com.local.music.core.ui.components.CollectionActionsSheet
import com.local.music.core.ui.components.DemoCatalog
import com.local.music.core.ui.components.DemoSong
import com.local.music.core.ui.components.DetailData
import com.local.music.core.ui.components.DetailTarget
import com.local.music.core.ui.components.GlassChrome
import com.local.music.core.ui.components.GlassIconButton
import com.local.music.core.ui.components.GlassLevel
import com.local.music.core.ui.components.GlassSurface
import com.local.music.core.ui.components.LocalHazeState
import com.local.music.core.ui.components.LocalModalState
import com.local.music.core.ui.components.LocalPlayerFlight
import com.local.music.core.ui.components.ReorderableColumn
import com.local.music.core.ui.components.SongActionsSheet
import com.local.music.core.ui.components.SongRow
import com.local.music.core.ui.components.SongRowHeight
import com.local.music.core.ui.components.pressScale
import com.local.music.core.ui.state.AppState
import com.local.music.core.ui.state.GlassScreen
import com.local.music.core.ui.theme.GlassIcons
import com.local.music.core.ui.theme.LocalAuroraPalette
import com.local.music.core.ui.theme.LocalGlassColors
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState

/**
 * 详情页（歌单 / 专辑 / 艺术家 / 文件夹 / 流派 共用模板）：
 * 数据由 AppState.detailData(state.detail) 解析；播放 / 随机经 playCollection 入队，
 * 曲目行 ⋯/长按 = 单曲操作面板，顶栏 ＋ = 添加歌曲面板（仅非动态歌单），
 * 顶栏更多 = 集合操作面板。
 * 面板经 LocalModalState.show 挂 GlassApp 根层宿主（屏内不再渲染 GlassSheet）；
 * 自绘 Chrome 的让位（modal.isOpen 时 alpha=0，保留组合护 keepOnDispose 几何）维持不变。
 *
 * 本轮结构：可重排分支（自建歌单）仍是 Column+verticalScroll+ReorderableColumn；
 * 其余分支改为 LazyColumn 真懒加载（长专辑/文件夹/流派可达上千行）。两条分支共用
 * [DetailHeader] / [DetailHero] / [DetailTrackRow]，避免大段重复。
 */
@Composable
fun PlaylistDetailScreen(
    state: AppState,
    modifier: Modifier = Modifier,
    /** D2：导出 M3U（id, 标题, 曲目）——SAF 选择器由 app 层持有；null = 不提供入口 */
    onExportM3u: ((playlistId: String, title: String, tracks: List<DemoSong>) -> Unit)? = null,
) {
    // 模态面板宿主（GlassApp 根层）；Chrome 让位同读此状态
    val modal = LocalModalState.current

    // 详情数据缓存——**为什么用 derivedStateOf 而不是 remember**：
    // detailData 的结果取决于 detail 目标、library 引用，以及 favorites / playedIds / playCounts /
    // userPlaylists / addedTracks / hiddenBuiltInPlaylists 这些 SnapshotState 集合。
    // remember 的 key 只认显式传进去的值：集合本身是稳定引用（元素变了引用不变），既不能当 key 也不会失效，
    // 所以纯 remember 会在「收藏一首歌 / 播放次数 +1 / 加歌进歌单」时给出陈旧数据。
    // derivedStateOf 的 lambda 只在**被读**时求值，并把读到的 snapshot 状态登记为依赖：
    // 上述任一集合、目标或曲库变化才重算；进度（progressSec）这类无关状态变化不重算——
    // 这正是全仓此前缺失的机制，也是详情页在播放中被 0.1s tick 拖着反复全库派生的根因。
    // P2-④：scanFilterShort 也是 key —— detailData 走 visibleLibrary()，开关一变
    // 详情页列表/计数必须同步刷新，否则与曲库页口径分叉
    val dataOrNull by remember(state.detail, state.library, state.scanFilterShort) {
        derivedStateOf { state.detailData(state.detail) }
    }
    // 委托属性（by）无法 smart-cast，先落到普通局部 val 再判空；此后 data 为 non-null
    val data = dataOrNull

    if (data == null) {
        // 目标已被删除（如用户歌单）：与返回键同走 backFromDetail（回 detailReturn），
        // 不再硬编码歌单页（P3-41：从曲库其他 tab 进入时被删目标应回原 tab）
        LaunchedEffect(Unit) { state.backFromDetail() }
        Box(modifier.fillMaxSize())
        return
    }

    fun playAll(shuffle: Boolean) {
        state.playCollection(data.tracks, data.title, shuffle)
        state.screen = GlassScreen.Player
    }
    val flight = LocalPlayerFlight.current

    val hazeState = rememberHazeState()
    val pageScroll = rememberScrollState()
    // 可重排判定：自建歌单且展示序与 addedTracks 一一对应（拖拽提交的索引才有意义）
    val reorderable = state.detail.kind == DetailTarget.Kind.Playlist &&
        state.canReorderPlaylist(state.detail.id)

    CompositionLocalProvider(LocalHazeState provides hazeState) {
        Column(modifier.fillMaxSize().statusBarsPadding()) {
            if (reorderable) {
                // 可重排分支保留 Column + verticalScroll + ReorderableColumn：
                // ReorderableColumn 的行数学直接依赖 ScrollState 与「直接父级即滚动内容」
                // （listTop 用 positionInParent 量取、滚动补偿读 scrollState.value 差值），
                // 换成 LazyColumn 会同时破坏这两条前提——这是 v1 已明示的取舍，本轮不动它。
                Column(
                    Modifier
                        .weight(1f)
                        .verticalScroll(pageScroll)
                        .hazeSource(hazeState),
                ) {
                    DetailHeader(state, data, onPlayAll = { playAll(it) }, onExportM3u = onExportM3u)
                    DetailHero(data, onPlayAll = { playAll(it) })

                    // 曲目玻璃卡（.card glass → md 层级）；自建歌单且无失效引用时曲目行可长按拖拽重排
                    GlassSurface(
                        modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 14.dp),
                        level = GlassLevel.Md,
                        shape = RoundedCornerShape(18.dp),
                    ) {
                        Column(Modifier.padding(vertical = 6.dp)) {
                            // 拖拽域 = 自建歌单的 addedTracks 序；列表随页面滚动（autoscroll 关闭：
                            // 页面滚动容器含头图等额外内容，视口估算不成立，长歌单 v1 可接受）
                            // dataVersion = 内容指纹（id 序列）：拖拽期间收藏/播放计数等任何写库
                            // 导致 tracks 位移时，四道防错闸据此作废本次拖拽，堵住不可撤销的错位提交
                            //（P2-17：原先未传 → 默认 null → 全部退化为"永不变"）
                            ReorderableColumn(
                                count = data.tracks.size,
                                rowHeight = SongRowHeight,
                                scrollState = pageScroll,
                                dataVersion = data.tracks.map { it.id },
                                onMove = { from, to -> state.movePlaylistTrack(state.detail.id, from, to) },
                            ) { index ->
                                DetailTrackRow(
                                    state = state,
                                    track = data.tracks[index],
                                    tracks = data.tracks,
                                    title = data.title,
                                    // 可重排列表里长按专属于拖拽手势，菜单只走 ⋯ 按钮
                                    reorderable = true,
                                )
                            }
                            if (data.tracks.isEmpty()) EmptyTracksHint()
                        }
                    }
                    Spacer(Modifier.height(18.dp))
                }
            } else {
                // 不可重排分支：真懒加载。原先 repeat(data.tracks.size) 一次性组合全部行
                // （每行还要按需解码封面），长专辑/文件夹/流派/动态歌单下首帧极重。
                // P3-40：LazyColumn 的 key 必须唯一，数据异常出现重复 id 会直接崩溃——
                // 展示序列先按 id 去重（可重排分支不去重：行索引要与 addedTracks 一一对应）；
                // 去重序列同时作为表头计数、isFirst/isLast 判定与播放队列的口径，避免错位
                val listedTracks = remember(data.tracks) { data.tracks.distinctBy { it.id } }
                LazyColumn(Modifier.weight(1f).hazeSource(hazeState)) {
                    item(key = "detail-header") {
                        DetailHeader(state, data, onPlayAll = { playAll(it) }, onExportM3u = onExportM3u)
                    }
                    item(key = "detail-hero") { DetailHero(data, onPlayAll = { playAll(it) }) }

                    if (data.tracks.isEmpty()) {
                        // 空态独占一张完整的卡（与原实现同一形状：单块 glass 卡 + 内边距）
                        item(key = "detail-empty") {
                            GlassSurface(
                                modifier = Modifier.fillMaxWidth()
                                    .padding(start = 20.dp, end = 20.dp, bottom = 14.dp),
                                level = GlassLevel.Md,
                                shape = RoundedCornerShape(18.dp),
                            ) {
                                Column(Modifier.padding(vertical = 6.dp)) { EmptyTracksHint() }
                            }
                        }
                    } else {
                        // 曲目卡：原实现是一整块 GlassSurface 里放 Column 全量行。LazyColumn 的懒项必须是
                        // 直接子级（不能被卡片包住），所以把同一块卡按行切成**连续色带**逐段绘制：
                        // 每段只画自己那条带的填充 + 左右边线，首/末段再补圆角、顶高光线与底边。
                        // 各段填充同色且首尾相接，拼起来与单块卡视觉等价；而且逐段绘制不依赖行高是否恒等于
                        // SongRowHeight（大字号下 SongRow 可能变高，仍是连续带，不会出现接缝）。
                        itemsIndexed(listedTracks, key = { _, song -> song.id }) { index, track ->
                            val isFirst = index == 0
                            val isLast = index == listedTracks.lastIndex
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    // 卡片的左右 20dp 内缩与末段 14dp 外距（同原 GlassSurface 的 modifier）
                                    .padding(
                                        start = 20.dp,
                                        end = 20.dp,
                                        bottom = if (isLast) 14.dp else 0.dp,
                                    )
                                    .detailCardSegment(isFirst = isFirst, isLast = isLast),
                            ) {
                                // 卡片内容原有的上下 6dp 内边距，折进首/末段的绘制范围内（否则卡片会缺一条边）
                                Box(
                                    Modifier.padding(
                                        top = if (isFirst) 6.dp else 0.dp,
                                        bottom = if (isLast) 6.dp else 0.dp,
                                    ),
                                ) {
                                    DetailTrackRow(
                                        state = state,
                                        track = track,
                                        tracks = listedTracks,
                                        title = data.title,
                                        reorderable = false,
                                    )
                                }
                            }
                        }
                    }
                    // 底部避让（与原实现同值）
                    item(key = "detail-bottom-inset") { Spacer(Modifier.height(18.dp)) }
                }
            }

            // 单曲/集合面板打开时 Chrome 让位（与 PlayerScreen 控制台同策略）：
            // graphicsLayer alpha 只隐不卸——keepOnDispose 的迷你条飞行几何保持冻结；
            // ModalState 语义保持「退场动画结束（content 清空）后 Chrome 才复位」，
            // 面板滑出全程 Chrome 保持让位
            GlassChrome(
                modifier = Modifier.graphicsLayer { alpha = if (modal.isOpen) 0f else 1f },
                song = state.currentSong,
                playing = state.playing,
                // 进度以 lambda 下钻：本屏 body 不再读 progressFraction，播放进度每 100ms 变化
                // 只失效 Chrome 的绘制，不会把整屏（含全部曲目行）拖着重组
                progressProvider = { state.progressFraction },
                // 跟随进入详情时的来源屏（0 曲库 / 1 歌单 / 2 设置），不再硬编码「歌单」
                navSelected = state.detailNavSelected,
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
                        // 传来源屏：退出飞行按 entrySource 回详情，不再硬编码曲库（P2-14）
                        f.beginEntry(state.currentSong, state.detailNavSelected, { state.screen = it }, GlassScreen.Detail)
                    } else {
                        f?.abort()
                        state.screen = GlassScreen.Player
                    }
                },
                onPlayPause = state::togglePlayPause,
                onNext = { state.next() },
            )
        }
    }
}

/** 顶栏：返回 / 类别 / ＋添加歌曲（仅非动态歌单） / 更多。两个分支共用。 */
@Composable
private fun DetailHeader(
    state: AppState,
    data: DetailData,
    onPlayAll: (Boolean) -> Unit,
    onExportM3u: ((playlistId: String, title: String, tracks: List<DemoSong>) -> Unit)?,
) {
    val modal = LocalModalState.current
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 2.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        GlassIconButton(onClick = { state.backFromDetail() }) {
            Icon(GlassIcons.Back, null, Modifier.size(20.dp), tint = LocalGlassColors.current.textPrimary)
        }
        Text(data.header, color = LocalGlassColors.current.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.weight(1f))
        // 添加歌曲入口（顶栏 ＋，PageHeader 双动作钮同款惯例）：仅歌单类且非动态歌单——
        // 动态歌单（收藏 / 最近播放 / 最常播放）由状态实时解析，addToPlaylist 写入的叠加层
        // 不参与解析，显示入口只会做出无效操作。统一走 DemoCatalog.dynamicIds 集合判定：
        // 新增动态歌单只需登记一处，不再逐处补 if（漏改曾导致「最常播放」加歌静默无效）。
        // （CollectionActionsSheet 为固定列表组件且冻结，动作项无法内插，入口上移到顶栏）
        if (state.detail.kind == DetailTarget.Kind.Playlist &&
            state.detail.id !in DemoCatalog.dynamicIds
        ) {
            GlassIconButton(
                onClick = {
                    // 添加歌曲面板：目标与现有曲目取打开时刻的详情快照（同集合面板惯例）
                    val playlistId = state.detail.id
                    modal.show {
                        AddSongsSheet(
                            visible = modal.visible,
                            onDismiss = modal::requestDismiss,
                            state = state,
                            playlistId = playlistId,
                            existingTracks = data.tracks,
                            onAdd = { songs -> songs.forEach { state.addToPlaylist(playlistId, it.id) } },
                        )
                    }
                },
                modifier = Modifier.semantics { contentDescription = "添加歌曲" },
                size = 36.dp,
            ) {
                Icon(GlassIcons.Plus, null, Modifier.size(19.dp), tint = LocalGlassColors.current.textPrimary)
            }
        }
        GlassIconButton(
            onClick = {
                // 集合操作面板（顶栏更多）：目标/标题/曲目取打开时刻的详情快照
                modal.show {
                    CollectionActionsSheet(
                        visible = modal.visible,
                        onDismiss = modal::requestDismiss,
                        state = state,
                        target = state.detail,
                        title = data.title,
                        tracks = data.tracks,
                        onPlayAll = onPlayAll,
                        // D2：导出 M3U（SAF 选择器由 app 层拉起）
                        onExportM3u = onExportM3u?.let { export ->
                            { export(state.detail.id, data.title, data.tracks) }
                        },
                    )
                }
            },
            size = 36.dp,
        ) {
            Icon(GlassIcons.More, null, Modifier.size(19.dp), tint = LocalGlassColors.current.textPrimary)
        }
    }
}

/** 沉浸头图 + 标题/meta + 播放/随机胶囊。两个分支共用。 */
@Composable
private fun DetailHero(data: DetailData, onPlayAll: (Boolean) -> Unit) {
    val palette = LocalAuroraPalette.current
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 16.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ArtThumb(
            data.art,
            Modifier
                .size(132.dp)
                .shadow(18.dp, RoundedCornerShape(24.dp))
                .border(1.dp, Color.White.copy(alpha = 0.28f), RoundedCornerShape(24.dp)),
            RoundedCornerShape(24.dp),
            bitmap = data.cover,
            artworkUri = data.coverUri,
        )
        Column(Modifier.weight(1f)) {
            Text(data.title, color = LocalGlassColors.current.textPrimary, fontSize = 20.sp,
                fontWeight = FontWeight.Bold, lineHeight = 26.sp,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(6.dp))
            Text(data.meta, color = LocalGlassColors.current.textSecondary, fontSize = 12.sp)
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // 播放：seed 填充胶囊 + seed 色投影
                val playInteraction = remember { MutableInteractionSource() }
                Row(
                    Modifier
                        .pressScale(playInteraction, pressed = 0.95f)
                        .shadow(
                            8.dp, CircleShape, clip = false,
                            ambientColor = palette.seed.copy(alpha = 0.45f),
                            spotColor = palette.seed.copy(alpha = 0.45f),
                        )
                        .clip(CircleShape)
                        .background(palette.seed)
                        .clickable(interactionSource = playInteraction, indication = null) { onPlayAll(false) }
                        .padding(horizontal = 20.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(GlassIcons.Play, null, Modifier.size(16.dp), tint = Color.White)
                    Text("播放", color = Color.White, fontSize = 13.sp) // seed 实底上保持白字
                }
                // 随机：玻璃胶囊（玻璃底上用主题文字色，浅色下保持对比度）
                GlassSurface(level = GlassLevel.Md, shape = CircleShape, onClick = { onPlayAll(true) }) {
                    Row(
                        Modifier.padding(horizontal = 15.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(GlassIcons.Shuffle, null, Modifier.size(16.dp), tint = LocalGlassColors.current.textPrimary)
                        Text("随机", color = LocalGlassColors.current.textPrimary, fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

/** 曲目行：可重排 / 懒加载两个分支共用（状态读取被隔离在本行，切歌只重组行、不牵连整屏） */
@Composable
private fun DetailTrackRow(
    state: AppState,
    track: DemoSong,
    tracks: List<DemoSong>,
    title: String,
    reorderable: Boolean,
) {
    val modal = LocalModalState.current
    SongRow(
        song = track,
        squareThumb = true,
        playing = track.id == state.currentSong.id,
        wavePlaying = state.playing,
        onMoreClick = {
            // 单曲面板：歌单语境提供「从歌单移除」（语境 id 读当下 detail，与旧判定一致）
            modal.show {
                SongActionsSheet(
                    visible = modal.visible,
                    onDismiss = modal::requestDismiss,
                    state = state,
                    song = track,
                    playlistContextId = if (state.detail.kind == DetailTarget.Kind.Playlist) state.detail.id else null,
                )
            }
        },
        // 可重排列表里长按专属于拖拽手势，菜单只走 ⋯ 按钮（避免两种长按语义打架）
        onLongClick = if (reorderable) null else ({
            modal.show {
                SongActionsSheet(
                    visible = modal.visible,
                    onDismiss = modal::requestDismiss,
                    state = state,
                    song = track,
                    playlistContextId = if (state.detail.kind == DetailTarget.Kind.Playlist) state.detail.id else null,
                )
            }
        }),
        onClick = {
            state.playSong(track, tracks, title)
            state.screen = GlassScreen.Player
        },
    )
}

/** 空曲目提示（两个分支共用，样式与原实现逐字一致） */
@Composable
private fun EmptyTracksHint() {
    Text(
        "这里还没有歌曲",
        color = LocalGlassColors.current.textTertiary, fontSize = 13.sp,
        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
        textAlign = TextAlign.Center,
    )
}

/**
 * 曲目卡的「分段」外观：LazyColumn 的懒项不能被一整块 GlassSurface 包住，因此把一张
 * 18dp 圆角 / 1dp 描边 / 顶部高光线的 md 玻璃卡，按行切成首尾相接的色带。
 *
 * 为什么按 index 判定首/末、而不是「整卡画一次再按偏移裁剪」：后者需要知道每行相对卡片顶部的
 * 偏移，也就隐含「所有行高恒等于 SongRowHeight」——大字号下 SongRow 会长高，一旦假设不成立就是
 * 错位接缝。分段画与行高完全解耦。
 *
 * 填充走 clip + background（与 GlassSurface 同路子），**不要**自己再叠一层半透明 drawRect：
 * glass.md 是 alpha≈0.10 的白色，同层绘制两次会把圆角之外那几条边压深（视觉上出现一道暗带）。
 * 描边则只能手画：Modifier.border 会画满四条边，中段需要的是「只有左右两条」——
 * 否则每行之间都会多一条 1dp 横线，拼接后被看成 N 张卡而不是一张卡。
 */
@Composable
private fun Modifier.detailCardSegment(isFirst: Boolean, isLast: Boolean): Modifier {
    val glass = LocalGlassColors.current
    val corner = 18.dp
    val shape = RoundedCornerShape(
        topStart = if (isFirst) corner else 0.dp,
        topEnd = if (isFirst) corner else 0.dp,
        bottomStart = if (isLast) corner else 0.dp,
        bottomEnd = if (isLast) corner else 0.dp,
    )
    return this
        .clip(shape)
        .background(glass.md)
        .drawBehind {
            val radius = corner.toPx()
            val strokeW = 1.dp.toPx()
            val half = strokeW / 2f // 描边内缩半像素：与 Modifier.border 被 clip 后的观感一致
            val w = size.width
            val h = size.height
            val arcR = (radius - half).coerceAtLeast(0f)
            val arcSize = Size(arcR * 2f, arcR * 2f)
            val stroke = Stroke(strokeW)
            val strokeColor = glass.strokeSoft

            // 左右竖边：首/末段从圆角之后起画（圆角那段由下面的弧线负责），中段通高
            val sideTop = if (isFirst) half + arcR else 0f
            val sideBottom = if (isLast) h - half - arcR else h
            drawLine(strokeColor, Offset(half, sideTop), Offset(half, sideBottom), strokeW)
            drawLine(strokeColor, Offset(w - half, sideTop), Offset(w - half, sideBottom), strokeW)

            if (isFirst) {
                // 上圆角（左上 180°→270°、右上 270°→360°）+ 顶边
                drawArc(strokeColor, 180f, 90f, false, Offset(half, half), arcSize, style = stroke)
                drawArc(strokeColor, 270f, 90f, false, Offset(w - half - arcR * 2f, half), arcSize, style = stroke)
                drawLine(strokeColor, Offset(half + arcR, half), Offset(w - half - arcR, half), strokeW)
                // 顶部高光线：与 GlassSurface 的 glassTopLine 同参数，只在首段画一次（否则每行一道）
                drawLine(
                    brush = Brush.horizontalGradient(
                        listOf(Color.Transparent, glass.topLine, Color.Transparent),
                        startX = w * 0.08f,
                        endX = w * 0.92f,
                    ),
                    start = Offset(w * 0.08f, 0.5f),
                    end = Offset(w * 0.92f, 0.5f),
                    strokeWidth = strokeW,
                )
            }
            if (isLast) {
                // 下圆角（左下 90°→180°、右下 0°→90°）+ 底边
                val bottomTop = h - half - arcR * 2f
                drawArc(strokeColor, 90f, 90f, false, Offset(half, bottomTop), arcSize, style = stroke)
                drawArc(strokeColor, 0f, 90f, false, Offset(w - half - arcR * 2f, bottomTop), arcSize, style = stroke)
                drawLine(strokeColor, Offset(half + arcR, h - half), Offset(w - half - arcR, h - half), strokeW)
            }
        }
}
