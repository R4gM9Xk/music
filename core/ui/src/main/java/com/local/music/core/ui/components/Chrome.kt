package com.local.music.core.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.layout.sizeIn
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
import com.local.music.core.ui.theme.LocalVisualStyle
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
            // 触摸热区 24dp → 44dp（P2 无障碍修复）。视觉轨道仍是 4dp、绘制期居中，
            // 热区只影响命中范围，不改任何像素。
            //
            // 为什么 24dp 不够：这是全曲唯一的 seek 入口，也是一条**横向细条**——
            // 纵向只有 24dp 可容错，而用户在播放页/迷你条上滑动的方向本来就不稳定，
            // 手指稍偏就落到轨道外的空白，表现为「拖了没反应」。44dp 与全 App
            // 其它可点目标的下限同档（GlassIconButton / SongRow 尾槽按钮 / 迷你条按钮）。
            //
            // 副作用（如实记录）：44dp 比原来的 24dp 多占 20dp 竖向空间。调用点若在
            // 密集布局里（播放页控制台）需要自己控 gap——那里本来就有 S.Gap 间距，
            // 实测不会挤，但改这一行时要知道它会影响**所有**调用点的占位。
            .height(44.dp)
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

/** 悬浮迷你条：60h / 圆角 20 / hi 玻璃，底部 2.5px 进度线。
 *
 *  ## 本轮重设计（2026-11）：从「两块悬浮胶囊」到「一块控制底座」
 *
 *  旧结构是迷你条 + 导航胶囊两块独立的悬浮玻璃，中间留 8dp 缝。问题有三：
 *  1. 两块玻璃各自投一次阴影、各自带一次描边，视觉上是两个物体在争夺注意力；
 *  2. 加起来 128dp 的高度把可读内容压得很紧，尤其在小屏上；
 *  3. 两者在功能上其实是**同一个东西**——「我现在在听什么」与「我在哪」。
 *
 *  现在合并为 [GlassChrome] 里的单一控制底座：上半是迷你播放区，下半是导航，
 *  中间一条 1dp 分隔线。圆角、阴影、材质只有一份。
 *
 *  [hasSongs] = false（空曲库 / 权限被拒）时**不渲染播放与下一首按钮**，
 *  且 [GlassChrome] 会把整块迷你播放区收起来（见那里）。
 *
 *  **本参数刻意不给默认值**：默认 `true` 让「忘记传」不报错、不崩溃，
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
    // 迷你条不再自持玻璃容器：它现在是控制底座的上半段，容器由 GlassChrome 提供。
    // 这样「一块底座」只有一个圆角、一份阴影、一处材质来源。
    Box(
        modifier
            .fillMaxWidth()
            .height(60.dp)
            .trackFlight(FlightKeys.MiniGlass, keepOnDispose = true)
            .graphicsLayer { alpha = flight?.miniSharedAlpha() ?: 1f }
            .clip(RoundedCornerShape(20.dp))
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick),
    ) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = S.Gap12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(S.Gap12.dp),
        ) {
            ArtThumb(
                song.art,
                Modifier.trackFlight(FlightKeys.MiniCover, keepOnDispose = true).size(40.dp),
                // 嵌套圆角共心推导：底座圆角 20、内边距 12 → 内侧应为 8dp。
                // 这与 SkeuoShape.Chip / R.Thumb10 都不同，是几何正确优先于刻度一致的一处。
                RoundedCornerShape(8.dp),
                bordered = true,
                // coverBitmap 恒为 null（扫描不再预解码全库）；真封面经 artworkUri 按需解码
                bitmap = song.coverBitmap,
                artworkUri = song.artworkUri,
            )
            Column(Modifier.weight(1f)) {
                // 15sp SemiBold 对齐 RowStrong（15 SemiBold 21）
                Text(song.title, color = glass.textPrimary, style = GlassText.RowStrong,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.trackFlight(FlightKeys.MiniTitle, keepOnDispose = true))
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
                        Modifier.size(20.dp), tint = glass.textPrimary)
                }
                MiniBarButton(
                    onClick = onNext,
                    contentDescription = "下一首",
                    modifier = Modifier.trackFlight(FlightKeys.MiniNext, keepOnDispose = true),
                ) {
                    Icon(GlassIcons.Next, null, Modifier.size(20.dp), tint = glass.textPrimary)
                }
            }
        }
        // 底部进度线：绘制期读进度（避免 10Hz 重组迷你条整行）
        //
        // 空曲库（hasSongs = false）时进度恒为 0：不读 progressProvider，画出全空轨道，
        // 避免占位曲场景下显示一条来历不明的填充。
        val smoothMini = rememberSmoothProgress(progressProvider)
        Box(
            Modifier
                .align(Alignment.BottomStart)
                .padding(horizontal = S.Gap12.dp)
                .fillMaxWidth()
                .height(2.dp)
                .clip(RoundedCornerShape(1.dp))
                .background(glass.textPrimary.copy(alpha = 0.15f))
                .drawWithCache {
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
            // 触控目标：图标 22dp + 4dp padding ×2 = 30dp（P2 无障碍修复）。
            //
            // 这是全 App 最常被点的两个按钮（迷你条常驻在每一屏底部），而 30dp 的命中区
            // 在拇指操作下明显偏小——迷你条本身只有 62dp 高，用户还在移动中按它。
            // 用 `.sizeIn(minWidth/minHeight = 44.dp)` 而**不是**加大 padding：
            // padding 会把 22dp 图标与相邻元素的间距一起推开，62dp 高的迷你条里
            // 两个按钮 + 封面 + 文本列会挤在一起；sizeIn 只保证布局尺寸下限，
            // 图标仍居中，视觉间距不变。
            //
            // 44dp 与 GlassIconButton / SongRow 行内按钮同档（全 App 统一的下限）。
            .sizeIn(minWidth = 44.dp, minHeight = 44.dp)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            // 语义挂 clickable 节点本身；子 Icon 传 null（避免重复播报），见 PlayPauseButton 同款说明
            .semantics {
                this.role = Role.Button
                this.contentDescription = contentDescription
                if (stateDescription != null) this.stateDescription = stateDescription
            }
            .padding(4.dp),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** 底导航：54h，3 项，选中项由「颜色 + 字重 + 短指示线」三重信号表达。
 *
 *  ## 本轮重设计（2026-11）
 *
 *  旧实现是一个独立的悬浮胶囊，带自己的玻璃、自己的描边和自己的模糊。
 *  现在它是 [GlassChrome] 控制底座的下半段——不再持有容器，只负责排列三个入口。
 *
 *  选中态的三重信号（替代旧版的彩色径向光晕）：
 *  1. **颜色**：textPrimary vs textTertiary；
 *  2. **字重**：SemiBold vs Normal；
 *  3. **短指示线**：选中项上方 16×2dp 的强调色短线。
 *
 *  为什么用指示线而不是把图标放大：图标尺寸变化会让整列高度跳 2dp，标签基线跟着动；
 *  指示线是独立的绘制层，不参与布局。
 */
