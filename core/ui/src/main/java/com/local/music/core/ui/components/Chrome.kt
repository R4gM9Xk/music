package com.local.music.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import com.local.music.core.ui.theme.GlassIcons
import com.local.music.core.ui.theme.LocalAuroraPalette
import com.local.music.core.ui.theme.LocalGlassColors
import com.local.music.core.ui.theme.R


/**
 * 播放进度轨：5px 高圆角轨道，seed2→文字色 渐变填充 + 端点 12dp 发光圆点。
 * 点击直接 seek；拖动本地预览、松手提交；24dp 触摸热区 + 读屏进度语义。
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

    Box(
        modifier
            .fillMaxWidth()
            .height(24.dp) // 触摸热区（视觉轨道仍 5dp 居中）
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
                val fillBrush = Brush.horizontalGradient(listOf(palette.seed2, glass.textPrimary))
                val dotGlow = glass.textPrimary.copy(alpha = 0.35f)
                val dotColor = glass.textPrimary
                val trackHeight = 5.dp.toPx()
                val dotRadius = 6.dp.toPx()
                val barTop = (size.height - trackHeight) / 2f
                onDrawBehind {
                    val shown = (dragProgress ?: progressProvider()).coerceIn(0f, 1f)
                    val barWidth = size.width
                    drawRoundRect(
                        color = trackColor,
                        topLeft = Offset(0f, barTop),
                        size = androidx.compose.ui.geometry.Size(barWidth, trackHeight),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(trackHeight / 2f),
                    )
                    if (shown > 0f) {
                        drawRoundRect(
                            brush = fillBrush,
                            topLeft = Offset(0f, barTop),
                            size = androidx.compose.ui.geometry.Size(barWidth * shown, trackHeight),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(trackHeight / 2f),
                        )
                    }
                    // 端点圆点 + 辉光（.fill::after）：x 随进度变化，绘制期算即可
                    val cx = (barWidth * shown).coerceIn(dotRadius, (barWidth - dotRadius).coerceAtLeast(dotRadius))
                    val cy = barTop + trackHeight / 2f
                    drawCircle(dotGlow, radius = dotRadius * 1.8f, center = Offset(cx, cy))
                    drawCircle(dotColor, radius = dotRadius, center = Offset(cx, cy))
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        // 读屏进度语义：独立小组件，10Hz 变化只重组它自己，不波及整条 Chrome
        ProgressSemantics(progressProvider)
    }
}

/** 进度轨的读屏语义载体（零尺寸）：state 读取被隔离在此，重组范围最小 */
@Composable
private fun ProgressSemantics(progressProvider: () -> Float) {
    Box(
        Modifier
            .fillMaxSize()
            .semantics {
                contentDescription = "播放进度"
                progressBarRangeInfo = ProgressBarRangeInfo(progressProvider().coerceIn(0f, 1f), 0f..1f)
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
 *  而 togglePlayPause / next 内部有 hasSongs 守卫 → 点了完全没反应，纯死按钮。 */
@Composable
fun MiniPlayerBar(
    song: DemoSong,
    playing: Boolean,
    progressProvider: () -> Float,
    onClick: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
    hasSongs: Boolean = true,
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
        // 唯一杠杆是 scrim（主题同向：深色压黑、浅色提白），它独立于 blur 也能压死透字
        // —— 这里**刻意不再加重 blur**：scrim alpha 已达 0.80，模糊结果对最终像素的
        // 可见贡献只剩 20%，再加大半径肉眼不可辨，只是白烧 GPU（RenderEffect 半径翻倍
        // 采样开销显著上升）。详见 GlassColors.chromeScrim 的推导。
        scrim = glass.chromeScrim,
        onClick = onClick,
    ) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ArtThumb(
                song.art,
                Modifier.trackFlight(FlightKeys.MiniCover, keepOnDispose = true).size(42.dp),
                RoundedCornerShape(R.Thumb10.dp),
                bordered = true,
                // coverBitmap 恒为 null（扫描不再预解码全库）；真封面经 artworkUri 按需解码
                bitmap = song.coverBitmap,
                artworkUri = song.artworkUri,
            )
            Column(Modifier.weight(1f)) {
                Text(song.title, color = glass.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.trackFlight(FlightKeys.MiniTitle, keepOnDispose = true))
                Text(song.artist, color = glass.textSecondary, fontSize = 11.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.trackFlight(FlightKeys.MiniArtist, keepOnDispose = true))
            }
            // 空曲库不渲染控制键（见函数 KDoc）
            if (hasSongs) {
                MiniBarButton(onPlayPause, Modifier.trackFlight(FlightKeys.MiniPlay, keepOnDispose = true)) {
                    Icon(if (playing) GlassIcons.Pause else GlassIcons.Play, null,
                        Modifier.size(22.dp), tint = glass.textPrimary)
                }
                MiniBarButton(onNext, Modifier.trackFlight(FlightKeys.MiniNext, keepOnDispose = true)) {
                    Icon(GlassIcons.Next, null, Modifier.size(22.dp), tint = glass.textPrimary)
                }
            }
        }
        // 底部进度线（.mini .ml）：绘制期读进度（同上，避免 10Hz 重组迷你条整行）
        // 空曲库（hasSongs = false）时进度恒为 0：不读 progressProvider，画出全空轨道，
        // 避免占位曲场景下显示一条来历不明的填充（P2-11）
        Box(
            Modifier
                .align(Alignment.BottomStart)
                .padding(horizontal = 12.dp)
                .fillMaxWidth()
                .height(2.5.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(glass.textPrimary.copy(alpha = 0.15f))
                .drawWithCache {
                    val brush = Brush.horizontalGradient(listOf(palette.seed2, glass.textPrimary))
                    onDrawBehind {
                        val shown = if (hasSongs) progressProvider().coerceIn(0f, 1f) else 0f
                        val w = size.width * shown
                        if (w > 0f) drawRect(brush = brush, size = androidx.compose.ui.geometry.Size(w, size.height))
                    }
                },
        )
    }
}

@Composable
private fun MiniBarButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier
            .pressScale(interactionSource, pressed = 0.88f)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .padding(4.dp),
    ) { content() }
}

