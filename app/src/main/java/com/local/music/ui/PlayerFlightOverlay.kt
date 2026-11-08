package com.local.music.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import com.local.music.core.ui.components.ArtThumb
import com.local.music.core.ui.components.FlightKeys
import com.local.music.core.ui.components.GlassNavBar
import com.local.music.core.ui.components.LocalHazeState
import com.local.music.core.ui.components.PlayerFlight
import com.local.music.core.ui.theme.GlassColors
import com.local.music.core.ui.theme.GlassIcons
import com.local.music.core.ui.theme.LocalAuroraPalette
import com.local.music.core.ui.theme.LocalGlassColors
import com.local.music.core.ui.theme.R
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import kotlin.math.roundToInt

/**
 * 共享元素飞行覆盖层：6 个替身（玻璃面板 / 封面 / 歌名 / 作者 / 播放钮 / 下一首）。
 * - 几何读 [PlayerFlight.snapshot]（两端 rect 边缘线性插值 = 中心直线平移 + 尺寸线性增长）
 * - R角 / 字号 / 图标尺寸 / 玻璃填充与模糊半径 / 圆钮底透明度 全部同一 t 同帧插值
 * - 歌词模式收起时封面不飞（原地淡出），其余照常
 * - 纯绘制层：无 pointerInput，不挡触摸；结尾 12% 与真身交叉淡入消除像素差
 * - P2-12/P3-15：t 不进本层组合期——几何/样式 lerp 全部下沉到布局期（offset/layout
 *   lambda）与绘制期（graphicsLayer/drawBehind lambda）读取，飞行 500ms 内整层零重组；
 *   等待期（enteringWait）强制 t=0，样式不被上一轮中止残留的半程 t 污染（半形态渲染）。
 *   仅有的两个组合期读取点受 API 限制（haze 样式对象、Text 字号参数），各自收窄进
 *   最小重组域 [FlightGlassPanel] / [FlightLabel]，不再牵动整层。
 */
