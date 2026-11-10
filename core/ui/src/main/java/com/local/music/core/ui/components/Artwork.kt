package com.local.music.core.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.local.music.core.ui.theme.AuroraPalette
import com.local.music.core.ui.theme.GlassMotion
import com.local.music.core.ui.theme.GlassPalettes
import com.local.music.core.ui.theme.LibraryOrigin
import com.local.music.core.ui.theme.LocalAuroraPalette
import com.local.music.core.ui.theme.LocalGlassColors
import com.local.music.core.ui.theme.LocalLibraryOrigin
import com.local.music.core.ui.theme.R
import androidx.compose.ui.graphics.lerp

/** 封面模拟图：一比一复刻设计稿 .art1 ~ .art6（渐变 + 装饰几何），随尺寸自适应缩放 */
enum class ArtStyle(
    val colors: List<Color>,
    val palette: AuroraPalette,
    val decorate: DrawScope.(Size) -> Unit,
) {
    Night( // art1：深夜紫，右上光斑 + 左下圆环
        listOf(Color(0xFF111111), Color(0xFF3A2F6E), Color(0xFF7F77DD)), GlassPalettes.Purple,
        {
            drawCircle(Color.White.copy(alpha = 0.14f), radius = it.minDimension * 0.30f,
                center = Offset(it.width + it.minDimension * 0.05f, -it.minDimension * 0.06f))
            drawCircle(Color.White.copy(alpha = 0.22f), radius = it.minDimension * 0.24f,
                center = Offset(-it.minDimension * 0.03f, it.height + it.minDimension * 0.05f),
                style = Stroke(width = it.minDimension * 0.11f))
        },
    ),
    Tide( // art2：潮汐绿，内嵌圆环
        listOf(Color(0xFF0F6E56), Color(0xFF1D9E75), Color(0xFF9FE1CB)), GlassPalettes.Teal,
        {
            val inset = it.minDimension * 0.22f
            drawCircle(Color.White.copy(alpha = 0.35f), radius = (it.minDimension - inset * 2) / 2f,
                center = Offset(it.width / 2f, it.height / 2f), style = Stroke(width = it.minDimension * 0.014f))
        },
    ),
    Afternoon( // art3：午后橙，斜向光带
        listOf(Color(0xFF993C1D), Color(0xFFD85A30), Color(0xFFF0997B)), GlassPalettes.Coral,
        {
            val band = it.minDimension * 0.22f
            val cx = it.width / 2f
            val cy = it.height * 0.55f
            val k = 0.29f // tan(-16°)
            drawPath(
                androidx.compose.ui.graphics.Path().apply {
                    moveTo(-it.width * 0.1f, cy - band / 2 + k * (cx + it.width * 0.1f))
                    lineTo(it.width * 1.1f, cy - band / 2 - k * (it.width * 1.1f - cx))
                    lineTo(it.width * 1.1f, cy + band / 2 - k * (it.width * 1.1f - cx))
                    lineTo(-it.width * 0.1f, cy + band / 2 + k * (cx + it.width * 0.1f))
                    close()
                },
                Color.White.copy(alpha = 0.20f),
            )
        },
    ),
    Galaxy( // art4：银河蓝，右下光斑
        listOf(Color(0xFF0C447C), Color(0xFF378ADD), Color(0xFFB5D4F4)), GlassPalettes.Blue,
        {
            drawCircle(Color.White.copy(alpha = 0.13f), radius = it.minDimension * 0.36f,
                center = Offset(it.width + it.minDimension * 0.08f, it.height + it.minDimension * 0.10f))
        },
    ),
    Tape( // art5：磁带琥珀，竖条纹
        listOf(Color(0xFF712B13), Color(0xFFBA7517), Color(0xFFFAC775)), GlassPalettes.Coral,
        {
            val stripe = it.minDimension * 0.04f
            val gap = it.minDimension * 0.12f
            var x = 0f
            while (x < it.width) {
                drawRect(Color.Black.copy(alpha = 0.16f), Offset(x, 0f), Size(stripe, it.height))
                x += gap
            }
        },
    ),
    Room( // art6：白色房间，中央光斑
        listOf(Color(0xFF3C3489), Color(0xFF7F77DD), Color(0xFFF4C0D1)), GlassPalettes.Purple,
        {
            drawCircle(Color.White.copy(alpha = 0.22f), radius = it.minDimension * 0.26f,
                center = Offset(it.width / 2f, it.height / 2f))
        },
    ),
}