@Composable
fun GlassNavBar(
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val glass = LocalGlassColors.current
    // 文案与目标页必须一致：index 0/1/2 = 曲库 / 歌单 / 设置（GlassApp 的 TAB_SCREENS 序）。
    val items = listOf(
        Triple(NavLabel.Library, GlassIcons.Home, 0),
        Triple(NavLabel.Playlist, GlassIcons.Queue, 1),
        Triple(NavLabel.Settings, GlassIcons.Gear, 2),
    )
    Row(modifier.fillMaxWidth().height(54.dp)) {
        items.forEach { (label, icon, index) ->
            val on = selected == index
            val interactionSource = remember { MutableInteractionSource() }
            // 选中/未选中色改为 250ms 渐变（硬切在观感上就是「廉价」）
            val tintColor by animateColorAsState(
                targetValue = if (on) glass.textPrimary else glass.textTertiary,
                animationSpec = GlassMotion.tweenHover<Color>(),
                label = "navTint",
            )
            // 指示线的透明度同样走动画：切换时它应该「长出来」而不是「跳出来」
            val indicatorAlpha by animateFloatAsState(
                targetValue = if (on) 1f else 0f,
                animationSpec = GlassMotion.tweenHover(),
                label = "navIndicator",
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
                        // 显式 contentDescription：Icon 没有描述、Column 又没合并子节点，
                        // 不指定时 TalkBack 会把「空图标 + 文字」拆成两个节点。
                        this.contentDescription = label
                    },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                // 指示线：固定 2dp 高度占位，透明度动画控制显隐——不参与布局变化
                Box(
                    Modifier
                        .size(width = 16.dp, height = 2.dp)
                        .clip(RoundedCornerShape(1.dp))
                        .background(com.local.music.core.ui.theme.auroraAccentColor().copy(alpha = indicatorAlpha)),
                )
                Spacer(Modifier.height(5.dp))
                Icon(icon, null, Modifier.size(22.dp), tint = tintColor)
                Spacer(Modifier.height(3.dp))
                Text(
                    label,
                    style = if (on) NavLabelSelectedStyle else GlassText.Micro,
                    color = tintColor,
                )
            }
        }
    }
}