@Composable
fun PlayerFlightOverlay(
    flight: PlayerFlight,
    playing: Boolean,
    progressProvider: () -> Float,
    modifier: Modifier = Modifier,
) {
    val snap = flight.snapshot
    // 等待期（Entering 未起飞）用冻结的 mini 几何原地绘制替身：Chrome 已卸载而替身
    // 若等 player rects 才出现，起点有 1~N 帧空窗（迷你条闪没再重现）。此时 snapshot
    // 只含 mini 键，t 恒为 0 → 各样式 lerp 天然等于迷你条形态，与真身逐像素一致
    val enteringWait = flight.phase == PlayerFlight.Phase.Entering && !flight.started
    if (snap == null || (!flight.started && !enteringWait)) return
    val song = flight.songSnapshot ?: return
    val glass = LocalGlassColors.current
    val palette = LocalAuroraPalette.current
    val density = LocalDensity.current
    val appHaze = LocalHazeState.current
    // 歌词模式收起：封面原地淡出，不参与飞行
    val skipCover = flight.phase == PlayerFlight.Phase.Exiting &&
        flight.visualMode == PlayerFlight.VisualMode.Lyrics

    // P2-12：t 改为取值函数而不是组合期读 Float——等待期强制 0。beginExit 的忙时中止
    // 只翻 phase 不复位 t，残留 t（如 0.4）若照读会让等待期替身按半程 lerp 渲染
    // （圆角/字号/圆钮底/导航胶囊全部半形态），起飞瞬间再跳变。
    // P3-15：取值函数只被布局期/绘制期 lambda 调用，t 每帧变化不再使本层重组。
    // displayT 同时覆盖退出侧（beginExit 的 snap 落地前强制 1），单一事实来源。
    val tAt: () -> Float = { flight.displayT() }

    /** 冻结端点的几何插值闭包：容错读取——两端键缺失时退化为可用端的静态几何（等待期即 t=0 兜底） */
    fun flightRect(fromKey: String, toKey: String): (Float) -> Rect {
        val a = snap[fromKey]
        val b = snap[toKey]
        return when {
            b == null -> { _ -> a ?: Rect.Zero }
            a == null -> { _ -> b }
            else -> { t ->
                Rect(
                    lerp(a.left, b.left, t), lerp(a.top, b.top, t),
                    lerp(a.right, b.right, t), lerp(a.bottom, b.bottom, t),
                )
            }
        }
    }

    Box(
        modifier
            .fillMaxSize()
            // 收尾交叉淡出：进入最后 12% 真身渐入时替身渐出（消灭双影像）；
            // 退出最后 12% 同理让位给 Chrome 真身。
            // **enteringWait 必须恒 1**：等待期 Chrome 已卸载、替身是唯一的迷你条视觉，
            // 若还让 alpha 由 t 派生，而 t 又因中止/上一轮残留不为 0，替身就直接隐形 →
            // 迷你条闪没（正是这套替身机制本来要消灭的问题）。
            // Exiting 侧同理给 0 下限：beginExit 的 snapTo(1f) 在协程首帧才生效，
            // 首帧读到 t=0 会让替身与迷你条真身同时隐身（双隐闪）。
            .graphicsLayer {
                val t = tAt()
                alpha = when {
                    enteringWait -> 1f
                    flight.phase == PlayerFlight.Phase.Entering -> ((1f - t) / 0.12f).coerceIn(0f, 1f)
                    flight.phase == PlayerFlight.Phase.Exiting -> {
                        // 两端交叉窗（时间等效）：起飞端 50% 路径渐入——与真身控制台的渐出曲线
                        // （playerSharedAlpha 退出端 (t-0.5)/0.5）**互补**（两者和恒为 1），
                        // 相同内容的玻璃/封面交叉时无亮度叠加；50% 而非对称 12% 是因为缓动
                        // 起步快，12% 路径只相当于 ~28ms（依旧近似硬切）。落地端 12% 渐出
                        // 让位迷你条真身（缓动慢尾巴 ≈165ms，与进场端一致）
                        minOf((1f - t) / 0.5f, t / 0.12f).coerceIn(0f, 1f)
                    }
                    else -> 1f
                }
                // 按压回弹接续（衔接修复②）：迷你条的 pressScale(.97) 回弹被 Chrome 卸载
                // 截断，替身在这里把它演完——否则点按瞬间迷你条从按压态「瞬跳」回全尺寸。
                // 取值统一走 flight.pressScale()：非进入方向恒 1、进入期未布防的**首帧**
                // 直接给按压值（snapTo 是 suspend，首帧读不到 .97，否则会先闪一帧全尺寸）。
                // 缩放轴心取迷你条真身中心（不是整层中心）：替身层是根层全屏 Box，
                // 按层中心缩放会让底部迷你条整体横移/上移，反而制造新的错位。
                // **必须无条件赋值**：graphicsLayer lambda 只对其中写到的属性生效，
                // 条件跳过会让上一次写进去的 scale 永久留在图层上（回弹结束仍缩着 3%）。
                val s = flight.pressScale()
                val mini = snap[FlightKeys.MiniGlass]
                if (mini != null && size.width > 0f && size.height > 0f) {
                    transformOrigin = TransformOrigin(
                        mini.center.x / size.width,
                        mini.center.y / size.height,
                    )
                }
                scaleX = s
                scaleY = s
            },
    ) {
        // ── 导航胶囊渐隐替身（仅进入飞行）：Chrome 已退出布局，原地随 t 消隐 ──
        // 仅当 Chrome 真身确实被卸载（tab 来源）时才代画：详情/搜索屏自带 GlassChrome，
        // 其真胶囊自己在 baseContentAlpha 下淡出，再画一份就是两枚半透明胶囊同位置叠加
        // （偏亮 + 消退节奏不对，旧审计 P3-23）——衔接修复③。
        if (flight.phase == PlayerFlight.Phase.Entering && flight.entryNavOwnedByOverlay) {
            snap[FlightKeys.MiniNav]?.let { navRect ->
                GlassNavBar(
                    selected = flight.entryNavSelected,
                    onSelect = {},
                    modifier = Modifier
                        .offset { IntOffset(navRect.left.roundToInt(), navRect.top.roundToInt()) }
                        .size(with(density) { DpSize(navRect.width.toDp(), navRect.height.toDp()) })
                        .graphicsLayer { alpha = 1f - tAt() },
                )
            }
        }

        // ── 玻璃面板：Hi(迷你条) → Md(控制台)，填充/模糊半径随 t 插值，R角两端同为 28 ──
        run {
            val rect = flightRect(FlightKeys.MiniGlass, FlightKeys.PlayerGlass)
            FlightGlassPanel(
                rect = rect,
                tAt = tAt,
                hazeState = appHaze,
                glass = glass,
                modifier = Modifier.flightOffset(rect, tAt).flightSize(rect, tAt),
            )
            // ── 迷你条进度线替身：真身迷你条底部 2.5dp 进度线（12dp 内缩、2dp 圆角、
            //    15% 轨道 + seed2→textPrimary 渐变已播段）。替身缺了它，点按瞬间这条线
            //    会凭空消失、落地时又凭空出现；控制台端没有此元素，随 t 淡出。t=0 时与
            //    真身逐像素一致（几何/配色/裁切同源复制）。
            Box(
                Modifier
                    .flightOffset(rect, tAt)
                    .flightSize(rect, tAt)
                    .graphicsLayer {
                        // 与迷你条玻璃同形裁切：R28 圆角在底边削掉线端的形状与真身一致
                        shape = FlightPanelShape
                        clip = true
                        alpha = (1f - tAt()).coerceIn(0f, 1f)
                    }
                    .drawBehind {
                        val h = 2.5.dp.toPx()
                        val inset = 12.dp.toPx()
                        val y = size.height - h
                        val lineW = size.width - inset * 2f
                        // 轨道（真身：textPrimary 15% 背景）
                        drawRoundRect(
                            color = glass.textPrimary.copy(alpha = 0.15f),
                            topLeft = Offset(inset, y),
                            size = Size(lineW, h),
                            cornerRadius = CornerRadius(2.dp.toPx()),
                        )
                        // 已播段（真身：seed2 → textPrimary 渐变，宽度 = 进度 × 线宽）
                        val w = lineW * progressProvider().coerceIn(0f, 1f)
                        if (w > 0f) {
                            drawRect(
                                brush = Brush.horizontalGradient(listOf(palette.seed2, glass.textPrimary)),
                                topLeft = Offset(inset, y),
                                size = Size(w, h),
                            )
                        }
                    },
            )
        }

        // ── 封面：42dp/R11 ⇄ 大封面/R28，阴影 0→18 渐起 ──
        if (!skipCover) {
            val rect = flightRect(FlightKeys.MiniCover, FlightKeys.PlayerCover)
            Box(
                Modifier
                    .flightOffset(rect, tAt)
                    .flightSize(rect, tAt)
                    .graphicsLayer {
                        // R角 10→28 与阴影 0→18 都在绘制期求值（层级属性直更，零重组）；
                        // shape+clip 等价原 .clip(lerp 形状)，shadowElevation 等价原 .shadow。
                        // from 端必须是 R.Thumb10：迷你条真身封面就是 R10（旧 11dp 起点导致
                        // 点按/落地瞬间封面圆角差 1dp，四角弧线肉眼可辨）
                        val t = tAt()
                        shape = RoundedCornerShape(lerp(R.Thumb10.dp, R.Player28.dp, t))
                        clip = true
                        shadowElevation = lerp(0.dp, 18.dp, t).toPx()
                    }
                    .drawWithContent {
                        // 1px 描边（白 12% → 玻璃高光）：内缩半线宽，对齐 Border 修饰符的画法。
                        // **画在内容之后**（drawWithContent 而非 drawBehind）：原 .border 就绘在
                        // 内容层上，而内层 ArtThumb fillMaxSize 会完全盖住 behind 描边
                        val t = tAt()
                        val sw = 1.dp.toPx()
                        drawContent()
                        drawRoundRect(
                            color = lerp(glass.strokeSoft, glass.stroke, t),
                            topLeft = Offset(sw / 2f, sw / 2f),
                            size = Size(size.width - sw, size.height - sw),
                            cornerRadius = CornerRadius(lerp(R.Thumb10.dp, R.Player28.dp, t).toPx() - sw / 2f),
                            style = Stroke(sw),
                        )
                    },
            ) {
                // 外层 graphicsLayer 已按 t 裁 R 角：内层传 RectangleShape 让 ArtThumb 自身
                // clip 退化为矩形、不再自绘描边——避免 lerped shape 作为参数把组合期 t
                // 读取扩散进 ArtThumb（其位图解码缓存命中是纯查表，不该每帧重跑）
                ArtThumb(
                    song.art, Modifier.fillMaxSize(), RectangleShape, bordered = false,
                    bitmap = song.coverBitmap, artworkUri = song.artworkUri,
                )
            }
        }

        // ── 歌名 / 作者：直线平移 + 字号 lerp（真布局渲染，非缩放） ──
        // 歌者行两端文本内容不同（迷你条 = song.artist，控制台 = song.subtitle）——
        // 单一文本会在交接瞬间整串换字；双文本随 t 交叉淡化，端点与各自真身逐像素一致
        run {
            val titleRect = flightRect(FlightKeys.MiniTitle, FlightKeys.PlayerTitle)
            FlightLabel(
                text = song.title,
                color = glass.textPrimary,
                // sizeFrom 必须对齐迷你条真身（Chrome.kt 15sp）：旧 13.5sp 让替身文字在
                // 点按瞬间缩小、基线上浮，落地交叉淡化时又跳回去
                sizeFrom = 15f, sizeTo = 16f,
                fontWeight = FontWeight.SemiBold,
                rect = titleRect,
                tAt = tAt,
            )
            val artistRect = flightRect(FlightKeys.MiniArtist, FlightKeys.PlayerArtist)
            FlightLabel(
                text = song.artist,
                color = glass.textSecondary,
                sizeFrom = 11f, sizeTo = 12f,
                fontWeight = null,
                rect = artistRect,
                tAt = tAt,
                alphaProvider = { 1f - tAt() },
            )
            FlightLabel(
                text = song.subtitle,
                color = glass.textSecondary,
                sizeFrom = 11f, sizeTo = 12f,
                fontWeight = null,
                rect = artistRect,
                tAt = tAt,
                alphaProvider = { tAt() },
            )
        }

        // ── 播放/暂停：图标平移放大；66dp 圆形玻璃底随 t 由虚到实 ──
        run {
            val rect = flightRect(FlightKeys.MiniPlay, FlightKeys.PlayerPlay)
            Box(
                Modifier.flightOffset(rect, tAt).flightSize(rect, tAt),
                contentAlignment = Alignment.Center,
            ) {
                // 旧实现 `if (t > 0.02f)` 是组合期分支（每帧重组）；下沉后恒组合，
                // alpha 里掐同一阈值——≤0.02 直接透明，视觉等价（阈值附近原本就近不可见）
                Box(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            val t = tAt()
                            alpha = if (t <= 0.02f) 0f else t
                        }
                        .clip(CircleShape)
                        .background(Brush.linearGradient(listOf(glass.hi, glass.lo)))
                        .border(1.dp, glass.stroke, CircleShape)
                        .drawBehind {
                            drawLine(
                                brush = Brush.horizontalGradient(
                                    listOf(Color.Transparent, Color.White.copy(alpha = 0.5f), Color.Transparent),
                                    startX = size.width * 0.15f,
                                    endX = size.width * 0.85f,
                                ),
                                start = Offset(size.width * 0.15f, 0.5f),
                                end = Offset(size.width * 0.85f, 0.5f),
                                strokeWidth = 1.dp.toPx(),
                            )
                        },
                )
                Icon(
                    if (playing) GlassIcons.Pause else GlassIcons.Play, null,
                    Modifier.flightIconSize({ lerp(22.dp, 26.dp, tAt()) }, density),
                    tint = glass.textPrimary,
                )
            }
        }

        // ── 下一首：图标平移放大，色调随目标（白 → 次级）过渡 ──
        run {
            val rect = flightRect(FlightKeys.MiniNext, FlightKeys.PlayerNext)
            Box(
                Modifier.flightOffset(rect, tAt).flightSize(rect, tAt),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    GlassIcons.Next, null,
                    Modifier
                        .flightIconSize({ lerp(22.dp, 28.dp, tAt()) }, density)
                        .graphicsLayer {
                            // tint 是 Icon 的组合期参数，无法下沉：改用层级 colorFilter
                            // 在绘制期调色（SrcATop 保留字形 alpha，仅替换颜色）
                            colorFilter = ColorFilter.tint(lerp(glass.textPrimary, glass.textSecondary, tAt()))
                        },
                    tint = glass.textPrimary,
                )
            }
        }
    }
}