/**
 * 封面：优先画真封面，否则画渐变占位 + 装饰层；bordered = 1px 描边。
 *
 * 描边色（D5）：原为硬编码「白 12%」，现读 `LocalGlassColors.strokeSoft`——理由见下方调用点。
 * 历史记录：白 12% 是深色模式下调出来的值，浅色模式从未复核，这正是硬编码色能潜伏的方式。
 *
 * 位图来源两种（按优先级）：
 * - [bitmap]：调用方手里已解好的位图（飞行覆盖层等需要「起飞瞬间几何冻结」的场景）
 * - [artworkUri]：磁盘/内容 uri，**按需解码**并受 [ArtworkBitmapCache] 总字节上限约束——
 *   曲库扫描不再预解码全库封面（几千首即数百 MB），只在真正要画的这一帧解一张 256px 缩略图
 */
@Composable
fun ArtThumb(
    style: ArtStyle,
    modifier: Modifier = Modifier,
    shape: Shape = androidx.compose.foundation.shape.RoundedCornerShape(R.Thumb10.dp),
    bordered: Boolean = false,
    bitmap: androidx.compose.ui.graphics.ImageBitmap? = null,
    artworkUri: String? = null,
) {
    // 只读缓存不复用 bitmap 参数语义：bitmap 优先（调用方已持有），其次按 uri 按需解码。
    // getOrDecode 命中 LRU 时为纯查表，未命中才解码一次 256px 缩略图；
    // 解码失败会进负缓存（ArtworkBitmapCache.failed），同一 uri 后续不再重解 ——
    // 否则「封面 PNG 已被清理但 artworkUri 还指着它」的条目在列表滚动时每帧都在主线程解码。
    val resolved = bitmap ?: ArtworkBitmapCache.getOrDecode(artworkUri)
    // 占位色源（R8，#10/#11）：真曲库无封面时，占位渐变从当前极光 palette 同色相派生
    // （deep2→deep→seed 中间带），保证与整屏氛围同源；演示内容仍走 ArtStyle 设计稿固有渐变。
    // 明度带 0.08–0.5：与文字 1.0/0.6/0.5 的层级拉开，不与前景争对比。
    // 装饰几何（style.decorate）两态共用：纹样提供可区分性，色相才分"演示/真库"。
    val aurora = LocalAuroraPalette.current
    val placeholderColors = if (LocalLibraryOrigin.current == LibraryOrigin.Live) {
        listOf(
            lerp(aurora.deep2, aurora.deep, 0.35f),
            aurora.deep,
            lerp(aurora.seed, aurora.deep, 0.45f),
        )
    } else {
        style.colors
    }
    Box(modifier.clip(shape)) {
        if (resolved != null) {
            androidx.compose.foundation.Image(
                bitmap = resolved,
                contentDescription = null,
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            )
        } else {
            Box(
                Modifier
                    .matchParentSize()
                    // A2-09：brush/装饰 Path 进 drawWithCache——旧 drawBehind 每次绘制都
                    // new 一个 linearGradient（Shader 级分配），而本组件是全 App 出现密度
                    // 最高的元素，滚动/按压/波形任何重绘都在累积 GC 压力（同仓
                    // AuroraBackground/Glass.kt glassTopLine 的既定红线）。cache 块在
                    // 尺寸/配色变化时才重算，帧间复用
                    .drawWithCache {
                        val brush = Brush.linearGradient(
                            placeholderColors,
                            start = Offset(size.width * 0.2f, 0f),
                            end = Offset(size.width * 0.8f, size.height),
                        )
                        onDrawBehind {
                            drawRect(brush)
                            style.decorate(this, size)
                        }
                    },
            )
        }
        if (bordered) {
            Box(
                Modifier
                    .matchParentSize()
                    // D5：描边改读 strokeSoft（深色 = 白 14%，浅色自动反转成近黑）。
                    // 原为硬编码 Color.White.copy(alpha = 0.12f)：浅色模式下封面多是浅底
                    // （白色房间那张尤甚），白描边压在白玻璃上等于没画——缩略图的边缘
                    // 直接糊进卡片底，看起来像图没对齐。改用 token 后深浅两态都能「画出」轮廓，
                    // 且描边强度跟随「玻璃强度」设置一起缩放，不再是一个游离的魔数。
                    .border(1.dp, LocalGlassColors.current.strokeSoft, shape),
            )
        }
    }
}

