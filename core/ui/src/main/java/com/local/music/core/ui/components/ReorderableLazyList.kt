package com.local.music.core.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.local.music.core.ui.theme.GlassMotion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 「在 `LazyColumn` 里长按拖拽重排」——本轮队列面板 Lazy 化的配套实现。
 *
 * ## 为什么不能复用 [ReorderableColumn]
 * [ReorderableColumn] 是 `Column { repeat(count) }` + 外层 `verticalScroll`：
 * 它一次组合全部行，滚动状态是 `ScrollState`。而 `LazyColumn` 下：
 * - 没有 `ScrollState`（只有 `LazyListState`）；
 * - 屏幕外的行**根本没有被组合**，`positionInParent()` 无从谈起；
 * - 想把 `Column` 塞进 `LazyColumn` 的一个 item 里绕过这些问题，等于放弃懒加载——
 *   一整段几百行仍会一次性组合，改造的意义就没了。
 *
 * 所以行数学必须在新宿主上**重新成立**，而不是搬过来。
 *
 * ## 行数学成立的前提（两条不变量，由调用方保证）
 * 1. **可拖区段内行高恒定**（`SongRow` 高度固定，见 [SongRowHeight]）。
 *    有这条，目标槽位就不必逐行测量：手指的内容坐标 ÷ 行高 即所在行。
 * 2. **可拖区段一直延伸到列表末尾，且段内没有任何变高元素**。
 *    有这条，「列表内容偏移」才能用 `firstVisibleItemIndex × 行高 + offset` 精确推算。
 *
 * 队列面板的结构正好满足：段头（已播放 / 正在播放）都在可拖区段**之前**，
 * 「接下来」段全是等高行且到末尾为止。若将来要在段内插段头，本类立刻失效。
 *
 * ## 实测不到的坑（写在这里避免后人踩）
 * - `LazyListState.firstVisibleItemScrollOffset` 是**首可见项内部**的偏移，不是整列表的；
 *   整列表偏移必须叠加 `index × 行高`。漏加会得到一个恒 < 行高的值，
 *   表现是「拖到屏幕边缘时目标槽位卡在第 0 行」。
 * - 手指位移与滚动补偿必须**分开记**（`dragOffset` vs `scrollStart`）：滚动时内容在动、
 *   手指没动，混在一起记会让被拖行跟着列表一起滚走。
 */