/** 底导航胶囊：58h / 圆角 99 / md 玻璃 + hi 模糊层级，3 项，选中项白色 + 强调色辉光 */
@Composable
fun GlassNavBar(
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalAuroraPalette.current
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
        level = GlassLevel.Md,
        shape = CircleShape,
        blur = GlassLevel.Md,
        // F3 / G2：与迷你条同一问题（导航栏下方同样滚过列表行）。md 档透字实测更严重：
        // 局部对比 11.4:1，达到自身标签 11.8:1 的 96% —— 几乎完全穿透。
        // 与迷你条同一档 scrim；blur 作用于条**背后**的背景采样，不会糊掉自身图标与标签
        // （它们画在 haze 结果之上）。
        scrim = glass.chromeScrim,
    ) {
        Row(Modifier.fillMaxSize()) {
            items.forEach { (label, icon, index) ->
                val on = selected == index
                val interactionSource = remember { MutableInteractionSource() }
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .pressScale(interactionSource, pressed = 0.94f)
                        .clickable(interactionSource = interactionSource, indication = null) { onSelect(index) }
                        .semantics {
                            this.role = Role.Tab
                            this.selected = on
                        },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    // 选中图标：白色 + drop-shadow(0 0 8px seed2) 辉光（径向光晕近似）
                    Box(contentAlignment = Alignment.Center) {
                        if (on) {
                            Box(
                                Modifier
                                    .size(30.dp)
                                    .drawBehind {
                                        drawCircle(
                                            Brush.radialGradient(
                                                0f to palette.seed2.copy(alpha = 0.9f),
                                                1f to Color.Transparent,
                                            ),
                                        )
                                    },
                            )
                        }
                        Icon(
                            icon, null, Modifier.size(21.dp),
                            tint = if (on) glass.textPrimary else glass.textTertiary,
                        )
                    }
                    Spacer(Modifier.height(3.dp))
                    Text(
                        label, fontSize = 11.sp,
                        color = if (on) glass.textPrimary else glass.textTertiary,
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
    modifier: Modifier = Modifier,
    hasSongs: Boolean = true,
) {
    val flight = LocalPlayerFlight.current
    Column(
        modifier
            .fillMaxWidth()
            .navigationBarsPadding() // 手势条悬浮于 chrome 之下（对应设计稿 homebar）
            .padding(start = 12.dp, end = 12.dp, bottom = 10.dp),
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
 * 主屏列表底部避让高度：悬浮 Chrome（迷你条 62 + 间距 8 + 导航 58 + 底距 10 ≈ 138dp）+ 余量。
 *
 * 单点定义的原因：曲库/歌单/设置三个主屏原本各自硬编码 `Spacer(height = 146.dp)`，
 * Chrome 高度一改就要同步改三处，漏一处就是最后一行被迷你条盖住（且很难在评审里发现）。
 */
val ChromeBottomInset: androidx.compose.ui.unit.Dp = 146.dp

/** 迷你条与导航胶囊之间的间距（[ChromeBottomInset] 的构成之一，避免两处魔数漂移） */
private val NavBarBottomInsetSpacer = 8.dp
