package com.local.music.feature.player.screen

import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.updateTransition
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
import androidx.compose.foundation.layout.offset
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
import androidx.compose.runtime.SideEffect
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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import com.local.music.core.ui.components.ArtStyle
import com.local.music.core.ui.components.ArtThumb
import com.local.music.core.ui.components.DemoCatalog
import com.local.music.core.ui.components.DemoSong
import com.local.music.core.ui.components.FlightKeys
import com.local.music.core.ui.components.GlassIconButton
import com.local.music.core.ui.components.GlassLevel
import com.local.music.core.ui.components.GlassSurface
import com.local.music.core.ui.components.LyricLine
import com.local.music.core.ui.components.LocalHazeState
import com.local.music.core.ui.components.LocalPlayerFlight
import com.local.music.core.ui.components.PlayerFlight
import com.local.music.core.ui.components.ProgressTrack
import com.local.music.core.ui.components.QueueSheet
import com.local.music.core.ui.components.SongActionsSheet
import com.local.music.core.ui.components.asTime
import com.local.music.core.ui.components.glassHaze
import com.local.music.core.ui.components.pressScale
import com.local.music.core.ui.components.trackFlight
import com.local.music.core.ui.state.AppState
import com.local.music.core.ui.state.GlassScreen
import com.local.music.core.ui.state.RepeatMode
import com.local.music.core.ui.theme.GlassIcons
import com.local.music.core.ui.theme.GlassMotion
import com.local.music.core.ui.theme.LocalAuroraPalette
import com.local.music.core.ui.theme.LocalGlassColors
import com.local.music.core.ui.theme.auroraAccentColor
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import kotlin.math.roundToInt

/** 播放页内底部面板 */
private enum class PlayerSheet { Queue, More }

/**
 * 播放页 · 歌词主角
 * - 统一舞台：歌词 rail / 唯一封面 / 纯音乐文案共存，modeT 插值几何——
 *   封面永不卸载，歌词⇄封面即封面在「左上 64dp 小窗 ⇄ 居中大方块」间边放大边直线平移
 * - 28dp 圆角毛玻璃控制台：收藏 / 队列 / 随机 / 三态循环 全部接入 AppState
 * - 迷你条进入/收起走共享元素飞行（Flight.kt 状态机 + 覆盖层替身）
 */