@Stable
class ReorderableLazyState internal constructor(
    private val listState: LazyListState,
    /** 行高（px） */
    private val rowPx: Float,
) {
    /** 当前被拖的行在**本段内**的下标（-1 = 无拖拽） */
    var dragFrom by mutableIntStateOf(-1)
        private set
    var dragTarget by mutableIntStateOf(-1)
        private set

    /** 手指累计位移（px）。**不含**滚动补偿——补偿在读取处叠加，见 [translationFor] */
    internal var dragOffset by mutableFloatStateOf(0f)

    /** 起拖时手指落点在行内的 y（把「手指位置」换算成「行位置」） */
    internal var touchInRow by mutableFloatStateOf(0f)

    /** 起拖瞬间的列表内容偏移（px），用于滚动补偿 */
    internal var scrollStart by mutableFloatStateOf(0f)

    /**
     * 「可拖拽段」顶部在**列表内容坐标系**里的 y（px）。
     * 由调用方经 [rememberReorderableLazyState] 的 `sectionTopPx` 提供。
     */
    internal var sectionTopPx by mutableFloatStateOf(0f)

    /** 本段行数 */
    internal var liveCount by mutableIntStateOf(0)

    // 落位补间共享状态（同一时刻只可能有一行在落位）
    internal var settleRow by mutableIntStateOf(-1)
    private var settleFrom by mutableFloatStateOf(0f)
    private var settleValue by mutableFloatStateOf(0f)
    private var settleGen by mutableIntStateOf(0)

    /**
     * 提交代际：每次 `onMove` 后 +1。
     *
     * 为什么必须有：让位偏移 `rowShift` 是 `animateFloatAsState`，提交会让内容整体重索引
     * （槽 i 现在显示旧 item(i+1)）。若只把目标值改成 0，**当前动画值仍是 ±rowPx**——
     * 于是途经行被画到「正确位置再上移一格」，再花 150ms 滑回来（整段「跳一格再滑回」）。
     * 把 commitGen 并入 [animateFloatAsState] 的 label 无效（label 不参与 key），
     * 所以它被放进 [rowModifier] 里 `graphicsLayer` 之外的重建条件——见那里的注释。
     */
    internal var commitGen by mutableIntStateOf(0)

    internal var onMove: (Int, Int) -> Unit = { _, _ -> }
    internal var scope: CoroutineScope? = null
    private var dragDataVersion: Any? = null
    private var liveDataVersion: Any? = null

    /** 列表内容偏移（px）：等高区段内由 (首可见下标, 项内偏移) 精确推算 */
    internal fun listOffsetPx(): Float =
        listState.firstVisibleItemIndex * rowPx + listState.firstVisibleItemScrollOffset

    /** 手指在**整条列表内容**里的 y（px） */
    internal fun fingerListY(): Float =
        dragFrom * rowPx + touchInRow + dragOffset + (listOffsetPx() - scrollStart)

    /** 目标槽位：被拖行中心点跨越行中线才换位（与原实现同一公式） */
    internal fun updateTarget() {
        if (dragFrom < 0) return
        val center = fingerListY() - touchInRow + rowPx / 2f
        val sectionIndex = ((center - sectionTopPx) / rowPx).toInt()
        dragTarget = sectionIndex.coerceIn(0, (liveCount - 1).coerceAtLeast(0))
    }

    internal fun cancelDrag() {
        dragFrom = -1
        dragTarget = -1
        dragOffset = 0f
    }

    internal fun cancelSettle() {
        settleGen++
        settleRow = -1
        settleValue = 0f
    }

    internal fun beginDrag(index: Int, withinRow: Float, version: Any?) {
        if (settleRow >= 0) cancelSettle()
        dragFrom = index
        dragTarget = index
        touchInRow = withinRow
        dragOffset = 0f
        scrollStart = listOffsetPx()
        dragDataVersion = version
    }

    internal fun endDrag() {
        val from = dragFrom
        val target = dragTarget
        if (from < 0) return
        // 松手瞬间的视觉偏移（含滚动补偿）：必须在 cancelDrag 清掉 dragOffset 之前取
        val visual = dragOffset + (listOffsetPx() - scrollStart)
        val sameVersion = liveDataVersion == dragDataVersion
        cancelDrag()
        if (target == from || !sameVersion) return
        // 补间起点 = 提交后新槽位下保持视觉位置不变所需的位移
        val start = visual + (from - target) * rowPx
        onMove(from, target)
        commitGen++
        if (start < -1f || start > 1f) {
            settleRow = target
            settleFrom = start
            settleValue = start
            val gen = ++settleGen
            scope?.launch {
                // 手写帧循环而非 Animatable：snapTo 是挂起函数，初值写入与 settleRow
                // 无法保证同帧（旧实现同款取舍，见 ReorderableColumn 的注释）
                val t0 = withFrameNanos { it }
                while (settleGen == gen && settleRow >= 0) {
                    val now = withFrameNanos { it }
                    val fraction = ((now - t0) / 1_000_000f / settleMs()).coerceIn(0f, 1f)
                    settleValue = settleFrom * (1f - GlassMotion.Curve.transform(fraction))
                    if (fraction >= 1f) {
                        settleRow = -1
                        settleValue = 0f
                        break
                    }
                }
            }
        }
    }

    internal fun syncLive(count: Int, version: Any?) {
        liveCount = count
        liveDataVersion = version
    }

    /**
     * 进行中的拖拽是否已因内容位移而失效。
     *
     * 判据必须是「实时版本 vs **起拖瞬间**的版本」（[dragDataVersion]）。
     * 旧写法拿 `rememberUpdatedState(dataVersion)` 与 `dataVersion` 自己比——两者恒等，
     * 条件永假，这道保护实际是死代码。
     */
    internal fun isDragStale(liveVersion: Any?): Boolean =
        dragFrom >= 0 && liveVersion != dragDataVersion

    /** 让位偏移：本行相对槽位应平移多少（px） */
    internal fun shiftFor(index: Int): Float = when {
        dragFrom < 0 || dragTarget == dragFrom -> 0f
        index > dragFrom && index <= dragTarget -> -rowPx
        index < dragFrom && index >= dragTarget -> rowPx
        else -> 0f
    }

    internal fun isSettling(index: Int): Boolean = index == settleRow
    internal fun settleProgress(): Float =
        if (settleFrom != 0f) (1f - settleValue / settleFrom).coerceIn(0f, 1f) else 0f
    internal fun settleValueNow(): Float = settleValue
    internal fun scrollCompensation(): Float = listOffsetPx() - scrollStart

    /** 已滚动的内容偏移（px）：自动滚动的「手指在视口里」判据要用它 */
    internal fun offsetPx(): Float = listOffsetPx()

    /** LazyListState 引用（自动滚动需要 scrollBy） */
    internal fun listStateInternal(): LazyListState = listState
}

