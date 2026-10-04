package com.local.music.feature.player.screen

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import com.local.music.core.ui.components.ArtStyle
import com.local.music.core.ui.components.ArtThumb
import com.local.music.core.ui.components.DemoCatalog
import com.local.music.core.ui.components.DemoPlaylist
import com.local.music.core.ui.components.DemoSong
import com.local.music.core.ui.components.GlassIconButton
import com.local.music.core.ui.components.GlassLevel
import com.local.music.core.ui.components.GlassSheet
import com.local.music.core.ui.components.GlassSurface
import com.local.music.core.ui.components.LyricLine
import com.local.music.core.ui.components.LocalHazeState
import com.local.music.core.ui.components.ProgressTrack
import com.local.music.core.ui.components.glassHaze
import com.local.music.core.ui.components.QueueSheet
import com.local.music.core.ui.components.SheetAction
import com.local.music.core.ui.components.asTime
import com.local.music.core.ui.components.pressScale
import com.local.music.core.ui.state.AppState
import com.local.music.core.ui.state.GlassScreen
import com.local.music.core.ui.state.RepeatMode
import com.local.music.core.ui.theme.GlassIcons
import com.local.music.core.ui.theme.GlassMotion
import com.local.music.core.ui.theme.LocalAuroraPalette
import com.local.music.core.ui.theme.LocalGlassColors
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState

/** 播放页内底部面板 */
private enum class PlayerSheet { Queue, More, AddTo, Info }

/**
 * 播放页 · 歌词主角
 * - 滚动歌词占满中段：当前行由播放进度推导（点行 = seek）；歌词关闭时切换为封面模式
 * - 封面退居左上 64dp 悬浮小窗（点击进入流体云）
 * - 28dp 圆角毛玻璃控制台：收藏 / 队列 / 随机 / 三态循环 全部接入 AppState
 */
