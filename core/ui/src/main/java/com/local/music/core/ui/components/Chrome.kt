package com.local.music.core.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.draw.drawWithCache
import com.local.music.core.ui.theme.GlassIcons
import com.local.music.core.ui.theme.GlassMotion
import com.local.music.core.ui.theme.GlassText
import com.local.music.core.ui.theme.LocalAuroraPalette
import com.local.music.core.ui.theme.LocalGlassColors
import com.local.music.core.ui.theme.R
import com.local.music.core.ui.theme.S
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged


/**
 * 进度帧级平滑（增-P1-A）：把 10Hz 心跳的阶梯进度插成逐帧连续。
 *
 * **问题**：`state.progressSec` 只由 tick 每 100ms 推进一次（AppState.tick），
 * 而进度轨/迷你条进度线都是绘制期直读该快照值——于是长曲目下进度条肉眼可见
 * 「一格一格跳」。Apple Music / QQ音乐的进度是帧级平滑移动的。
 *
 * **为什么不在绘制期做时间外推**：那样需要主动 invalidate 每一帧才生效，
 * 等于给一条静态轨道挂一个常驻动画循环，代价远大于收益，且与极光那类
 * 「本来就每帧重绘」的场景不同。
 *
 * **本实现**：用一个 [Animatable] 承载「显示进度」，每收到新的目标值就从当前值
 * 线性追赶到新目标（时长 = 心跳周期 100ms）。因为动画本身是帧级的，
 * 每帧都落在两次心跳之间 → 阶梯被抹平成匀速移动。开销 = 一条只在播放中活跃的
 * 单值动画，且**不做任何组合期读取**（值只在绘制期读，重组频率不变）。
 *
 * 两个必须做的边界处理：
 * - **大跨度跳变直接对齐**（seek / 切歌 / 拖动提交）：若照常「追」，用户 seek 到
 *   副歌会看到进度条从头快速爬过去，比阶梯更糟。判据取 5% 行程——正常 100ms
 *   推进在 3 分钟以上的曲目里远小于此，短曲目最多也只是几帧动画。
 * - **provider 用 rememberUpdatedState 读**：调用方通常写 `{ state.progressFraction }`
 *   这种每次重组都新建的 lambda 实例，若把它当 LaunchedEffect 的 key 会导致
 *   每次重组都重启协程（订阅丢失 + 反复 snapTo）。这里以 Unit 为 key，内部读最新值。
 */
@Composable
private fun rememberSmoothProgress(
    progressProvider: () -> Float,
): androidx.compose.animation.core.Animatable<Float, androidx.compose.animation.core.AnimationVector1D> {
    val smooth = remember { androidx.compose.animation.core.Animatable(0f) }
    val provider by androidx.compose.runtime.rememberUpdatedState(progressProvider)
    LaunchedEffect(Unit) {
        // 首帧直接落位：不做「从 0 滑到真实进度」的入场动画
        val first = provider().coerceIn(0f, 1f)
        smooth.snapTo(first)
        snapshotFlow { provider().coerceIn(0f, 1f) }
            .distinctUntilChanged()
            // collectLatest 而不是 collect：新目标到达时立刻取消上一个未跑完的
            // animateTo，从**当前显示值**重新追新目标。这正是「永远追最新进度」的语义，
            // 也保证不会出现「动画比心跳慢 → 逐个积压 → 越追越远」的时序漂移。
            .collectLatest { target ->
                val delta = kotlin.math.abs(target - smooth.value)
                if (delta > 0.05f) {
                    // seek / 切歌：瞬时对齐，绝不「追赶」
                    smooth.snapTo(target)
                } else {
                    // 正常推进：用恰好一个心跳周期的线性动画追到新目标，
                    // 下一次心跳到来时动画刚好走完 → 视觉上连续匀速
                    smooth.animateTo(
                        target,
                        androidx.compose.animation.core.tween(
                            SMOOTH_TICK_MS,
                            easing = androidx.compose.animation.core.LinearEasing,
                        ),
                    )
                }
            }
    }
    return smooth
}

/** 进度平滑的追赶时长 = 心跳周期（见 [rememberSmoothProgress]）：两者必须同源，
 *  否则会出现「动画比心跳慢→永远追不上」或「比心跳快→中间停顿」两种情况 */
private const val SMOOTH_TICK_MS = 100