/** 落位补间时长：与邻居让位 tween(150×scale) 同值，两者同时收束才像一个整体 */
private fun settleMs(): Float = 150f * GlassMotion.scale

/**
 * 创建 [ReorderableLazyState]。
 *
 * ## 段首位置为什么用「item 索引」而不是测量
 * 第一版打算用 `onGloballyPositioned { it.positionInParent() }` 量段头底边——**那是错的**：
 * `positionInParent()` 只到**直接父级**（LazyColumn 的内部 item 容器），而 LazyColumn
 * 会回收/复用 item 子节点、并让 item 在当前帧内按视口位置重新布局，
 * 于是拿到的既不是内容坐标、也随滚动漂移。
 *
 * 正确做法是**不测量**：段头之前的内容全部已知——
 * - 若 `safeIndex > 0`：1 个「已播放」段头 + safeIndex 行
 * - 1 个「正在播放」段头 + 1 行当前曲
 * - 1 个「接下来」段头（它自己的高度就是段首偏移的一部分）
 *
 * 只要这几类元素的高度是**常量**，段首就能纯算术算出。所以本类要求调用方显式传入
 * [sectionTopPx]——它是调用方按自己的 item 结构算好的常量表达式，
 * 而不是运行时测量值。队列面板里它 = 段头高度（常量），段内行全是等高行。
 *
 * @param listState 面板里 `LazyColumn` 的状态
 * @param rowHeight 行高（必须与实际行高一致，否则槽位推算会逐行累积误差）
 * @param count 可拖区段的行数
 * @param sectionTopPx 可拖区段顶部在**列表内容坐标**里的 y（px），由调用方按 item
 *   结构算常量（见上面的说明），**不要**用 positionInParent 测量
 * @param onMove 提交回调（段内下标，语义与 [ReorderableColumn] 一致）
 * @param dataVersion 内容语义版本：一变即作废进行中的拖拽
 */
@Composable
fun rememberReorderableLazyState(
    listState: LazyListState,
    rowHeight: Dp,
    count: Int,
    sectionTopPx: () -> Float,
    onMove: (fromIndex: Int, toIndex: Int) -> Unit,
    dataVersion: Any? = null,
): ReorderableLazyState {
    val density = LocalDensity.current
    val rowPx = with(density) { rowHeight.toPx() }
    // remember(listState)：同一个 LazyListState 复用同一个拖拽状态；列表换了（面板重建）
    // 就整体重置。不把 rowPx 放进 key —— 密度不会在运行中变化。
    val state = remember(listState) { ReorderableLazyState(listState, rowPx) }
    state.onMove = onMove
    state.scope = rememberCoroutineScope()
    // 段首每帧同步最新值：它是调用方按 item 结构算出的常量表达式，
    // 但会随 safeIndex 变化（自动切歌会让已播段变长 → 段首下移）
    state.sectionTopPx = sectionTopPx()

    val countLive by rememberUpdatedState(count)
    val versionLive by rememberUpdatedState(dataVersion)
    // 同步实时值：拖拽中列表变短（清空/移除）时用旧上界会把目标槽位定到不存在的行
    state.syncLive(countLive, versionLive)
    LaunchedEffect(countLive, versionLive) {
        // 内容位移（自动切歌把「接下来」段整体挪一格）时作废本次拖拽：
        // 继续拖就必须把手指位移、让位预览、目标槽位全部重映射到新基准，
        // 任一处算错就是一次错歌移动（不可撤销）；作废只损失一次手势
        if (state.isDragStale(versionLive)) state.cancelDrag()
    }
    // ── 边缘自动滚动 ──
    //
    // 与 [ReorderableColumn] 同语义：手指贴近视口上下边缘时让列表滚，目标槽位随滚动值
    // 连续重算。Lazy 版的差别是滚动用 `listState.scrollBy`——它按像素滚动，
    // 不必像 ScrollState 那样自己 clamp 到 [0, maxValue]（LazyListState 内部处理边界）。
    //
    // 为什么仍要「按时间积分」而不是每帧固定 dp：固定步长在 120Hz 屏上速度是 60Hz 的两倍。
    val listStateForScroll = state.listStateInternal()
    LaunchedEffect(state.dragFrom) {
        if (state.dragFrom < 0) return@LaunchedEffect
        val edgePx = with(density) { 28.dp.toPx() }
        val speedDpPerSec = 240f
        var lastNs = 0L
        while (state.dragFrom >= 0) {
            val now = withFrameNanos { it }
            val dtSec = if (lastNs == 0L) 0f else ((now - lastNs) / 1_000_000_000f).coerceIn(0f, 0.05f)
            lastNs = now
            if (dtSec > 0f) {
                // 手指在**视口**里的 y = 内容坐标 − 已滚动的量
                val fingerInViewport = state.fingerListY() - state.offsetPx()
                val viewportH = listStateForScroll.layoutInfo.viewportSize.height.toFloat()
                val delta = (speedDpPerSec * density.density * dtSec)
                when {
                    fingerInViewport < edgePx -> listStateForScroll.scrollBy(-delta)
                    fingerInViewport > viewportH - edgePx -> listStateForScroll.scrollBy(delta)
                }
            }
            state.updateTarget()
        }
    }
    return state
}

