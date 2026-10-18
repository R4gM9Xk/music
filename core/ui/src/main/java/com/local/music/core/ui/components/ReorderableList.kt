package com.local.music.core.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.foundation.ScrollState

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

    fun cancelDrag() {
        dragFrom = -1
        dragTarget = -1
        dragOffset = 0f
    }

    /** 手指在列表内容坐标系中的 y（行高网格）：滚动发生时手指内容坐标随之平移 */
    fun fingerListY(): Float =
        dragFrom * rowPx + touchInRow + dragOffset + (scrollState.value - scrollStart)

    /** 目标槽位 = 被拖行中心点所在行（跨越行中线才换位，起点恒为本行） */
    fun updateTarget() {
        if (dragFrom < 0) return
        val center = fingerListY() - touchInRow + rowPx / 2f
        dragTarget = (center / rowPx).toInt().coerceIn(0, count - 1)
    }

    // 边缘自动滚动：手指贴近视口上下边缘时匀速滚，目标槽位随滚动值连续重算
    LaunchedEffect(dragFrom, autoscroll) {
        if (dragFrom < 0 || !autoscroll) return@LaunchedEffect
        val edgePx = with(density) { 28.dp.toPx() }
        val stepPx = with(density) { 4.dp.toPx() }
        while (dragFrom >= 0) {
            if (scrollState.maxValue > 0) {
                val viewportTopInList = scrollState.value - listTopInContent
                val fingerInViewport = fingerListY() - viewportTopInList
                val viewportH = contentHeightPx - (scrollState.maxValue - listTopInContent).coerceAtLeast(0f)
                when {
                    fingerInViewport < edgePx -> scrollState.scrollTo(scrollState.value - stepPx.toInt())
                    fingerInViewport > viewportH - edgePx -> scrollState.scrollTo(scrollState.value + stepPx.toInt())
                }
            }
            updateTarget()
            withFrameNanos { }
        }
    }

    Column(
        modifier.onGloballyPositioned {
            listTopInContent = it.positionInParent().y
            contentHeightPx = it.size.height
        },
    ) {
        repeat(count) { index ->
            val isDragged = index == dragFrom
            val shift = when {
                dragFrom < 0 || dragTarget == dragFrom -> 0f
                index > dragFrom && index <= dragTarget -> -rowPx // 下拖：途经行上移让位
                index < dragFrom && index >= dragTarget -> rowPx // 上拖：途经行下移让位
                else -> 0f
            }
            val rowShift by animateFloatAsState(
                targetValue = shift,
                animationSpec = tween(150),
                label = "reorderShift$index",
            )
            Box(
                Modifier
                    .height(rowHeight)
                    .graphicsLayer {
                        translationY = if (isDragged) {
                            dragOffset + (scrollState.value - scrollStart) // 滚动补偿：拖拽行钉在指尖
                        } else {
                            rowShift
                        }
                        if (isDragged) {
                            scaleX = 1.02f
                            scaleY = 1.02f
                        }
                    }
                    .pointerInput(index, isDraggable(index)) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = { offset ->
                                if (!isDraggable(index)) return@detectDragGesturesAfterLongPress
                                dragFrom = index
                                dragTarget = index
                                touchInRow = offset.y
                                dragOffset = 0f
                                scrollStart = scrollState.value.toFloat()
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                dragOffset += dragAmount.y
                                updateTarget()
                            },
                            onDragEnd = {
                                if (dragFrom >= 0 && dragTarget != dragFrom) onMove(dragFrom, dragTarget)
                                cancelDrag()
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