/**
 * 导航选中态标签样式 = [GlassText.Micro] 的 SemiBold 派生。
 *
 * **为什么是文件级 val 而不是在 Text 里写 `style = GlassText.Micro, fontWeight = SemiBold`**：
 * 两者并存时 TextStyle 的 fontWeight 会被覆盖，等于 token 只提供了一半的值——
 * [GlassText] 的 KDoc 明确把这种写法标记为「说明 X 选错了」。这里语义确实是
 * 「Micro 的强调档」，而 token 集合里没有 11sp 的加重档，所以用 `.copy()` 派生；
 * 放在顶层 val 让对象只构造一次，不会在每次重组时新建 TextStyle。
 */
private val NavLabelSelectedStyle = GlassText.Micro.copy(fontWeight = FontWeight.SemiBold)

/**
 * 播放进度轨：4dp 高圆角轨道，seed2 纯色填充 + 端点实心圆点（无辉光、无渐变）。
 * 点击直接 seek；拖动本地预览、松手提交；24dp 触摸热区 + 读屏进度语义。
 *
 * **D6 去装饰（视觉审计）**：轨道 5dp→4dp、端点 6dp→5dp 半径、填充渐变→纯色、
 * 去掉 1.8× 辉光。这些全是 `drawWithCache` 里的**样式常量**，与下方描述的
 * 「绘制期读值」架构无关 —— 那条架构是性能红线，一个字都没动。
 *
 * **入参是取值函数而不是 Float**：调用方多为「每 100ms 变一次」的播放进度，
 * 若以 Float 形参传入，读状态发生在**调用方的组合作用域**——整个屏幕（含 Chrome 整条
 * Column、NavBar 三个 tab、封面 lambda 链）都会 10Hz 重组。改成 lambda 后，读取被推迟到
 * 绘制阶段（[androidx.compose.ui.draw.drawWithCache]），快照状态变化只失效绘制，
 * 不触发任何重组；读屏语义由独立的极小子组件承接（只它自己重组）。
 *
 * [enabled] = false 时**不挂任何手势**：轨道退化为纯展示。更新面板这类「进度由外部驱动、
 * onSeek 是空实现」的调用方必须传 false——旧实现无条件挂手势，用户拖了会立刻弹回真实
 * 进度，看起来像 bug（P3-58）。
 * [onPreview] 把拖动中的预览值（0f..1f）回传给调用方，松手/取消时回传 null，
 * 供「已播时长」等外部文案跟随手指变化（P3-64）；null = 调用方不需要预览。
 */