/**
 * 给可拖行的根节点挂手势与位移。
 *
 * ## `graphicsLayer` 里读的所有值都是「绘制期读」
 * 位移与缩放每帧变，在组合期读会让整行按帧重组（本仓的既有红线）。
 *
 * ## 为什么 `pointerInput` 的 key 含 [ReorderableLazyState.commitGen]
 * 提交后内容重索引，行槽身份变了。把 commitGen 放进 key 会让手势协程重启——
 * 这是**有意的**：提交瞬间进行中的手势本就该结束（一次手势只提交一次移动）。
 */
@Composable
fun ReorderableLazyState.rowModifier(index: Int, dataVersion: Any? = null): Modifier {
    // key(commitGen)：提交后内容整体重索引（槽 i 现在显示旧 item(i+1)），让位偏移必须以 0
    // 为初值重建。只把目标值改成 0 不够——animateFloatAsState 的**当前动画值**仍是 ±rowPx，
    // 途经行会被画到「正确位置再偏一格」再花 150ms 滑回来。与 ReorderableColumn 的
    // key(dataVersion, commitGen, index) 同一修法（那边是内容 key，这里只能 key 住动画本身）。
    //
    // **持有 State 对象而不是解构后的 Float**：值只在下方 graphicsLayer 的绘制期 lambda 里
    // 读，让位动画的 150ms 内本行零重组（本仓既有红线）。解构成 Float 会把读取带回组合期，
    // 每帧重组整行。
    val rowShift = key(commitGen) {
        animateFloatAsState(
            targetValue = shiftFor(index),
            animationSpec = tween(
                (150 * GlassMotion.scale).toInt().coerceAtLeast(1),
                easing = GlassMotion.Curve,
            ),
            label = "lazyReorderShift$index",
        )
    }
    return Modifier
        .graphicsLayer {
            translationY = when {
                index == dragFrom -> dragOffset + scrollCompensation()
                isSettling(index) -> settleValueNow()
                else -> rowShift.value
            }
            if (index == dragFrom) {
                scaleX = 1.02f
                scaleY = 1.02f
            } else if (isSettling(index)) {
                val s = 1f + 0.02f * (1f - settleProgress())
                scaleX = s
                scaleY = s
            } else {
                // 必须**无条件**写回 1：graphicsLayer 只对本次 lambda 里写到的属性生效，
                // 条件跳过会让上一次拖拽写进去的 1.02 永久留在图层上（2% 放大 + 位图
                // 重采样导致的发虚），直到该行滚出视口被回收。这条路径在「原位放下
                // （target == from）」「onDragCancel」「拖拽中切歌作废（!sameVersion）」
                // 三处都会走到——它们都不启动 settle 补间，也就没有别的机会归位。
                // 同款教训已记录在 PlayerFlightOverlay 的 graphicsLayer 注释里。
                scaleX = 1f
                scaleY = 1f
            }
        }
        .pointerInput(dataVersion, index, commitGen) {
            detectDragGesturesAfterLongPress(
                onDragStart = { offset -> beginDrag(index, offset.y, dataVersion) },
                onDrag = { change, dragAmount ->
                    change.consume()
                    dragOffset += dragAmount.y
                    updateTarget()
                },
                onDragEnd = { endDrag() },
                onDragCancel = { cancelDrag() },
            )
        }
}
