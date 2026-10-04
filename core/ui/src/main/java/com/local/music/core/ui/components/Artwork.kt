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
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
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

/** 封面：bitmap 非空画真封面（内嵌图），否则画渐变占位 + 装饰层；bordered = 1px 白 12% 描边 */
@Composable
fun ArtThumb(
    style: ArtStyle,
    modifier: Modifier = Modifier,
    shape: Shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
    bordered: Boolean = false,
    bitmap: androidx.compose.ui.graphics.ImageBitmap? = null,
) {
    Box(modifier.clip(shape)) {
        if (bitmap != null) {
            androidx.compose.foundation.Image(
                bitmap = bitmap,
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
    val delays = listOf(0, 180, 360, 100, 280)
    Row(
        modifier.height(14.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        repeat(barCount) { i ->
            val h by if (playing) {
                val t = rememberInfiniteTransition(label = "wave$i")
                t.animateFloat(
                    initialValue = 4f, targetValue = 13f,
                    animationSpec = infiniteRepeatable(
                        tween(550, delayMillis = delays[i % delays.size]), RepeatMode.Reverse,
                    ),
                    label = "h$i",
                )
            } else {
                androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(4f) }
            }
            Box(
                Modifier
                    .width(2.dp)
                    .height(h.dp)
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(1.dp))
                    .drawBehind { drawRect(color) },
            )
        }
    }
}

// ── 演示数据（与设计稿同文案）──────────────────────────────

/** 曲目：id 供收藏/歌单/队列引用；时长以秒为单源，duration 为显示串。
 *  真曲库文件：mediaUri = MediaStore content uri；coverBitmap = 内嵌封面缩略图；
 *  lyricLines = 同名 .lrc 解析结果（缺省用 DemoCatalog.lyrics 演示歌词）；
 *  格式参数：codec/sampleRateHz/bitDepth/bitrateKbps（0 = 未知，展示时降级）。 */
data class DemoSong(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationSec: Int,
    val art: ArtStyle,
    val folder: String = "音乐",
    val genre: String = "独立",
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
) {
    val subtitle get() = "$artist · $album"
    val duration: String get() = "%d:%02d".format(durationSec / 60, durationSec % 60)
}

object DemoData {
    val songs = listOf(
        DemoSong("night-flight", "夜航西飞", "林之遥", "城市边缘", 256, ArtStyle.Night, genre = "流行"),
        DemoSong("tide-signal", "潮汐信号", "海边的卡夫卡", "潮汐信号", 232, ArtStyle.Tide, genre = "电子"),
        DemoSong("afternoon", "午后三点半", "沈青", "慢下来", 304, ArtStyle.Afternoon, folder = "下载", genre = "民谣"),
        DemoSong("galaxy", "银河公路", "远山乐队", "长途", 278, ArtStyle.Galaxy, folder = "下载", genre = "摇滚"),
        DemoSong("old-tape", "旧磁带", "周一", "复刻", 201, ArtStyle.Tape, genre = "流行"),
        DemoSong("white-room", "白色房间", "林之遥", "城市边缘", 242, ArtStyle.Room, genre = "独立"),
        DemoSong("rain-tower", "雨落信号塔", "海边的卡夫卡", "潮汐信号", 371, ArtStyle.Tide, folder = "下载", genre = "电子"),
        DemoSong("south-story", "城南旧事", "沈青", "慢下来", 287, ArtStyle.Afternoon, folder = "录音", genre = "民谣"),
        // 仅搜索可见（单曲/伴奏版），不进曲库列表——对齐设计稿搜索结果
        DemoSong("night-shuttle", "夜航班车", "远山乐队", "长途", 238, ArtStyle.Galaxy, inLibrary = false),
        DemoSong("night-flight-instr", "夜航西飞（伴奏）", "林之遥", "城市边缘", 256, ArtStyle.Night, inLibrary = false),
    )

    /** 曲库列表（tab「歌曲」与扫描计数口径） */
    val librarySongs: List<DemoSong> get() = songs.filter { it.inLibrary }
}

fun Int.asTime(): String = "%02d:%02d".format(this / 60, this % 60)
