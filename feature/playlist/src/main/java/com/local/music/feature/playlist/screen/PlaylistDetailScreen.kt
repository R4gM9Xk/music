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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
import com.local.music.core.ui.theme.GlassText
import com.local.music.core.ui.theme.LocalAuroraPalette
import com.local.music.core.ui.theme.LocalGlassColors
import com.local.music.core.ui.theme.R
import com.local.music.core.ui.theme.onSeedColor
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
        state.openPlayer()
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

                    // E1 视觉审计（曲目列表一律裸行）：此处原有 GlassSurface(Md) 玻璃卡外壳，已去掉。
                    // 为什么裸行：曲库页 / 搜索结果 / 队列面板的歌单行都是裸行，唯独详情页把同一批
                    // SongRow 关进一张玻璃卡——同一行组件在三个页面呈现两种层级，读者要靠卡片边缘
                    // 才能判断"这些是歌"，而歌名/副标题本身已经承担了全部信息。卡片还额外吃掉
                    // 左右各 20dp，使详情页行文字比曲库页深缩 20dp，跨页切换时列表像"抖"了一下。
                    //
                    // 为什么**只**去掉外壳、保留这层 Column：ReorderableColumn 的 KDoc 前提是
                    // 「位于 scrollState 对应滚动容器的内容里」，且 autoscroll 为 false（本页不启用
                    // 边缘自动滚动——滚动容器含头图等额外内容，视口估算不成立）。包裹层结构与改前
                    // 逐字相同，拖拽的两条数学（行高网格、scrollState 值差补偿）因此零变化。
                    // 行内边距（SongRow 自带 6dp 外边距 + 14dp 内边距 = 20dp 页面边距）正好落在
                    // 全 App 统一的 PageMargin 上，裸行后无需再补任何左右内缩。
                    //
                    // 保留下来的 14dp 下内距：改前它是 GlassSurface 自己的外距（卡片底边到下一元素
                    // 的呼吸），卡去掉后这段空白仍需要——否则最后一行会紧贴迷你条。上方的 6dp
                    // （卡片内距）则按 E1 一并去掉，让首行直接承接 Hero 的 16dp 下距。
                    Column(Modifier.fillMaxWidth().padding(bottom = 14.dp)) {
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
                    Spacer(Modifier.height(16.dp))
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
                        // 空态：与可重排分支同样走裸行（E1），不再独占一张 18dp 玻璃卡。
                        // 空态本来就是一句话，套卡片会让"没有内容"这件事看起来像一块内容。
                        item(key = "detail-empty") { EmptyTracksHint() }
                    } else {
                        // E1：曲目列表一律裸行。改前是「一整块 GlassSurface 被切成连续色带」的
                        // 手绘方案（detailCardSegment），一行为一段填充 + 左右边线，首末段补圆角与
                        // 顶高光线——为的是在 LazyColumn 里伪造一张不可分割的玻璃卡。卡本身去掉
                        // 之后，这套分段绘制失去存在理由，函数一并删除。
                        //
                        // 关键约束（本次结构改动的风险点，逐条核对过）：
                        // - item key 逻辑**一字未改**：仍是 song.id（P3-40 的唯一性约定），
                        //   去重仍由上面的 listedTracks 负责 → 不会引入 "Key already used" 崩溃；
                        // - DetailTrackRow 的四个实参完全不变（tracks 仍传去重后的 listedTracks，
                        //   与播放队列口径一致）；
                        // - 每行的 Item 结构从 Box(两三层) 变成直接 DetailTrackRow，
                        //   组合层级变浅、行高仍由 SongRow 内容撑开 → 懒加载测量行为只变简单。
                        // 左右 20dp 不再补：SongRow 自带 6dp 外边距 + 14dp 内边距，
                        // 裸行后行文字正好落在全 App 统一的 20dp 页面边距上（与曲库页对齐）。
                        itemsIndexed(listedTracks, key = { _, song -> song.id }) { _, track ->
                            DetailTrackRow(
                                state = state,
                                track = track,
                                tracks = listedTracks,
                                title = data.title,
                                reorderable = false,
                            )
                        }
                    }
                    // 底部避让（原 18，归 16 刻度）
                    item(key = "detail-bottom-inset") { Spacer(Modifier.height(16.dp)) }
                }
            }

            // 单曲/集合面板打开时 Chrome 让位（与 PlayerScreen 控制台同策略）：
            // graphicsLayer alpha 只隐不卸——keepOnDispose 的迷你条飞行几何保持冻结；
            // ModalState 语义保持「退场动画结束（content 清空）后 Chrome 才复位」，
            // 面板滑出全程 Chrome 保持让位
            // A3-08：让位 alpha 走 tweenPage 渐变——旧实现 0/1 硬切，与面板 360ms 进出场
            // 节奏脱节（SearchScreen 同款；animateFloatAsState 只在此低频状态变化时重组）
            val chromeAlpha by androidx.compose.animation.core.animateFloatAsState(
                targetValue = if (modal.isOpen) 0f else 1f,
                animationSpec = com.local.music.core.ui.theme.GlassMotion.tweenPage(),
                label = "detailChromeAlpha",
            )
            GlassChrome(
                modifier = Modifier.graphicsLayer { alpha = chromeAlpha },
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
                        f.beginEntry(state.currentSong, state.detailNavSelected, { state.openPlayer() }, GlassScreen.Detail)
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
        // 读屏（无障碍 P0-4）：这是一个只画了 ← 箭头的玻璃圆钮，**没有任何可见文字**。
        // 旧实现完全没挂语义——TalkBack 停上去只念「按钮」，盲用户在详情页里找不到
        // 返回入口（Android 系统返回手势对读屏用户存在，但读屏用户更依赖显式按钮，
        // 且这是页面上唯一的返回控件，缺描述等于该功能对特定人群不存在）。
        // role + contentDescription 都补：GlassIconButton 内部只有 clickable（无 role），
        // 不补 role 读屏不会提示「双击激活」。
        GlassIconButton(
            onClick = { state.backFromDetail() },
            modifier = Modifier.semantics {
                this.role = Role.Button
                this.contentDescription = DetailA11y.BACK
            },
        ) {
            // 有意 null、非遗漏：语义已挂在外层圆钮上，子图标再给描述会重复播报
            Icon(GlassIcons.Back, null, Modifier.size(20.dp), tint = LocalGlassColors.current.textPrimary)
        }
        // A1 字号 token：16sp Bold 三连 → GlassText.TitleCompact（16 Bold 22）。
        // 顶栏页名与下方 Hero 大标题刻意差两档（16/20），靠这里定死的档位表达，不再散在调用点
        Text(data.header, color = LocalGlassColors.current.textPrimary, style = GlassText.TitleCompact)
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
                modifier = Modifier.semantics {
                    this.role = Role.Button
                    this.contentDescription = DetailA11y.ADD_SONGS
                },
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
            // 读屏（无障碍 P0-4）：与返回钮同款——只有 ⋯ 图形、无可见文字，
            // 旧实现完全无描述。这是集合操作面板（播放全部/重命名/删除歌单/导出 M3U）
            // 的**唯一入口**，缺描述等于整组集合管理功能对读屏用户不可达。
            modifier = Modifier.semantics {
                this.role = Role.Button
                this.contentDescription = DetailA11y.MORE
            },
            size = 36.dp,
        ) {
            // 有意 null、非遗漏：语义已挂在外层圆钮上
            Icon(GlassIcons.More, null, Modifier.size(19.dp), tint = LocalGlassColors.current.textPrimary)
        }
    }
}

