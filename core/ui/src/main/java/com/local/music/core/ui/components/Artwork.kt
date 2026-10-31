package com.local.music.core.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
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
import com.local.music.core.ui.theme.GlassPalettes

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
 * 封面：优先画真封面，否则画渐变占位 + 装饰层；bordered = 1px 白 12% 描边。
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
    shape: Shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
    bordered: Boolean = false,
    bitmap: androidx.compose.ui.graphics.ImageBitmap? = null,
    artworkUri: String? = null,
) {
    // 只读缓存不复用 bitmap 参数语义：bitmap 优先（调用方已持有），其次按 uri 按需解码。
    // getOrDecode 命中 LRU 时为纯查表，未命中才解码一次 256px 缩略图。
    val resolved = bitmap ?: ArtworkBitmapCache.getOrDecode(artworkUri)
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
                    .drawBehind {
                        drawRect(
                            Brush.linearGradient(
                                style.colors,
                                start = Offset(size.width * 0.2f, 0f),
                                end = Offset(size.width * 0.8f, size.height),
                            ),
                        )
                        style.decorate(this, size)
                    },
            )
        }
        if (bordered) {
            Box(
                Modifier
                    .matchParentSize()
                    .border(1.dp, Color.White.copy(alpha = 0.12f), shape),
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
            val h by if (playing) {
                val t = rememberInfiniteTransition(label = "wave$i")
                t.animateFloat(
                    initialValue = WaveBarMinPx, targetValue = WaveBarMaxPx,
                    animationSpec = infiniteRepeatable(
                        tween(550, delayMillis = WaveDelays[i % WaveDelays.size]), RepeatMode.Reverse,
                    ),
                    label = "h$i",
                )
            } else {
                androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(WaveBarMinPx) }
            }
            Box(
                Modifier
                    .width(2.dp)
                    .height(WaveBarMaxDp)
                    .graphicsLayer {
                        scaleY = h / WaveBarMaxPx
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
 *  lyricLines = 同名 .lrc 解析结果（缺省用 DemoCatalog.lyrics 演示歌词）；
 *  格式参数：codec/sampleRateHz/bitDepth/bitrateKbps（0 = 未知，展示时降级）；
 *  元数据：genre 为扫描真值（空 = 未知流派，分组视图归一展示）；year/trackNo 取 MediaStore（0 = 未知）。 */
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
    val inLibrary: Boolean = true,
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
        inLibrary = false,
    )

    /** 演示曲库已移除：曲库内容唯一来源 = MediaStore 真实扫描（含曲库缓存） */
    val songs: List<DemoSong> get() = emptyList()
}

/** 秒数 → mm:ss 显示串（进度文本/倒计时共用）。
 *  同 [DemoSong.duration]，固定 Locale.US：默认 locale 会输出本地化数字字形，
 *  而这里是纯数值展示（不是给用户看的本地化量词），必须跨区域稳定。 */
fun Int.asTime(): String = String.format(java.util.Locale.US, "%02d:%02d", this / 60, this % 60)
