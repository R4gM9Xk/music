package com.local.music.feature.player.screen

import androidx.compose.animation.core.Animatable
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
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
import androidx.compose.ui.platform.LocalConfiguration
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
import com.local.music.core.ui.components.GlassSheet
import com.local.music.core.ui.components.GlassSurface
import com.local.music.core.ui.components.LyricLine
import com.local.music.core.ui.components.LocalHazeState
import com.local.music.core.ui.components.LocalModalState
import com.local.music.core.ui.components.SongActionsSheet
import com.local.music.core.ui.components.LocalPlayerFlight
import com.local.music.core.ui.components.PlayerFlight
import com.local.music.core.ui.components.ProgressTrack
import com.local.music.core.ui.components.QueueSheet
import com.local.music.core.ui.components.SheetAction
import com.local.music.core.ui.components.asTime
import com.local.music.core.ui.components.glassHaze
import com.local.music.core.ui.components.pressScale
import com.local.music.core.ui.components.trackFlight
import com.local.music.core.ui.state.AppState
import com.local.music.core.ui.state.GlassScreen
import com.local.music.core.ui.state.RepeatMode
import com.local.music.core.ui.state.SleepTimerOptions
import com.local.music.core.ui.state.sleepTimerLabel
import com.local.music.core.ui.state.sleepTimerSubtitle
import com.local.music.core.ui.theme.GlassIcons
import com.local.music.core.ui.theme.GlassMotion
import com.local.music.core.ui.theme.LocalAuroraPalette
import com.local.music.core.ui.theme.LocalGlassColors
import com.local.music.core.ui.theme.auroraAccentColor
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlin.math.roundToInt

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
    // 模态面板宿主（GlassApp 根层）：队列/更多面板经 show 挂载，屏内不再渲染 GlassSheet
    val modal = LocalModalState.current
    // P2-⑤：横屏判定（供舞台/控制台/提示行的紧凑化共用）。
    // 用 configuration 的宽高比较而不是舞台的 stageW/stageH：舞台高度还受控制台占位影响，
    // 不能反过来当屏幕方向用（否则控制台一压，舞台自己就把自己判成横屏）。
    val configuration = LocalConfiguration.current
    val landscape = configuration.screenHeightDp < configuration.screenWidthDp

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

    // 封面点击：对当前视觉模式取反（entryShowCover 态下首次点击即回到正常切换语义）。
    // 无歌词（纯音乐）时不再静默写 lyricEnabled（P3-66）——点击没有任何视觉反馈，
    // 偏好却被改掉，之后回到有歌词的歌才发现「歌词开关自己变了」
    val onCoverClick: () -> Unit = {
        flight?.entryShowCover = false
        if (lyrics != null) state.lyricEnabled = !showLyrics
    }

    // 播放页自持采样状态：顶栏+舞台为 hazeSource，控制台为兄弟效果层
    // （底部面板已挂 GlassApp 根层宿主，消费 app 级源，不在本屏子树内）
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
                    // P2-⑤：横屏纵向极度紧张，顶栏收紧内边距（6dp → 0dp），把高度让给舞台
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .graphicsLayer { alpha = flight?.softAlpha() ?: 1f }
                            .padding(start = 20.dp, end = 20.dp, top = if (landscape) 0.dp else 6.dp),
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
                        GlassIconButton(onClick = {
                            // 播放选项面板挂根层宿主（照抄 modal.show 模式；重复打开即整组重建，状态无残留）
                            modal.show {
                                PlaybackOptionsSheet(
                                    visible = modal.visible,
                                    onDismiss = modal::requestDismiss,
                                    state = state,
                                )
                            }
                        }) {
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
                        // P2-⑤ 横屏：舞台高度被控制台+顶栏挤到 ~100dp 级，而旧公式
                        // `min(280dp, stageW, stageH*0.85)` 完全以高度为准 → 封面塌缩成
                        // 64dp 左右的小圆（实测正是「横屏封面变小小圆形缩略图 + 下半屏留白」）。
                        // 宽屏下改为「按可用高度与宽度共同约束，并保一个下限」：
                        // - 短边（=高度）决定封面边长，但设 96dp 下限，避免变得比歌词小窗还小；
                        // - 同时不超过舞台宽度的 42%，给呼吸区，不出现「一张大图顶满」。
                        val bigSize = if (stageW > stageH) {
                            minOf(stageH * 0.92f, stageW * 0.42f)
                                .coerceAtLeast(with(density) { 96.dp.toPx() })
                        } else {
                            minOf(with(density) { 280.dp.toPx() }, stageW, stageH * 0.85f)
                        }
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

                        // 歌词 rail：常驻组合（P3-15——旧写法 modeT>0 的组合期读取让模式切换
                        // 500ms 内整个舞台每帧重组）；淡入淡出交给下方 graphicsLayer 绘制期读。
                        // onSelect 门控 showLyrics：封面模式下 rail alpha=0 仍驻组合，不可见行不得响应点击
                        if (lyrics != null) {
                            LyricRail(
                                lines = lyrics,
                                current = state.currentLyricIndex,
                                onSelect = { index -> if (showLyrics) state.seekTo(lyrics[index].timeSec + 0.01f) },
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
                                artworkUri = song.artworkUri,
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
            //    底部面板打开时控制台整体让位：读全局 ModalState（含退场期，与 GlassApp 对
            //    Chrome 的门控同源）——面板滑出全程保持隐藏，退场时长到 content 清空后才复位
            val sheetOpen = modal.isOpen
            // 面板开合走 360ms 淡出/淡入（P3-63——if 硬切会让控制台在面板滑出的第一帧瞬时消失、
            // 回来时又瞬间闪现）；两个 lambdas 只在 graphicsLayer 内调用，绘制期读，不产生逐帧重组
            val sheetAlpha by animateFloatAsState(
                targetValue = if (sheetOpen) 0f else 1f,
                animationSpec = GlassMotion.tweenPage(),
                label = "sheetAlpha",
            )
            val sharedAlpha = { (flight?.playerSharedAlpha() ?: 1f) * sheetAlpha }
            val softAlpha = { (flight?.softAlpha() ?: 1f) * sheetAlpha }
            GlassSurface(
                modifier = Modifier
                    .fillMaxWidth()
                    // P2-⑤：横屏收紧外边距与底部留白，控制台少占的高度全部给舞台
                    .padding(
                        start = 16.dp,
                        end = 16.dp,
                        bottom = if (landscape) 6.dp else 14.dp,
                    )
                    .trackFlight(FlightKeys.PlayerGlass)
                    .graphicsLayer { alpha = sharedAlpha() },
                level = GlassLevel.Md,
                shape = RoundedCornerShape(28.dp),
                blur = GlassLevel.Md,
            ) {
                Column(
                    Modifier.padding(
                        start = 18.dp,
                        end = 18.dp,
                        top = if (landscape) 8.dp else 14.dp,
                        bottom = if (landscape) 8.dp else 16.dp,
                    ),
                ) {
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
                            onClick = {
                                modal.show {
                                    QueueSheet(
                                        visible = modal.visible,
                                        onDismiss = modal::requestDismiss,
                                        queue = state.queue,
                                        currentIndex = state.queueIndex,
                                        onPick = { index ->
                                            state.playSong(state.queue[index], state.queue)
                                            modal.requestDismiss()
                                        },
                                        onMove = state::moveQueueItem,
                                        onRemove = state::removeQueueItem,
                                        onClearUpNext = state::clearUpNext,
                                        isPlaying = state.playing,
                                    )
                                }
                            },
                            modifier = Modifier.graphicsLayer { alpha = softAlpha() },
                        ) {
                            Icon(GlassIcons.Queue, null, Modifier.size(21.dp),
                                tint = LocalGlassColors.current.textSecondary)
                        }
                    }

                    // 进度：轨道 + 时间
                    Spacer(Modifier.height(12.dp))
                    Box(Modifier.graphicsLayer { alpha = softAlpha() }) {
                        // 传取值函数：进度 10Hz 变，以 Float 传入会让整个控制台（含歌词区外的
                        // 音量/模式行）每 tick 重组。绘制期读才是这套 UI 的既有做法。
                        ProgressTrack(
                            progressProvider = { state.progressFraction },
                            onSeek = state::seekToFraction,
                        )
                    }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                            .graphicsLayer { alpha = softAlpha() },
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        // 时间文本无法走绘制期读（Text 必须组合期排版）：抽成极小组件，
                        // 让 10Hz 的重组范围只限于这一张文本，而不是整条控制台
                        ElapsedTimeText(state)
                        TotalTimeText(state)
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

            // 底部提示行（按当前视觉模式而非持久化偏好）；无歌词时不再教「滑动切换」（P3-65）——
            // 纯音乐没有歌词端可切，提示与舞台空态文案保持同一句话
            val hint = when {
                lyrics == null -> "纯音乐，请欣赏"
                showLyrics -> "点击歌词行可跳转 · 左右滑动切换封面 / 歌词"
                else -> "左右滑动切换封面 / 歌词"
            }
            Text(
                hint,
                color = LocalGlassColors.current.textFaint, fontSize = 10.5.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    // P2-⑤：横屏下提示行紧贴控制台，避免它在 720dp 高度里白占一行
                    .padding(bottom = if (landscape) 4.dp else 12.dp)
                    .graphicsLayer { alpha = softAlpha() },
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}

/**
 * 歌词 rail：点击行 seek 到该行；首次/切歌瞬时居中，行进切换 500ms 弹性滚动；上下羽化渐隐。
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
    // P2-32：偏移改 Animatable 手控。旧 animateFloatAsState 初值恒 0——每次进入播放页/切歌
    // 都会从 offset=0「滑」到居中位；Animatable 允许首次定位 snapTo 瞬时落位，行进切换才弹性动画
    val railOffset = remember { Animatable(0f) }
    // 当前歌词是否已完成过首次居中：换歌词（实例身份变化）即重置，每首歌的第一次定位不走动画
    var hasPositioned by remember(lines) { mutableStateOf(false) }

    BoxWithConstraints(modifier.clipToBounds()) {
        val stageHeightPx = with(density) { maxHeight.toPx() }
        // 切歌：清空旧行坐标并瞬时归零偏移（snapTo 不带动画），避免上一首的 rail 位置带入新歌词
        LaunchedEffect(lines) {
            lineCenters.clear()
            railOffset.snapTo(0f)
        }
        // 当前行中心 → 对齐舞台中心（rail 与舞台同高，行坐标即舞台坐标，同 CSS 公式）。
        // 行坐标由 onGloballyPositioned 异步回填：用 snapshotFlow 等它就绪后取一次，
        // 而不是把 lineCenters[current] 挂进 key（旧写法每次回填都重启 effect，当前行漂移 P3-67）
        LaunchedEffect(lines, current, stageHeightPx) {
            val center = snapshotFlow { lineCenters[current] }.filterNotNull().first()
            if (hasPositioned) {
                railOffset.animateTo(stageHeightPx / 2f - center, GlassMotion.tweenLyric())
            } else {
                // 首次定位（进入播放页 / 刚换歌）：瞬时对齐，不做「从 0 滑到居中」的入场动画
                railOffset.snapTo(stageHeightPx / 2f - center)
                hasPositioned = true
            }
        }

        // 歌词 rail：自定义 Layout——子行一律以无界高度测量。
        // （Column.fillMaxSize 会把溢出行的剩余高度压成 0，真实 .lrc 三四十行时"后半部分无显示"；
        //   这里测出内容总高后按 (舞台高−内容高)/2 居中放置，行坐标仍是舞台坐标系，
        //   railOffset 居中公式与逐行位置衰减零改动）
        Layout(
            content = {
                lines.forEachIndexed { index, line ->
                    LyricLineRow(
                        text = line.text,
                        distance = kotlin.math.abs(index - current),
                        // 位置衰减以 lambda 下发：railOffset 是动画值，行内 graphicsLayer 绘制期
                        // 才读（P3-68——旧版在组合期读，rail 每帧动画都把全部歌词行重组一遍）
                        positionalAlphaProvider = {
                            positionalFade(railOffset.value + (lineCenters[index] ?: 0f), stageHeightPx)
                        },
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
                .graphicsLayer { translationY = railOffset.value },
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

/** 单行歌词：当前 24sp 粗体 + 强调色辉光；邻行 55% 白；远处 34% 白 + 微模糊；整行再乘位置衰减（绘制期读） */
@Composable
private fun LyricLineRow(
    text: String,
    distance: Int,
    positionalAlphaProvider: () -> Float,
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
    // 命名必须避开 `alpha`：`graphicsLayer { }` 的接收者 GraphicsLayerScope 也有 alpha，
    // 而局部委托属性优先级高于隐式接收者成员 —— 叫 alpha 会让下方 `alpha = ...` 变成
    // 「给 val 赋值」编译错误（'val' cannot be reassigned）
    val lineAlpha by animateFloatAsState(targetAlpha, GlassMotion.tweenLyric(), label = "lyricA")
    val glowPx = with(LocalDensity.current) { 24.dp.toPx() }
    val interactionSource = remember { MutableInteractionSource() }

    Text(
        text = text,
        fontSize = lerp(17f, 24f, p).sp,
        // P3-67：字重随 p 连续插值（400→700），替代 >0.5 阈值瞬切——中段不再肉眼跳变
        fontWeight = FontWeight(lerp(400f, 700f, p).roundToInt()),
        // 位置衰减不再乘进文字/阴影颜色（那会组合期读动画值）：移到下方 graphicsLayer 绘制期乘，
        // 总透明度数学上等价（alpha 可交换），rail 滚动动画期间本行不重组
        color = LocalGlassColors.current.textPrimary.copy(alpha = lineAlpha),
        letterSpacing = lerp(0f, 0.2f, p).sp,
        lineHeight = lerp(24.65f, 34.8f, p).sp, // 17/24 × 1.45
        style = androidx.compose.ui.text.TextStyle(
            shadow = if (p > 0.01f) {
                Shadow(
                    color = palette.seed2.copy(alpha = 0.6f * p),
                    blurRadius = glowPx * p,
                )
            } else null,
        ),
        modifier = modifier
            .then(if (distance > 1) Modifier.blur(0.4.dp) else Modifier)
            .graphicsLayer { alpha = positionalAlphaProvider() }
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

/** 播放速度档位（Media3 原生 Sonic 变速不变调） */
private val PlaybackSpeedOptions = listOf(0.75f, 1f, 1.25f, 1.5f, 2f)

/** 速度档位展示名：固定一位小数（1f 显示「1.0×」，与 0.75× 对齐） */
private fun speedLabel(speed: Float): String =
    if (speed == speed.toInt().toFloat()) "${speed.toInt()}.0×" else "$speed×"

/**
 * 播放选项面板：定时关闭 + 播放速度（顶栏 More 打开，经 modal.show 挂根层宿主）。
 * 当前生效项 emphasized；分钟档剩余时间每秒刷新（deadline 语义在 AppState——
 * 服务端到点自行暂停且无回执，本地按期限推导结束态）。
 */
@Composable
private fun PlaybackOptionsSheet(
    visible: Boolean,
    onDismiss: () -> Unit,
    state: AppState,
) {
    // 秒级倒计时：每秒读一次剩余值驱动本面板重组（高亮/副标题随到点自动归位）
    var remainingSec by remember { mutableIntStateOf(0) }
    LaunchedEffect(state.sleepTimerDeadlineMs) {
        while (true) {
            remainingSec = state.sleepTimerRemainingSec() ?: 0
            delay(1000)
        }
    }
    val activeMode = state.effectiveSleepTimerMode()
    val modal = LocalModalState.current
    GlassSheet(visible = visible, onDismiss = onDismiss, title = "播放选项") {
        // 当前歌曲操作：More 改挂播放选项后，歌曲信息/加入歌单等从这里二次进入
        // （modal 单槽 show 原位替换，SongActionsSheet 返回键/点 scrim 关闭行为不变）
        GroupLabel("当前歌曲")
        // 打开面板时快照当前曲（P3-10）：面板挂着期间若自动切歌，「歌曲操作」的二级面板
        // 不得跟着漂到新歌——remember 钉住面板生命周期（裸 val 会被秒级倒计时重组刷新）
        val targetSong = remember { state.currentSong }
        SheetAction(
            icon = GlassIcons.More,
            label = "歌曲操作",
            subtitle = targetSong.title,
            onClick = {
                modal.show { SongActionsSheet(visible = modal.visible, onDismiss = modal::requestDismiss, state = state, song = targetSong) }
            },
        )
        GroupLabel("定时关闭")
        SleepTimerOptions.forEach { option ->
            SheetAction(
                icon = GlassIcons.Moon,
                label = sleepTimerLabel(option),
                // P3-47：「取消定时」只在真有定时时高亮；无定时时连「未开启」副标题也不给
                subtitle = if (option == 0 && activeMode == 0) null else sleepTimerSubtitle(option, activeMode, remainingSec),
                emphasized = option != 0 && option == activeMode,
                onClick = {
                    state.setSleepTimer(option)
                    onDismiss()
                },
            )
        }
        GroupLabel("播放速度")
        PlaybackSpeedOptions.forEach { speed ->
            SheetAction(
                icon = GlassIcons.Wave,
                label = speedLabel(speed),
                emphasized = state.playbackSpeed == speed,
                onClick = {
                    state.selectPlaybackSpeed(speed)
                    onDismiss()
                },
            )
        }
    }
}

/** 面板分组小标题（Sheet.kt 的 SectionLabel 为私有，就地等价实现；start=6dp 与 SheetAction 文案对齐） */
@Composable
private fun GroupLabel(text: String) {
    Text(
        text,
        color = LocalGlassColors.current.textTertiary,
        fontSize = 11.sp,
        letterSpacing = 0.3.sp,
        modifier = Modifier.padding(start = 6.dp, top = 10.dp, bottom = 4.dp),
    )
}

/**
 * 已播时长文本：把「10Hz 变化」的 state 读取隔离成极小组件。
 *
 * 为什么单独抽：`state.progressSec` 每 100ms 写一次，若在控制台函数体里读取，整条控制台
 * （模式行 / 音量 / 播放键 / 队列入口……）都会每 tick 重组。进度轨已改为绘制期读，
 * 而 Text 必须组合期排版、绕不开——那就把重组范围缩到「一张文本」。
 */
@Composable
private fun ElapsedTimeText(state: AppState) {
    Text(
        state.progressSec.toInt().asTime(),
        color = LocalGlassColors.current.textSecondary,
        fontSize = 11.sp,
    )
}

/**
 * 总时长文本（P3-1）：与进度条 / 已播时长同口径读引擎真值 durationSec，替代扫描元数据
 * song.duration——元数据缺失时扫描值兜底 1s，进度却按真实时长走，两个数字对不上。
 * 同样抽成极小组件：durationSec 相关状态（队列/索引）变化只重组这张文本，不波及控制台。
 */
@Composable
private fun TotalTimeText(state: AppState) {
    Text(
        state.durationSec.toInt().asTime(),
        color = LocalGlassColors.current.textSecondary,
        fontSize = 11.sp,
    )
}