/**
 * 飞行替身的玻璃面板：Hi→Md 填充、模糊半径 26→20 全程随 t 插值，R角两端同为 28。
 *
 * 与真身 [GlassSurface] 的三层结构逐层对齐（任何一层差一档，交接交叉淡化时就会
 * 明度/圆角跳变）：
 * - clip(R28)：迷你条与控制台真实端点都是 R.Player28，旧 20→28 lerp 与两端都不符；
 * - hazeEffect（tint=fill）：有 haze 时**不再叠 background(fill)**——真身 haze 路径
 *   就是 tint 单层承担填充（P3-78 双重填充修复），旧替身在 tint 上又画一层 fill，
 *   比真身亮近一倍，进出交接瞬间控制台/迷你条明度跳半档；
 * - scrim：迷你条真身带 chromeScrim（透字压制，α0.74~0.80），控制台没有——旧替身
 *   两端都没有，点按瞬间迷你条突然变透、落地又突然变实；现随 t 从 chromeScrim 淡到透明；
 * - 1px strokeSoft 描边 + 顶部亮线：与真身同源同序（描边内缩半线宽，亮线 8%~92%）。
 * 无 haze 退化路径（LocalHazeState 为 null）才回退普通 fill，对齐 GlassSurface 的 !hazed 分支。
 * haze 样式参数是仅有的组合期 t 读取（haze 1.6 无绘制期样式 API），隔离在本节点最小重组域。
 */