@Composable
fun PlayerScreen(state: AppState, modifier: Modifier = Modifier) {
    val palette = LocalAuroraPalette.current
    val glass = LocalGlassColors.current
    val lyrics = state.currentLyrics
    val song = state.currentSong

    var sheet by remember { mutableStateOf<PlayerSheet?>(null) }

    // 左右滑动切模式：左滑 → 封面模式，右滑 → 歌词模式
    val swipeThreshold = with(LocalDensity.current) { 48.dp.toPx() }
    fun Modifier.modeSwipe() = pointerInput(state.lyricEnabled) {
        var total = 0f
        detectHorizontalDragGestures(
            onDragStart = { total = 0f },
            onHorizontalDrag = { change, amount ->
                change.consume()
                total += amount
            },
            onDragEnd = {
                when {
                    total <= -swipeThreshold -> state.lyricEnabled = false
                    total >= swipeThreshold -> state.lyricEnabled = true
                }
            },
        )
    }

    // 播放页自持采样状态：顶栏+歌词舞台为 hazeSource，控制台/底部面板为兄弟效果层
    val playerHaze = rememberHazeState()
    CompositionLocalProvider(LocalHazeState provides playerHaze) {
        Column(modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Box(Modifier.weight(1f).hazeSource(playerHaze)) {
                Column(Modifier.fillMaxSize()) {
                    // ── 顶栏：收起 / 正在播放 / 更多 ─────────────────────
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            GlassIconButton(onClick = { state.screen = GlassScreen.Library }) {
                Icon(GlassIcons.Down, null, Modifier.size(20.dp), tint = LocalGlassColors.current.textPrimary)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                Text("正在播放", color = LocalGlassColors.current.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("来自「深夜巡航」", color = LocalGlassColors.current.textSecondary, fontSize = 11.sp)
            }
            GlassIconButton(onClick = { sheet = PlayerSheet.More }) {
                Icon(GlassIcons.More, null, Modifier.size(20.dp), tint = LocalGlassColors.current.textPrimary)
            }
        }

        // ── 歌词舞台 / 封面模式 / 纯音乐空态：切换带缩放动效，均可左右滑动 ──
        val showLyrics = state.lyricEnabled && lyrics != null
        AnimatedContent(
            targetState = showLyrics,
            transitionSpec = {
                if (targetState) {
                    // 回歌词：封面缩小退场，歌词轻微放大进入
                    (fadeIn(GlassMotion.tweenPage()) + scaleIn(GlassMotion.tweenPage(), initialScale = 1.06f))
                        .togetherWith(fadeOut(GlassMotion.tweenPage()) + scaleOut(GlassMotion.tweenPage(), targetScale = 0.55f))
                } else {
                    // 进封面：封面由小变大（0.55 → 1.0），歌词轻缩淡出
                    (fadeIn(GlassMotion.tweenPage()) + scaleIn(GlassMotion.tweenPage(), initialScale = 0.55f))
                        .togetherWith(fadeOut(GlassMotion.tweenPage()) + scaleOut(GlassMotion.tweenPage(), targetScale = 0.96f))
                }
            },
            label = "playerMode",
            modifier = Modifier.weight(1f).modeSwipe(),
        ) { showLyrics ->
            // 分支内重读当前歌词：切歌瞬间（旧帧 showLyrics=true 但新歌无 .lrc）不能依赖捕获值
            val branchLyrics = state.currentLyrics
            if (showLyrics && branchLyrics != null) {
                LyricStage(
                    lines = branchLyrics,
                    current = state.currentLyricIndex,
                    onSelect = { index -> state.seekTo(branchLyrics[index].timeSec + 0.01f) },
                    cover = song.art,
                    coverBitmap = song.coverBitmap,
                    onCoverClick = { state.lyricEnabled = !state.lyricEnabled },
                    modifier = Modifier.fillMaxSize().padding(top = 8.dp, bottom = 4.dp),
                )
            } else {
                // 封面模式：按可用宽高自适应（矮窗口/分屏不挤压控制区）
                BoxWithConstraints(
                    Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    val coverSize = minOf(280.dp, maxWidth, maxHeight * 0.85f)
                    val coverInteraction = remember { MutableInteractionSource() }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        ArtThumb(
                            song.art,
                            Modifier
                                .size(coverSize)
                                .pressScale(coverInteraction, pressed = 0.96f)
                                .clickable(
                                    interactionSource = coverInteraction, indication = null,
                                    onClick = { state.lyricEnabled = !state.lyricEnabled },
                                )
                                .shadow(18.dp, RoundedCornerShape(28.dp))
                                .border(1.dp, glass.stroke, RoundedCornerShape(28.dp)),
                            RoundedCornerShape(28.dp),
                            bitmap = song.coverBitmap,
                        )
                        if (branchLyrics == null && song.mediaUri != null) {
                            Spacer(Modifier.height(20.dp))
                            Text(
                                "纯音乐，请欣赏",
                                color = LocalGlassColors.current.textSecondary, fontSize = 13.sp,
                            )
                        }
                    }
                }
            }
        }
                }
            }

        // ── 玻璃控制台（背景真模糊：采样舞台区）──────────────
        GlassSurface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 14.dp)
                .glassHaze(GlassLevel.Md),
            level = GlassLevel.Md,
            shape = RoundedCornerShape(28.dp),
        ) {
            Column(Modifier.padding(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 16.dp)) {
                // 歌曲信息 + 收藏 + 队列
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(song.title, color = LocalGlassColors.current.textPrimary, fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(song.subtitle, color = LocalGlassColors.current.textSecondary, fontSize = 12.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Spacer(Modifier.width(12.dp))
                    ConsoleIcon(onClick = { state.toggleFavorite(song.id) }) {
                        Icon(
                            if (state.isFavorite(song.id)) GlassIcons.HeartFilled else GlassIcons.Heart, null,
                            Modifier.size(21.dp), tint = LocalGlassColors.current.textPrimary,
                        )
                    }
                    ConsoleIcon(onClick = { sheet = PlayerSheet.Queue }) {
                        Icon(GlassIcons.Queue, null, Modifier.size(21.dp),
                            tint = LocalGlassColors.current.textSecondary)
                    }
                }

                // 进度：轨道 + 时间
                Spacer(Modifier.height(12.dp))
                ProgressTrack(progress = state.progressFraction, onSeek = state::seekToFraction)
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(state.progressSec.toInt().asTime(), color = LocalGlassColors.current.textSecondary, fontSize = 11.sp)
                    Text(song.duration, color = LocalGlassColors.current.textSecondary, fontSize = 11.sp)
                }

                // 控制键：随机 / 上一首 / 播放 / 下一首 / 循环（三态）
                Row(
                    Modifier.fillMaxWidth().padding(top = 10.dp, start = 2.dp, end = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    ConsoleIcon(onClick = state::toggleShuffle) {
                        Icon(GlassIcons.Shuffle, null, Modifier.size(20.dp),
                            tint = if (state.shuffleOn) palette.seed3 else LocalGlassColors.current.textSecondary)
                    }
                    ConsoleIcon(onClick = { state.prev() }) {
                        Icon(GlassIcons.Prev, null, Modifier.size(28.dp),
                            tint = LocalGlassColors.current.textSecondary)
                    }
                    PlayPauseButton(playing = state.playing, onClick = state::togglePlayPause)
                    ConsoleIcon(onClick = { state.next() }) {
                        Icon(GlassIcons.Next, null, Modifier.size(28.dp),
                            tint = LocalGlassColors.current.textSecondary)
                    }
                    ConsoleIcon(onClick = state::cycleRepeat) {
                        Box(Modifier.size(20.dp)) {
                            Icon(GlassIcons.Repeat, null, Modifier.size(20.dp),
                                tint = if (state.repeatMode != RepeatMode.Off) {
                                    palette.seed3
                                } else {
                                    LocalGlassColors.current.textSecondary
                                },
                            )
                            if (state.repeatMode == RepeatMode.One) {
                                // 单曲循环角标
                                Box(
                                    Modifier
                                        .align(Alignment.TopEnd)
                                        .size(6.dp)
                                        .clip(CircleShape)
                                        .background(palette.seed3),
                                )
                            }
                        }
                    }
                }
            }
        }

        // 底部提示行
        Text(
            if (state.lyricEnabled) "点击歌词行可跳转 · 左右滑动切换封面 / 歌词" else "左右滑动切换封面 / 歌词",
            color = LocalGlassColors.current.textFaint, fontSize = 10.5.sp,
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        }
    }

    // ── 底部面板：队列 / 更多 / 加入歌单 / 歌曲信息 ────────
    QueueSheet(
        visible = sheet == PlayerSheet.Queue,
        onDismiss = { sheet = null },
        queue = state.queue,
        currentIndex = state.queueIndex,
        onPick = { index ->
            state.playSong(state.queue[index], state.queue)
            sheet = null
        },
    )
    GlassSheet(
        visible = sheet == PlayerSheet.More,
        onDismiss = { sheet = null },
        title = song.title,
    ) {
        SheetAction(
            icon = if (state.isFavorite(song.id)) GlassIcons.HeartFilled else GlassIcons.Heart,
            label = if (state.isFavorite(song.id)) "取消收藏" else "收藏",
            emphasized = state.isFavorite(song.id),
            onClick = {
                state.toggleFavorite(song.id)
                sheet = null
            },
        )
        SheetAction(
            icon = GlassIcons.Plus,
            label = "加入歌单",
            subtitle = "添加到已有歌单",
            onClick = { sheet = PlayerSheet.AddTo },
        )
        SheetAction(
            icon = GlassIcons.Book,
            label = "歌曲信息",
            subtitle = state.currentFormatText,
            onClick = { sheet = PlayerSheet.Info },
        )
    }
    GlassSheet(
        visible = sheet == PlayerSheet.AddTo,
        onDismiss = { sheet = null },
        title = "加入歌单",
    ) {
        (DemoCatalog.playlists + state.userPlaylists).forEach { playlist ->
            val added = state.isSongInPlaylist(playlist.id, song.id)
            PlaylistPickRow(playlist, added = added) {
                // 已在歌单中的曲目点击不生效（保持面板打开，展示"已添加"反馈）
                if (state.addToPlaylist(playlist.id, song.id)) sheet = null
            }
        }
    }
    GlassSheet(
        visible = sheet == PlayerSheet.Info,
        onDismiss = { sheet = null },
        title = "歌曲信息",
    ) {
        InfoRow("标题", song.title)
        InfoRow("艺术家", song.artist)
        InfoRow("专辑", song.album)
        InfoRow("时长", song.duration)
        InfoRow("格式", state.currentFormatText)
        InfoRow("文件夹", song.folder)
    }
}

