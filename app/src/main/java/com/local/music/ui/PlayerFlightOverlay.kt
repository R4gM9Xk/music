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
import androidx.compose.ui.draw.drawWithCache
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
import com.local.music.core.ui.components.BlurVeilKind
import com.local.music.core.ui.components.FlightKeys
import com.local.music.core.ui.components.veilTint
import com.local.music.core.ui.components.GlassNavBar
import com.local.music.core.ui.components.LocalHazeState
import com.local.music.core.ui.components.PlayerFlight
import com.local.music.core.ui.components.drawLiquidGlass
import com.local.music.core.ui.components.glassVeil
import com.local.music.core.ui.components.liquidFill
import com.local.music.core.ui.components.liquidGlassBrushes
import com.local.music.core.ui.components.liquidHazeTint
import com.local.music.core.ui.theme.GlassColors
import com.local.music.core.ui.theme.GlassIcons
import com.local.music.core.ui.theme.LocalAuroraPalette
import com.local.music.core.ui.theme.LocalGlassColors
import com.local.music.core.ui.theme.R
import dev.chrisbanes.haze.HazeProgressive
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import kotlin.math.roundToInt

/**
 * 共享元素飞行覆盖层：6 个替身（玻璃面板 / 封面 / 歌名 / 作者 / 播放钮 / 下一首）。
 * - 几何读 [PlayerFlight.snapshot]（两端 rect 边缘线性插值 = 中心直线平移 + 尺寸线性增长）
 * - R角 / 字号 / **行高** / 图标尺寸 / 玻璃填充与模糊半径 / 圆钮底透明度 全部同一 t 同帧插值
 * - 歌词模式收起时封面不飞（原地淡出），其余照常
 * - 纯绘制层：无 pointerInput，不挡触摸；结尾 12% 与真身交叉淡入消除像素差
 * - P2-12/P3-15：t 不进本层组合期——几何/样式 lerp 全部下沉到布局期（offset/layout
 *   lambda）与绘制期（graphicsLayer/drawBehind lambda）读取，飞行 500ms 内整层零重组；
 *   等待期（enteringWait）强制 t=0，样式不被上一轮中止残留的半程 t 污染（半形态渲染）。
 *   仅有的两个组合期读取点受 API 限制（haze 样式对象、Text 字号/行高参数），各自收窄进
 *   最小重组域 [FlightGlassPanel] / [FlightLabel]，不再牵动整层。
 *
 * ## C2（歌名出卡 + 封面放大）之后，本文件需要什么、不需要什么
 *
 * **不需要改**：所有替身的几何**端点本身**。它们全部来自 `trackFlight` 在渲染后实测的
 * root rect 快照（[PlayerFlight.snapshot]），真身挪到哪里，端点就到哪里。歌名从玻璃卡内
 * 搬到卡外、控制台变矮、封面变大——这些都是「真身 rect 变了」，替身自动跟随，不需要在
 * 本文件里重算任何坐标。这正是不该在 UI 里硬编码端点数值的理由。
 *
 * **必须改**（本轮已改，逐条见各调用点注释）：
 * 1. 歌名替身的 `sizeTo` 16→20、新增 `lineFrom/lineTo`（21→26）、字重 SemiBold→Bold
 *    —— 真身换了排版档（Heading → Title），字号/行高/字重都不再一致；
 * 2. 歌手行两端都是 Caption（12/16）——迷你条真身与播放页副标题同 token，
 *    替身的 `sizeFrom/lineFrom` 必须同值，不是「插值用的近似值」；
 * 3. 迷你条进度线：真身 D6 已改**纯 seed2**，替身不得再画渐变（否则右端跳色）；
 * 4. 封面起点圆角：真身 C4 嵌套圆角是 **16dp**（字面量），替身起点不得再用 R.Thumb10
 *    （旧值 10dp 让 42dp 封面四角弧线差 6dp）；
 * 5. 玻璃面板落点端：真身 v11 已升 **GlassLevel.Liquid**（方向性斜面 + specular、
 *    无描边/顶线），替身必须落在同一档——见 [FlightGlassPanel] 的 KDoc；
 * 6. 与之无关但一并核过：`PlayerGlass` 的 rect 变小后，[FlightGlassPanel] 的 R28 圆角与
 *    描边画法仍然成立（它按 size 自适应画，无硬编码宽高）。
 *
 * **必须在 PlayerScreen 侧同步**（不在本文件，列出以免漏）：歌名组出卡后不再是玻璃卡的
 * 子节点，透明度必须自己挂 `playerSharedAlpha × sheetAlpha`——否则退出飞行时替身已经
 * 淡出、真身歌名还亮着，收尾明显错拍。
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
                // 单影原则（几何重合驱动，取代按路径 t 的固定交叉窗）：替身只在「途中」可见，
                // 接近任一端点时让位给该端真身（真身按 PlayerFlight.*Coincidence 同窗渐入）。
                // 旧固定窗在快起慢收缓动下与真身空间错位同时可见 = 进入/退出重影（录屏实证）。
                // **enteringWait 必须恒 1**：等待期 Chrome 已卸载、替身是唯一的迷你条视觉，
                // 若还让 alpha 由 t 派生，而 t 又因中止/上一轮残留不为 0，替身就直接隐形 →
                // 迷你条闪没（正是这套替身机制本来要消灭的问题）。
                alpha = when {
                    enteringWait -> 1f
                    flight.phase == PlayerFlight.Phase.Entering ->
                        (1f - flight.playerCoincidence()).coerceIn(0f, 1f)
                    flight.phase == PlayerFlight.Phase.Exiting ->
                        (1f - maxOf(flight.playerCoincidence(), flight.miniCoincidence())).coerceIn(0f, 1f)
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

        // ── 玻璃面板：Hi(迷你条) → Liquid(控制台)，填充/装饰随 t 插值，R角两端同为 28 ──
        run {
            val rect = flightRect(FlightKeys.MiniGlass, FlightKeys.PlayerGlass)
            FlightGlassPanel(
                rect = rect,
                tAt = tAt,
                hazeState = appHaze,
                glass = glass,
                // specular 的光影驱动与真身控制台同值（state.progressFraction）：
                // 落地帧亮斑位置不能跳。progressProvider 本来就是绘制期取值函数。
                liquidMotion = progressProvider,
                modifier = Modifier.flightOffset(rect, tAt).flightSize(rect, tAt),
            )
            // ── 迷你条进度线替身：真身迷你条底部 2.5dp 进度线（12dp 内缩、2dp 圆角、
            //    15% 轨道 + **纯 seed2** 已播段，D6）。替身缺了它，点按瞬间这条线
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
                        // 已播段（真身 D6：纯 seed2，宽度 = 进度 × 线宽）。
                        // 旧实现画 seed2→textPrimary 渐变：真身删掉渐变后，替身的线右端
                        // 在起飞/落地那一帧会跳色——「改真身忘改对端」的典型。
                        val w = lineW * progressProvider().coerceIn(0f, 1f)
                        if (w > 0f) {
                            drawRect(
                                color = palette.seed2,
                                topLeft = Offset(inset, y),
                                size = Size(w, h),
                            )
                        }
                    },
            )
        }

        // ── 封面：42dp/R16 ⇄ 大封面/R28，阴影 0→18 渐起 ──
        if (!skipCover) {
            val rect = flightRect(FlightKeys.MiniCover, FlightKeys.PlayerCover)
            Box(
                Modifier
                    .flightOffset(rect, tAt)
                    .flightSize(rect, tAt)
                    .graphicsLayer {
                        // R角 8→16 与阴影 0→10 都在绘制期求值（层级属性直更，零重组）。
                        //
                        // ⚠️ **必须与两端真身严格同源**（本轮重设计后）：
                        // - 起点：迷你条封面 40dp / 圆角 8dp（Chrome.kt，底座圆角 20 − 内边距 12）
                        // - 终点：播放页封套圆角 16dp / 阴影 10dp（PlayerScreen.kt）
                        // 改任何一端必须同步改这里，否则起飞/落地那一帧四角弧线会跳变。
                        val t = tAt()
                        shape = RoundedCornerShape(lerp(8.dp, 16.dp, t))
                        clip = true
                        shadowElevation = lerp(0.dp, 10.dp, t).toPx()
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
                            cornerRadius = CornerRadius(lerp(8.dp, 16.dp, t).toPx() - sw / 2f),
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
                sizeFrom = 15f, sizeTo = 20f,
                // **sizeTo 从 16 改成 20（C2 歌名出卡）**：播放页歌名已从 GlassText.Heading
                // （16 SemiBold 22）升到 GlassText.Title（20 Bold 26）——出卡之后它是页面主角，
                // 必须与二级页页名同档（理由见 PlayerScreen 的 titleBlock KDoc）。
                // 这个数**漏改的后果是隐性的**：飞行途中一切正常，只有落地那 12% 交叉窗里
                // 替身字比真身瘦一圈，两个同名文本叠着淡入淡出，看起来像「文字抖了一下」。
                // 行高同样要跟进：只改字号不改行高会让替身的行盒比真身矮 4dp，
                // 落地时整行基线上跳（Text 的 maxLines=1 下，行高决定盒子高度与文字垂直位置）。
                lineFrom = 21f, lineTo = 26f,
                fontWeight = FontWeight.Bold,
                // **字重从 SemiBold 改成 Bold**，原因同上：GlassText.Title 是 Bold。
                // 字重无法 lerp（FontWeight 是离散枚举，跨档插值会跳变），所以取**终点值**
                // 并对 MiniTitle 端接受一点差异：迷你条的歌名是 RowStrong（15 SemiBold），
                // 替身以 Bold 起步会比真身粗约一档——但这段差异只存在于起飞的 500ms 内、
                // 且替身此刻正在放大与位移（用户注意力在运动上），落地端才是需要逐像素一致
                // 的那一端（交叉淡化就发生在那里）。取起点值则相反：落地会「变细」。
                rect = titleRect,
                tAt = tAt,
            )
            val artistRect = flightRect(FlightKeys.MiniArtist, FlightKeys.PlayerArtist)
            FlightLabel(
                text = song.artist,
                color = glass.textSecondary,
                // 起点必须对齐迷你条真身的 Caption（Chrome.kt B3：12sp/16）。旧实现写
                // 11sp/14（B3 之前的残留）：起飞瞬间副标题会缩小一档，且 :335 的注释
                // 自称「精确端点」却与代码矛盾——这正是「改真身忘改对端」的第三处。
                sizeFrom = 12f, sizeTo = 12f,
                lineFrom = 16f, lineTo = 16f,
                fontWeight = null,
                rect = artistRect,
                tAt = tAt,
                alphaProvider = { 1f - tAt() },
            )
            FlightLabel(
                text = song.subtitle,
                color = glass.textSecondary,
                // 副标题两端都是 GlassText.Caption（12/16），迷你条艺术家端是同一 token，
                // 所以 sizeFrom 与 lineFrom 在这里**不是插值用的近似值**，而是精确端点。
                sizeFrom = 12f, sizeTo = 12f,
                lineFrom = 16f, lineTo = 16f,
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
                        // 顶线 Brush 进 drawWithCache：旧 drawBehind 每次绘制都 new 一个
                        // horizontalGradient（Shader 级分配），飞行 500ms × 60fps 是纯 GC
                        // 压力。Brush 只依赖 size/颜色，cache 块天然覆盖失效条件（同
                        // Glass.kt 的 glassTopLine 写法）。
                        .drawWithCache {
                            val startX = size.width * 0.15f
                            val endX = size.width * 0.85f
                            val topLine = Brush.horizontalGradient(
                                listOf(Color.Transparent, Color.White.copy(alpha = 0.5f), Color.Transparent),
                                startX = startX,
                                endX = endX,
                            )
                            val strokeWidth = 1.dp.toPx()
                            onDrawBehind {
                                drawLine(
                                    brush = topLine,
                                    start = Offset(startX, 0.5f),
                                    end = Offset(endX, 0.5f),
                                    strokeWidth = strokeWidth,
                                )
                            }
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
 * 飞行替身的玻璃面板：Hi(迷你条) → **Liquid**(控制台)，R角两端同为 28。
 *
 * ## 为什么落点端必须是 Liquid（A4 审计 Minor-5）
 *
 * v11 把播放页控制台升到 `GlassLevel.Liquid`（方向性斜面 + specular、无描边、无顶线），
 * 替身却仍落在 Md：落地交叉淡化窗内，均匀描边/顶线会「褪掉」、白霜色相偏移成 accent
 * 有色、斜面与 specular 凭空出现——正是本文件 KDoc 警告的「任何一层差一档，交接时
 * 明度跳变」。现在两端逐层同源：
 *
 * - **haze tint**：hi → [liquidHazeTint]（真身 `glassHaze` 的 Liquid 配方，跨模块共用
 *   同一份函数，不复制 0.55/0.14 这些魔数）；
 * - **模糊半径全程 26dp**：Hi 与 Liquid 的真身半径都是 26（Md 才是 20），旧实现
 *   26→20 插值在落地端反而与真身差 6dp——这是**实打实的端点错误**，不是观感差异。
 *   半径固定后，飞行中真正随 t 变的只剩 tint，`HazeStyle` 仍每帧重建一次（haze 1.6
 *   的 API 天花板，样式对象只能在组合期构造），代价已收到最小；
 * - **内衬（veil）**：迷你条真身带 glassVeil（通透玻璃），控制台没有——随 t 淡到透明；
 * - **描边 + 顶线**：Hi 独家装饰，alpha 随 t 淡出（Liquid 不画这两层）；
 * - **方向性斜面 + specular**：alpha 随 t 淡入，t=1 时与真身共用 `liquidGlassBrushes` /
 *   `drawLiquidGlass`（同一套笔刷与绘制顺序）。specular 的 motion 取播放进度，与真身
 *   控制台的 `liquidMotion` 同值，落地帧亮斑位置不跳。
 *
 * 无 haze 退化路径（LocalHazeState 为 null）才回退普通 fill——终点是 **Liquid 的填充**
 * （而非 Md），对齐 GlassSurface 的 !hazed 分支。
 *
 * haze 样式参数是仅有的组合期 t 读取（haze 1.6 无绘制期样式 API），隔离在本节点最小
 * 重组域；其余全部在绘制期读取。Brush 全部在 drawWithCache 的 cache 块构造；飞行中
 * 面板尺寸逐帧变化会令 cache 重建（这是尺寸真变的固有代价），但静止/等待期帧间复用，
 * 且不再有 drawBehind 里的无条件 `horizontalGradient` 分配。
 */
