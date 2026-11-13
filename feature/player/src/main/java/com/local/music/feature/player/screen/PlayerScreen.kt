package com.local.music.feature.player.screen

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.updateTransition
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
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
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
import com.local.music.core.ui.state.RepeatMode
import com.local.music.core.ui.state.SleepTimerOptions
import com.local.music.core.ui.state.sleepTimerLabel
import com.local.music.core.ui.state.sleepTimerSubtitle
import com.local.music.core.ui.theme.GlassIcons
import com.local.music.core.ui.theme.GlassMotion
import com.local.music.core.ui.theme.GlassText
import com.local.music.core.ui.theme.LocalAuroraPalette
import com.local.music.core.ui.theme.R
import com.local.music.core.ui.theme.LocalGlassColors
import com.local.music.core.ui.theme.S
import com.local.music.core.ui.theme.auroraAccentColor
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlin.math.roundToInt

/**
 * 播放页 · 歌词主角
 * - 统一舞台：歌词 rail / 唯一封面 / 纯音乐文案共存，modeT 插值几何——
 *   封面永不卸载，歌词⇄封面即封面在「左上 64dp 小窗 ⇄ 居中大方块」间边放大边直线平移
 * - **歌名组出卡**（视觉审计 C2）：歌名/副标题是**裸内容**（无容器无描边），落在封面正下方；
 *   收藏/队列跟着出卡、与歌名同排右侧。玻璃控制台只留进度/时间/控制键，高度从 202dp 收到 152dp。
 * - 播放模式是**一枚**四态按钮（顺序 → 列表循环 → 单曲循环 → 随机），图标形变动效 + 1.2s 标签反馈
 * - 迷你条进入/收起走共享元素飞行（Flight.kt 状态机 + 覆盖层替身）
 *
 * ## 为什么歌名必须出卡（而不是「卡内放大」）
 *
 * 卡片是一个**容器**，容器内的元素天然读作「容器的一个字段」。歌名在卡里时，它和进度条、
 * 收藏钮是同一层级的三个字段——这正切断了「封面 ⇄ 歌名」这条唯一的归属关系。
 * 出卡后歌名与封面共享同一片裸背景，两者的间距（S.Gap12）小于它们与卡的间距，
 * 于是「这张封面叫这个名字」由**构图**表达，不再靠字号大小暗示。
 *
 * ## 飞行系统的对端几何（改布局 = 改对端，两处必须同时改）
 *
 * 歌名/副标题/播放键/下一首是共享元素真身，[com.local.music.ui.PlayerFlightOverlay] 按
 * `trackFlight` 回填的实测 rect 画替身——所以**只要真身 rect 由实测得出，飞行本身自动跟随**。
 * 但有两处不能自动跟随、必须在这里同步想清楚：
 * 1. **替身的字号/行高插值**要把终点改成新真身（歌名 16sp→**20sp Bold**、行高 22→26），
 *    否则落地那一刻替身字比真身瘦一圈（见 FlightLabel 的 sizeFrom/sizeTo/lineFrom/lineTo）；
 * 2. **透明度曲线**：歌名原先在卡内，退出时靠卡的 `playerSharedAlpha` 一起渐出；出卡后
 *    它不再是卡的子节点，必须**自己**用 `sharedAlpha`（= playerSharedAlpha × sheetAlpha），
 *    否则退出飞行时歌名会一直亮到播放页离场（比别的元素晚 300ms 硬消失）。
 */