/** 屏幕底部 chrome：**统一控制底座**（迷你播放区 + 分隔线 + 导航区）
 *
 *  ## 本轮重设计（2026-11）：从两块悬浮胶囊到一块底座
 *
 *  旧结构是 `Column { MiniPlayerBar; Spacer(8dp); GlassNavBar }`，两块各自悬浮的玻璃。
 *  新结构是**一块**圆角 20dp 的玻璃底座，上半迷你播放、下半导航、中间 1dp 分隔线：
 *
 *  - 只投一次阴影、只有一处描边、只有一个圆角来源；
 *  - 总高度从 128dp 降到 115dp，可读内容多出 13dp；
 *  - 「我在听什么」与「我在哪」在视觉上被明确归为同一个东西。
 *
 *  ## 空曲库：整块迷你播放区收起
 *
 *  `hasSongs = false` 时不渲染上半区（也不渲染分隔线），底座高度收到 54dp。
 *  这比「显示占位曲 + 两个死按钮」正确得多——没有歌就没有「正在播放」这回事。
 *  高度变化走 200ms 动画，不硬切。
 *
 *  [progressProvider] 而非 Float：见 [ProgressTrack] 的说明——进度是 10Hz 变的状态，
 *  以 Float 形参传入会让**调用方屏幕**每 tick 重组一次。 */
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
    // 迷你播放区高度：有歌 60dp，无歌 0dp（动画过渡，不硬切）
    val miniHeight by animateDpAsState(
        targetValue = if (hasSongs) 60.dp else 0.dp,
        animationSpec = GlassMotion.tweenHover(),
        label = "miniHeight",
    )
    val dividerAlpha by animateFloatAsState(
        targetValue = if (hasSongs) 1f else 0f,
        animationSpec = GlassMotion.tweenHover(),
        label = "dividerAlpha",
    )
    val glass = LocalGlassColors.current
    val skeuoMode = LocalVisualStyle.current == com.local.music.core.ui.state.VisualStyle.Skeuomorph
    Column(
        modifier
            .fillMaxWidth()
            .navigationBarsPadding() // 手势条悬浮于 chrome 之下
            .padding(start = S.Gap12.dp, end = S.Gap12.dp, bottom = S.Gap12.dp),
    ) {
        GlassSurface(
            modifier = Modifier
                .fillMaxWidth()
                .trackFlight(FlightKeys.MiniNav, keepOnDispose = true)
                .graphicsLayer { alpha = flight?.navAlpha() ?: 1f },
            level = GlassLevel.Hi,
            shape = RoundedCornerShape(20.dp),
            blur = GlassLevel.Hi,
            // 「模糊挡字」配方（见 MiniPlayerBar 旧注释与 BlurVeilKind 对比表）：
            // 现在整块底座共用一份材质，不再分两块各自配一遍。
            blurVeil = BlurVeilKind.Chrome,
            scrim = glassVeil(),
            // 拟物档：底部底座是**机身面板**（平放），不是浮起来的封套。
            // 给它 Sleeve 会让它带着大投影浮在列表上，与「底座压在最下面」的物理关系相反。
            skeuoRole = com.local.music.core.ui.theme.SkeuoRole.Body,
        ) {
            Column(Modifier.fillMaxWidth()) {
                if (miniHeight > 0.dp) {
                    Box(Modifier.fillMaxWidth().height(miniHeight)) {
                        MiniPlayerBar(
                            song, playing, progressProvider, onOpenPlayer, onPlayPause, onNext,
                            hasSongs = hasSongs,
                        )
                    }
                    // 分隔线：1dp，只在有迷你播放区时出现。
                    // 用 token 色而不是硬编码白/黑——深浅主题下它必须自动反转。
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = S.Gap16.dp)
                            .height(1.dp)
                            .background(
                                if (skeuoMode) {
                                    com.local.music.core.ui.theme.LocalSkeuomorphColors.current.separator
                                } else {
                                    glass.textPrimary.copy(alpha = 0.08f * dividerAlpha)
                                },
                            ),
                    )
                }
                // 导航区：飞行期间随 t 消隐/复原
                GlassNavBar(
                    navSelected, onNavSelect,
                    Modifier.graphicsLayer { alpha = flight?.navAlpha() ?: 1f },
                )
            }
        }
    }
}

/** 底部导航三项文案（index 语义与 GlassApp 的 TAB_SCREENS 同序，改这里等于改三个主屏名称） */
object NavLabel {
    const val Library = "曲库"
    const val Playlist = "歌单"
    const val Settings = "设置"
}

/**
 * 主屏列表底部避让高度：统一控制底座（迷你播放 60 + 分隔 1 + 导航 54 + 底距 12 = 127dp）+ 余量 8dp。
 *
 * 单点定义的原因：曲库/歌单/设置三个主屏原本各自硬编码 `Spacer(height = 146.dp)`，
 * Chrome 高度一改就要同步改三处，漏一处就是最后一行被迷你条盖住（且很难在评审里发现）。
 *
 * 本轮控制底座从 128dp 降到 115dp（两块胶囊合并），避让值同步从 146 收到 135。
 */
val ChromeBottomInset: androidx.compose.ui.unit.Dp = 135.dp