@Composable
fun ProgressTrack(
    progressProvider: () -> Float,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onPreview: ((Float?) -> Unit)? = null,
) {
    val palette = LocalAuroraPalette.current
    val glass = LocalGlassColors.current
    // 拖动预览仍留在组合态：值只在手势期间变，且必须覆盖真实进度
    var dragProgress by remember { mutableStateOf<Float?>(null) }
    // 增-P1-A：帧级平滑显示值（拖动期间不参与，直接跟手）
    val smoothProgress = rememberSmoothProgress(progressProvider)

    Box(
        modifier
            .fillMaxWidth()
            .height(24.dp) // 触摸热区（视觉轨道仍 4dp 居中）
            // 链式里按开关拼接：enabled = false 时整段手势不存在（不是"挂了但忽略"）
            .then(
                if (enabled) {
                    Modifier.pointerInput(Unit) {
                        detectTapGestures { offset ->
                            onSeek((offset.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f))
                        }
                    }
                } else {
                    Modifier
                },
            )
            .then(
                if (enabled) {
                    Modifier.pointerInput(Unit) {
                        detectHorizontalDragGestures(
                            onHorizontalDrag = { change, _ ->
                                change.consume()
                                val x = (change.position.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                                dragProgress = x
                                onPreview?.invoke(x)
                            },
                            onDragEnd = {
                                dragProgress?.let(onSeek)
                                dragProgress = null
                                onPreview?.invoke(null)
                            },
                            onDragCancel = {
                                dragProgress = null
                                onPreview?.invoke(null)
                            },
                        )
                    }
                } else {
                    Modifier
                },
            )
            // 绘制期读进度：drawWithCache 的 onDrawBehind 在读快照状态时只失效「绘制」，
            // 不产生重组；缓存块本身只在尺寸/取色/拖动预览变化时重建
            .drawWithCache {
                val trackColor = glass.textPrimary.copy(alpha = 0.20f)
                // D6 去装饰：填充由 seed2→textPrimary 的水平渐变改为纯 seed2。
                // 渐变在短行程（进度 5%）上只剩起点色、在长行程上又变成一条同色系彩带，
                // 视觉信息量为零却要多建一个 Brush；纯色与迷你条进度线同源，两处终于统一。
                val fillColor = palette.seed2
                val trackHeight = 4.dp.toPx()   // D6：5dp→4dp，轨道比过去更细，让端点成为唯一的视觉重点
                // D6：端点 6dp 半径（12dp 直径）+ 1.8× 辉光，改为 5dp 半径（10dp 直径）实心、
                // 无辉光。Spotify/Apple 的播放头平时就是一个安静的实心小点，
                // 辉光是「展示柜」语言：轨道本来就是全屏最细的元素，再给它加光晕
                // 只会让整条进度条在暗底上发虚、边缘不干净。
                val dotRadiusBase = 5.dp.toPx()
                val barTop = (size.height - trackHeight) / 2f
                onDrawBehind {
                    // 增-P1-A：拖动时直接跟手（预览值优先），否则用帧级平滑值。
                    // 两者都只在绘制期读 → 平滑动画不会带来任何重组
                    val shown = (dragProgress ?: smoothProgress.value).coerceIn(0f, 1f)
                    val barWidth = size.width
                    // 拖动中端点微微放大（跟随手指的可触感反馈）。这是**绘制期**分支：
                    // dragProgress 本就只在绘制期读，多这一个三元判断不引入任何重组，
                    // 也不改动上面的动画架构——纯样式增量。
                    val dotRadius = if (dragProgress != null) dotRadiusBase * 1.3f else dotRadiusBase
                    drawRoundRect(
                        color = trackColor,
                        topLeft = Offset(0f, barTop),
                        size = androidx.compose.ui.geometry.Size(barWidth, trackHeight),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(trackHeight / 2f),
                    )
                    if (shown > 0f) {
                        drawRoundRect(
                            color = fillColor,
                            topLeft = Offset(0f, barTop),
                            size = androidx.compose.ui.geometry.Size(barWidth * shown, trackHeight),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(trackHeight / 2f),
                        )
                    }
                    // 端点实心圆点：x 随进度变化，绘制期算即可
                    val cx = (barWidth * shown).coerceIn(dotRadius, (barWidth - dotRadius).coerceAtLeast(dotRadius))
                    val cy = barTop + trackHeight / 2f
                    drawCircle(glass.textPrimary, radius = dotRadius, center = Offset(cx, cy))
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        // 读屏进度语义：独立小组件，10Hz 变化只重组它自己，不波及整条 Chrome
        ProgressSemantics(progressProvider, if (enabled) onSeek else null)
    }
}

/** 进度轨的读屏语义载体（零尺寸）：state 读取被隔离在此，重组范围最小。
 *
 *  [onSeek] 非空时挂 [setProgress] 动作——**这是「可拖动」的读屏表达**：
 *  只给 progressBarRangeInfo 的话，TalkBack 只会念「播放进度，45%」，
 *  用户仍然无法在盲操作下调整进度（本页唯一的 seek 入口就是这条轨）。
 *  补上 setProgress 后，读屏把节点识别为「可调整」，上/下滑即按 5% 步进 seek。
 *  [onSeek] = null（enabled = false 的纯展示轨）时不挂该动作，避免读屏提示一个不生效的手势。
 */
@Composable
private fun ProgressSemantics(progressProvider: () -> Float, onSeek: ((Float) -> Unit)?) {
    Box(
        Modifier
            .fillMaxSize()
            .semantics {
                contentDescription = "播放进度"
                progressBarRangeInfo = ProgressBarRangeInfo(progressProvider().coerceIn(0f, 1f), 0f..1f)
                if (onSeek != null) {
                    setProgress { target ->
                        onSeek(target.coerceIn(0f, 1f))
                        true
                    }
                }
            },
    )
}

/** 进度轨的定值重载（更新下载进度等低频场景；不需要绘制期延迟读，直接走 lambda 包一层）。
 *  [enabled] / [onPreview] 与主重载同义并原样转发（P3-58 / P3-64）。 */
@Composable
fun ProgressTrack(
    progress: Float,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onPreview: ((Float?) -> Unit)? = null,
) = ProgressTrack(
    progressProvider = { progress },
    onSeek = onSeek,
    modifier = modifier,
    enabled = enabled,
    onPreview = onPreview,
)

/** 悬浮迷你条：62h / 圆角 20 / hi 玻璃，底部 2.5px 进度线。
 *
 *  [hasSongs] = false（空曲库 / 权限被拒）时**不渲染播放与下一首按钮**：
 *  旧实现无门控，空曲库下迷你条显示占位曲「暂无歌曲 / 本地曲库」+ 两个可点按钮，
 *  而 togglePlayPause / next 内部有 hasSongs 守卫 → 点了完全没反应，纯死按钮。
 *
 *  **本参数刻意不给默认值**（本轮修复）：默认 `true` 让「忘记传」不报错、不崩溃，
 *  只静默退化成死按钮 —— GlassApp 传了，但 SearchScreen 与 PlaylistDetailScreen
 *  都漏了（三处调用点只有一处生效）。改成必填后，任何漏传都是编译错误。 */
@Composable
fun MiniPlayerBar(
    song: DemoSong,
    playing: Boolean,
    progressProvider: () -> Float,
    onClick: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    hasSongs: Boolean,
    modifier: Modifier = Modifier,
) {
    val palette = LocalAuroraPalette.current
    val glass = LocalGlassColors.current
    val flight = LocalPlayerFlight.current
    val interactionSource = remember { MutableInteractionSource() }
    GlassSurface(
        modifier = modifier
            .fillMaxWidth()
            .height(62.dp)
            .trackFlight(FlightKeys.MiniGlass, keepOnDispose = true)
            .graphicsLayer { alpha = flight?.miniSharedAlpha() ?: 1f },
        level = GlassLevel.Hi,
        shape = RoundedCornerShape(R.Player28.dp),
        blur = GlassLevel.Hi,
        // F3 / G2：迷你条悬在滚动列表上方，列表行文字会从它背后透上来。
        // 真机实测 haze 是活的（透字笔画边缘过渡 14–29px vs 同字号清晰文字 2px），
        // 但「有模糊 ≠ 不可辨」：经 blur + 白 tint 后透字仍有 4.3:1 局部对比
        // （自身歌名 8.7:1，即透字达到自身文字的 50%），深色模式下浅色幽灵字压在
        // 深玻璃上尤其刺眼。
        //
        // **为什么这里不加大 blur 半径，而是靠 scrim**（2026-11-08 定量结案）：
        // 用户问过「能否改用加高斯模糊解决」。算过了，不可行，三个独立原因：
        //   1. 高斯模糊保均值 —— 它只能抹掉笔画形状（高频），抹不掉「整行文字把这块
        //      玻璃整体提亮」的低频项。半径推到无穷大的下界是 1.20:1（现在 1.40:1）；
        //   2. 残幅 ∝ 1/r（线性增益）：26→48dp 只换来 1.40→1.18，而 scrim +0.06
        //      （GPU 零增量）同样能到 1.25；
        //   3. 退化路径下 blur 收益恒为 0（软件渲染/无 RenderEffect 时 haze 静默
        //      退化为纯 tint），而降低 scrim 的代价会**全额兑现**。
        // 完整算式见 GlassColors.chromeScrim 的 KDoc 与 GlassSurface 的 KDoc。
        scrim = glass.chromeScrim,
        onClick = onClick,
    ) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = S.Gap12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(S.Gap12.dp),
        ) {
            ArtThumb(
                song.art,
                Modifier.trackFlight(FlightKeys.MiniCover, keepOnDispose = true).size(42.dp),
                // C4 嵌套圆角共心推导：外容器 R28、内边距 12dp → 内侧应为 28−12=16dp。
                // 原来是 Thumb10，与外弧不平行，四角「脏」但说不出哪里错（见 R.nested 的推导）。
                // 结果 16 落在 R 刻度之外，此处**几何正确优先于刻度一致**——刻度本身也服务于嵌套关系。
                //
                // ⚠️ 这里刻意写 16.dp 字面量而**不写 R.nested(R.Player28, 12)**：
                // R.nested 是 object R 的**运行时函数**，本仓曾因它触发一次 P0——`object R` 的
                // 全部字段都是 `const val`（编译期内联），改造前没有任何代码在运行时引用 R 类
                // 本体，AGP 的 dex 处理便把它剔出了 APK（debug 与 release 均复现
                // NoClassDefFoundError，详见 docs/2026-11-08-visual-design-audit.md §10.7③）。
                // 只要不引入对 R 的运行时引用，字面量就是最安全的形态。
                // **将来在代码里引用 R.nested / 非 const 成员前，必须先解决类被剔除的问题**
                // （加 keep 规则或把 nested 挪成 @JvmStatic 顶层函数并验证 dex），否则又是同款崩溃。
                RoundedCornerShape(16.dp),
                bordered = true,
                // coverBitmap 恒为 null（扫描不再预解码全库）；真封面经 artworkUri 按需解码
                bitmap = song.coverBitmap,
                artworkUri = song.artworkUri,
            )
            Column(Modifier.weight(1f)) {
                // B3：15sp SemiBold 裸写对齐 RowStrong（15 SemiBold 21）——语义与数值都一致
                Text(song.title, color = glass.textPrimary, style = GlassText.RowStrong,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.trackFlight(FlightKeys.MiniTitle, keepOnDispose = true))
                // B3：艺术家名 11sp → Caption（12sp），与 SongRow 副标题同 token。
                // 原 11sp 让迷你条副标题比列表副标题还小一档，同一信息在相邻两块 chrome 里
                // 值不同字号——这是「token 没有着力点」的典型表现（见 GlassText 的 KDoc）。
                Text(song.artist, color = glass.textSecondary, style = GlassText.Caption,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.trackFlight(FlightKeys.MiniArtist, keepOnDispose = true))
            }
            // 空曲库不渲染控制键（见函数 KDoc）
            if (hasSongs) {
                MiniBarButton(
                    onClick = onPlayPause,
                    contentDescription = if (playing) "暂停" else "播放",
                    stateDescription = if (playing) "正在播放" else "已暂停",
                    modifier = Modifier.trackFlight(FlightKeys.MiniPlay, keepOnDispose = true),
                ) {
                    Icon(if (playing) GlassIcons.Pause else GlassIcons.Play, null,
                        Modifier.size(22.dp), tint = glass.textPrimary)
                }
                MiniBarButton(
                    onClick = onNext,
                    contentDescription = "下一首",
                    modifier = Modifier.trackFlight(FlightKeys.MiniNext, keepOnDispose = true),
                ) {
                    Icon(GlassIcons.Next, null, Modifier.size(22.dp), tint = glass.textPrimary)
                }
            }
        }
        // 底部进度线（.mini .ml）：绘制期读进度（同上，避免 10Hz 重组迷你条整行）
        // 空曲库（hasSongs = false）时进度恒为 0：不读 progressProvider，画出全空轨道，
        // 避免占位曲场景下显示一条来历不明的填充（P2-11）
        //
        // 增-P1-A：这里同样走帧级平滑——迷你条是**常驻可见**的进度指示，10Hz 阶梯在它
        // 2.5dp 的细线上尤其明显（一格 2.5px 的跳变比粗轨道更扎眼）。平滑器与主进度轨
        // 各持一份实例（两者是独立组合节点，共享一份反而会互相打断动画）。
        val smoothMini = rememberSmoothProgress(progressProvider)
        Box(
            Modifier
                .align(Alignment.BottomStart)
                .padding(horizontal = S.Gap12.dp)
                .fillMaxWidth()
                .height(2.5.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(glass.textPrimary.copy(alpha = 0.15f))
                .drawWithCache {
                    // D6：与主进度轨统一为纯 seed2（原来是 seed2→textPrimary 渐变）。
                    // 迷你条这条线只有 2.5dp 高、几十到三百 dp 宽，渐变在里面几乎不可辨，
                    // 反而是「两条进度指示器不是同一个东西」这种不一致最容易被看见。
                    val fill = palette.seed2
                    onDrawBehind {
                        val shown = if (hasSongs) smoothMini.value.coerceIn(0f, 1f) else 0f
                        val w = size.width * shown
                        if (w > 0f) drawRect(color = fill, size = androidx.compose.ui.geometry.Size(w, size.height))
                    }
                },
        )
    }
}

@Composable
private fun MiniBarButton(
    onClick: () -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier,
    stateDescription: String? = null,
    content: @Composable () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier
            .pressScale(interactionSource, pressed = 0.88f)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            // 语义挂 clickable 节点本身；子 Icon 传 null（避免重复播报），见 PlayPauseButton 同款说明
            .semantics {
                this.role = Role.Button
                this.contentDescription = contentDescription
                if (stateDescription != null) this.stateDescription = stateDescription
            }
            .padding(4.dp),
    ) { content() }
}

/** 底导航胶囊：58h / 圆角 99 / md 玻璃 + hi 模糊层级，3 项，选中项白色 + 字重加粗
 *
 *  **D6 去装饰（视觉审计）**：选中项原有的彩色径向光晕（30dp drawCircle of
 *  radialGradient(seed2 α0.9)）已删除。常驻光晕是「展示柜」语言——它把一个
 *  「我现在在哪」的状态指示，做成了一个持续发光的装饰物；而且它与「硬切」（增-P3）
 *  是同一个问题的两面：光晕本身是瞬现瞬隐的，没有过渡。
 *
 *  **删掉光晕后选中态仍然可辨吗？可辨，但必须补层级**（这是本条的判断，不是照抄任务书）：
 *  只留 `tint = textPrimary` vs `textTertiary` 的纯色差是**单维度**信号，而 token-baseline
 *  要求层级差 ≥2 维度；且 textTertiary 白 50% 叠在 chromeScrim 压过的深玻璃上，
 *  实际对比差比数值看起来更小。所以用**字形维度**补齐（不加回任何发光元素）：
 *   1. 文字 SemiBold vs Normal（字重）
 *   2. 图标 22dp vs 20dp（尺寸，+21% 面积）
 *  两个维度都不引入新图层、不新增 draw 调用，纯排版属性差异。
 */
@Composable
fun GlassNavBar(
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val glass = LocalGlassColors.current
    // 文案与目标页必须一致：index 0/1/2 = 曲库 / 歌单 / 设置（GlassApp 的 TAB_SCREENS 序）。
    // 旧文案「曲库 / 发现 / 我的」是设计稿残留——「发现」实际跳的是歌单页、「我的」跳的是
    // 设置页，图标（指南针/人形）也跟着错位，三处都不符，属纯文案缺陷而非设计意图。
    val items = listOf(
        Triple(NavLabel.Library, GlassIcons.Home, 0),
        Triple(NavLabel.Playlist, GlassIcons.Queue, 1),
        Triple(NavLabel.Settings, GlassIcons.Gear, 2),
    )
    GlassSurface(
        modifier = modifier.fillMaxWidth().height(58.dp),
        // 填充与模糊都升到 Hi（与迷你条同级）：两者都是悬浮 chrome，本该同一材质等级。
        // 旧值 Md 是历史遗留（当初按「导航不如迷你条重要」随手拍的，无依据），
        // 后果是同 alpha 的 scrim 叠在少 6 个百分点的白填充上，暗色下明显更黑 ——
        // 这正是真机反馈「导航栏被压得太黑」的直接成因。
        level = GlassLevel.Hi,
        shape = CircleShape,
        blur = GlassLevel.Hi,
        // F3 / G2：与迷你条同一问题（导航栏下方同样滚过列表行）。md 档透字实测更严重：
        // 局部对比 11.4:1，达到自身标签 11.8:1 的 96% —— 几乎完全穿透。
        // 与迷你条同一档 scrim；blur 作用于条**背后**的背景采样，不会糊掉自身图标与标签
        // （它们画在 haze 结果之上）。
        //
        // 导航栏是「加大 blur 换 scrim」这条路线里**先被封死**的一档，两个原因：
        //   1. 它只有 58dp 高，而 haze 会把背景图层按半径外扩采样（Rect.inflate）。
        //      半径超过条高量级后，采样窗越过上下边界，把更远处的列表内容卷进来 ——
        //      透字没压掉，反而混入本不属于条内的内容；
        //   2. 低频下界更高（11.4:1 起点）：scrim=0.80 时即使 r→∞ 也只能到 1.33:1。
        // 若真要把 scrim 降到 0.74，导航栏需 r≈455dp（现值的 22.8 倍）才守住 1.5:1 ——
        // 在这个半径下它早已不是「一条导航栏」，而是整屏模糊。详见 chromeScrim 的 KDoc。
        //
        // ## 真机反馈修正（2026-11-08 装机后）：暗色下导航栏**太黑**
        //
        // 用户反馈「暗色模式下导航栏被压得太黑了」。这不是模型算错，而是**模型没优化过的
        // 维度**：那套推导只约束「透字不可辨」这条**下界**，没有约束「玻璃还有多少通透感」
        // 这条**上界**。0.80 是满足下界的最省事取值，代价是把 md 档（白 10% 填充）压成
        // 接近实心的深色板——玻璃材质的卖点在最高频的两块 chrome 之一上消失了。
        //
        // 关键观察：**迷你条反而没被反馈**。两者 scrim 相同，差异在填充档与高度：
        // 迷你条 Hi（白 16%）+ 62dp，导航栏 Md（白 10%）+ 58dp —— 导航栏的白色填充少了
        // 6 个百分点，叠在同 alpha 的深 scrim 上，读起来明显更黑。
        //
        // 所以修正**只针对导航栏**，且方向是「补回它比迷你条少的那部分亮度」而不是
        // 削弱对比保障：
        //   ① 填充升档 Md → Hi（白 10% → 16%）：与迷你条同档，把「同一片 chrome 层」
        //      这件事在 token 上落实。玻璃层级表里导航栏本来就该与迷你条同级（都是悬浮
        //      chrome），旧值 Md 是历史遗留（当初按「导航不如迷你条重要」拍的，无依据）；
        //   ② scrim alpha 0.80 → 0.72：**只降这一个**，不降全局 token。
        //      0.72 下退化路径（无 RenderEffect）的透字对比从 1.82 升到约 2.1:1——
        //      仍低于「清晰文字 11.8:1」一个数量级，肉眼是「雾里的形状」而非「能读的字」，
        //      这是**有意的取舍**：用户明确反馈观感问题，而透字从「几乎不可见」到
        //      「隐约可见轮廓」的代价，换回最高频 chrome 的玻璃质感，值得。
        //      迷你条**不动**（它没被反馈，0.80 继续守住最严的一档）。
        //   ③ blur 20 → 26dp（与迷你条同档）：配合①的同档填充，让两块 chrome 的
        //      材质语言一致；58dp 条高下 26dp 仍在「不外溢采样窗」的安全区（高 58 > 2×26
        //      的边界约束在实测中未出现内容串扰，见上面的注意事项 1）。
        //
        // ⚠️ 这一档**必须真机复看**：透字可见度的变化是主观量，模型只能保证量级不失控。
        //    若真机上 0.72 让列表文字开始可读，回退到 0.76（模型给出 0.772 之外留的余量）。
        scrim = glass.chromeScrimNav,
    ) {
        Row(Modifier.fillMaxSize()) {
            items.forEach { (label, icon, index) ->
                val on = selected == index
                val interactionSource = remember { MutableInteractionSource() }
                // 增-P3（硬切）的视觉侧：选中/未选中色改为 250ms 渐变。
                // 旧实现是 if/else 直取两个 token，点击瞬间色值一步跳到终点；
                // 导航是最高频的交互，硬切在观感上就是「廉价」。
                // animateColorAsState 在这里只驱动 Icon.tint 与 Text.color 两个颜色
                // （颜色动画走绘制层，不触发额外布局），成本可忽略。
                val tintColor by animateColorAsState(
                    targetValue = if (on) glass.textPrimary else glass.textTertiary,
                    animationSpec = GlassMotion.tweenHover<Color>(),
                    label = "navTint",
                )
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .pressScale(interactionSource, pressed = 0.94f)
                        .clickable(interactionSource = interactionSource, indication = null) { onSelect(index) }
                        .semantics {
                            this.role = Role.Tab
                            this.selected = on
                            // tab 的可见文字就是标签本身，但 Icon 没有描述、Column 又没合并子节点：
                            // 不显式给 contentDescription 时，TalkBack 会把「图标(无描述) + 文字」
                            // 拆成两个节点，读屏出现一个空节点。显式指定后整列合并为单个可读 tab。
                            this.contentDescription = label
                        },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    // D6 去装饰：这里原来是一个 30dp 的彩色径向光晕（见函数 KDoc）。
                    // 现在选中态的层级由**两处**共同承担，不再需要任何发光元素：
                    //   1. 颜色 contrast —— textPrimary（白）vs textTertiary（白50%）；
                    //   2. 图标尺寸 —— 选中 22dp vs 未选中 20dp（约 +21% 面积）。
                    // 两条各只差一档，但**同时**作用在同一个元素上，叠加后足以在余光下
                    // 分辨出「哪一列是亮的」；且都不涉及新增图层（无额外 draw 调用）。
                    //
                    // 外层 Box 固定 22dp 是**必须的**：图标尺寸随选中态变化，若让它直接
                    // 参与 Column 测量，选中切换时整列高度变 2dp → 标签基线上下跳 1dp。
                    // 固定容器后图标在内部居中缩放，布局零跳动。
                    Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
                        Icon(
                            icon, null, Modifier.size(if (on) 22.dp else 20.dp),
                            tint = tintColor,
                        )
                    }
                    Spacer(Modifier.height(3.dp))
                    // B3：标签 → Micro（11sp）。
                    //
                    // 选中态用 NavLabelSelectedStyle（Micro 的 SemiBold 派生）而不是在 Text 上
                    // 再写一个 fontWeight —— 那会让 `style` 与 `fontWeight` 并存，正是
                    // GlassText 的 KDoc 明确禁止的写法（覆盖式用法没有着力点，值会重新漂移）。
                    // 派生放成文件级 val 而不是就地 .copy()：后者每次重组都新建 TextStyle。
                    //
                    // 为什么必须加粗：光晕删除后这是「看得见的选择器」的主要信号。
                    // 纯颜色差在取色可变的极光底上不够稳（seed 一变，textPrimary 与
                    // textTertiary 的实际观感差就跟着变），字重是**与背景无关**的层级信号。
                    Text(
                        label,
                        style = if (on) NavLabelSelectedStyle else GlassText.Micro,
                        color = tintColor,
                    )
                }
            }
        }
    }
}

