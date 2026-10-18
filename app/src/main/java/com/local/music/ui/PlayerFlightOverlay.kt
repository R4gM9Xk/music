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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
import com.local.music.core.ui.components.LocalPlayerFlight
import com.local.music.core.ui.components.PlayerFlight
import com.local.music.core.ui.theme.GlassIcons
import com.local.music.core.ui.theme.LocalGlassColors
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
 */
@Composable
fun PlayerFlightOverlay(
    flight: PlayerFlight,
    playing: Boolean,
    modifier: Modifier = Modifier,
) {
    val snap = flight.snapshot
    // 等待期（Entering 未起飞）用冻结的 mini 几何原地绘制替身：Chrome 已卸载而替身
    // 若等 player rects 才出现，起点有 1~N 帧空窗（迷你条闪没再重现）。此时 snapshot
    // 只含 mini 键，t 恒为 0 → 各样式 lerp 天然等于迷你条形态，与真身逐像素一致
    val enteringWait = flight.phase == PlayerFlight.Phase.Entering && !flight.started
    if (snap == null || (!flight.started && !enteringWait)) return
    val song = flight.songSnapshot ?: return
    val t = flight.t.value
    val glass = LocalGlassColors.current
    val density = LocalDensity.current
    val appHaze = LocalHazeState.current
    // 歌词模式收起：封面原地淡出，不参与飞行
    val skipCover = flight.phase == PlayerFlight.Phase.Exiting &&
        flight.visualMode == PlayerFlight.VisualMode.Lyrics

    fun flightRect(fromKey: String, toKey: String): Rect {
        val a = snap.getValue(fromKey)
        if (enteringWait) return a
        val b = snap.getValue(toKey)
        return Rect(
            lerp(a.left, b.left, t), lerp(a.top, b.top, t),
            lerp(a.right, b.right, t), lerp(a.bottom, b.bottom, t),
        )
    }

    Box(
        modifier
            .fillMaxSize()
            // 收尾交叉淡出：进入最后 12% 真身渐入时替身渐出（消灭双影像）；
            // 退出最后 12% 同理让位给 Chrome 真身
            .graphicsLayer {
                alpha = when (flight.phase) {
                    PlayerFlight.Phase.Entering -> ((1f - t) / 0.12f).coerceIn(0f, 1f)
                    PlayerFlight.Phase.Exiting -> (t / 0.12f).coerceIn(0f, 1f)
                    else -> 1f
                }
            },
    ) {
        // ── 导航胶囊渐隐替身（仅进入飞行）：Chrome 已退出布局，原地随 t 消隐 ──
        if (flight.phase == PlayerFlight.Phase.Entering) {
            snap[FlightKeys.MiniNav]?.let { navRect ->
                GlassNavBar(
                    selected = flight.entryNavSelected,
                    onSelect = {},
                    modifier = Modifier
                        .offset { IntOffset(navRect.left.roundToInt(), navRect.top.roundToInt()) }
                        .size(with(density) { DpSize(navRect.width.toDp(), navRect.height.toDp()) })
                        .graphicsLayer { alpha = 1f - t },
                )
            }
        }

        // ── 玻璃面板：Hi(迷你条) → Md(控制台)，填充/模糊半径随 t 插值，R角 20→28 ──
        run {
            val rect = flightRect(FlightKeys.MiniGlass, FlightKeys.PlayerGlass)
            val shape = RoundedCornerShape(lerp(20.dp, 28.dp, t))
            val fill = lerp(glass.hi, glass.md, t)
            Box(
                Modifier
                    .offset { IntOffset(rect.left.roundToInt(), rect.top.roundToInt()) }
                    .size(with(density) { DpSize(rect.width.toDp(), rect.height.toDp()) })
                    .clip(shape)
                    .then(
                        if (appHaze != null) {
                            Modifier.hazeEffect(
                                state = appHaze,
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
                    .background(fill)
                    .border(1.dp, glass.strokeSoft, shape)
                    .drawBehind {
                        // GlassSurface 顶部亮线（glassTopLine 为模块私有，此处同款复刻）
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

        // ── 封面：42dp/R11 ⇄ 大封面/R28，阴影 0→18 渐起 ──
        if (!skipCover) {
            val rect = flightRect(FlightKeys.MiniCover, FlightKeys.PlayerCover)
            val shape = RoundedCornerShape(lerp(11.dp, 28.dp, t))
            Box(
                Modifier
                    .offset { IntOffset(rect.left.roundToInt(), rect.top.roundToInt()) }
                    .size(with(density) { rect.width.toDp() })
                    .shadow(lerp(0.dp, 18.dp, t), shape)
                    .clip(shape)
                    .border(1.dp, lerp(Color.White.copy(alpha = 0.12f), glass.stroke, t), shape),
            ) {
                ArtThumb(song.art, Modifier.fillMaxSize(), shape, bordered = true, bitmap = song.coverBitmap)
            }
        }

        // ── 歌名 / 作者：直线平移 + 字号 lerp（真布局渲染，非缩放） ──
        val titleRect = flightRect(FlightKeys.MiniTitle, FlightKeys.PlayerTitle)
        Text(
            song.title,
            color = glass.textPrimary,
            fontSize = lerp(13.5f, 16f, t).sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .offset { IntOffset(titleRect.left.roundToInt(), titleRect.top.roundToInt()) }
                .size(with(density) { DpSize(titleRect.width.toDp(), titleRect.height.toDp()) }),
        )
        val artistRect = flightRect(FlightKeys.MiniArtist, FlightKeys.PlayerArtist)
        Text(
            song.artist,
            color = glass.textSecondary,
            fontSize = lerp(11f, 12f, t).sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .offset { IntOffset(artistRect.left.roundToInt(), artistRect.top.roundToInt()) }
                .size(with(density) { DpSize(artistRect.width.toDp(), artistRect.height.toDp()) }),
        )

        // ── 播放/暂停：图标平移放大；66dp 圆形玻璃底随 t 由虚到实 ──
        run {
            val rect = flightRect(FlightKeys.MiniPlay, FlightKeys.PlayerPlay)
            Box(
                Modifier
                    .offset { IntOffset(rect.left.roundToInt(), rect.top.roundToInt()) }
                    .size(with(density) { DpSize(rect.width.toDp(), rect.height.toDp()) }),
                contentAlignment = Alignment.Center,
            ) {
                if (t > 0.02f) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .graphicsLayer { alpha = t }
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
                }
                Icon(
                    if (playing) GlassIcons.Pause else GlassIcons.Play, null,
                    Modifier.size(lerp(22.dp, 26.dp, t)), tint = glass.textPrimary,
                )
            }
        }

        // ── 下一首：图标平移放大，色调随目标（白 → 次级）过渡 ──
        run {
            val rect = flightRect(FlightKeys.MiniNext, FlightKeys.PlayerNext)
            Box(
                Modifier
                    .offset { IntOffset(rect.left.roundToInt(), rect.top.roundToInt()) }
                    .size(with(density) { DpSize(rect.width.toDp(), rect.height.toDp()) }),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    GlassIcons.Next, null,
                    Modifier.size(lerp(22.dp, 28.dp, t)),
                    tint = lerp(glass.textPrimary, glass.textSecondary, t),
                )
            }
        }
    }
}