/** 跳动波形（正在播放指示）：5 条/4 条竖条，1.1s 错峰起伏，等价 CSS @keyframes wv */
@Composable
fun WaveIndicator(
    modifier: Modifier = Modifier,
    barCount: Int = 4,
    color: Color = com.local.music.core.ui.theme.LocalGlassColors.current.textPrimary,
    playing: Boolean = true,
) {
    Row(
        modifier.height(WaveBoxHeight),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        repeat(barCount) { i ->
            // 动画值只在 graphicsLayer 的绘制期 lambda 里读，不在组合期读——
            // 组合期读 State（原 `Modifier.height(h.dp)`）会让整个 Row 子树每帧重组并重新
            // 测量/布局（无限动画 = 60fps 重组）。这里固定条高为最大值、改用 scaleY：
            // graphicsLayer lambda 只随重绘执行，零重组零重测量。
            // 视觉等价性：竖条是纯色不透明 2dp 圆角矩形，纵向缩放不改颜色与横向轮廓；
            // transformOrigin(0.5f, 1f) 把原点钉在底边，缩放时顶边上下起伏，正是原来
            // 「底对齐 + height 变化」的观感。唯一差异是 1dp 圆角随缩放纵向变扁，
            // 在 4~13dp 高度区间不可辨，可接受。
            // 增-P2-A：重写为「单一 Animatable + 绘制期读」，一并解决三个问题：
            //
            // ① **曲线漏网**：旧 tween(550, delay) 未传 easing，落 Compose 默认
            //    FastOutSlowInEasing——全仓唯一违反「统一曲线」纪律的点
            //    （GlassMotion.Curve 的 KDoc 明写「全 app 唯一缓动曲线」）。现补 Curve。
            //
            // ② **暂停硬跳**：旧实现 playing=false 时直接取常量 mutableStateOf(min)，
            //    四条竖条从各自任意高度**瞬跳**到最低，像被掐断。现在同一实例收束
            //    （tweenHover），暂停时自然「落下去」。
            //    为什么不能用 animateFloatAsState 追无限动画的值：那会让它每帧追一个
            //    每帧都变的目标，起伏被平滑成滞后，等于把波形抹平。必须由协程直接驱动。
            //
            // ③ **组合期读动画值**：旧写法 `val h by ...` 在组合期解构 State，与文件内
            //    注释宣称的「零重组」相悖——无限动画下等于 60fps 重组本行子树。
            //    现在把 Animatable 实例交给绘制期 lambda 读（见下方 graphicsLayer）。
            val amp = remember { Animatable(WaveBarMinPx) }
            LaunchedEffect(playing) {
                if (playing) {
                    // 首轮带错峰 delay（等价原 CSS animation-delay），之后匀速往返
                    amp.animateTo(
                        WaveBarMaxPx,
                        tween(550, delayMillis = WaveDelays[i % WaveDelays.size], easing = GlassMotion.Curve),
                    )
                    while (true) {
                        amp.animateTo(WaveBarMinPx, tween(550, easing = GlassMotion.Curve))
                        amp.animateTo(WaveBarMaxPx, tween(550, easing = GlassMotion.Curve))
                    }
                } else {
                    // 暂停：从当前高度平滑落到最低，绝不硬跳
                    amp.animateTo(WaveBarMinPx, GlassMotion.tweenHover())
                }
            }
            Box(
                Modifier
                    .width(2.dp)
                    .height(WaveBarMaxDp)
                    .graphicsLayer {
                        // 绘制期读：amp.value 变化只失效绘制，不触发重组（既有红线）
                        scaleY = amp.value / WaveBarMaxPx
                        transformOrigin = TransformOrigin(0.5f, 1f)
                    }
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(1.dp))
                    .drawBehind { drawRect(color) },
            )
        }
    }
}