/** 「加入歌单」选择行：封面 + 歌单名 + 计数；added = 已含该曲目 */
@Composable
private fun PlaylistPickRow(playlist: DemoPlaylist, added: Boolean = false, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .pressScale(interactionSource, pressed = 0.98f)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ArtThumb(playlist.art, Modifier.size(40.dp), RoundedCornerShape(10.dp), bordered = true)
        Column(Modifier.weight(1f)) {
            Text(playlist.title, color = LocalGlassColors.current.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(playlist.meta, color = LocalGlassColors.current.textTertiary, fontSize = 11.5.sp)
        }
        if (added) {
            Text("已添加", color = LocalGlassColors.current.textFaint, fontSize = 11.5.sp)
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = LocalGlassColors.current.textTertiary, fontSize = 13.sp, modifier = Modifier.width(72.dp))
        Text(value, color = LocalGlassColors.current.textPrimary, fontSize = 13.sp, modifier = Modifier.weight(1f))
    }
}

/** 歌词舞台：点击行 seek 到该行 + 500ms 弹性滚动居中；上下 18% 羽化渐隐 */
@Composable
private fun LyricStage(
    lines: List<LyricLine>,
    current: Int,
    onSelect: (Int) -> Unit,
    cover: ArtStyle,
    coverBitmap: androidx.compose.ui.graphics.ImageBitmap?,
    onCoverClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    // 每行在 rail 内的中心坐标（px），由 onGloballyPositioned 回填
    val lineCenters = remember { mutableStateMapOf<Int, Float>() }
    var targetOffset by remember { mutableFloatStateOf(0f) }
    val railOffset by animateFloatAsState(
        targetValue = targetOffset,
        animationSpec = GlassMotion.tweenLyric(),
        label = "railOffset",
    )

    BoxWithConstraints(modifier.clipToBounds()) { // .lystage overflow:hidden
        val stageHeightPx = with(density) { maxHeight.toPx() }
        // 切歌：清空旧行坐标并归零偏移，避免上一首的 rail 位置带入新歌词
        LaunchedEffect(lines) {
            lineCenters.clear()
            targetOffset = 0f
        }
        // 当前行中心 → 对齐舞台中心（rail 与舞台同高，行坐标即舞台坐标，同 CSS 公式）
        LaunchedEffect(current, stageHeightPx, lineCenters[current]) {
            val center = lineCenters[current] ?: return@LaunchedEffect
            targetOffset = stageHeightPx / 2f - center
        }

        // 歌词 rail：自定义 Layout——子行一律以无界高度测量。
        // （Column.fillMaxSize 会把溢出行的剩余高度压成 0，真实 .lrc 三四十行时"后半部分无显示"；
        //   这里测出内容总高后按 (舞台高−内容高)/2 居中放置，行坐标仍是舞台坐标系，
        //   targetOffset 公式与逐行位置衰减零改动）
        Layout(
            content = {
                lines.forEachIndexed { index, line ->
                    val stageY = railOffset + (lineCenters[index] ?: 0f)
                    val positionalAlpha = positionalFade(stageY, stageHeightPx)
                    LyricLineRow(
                        text = line.text,
                        distance = kotlin.math.abs(index - current),
                        positionalAlpha = positionalAlpha,
                        onClick = { onSelect(index) },
                        modifier = Modifier.onGloballyPositioned { coords ->
                            lineCenters[index] = coords.positionInParent().y + coords.size.height / 2f
                        },
                    )
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 30.dp)
                .graphicsLayer { translationY = railOffset },
        ) { measurables, constraints ->
            val gap = 20.dp.roundToPx()
            val placeables = measurables.map {
                it.measure(constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity))
            }
            val contentHeight = placeables.sumOf { it.height } + gap * (placeables.size - 1).coerceAtLeast(0)
            layout(constraints.maxWidth, constraints.maxHeight) {
                var y = (constraints.maxHeight - contentHeight) / 2
                placeables.forEach { p ->
                    p.placeRelative(0, y)
                    y += p.height + gap
                }
            }
        }

        // 封面小窗：左上 64dp 悬浮（top 14 / left 22，圆角 18，高光描边）
        val coverInteraction = remember { MutableInteractionSource() }
        ArtThumb(
            style = cover,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = 22.dp, top = 14.dp)
                .pressScale(coverInteraction, pressed = 0.94f)
                .size(64.dp)
                .shadow(10.dp, RoundedCornerShape(18.dp)) // box-shadow 0 10px 26px 黑40%
                .clip(RoundedCornerShape(18.dp))
                .border(1.dp, LocalGlassColors.current.stroke, RoundedCornerShape(18.dp))
                .clickable(interactionSource = coverInteraction, indication = null, onClick = onCoverClick),
            bitmap = coverBitmap,
        )
    }
}