@Composable
private fun FlightGlassPanel(
    rect: (Float) -> Rect,
    tAt: () -> Float,
    hazeState: HazeState?,
    glass: GlassColors,
    modifier: Modifier = Modifier,
) {
    // 仅有的组合期 t 读取：haze 样式参数（见上）
    val t = tAt()
    val fill = lerp(glass.hi, glass.md, t)
    Box(
        modifier
            .clip(FlightPanelShape)
            .then(
                if (hazeState != null) {
                    Modifier.hazeEffect(
                        state = hazeState,
                        style = HazeStyle(
                            backgroundColor = Color.Transparent,
                            tints = listOf(HazeTint(fill)),
                            blurRadius = lerp(26.dp, 20.dp, t),
                            noiseFactor = 0.05f,
                            fallbackTint = HazeTint(fill),
                        ),
                    )
                } else {
                    Modifier
                },
            )
            .drawBehind {
                val t2 = tAt()
                // 无 haze 退化路径才补填充（对齐 GlassSurface：有 haze 时填充交给 tint）
                if (hazeState == null) drawRect(lerp(glass.hi, glass.md, t2))
                // 迷你条专属 scrim 随 t 淡出（落点在 haze 结果之上、描边之下，同 GlassSurface 层序）
                drawRect(lerp(glass.chromeScrim, Color.Transparent, t2))
                // 1px 常规描边：内缩半线宽，对齐 Border 修饰符的画法（原 .border 等价）
                val sw = 1.dp.toPx()
                drawRoundRect(
                    color = glass.strokeSoft,
                    topLeft = Offset(sw / 2f, sw / 2f),
                    size = Size(size.width - sw, size.height - sw),
                    cornerRadius = CornerRadius(R.Player28.dp.toPx() - sw / 2f),
                    style = Stroke(sw),
                )
                // GlassSurface 顶部亮线（glassTopLine 为模块私有，此处同款复刻）——
                // 保持在描边之后绘制：原实现 border 在 drawBehind 之前，顶边重叠处亮线在上
                drawLine(
                    brush = Brush.horizontalGradient(
                        listOf(Color.Transparent, glass.topLine, Color.Transparent),
                        startX = size.width * 0.08f,
                        endX = size.width * 0.92f,
                    ),
                    start = Offset(size.width * 0.08f, 0.5f),
                    end = Offset(size.width * 0.92f, 0.5f),
                    strokeWidth = 1.dp.toPx(),
                )
            },
    )
}