/** 沉浸头图 + 标题/meta + 播放/随机胶囊。两个分支共用。 */
@Composable
private fun DetailHero(data: DetailData, onPlayAll: (Boolean) -> Unit) {
    val palette = LocalAuroraPalette.current
    // A4 对比度：压在 palette.seed 实底上的前景色必须由 seed 亮度派生，不能写死白色。
    // 项目允许用户把主题色设成琥珀 #E0A800 / 草绿 #57A94A，白字压上去实测对比只有 ≈2:1
    // ——"播放"这颗全页最重要的按钮反而成了最难读的元素。onSeedColor() 按 WCAG 相对亮度
    // 阈值 0.5 在近黑/白之间二选一，浅色系 seed 自动转黑字。
    //
    // **必须在组合作用域内取值**：onSeedColor() 是 @Composable（它读 LocalAuroraPalette），
    // 不能塞进 clickable / Icon 的 lambda 里裸调——那不在组合作用域内，编译直接报错。
    // 这里在 DetailHero 函数体（组合期）取一次，两个消费点共用同一个色值。
    val onSeed = onSeedColor()
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 16.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ArtThumb(
            data.art,
            Modifier
                .size(132.dp)
                .shadow(18.dp, RoundedCornerShape(R.Card24.dp))
                // D5 硬编码白清扫：描边原写死 Color.White.copy(alpha = 0.28f)，浅色模式下
                // 白描边压在近白极光 + 浅玻璃上等于**完全消失**，132dp 大封面失去边缘定义。
                // stroke token 深色档本就是"白 0.28"（观感零变化），浅色档自动反转为近黑 0.14，
                // 这是 token 存在的意义——同一处描边在两套主题下都画得出来。
                .border(1.dp, LocalGlassColors.current.stroke, RoundedCornerShape(R.Card24.dp)),
            RoundedCornerShape(R.Card24.dp),
            bitmap = data.cover,
            artworkUri = data.coverUri,
        )
        Column(Modifier.weight(1f)) {
            // A1 字号 token：20sp Bold / lineHeight 26 三连 → GlassText.Title。
            // 重要：行高原本裸写 26.sp 与新 token 的 LHTitle 同为 26——这里不是"顺手换个数"，
            // 而是让 20/26 这组比值从"调用点手抄"变成"token 唯一定义"，后续调整只改一处
            Text(data.title, color = LocalGlassColors.current.textPrimary, style = GlassText.Title,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(6.dp))
            // A1：meta 12sp → GlassText.Caption（12 Normal 16），与全 App 副标题同档
            Text(data.meta, color = LocalGlassColors.current.textSecondary, style = GlassText.Caption)
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
                        // 读屏（无障碍 P0-4）：胶囊内有「播放」二字可念，但 clickable 只提供
                        // onClick，读屏不会提示「按钮 / 双击激活」。补 role 即可，不写
                        // contentDescription——可见文字「播放」本身就是最好的描述，
                        // 写死会与之双源漂移。子图标有意 null（文字已表达同一件事）。
                        .semantics { this.role = Role.Button }
                        .padding(horizontal = 20.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    // 有意 null、非遗漏：同行「播放」文字已表达语义
                    // A4：tint 走 onSeed（压在 seed 实底上按亮度反转），不再写死 Color.White
                    Icon(GlassIcons.Play, null, Modifier.size(16.dp), tint = onSeed)
                    // A1：13sp 裸字号 → GlassText.BodyMedium（13 Medium 18）；
                    // A4：文字色同上改 onSeed——琥珀 seed 下白字不可读
                    Text("播放", color = onSeed, style = GlassText.BodyMedium)
                }
                // 随机：玻璃胶囊（玻璃底上用主题文字色，浅色下保持对比度）
                // 读屏：GlassSurface 内部走 combinedClickable，同样不带 role，这里补上
                GlassSurface(
                    level = GlassLevel.Md,
                    shape = CircleShape,
                    onClick = { onPlayAll(true) },
                    modifier = Modifier.semantics { this.role = Role.Button },
                ) {
                    Row(
                        Modifier.padding(horizontal = 15.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        // 有意 null、非遗漏：同行「随机」文字已表达语义
                        Icon(GlassIcons.Shuffle, null, Modifier.size(16.dp), tint = LocalGlassColors.current.textPrimary)
                        // A1：13sp → GlassText.BodyMedium，与「播放」胶囊同档（两个胶囊视觉等重）
                        Text("随机", color = LocalGlassColors.current.textPrimary, style = GlassText.BodyMedium)
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
            state.openPlayer()
        },
    )
}

/** 空曲目提示（两个分支共用，样式与原实现逐字一致） */
@Composable
private fun EmptyTracksHint() {
    Text(
        "这里还没有歌曲",
        // A1：13sp 裸字号 → GlassText.Body（13 Normal 18）
        color = LocalGlassColors.current.textTertiary, style = GlassText.Body,
        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
        textAlign = TextAlign.Center,
    )
}

/**
 * 歌单/专辑详情页顶栏按钮的读屏文案（无障碍 P0-4）。
 *
 * 这一页的顶栏是「纯图标、零可见文字」的：← 返回、＋ 添加歌曲、⋯ 更多。
 * 对明眼用户，图标形状 + 位置约定就够；对读屏用户，没有描述的图标节点等于不存在。
 * 三个文案集中在这里，下一轮 task-5 抽取 strings.xml 时整体搬走即可。
 *
 * 注意顶部「播放 / 随机」两个胶囊**不在**此列：它们有可见文字「播放」「随机」，
 * 读屏靠节点内文字即可朗读，只补了 role = Button，不写死 contentDescription
 * （写死会和界面文字形成两个来源，改文案时极易只改一处）。
 */
private object DetailA11y {
    const val BACK = "返回"
    const val ADD_SONGS = "添加歌曲"
    const val MORE = "更多歌单操作"
}