/** 单行歌词：当前 24sp 粗体 + 强调色辉光；邻行 55% 白；远处 34% 白 + 微模糊；整行再乘位置衰减 */
@Composable
private fun LyricLineRow(
    text: String,
    distance: Int,
    positionalAlpha: Float,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalAuroraPalette.current
    val p by animateFloatAsState(
        targetValue = if (distance == 0) 1f else 0f,
        animationSpec = GlassMotion.tweenLyric(),
        label = "lyricP",
    )
    val targetAlpha = when (distance) {
        0 -> 1f
        1 -> 0.55f
        else -> 0.34f
    }
    val alpha by animateFloatAsState(targetAlpha, GlassMotion.tweenLyric(), label = "lyricA")
    val glowPx = with(LocalDensity.current) { 24.dp.toPx() }
    val interactionSource = remember { MutableInteractionSource() }

    Text(
        text = text,
        fontSize = lerp(17f, 24f, p).sp,
        fontWeight = if (p > 0.5f) FontWeight.Bold else FontWeight.Normal,
        color = LocalGlassColors.current.textPrimary.copy(alpha = alpha * positionalAlpha),
        letterSpacing = lerp(0f, 0.2f, p).sp,
        lineHeight = lerp(24.65f, 34.8f, p).sp, // 17/24 × 1.45
        style = androidx.compose.ui.text.TextStyle(
            shadow = if (p > 0.01f) {
                Shadow(
                    color = palette.seed2.copy(alpha = 0.6f * p * positionalAlpha),
                    blurRadius = glowPx * p,
                )
            } else null,
        ),
        modifier = modifier
            .then(if (distance > 1) Modifier.blur(0.4.dp) else Modifier)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick),
    )
}