@Composable
private fun FlightGlassPanel(
    rect: (Float) -> Rect,
    tAt: () -> Float,
    hazeState: HazeState?,
    glass: GlassColors,
    liquidMotion: () -> Float,
    modifier: Modifier = Modifier,
) {
    val palette = LocalAuroraPalette.current
    // 仅有的组合期 t 读取：haze 样式参数（见上）
    val t = tAt()
    // ── 起点必须与真身同源（2026-11-21 用户反馈「Chrome 遮住下方文字」）──
    //
    // 真身已改为 chromeHaze 配方：tint 只有 hi 的 1/3 浓度、半径 36dp、竖向 progressive。
    // 替身若继续用 `glass.hi` 原浓度 + 26dp 无 progressive，起飞第一帧就会从
    // 「通透模糊」跳到「近实色板」——这正是本文件反复警告的「端点差一档」。
    //
    // 所以起点端用与 chromeHaze 完全相同的三个参数，随 t 走向 Liquid 端点：
    // - tint：hi×0.35 → liquidHazeTint
    // - 半径：36 → 26（Liquid 真身是 26，见 HazeGlass.glassHaze 的 level 映射）
    // - progressive：起点开、终点关（控制台是 360dp 的大面板，竖向衰减在那里没有意义）
    val chromeTintStart = veilTint(glass, BlurVeilKind.Chrome)
    val tint = lerp(chromeTintStart, liquidHazeTint(glass, palette), t)
    // 迷你条真身的玻璃内衬（glassVeil）：替身起点必须与真身同源，随 t 淡到透明
    // （落点控制台无内衬）。在组合期取值，绘制期只做 lerp。
    //
    // ⚠️ 真身现在**只在 haze 退化时**才叠 veil（正常设备靠模糊挡字）。替身这里仍按
    // 旧公式画 veil——因为它代表的是「退化设备上的真身」。两条路径在正常设备上
    // 不一致（替身多了层 veil），但飞行只有 420ms 且替身始终在移动，实测不可辨；
    // 而若替身也去掉 veil，退化设备上起飞瞬间会「突然变透」再「突然变实」，更糟。
    // 这个取舍与 GlassSurface 的 blurVeil 兜底逻辑是同一条原则：退化路径必须自洽。
    val veil = glassVeil()
    Box(
        modifier
            .clip(FlightPanelShape)
            .then(
                if (hazeState != null) {
                    Modifier.hazeEffect(
                        state = hazeState,
                        style = HazeStyle(
                            backgroundColor = Color.Transparent,
                            tints = listOf(HazeTint(tint)),
                            // 12 → 26：起点对齐 chrome 档（已降到 12dp），终点对齐 Liquid 真身 26dp
                            blurRadius = lerp(12.dp, 26.dp, t),
                            noiseFactor = 0.05f,
                            fallbackTint = HazeTint(tint),
                        ),
                    ) {
                        // 起点用真身同款竖向 progressive，随 t 淡出到「无 progressive」
                        // （t=1 时 intensity 恒 1 等价于关闭）
                        if (t < 1f) {
                            this.progressive = HazeProgressive.verticalGradient(
                                startIntensity = 1f,
                                endIntensity = lerp(0.92f, 1f, t),
                                preferPerformance = false,
                            )
                        }
                    }
                } else {
                    Modifier
                },
            )
            .drawWithCache {
                val topLine = Brush.horizontalGradient(
                    listOf(Color.Transparent, glass.topLine, Color.Transparent),
                    startX = size.width * 0.08f,
                    endX = size.width * 0.92f,
                )
                val decor = liquidGlassBrushes(glass, size)
                val sw = 1.dp.toPx()
                onDrawBehind {
                    val t2 = tAt()
                    // 无 haze 退化路径才补填充（有 haze 时填充交给 tint）
                    if (hazeState == null) drawRect(lerp(glass.hi, glass.liquidFill, t2))
                    // 迷你条专属内衬（veil）随 t 淡出（落点在 haze 结果之上、描边/装饰之下）
                    drawRect(lerp(veil, Color.Transparent, t2))
                    // 1px 常规描边：Hi 专属，随 t 淡出（Liquid 不画均匀描边）
                    drawRoundRect(
                        color = glass.strokeSoft,
                        topLeft = Offset(sw / 2f, sw / 2f),
                        size = Size(size.width - sw, size.height - sw),
                        cornerRadius = CornerRadius(FlightPanelRadiusPx() - sw / 2f),
                        style = Stroke(sw),
                        alpha = 1f - t2,
                    )
                    // 顶部亮线：同样是 Hi 独家装饰（Liquid 用方向性斜面替代），随后淡出
                    drawLine(
                        brush = topLine,
                        start = Offset(size.width * 0.08f, 0.5f),
                        end = Offset(size.width * 0.92f, 0.5f),
                        strokeWidth = sw,
                        alpha = 1f - t2,
                    )
                    // Liquid 装饰淡入：t=0 完全不可见（迷你条是 Hi），t=1 与真身同源同序
                    drawLiquidGlass(decor, liquidMotion(), t2)
                }
            },
    )
}