@Composable
fun PlayerScreen(state: AppState, modifier: Modifier = Modifier) {
    val glass = LocalGlassColors.current
    val lyrics = state.currentLyrics
    val song = state.currentSong
    val flight = LocalPlayerFlight.current
    // A2-01：currentLyricIndex 的 getter 链组合期读 progressSec（10Hz tick）——直接把
    // state.currentLyricIndex 传给舞台，会让整个 stageBlock 每拍重组（封面 rect/shadow
    // 插值、LyricRail 调用点全部重跑，与本项目「进度只失效绘制」的既有做法相悖）。
    // derivedStateOf 只在结果（行下标）变化时失效：重组频率从 10Hz 降到歌词行推进频率。
    // key = currentLyrics（换歌换词才重建派生）
    val lyricIndex by remember(state.currentLyrics) { derivedStateOf { state.currentLyricIndex } }
    // 模态面板宿主（GlassApp 根层）：队列/更多面板经 show 挂载，屏内不再渲染 GlassSheet
    val modal = LocalModalState.current
    // P2-⑤：横屏判定（供舞台/控制台/提示行的紧凑化共用）。
    // 用 configuration 的宽高比较而不是舞台的 stageW/stageH：舞台高度还受控制台占位影响，
    // 不能反过来当屏幕方向用（否则控制台一压，舞台自己就把自己判成横屏）。
    val configuration = LocalConfiguration.current
    val landscape = configuration.screenHeightDp < configuration.screenWidthDp

    // 进入飞行落封面模式：entryShowCover 为瞬态，点击封面/手势即清除，不动持久化偏好。
    //
    // 注意：退出飞行期间这里**不会**翻转——`beginExit` 特意把 entryShowCover 的清理
    // 推迟到飞行结束（见 Flight.beginExit 的注释）。否则本值由 false 翻 true 会让 modeT
    // 从 0 动画到 1，封面改朝歌词小窗（左上）飞、歌词页闪一下，与「缩回右下迷你条」冲突。
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
    //
    // **无歌词时整条手势不生效**（本轮修复，与 onCoverClick 的 P3-66 同口径）：
    // 旧实现只给封面点击加了 `lyrics != null` 守卫，滑动分支仍无条件写 lyricEnabled —
    // 于是纯音乐/占位曲上滑一下，偏好被静默改掉，用户回到有歌词的歌才发现「歌词开关自己变了」。
    // 同一状态的另一个入口（onCoverClick）已经修好，两处判定必须一致。
    val swipeThreshold = with(LocalDensity.current) { 48.dp.toPx() }
    fun Modifier.modeSwipe() = pointerInput(lyrics != null) {
        var total = 0f
        detectHorizontalDragGestures(
            onDragStart = { total = 0f },
            onHorizontalDrag = { change, amount ->
                change.consume()
                total += amount
            },
            onDragEnd = {
                if (lyrics == null) return@detectHorizontalDragGestures
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
            // ── P2-⑤ 横屏布局：双栏 ──
            // 竖屏保持原单列（顶栏 / 舞台 / 控制台 / 提示）。
            // 横屏（高度仅 360dp）改「顶栏通栏 + 下方左舞台 / 右控制台」：
            // 单列时顶栏+控制台+提示把舞台压到 ~76dp，封面按任何公式都放不大——
            // 这正是 P2-⑤「封面塌缩成小圆 + 下半屏留白」的病灶，改尺寸公式治不了，
            // 必须让舞台拿到**整列高度**。四个内容块抽成局部 composable 共用，
            // 避免两套布局重复维护（共享元素 flight 的 key 也天然一致）。

            val topBarBlock: @Composable () -> Unit = {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .graphicsLayer { alpha = flight?.softAlpha() ?: 1f }
                            .padding(
                                start = S.PageMargin.dp,
                                end = S.PageMargin.dp,
                                // 6dp：顶栏与下方舞台之间的光学缝，属 0–6dp 微缝豁免档（不参与 S 刻度）
                                top = if (landscape) 0.dp else 6.dp,
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        GlassIconButton(
                            onClick = {
                                val f = flight
                                if (f != null) {
                                    // 收起回来源页（playerOrigin），而不是硬编码曲库
                                    f.beginExit(state.currentSong) { state.closePlayer() }
                                } else {
                                    state.closePlayer()
                                }
                            },
                            modifier = Modifier.semantics { contentDescription = "收起播放页" },
                        ) {
                            Icon(GlassIcons.Down, null, Modifier.size(20.dp), tint = LocalGlassColors.current.textPrimary)
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                            // A1 token 化：顶栏是**次要信息**，字重必须弱于下方歌名（Heading/SemiBold 16）。
                            // 旧值是 13sp + SemiBold——字号比歌名小但字重同级，在玻璃上两者争视觉焦点；
                            // BodyMedium（13 Medium）同时守住字号与字重的从属关系。
                            Text("正在播放", color = LocalGlassColors.current.textPrimary, style = GlassText.BodyMedium,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            // 「来自…」是来源注脚：Micro（11/14）与播放页时间戳同档，不参与信息层级竞争
                            Text("来自「${state.queueSource ?: "本地音乐"}」", color = LocalGlassColors.current.textSecondary, style = GlassText.Micro)
                        }
                        GlassIconButton(
                            onClick = {
                                // 播放选项面板挂根层宿主（照抄 modal.show 模式；重复打开即整组重建，状态无残留）
                                modal.show {
                                    PlaybackOptionsSheet(
                                        visible = modal.visible,
                                        onDismiss = modal::requestDismiss,
                                        state = state,
                                    )
                                }
                            },
                            modifier = Modifier.semantics { contentDescription = "更多播放选项" },
                        ) {
                            Icon(GlassIcons.More, null, Modifier.size(20.dp), tint = LocalGlassColors.current.textPrimary)
                        }
                    }
            }


                    // ── 统一舞台：modeT(0=封面端 1=歌词端) 插值，封面中心+尺寸同帧直线过渡 ──
                    val modeT by updateTransition(showLyrics, label = "playerMode").animateFloat(
                        transitionSpec = { GlassMotion.tweenLyric() },
                        label = "modeT",
                    ) { if (it) 1f else 0f }

            val stageBlock: @Composable () -> Unit = {
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
                        //
                        // 下面三个是**几何参数不是间距**，保持裸值（S 刻度管的是「元素之间隔多远」，
                        // 不管「元素自身多大、锚在哪」）：64 = 歌词端小窗边长；22/14 = 小窗相对舞台
                        // 左上角的偏移——G1 保护带的 78dp 底边就是 14+64 推出来的，改这三个数要
                        // 同步改 LyricRail 的 topGuardPx，否则歌词会从封面底下穿出来。
                        val smallSize = with(density) { 64.dp.toPx() }
                        val smallLeft = with(density) { 22.dp.toPx() }
                        val smallTop = with(density) { 14.dp.toPx() }
                        // P2-⑤：封面尺寸**一律由舞台可用空间推导**，不设任何超过父约束的下限。
                        //
                        // 血的教训（横屏真机实测）：曾用 `coerceAtLeast(96dp)` 想把封面撑大，
                        // 但横屏单列时舞台只有 ~76dp 高——Modifier.size 会被父约束 coerce，
                        // 于是 96dp 的边长被压成「宽 96 / 高 76」的**椭圆**，且 offset 后
                        // 叠到顶栏标题上。封面尺寸必须 ≤ 舞台高，否则非方即溢。
                        // 横屏真正变大靠的是 §布局 的双栏（舞台拿到整列高度），不是改这个下限。
                        //
                        // ## C2（本轮）：旧 280dp 硬上限取消，改「宽度导向 + 边缘留白」推导
                        //
                        // 旧式 `min(280dp, stageW, stageH*0.92)` 让 411dp 宽机型只用到 68% 的
                        // 可用宽度——封面被一个与设备无关的常数锁死，屏幕越大留白越多。
                        // 新式分两步：先按宽度扣掉两侧留白得到「宽度档」，再按舞台高扣掉纯音乐
                        // 预留与 8% 呼吸得到「高度档」，取小。两个档次都**由舞台实测尺寸推导**，
                        // 不含任何常量上限，所以屏幕变宽/变高时封面跟着长。
                        //
                        // **为什么仍然是正圆角方块（不会重现椭圆）**：`Modifier.size(side)` 被父
                        // 约束 coerce 才会变成椭圆，而这里 side ≤ stageW − 2×留白 < stageW 且
                        // side ≤ 0.92×(stageH − 预留) < stageH，两条边都**严格小于**父约束——
                        // coerce 无从发生。这正是「封面边长 ≤ min(stageW, stageH)」这条硬约束的
                        // 代码表达（任务要求保留，不能破）。
                        //
                        // 边缘留白取 S.Gap20（20dp）：视觉审计 C2 建议 24–32dp，但在 411dp 宽机型上
                        // 24 会把封面压回 363dp 以下；20 是「取整到 S 刻度后的下限」——封面与屏幕
                        // 边缘的呼吸感仍在，同时与歌名组的左边距**同值**，于是封面左缘和歌名左缘
                        // 在宽度受限的机型上天然对齐（见 titleBlock）。
                        //
                        // ## 真机反馈修正（2026-11-08 装机后）：上一版封面仍然太大
                        //
                        // 上一版留白 20dp + 高度档是唯一约束，411dp 屏上算出来 371dp ——
                        // 占屏宽 90%，把歌名组和玻璃控制台一起压到屏幕最下面，读起来是
                        // 「一张图 + 一条尾巴」而不是「一张唱片 + 一套控制台」。
                        // 封面**不该吃掉整个舞台**：它需要给上方的顶栏、下方的歌名留出可呼吸的空
                        // 白，否则「内容优先」会退化成「封面优先」。
                        //
                        // 修正为**三档取小**，并给宽度档加一个 0.82 的比例上限：
                        // 1. 宽度档 = stageW × 0.82（不是 stageW − 留白：留白随屏幕走，
                        //    比例才是跨机型稳定的构图关系。0.82 在 411dp 屏上 ≈ 337dp，
                        //    两侧各余 ~37dp，与歌名的 20dp 左边距形成「外向留白 > 内向留白」的
                        //    经典构图比例）；
                        // 2. 高度档 = stageH − 纯音乐预留 − stageH × 0.16（呼吸从 8% 提到 16%：
                        //    8% 在 905dp 高的屏上只有 72dp，扣掉纯音乐预留后几乎不留白）；
                        // 3. 绝对上限 360dp——**这是本仓唯一允许的常量上限**，理由见下。
                        //
                        // 为什么要重新引入一个常量上限（上一轮刚取消掉 280dp）：纯比例推导在
                        // 平板/折叠屏上会让封面长到 600dp+，而唱片封面是**有视觉合理尺寸**的
                        // 内容——超过约 360dp 后它不再「随屏幕变大而更清晰」，只是把同一张
                        // 位图放大插值。所以比例负责**跨机型比例感**，常量负责**质量上限**，
                        // 两者取小才是完整的表达式。360 这个值来自：411dp 屏 × 0.82 ≈ 337 < 360
                        // （窄机仍由比例主导），平板才触顶。
                        val coverEdgePx = with(density) { S.Gap20.dp.toPx() }
                        // 纯音乐空态文案（封面下方 20dp 起、Micro 行高 14 + 折行余量 ≈ 18）预留 38dp。
                        // 这个数**必须从封面可用高度里扣**：不扣的话，高度受限的机型上文案会被
                        // 推出舞台、压进歌名组（文案是 offset 定位的，不受舞台高度裁剪）。
                        val pureMusicReservePx = if (pureMusic) with(density) { 38.dp.toPx() } else 0f
                        val coverMaxPx = with(density) { 360.dp.toPx() }
                        val availH = (stageH - pureMusicReservePx - stageH * 0.16f).coerceAtLeast(0f)
                        val bigSize = minOf(
                            // 宽度档：比例为主，但要保证不小于「屏宽 − 2×边距」，否则用户手动
                            // 用超窄分屏时封面会被压成比边距还小的碎片
                            minOf(stageW * 0.82f, stageW - coverEdgePx * 2f).coerceAtLeast(0f),
                            availH,
                            coverMaxPx,
                        ).coerceAtLeast(0f)
                        // 封面中心：扣掉预留后那段区域的中心（纯音乐时整体上移半个预留高）
                        val bigCenterY = (stageH - pureMusicReservePx) / 2f
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
                        // C4：圆角两端改读 R 刻度（R.Player28 大封面端 / R.Card18 歌词小窗端）。
                        // 数值与旧裸值 28/18 完全相同——token 化的意义不在改观感，而是让
                        // 「播放页封面两端圆角 = 两个既有刻度」这件事写进代码；日后调刻度时
                        // 只需改 R，封面不会成为全仓唯一还在用裸 28/18 的角落。
                        val coverShape = RoundedCornerShape(lerp(R.Player28.dp, R.Card18.dp, modeT))

                        // 歌词 rail：常驻组合（P3-15——旧写法 modeT>0 的组合期读取让模式切换
                        // 500ms 内整个舞台每帧重组）；淡入淡出交给下方 graphicsLayer 绘制期读。
                        // onSelect 门控 showLyrics：封面模式下 rail alpha=0 仍驻组合，不可见行不得响应点击
                        if (lyrics != null) {
                            LyricRail(
                                lines = lyrics,
                                current = lyricIndex,
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
                                // A1：Body（13/18）与旧裸值 13sp 同档，但行高由 token 给定——
                                // 舞台空态文案是单句、可折行（窄屏/横屏），裸 13sp 走的是
                                // Material 默认行高，与本仓 1.38 的中文行高规范不一致
                                color = LocalGlassColors.current.textSecondary, style = GlassText.Body,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .offset {
                                        IntOffset(0, (coverRect.bottom + with(density) { 20.dp.toPx() }).roundToInt())
                                    }
                                    .graphicsLayer { alpha = (1f - modeT) * (flight?.softAlpha() ?: 1f) },
                            )
                        }
                    }
            }


            // ── 共享/非共享的归属（歌名组出卡后重新分组，这条注释必须跟着改）──
            //  - **共享真身**（有 flight key，飞行期由覆盖层替身接管，收尾交叉淡入）：
            //    玻璃控制台根层 PlayerGlass、播放键 PlayerPlay、下一首 PlayerNext
            //    ——它们仍在卡内；歌名 PlayerTitle / 副标题 PlayerArtist 已随 [titleBlock]
            //    出卡，但它们**依然是共享真身**（key 不变、语义不变），只是换了父节点。
            //  - **非共享**（原地 softAlpha 淡入淡出）：收藏 / 队列 / 进度 / 时间 / 上一首 /
            //    播放模式钮。它们没有对应替身，飞行期只让自己消失/出现。
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
            // 共享子元素（歌名/歌者/播放/下一首）：退出期根层已按 12% 窗渐出，子元素若再乘
            // 同一系数会平方衰减（中点 0.25），交叉淡化亮度凹陷加深——退出期恒 1 交给根层
            val sharedInnerAlpha = { (flight?.playerSharedInnerAlpha() ?: 1f) * sheetAlpha }
            val softAlpha = { (flight?.softAlpha() ?: 1f) * sheetAlpha }

            // ── 播放模式标签（1.2s 后消失）──
            //
            // 状态放在**屏幕级**而不是按钮内：标签要渲染在底部提示行（见下方 hint 的选型注释），
            // 那是按钮的兄弟节点、隔着整个 GlassSurface。按钮只负责「我切到哪一档了」这一句
            // 事件（onModeChanged 回调），停留计时在屏幕级做。
            //
            // 为什么用 `LaunchedEffect(modeLabel)` 而不是按钮内自增 token：
            // 文案本身是 key —— 同一档连点两次（理论上不会发生，四态环每次都变）不会重启计时，
            // 而不同档之间必然重启。这比维护一个额外计数器少一份状态、也少一类「计数与文案
            // 不同步」的 bug。
            var modeLabel by remember { mutableStateOf<String?>(null) }
            LaunchedEffect(modeLabel) {
                if (modeLabel == null) return@LaunchedEffect
                // 1200ms 是**阅读时长**（四个字的词组 + 一次视线下移），不是动效时长，
                // 所以刻意不乘 GlassMotion.scale：克制档下若也跟着缩，用户会看不清
                // 自己刚按出了什么模式。
                delay(1_200)
                modeLabel = null
            }
            // 2.2-D：拖动进度条时的预览比例（null = 未拖动，显示真实进度）。
            //
            // 为什么用「持有 State 对象 + 传 provider lambda」而不是直接读 Float：
            // 拖动中这个值每帧变，若在 consoleBlock 作用域读它，整个控制台（模式行 /
            // 播放键 / 队列入口）每帧重组——正是本仓明令禁止的模式（见 ElapsedTimeText
            // 的注释：进度必须收缩到最小重组域）。这里只把**引用**透传下去，
            // 真正的读取发生在 ElapsedTimeText 内部，重组范围 = 一张文本。
            //
            // 这个接线本来就该有：ProgressTrack 早已支持 onPreview（Chrome.kt 的 P3-64
            // 专为此加），文档写明「供『已播时长』跟随手指变化」，但全仓没有任何调用点
            // 传过它 —— 于是拖动时轨道跟手、时间数字一动不动，松手才跳。
            val previewFraction = remember { mutableStateOf<Float?>(null) }

            // ── 歌名组（**卡外裸内容**：无容器、无描边、无底色）──
            //
            // 它和舞台、控制台是**平级**的三个抽屉：舞台（封面/歌词）→ 歌名组 → 玻璃控制台。
            // 歌名组不吃玻璃、不吃描边，于是它与封面共享同一片背景，两者构成一个视觉单元；
            // 而它到控制台之间隔着一整块玻璃的边界，层级差由**材质**表达而不是字号。
            //
            // ## 为什么标题用 GlassText.Title（20 Bold 26）而不是 Heading（16 SemiBold 22）
            //
            // 视觉审计 C2 的原话是「歌名 16sp 是控制台的『附件』而非页面主角」。出卡只解决
            // 「是不是附件」的一半问题：歌名与卡分离了，但如果仍是 16sp，它在整屏的最高层级
            // 还是低于 20sp 的二级页标题（详情页 / 歌单页的页名），于是「播放页的主角」比
            // 「歌单页的标题」还弱——这违背 C2 的初衷。GlassType 阶梯里 20sp 只有一档
            // （Title = 二级页大标题），它正是「这一页的名字」的语义档；播放页的主角就该是
            // 这一档。加粗（Title 是 Bold 而非 Heading 的 SemiBold）另有一层原因：裸文字
            // 失去了玻璃卡提供的边界，字重是它唯一还能拿到的「前景性」补偿。
            //
            // ## 为什么收藏/队列与歌名同排（而不是留在控制台里或控制台上方右对齐）
            //
            // 这两个动作属于**这首歌**，不属于播放控制（播放/上一首/下一首属于「播放」这个
            // 行为）。它们原本与歌名同处卡内一行，正是按「属于这首歌」归的组；歌名移出卡时
            // 若把它们留下，会出现「歌名在卡外、收藏在卡内」这种归属撕裂——用户会找不到
            // 收藏钮（它在视觉上隶属于控制台那组了）。
            //
            // 与「控制台上方右对齐」方案相比，同排的额外好处是**省一整行高度**：竖屏总共
            // 只有 ~150dp 的可支配高度（见下），单独一行图标钮要吃掉 40dp，等于封面少 40dp。
            // 同排则完全免费：歌名两行（26+16）本就比两个 33dp 圆钮高，这一行的高度由文字决定。
            val titleBlock: @Composable () -> Unit = {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(
                            start = S.PageMargin.dp,
                            end = S.PageMargin.dp,
                            // 上 12dp = 封面与歌名之间的呼吸（小于它到控制台的整个玻璃厚度）；
                            // 下 12dp = 歌名组与卡片之间的留白。两端同值不是偷懒：这里要的是
                            // 「歌名组居中于封面与卡片之间」的对称，不是「更靠近卡片」的偏心。
                            top = S.Gap12.dp,
                            bottom = S.Gap12.dp,
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(
                        Modifier
                            .weight(1f)
                            // 整组共用一个 alpha 门控，且取的是**共享真身**曲线 sharedAlpha
                            // （playerSharedAlpha × sheetAlpha）——这条不能省，原因见文件头
                            // KDoc 第 2 点：出卡后它不再是玻璃卡的子节点，卡的渐隐不再顺带
                            // 带走它，必须自己挂同一根曲线，否则收起飞行时歌名会比别的东西
                            // 晚消失近 300ms。
                            .graphicsLayer { alpha = sharedAlpha() },
                    ) {
                        // trackFlight 挂在**文本节点本身**（不是一个包装 Box）：飞行替身的
                        // rect 直接对应文字的排版盒，虚线才有意义。加包装会多出零高/全高
                        // 两种不同量法，端点一律偏。
                        Text(song.title, color = LocalGlassColors.current.textPrimary, style = GlassText.Title,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.trackFlight(FlightKeys.PlayerTitle))
                        // 副标题仍是 Caption（12/16）：它与列表副标题同档，是「歌名之下的
                        // 一行注脚」。不出卡时它是卡内的第二行；出卡后它依然是第二行——
                        // 出卡改变的是**容器归属**，不是文字之间的相对层级。
                        Text(song.subtitle, color = LocalGlassColors.current.textSecondary, style = GlassText.Caption,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.trackFlight(FlightKeys.PlayerArtist))
                    }
                    Spacer(Modifier.width(S.Gap12.dp))
                    // 收藏/队列是**非共享**元素（没有 flight key）：飞行期按 softAlpha 原地
                    // 淡入淡出，与它们的旧行为一致——这条不能因为搬进歌名组就跟着换成
                    // sharedAlpha，否则飞行中途会有一段时间「收藏钮凭空消失又出现」。
                    ConsoleIcon(
                        onClick = { state.toggleFavorite(song.id) },
                        contentDescription = if (state.isFavorite(song.id)) "取消收藏" else "收藏",
                        stateDescription = if (state.isFavorite(song.id)) "已收藏" else "未收藏",
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
                        contentDescription = "打开播放队列",
                        modifier = Modifier.graphicsLayer { alpha = softAlpha() },
                    ) {
                        Icon(GlassIcons.Queue, null, Modifier.size(21.dp),
                            tint = LocalGlassColors.current.textSecondary)
                    }
                }
            }

            val consoleBlock: @Composable () -> Unit = {
            GlassSurface(
                modifier = Modifier
                    .fillMaxWidth()
                    // P2-⑤：横屏收紧外边距与底部留白，控制台少占的高度全部给舞台
                    .padding(
                        start = S.Gap16.dp,
                        end = S.Gap16.dp,
                        // 竖屏 14dp → 12dp（S 刻度就近归位）：控制台是页面最后一块内容，
                        // 它下方只剩提示行，两者之间的视觉关系本来就是「一组」，不该比
                        // 卡内的行间距更松。横屏 6dp 落在微缝豁免档，保持裸值。
                        bottom = if (landscape) 6.dp else S.Gap12.dp,
                    )
                    .trackFlight(FlightKeys.PlayerGlass)
                    .graphicsLayer { alpha = sharedAlpha() },
                // 液态档（v11，第四档玻璃）：这块控制台是「液态层」的第一处落点。
                //
                // 为什么是它：它背后是封面位图 + 舞台（无滚动列表文字穿越），是本 App
                // 唯一一块「玻璃后面有照片」的常驻面板——液态档的方向性斜面与 specular
                // 只有在背后有内容时才读得出立体感，压在纯色极光上会像一圈没必要的亮边。
                level = GlassLevel.Liquid,
                shape = RoundedCornerShape(R.Player28.dp),
                // 模糊升到 Hi（26dp）：液态档的差异化在装饰层，模糊强度与 Hi 同级即可。
                blur = GlassLevel.Hi,
                // 光影驱动：绑**播放进度**（已有连续值，零新增基础设施）。
                // 传 lambda 而不是解构后的 Float —— 它在绘制期逐帧读取，不触发重组；
                // 传值会让整块控制台在播放中每帧重组（本仓反复点名的错误写法）。
                liquidMotion = { state.progressFraction },
            ) {
                Column(
                    Modifier.padding(
                        // 卡内边距：18 → 16（S 刻度）。出卡后卡里只剩进度/时间/控制键，
                        // 全是「满宽元素」，18 的偏心内距失去了存在的理由（18 原本是为了
                        // 让歌名文字与卡缘对齐得更松）。16 同时是 S 刻度上的值。
                        start = S.Gap16.dp,
                        end = S.Gap16.dp,
                        // 上下边距重算：旧值 14/16 是按「三行内容（歌名+进度+控制键）」配的
                        // 呼吸。现在只剩「进度/时间/控制键」，上下各 12 即可——卡的高度从
                        // 旧 202dp 收到 152dp（推导见文件头），省下的 50dp 全部归封面。
                        top = if (landscape) S.Gap8.dp else S.Gap12.dp,
                        bottom = if (landscape) S.Gap8.dp else S.Gap12.dp,
                    ),
                ) {
                    // 进度：轨道 + 时间（歌名组已出卡，这里从第一行开始就是进度）
                    Box(Modifier.graphicsLayer { alpha = softAlpha() }) {
                        // 传取值函数：进度 10Hz 变，以 Float 传入会让整个控制台（含歌词区外的
                        // 音量/模式行）每 tick 重组。绘制期读才是这套 UI 的既有做法。
                        ProgressTrack(
                            progressProvider = { state.progressFraction },
                            onSeek = state::seekToFraction,
                            // 2.2-D：拖动预览回传（null = 松手/取消）。只写进预览 State，
                            // 不在这里读 value，避免把逐帧读取带进控制台组合作用域
                            onPreview = { previewFraction.value = it },
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
                        ElapsedTimeText(state, previewFraction)
                        TotalTimeText(state)
                    }

                    // 控制键：播放模式（四态合一）/ 上一首 / 播放 / 下一首
                    //
                    // ## 为什么随机与循环必须合成一枚按钮（用户指定，QQ 音乐同款）
                    //
                    // 改前是两个独立图标钮：随机（开/关二态）+ 循环（三态）。这在**语义上**
                    // 是两个正交开关，但在**用户心智上**只有一个问题——"这首歌放完怎么办"。
                    // 两个控件同时亮着时，用户要先把两个状态在心里组合一次才知道会发生什么：
                    // 随机开着 + 单曲循环 = ? （答案：单曲循环赢，随机不生效——这个优先级
                    // 规则在任何 UI 上都没有被表达过）。合成一枚四态按钮后，「当前模式」成为
                    // 一个可命名的**单一状态**，并且用一次点击就能遍历，不再有隐藏的优先级。
                    //
                    // 代价是牺牲独立性：用户不能表达「随机 + 单曲循环」。这是**有意**的取舍，
                    // 依据是「随机播放单曲」本身就是无意义组合（随机一首再无限重复它）。
                    //
                    // ## 四态与持久化字段
                    // `shuffleOn` / `repeatMode` 两个字段名与语义**一字未改**（旧快照、旧入口、
                    // 引擎下发全部照旧），映射与派生逻辑收敛在 AppState.cyclePlayMode() 的
                    // KDoc 里（一张表，读它即可）。
                    // ## 控制键行的排布：为什么不用 SpaceBetween（真机装机后修正）
                    //
                    // 上一版是 `Row(SpaceBetween)` 塞 5 个子项（模式钮 / 上一首 / 播放 / 下一首 /
                    // 32dp 占位）。实测（411dp 屏，截图逐像素测量）：
                    //   模式钮图标中心 13.8% ｜ 上一首 29% ｜ 播放 50% ｜ 下一首 71% ｜ 右侧空
                    // 左右**不对称**——左边有个真实控件占位、右边是个空 Spacer，两者宽度不同
                    // （模式钮 32dp vs Spacer 32dp 看似相同，但 SpaceBetween 均分的是
                    // 「剩余空间」，5 个宽度不等的子项被均分后，播放键与两侧的**间距**并不相等）。
                    // 肉眼读作「播放键偏右、左边挤了一个模式钮」，正是用户反馈的「排版有点错误」。
                    //
                    // 修正：**两侧等权重的三段式** —— 左段 weight(1f)、中段固定宽度（三枚控制键
                    // 居中）、右段 weight(1f)。这样播放键**恒在屏幕正中**，与下方进度条严格对齐；
                    // 上一首/下一首以播放键为中心对称分布；模式钮落在左段右缘。
                    // 右段刻意留空（不放任何控件）：它不是「待填的坑」，而是让左段的模式钮
                    // 有对手的**配重**——不对称的排布在视觉上永远读作「错位」，除非两侧都有内容。
                    Row(
                        Modifier.fillMaxWidth().padding(top = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // 左段：模式钮靠右对齐（贴近中段，不贴屏幕边缘——贴边会让它读作
                        // 「页面的边距元素」而不是「控制行的一员」）
                        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                            PlayModeButton(
                                state = state,
                                // 按钮只上报「切到哪一档了」，停留计时的 State 在屏幕级（见上方 modeLabel）
                                onModeChanged = { modeLabel = it },
                                modifier = Modifier.graphicsLayer { alpha = softAlpha() },
                            )
                        }
                        // 中段：三枚控制键固定间距。用 spacedBy 而不是 SpaceBetween——
                        // 中段内部宽度由子项决定，播放键两侧的缝隙必须相等，这只有显式间距能保证。
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(S.Gap16.dp),
                        ) {
                            ConsoleIcon(
                                onClick = { state.prev() },
                                contentDescription = "上一首",
                                modifier = Modifier.graphicsLayer { alpha = softAlpha() },
                            ) {
                                Icon(GlassIcons.Prev, null, Modifier.size(28.dp),
                                    tint = LocalGlassColors.current.textSecondary)
                            }
                            PlayPauseButton(
                                playing = state.playing,
                                onClick = state::togglePlayPause,
                                modifier = Modifier
                                    .trackFlight(FlightKeys.PlayerPlay)
                                    .graphicsLayer { alpha = sharedInnerAlpha() },
                            )
                            ConsoleIcon(
                                onClick = { state.next() },
                                contentDescription = "下一首",
                                modifier = Modifier
                                    .trackFlight(FlightKeys.PlayerNext)
                                    .graphicsLayer { alpha = sharedInnerAlpha() },
                            ) {
                                Icon(GlassIcons.Next, null, Modifier.size(28.dp),
                                    tint = LocalGlassColors.current.textSecondary)
                            }
                        }
                        // 右段：**等权重留白**。见上方注释——它保证播放键绝对居中。
                        // 刻意不塞控件：收藏/队列已随歌名出卡到卡外，这里再放一枚会让
                        // 控制行变成「五个功能挤一行」，重心重新偏移。
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
            }


            // 底部提示行（按当前视觉模式而非持久化偏好）；无歌词时不再教「滑动切换」（P3-65）——
            // 纯音乐没有歌词端可切，提示与舞台空态文案保持同一句话
            //   ↑ 这条 P3-65 的「保持同一句话」已被下面的 E4 废止：同一句话重复两遍本身就是
            //     问题（双显）。现在「无歌词」只有舞台那一份，提示行留空但保留行高。
            //
            // E4（视觉审计）：同一句「纯音乐，请欣赏」原先**双显**——舞台封面下方已经有一句，
            // 底部提示行再复述一遍，一屏两次完全相同的话，读起来像卡住的占位文案。
            // 改后：无歌词时提示行**不出字**，这句话的唯一归属是舞台上那句（它跟随封面端淡入淡出）。
            // 槽位仍按原高度保留（见下方 Text 的 `hint ?: "\u00A0"`），否则控制台会随之下移一整行。
            //
            // 顺带修掉一处失真：空队列的占位曲（mediaUri == null）现在也走这里不出字。
            // 它的舞台上压根没有这句（stageBlock 的 pureMusic 要求 mediaUri != null），
            // 旧实现却在提示行对它宣称「纯音乐，请欣赏」——占位曲不是纯音乐，是**没有歌**。
            //
            // ## 播放模式标签复用本行（B 任务）
            //
            // 四态按钮切换后需要一个「一眼可读」的模式名（「列表循环」四个字，图标承载不了）。
            // 三条候选路线里只有本行可行：
            // - 按钮上方浮出 → 被玻璃卡的 `clip(RoundedCornerShape(R.Player28))` 裁掉
            //   （按钮是卡内最底一行，向上 20dp 正好压进时间行）；
            // - 按钮旁 overlay → 会挤压控制行（该行已改为三段等权重布局，多一个兄弟节点
            //   就破坏播放键的绝对居中；而这条提示行的**高度本来就是恒定的**，加一段文字零成本）；
            // - 新建全局浮层 → 任务明令禁止。
            // 本行的既有契约正好是「恒定一行、恒定高度、可换文案」——它本来就是为
            // 「不出字也要占位」设计的，模式标签只是又一个消费者。
            //
            // 优先级：模式标签 > 操作提示。理由：模式标签是**对刚才那次点击的回复**
            // （1.2s 后消失），操作提示是**常驻教学**；瞬态反馈必须压过常驻文案，
            // 否则用户点了按钮却在提示行读到「左右滑动切换封面」，反馈等于丢失。
            val hint: String? = when {
                modeLabel != null -> modeLabel
                lyrics == null -> null
                showLyrics -> "点击歌词行可跳转 · 左右滑动切换封面 / 歌词"
                else -> "左右滑动切换封面 / 歌词"
            }
            val hintBlock: @Composable () -> Unit = {
            // 空态仍然渲染**同一个 Text**，只把文案换成一个不可见字符（而不是直接不渲染）：
            //
            // 不渲染的话提示行会矮一整行，两种方向下都会移动控制台——只是量不同：
            // - 竖屏：`Column { Box(weight 1f){顶栏+舞台} / 控制台 / 提示行 }`。控制台与提示行是
            //   固定高度子项，Box 吃剩余。提示行矮 26dp → **控制台整体下移 26dp**，同时舞台变高 26dp；
            //   而封面边长是 `min(280dp, stageW, stageH*0.92)`、中心是 stageH/2 —— 封面跟着变大下移。
            //   于是从「有歌词」切到「纯音乐」，封面与控制台两处同时跳，切歌时这一帧很显眼。
            // - 横屏：右侧 `Column(width=380, fillMaxHeight, Arrangement.Center)` 里是
            //   「控制台+提示行」这一组、整组垂直居中。少一行则整组重新居中 → **控制台下移半行**。
            // 提示行是恒定的装饰性留白，不该随内容有无去改页面几何。所以保持同一个 Text 节点、
            // 同一个 style，只把内容换成**不换行空格**（U+00A0）：行盒高度与出字时逐像素相同
            // （连系统字号放大时的 sp 缩放也一致），且视觉上不可见。
            // 为什么不用普通空格 / 空串：普通空格属于可折叠空白，排版器在行尾会把它当零宽处理，
            // 空串更可能直接量成 0 高——占位就失效了。U+00A0 有实体字宽，必然出一行。
            // 为什么不用 Spacer(height = …)：行盒高度受字体 metrics / includeFontPadding 影响，
            // 手写 dp 只能逼近，反而引入 1–2dp 的常驻错位。
            // 已知残留：极窄屏上长提示可能折成两行（比占位高一行）。这只影响「有歌词」那一侧，
            // 且折行时本来就不可能与占位等高——属可接受误差，不要为它改成高度测量。
            Text(
                hint ?: "\u00A0",
                // A1：Micro（11/14）——与旧裸 11sp 同字号，补上 14 行高（裸 11sp 走默认行高，
                // 与上方控制台的行高节奏脱节）
                color = LocalGlassColors.current.textTertiary, style = GlassText.Micro,
                modifier = Modifier
                    .fillMaxWidth()
                    // P2-⑤：横屏下提示行紧贴控制台，避免它在 720dp 高度里白占一行
                    .padding(bottom = if (landscape) 4.dp else 12.dp)
                    .graphicsLayer { alpha = softAlpha() },
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            }

            if (landscape) {
                topBarBlock()
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .hazeSource(playerHaze),
                    ) { stageBlock() }
                    Column(
                        Modifier
                            .width(380.dp)
                            .fillMaxHeight(),
                        verticalArrangement = Arrangement.Center,
                    ) {
                        // 横屏右侧栏的顺序：歌名组 → 控制台 → 提示行。
                        // **歌名组必须在横屏也保留**：它承载 PlayerTitle/PlayerArtist 两个飞行
                        // 真身键，横屏下若直接不渲染，横屏进入播放页时 `awaitRects(playerKeys)`
                        // 会等不到这两个键 → 600ms 超时 → 整个飞行被中止（退化成硬切）。
                        // 也就是说「横屏不显示歌名」这条看似合理的省空间方案，实际会打断飞行。
                        titleBlock()
                        consoleBlock()
                        hintBlock()
                    }
                }
            } else {
                Box(Modifier.weight(1f).hazeSource(playerHaze)) {
                    Column(Modifier.fillMaxSize()) {
                        topBarBlock()
                        stageBlock()
                    }
                }
                // 竖屏顺序：舞台（weight 1f 吃剩余）→ 歌名组（固定高）→ 控制台（固定高）→ 提示行。
                // 封面放大靠的就是「歌名组与控制台都是固定高、舞台吃剩下的一切」这条结构：
                // 控制台从 202dp 收到 152dp、歌名组只占约 62dp（26+16 文字 + 上下 12 留白），
                // 省下的高度**全部**自动流进舞台，无需再动任何尺寸公式。
                titleBlock()
                consoleBlock()
                hintBlock()
            }
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
    // 上面那条坐标是**在哪一套舞台几何下量出来的**（宽高打包成一个 Long）。
    // 只有量它的几何 == 当前舞台几何时才算有效。
    //
    // 这是 F4（真机横屏歌词空白）的根因判据。manifest 的 configChanges 含
    // orientation|screenSize|screenLayout|smallestScreenSize，所以旋转**既不重建 Activity
    // 也不重建组合** —— lineCenters 原样留着竖屏舞台的几何。而舞台尺寸一变就会重启定位
    // effect，它第一行的 `snapshotFlow { lineCenters[current] }.first()` 对已存在的值**立即发射**
    // （快照流不会等新布局），于是拿竖屏坐标去算横屏偏移 → rail 被推出舞台 →
    // 每行 positionalFade 归零 → 左舞台只剩封面。
    //
    // **必须同时盖宽度**：lineCenters 存的是行**中心 y**，而 y 由行高与**折行**共同决定，
    // 折行只取决于宽度。只盖高度的话，分屏拖分隔条 / 折叠屏展开 / 自由窗口 resize 这类
    // 「只改宽不改高」的路径会让旧戳仍然「有效」→ 同一个 bug 换个入口原样复现。
    //
    // 为什么记几何戳而不是「尺寸一变就清空」：清空与定位是两个 LaunchedEffect，靠「清得早」
    // 来保证正确性等于依赖执行顺序；而带几何戳的坐标**自己就过期**，无论谁先跑都不会被误用。
    val lineCenterStageKey = remember { mutableStateMapOf<Int, Long>() }
    // P2-32：偏移改 Animatable 手控。旧 animateFloatAsState 初值恒 0——每次进入播放页/切歌
    // 都会从 offset=0「滑」到居中位；Animatable 允许首次定位 snapTo 瞬时落位，行进切换才弹性动画
    val railOffset = remember { Animatable(0f) }
    // 当前歌词是否已完成过首次居中：换歌词（实例身份变化）即重置，每首歌的第一次定位不走动画
    var hasPositioned by remember(lines) { mutableStateOf(false) }

    BoxWithConstraints(modifier.clipToBounds()) {
        val stageHeightPx = with(density) { maxHeight.toPx() }
        // 舞台几何戳（宽 + 高打包）：行坐标在哪套几何下量出来的唯一凭据。
        // 宽度**必须**进戳 —— 见 lineCenterStageKey 的注释（折行只由宽度决定）。
        // 高位塞宽度、低位塞高度的原始位模式，两者任一变化都会得到不同的 Long。
        val stageW = constraints.maxWidth
        val stageKey: Long = remember(stageHeightPx, stageW) {
            (stageW.toLong() shl 32) or (stageHeightPx.toRawBits().toLong() and 0xFFFF_FFFFL)
        }
        // G1（体验审计）：歌词端左上角悬浮 64dp 封面（底边 78dp）会盖住从它后方穿过的行
        // （真机 s7/s8/sc 实拍）——顶部保护带 90dp = 封面底边 78dp + 12dp 缓冲，
        // 滚入封面区间的行先于封面淡出，而非被硬裁
        val topGuardPx = with(density) { 90.dp.toPx() }
        // 切歌：旧行坐标必须**彻底作废**（行文本/行数/折行全不同）。
        //
        // 这里必须是**只以 lines 为 key** 的独立 effect —— 不能和「尺寸变化复位」合并：
        // 合并后（`LaunchedEffect(lines, stageHeightPx)`）切歌时若不 clear，定位 effect 会被
        // **上一首**残留的坐标立刻喂饱（阶段高度没变，高度戳判据拦不住）并置 hasPositioned=true，
        // 之后 lines/current/height 三个 key 都不再变 → rail 一直停在上一首的偏移上，
        // 直到下一句歌词到来才纠正（数秒级错位）。这正是竖屏「切歌瞬时归位」的回归。
        //
        // 清空在这里是安全的：切歌会让每一行都重新组合并重新 onGloballyPositioned，
        // 不存在「清空后长时间没有坐标」的窗口（那种情况才会让 positionalFade 归零黑屏）。
        LaunchedEffect(lines) {
            lineCenters.clear()
            lineCenterStageKey.clear()
        }
        // 尺寸变化（旋转 / 分屏 / 自由窗口）：只复位偏移与首次定位标记，**不清 lineCenters**。
        //
        // 不清是有原因的：逐行透明度由 `positionalFade(railOffset + (lineCenters[i] ?: 0f), …)`
        // 算，而 positionalFade 对 stageY <= 0f 直接返回 0。尺寸变化时行通常不会立刻重测，
        // 一旦清空就会让**所有行算成 0 透明 —— 整片歌词消失**（第一版修法就是这么把
        // 「概率性空白」变成「必现空白」的）。保留旧坐标最多一帧偏移不准，绝不会黑屏。
        //
        // 正确性由 stageKey（宽高戳）保证：定位 effect 只认「在当前舞台几何下量出来」的坐标。
        LaunchedEffect(stageKey) {
            railOffset.snapTo(0f)
            hasPositioned = false
        }
        // 当前行中心 → 对齐舞台中心（rail 与舞台同高，行坐标即舞台坐标，同 CSS 公式）。
        // 行坐标由 onGloballyPositioned 异步回填：用 snapshotFlow 等它就绪后取一次，
        // 而不是把 lineCenters[current] 挂进 key（旧写法每次回填都重启 effect，当前行漂移 P3-67）
        //
        // 只认**在当前舞台几何下量出来**的坐标：没有就等，绝不用旋转前那套几何去算。
        // 这是 F4 的正解——旧代码只判 null，于是旋转后被旧值喂饱、把整条 rail 推出可视区。
        LaunchedEffect(lines, current, stageKey) {
            val center = snapshotFlow {
                lineCenters[current]?.takeIf { lineCenterStageKey[current] == stageKey }
            }.filterNotNull().first()
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
                            positionalFade(railOffset.value + (lineCenters[index] ?: 0f), stageHeightPx, topGuardPx)
                        },
                        onClick = { onSelect(index) },
                        modifier = Modifier.onGloballyPositioned { coords ->
                            lineCenters[index] = coords.positionInParent().y + coords.size.height / 2f
                            // 同一次回调里盖几何戳：坐标与它成立时的舞台几何必须原子一致，
                            // 分两次写会让定位 effect 有机会读到「新坐标 + 旧戳」的半截组合
                            lineCenterStageKey[index] = stageKey
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
    // A2 歌词色 token 接线：邻行/远行的衰减值**不再在这里写死**，改读 GlassColors 的
    // lyricAdjacent / lyricFar 的实际 alpha。这两个 token 早就定义了，但全仓零引用——
    // 于是「歌词非当前行多亮」这条规则在代码里没有着落点，只能在播放页手写 0.55/0.34。
    // 接线后：浅色模式的 lyricAdjacent/lyricFar 是近黑系（见 GlassColors.light()），
    // 若日后把浅色档调到 0.48/0.28 这类更适合白底的值，歌词衰减会自动跟随，不需要回来改这里。
    //
    // 当前行（distance==0）仍恒为 1f：它是**完全前置**的行，不是「比邻行更亮一档」，
    // 用 token 表达反而会在浅色档被调低时让当前行也变灰。
    //
    // 性能：这里在**组合期**读 LocalGlassColors（animateFloatAsState 的 target 必须是组合期值）。
    // 不新增重组成本——本函数下方 `color = LocalGlassColors.current.textPrimary` 本来就组合期读它，
    // 玻璃色变化时这些行已经会重组；而真正高频的动画值（railOffset 位置衰减）仍在
    // positionalAlphaProvider 的绘制期读，没有被这条改动带进组合期。
    val glass = LocalGlassColors.current
    val p by animateFloatAsState(
        targetValue = if (distance == 0) 1f else 0f,
        animationSpec = GlassMotion.tweenLyric(),
        label = "lyricP",
    )
    val targetAlpha = when (distance) {
        0 -> 1f
        1 -> glass.lyricAdjacent.alpha
        else -> glass.lyricFar.alpha
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

/**
 * 逐行位置衰减：行中心在舞台顶部淡出带 / 底部 8% 内平滑淡出（smoothstep），永不拦腰切行。
 * 顶部淡出带 = max(10%, topGuardPx)，再夹到 ≤45% 舞台高：topGuard 覆盖歌词端左上角
 * 悬浮封面区间（见 LyricRail 内注释）；45% 上限防矮舞台（横屏单列）被保护带整个吞掉、
 * 恒居中的当前行反而变暗。
 */
private fun positionalFade(stageY: Float, stageH: Float, topGuardPx: Float = 0f): Float {
    if (stageH <= 0f) return 1f
    val topEnd = minOf(maxOf(stageH * 0.10f, topGuardPx), stageH * 0.45f)
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
            // A6 未落地（有意跳过，非遗漏）：pressScale 的 animationSpec 在 Glass.kt 内部写死
            // GlassMotion.tweenPress()，函数签名不接受 spec，因此**无法从这个调用点**把它换成
            // GlassSpring.gentle()。要做需要在 Glass.kt 给 pressScale 加一个可选的 spec 参数——
            // 而 Glass.kt 本轮是别人的写域，且它是全 App 的按压基元，签名一改六屏都要重读。
            // 正确的做法是下一轮由 Glass.kt 的 owner 统一加上 `spec: AnimationSpec<Float> =
            // GlassMotion.tweenPress()` 默认参数（默认值保持现状 → 零回归），之后各调用点再按
            // 「按钮类用 spring / 进度类用 tween」逐步切换。
            // 66dp 大按钮本身的 0.93 缩放幅度是对的，这条只差动效曲线。
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
            // 读屏语义：整个 66dp 圆是一个可点按钮。contentDescription 固定为「播放/暂停」这一对
            // 动作名的**当前动作**，stateDescription 单独表达「正在播放/已暂停」——
            // 二者分开是 TalkBack 的正确用法：前者答「点它会怎样」，后者答「现在是什么状态」。
            // 只把状态塞进 contentDescription 会让读屏每次念一长串且丢失 role。
            .semantics {
                this.role = Role.Button
                contentDescription = if (playing) "暂停" else "播放"
                stateDescription = if (playing) "正在播放" else "已暂停"
            }
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (playing) GlassIcons.Pause else GlassIcons.Play,
            // 语义已由外层 Box 的 semantics{} 完整承担（含 role/state）；
            // Icon 再挂一份会和父节点重复播报两遍，故此处 null 是**有意为之**，不是漏补
            contentDescription = null,
            Modifier.size(26.dp), tint = LocalGlassColors.current.textPrimary,
        )
    }
}

// ── 播放模式：四态合一按钮（用户指定，QQ 音乐同款）──────────────

/**
 * 播放模式四态。**这是持久化字段的联合视图，不是新状态**——
 * 每次组合都由 ([AppState.shuffleOn], [AppState.repeatMode]) 现算，
 * 不存在第三份真相（见 [AppState.cyclePlayMode] 的映射表）。
 */
private enum class PlayMode(
    val label: String,
    val icon: ImageVector,
) {
    Order("顺序播放", GlassIcons.PlayOrder),
    ListLoop("列表循环", GlassIcons.PlayListLoop),
    RepeatOne("单曲循环", GlassIcons.PlayRepeatOne),
    Shuffle("随机播放", GlassIcons.PlayShuffle),
    ;

    /** 顺序播放是「无声模式」（无高亮）；其余三态亮强调色。语义：只有非默认态才需要被注意 */
    val isAccent: Boolean get() = this != Order

    /**
     * 环形序的下一态——**与 [AppState.cyclePlayMode] 的四态环严格同构**。
     *
     * 这份重复看起来违反 DRY，但两边求的是不同的东西：AppState 算的是「下一对
     * (shuffleOn, repeatMode) 字段值」，这里算的是「下一条给用户读的标签」。
     * 硬要合并只能让 AppState 暴露一个 enum 出去（污染持久化层），或者让 UI 猜标签
     * ——两者都比这 5 行代码更糟。若将来四态环变化，**两处都要改**，这是唯一的代价。
     */
    fun next(): PlayMode = when (this) {
        Order -> ListLoop
        ListLoop -> RepeatOne
        RepeatOne -> Shuffle
        Shuffle -> Order
    }

    companion object {
        /**
         * 从持久化字段反查视觉态。
         *
         * `shuffleOn` 优先：随机开着时**忽略** repeatMode。这条容错是必需的——
         * 「随机 + Off」「随机 + One」这两种组合仍可能来自旧版本快照或
         * [AppState.playCollection] 的 shuffle 入口（它只改 shuffleOn）。
         * 若不忽略，用户会看到「按钮写着顺序播放、实际却在随机跳歌」。
         */
        fun of(shuffleOn: Boolean, repeat: RepeatMode): PlayMode = when {
            shuffleOn -> Shuffle
            repeat == RepeatMode.All -> ListLoop
            repeat == RepeatMode.One -> RepeatOne
            else -> Order
        }
    }
}

/**
 * 四态播放模式按钮：单枚图标 + 形变过渡 + 通过 hint 行播报的 1.2s 模式标签。
 *
 * ## 形变动效（用户明确要求「记得加上相应动效」，QQ 音乐同款观感）
 *
 * 一次点击要表达两件事：**「换成了什么」** 和 **「这一下生效了」**。所以过渡分两层：
 * 1. [AnimatedContent] 做图标**交叉形变**：旧图标淡出 + 缩到 0.72 + 逆时针转出 90°，
 *    新图标同时从 0.72 弹回 1.0 + 顺时针转正。两者用的是**同一条** [GlassMotion.tweenPress]
 *    曲线，于是「旧的转出去、新的转进来」是同一支舞，而不是两段各自播放的动画。
 * 2. 一圈**强调色细环脉冲**：从图标位置向外扩散并淡出，是「点击已被接收」的即时回执
 *    （按压缩放只有 0.88 且 180ms，快速连点时几乎读不出来）。
 *
 * ## 为什么这两层都不产生逐帧重组
 *
 * 旋转/缩放/alpha 全部写在 `graphicsLayer { }` 的 lambda 里，即**绘制期读**动画值；
 * `AnimatedContent` 的 transition 只在内容切换的那一瞬重组一次，`animateFloat(transition)`
 * 拿到的是 State，`.value` 只在绘制期被读。这与本仓既有纪律一致
 * （见 ProgressTrack 的「绘制期读」注释、sheetAlpha 的注释）。
 *
 * ## 时长与曲线
 *
 * 走 [GlassMotion.tweenPress] / [GlassMotion.tweenHover]（全 App 唯一缓动曲线），
 * 并**自动**被 `GlassMotion.scale`（设置页动效强度三档）乘过——
 * 不新造曲线、不绕过强度设置。标签停留 1.2s 是**阅读时长**不是动效时长，
 * 故意不参与强度缩放：克制档下若也跟着缩，用户会看不清自己按出了什么。
 *
 * ## 为什么标签不走「按钮上方浮出」的 overlay
 *
 * 试过，不可行：控制台的 [com.local.music.core.ui.components.GlassSurface] 用
 * `clip(shape)` 裁内容（圆角矩形），而按钮位于卡内**最底一行**——标签向上偏移 20dp
 * 正好压进上方的时间行（已播/总时长），向右/向下则直接被卡缘裁掉。
 * 三条路都失败，于是改用**既有 hint 行**承载（见 [PlayerHintRow]）：它是一整屏唯一的
 * 恒定装饰槽位，高度固定、不被裁剪、不参与任何测量竞争。
 */
@Composable
private fun PlayModeButton(
    state: AppState,
    onModeChanged: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val glass = LocalGlassColors.current
    val mode = PlayMode.of(state.shuffleOn, state.repeatMode)
    val interactionSource = remember { MutableInteractionSource() }

    // 环脉冲：每次切换从 0 走到 1。snapTo(0)+animateTo(1) 在同一协程里，
    // 连续快点时旧动画被取消、新的从 0 重来（这正确：每次点击都该有完整的一次扩散）。
    val ringPulse = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()

    // ── 语义：清空子树后**自己写全**，保证落到**同一个可点节点**上 ──
    //
    // 这是本轮实测踩出来的，三代写法都不对，记下来避免后人重犯：
    // ① `semantics{} then clickable{}` → 标签节点 clickable=false，旁边还有个空标签的
    //    可点节点（标签与点击性分裂到两个节点）；
    // ② `clickable{} then semantics{}`（ConsoleIcon 的写法）单独看没问题，但本按钮内层是
    //    `AnimatedContent`——它给进出两个图标各建了语义节点，于是外层被识别成**容器**，
    //    uiautomator 仍然把 clickable 归到父节点、标签留在子节点；
    // ③ `semantics(mergeDescendants = true)` 会被 AnimatedContent 的两个过渡子树干扰，
    //    合并结果仍不稳定。
    //
    // 最终解：`clearAndSetSemantics` —— 它**丢弃整个子树的语义**再由这里重建一个节点。
    // 图标本来就是 contentDescription=null 的纯装饰，AnimatedContent 的过渡节点也不是给
    // 读屏用的，全丢正是想要的。重建后 label / stateDescription / role 与 clickable
    // 落在同一节点，TalkBack 与 uiautomator 都读到一致结果（实测 clickable=true）。
    Box(
        modifier
            .pressScale(interactionSource, pressed = 0.88f)
            .clickable(interactionSource = interactionSource, indication = null) {
                state.cyclePlayMode()
                onModeChanged(mode.next().label)
                scope.launch {
                    ringPulse.snapTo(0f)
                    ringPulse.animateTo(1f, GlassMotion.tweenHover())
                }
            }
            .clearAndSetSemantics {
                this.role = Role.Button
                this.contentDescription = "播放模式"
                // stateDescription 承载四态中文：读屏用户靠它知道「现在是哪一档」，
                // contentDescription 只回答「这是什么控件」。两者分开是 TalkBack 的正确用法。
                this.stateDescription = mode.label
            },
        contentAlignment = Alignment.Center,
    ) {
        // 内层：纯布局 + 动画容器，不再挂任何语义（语义已由外层可点节点全权承担）
        Box(
            Modifier.padding(6.dp),
            contentAlignment = Alignment.Center,
        ) {
            AnimatedContent(
                targetState = mode,
                transitionSpec = {
                    // 进入端：淡入 + 从 0.72 弹回 1；退出端：淡出 + 缩到 0.72。
                    // 两者同时进行（togetherWith），中途是两个半透明图标交叠——
                    // 这正是「形变」而不是「换图」的关键：用户看到的是一枚图标在变，
                    // 不是两枚图标在交替。
                    fadeIn(GlassMotion.tweenPress<Float>()) +
                        scaleIn(initialScale = 0.72f, animationSpec = GlassMotion.tweenPress()) togetherWith
                        (fadeOut(GlassMotion.tweenPress<Float>()) +
                            scaleOut(targetScale = 0.72f, animationSpec = GlassMotion.tweenPress()))
                },
                label = "playModeIcon",
            ) { m ->
                // 旋转按**本子树自己的进出进度**推：进入时 -90° → 0（顺时针转正），
                // 退出时 0 → -90°（逆时针转出）。旧图标转出与新图标转正方向相反，
                // 于是整段读作「旧的一枚转走、新的一枚转进来」，而不是「整块图标转了 90°」。
                // 用 EnterExitState 而不是 0/1 布尔：AnimatedContent 的 Visible 态覆盖
                // 进入完成与稳定两段，直接映射成「稳定 = 1」即可。
                val rot by transition.animateFloat(label = "playModeRot") { state ->
                    if (state == EnterExitState.Visible) 1f else 0f
                }
                Icon(
                    m.icon,
                    // 语义由外层 Box 全权承担；Icon 再挂一份会重复播报（与 PlayPauseButton 同款纪律）
                    contentDescription = null,
                    Modifier
                        .size(20.dp)
                        .graphicsLayer { rotationZ = (1f - rot) * -90f },
                    tint = if (m.isAccent) auroraAccentColor() else glass.textSecondary,
                )
            }
        }

        // 环脉冲：matchParentSize 让它**不参与父级测量**（等价 fillMaxSize 但不影响
        // 父 Box 的尺寸）——否则这枚环会把按钮撑大 4dp，把 SpaceBetween 的整行配重推歪。
        //
        // 强调色在**组合期**取一次存进局部量：auroraAccentColor() 是 @Composable（它要读
        // LocalAuroraPalette + 主题态），而 drawBehind 的 lambda 是纯绘制期、不可调用
        // composable。取色只依赖主题，不会逐帧变，所以组合期取一次是正确的粒度。
        val ringColor = auroraAccentColor()
        Box(
            Modifier
                .matchParentSize()
                .graphicsLayer {
                    val p = ringPulse.value
                    alpha = if (p >= 1f) 0f else (1f - p) * 0.55f
                    val s = 0.55f + p * 0.85f
                    scaleX = s
                    scaleY = s
                }
                .drawBehind {
                    // 1.2dp 细环：与图标的 1.8/1.9 笔画不抢视觉，只做「扩散消失」的时间信号
                    drawCircle(
                        color = ringColor,
                        radius = size.minDimension / 2f - 1.2.dp.toPx(),
                        style = Stroke(1.2.dp.toPx()),
                    )
                },
        )
    }
}

/** 控制台小图标按钮：按压 scale(.88) */
@Composable
private fun ConsoleIcon(
    onClick: () -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier,
    stateDescription: String? = null,
    content: @Composable () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier
            .pressScale(interactionSource, pressed = 0.88f)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            // 语义挂在这个 Box 上（它就是 clickable 节点）：role=Button + 文案 + 可选状态。
            // 子 Icon 一律传 null，避免读屏把同一句话念两遍。
            .semantics {
                this.role = Role.Button
                this.contentDescription = contentDescription
                if (stateDescription != null) this.stateDescription = stateDescription
            }
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
        // A1：SectionLabelMicro = 11sp/14 + 0.3 字距，与旧的三处裸值（fontSize 11 + letterSpacing 0.3
        // + 默认行高）逐项一致。字距是这个档位**唯一的层级信号**（它代替了「小号大写字母」的
        // 视觉角色），所以必须由 token 一并携带，不能在调用点手写。
        style = GlassText.SectionLabelMicro,
        modifier = Modifier.padding(start = 6.dp, top = 10.dp, bottom = 4.dp),
    )
}

/**
 * 已播时长文本：把「10Hz 变化」的 state 读取隔离成极小组件。
 *
 * 为什么单独抽：`state.progressSec` 每 100ms 写一次，若在控制台函数体里读取，整条控制台
 * （模式行 / 播放键 / 队列入口……）都会每 tick 重组。进度轨已改为绘制期读，
 * 而 Text 必须组合期排版、绕不开——那就把重组范围缩到「一张文本」。
 *
 * [previewFraction]（2.2-D）：拖动进度条期间非空，此时显示**预览位置**而不是真实播放位置。
 * 读取发生在本组件内部，因此拖动时只有这一张文本逐帧重组，控制台其余部分零重组。
 * 拖动结束（回调传 null）后自动回到真实进度。
 */
@Composable
private fun ElapsedTimeText(state: AppState, previewFraction: MutableState<Float?>) {
    // 拖动中读预览值；未拖动走真实进度。两个来源都在本组件内读 → 重组域最小
    val preview = previewFraction.value
    val shownSec = if (preview != null) preview * state.durationSec else state.progressSec
    Text(
        shownSec.toInt().asTime(),
        color = LocalGlassColors.current.textSecondary,
        // B4 等宽数字：Numeric 带 fontFeatureSettings="tnum"。逐秒递增时比例数字下 "1" 比 "8" 窄，
        // 文本宽度每秒跳变一次，且与右侧 TotalTimeText 的右对齐关系会轻微呼吸；
        // tnum 让每个数字占同一字宽，时间轴彻底静止。字号/行高与旧裸 11sp 同档。
        style = GlassText.Numeric,
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
        // B4：与 ElapsedTimeText 同走 Numeric（等宽）：两端同为 tnum 才能保证
        // 「已播 / 总时长」这组对照数字宽度关系恒定（只改一侧会让两端的字宽基准不同）。
        style = GlassText.Numeric,
    )
}