/** 波形条高度动画的相位偏移（ms）：等价 CSS 各条 animation-delay。
 *  顶层 IntArray 而非组合内 listOf：原实现在组合体里每帧重建一个 List（无限动画下
 *  每帧一次分配），提为常量后彻底消除。 */
private val WaveDelays = intArrayOf(0, 180, 360, 100, 280)

/** 波形容器高度（Row 高度）与竖条满高：原 14dp / 13dp */
private val WaveBoxHeight = 14.dp
private val WaveBarMaxDp = 13.dp

/** 竖条动画值的像素上下界（4dp~13dp 的表达）：scaleY = 动画值 / 满高 */
private const val WaveBarMinPx = 4f
private const val WaveBarMaxPx = 13f

// ── 演示数据（与设计稿同文案）──────────────────────────────

/** 曲目：id 供收藏/歌单/队列引用；时长以秒为单源，duration 为显示串。
 *  真曲库文件：mediaUri = MediaStore content uri；coverBitmap = 内嵌封面缩略图；
 *  lyricLines = 同名 .lrc 解析结果（无文件时为 null，UI 显示「纯音乐，请欣赏」空态）；
 *  格式参数：codec/sampleRateHz/bitDepth/bitrateKbps（0 = 未知，展示时降级）；
 *  元数据：genre 为扫描真值（空 = 未知流派，分组视图归一展示）；year/trackNo/discNo 取 MediaStore（0 = 未知）；
 *  albumArtist 空串 = 标签缺失（专辑分组时回退到 [artist]，见 AppState.albumGroupsFrom）。
 *  **新增字段一律带默认值**：快照/缓存的旧数据缺这些键时按默认值解码，不要求字段一定存在。 */
