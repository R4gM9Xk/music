package com.local.music.core.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import com.local.music.core.ui.theme.GlassMotion
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * 固定行高拖拽重排列表（自研，零依赖）：长按起拖 → 邻居让位预览 → 松手单次提交 [onMove]。
 *
 * 前提（队列面板与歌单详情共用一套数学）：
 * - 行高恒定 = [rowHeight]，目标槽位用"被拖行中心点跨行中线"纯数学推算，不逐行测量；
 * - 本组件位于 [scrollState] 对应的滚动容器内容里（滚动补偿 = scrollState 值差）；
 * - [autoscroll] = true 时要求直接父级即滚动内容（listTop 用 positionInParent 量取），
 *   且列表自身高度须小于视口溢出量参与计算（GlassSheet 内容列满足）。
 *
 * 拖拽期间列表顺序冻结（不做中途提交），邻居行按目标槽位 ±行高 让位预览；
 * 松手才提交一次 onMove —— 引擎侧每次手势只收到一条 moveMediaItem。
 *
 * [dataVersion]：**内容语义版本**（调用方给，默认 null = 视为不变）。行槽组合 key 与
 * 手势 key 都含它；一变即「行槽重建 + 手势重启」，语义 = 拖拽中内容位移则本次拖拽作废。
 * 队列面板传当前曲索引：长拖期间自动切歌会让「接下来」段整体位移一格，此时继续拖就必须
 * 把手指位移、让位预览、目标槽位全部重映射到新基准，任一处算错就提交一次错歌的移动
 * （不可撤销）；作废只损失一次手势，明显更安全。代价：内容位移那一帧拖拽视觉中断
 * （行回到槽位），但这是「正在自动切歌」这种本就不该拖的场景，可接受。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ReorderableColumn(
    count: Int,
    rowHeight: Dp,
    scrollState: ScrollState,
    onMove: (fromIndex: Int, toIndex: Int) -> Unit,
    modifier: Modifier = Modifier,
    autoscroll: Boolean = false,
    dataVersion: Any? = null,
    isDraggable: (index: Int) -> Boolean = { true },
    row: @Composable (index: Int) -> Unit,
) {
    val density = LocalDensity.current
    val rowPx = with(density) { rowHeight.toPx() }
    var dragFrom by remember { mutableIntStateOf(-1) }
    var dragTarget by remember { mutableIntStateOf(-1) }
    var dragOffset by remember { mutableFloatStateOf(0f) } // 手指累计位移（px，不含滚动）
    var touchInRow by remember { mutableFloatStateOf(0f) }
    var scrollStart by remember { mutableFloatStateOf(0f) }
    var listTopInContent by remember { mutableFloatStateOf(0f) }
    var contentHeightPx by remember { mutableIntStateOf(0) }

    // 拖拽起点时的 dataVersion 快照：松手提交前与实时值比对，变了就放弃本次提交。
    // 手势协程的重启（pointerInput key 含 dataVersion）与状态更新在极端时序下可能让
    // onDragEnd 先跑到，这道比对是提交前的最后一道闸（见 dataVersion KDoc）。
    val liveDataVersion by rememberUpdatedState(dataVersion)
    var dragDataVersion by remember { mutableStateOf<Any?>(null) }
    /** 实时 count（见 updateTarget 的说明） */
    val liveCount by rememberUpdatedState(count)

    // 松手落位补间（纯视觉）：被拖行原本在 onDragEnd 里瞬间回到槽位（邻居有 150ms 让位
    // 补间，它没有），观感像"弹回"。同一时刻只可能有一行在落位，所以用一组共享状态而不是
    // 每行一个 Animatable（行数可达数百）。
    var settleRow by remember { mutableIntStateOf(-1) }
    var settleFrom by remember { mutableFloatStateOf(0f) }
    var settleValue by remember { mutableFloatStateOf(0f) }
    var settleGen by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()

    /**
     * 提交代际：每次 `onMove` 提交后 +1，并入行槽 key。
     *
     * 为什么必须有它：让位偏移 `rowShift` 是挂在**槽位**（key = index）上的 animateFloatAsState，
     * 而提交会让内容整体重索引（槽 i 现在显示旧 item(i+1)）。若只把目标值改成 0，
     * **当前动画值仍是 ±rowPx** —— 于是途经行在提交帧被画到"正确位置再上移一格"，
     * 再用 150ms 滑回来（整段"跳一格再滑回"）。
     * 更糟的是「手指精准落格」分支（|start|<=1 时跳过 settle）：被拖行落到槽 target 后
     * 直接吃到该槽残留的 ±rowPx → 整行瞬移一格。
     * commitGen 变化使所有槽位以 targetValue=0 重建（瞬时归位），被拖行仍由共享的
     * settleRow/settleValue 补间——两套机制解耦，互不覆盖。
     */
    var commitGen by remember { mutableIntStateOf(0) }

    fun cancelDrag() {
        dragFrom = -1
        dragTarget = -1
        dragOffset = 0f
    }

    fun cancelSettle() {
        settleGen++
        settleRow = -1
        settleValue = 0f
    }

    /**
     * 内容位移（dataVersion 变化）时作废拖拽与落位补间：行槽 key 会随 dataVersion 重建，
     * 但组合键变化本身不保证手势协程在 onDragEnd 之前一定被取消，这里显式清状态统一收敛。
     */
    LaunchedEffect(dataVersion) {
        if (dragFrom >= 0) cancelDrag()
        if (settleRow >= 0) cancelSettle()
    }

    /** 手指在列表内容坐标系中的 y（行高网格）：滚动发生时手指内容坐标随之平移 */
    fun fingerListY(): Float =
        dragFrom * rowPx + touchInRow + dragOffset + (scrollState.value - scrollStart)

    /** 目标槽位 = 被拖行中心点所在行（跨越行中线才换位，起点恒为本行）。
     *  读 [liveCount] 而不是组合期捕获的 count：拖拽中列表变短（清空接下来/移除后续项）
     *  时用旧上界会把 dragTarget 定到不存在的行（settleRow 指向无效行、补间白跑）。 */
    fun updateTarget() {
        if (dragFrom < 0) return
        val center = fingerListY() - touchInRow + rowPx / 2f
        dragTarget = (center / rowPx).toInt().coerceIn(0, (liveCount - 1).coerceAtLeast(0))
    }

    // 边缘自动滚动：手指贴近视口上下边缘时滚动，目标槽位随滚动值连续重算。
    // **按时间积分而不是每帧固定 4dp**：固定步长在 120Hz 屏上速度是 60Hz 的两倍。
    LaunchedEffect(dragFrom, autoscroll) {
        if (dragFrom < 0 || !autoscroll) return@LaunchedEffect
        val edgePx = with(density) { 28.dp.toPx() }
        // 240dp/s：与旧实现 60Hz × 4dp/帧 的观感一致，但不再依赖帧率
        val speedDpPerSec = 240f
        var lastNs = 0L
        while (dragFrom >= 0) {
            val now = withFrameNanos { it }
            val dtSec = if (lastNs == 0L) 0f else ((now - lastNs) / 1_000_000_000f).coerceIn(0f, 0.05f)
            lastNs = now
            if (scrollState.maxValue > 0 && dtSec > 0f) {
                val viewportTopInList = scrollState.value - listTopInContent
                val fingerInViewport = fingerListY() - viewportTopInList
                val viewportH = scrollState.viewportSize.toFloat()
                val delta = (speedDpPerSec * density.density * dtSec).toInt()
                when {
                    fingerInViewport < edgePx ->
                        scrollState.scrollTo((scrollState.value - delta).coerceAtLeast(0))
                    fingerInViewport > viewportH - edgePx ->
                        scrollState.scrollTo((scrollState.value + delta).coerceAtMost(scrollState.maxValue))
                }
            }
            updateTarget()
        }
    }

    Column(
        modifier.onGloballyPositioned {
            listTopInContent = it.positionInParent().y
            contentHeightPx = it.size.height
        },
    ) {
        repeat(count) { index ->
            // key 并入 (dataVersion, commitGen, index)：
            // - dataVersion：内容位移时行槽身份整体重建（拖拽中切歌作废）
            // - commitGen：**每次提交后重建**，让让位偏移 animateFloatAsState 以 0 为初值归位
            //   （否则提交帧途经行会带着旧基准的 ±rowPx 被画错一行，见 commitGen KDoc）
            key(dataVersion, commitGen, index) {
                val isDragged = index == dragFrom
                val shift = when {
                    dragFrom < 0 || dragTarget == dragFrom -> 0f
                    index > dragFrom && index <= dragTarget -> -rowPx // 下拖：途经行上移让位
                    index < dragFrom && index >= dragTarget -> rowPx // 上拖：途经行下移让位
                    else -> 0f
                }
                val rowShift by animateFloatAsState(
                    targetValue = shift,
                    // 让位补间随动效强度档位缩放（旧实现硬编码 150ms，设置页的「克制/增强」对它无效）
                    animationSpec = tween((150 * GlassMotion.scale).toInt().coerceAtLeast(1)),
                    label = "reorderShift$index",
                )
                Box(
                    Modifier
                        // heightIn 而非 height：大字号/大显示尺寸下 SongRow 会变高，
                        // 固定高会把文字上下切掉。行数学仍按 rowHeight 网格推算，大字号下
                        // 可能有轻微视觉重叠，但不再裁字（取舍：可读性优先）。
                        .heightIn(min = rowHeight)
                        .graphicsLayer {
                            // 落位进度只在这里（绘制期）读：0 = 刚松手的偏移，1 = 已回到槽位，
                            // 不触发重组；缩放同步从 1.02 收回到 1，避免补间结束时「啪」一下。
                            // settleValue 从 settleFrom 递减到 0，故进度 = 1 - 剩余/起点
                            val settleProgress = if (index == settleRow && settleFrom != 0f) {
                                (1f - settleValue / settleFrom).coerceIn(0f, 1f)
                            } else {
                                0f
                            }
                            translationY = when {
                                // 滚动补偿：拖拽行钉在指尖
                                isDragged -> dragOffset + (scrollState.value - scrollStart)
                                index == settleRow -> settleValue
                                else -> rowShift
                            }
                            if (isDragged) {
                                scaleX = 1.02f
                                scaleY = 1.02f
                            } else if (index == settleRow) {
                                // 起点仍是 1.02（松手瞬间与拖拽态无缝），随进度收回 1.0
                                val s = 1f + 0.02f * (1f - settleProgress)
                                scaleX = s
                                scaleY = s
                            }
                        }
                        .pointerInput(dataVersion, index, isDraggable(index)) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = { offset ->
                                    if (!isDraggable(index)) return@detectDragGesturesAfterLongPress
                                    // 新拖拽必须打断上一次的落位补间（否则两套位移叠在一起）
                                    if (settleRow >= 0) cancelSettle()
                                    dragFrom = index
                                    dragTarget = index
                                    touchInRow = offset.y
                                    dragOffset = 0f
                                    scrollStart = scrollState.value.toFloat()
                                    dragDataVersion = liveDataVersion
                                },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    dragOffset += dragAmount.y
                                    updateTarget()
                                },
                                onDragEnd = {
                                    val from = dragFrom
                                    val target = dragTarget
                                    // 松手瞬间的视觉偏移（含滚动补偿）：必须在 cancelDrag
                                    // 清掉 dragOffset 之前取，它是落位补间的起点基准
                                    val visual = dragOffset + (scrollState.value - scrollStart)
                                    val sameVersion = liveDataVersion == dragDataVersion
                                    cancelDrag()
                                    if (from >= 0 && target != from && sameVersion) {
                                        // 补间起点 = 提交后新槽位下要让视觉位置不变所需的位移：
                                        // 旧顶边 = from*行高 + visual，新顶边 = to*行高，
                                        // 故 translationY = visual + (from - to) * 行高
                                        val start = visual + (from - target) * rowPx
                                        // 先提交（语义不变：一次手势一条 move），再补间——
                                        // 提交后内容已在新槽位，补间只把视觉位置拉过去
                                        onMove(from, target)
                                        // **提交后代际 +1**：行槽 key 重建，让位偏移 animateFloatAsState
                                        // 以 0 为初值归位（否则途经行会带着旧基准的 ±rowPx 画错一行）
                                        commitGen++
                                        // 起点已与槽位重合（不足 1px）时没有可补的位移：
                                        // 跳过补间，否则 settleFrom≈0 会让缩放停在 1.02 一帧。
                                        // 已知取舍：此分支下缩放在松手帧从 1.02 直接回 1.0
                                        //（无极短补间）。不做「settleFrom=极小值、只驱动缩放」
                                        // 是因为进度 = 1 - settleValue/settleFrom 在分母趋 0 时
                                        // 得 NaN 会污染 graphicsLayer；且落位与槽位重合不足
                                        // 1px，硬跳肉眼不易察觉，引入第二套补间得不偿失。
                                        if (start < -1f || start > 1f) {
                                            // 三个状态同步写好（此回调内无挂起点 → 同一次快照可见），
                                            // 否则首帧会先按行槽位置画一下再跳回起点，等于没修
                                            settleRow = target
                                            settleFrom = start
                                            settleValue = start
                                            val gen = ++settleGen
                                            scope.launch {
                                                // 手写帧循环而非 Animatable(tween)：Animatable.snapTo
                                                // 是挂起函数，初值写入与 settleRow 无法保证同帧；
                                                // 缓动取 FastOutSlowIn（tween 的默认曲线）与邻居让位一致
                                                val t0 = withFrameNanos { it }
                                                while (settleGen == gen && settleRow >= 0) {
                                                    val now = withFrameNanos { it }
                                                    val fraction =
                                                        ((now - t0) / 1_000_000f / SETTLE_MS()).coerceIn(0f, 1f)
                                                    val eased = FastOutSlowInEasing.transform(fraction)
                                                    settleValue = settleFrom * (1f - eased)
                                                    if (fraction >= 1f) {
                                                        settleRow = -1
                                                        settleValue = 0f
                                                        break
                                                    }
                                                }
                                            }
                                        }
                                    }
                                },
                                onDragCancel = { cancelDrag() },
                            )
                        },
                ) {
                    row(index)
                }
            }
        }
    }
}

/** 落位补间时长：与邻居让位 tween(150×scale) 同值，两者同时收束才像一个整体。
 *  函数而非常量：随动效强度档位缩放（设置页「克制 ×0.6 / 增强 ×1.4」） */
private fun SETTLE_MS(): Float = 150f * GlassMotion.scale