@Composable
fun PlayerScreen(state: AppState, modifier: Modifier = Modifier) {
    val glass = LocalGlassColors.current
    val lyrics = state.currentLyrics
    val song = state.currentSong
    val flight = LocalPlayerFlight.current

    var sheet by remember { mutableStateOf<PlayerSheet?>(null) }

    // 进入飞行落封面模式：entryShowCover 为瞬态，点击封面/手势即清除，不动持久化偏好
    val showLyrics = state.lyricEnabled && lyrics != null && flight?.entryShowCover != true
    SideEffect {
        flight?.visualMode = if (showLyrics) PlayerFlight.VisualMode.Lyrics else PlayerFlight.VisualMode.Cover
    }
    // 入场封面模式只对「本次进入」生效：切歌（含自动切歌）后按歌词偏好回到正常语义
    LaunchedEffect(song.id) {
        val f = flight ?: return@LaunchedEffect
        if (f.entryShowCover && f.songSnapshot?.id != song.id) f.entryShowCover = false
    }

    // 左右滑动切模式：左滑 → 封面模式，右滑 → 歌词模式
    val swipeThreshold = with(LocalDensity.current) { 48.dp.toPx() }
    fun Modifier.modeSwipe() = pointerInput(Unit) {
        var total = 0f
        detectHorizontalDragGestures(
            onDragStart = { total = 0f },
            onHorizontalDrag = { change, amount ->
                change.consume()
                total += amount
            },
            onDragEnd = {
                when {
                    total <= -swipeThreshold -> {
                        flight?.entryShowCover = false
                        state.lyricEnabled = false
                    }
                    total >= swipeThreshold -> {
                        flight?.entryShowCover = false
                        state.lyricEnabled = true
                    }
                }
            },
        )
    }

    // 封面点击：对当前视觉模式取反（entryShowCover 态下首次点击即回到正常切换语义）
    val onCoverClick: () -> Unit = {
        flight?.entryShowCover = false
        state.lyricEnabled = !showLyrics
    }

    // 播放页自持采样状态：顶栏+舞台为 hazeSource，控制台/底部面板为兄弟效果层
    val playerHaze = rememberHazeState()
    CompositionLocalProvider(LocalHazeState provides playerHaze) {
        Column(
            modifier
                .fillMaxSize()
                .trackFlight(FlightKeys.PlayerRoot)
                .statusBarsPadding()
                .navigationBarsPadding()
                // 飞行期触摸闸门：graphicsLayer alpha 不挡触摸，进入等待期/退出期播放页全透明
                // 仍可点（双击迷你条误触透明控制台、误点中央透明封面清 entryShowCover 连锁）。
                // 在 Initial 隧道先行消费全部指针事件——子层 clickable/滑动手势全部失效；
                // phase 回到 Idle 即摘除（.then(Modifier) 零开销，正常态歌词行点击/左右滑不受影响）。
                // 替身层在 GlassApp 根层的 Overlay（无 pointerInput），不受此闸门影响
                .then(
                    if (flight?.isActive == true) {
                        Modifier.pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) {
                                    awaitPointerEvent(PointerEventPass.Initial)
                                        .changes
                                        .forEach { it.consume() }
                                }
                            }
                        }
                    } else {
                        Modifier
                    },
                ),
        ) {
            Box(Modifier.weight(1f).hazeSource(playerHaze)) {
                Column(Modifier.fillMaxSize()) {
                    // ── 顶栏：收起 / 正在播放 / 更多（非共享：原地由虚到实）──
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .graphicsLayer { alpha = flight?.softAlpha() ?: 1f }
                            .padding(start = 20.dp, end = 20.dp, top = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        GlassIconButton(onClick = {
                            val f = flight
                            if (f != null) {
                                f.beginExit(state.currentSong) { state.screen = it }
                            } else {
                                state.screen = GlassScreen.Library
                            }
                        }) {
                            Icon(GlassIcons.Down, null, Modifier.size(20.dp), tint = LocalGlassColors.current.textPrimary)
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                            Text("正在播放", color = LocalGlassColors.current.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("来自「${state.queueSource ?: "本地音乐"}」", color = LocalGlassColors.current.textSecondary, fontSize = 11.sp)
                        }
                        GlassIconButton(onClick = { sheet = PlayerSheet.More }) {
                            Icon(GlassIcons.More, null, Modifier.size(20.dp), tint = LocalGlassColors.current.textPrimary)
                        }
                    }

                    // ── 统一舞台：modeT(0=封面端 1=歌词端) 插值，封面中心+尺寸同帧直线过渡 ──
                    val modeT by updateTransition(showLyrics, label = "playerMode").animateFloat(
                        transitionSpec = { GlassMotion.tweenLyric() },
                        label = "modeT",
                    ) { if (it) 1f else 0f }

                    BoxWithConstraints(
                        Modifier
                            .weight(1f)
                            .modeSwipe()
                            .padding(top = 8.dp, bottom = 4.dp),
                    ) {
                        val density = LocalDensity.current
                        val stageW = constraints.maxWidth.toFloat()
                        val stageH = constraints.maxHeight.toFloat()
                        val pureMusic = lyrics == null && song.mediaUri != null

                        // 封面两端 rect（舞台本地 px）：左上 64dp 小窗 ⇄ 居中大方块
                        val smallSize = with(density) { 64.dp.toPx() }
                        val smallLeft = with(density) { 22.dp.toPx() }
                        val smallTop = with(density) { 14.dp.toPx() }
                        val bigSize = minOf(with(density) { 280.dp.toPx() }, stageW, stageH * 0.85f)
                        val bigCenterY = stageH / 2f -
                            if (pureMusic) with(density) { (18.dp + 20.dp).toPx() / 2f } else 0f
                        val smallRect = Rect(smallLeft, smallTop, smallLeft + smallSize, smallTop + smallSize)
                        val bigRect = Rect(
                            stageW / 2f - bigSize / 2f, bigCenterY - bigSize / 2f,
                            stageW / 2f + bigSize / 2f, bigCenterY + bigSize / 2f,
                        )
                        // 边缘线性插值 = 中心走直线 + 尺寸线性增长（设计要求的直线平移）
                        // modeT: 0=封面端(大居中 R28 阴影18) 1=歌词端(左上64dp R18 阴影10)
                        val coverRect = Rect(
                            lerp(bigRect.left, smallRect.left, modeT),
                            lerp(bigRect.top, smallRect.top, modeT),
                            lerp(bigRect.right, smallRect.right, modeT),
                            lerp(bigRect.bottom, smallRect.bottom, modeT),
                        )
                        val coverShape = RoundedCornerShape(lerp(28.dp, 18.dp, modeT))

                        // 歌词 rail：modeT>0 时在组合内；整体随 modeT 淡入淡出 × softAlpha（歌词区归非共享组）
                        if (lyrics != null && modeT > 0f) {
                            LyricRail(
                                lines = lyrics,
                                current = state.currentLyricIndex,
                                onSelect = { index -> state.seekTo(lyrics[index].timeSec + 0.01f) },
                                modifier = Modifier
                                    .fillMaxSize()
                                    .graphicsLayer { alpha = modeT * (flight?.softAlpha() ?: 1f) },
                            )
                        }

                        // 唯一封面：永不卸载，切歌只换 bitmap；飞行期让位替身（共享真身 alpha 门控），
                        // 歌词模式退出时封面不飞，改随非共享组原地淡出
                        val coverInteraction = remember { MutableInteractionSource() }
                        Box(
                            Modifier
                                .offset {
                                    IntOffset(coverRect.left.roundToInt(), coverRect.top.roundToInt())
                                }
                                .size(with(density) { coverRect.width.toDp() })
                                .trackFlight(FlightKeys.PlayerCover)
                                .graphicsLayer {
                                    val f = flight
                                    alpha = if (f != null &&
                                        f.phase == PlayerFlight.Phase.Exiting &&
                                        f.visualMode == PlayerFlight.VisualMode.Lyrics
                                    ) {
                                        f.softAlpha()
                                    } else {
                                        f?.playerSharedAlpha() ?: 1f
                                    }
                                }
                                .pressScale(coverInteraction, pressed = 0.95f)
                                .clickable(interactionSource = coverInteraction, indication = null, onClick = onCoverClick)
                                .shadow(lerp(18.dp, 10.dp, modeT), coverShape)
                                .border(1.dp, glass.stroke, coverShape),
                        ) {
                            ArtThumb(
                                song.art,
                                Modifier.fillMaxSize(),
                                coverShape,
                                bitmap = song.coverBitmap,
                            )
                        }

                        // 纯音乐空态：无歌词的真实曲目，跟随封面端淡入淡出
                        if (pureMusic) {
                            Text(
                                "纯音乐，请欣赏",
                                color = LocalGlassColors.current.textSecondary, fontSize = 13.sp,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .offset {
                                        IntOffset(0, (coverRect.bottom + with(density) { 20.dp.toPx() }).roundToInt())
                                    }
                                    .graphicsLayer { alpha = 1f - modeT },
                            )
                        }
                    }
                }
            }

            // ── 玻璃控制台（背景真模糊：采样舞台区）。玻璃本体/歌名/副标题/播放/下一首 = 共享真身；
            //    收藏/队列/进度/时间/随机/上一首/循环 = 非共享原地淡入淡出。
            //    底部面板打开时控制台整体让位（与 GlassApp 对 Chrome 的 ModalState 策略同源——
            //    面板与控制台都是带 hazeEffect 的兄弟层，重叠时 HWUI 合成顺序会串台）
            val sheetOpen = sheet != null
            val sharedAlpha = { (flight?.playerSharedAlpha() ?: 1f) * (if (sheetOpen) 0f else 1f) }
            val softAlpha = { (flight?.softAlpha() ?: 1f) * (if (sheetOpen) 0f else 1f) }
            GlassSurface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, bottom = 14.dp)
                    .trackFlight(FlightKeys.PlayerGlass)
                    .graphicsLayer { alpha = sharedAlpha() },
                level = GlassLevel.Md,
                shape = RoundedCornerShape(28.dp),
                blur = GlassLevel.Md,
            ) {
                Column(Modifier.padding(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 16.dp)) {
                    // 歌曲信息 + 收藏 + 队列
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(song.title, color = LocalGlassColors.current.textPrimary, fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .trackFlight(FlightKeys.PlayerTitle)
                                    .graphicsLayer { alpha = sharedAlpha() })
                            Text(song.subtitle, color = LocalGlassColors.current.textSecondary, fontSize = 12.sp,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .trackFlight(FlightKeys.PlayerArtist)
                                    .graphicsLayer { alpha = sharedAlpha() })
                        }
                        Spacer(Modifier.width(12.dp))
                        ConsoleIcon(
                            onClick = { state.toggleFavorite(song.id) },
                            modifier = Modifier.graphicsLayer { alpha = softAlpha() },
                        ) {
                            Icon(
                                if (state.isFavorite(song.id)) GlassIcons.HeartFilled else GlassIcons.Heart, null,
                                Modifier.size(21.dp), tint = LocalGlassColors.current.textPrimary,
                            )
                        }
                        ConsoleIcon(
                            onClick = { sheet = PlayerSheet.Queue },
                            modifier = Modifier.graphicsLayer { alpha = softAlpha() },
                        ) {
                            Icon(GlassIcons.Queue, null, Modifier.size(21.dp),
                                tint = LocalGlassColors.current.textSecondary)
                        }
                    }

                    // 进度：轨道 + 时间
                    Spacer(Modifier.height(12.dp))
                    Box(Modifier.graphicsLayer { alpha = softAlpha() }) {
                        ProgressTrack(progress = state.progressFraction, onSeek = state::seekToFraction)
                    }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                            .graphicsLayer { alpha = softAlpha() },
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(state.progressSec.toInt().asTime(), color = LocalGlassColors.current.textSecondary, fontSize = 11.sp)
                        Text(song.duration, color = LocalGlassColors.current.textSecondary, fontSize = 11.sp)
                    }

                    // 控制键：随机 / 上一首 / 播放 / 下一首 / 循环（三态）
                    Row(
                        Modifier.fillMaxWidth().padding(top = 10.dp, start = 2.dp, end = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        ConsoleIcon(onClick = state::toggleShuffle, modifier = Modifier.graphicsLayer { alpha = softAlpha() }) {
                            Icon(GlassIcons.Shuffle, null, Modifier.size(20.dp),
                                tint = if (state.shuffleOn) auroraAccentColor() else LocalGlassColors.current.textSecondary)
                        }
                        ConsoleIcon(onClick = { state.prev() }, modifier = Modifier.graphicsLayer { alpha = softAlpha() }) {
                            Icon(GlassIcons.Prev, null, Modifier.size(28.dp),
                                tint = LocalGlassColors.current.textSecondary)
                        }
                        PlayPauseButton(
                            playing = state.playing,
                            onClick = state::togglePlayPause,
                            modifier = Modifier
                                .trackFlight(FlightKeys.PlayerPlay)
                                .graphicsLayer { alpha = sharedAlpha() },
                        )
                        ConsoleIcon(
                            onClick = { state.next() },
                            modifier = Modifier
                                .trackFlight(FlightKeys.PlayerNext)
                                .graphicsLayer { alpha = sharedAlpha() },
                        ) {
                            Icon(GlassIcons.Next, null, Modifier.size(28.dp),
                                tint = LocalGlassColors.current.textSecondary)
                        }
                        ConsoleIcon(onClick = state::cycleRepeat, modifier = Modifier.graphicsLayer { alpha = softAlpha() }) {
                            Box(Modifier.size(20.dp)) {
                                Icon(GlassIcons.Repeat, null, Modifier.size(20.dp),
                                    tint = if (state.repeatMode != RepeatMode.Off) {
                                        auroraAccentColor()
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
                                            .background(auroraAccentColor()),
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 底部提示行（按当前视觉模式而非持久化偏好）
            Text(
                if (showLyrics) "点击歌词行可跳转 · 左右滑动切换封面 / 歌词" else "左右滑动切换封面 / 歌词",
                color = LocalGlassColors.current.textFaint, fontSize = 10.5.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp)
                    .graphicsLayer { alpha = softAlpha() },
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }

    // ── 底部面板：队列 / 更多（单曲操作面板统一组件）────────
    QueueSheet(
        visible = sheet == PlayerSheet.Queue,
        onDismiss = { sheet = null },
        queue = state.queue,
        currentIndex = state.queueIndex,
        onPick = { index ->
            state.playSong(state.queue[index], state.queue)
            sheet = null
        },
        onMove = state::moveQueueItem,
        onRemove = state::removeQueueItem,
        onClearUpNext = state::clearUpNext,
        isPlaying = state.playing,
    )
    SongActionsSheet(
        visible = sheet == PlayerSheet.More,
        onDismiss = { sheet = null },
        state = state,
        song = song,
    )
}

/**
 * 歌词 rail：点击行 seek 到该行 + 500ms 弹性滚动居中；上下 18% 羽化渐隐。
 * （原 LyricStage 去掉内嵌封面小窗——封面由统一舞台接管，rail 只负责歌词）
 */
@Composable
private fun LyricRail(
    lines: List<LyricLine>,
    current: Int,
    onSelect: (Int) -> Unit,
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

    BoxWithConstraints(modifier.clipToBounds()) {
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
private fun PlayPauseButton(
    playing: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val glass = LocalGlassColors.current
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier
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
private fun ConsoleIcon(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier
            .pressScale(interactionSource, pressed = 0.88f)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .padding(6.dp),
    ) { content() }
}