/** 逐行位置衰减：行中心在舞台顶部 10% / 底部 8% 内平滑淡出（smoothstep），永不拦腰切行 */
private fun positionalFade(stageY: Float, stageH: Float): Float {
    if (stageH <= 0f) return 1f
    val topEnd = stageH * 0.10f
    val bottomStart = stageH * 0.92f
    return when {
        stageY <= 0f || stageY >= stageH -> 0f
        stageY < topEnd -> smoothstep(stageY / topEnd)
        stageY > bottomStart -> smoothstep(((stageH - stageY) / (stageH * 0.08f)).coerceIn(0f, 1f))
        else -> 1f
    }
}

private fun smoothstep(t: Float): Float {
    val x = t.coerceIn(0f, 1f)
    return x * x * (3f - 2f * x)
}

/** 播放/暂停大按钮：66dp 圆，hi→lo 玻璃渐变 + 高光描边 + 内嵌顶部亮线，按压 scale(.93) */
@Composable
private fun PlayPauseButton(playing: Boolean, onClick: () -> Unit) {
    val glass = LocalGlassColors.current
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        Modifier
            .pressScale(interactionSource, pressed = 0.93f)
            .size(66.dp)
            .clip(CircleShape)
            .background(Brush.linearGradient(listOf(glass.hi, glass.lo)))
            .border(1.dp, glass.stroke, CircleShape)
            .drawBehind {
                // box-shadow inset 0 1px 0 白50%（被圆形裁成短弦）
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
            }
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (playing) GlassIcons.Pause else GlassIcons.Play, null,
            Modifier.size(26.dp), tint = LocalGlassColors.current.textPrimary,
        )
    }
}

/** 控制台小图标按钮：按压 scale(.88) */
@Composable
private fun ConsoleIcon(onClick: () -> Unit, content: @Composable () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        Modifier
            .pressScale(interactionSource, pressed = 0.88f)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .padding(6.dp),
    ) { content() }
}