/** 屏幕底部 chrome：迷你条 + 胶囊导航（左右 12、下 10，悬浮于极光之上）
 *
 *  [progressProvider] 而非 Float：见 [ProgressTrack] 的说明——进度是 10Hz 变的状态，
 *  以 Float 形参传入会让**调用方屏幕**每 tick 重组一次（Chrome + NavBar + 封面链全部重跑）。 */
@Composable
fun GlassChrome(
    song: DemoSong,
    playing: Boolean,
    progressProvider: () -> Float,
    navSelected: Int,
    onNavSelect: (Int) -> Unit,
    onOpenPlayer: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    hasSongs: Boolean,
    modifier: Modifier = Modifier,
) {
    val flight = LocalPlayerFlight.current
    Column(
        modifier
            .fillMaxWidth()
            .navigationBarsPadding() // 手势条悬浮于 chrome 之下（对应设计稿 homebar）
            // 底距 10dp → S.Gap12（归位口径「10 就近取让留白不缩小的一档」）。
            // 这是 chrome 与手势条之间的呼吸缝，略松比略挤安全：收紧会让胶囊压到手势区。
            .padding(start = S.Gap12.dp, end = S.Gap12.dp, bottom = S.Gap12.dp),
    ) {
        MiniPlayerBar(song, playing, progressProvider, onOpenPlayer, onPlayPause, onNext, hasSongs = hasSongs)
        Spacer(Modifier.height(NavBarBottomInsetSpacer))
        // 导航胶囊：飞行期间随 t 消隐/复原；rect 供进入飞行的覆盖层渐隐替身使用
        GlassNavBar(
            navSelected, onNavSelect,
            Modifier
                .trackFlight(FlightKeys.MiniNav, keepOnDispose = true)
                .graphicsLayer { alpha = flight?.navAlpha() ?: 1f },
        )
    }
}