data class DemoSong(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationSec: Int,
    val art: ArtStyle,
    val folder: String = "音乐",
    val genre: String = "",
    val year: Int = 0,
    val trackNo: Int = 0,
    /** 专辑艺术家（MediaStore ALBUM_ARTIST / 标签 TPE2）：专辑分组键的第二元。
     *  空串 = 标签缺失 → 分组时回退到 [artist]（合辑的专辑艺术家通常是
     *  "Various Artists"，缺失的普通专辑回退到曲目艺术家 = 旧行为）。
     *  用空串而非 null：与 [genre]/[year]/[trackNo] 的「哨兵值 = 未知」口径一致，
     *  旧缓存/旧快照缺该键时直接落到同一路径，不需要额外 null 分支。 */
    val albumArtist: String = "",
    /** 碟号（MediaStore DISC_NUMBER / 标签 TPOS）：多碟专辑内排序的**第一键**。
     *  0 = 未知（很多 MP3 根本没有 TPOS 帧，MediaProvider 也常填 NULL）→ 排序时排**末尾**，
     *  **不是**当成第 0 碟排最前。这一点极易写反：若把 0 当第 1 碟，一张没打碟号的专辑里
     *  那些「恰好没编号」的歌会被排到正式第 1 碟之前，比改造前的字母序还离谱。
     *  排序实现在 AppState.sortAlbumTracks（0 → Int.MAX_VALUE），与这里必须同口径。 */
    val discNo: Int = 0,
    /**
     * 本机能否解码该曲目的容器/编码（扫描期由 [com.local.music.player.MediaLibrary] 按 MIME 判定）。
     *
     * **为什么需要这个字段**：本工程的播放引擎只依赖 `media3-exoplayer` + `media3-session`，
     * 没有接 FFmpeg 扩展渲染器。而 `mimeToCodec` 表里明确写了 `audio/ape → APE`、
     * `audio/x-ms-wma → WMA` —— 于是用户在歌曲信息面板会看到一个漂亮的「APE · 44.1kHz / 16bit」，
     * 点播放却得到「音频格式不支持播放」。**「说了做不到」比「没说」伤口碑得多**，这是审计
     * （01 报告 14a）点名的整类问题。
     *
     * 所以在扫描期就把「引擎能不能解」这件事判出来并落到数据模型上，让 UI 有机会诚实标注。
     * 判定口径是**保守**的：只把 Media3 开箱支持的那批 MIME 判为 true，其余一律 false ——
     * 宁可对某个冷门但恰好能解的容器少标一次「不支持」，也不能反过来宣称支持然后播不了。
     *
     * 默认 `true`（乐观）是刻意的：默认值面向「旧缓存 / 旧快照里没有这个键的条目」与
     * 「演示曲目」这两类场景，它们要么是真能播的常规格式，要么根本不参与播放判定；
     * 给 false 会让升级后的首屏把所有老歌都标成「不支持」，那才是真正的事故。
     */
    val playable: Boolean = true,
    // 已删除：`val inLibrary: Boolean = true`（本轮清理）。全仓零读取 ——
    // 唯一写入点是 placeholder 传 false，而没有任何一处按它分支。
    // 它原本大概想表达「这条是否属于曲库」，实际所有过滤都走
    // `realLibraryLoaded` + `songIndex()` 解析失败即丢弃，与本字段无关。
    val mediaUri: String? = null,
    val coverBitmap: androidx.compose.ui.graphics.ImageBitmap? = null,
    val artworkUri: String? = null,
    val lyricLines: List<LyricLine>? = null,
    val codec: String = "",
    val sampleRateHz: Int = 0,
    val bitDepth: Int = 0,
    val bitrateKbps: Int = 0,
    /** 封面取色派生的极光配色（扫描时由内嵌封面计算）；null = 无封面/未取色，回退 art 占位配色 */
    val palette: com.local.music.core.ui.theme.AuroraPalette? = null,
    /** MediaStore DATE_ADDED（秒）：「最近添加」排序键；缓存 v1 旧数据缺省 0 */
    val dateAddedSec: Long = 0,
) {
    val subtitle get() = "$artist · $album"
    /** 时长显示串：统一走 [asTime]（P3-26）——旧实现 `"%d:%02d"` 分钟不补零，
     *  同一行内与 asTime() 的 `"%02d:%02d"`（补零两位分钟）口径不同，
     *  9 分以内的歌会出现 "3:25" vs "03:25" 两种宽度（列表右对齐时尤显跳眼）。
     *  Locale.US 固定数字字形：`"%d".format()` 走默认 locale，
     *  在 ar-EG / fa-IR 等区域会输出本地化数字（如 ٣:٢٥），与 AppState 的格式串口径不一致 */
    val duration: String
        get() = durationSec.asTime()
}

object DemoData {
    /** 空队列兜底占位曲：仅在曲库尚未装载/无曲库时防越界，正常界面不可见 */
    val placeholder = DemoSong(
        id = "placeholder",
        title = "暂无歌曲",
        artist = "本地曲库",
        album = "未扫描",
        durationSec = 1,
        art = ArtStyle.Night,
    )

    /**
     * 演示曲库**已彻底移除**（本轮清理）：曲库内容唯一来源 = MediaStore 真实扫描/缓存。
     *
     * 保留这个函数（而不是删掉它）纯粹是为了让 `DemoCatalog.songById` 的
     * 非真库兜底分支保持可编译——那条分支本身对空表是无害空转，
     * 而改动它会牵动 AppState 里封板的快照/恢复链。
     * **不要**再往这里加回任何曲目：那会让「真曲库装载前的搜索/歌单解析突然有内容」
     * 这个早已被删掉的行为重新出现。
     */
    val songs: List<DemoSong> get() = emptyList()
}

/** 秒数 → mm:ss 显示串（进度文本/倒计时共用）。
 *  同 [DemoSong.duration]，固定 Locale.US：默认 locale 会输出本地化数字字形，
 *  而这里是纯数值展示（不是给用户看的本地化量词），必须跨区域稳定。
 *
 *  负值钳到 0：引擎时长未知时可能传出 -1（C.TIME_UNSET 的转换残留），
 *  原实现会渲染成 "00:-1"（Kotlin 对负数的整除/取模都是负），是明显的显示 bug。 */
fun Int.asTime(): String {
    val sec = this.coerceAtLeast(0)
    return String.format(java.util.Locale.US, "%02d:%02d", sec / 60, sec % 60)
}