/** 替身玻璃面板形状：两端真身（迷你条 / 控制台）都是 R.Player28，恒定不复用 lerp */
private val FlightPanelShape = RoundedCornerShape(R.Player28.dp)

/**
 * 飞行替身的单行文字（歌名/作者共用）：字号 lerp 是 Text 的组合期参数（真布局渲染，
 * 不能用 graphicsLayer 缩放替代——缩放会改变省略号断点与基线位置，破坏起飞/落地瞬间
 * 与真身的逐像素一致），因此 t 在此节点组合期读取。读取被隔离在这一个最小重组域：
 * 飞行期每帧只有这两个小 Text 重组，几何（offset/size）仍在布局期读取，不牵动整层。
 * [alphaProvider] 供双文本内容交叉淡化用（歌者行 artist→subtitle），绘制期读，零重组。
 */
@Composable
private fun FlightLabel(
    text: String,
    color: Color,
    sizeFrom: Float,
    sizeTo: Float,
    fontWeight: FontWeight?,
    rect: (Float) -> Rect,
    tAt: () -> Float,
    alphaProvider: (() -> Float)? = null,
) {
    Text(
        text,
        color = color,
        fontSize = lerp(sizeFrom, sizeTo, tAt()).sp,
        fontWeight = fontWeight,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .flightOffset(rect, tAt)
            .flightSize(rect, tAt)
            .then(
                if (alphaProvider != null) {
                    Modifier.graphicsLayer { alpha = alphaProvider().coerceIn(0f, 1f) }
                } else {
                    Modifier
                },
            ),
    )
}