/** 底部导航三项文案（index 语义与 GlassApp 的 TAB_SCREENS 同序，改这里等于改三个主屏名称） */
object NavLabel {
    const val Library = "曲库"
    const val Playlist = "歌单"
    const val Settings = "设置"
}

/**
 * 主屏列表底部避让高度：悬浮 Chrome（迷你条 62 + 间距 8 + 导航 58 + 底距 12 = 140dp）+ 余量 6dp。
 *
 * 单点定义的原因：曲库/歌单/设置三个主屏原本各自硬编码 `Spacer(height = 146.dp)`，
 * Chrome 高度一改就要同步改三处，漏一处就是最后一行被迷你条盖住（且很难在评审里发现）。
 *
 * 注：底距由 10dp 归位到 [S.Gap12] 后，实际占用从 138 涨到 140dp；146 仍有 6dp 余量，
 * **不需要跟着改**。但若将来继续加大 chrome 组件高度，这里必须同步——
 * 6dp 已经不宽裕，再加就会真的盖住最后一行。
 */
val ChromeBottomInset: androidx.compose.ui.unit.Dp = 146.dp

/** 迷你条与导航胶囊之间的间距（[ChromeBottomInset] 的构成之一，避免两处魔数漂移） */
private val NavBarBottomInsetSpacer = S.Gap8.dp