/**
 * 替身玻璃面板形状：两端真身现在都是 **20dp**（本轮重设计）。
 *
 * - 迷你条/底部控制底座：`GlassChrome` 的圆角 20dp（Chrome.kt）
 * - 播放页控制台：`RoundedCornerShape(20.dp)`（PlayerScreen.kt）
 *
 * 两端同值，所以恒定不复用 lerp。旧值 28dp 对应的是旧的「两块胶囊」时代，
 * 改真身必须同步改这里，否则落地帧圆角会跳变。
 */
private const val FlightPanelRadiusDp = 20f

/** 同上，px 形态（绘制期用，避免每帧 with(density) 换算） */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.FlightPanelRadiusPx(): Float =
    FlightPanelRadiusDp * density

private val FlightPanelShape = RoundedCornerShape(FlightPanelRadiusDp.dp)

/**
 * 飞行替身的单行文字（歌名/作者共用）：字号 lerp 是 Text 的组合期参数（真布局渲染，
 * 不能用 graphicsLayer 缩放替代——缩放会改变省略号断点与基线位置，破坏起飞/落地瞬间
 * 与真身的逐像素一致），因此 t 在此节点组合期读取。读取被隔离在这一个最小重组域：
 * 飞行期每帧只有这两个小 Text 重组，几何（offset/size）仍在布局期读取，不牵动整层。
 * [alphaProvider] 供双文本内容交叉淡化用（歌者行 artist→subtitle），绘制期读，零重组。
 *
 * ## 为什么行高也要插值（[lineFrom] / [lineTo]）
 *
 * 旧实现只 lerp 字号、行高留空（走 Text 默认行高）。在那套端点下没暴露问题，因为两端
 * 字号（15↔16、11↔12）只差 1sp，默认行高的差异不到 1dp。C2 之后歌名终点是 26sp 行高
 * （GlassText.Title），起点是 21sp（RowStrong）——**差 5dp**。行高在 maxLines=1 时决定
 * 行盒高度，而行盒高度决定文字在盒内的垂直位置：不插值的话，替身的文字基线在整段飞行里
 * 停在「按字号算的默认行高」上，落地交叉窗里与真身那一行**错开约 2–3dp** 且逐帧收敛，
 * 表现为「两行字在互相咬合」——这是最难归因的一类错位（静止看不错，动起来晃）。
 *
 * 端点值口径：`lineFrom/lineTo` 传的是**行高 sp 数**，与真身 TextStyle 逐项对应
 * （迷你条 RowStrong 21 / 播放页 Title 26；两端的副标题都是 Caption 16）。
 */
@Composable
private fun FlightLabel(
    text: String,
    color: Color,
    sizeFrom: Float,
    sizeTo: Float,
    lineFrom: Float,
    lineTo: Float,
    fontWeight: FontWeight?,
    rect: (Float) -> Rect,
    tAt: () -> Float,
    alphaProvider: (() -> Float)? = null,
) {
    // t 只读一次：字号与行高必须来自**同一帧的同一个 t**，分两次读 tAt() 在
    // 动画驱动下虽然同帧同值（Animatable.value 不会在一次读取间变化），但一旦日后
    // 改成 spring/手动驱动就会出现「字号按 t₁、行高按 t₂」的半帧不一致——顺手钉死。
    val t = tAt()
    Text(
        text,
        color = color,
        fontSize = lerp(sizeFrom, sizeTo, t).sp,
        lineHeight = lerp(lineFrom, lineTo, t).sp,
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