/** 几何下沉：位置在布局期读 t（lambda offset 每次布局重算，零重组） */
private fun Modifier.flightOffset(rect: (Float) -> Rect, tAt: () -> Float): Modifier = offset {
    val r = rect(tAt())
    IntOffset(r.left.roundToInt(), r.top.roundToInt())
}

/**
 * 几何下沉：尺寸在布局期读 t（layout 修饰符重测量，零重组）。
 * 目标约束再对入射约束 coerce，等价原 `.size(DpSize(w, h))` 的行为
 * （Modifier.size 就是 incoming.constrain(fixed)，缺这步上报越界约束会运行时炸）。
 */
private fun Modifier.flightSize(rect: (Float) -> Rect, tAt: () -> Float): Modifier = layout { measurable, constraints ->
    val r = rect(tAt())
    val w = r.width.roundToInt().coerceAtLeast(0)
    val h = r.height.roundToInt().coerceAtLeast(0)
    val targetW = constraints.constrainWidth(w)
    val targetH = constraints.constrainHeight(h)
    val placeable = measurable.measure(Constraints.fixed(targetW, targetH))
    layout(targetW, targetH) { placeable.place(0, 0) }
}

/** 图标尺寸下沉（22→26/28 的 dp lerp）：布局期求值，等价原 `.size(lerp(...))`（约束同上 coerce） */
private fun Modifier.flightIconSize(sizeAt: () -> Dp, density: Density): Modifier = layout { measurable, constraints ->
    val side = with(density) { sizeAt().roundToPx() }.coerceAtLeast(0)
    val targetW = constraints.constrainWidth(side)
    val targetH = constraints.constrainHeight(side)
    val placeable = measurable.measure(Constraints.fixed(targetW, targetH))
    layout(targetW, targetH) { placeable.place(0, 0) }
}
