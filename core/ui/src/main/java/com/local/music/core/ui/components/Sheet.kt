package com.local.music.core.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.local.music.core.ui.theme.GlassMotion
import com.local.music.core.ui.theme.GlassText
import com.local.music.core.ui.theme.R
import com.local.music.core.ui.theme.S
import com.local.music.core.ui.theme.LocalGlassColors
import com.local.music.core.ui.theme.auroraAccentColor
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

/**
 * 模态面板全局宿主状态：同一时刻最多一个面板（单 content 槽）。
 * 面板渲染统一提升到根层宿主（[ModalSheetHost]，由 GlassApp 在内容区 hazeSource
 * 的兄弟位置调用——铁律①「效果层=源兄弟」，面板玻璃不再位于任何源子树内），据此——
 * - 隐藏底部 Chrome / 播放页控制台（面板在根层后绘，Chrome 门控与控制台让位读 [isOpen]，
 *   退场期保持让位直到 [ModalSheetHost] 清空 content 才复位）
 * - 返回键优先关闭面板而不是离开页面
 *
 * 生命周期：show(content) → content 即刻组合（GlassSheet 以 visible=true 入场）；
 * requestDismiss() → visible=false（GlassSheet 播放退场动画，isOpen 仍为 true）；
 * **退场动画真正跑完**后 [ModalSheetHost] 清空 content → 面板卸载、让位解除。
 */
class ModalState {
    /** 当前面板内容：打开→组合；退场动画结束后清空→卸载（期间保持非 null 维持让位语义） */
    var content: (@Composable () -> Unit)? by mutableStateOf(null)
        private set

    /** 可见标志：false = 退场中（退场动画由 GlassSheet 的 AnimatedVisibility 播放） */
    var visible by mutableStateOf(false)
        private set

    /**
     * 退场动画结束代数：GlassSheet 的 AnimatedVisibility 真正空闲（退场末帧已画完）时 +1。
     *
     * 宿主据此清理 content ——「按 tweenPage 时长 delay」不是同一时间源：延时协程与动画
     * 起表差一帧、退场期掉帧、动效强度乘数变化都会让 delay 先于动画结束，content 提前
     * 清空 → 退场最后 1-2 帧被截断。用代数而不是回调，是为了让宿主用 snapshotFlow 观测，
     * 不必关心时序与重入（重开面板时代数不变，宿主 effect 直接被取消）。
     *
     * **只统计「属于当前关闭代次」的信号**（见 [dismissSeq] / [onSheetExitFinished]）。
     */
    var exitAnimationId by mutableIntStateOf(0)
        private set

    /**
     * 关闭请求代次：每次 [requestDismiss] +1。
     *
     * **为什么需要它（本轮修复）**：一个 modal 生命周期内可能同时存在多个 [GlassSheet]。
     * `SongActionsSheet` 就有三个互斥子面板（主面板 / 加入歌单 / 歌曲信息），
     * `CollectionActionsSheet` 同样有三个（主面板 / 重命名 / 删除确认）。
     * 子面板之间的切换（主面板退出、子面板进入）也会让**旧子面板**播完退场动画并上报信号。
     * 宿主那时若正好在等一次 dismiss 的收尾信号，就会被这个**属于另一次切换**的信号
     * 提前唤醒 → `clearContent()` 提前执行 → 当前正在退场的那一块被截断最后几帧。
     *
     * 修法：退场**开始**时冻结当时的代次，完成时带回；宿主只认代次等于当前值的信号。
     * 子面板切换发生在 dismiss 之前，携带的是旧代次 → 天然被忽略。
     */
    private var dismissSeq = 0

    /** 当前关闭请求代次（[GlassSheet] 在退场开始时取一次快照） */
    internal fun currentDismissSeq(): Int = dismissSeq

    /** 面板存在（含退场期）：返回键 / Chrome 门控 / 控制台让位统一读这里 */
    val isOpen: Boolean get() = content != null

    /**
     * 打开面板（同刻只保有一个：再 show 即原位替换，与旧单可见面板语义一致）。
     *
     * 原位替换（P3-69）：面板**已打开**（visible=true）时再次 [show]，宿主会换用新 content
     * lambda —— 旧 GlassSheet 节点离场、新节点全新组合（visibilityState 初值 false），
     * 新面板播一次入场动画，旧内容不做退场（单树切换，无新旧并存期）。若要求「旧退场 →
     * 新入场」的双树交叉需引入 content 代际 key，牵动宿主清理时机与嵌套子面板的退场信号
     * 语义——维持单树，登记为已知取舍。
     * 退场中途重开则不同：visible 翻回 true 会从退场中间态反向播入场。
     */
    fun show(content: @Composable () -> Unit) {
        this.content = content
        visible = true
    }

    /** 请求关闭当前面板（返回键 / scrim 点击 / 动作完成统一走这里） */
    fun requestDismiss() {
        // 代次先行：让「这次 dismiss」与「之前/之后面板切换产生的退场信号」可区分
        if (visible) dismissSeq++
        visible = false
    }

    /**
     * 退场动画结束时由 GlassSheet 回传（宿主机制不外泄：仅 ModalSheet 接线）。
     *
     * @param seq 该 GlassSheet **开始退场时**冻结的 [currentDismissSeq] 值。
     *   只有与当前 [dismissSeq] 相等才计入 —— 见 dismissSeq 的 KDoc（子面板切换的
     *   退场动画不能唤醒宿主的 dismiss 收尾等待）。
     */
    internal fun notifyExitAnimationFinished(seq: Int) {
        if (seq != dismissSeq) return
        exitAnimationId++
    }

    /** 退场动画结束后由 [ModalSheetHost] 清空内容（宿主机制不外泄） */
    internal fun clearContent() {
        content = null
    }
}

/**
 * 模态面板状态：**没有可用默认值**。
 *
 * 默认工厂若返回 `ModalState()`，provider 外的读取（新屏、新组件一时忘了包在
 * GlassApp 的 provider 内）会拿到一个孤儿实例：面板能 show、visible 也会翻 true，
 * 但没有任何 [ModalSheetHost] 渲染它 —— 表现为「点了没反应」，且不报错、极难定位。
 * 所以这里直接 error：漏了 provider 是编排错误，应当在开发期就炸掉。
 *
 * 全仓读取点已核对：GlassApp.kt 提供，各屏（Library / Playlist / PlaylistDetail /
 * Player / Search / Settings）均在 provider 内 current 读取；无 @Preview / 测试
 * 依赖默认值（若将来要加预览，请显式传 `LocalModalState provides ModalState()`）。
 */
val LocalModalState = compositionLocalOf<ModalState> {
    error("LocalModalState 必须在 GlassApp 的 provider 内使用（ModalSheetHost 是唯一宿主）")
}

/**
 * 模态面板宿主渲染点（GlassApp 根层、内容区 hazeSource 兄弟位置调用）：
 * - content 非空即渲染；面板经自身参数接 [ModalState.visible] 驱动 GlassSheet 进出场动画
 * - **退场动画真正结束后**才清空 content（isOpen 归 false，Chrome/控制台让位解除）：
 *   中途重开（visible 翻回 true）时不会收到信号，清理自然被取消——与旧「退场中途重开
 *   撤销待注销注册」语义等价，但不再依赖「delay 与动画同值」这种时间猜测。
 */
@Composable
fun ModalSheetHost(modal: ModalState) {
    // 退场收尾信号的下发通道：内层 GlassSheet 退场真正结束时回调（详见 LocalSheetExitFinished）
    val exitReporter = remember(modal) {
        SheetExitReporter(
            currentSeq = { modal.currentDismissSeq() },
            notify = { seq -> modal.notifyExitAnimationFinished(seq) },
        )
    }
    LaunchedEffect(modal) {
        // collectLatest：visible 翻回 true 时取消本次等待，清理随之取消（重开面板不清内容，
        // 与旧「退场中途重开撤销待注销注册」逐帧等价）
        snapshotFlow { modal.visible }.collectLatest { vis ->
            if (vis || modal.content == null) return@collectLatest
            val startId = modal.exitAnimationId
            // 正路：等内层 AnimatedVisibility 真正空闲（退场末帧已画完）后发的信号。
            // 超时只作活性兜底——若这次关闭没有任何可见子面板真跑退场动画（嵌套子面板已在
            // 打开期切走），信号不会来，此时按「时长 + 2 帧余量」收场，避免让位永不解除。
            // 注意这是兜底不是精度依赖：正常路径下信号必定先到，与动画同源。
            withTimeoutOrNull(
                GlassMotion.tweenPage<Float>().durationMillis.toLong() + EXIT_FALLBACK_MS,
            ) {
                snapshotFlow { modal.exitAnimationId }.first { it != startId }
            }
            // 等待期间可能又被 show() 重开：落笔前再确认一次，宁可不清理也不误清
            if (!modal.visible) modal.clearContent()
        }
    }
    val content = modal.content
    if (content != null) {
        CompositionLocalProvider(LocalSheetExitFinished provides exitReporter) {
            content()
        }
    }
}

/**
 * 面板退场收尾信号通道：由 [ModalSheetHost] 在 content 外层提供，内层 [GlassSheet] 的
 * AnimatedVisibility 退场**动画结束**时经它上报。
 *
 * 为什么走 CompositionLocal 而不是给每个调用点加参数：GlassSheet 的直接调用点分散在
 * 各 feature 屏（PlayerScreen 等），而宿主清理只需要一份信号；用一个「宿主作用域内的
 * 环境回调」可以让既有调用点零改动（新增参数必须默认值、不能逼别人改文件，见写域约束）。
 * 默认空实现 = 非宿主场景（如二级面板自渲染、将来预览）静默降级。
 *
 * **为什么是两个成员而不是一个 `() -> Unit`**（本轮修复）：一个 modal 生命周期内可以有
 * 多个 GlassSheet（`SongActionsSheet` / `CollectionActionsSheet` 各三个互斥子面板），
 * 子面板**切换**也会跑退场动画并上报。旧实现只上报「我退场完了」，宿主无法区分那一次退场
 * 属于「当前这次关闭」还是「面板内部切换」→ 会被无关信号提前唤醒、把当前面板的退场
 * 末帧截断（正是该机制本来要消灭的观感问题）。
 * 现在 GlassSheet 在退场**开始那一刻**取 [currentSeq] 快照，完成时带回；宿主只接受
 * 等于当前关闭代次的信号。
 */
internal class SheetExitReporter(
    val currentSeq: () -> Int,
    val notify: (seq: Int) -> Unit,
)

internal val LocalSheetExitFinished = staticCompositionLocalOf {
    SheetExitReporter(currentSeq = { 0 }, notify = { })
}

/**
 * 退场收尾信号的活性兜底余量（ms，约 4 帧@60Hz）：只在「本次关闭没有任何子面板跑过退场
 * 动画」（信号永不到达）时用于收场，正常路径由真动画结束信号触发。**这是兜底不是精度依赖**：
 * 若把它当主路径（旧实现），就会因起表差帧 / 掉帧而截断退场末帧。
 */
private const val EXIT_FALLBACK_MS = 64L

/**
 * 面板容器自身的读屏文案（无障碍 P0-4）。
 *
 * **为什么要集中成对象**：本轮（task-4）不建 strings.xml（那是 task-5 的活），文案先用
 * 中文字面量；但若把字面量直接散进 `semantics { contentDescription = "关闭面板" }` 里，
 * 下一轮做资源抽取时就得反过来在 UI 代码里逐个 grep 定位。集中成常量后，抽取动作变成
 * 「把这个对象整体搬进 resources + 改这里的引用」，每个文案的**唯一来源**仍是这里。
 *
 * 只放面板容器自己的文案。菜单项文案（「下一首播放」「全部播放」等）**不搬进来**——
 * 它们来自 [SheetAction] 的 label/subtitle 形参，是可见文字，读屏靠 merge 自动朗读，
 * 硬编码到常量里反而会造成「界面文案 vs 读屏文案」双源漂移。
 */
private object SheetA11y {
    /** 面板遮罩：点它 = 关闭面板（明眼用户点面板外部，盲用户走读屏动作） */
    const val BACKDROP = "关闭面板"

    /**
     * 队列面板「清空」按钮。
     *
     * 可见文字只有「清空」两字，紧跟在「接下来 · N 首」右侧；读屏按节点顺序朗读时
     * 上下文（那行段头文字）已经过去了，单独一个「清空」无法自解释。
     * 文案里写清**作用范围**：只清「接下来」，不动正在播放的那首。
     */
    const val CLEAR_UP_NEXT = "清空接下来要播放的歌曲"
}

/**
 * 在 modal.show { } 内挂 GlassSheet 的便捷包装：自动接宿主 visible/dismiss——
 * 各屏不再直接渲染 GlassSheet（效果层统一在根层宿主）。
 */
@Composable
fun ModalSheet(
    modal: ModalState,
    title: String,
    modifier: Modifier = Modifier,
    scrollState: ScrollState = rememberScrollState(),
    content: @Composable ColumnScope.() -> Unit,
) {
    GlassSheet(
        visible = modal.visible,
        onDismiss = modal::requestDismiss,
        title = title,
        modifier = modifier,
        scrollState = scrollState,
        content = content,
    )
}

/**
 * 底部玻璃动作面板：半透明 scrim + 顶部圆角玻璃卡，360ms 弹性上滑入场。
 * 设置强度选择 / 播放队列 / 更多操作 / 新建歌单 的共用容器。
 * 可见性由调用方驱动（模态面板经根层宿主 ModalSheetHost 挂载、接 ModalState.visible）；
 * 面板本体不再向 ModalState 注册（注册/注销职责已上收到宿主）。
 */
@Composable
fun GlassSheet(
    visible: Boolean,
    onDismiss: () -> Unit,
    title: String,
    modifier: Modifier = Modifier,
    scrollState: ScrollState = rememberScrollState(),
    content: @Composable ColumnScope.() -> Unit,
) {
    GlassSheetScaffold(
        visible = visible,
        onDismiss = onDismiss,
        title = title,
        modifier = modifier,
    ) { contentMaxHeight ->
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = contentMaxHeight)
                .verticalScroll(scrollState),
            content = content,
        )
    }
}

/**
 * 懒加载版底部面板（本轮新增，配合队列面板 Lazy 化）。
 *
 * ## 为什么需要单独一个入口，而不是给 [GlassSheet] 加个开关
 * 两者的**滚动容器类型**根本不同：普通版是 `Column + verticalScroll(ScrollState)`，
 * 懒加载版是 `LazyColumn(LazyListState)`。这不是「同一个东西的两种模式」——
 * [ScrollState] 与 [LazyListState] 是两套互不兼容的 API（前者 measure 全部子项、
 * 后者按需组合），任何一方的调用方（例如 ReorderableColumn 的行数学）都只认其中一套。
 * 做成 boolean 参数会让签名上写着一个 `ScrollState` 却在 true 时被忽略——
 * 那是最难排查的一类 API。
 *
 * ## 内容列为什么自己不是滚动容器
 * Lazy 列表必须**独占**滚动轴（不能嵌在 verticalScroll 里，会抛「测量被赋予无限高度」）。
 * 所以这里的内容列是一个普通 Column（不滚），把 `heightIn(max)` 约束交给
 * [LazyColumn] 自己——它拿到有界高度才能正确懒加载。
 *
 * @param content `LazyListScope` 的扩展 lambda：调用方用 `item {}` / `items {}` 描述内容
 */
@Composable
fun GlassSheetLazy(
    visible: Boolean,
    onDismiss: () -> Unit,
    title: String,
    listState: LazyListState,
    modifier: Modifier = Modifier,
    /**
     * 列表下方的固定脚注（可选）。
     *
     * 为什么需要它：有些面板要在列表**之后**放一块不滚动的区域（队列面板的撤销条）。
     * 若把这类内容做成 LazyColumn 的最后一个 item，它会成为滚动内容的一部分——
     * 对队列面板而言这会破坏「接下来段延伸到末尾」这条拖拽行数学的前提
     * （见 QueueSheet 的 KDoc）。所以脚注必须留在 LazyColumn 之外、面板之内。
     */
    footer: (@Composable () -> Unit)? = null,
    content: LazyListScope.() -> Unit,
) {
    GlassSheetScaffold(
        visible = visible,
        onDismiss = onDismiss,
        title = title,
        modifier = modifier,
    ) { contentMaxHeight ->
        // ── 高度约束：本函数唯一需要小心的地方（真机踩坑记录，改前请读完）──
        //
        // ## 现象（一加 13 真机，2026-11-14）
        // 队列面板的玻璃卡只包住约 2.5 行，后面的行画在卡外、背景全透，
        // 且**溢出部分不接收点击**（「清空」按钮点了完全没反应）。
        //
        // ## 根因（这是关键，前 4 个版本都栽在没找到它）
        // 传进来的 `contentMaxHeight` 是「屏幕高的 65%」（本机 514.8dp），
        // 但它会被放进 Scaffold 那个带 `navigationBarsPadding()` +
        // `padding(top 12 / bottom 16)` 的 Column 里。该 Column **按内容测高**，
        // 分给子项的可用高度已经把 navbar（≈48dp）与内边距、把手（4dp）、
        // 标题（≈38dp）都扣掉了 —— 只剩约 397dp。
        //
        // 也就是说：**子项声明 514.8dp，父级实际只给得出 397dp**。
        // 普通版（`verticalScroll`）对此无感——视口被压小就照常滚动；
        // 但 LazyColumn 会拿声明值当确定高度排版，而 Box 又按父级真正给出的
        // 尺寸定框，于是「内容按大的排、卡片按小的画」，末尾几行跑到卡外、
        // 且那部分的触摸命中区不在卡片内（点「清空」没反应就是这么来的）。
        //
        // 实测吻合：卡高 ≈1800px ÷ 4 density ≈ 450dp；而在 397dp 附近。
        // 此前的 `lazySize=2059px`（= 514.8dp × 4）只是 LazyColumn **自己**
        // 拿到的声明值，不代表卡片真有那么高——这也是我一度被误导的原因。
        //
        // ## 修复
        // 在**本函数内**先扣掉面板骨架，让子项声明的高度与父级能提供的一致。
        // 只在 lazy 版扣、不动 `GlassSheet` 的公共算法：普通版有 9 个调用点，
        // 统一改会让所有面板矮 118dp（例如「歌曲操作」面板），那是不必要的回归。
        val chromeDp = 48 + 12 + 16 + 4 + 38 // navbar + padTop + padBottom + 把手 + 标题
        val listMaxHeight = (contentMaxHeight.value - chromeDp).dp.coerceAtLeast(160.dp)
        // 让一个**普通（非滚动）** Box 持有确定高度，LazyColumn 填满它。
        // Box 量这一层时拿到的是明确的 listMaxHeight（普通容器的尺寸由 height
        // 修饰符直接得出，与内容测量无关），于是卡片高度正确、clip 也裁得对。
        Box(
            Modifier
                .fillMaxWidth()
                .height(listMaxHeight),
        ) {
            LazyColumn(
                Modifier.fillMaxSize(),
                state = listState,
                // 尾部留白 = 脚注高度：见上方 P2 说明。footer 为 null 时不补——
                // 那时最后一行贴底是正确的（多出的空白会被读成「列表没加载完」）。
                contentPadding = PaddingValues(bottom = if (footer != null) QueueUndoBarHeight else 0.dp),
                content = content,
            )
            if (footer != null) {
                // 脚注**叠在**列表底部之上，而不是把列表挤矮（P2 修复：改为按实际高度让位）。
                //
                // ## 为什么仍然叠（而不是把列表挤矮）
                // 挤矮会让列表高度随脚注出现/消失跳变，而撤销条是 4 秒后自动消失的瞬态
                // 元素，那种跳变会很显眼（面板整体高度在 4 秒内弹一下）。
                //
                // ## 为什么必须补 bottom padding 给列表内容
                // 旧实现的注释声称「不遮任何内容」，但**它确实会遮**：footer 是
                // `align(BottomCenter)` 的覆盖层，而 LazyColumn 的内容一直延伸到可视区末尾，
                // 所以当用户把列表滚到底部时，最后一行正好落在 footer 底下——
                // 「已清空 N 首 / 撤销」那条浮条压住了用户刚想看的最后一首。
                //
                // 修法不是把 footer 挪走（它必须固定可见），而是**给列表内容补足尾部留白**：
                // LazyColumn 的 contentPadding 只在内容两端生效、不影响视口高度，
                // 于是滚到底时最后一行会停在 footer 之上，而 footer 出现/消失仍不改变
                // 面板高度（contentPadding 变化只是内容偏移，不触发视口重排跳变）。
                //
                // 高度取 QueueUndoBarHeight —— 与 footer 那个 `.height()` 是同一个常量，
                // 不另写一份数值（两处各写一份就会「改了一处忘了另一处」，那正是这条
                // 缺陷最初的成因）。
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(QueueUndoBarHeight),
                    contentAlignment = Alignment.Center,
                ) {
                    footer.invoke()
                }
            }
        }
    }
}

/** 撤销条固定高度（dp）：一行 Caption（17 行高）+ 上下 padding 各 8 + 余量 */
private val QueueUndoBarHeight = 48.dp

/**
 * 面板共同骨架：scrim + 入场动画 + 玻璃卡 + 把手 + 标题。
 *
 * 抽出来的唯一目的是让 [GlassSheet] 与 [GlassSheetLazy] 共享**除滚动容器之外**的一切
 * （入场/退场动画、遮罩、把手、标题层级、高度约束推导）。复制一份的结果是：
 * 任何一次视觉调整都要记得改两处，而漏掉的那一处会静默漂移。
 *
 * @param scrollableContent 收到「内容区最大高度」，自行决定怎么用（普通列 / Lazy 列）
 */
@Composable
private fun GlassSheetScaffold(
    visible: Boolean,
    onDismiss: () -> Unit,
    title: String,
    modifier: Modifier,
    scrollableContent: @Composable (contentMaxHeight: Dp) -> Unit,
) {
    // 用 MutableTransitionState 而不是 visible 布尔 + AnimatedVisibility：那是唯一能
    // 「知道退场动画真的跑完了」的公开入口（isIdle 且 targetState=false）。宿主据此清 content，
    // 取代「按 tweenPage 时长 delay」——delay 与动画不同源（起表差一帧 / 退场掉帧 /
    // 动效强度乘数变化），会先于动画结束，导致退场最后 1-2 帧被截断。
    // 初值恒 false（不再取当前 visible）：首组合 visible=true 时两态不等 → 播放开场
    // 上滑渐入（:227 契约）。旧实现初值取 visible 使首组合即 idle，打开=1 帧硬切且与
    // 有退场无入场不对称；连带修掉子面板切换（ActionsSheet 互斥 GlassSheet）时
    // 「主面板渐隐 + 子面板瞬现」的双 scrim 叠闪——现在主退场与子入场交叉，scrim 之和近似恒定。
    // 原位替换语义（P3-69，modal.show 换内容）：旧面板即刻离场、新面板播入场——
    // 仍是单树切换（不引 contentKey 双子树），无退场残留。
    val visibilityState = remember { MutableTransitionState(false) }
    visibilityState.targetState = visible
    val exitReporter = LocalSheetExitFinished.current
    // 只有「先真的可见过、现在退场跑完」才算一次退场结束：
    // - 初次组合就是 visible=false 的嵌套子面板（动作面板里 sub 未选中的那些）一上来
    //   就是 idle + target=false，若只看这个组合会立刻误报一次退场；
    // - 入场结束时 idle + target=true，也必须不报（否则宿主会把刚打开的面板清掉）。
    var hasBeenVisible by remember { mutableStateOf(false) }
    // 退场开始时冻结的 dismiss 代次：随信号一起回传，让宿主能区分「这次关闭」与
    // 「同生命周期内其它子面板切换产生的退场」（见 ModalState.dismissSeq 的 KDoc）。
    var exitSeq by remember { mutableIntStateOf(0) }
    LaunchedEffect(visibilityState.isIdle, visibilityState.currentState, visibilityState.targetState) {
        if (!visibilityState.isIdle) {
            hasBeenVisible = true
            // 非空闲且有退场倾向 = 正在进入退场：冻结当下代次（而不是用组合首帧的死值）
            if (!visibilityState.targetState) exitSeq = exitReporter.currentSeq()
        } else if (hasBeenVisible && !visibilityState.targetState) {
            hasBeenVisible = false
            exitReporter.notify(exitSeq)
        }
    }
    AnimatedVisibility(
        visibleState = visibilityState,
        enter = fadeIn(GlassMotion.tweenPage()) +
            slideInVertically(GlassMotion.tweenPage()) { it },
        exit = fadeOut(GlassMotion.tweenPage()) +
            slideOutVertically(GlassMotion.tweenPage()) { it },
    ) {
        // BoxWithConstraints 而**不是**直接读 LocalConfiguration.screenHeightDp（P2 修复）：
        // 后者报的是**物理屏幕**的高度，在分屏/自由窗口/画中画下与这个 Composable 真正
        // 拿到的高度无关（Lint 的 ConfigurationScreenWidthHeight 正是这条）。而面板高度
        // 按「屏幕的 65%」算，在分屏里会得到一个比可用空间还大的值 → 面板被顶穿、
        // 内容被 GlassSurface 的 clip 裁掉、最后几行点不到。
        //
        // BoxWithConstraints 的 maxHeight 是**本节点实际拿到的约束上限**，天然跟着
        // 窗口大小/横竖屏/insets 走，也不需要再手写任何「减去系统栏」的补偿。
        BoxWithConstraints(
            modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.35f))
                // 读屏（无障碍 P0-4）：这个 Box 只是为了「点面板外部关闭面板」而整屏铺满的
                // 视觉遮罩，本身就画一层 35% 黑。若不处理语义，TalkBack 会把它当成一个
                // 占满全屏、无描述的可点节点——盲用户划进面板时第一个落点常是它，
                // 读屏只念「按钮」，既不知道这是什么、也不知道点了会怎样。
                //
                // 这里给两条语义：
                // 1. contentDescription = "关闭面板" —— 让这个节点自解释。
                // 2. **必须同时挂 onClick 动作** —— 明眼用户靠「点外面」关闭面板，盲用户
                //    没有这个手势；把点击暴露成读屏可执行的动作后，局部导航里就多出一条
                //    「关闭面板」，功能对等。只写 contentDescription 不挂 onClick 是**更糟**
                //    的假修复：读屏会念「关闭面板」却点不动，文案承诺了一个不存在的功能。
                //
                // 关于「焦点圈在面板内」：不要试图在这里用 accessibilityFocusGroup ——
                // 那是 compose-ui 1.11+ 的 API，本工程锁在 1.10.0（见 libs.versions.toml），
                // 用了直接编译不过。1.10.0 的正确做法是把 paneTitle 挂在**面板本体**上
                // （见下方 GlassSurface 的注释），而不是挂在遮罩上。
                .semantics {
                    this.contentDescription = SheetA11y.BACKDROP
                    onClick(label = SheetA11y.BACKDROP) {
                        onDismiss()
                        true
                    }
                }
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                ),
        ) {
            // 可用高度必须在**本 lambda 的第一层**接成普通局部 val：一旦进入内部
            // Box / GlassSurface / Column 的 content lambda（各带 BoxScope /
            // ColumnScope 接收者），隐式接收者就换成了别的 scope，直接写 `maxHeight`
            // 会编译失败：
            //   'val maxHeight: Dp' cannot be called in this context with an implicit receiver
            // 接成普通 val 之后，下面任意深度的 lambda 都只是闭包捕获，不受接收者影响。
            val panelAvailableHeight = maxHeight
            // ── 下拉收起手势的状态（见把手处的 KDoc）──
            //
            // 只做「松手判定」，不做面板跟随位移的视觉反馈：后者要把位移接进
            // AnimatedVisibility 的 slideInVertically 偏移，而那条曲线正被入场/退场动画
            // 独占——两个来源写同一个 translationY 会互相打架（入场时面板会从半空开始滑）。
            // 收益（跟手的爽感）远小于风险（转场抖动），所以本轮只做判定：
            // 视觉上面板不动、松手过阈值才收起。
            var sheetDragOffset by remember { mutableStateOf(0f) }
            // 阈值取面板可用高度的 1/4，钳在 [72dp, 160dp]：
            // - 太小（横屏 244dp 面板的 1/4 只有 61dp）→ 轻微下滑就误关；
            // - 太大（900dp 屏的 1/4 要拖 225dp）→ 拇指一次自然下滑到不了。
            // 72–160dp 覆盖两者，是一次自然下滑的量级。
            val sheetDragDismissPx = with(LocalDensity.current) {
                (panelAvailableHeight * 0.25f).coerceIn(72.dp, 160.dp).toPx()
            }
            // 面板本体：吃掉自身点击，避免透传到 scrim；imePadding 让输入面板浮在键盘之上
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .imePadding()
                    // 手势挡板：这个 Box 存在的唯一目的是「吞掉打在面板上的点击」，
                    // 防止透传到下面的 scrim 把面板关掉。
                    //
                    // **不能用 clearAndSetSemantics {}**：它清的是**整棵子树**的语义，
                    // 而本 Box 是 GlassSurface（面板全部内容）的父节点——旧写法把标题、
                    // 动作行、输入框、队列行全部从无障碍树里抹掉了，读屏在任意面板里
                    // 只剩遮罩一个「关闭面板」节点，删除确认/加入歌单对盲用户完全不可达。
                    //
                    // 改用 Main 隧道消费：Main  pass 由子到父派发，子节点（动作行/列表滚动）
                    // 先拿到事件，本层再把**仍未被消费**的事件吃掉，于是事件到不了 scrim。
                    // pointerInput 不产生任何语义节点，正好满足「纯手势挡板、无描述」的原意。
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                awaitPointerEvent(PointerEventPass.Main)
                                    .changes
                                    .forEach { it.consume() }
                            }
                        }
                    },
            ) {
                GlassSurface(
                    modifier = Modifier
                        .fillMaxWidth()
                        // 读屏（无障碍 P0-4）：paneTitle 把这块底部面板声明成一个「窗格」。
                        // 这是 compose-ui 1.10.0 里表达「焦点圈在面板内」的标准做法
                        // （accessibilityFocusGroup 要到 1.11+ 才有，本工程不能用）。
                        // 挂面板本体而不是挂遮罩：paneTitle 的语义是「这片区域是一个独立
                        // 窗格」，读屏进入时先报窗格名，再把焦点限制在窗格内部遍历——
                        // 盲用户因此不会划着划着跑到被面板压黑的背景层上去，
                        // 打开面板时也立刻知道「现在弹出了什么」。
                        // 标题复用 GlassSheet 的 title 形参：面板顶部的可见标题就是它，
                        // 不另造一份文案（避免界面与读屏两个来源）。
                        .semantics { paneTitle = title },
                    level = GlassLevel.Md,
                    // C4：面板顶部圆角改用 R.Player28 常量（原裸写 28.dp）。
                    // 底部两角为 0：面板贴在屏幕底边，圆角在那里没有可见意义。
                    shape = RoundedCornerShape(topStart = R.Player28.dp, topEnd = R.Player28.dp),
                    // ── 「模糊背景」而不是「用内衬盖住背景」（2026-11-21 用户反馈）──
                    //
                    // 用户要求「队列面板、下拉浮层也一样改」：与底部 chrome 统一到
                    // 「靠模糊挡字」这套语言。
                    //
                    // 旧实现是 `blur = Md`（20dp）+ 下面 Column 里再叠一层
                    // `.background(glassVeil())`（深色黑 32% / 浅色白 62%）。
                    // 那层 veil 就是「遮挡」的来源——它把模糊结果整体压平，面板读作
                    // 一块半透明实板，看不出背后被糊过。
                    //
                    // 现在：blurVeil = Panel（30dp、竖向 progressive、tint 只定调），
                    // **下面 Column 的 veil 背景必须一起去掉**（否则等于没改）。
                    blurVeil = BlurVeilKind.Panel,
                ) {
                    // 模态面板是最高层级：内衬已由 blurVeil = Panel 的 tint 承担
                    // （2026-11-21：原实现这里再叠一层 glassVeil()，正是「遮挡感」的来源——
                    //   深色黑 32% / 浅色白 62% 把模糊结果整体压平，面板读作半透明实板）。
                    // **不要再往这里加 background**：加回去就等于撤销「模糊而非遮挡」这条修复。
                    //
                    // 可用高度已在 GlassSurface 之前接成 panelAvailableHeight（见那里），
                    // 这里不再碰 maxHeight —— 本 lambda 的隐式接收者是 BoxScope 而非
                    // BoxWithConstraintsScope，读它会编译失败。
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            // top 从 12 收到 4：把手命中区从 4dp 高变成 20dp 高（见下），
                            // 4 + 20/2 = 14dp 恰好等于改前「12 + 4/2」的条中心 y，
                            // 视觉位置零变化，只扩了命中区
                            .padding(start = S.Gap20.dp, end = S.Gap20.dp, top = 4.dp, bottom = S.Gap16.dp),
                    ) {
                        // 顶部拖拽把手 + 下拉收起手势。
                        //
                        // ## 为什么把手必须有手势（本轮修复）
                        // 旧实现只画了一根 32×4dp 的圆角条，**全文件零拖拽手势**——它是纯装饰。
                        // 而「顶部把手 = 可以往下拽」是用户对底部面板的既有心智（系统通知栏、
                        // iOS sheet、几乎所有 Android 音乐 App 都是这样）。画了把手却拽不动，
                        // 用户会先试两三次才放弃，然后把「这个面板关不掉」记成缺陷。
                        //
                        // 为什么不给整个面板挂拖拽：面板内部有 LazyColumn / 滚动列，整面拖拽
                        // 会与滚动的手势竞争（向下滚列表 vs 向下拖面板无法区分）。把手是
                        // **专属拖拽区**，它的存在意义正是「这里只负责关面板」。
                        //
                        // 热区 20dp 高 + 72dp 宽：把手视觉只有 32×4dp，命中区必须够大，
                        // 但**不能**撑高布局——Column 的 top padding 是 12dp，这里把 4dp
                        // 条放在 20dp 命中区正中，条的中心落在 12+10=22dp 处；
                        // 改前是 12dp padding 后紧跟 4dp 条（中心 14dp）。
                        // 所以同时把 Column 的 top padding 从 12 收到 4，让中心回到 14dp
                        // ——视觉位置与改前逐像素一致，只多了命中区。
                        val dismissDragState = rememberDraggableState { delta ->
                            // 只累计向下位移；向上拖不产生任何效果（面板已经贴底，没有
                            // 「往上展开」这个状态，允许向上拖会让手感变成「拖不动」）
                            sheetDragOffset = (sheetDragOffset + delta).coerceAtLeast(0f)
                        }
                        Box(
                            Modifier
                                .align(Alignment.CenterHorizontally)
                                .width(72.dp)
                                .height(20.dp)
                                .draggable(
                                    state = dismissDragState,
                                    orientation = Orientation.Vertical,
                                    onDragStopped = { velocity ->
                                        // 阈值双判据：位移过半（面板高度的 1/4 以上）
                                        // 或**甩**出去（速度 > 1000px/s）——只看位移会
                                        // 让快速轻扫失灵，只看速度会让慢拖无响应
                                        val passed = sheetDragOffset > sheetDragDismissPx ||
                                            velocity > 1_000f
                                        sheetDragOffset = 0f
                                        if (passed) onDismiss()
                                    },
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(
                                Modifier
                                    .width(32.dp)
                                    .height(4.dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(LocalGlassColors.current.textTertiary.copy(alpha = 0.55f)),
                            )
                        }
                        // B1 标题层级倒挂修复：原为 12sp + textSecondary，比面板内的 15sp
                        // 动作行**还弱**——用户第一眼读到的是菜单项而不是「这个面板是什么」。
                        // 改 Heading（16 SemiBold）+ textPrimary：面板标题 > 动作行 > 段头，
                        // 三档单调递减，层级才成立。段头（SectionLabelMicro）保持 textTertiary
                        // 不动——它是第三档，模糊掉它反而会让面板重新变吵。
                        Text(
                            title,
                            style = GlassText.Heading,
                            color = LocalGlassColors.current.textPrimary,
                            modifier = Modifier
                                .align(Alignment.CenterHorizontally)
                                .padding(top = S.Gap12.dp, bottom = 6.dp),
                        )
                        // E5 面板高度：固定 420dp → **可用高度的 65%**。
                        //
                        // 为什么不再用固定值：420dp 在小屏（≈640dp 高）上占 66%、在
                        // 一加13 这类 900+dp 大屏上只占 46%——同一个面板在大屏上「显得局促」
                        // 而小屏上「几乎铺满」，面板的呼吸感随设备漂移。按可用高度取比例后，
                        // 「面板占多少」这个观感在设计上是常量。
                        //
                        // **分母用 maxHeight 而不是 LocalConfiguration.screenHeightDp**：
                        // 后者是物理屏幕高度，分屏/自由窗口下与本节点实际可用空间无关
                        // （Lint 的 ConfigurationScreenWidthHeight 告警即此项）；面板会因此
                        // 被算得比可用空间还大，被顶部顶穿、末尾几行落在卡片外且点不到。
                        // maxHeight 是**本节点拿到的约束上限**，天然含窗口尺寸与 insets。
                        //
                        // **下限 280dp 是必须的**：横屏时可用高度只剩 360–420dp，
                        // 纯 65% 会算出 234–273dp，长队列（几百首）就只能在一条窄缝里滚动。
                        // 280dp 保证横屏下至少露出约 4 行，队列面板仍然可用。
                        // 下限用 coerceAtMost(maxHeight) 兜一层：极窄分屏（例如 300dp 高）
                        // 上 280dp 仍然超出可用空间，此时必须服从父约束——宁可面板矮，
                        // 也不能超出屏幕（超出部分既画不出来、也点不到）。
                        //
                        // **不需要再手写上限**：外层 Column 在测量非 weight 子项时会递减
                        // 剩余空间（把手 4 + 标题 38 已占用，另有 navigationBarsPadding 与
                        // 内边距先行扣减），把「剩余可用高度」作为 mainAxisMax 传给本列。
                        // 于是横屏极小屏上 280dp 会被父约束自动压到约 244dp，
                        // 面板不会被顶穿、也不会被 GlassSurface 的 clip 裁掉。
                        //
                        // 顺序要求：heightIn 必须作用在滚动**视口**上（排在 verticalScroll
                        // 之前）。反过来的话它约束的是滚动内容而不是视口，max 就不再是
                        // 「面板最多多高」而是「内容最多多高」，语义和实际效果都变了。
                        //
                        // ## 注意：这个值是「面板内容区的外层上限」，不是内层可用的绝对值
                        // 对普通版（verticalScroll）无害——verticalScroll 的视口会自己
                        // 服从父约束（被压到 438dp 也照常滚动，只是矮一点）。
                        // 但对 **lazy 版**它是个坑：LazyColumn 会拿这个值当**确定高度**
                        // 声明，而父级实际只给得出「此值 − 骨架（navbar+padding+把手+标题）」，
                        // 两者错位导致列表绘制/命中区溢出卡片。所以 `GlassSheetLazy`
                        // 内部会再扣一次骨架（见该函数），这里保持原值不变。
                        val contentMaxHeight = (panelAvailableHeight * 0.65f)
                            .coerceAtLeast(280.dp)
                            .coerceAtMost(panelAvailableHeight)
                        // 滚动容器由调用方（GlassSheet / GlassSheetLazy）提供，
                        // 这一层只负责「把有界高度传下去」与外壳装饰
                        scrollableContent(contentMaxHeight)
                    }
                }
            }
        }
    }
}

/**
 * 面板动作行：32dp 玻璃图标块 + 主副标题；emphasized = 选中态（强调色）；
 * destructive = 破坏性动作（警示色，标在不可撤销的删除/清空/放弃类动作上）。
 *
 * **读屏语义（无障碍 P0-4）**：这是全 App 所有菜单项的唯一渲染点——
 * SongActionsSheet（下一首播放/收藏/加入歌单/歌曲信息…）、CollectionActionsSheet
 * （全部播放/重命名/删除…）、排序面板、添加歌曲面板全部经它出图。
 * 所以这一处的语义补齐，等价于把「曲库/搜索/歌单/播放页所有 Sheet 菜单」一次性修好，
 * 是本次收益/成本比最高的一处。
 *
 * 为什么要 `mergeDescendants = true`（这一点不是可选的）：
 * 行内是「图标块 Box + 标题 Text + 可选副标题 Text」三个兄弟节点。Compose 默认**不会**
 * 把 Row 的子节点合并成一个无障碍节点，于是 TalkBack 会把这行拆成 3 个可聚焦项——
 * 其中图标块是个完全无文字的空节点（读屏停上去只念「按钮」）、标题和副标题又各停一次。
 * 对一行「下一首播放 / 添加到当前播放队列之后」的动作项，盲用户要划 3 下才能确认自己
 * 停在哪里，且中间那个空节点纯属噪声。merge 后整行收敛为单个按钮节点，
 * 读屏一次念完「下一首播放，添加到当前播放队列之后，按钮」。
 *
 * 为什么不给 Row 写死 contentDescription 而是让子 Text 自然合并：label/subtitle 本身就是
 * 可见文字，merge 后 Compose 按子树文字顺序拼出描述——**不写死**才不会在文案改动时
 * 出现「读屏念旧文案、界面显示新文案」的双源漂移（这类缺陷审计查不出来，只有盲用户能撞上）。
 *
 * 子 Icon 传 null 是对的、且必须保持 null：图标纯粹是行首视觉锚点，语义已由同行的
 * 标题/副标题承担；若给它一并描述，读屏会把同一句话念两遍（见 PlayPauseButton 同款说明）。
 */
@Composable
fun SheetAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    emphasized: Boolean = false,
    destructive: Boolean = false,
) {
    val glass = LocalGlassColors.current
    val accent = auroraAccentColor()
    // A4 破坏性动作色：destructive 的优先级**高于** emphasized。
    // 两者语义互斥（一个是「推荐/开启」，一个是「不可撤销」），同时为 true 是调用方
    // 表达冲突；此处选择让**风险信号**胜出——误把删除动作渲染成普通强调色的代价，
    // 远大于误把强调动作渲染成警示色。加这个参数是为了让 ActionsSheet 的
    // 「删除歌单 / 确认删除 / 从歌单移除」有统一入口，不必各自硬编码颜色。
    val rowColor = when {
        destructive -> glass.destructive
        emphasized -> accent
        else -> glass.textPrimary
    }
    val interactionSource = remember { MutableInteractionSource() }
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(R.Card14.dp))
            .pressScale(interactionSource, pressed = 0.98f)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            // 语义挂在 clickable 同一个节点上（与 Chrome.MiniBarButton / PlayerScreen.ConsoleIcon
            // 同款写法），整行合并为单个 Button 节点，见上方 KDoc
            .semantics(mergeDescendants = true) { this.role = Role.Button }
            .padding(horizontal = 6.dp, vertical = S.Gap12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(S.Gap12.dp),
    ) {
        Box(
            Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(R.Thumb10.dp))
                .background(glass.md),
            contentAlignment = Alignment.Center,
        ) {
            // contentDescription = null 是**有意为之、非遗漏**：图标为行首视觉锚点，
            // 语义由同一行的 label/subtitle 承担（merge 后自动朗读），给了就是重复播报
            Icon(
                icon, null, Modifier.size(18.dp),
                tint = rowColor,
            )
        }
        Column(Modifier.weight(1f)) {
            // A1：15sp Medium 裸写 → Row（15 Medium 21），与列表行同档。
            // 动作项不该比列表行更重（视觉审计 A1 的意图），层级靠行高与图标块撑。
            Text(
                label,
                color = rowColor,
                style = GlassText.Row,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                // subtitle 含路径/歌名拼接，窄屏会换行。Micro 自带 LHMicro（14sp）行高，
                // 原实现手写 lineHeight = 14.sp 正是 LHMicro 的值，改 token 后不必再写。
                Text(subtitle, color = LocalGlassColors.current.textTertiary, style = GlassText.Micro)
            }
        }
    }
}

/** 段头小标签（队列面板内：已播放 / 正在播放），与行内文案左对齐
 *
 *  A1：11sp + 0.3 字距的裸写 → `GlassText.SectionLabelMicro`，两者是完全相同的打包
 *  （11 Normal / 14 行高 / 0.3 字距）。用 token 的意义在于「段头」这个语义从此有了名字：
 *  下次有人想给面板加段头时，不必再去别处抄这三个数值。 */
@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        color = LocalGlassColors.current.textTertiary,
        style = GlassText.SectionLabelMicro,
        modifier = Modifier.padding(start = S.Gap20.dp, end = S.Gap20.dp, top = S.Gap12.dp, bottom = 4.dp),
    )
}

/**
 * 队列面板段头的**固定高度**（dp）。
 *
 * 为什么这个数必须写成常量、而不是靠测量：拖拽重排的行数学需要知道「接下来」段
 * 顶部在列表内容坐标里的偏移，而 LazyColumn 的 item 坐标无法可靠测量
 * （理由见 [rememberReorderableLazyState] 的 KDoc）。既然只能算，就必须把
 * 段头高度钉成一个**与实际布局一致**的常量。
 *
 * 当前值 = padding(top S.Gap12 = 12) + padding(bottom 4) + Micro 行高(LHMicro = 14) = 30dp。
 * **改 SectionLabel 的内边距或字号时必须同步改这里**，否则拖拽的目标槽位会整体偏移。
 * 之所以只留这一个常量而不是算三个：已播放 / 正在播放 / 接下来 三个段头用的是
 * 同一套内边距与同一个字号（见 [SectionLabel] 与下面 upcoming-header 的 Row），
 * 高度天然相同。
 */
private val SheetSectionLabelHeight = 30.dp

/**
 * 播放队列面板（两段式）：已播段（普通行）/ 正在播放（波形高亮、不可拖不可删）
 * / 接下来（可拖拽重排、可移除、可清空）。
 * 行序 = 播放器队列平铺序；拖拽域仅「接下来」段（onMove 索引已换算回队列空间，
 * 全部 > 当前曲索引 → 当前曲与引擎窗口不受影响）。
 *
 * ## 本轮：Lazy 化（挂起已久的性能项）
 *
 * 改动前是 `Column + verticalScroll`：打开面板时**一次性组合全部行**。典型队列
 * （<60 首）打开约 150ms，尚可；但数百首以上时每一行都要主线程同步解码封面
 * （冷缓存 1-3ms/行），500 首就是数百 ms 的可见卡顿，且整段行全部常驻组合。
 *
 * 现在是 `LazyColumn`：只为视口内的行组合与解码。收益随队列长度线性放大。
 *
 * ## Lazy 化牵动了什么（这是它被挂起这么久的真实原因）
 * 拖拽重排的行数学原本建立在 `ScrollState` 之上（[ReorderableColumn]），
 * 而 `LazyColumn` 只有 `LazyListState`，且屏幕外的行**不被组合**——
 * 「逐行 positionInParent」那套测量完全不成立。修复方式见
 * [rememberReorderableLazyState]：靠「段内行高恒定 + 段延伸到末尾」这两条不变量，
 * 把行数学简化回一维纯算术。
 *
 * ## 段头的位置为什么关键
 * 可拖区段（「接下来」）之后**不能再有任何变高元素**（包括另一个段头），
 * 否则「列表内容偏移 = 首可见下标 × 行高」这条推算失效。撤销条因此被放在
 * LazyColumn **之外**（面板内容列的最后一项），而不是作为列表 item 追加。
 */
@Composable
fun QueueSheet(
    visible: Boolean,
    onDismiss: () -> Unit,
    queue: List<DemoSong>,
    currentIndex: Int,
    onPick: (Int) -> Unit,
    onMove: (fromIndex: Int, toIndex: Int) -> Unit,
    onRemove: (index: Int) -> Unit,
    onClearUpNext: () -> Unit,
    isPlaying: Boolean = true,
    /**
     * A-3：撤销窗口。
     *
     * 三个参数都是**由调用方（AppState）驱动**的最小面：数量决定撤销条显不显示、
     * 文案要写几首；[onUndoClearUpNext] 返回 false 表示现场已失效，本面板据它决定
     * 要不要给「无法撤销」的回执；[onDismissUndo] 让超时与主动关闭都收敛到状态层
     * 一处（面板自己不持有计时，否则面板被关掉后计时就断了，撤销窗口会永远开着）。
     *
     * 为什么撤销条做在**面板内**而不是 App 级浮层：清空动作发生在面板里，
     * 撤销条贴着发生地出现，用户不需要把视线移出面板去找「刚才那个东西在哪」。
     * 代价是关掉面板就失去撤销入口——这与 4 秒窗口一致（超过窗口本来也不给撤销）。
     */
    undoClearCount: Int = 0,
    onUndoClearUpNext: () -> Boolean = { false },
    onDismissUndo: () -> Unit = {},
    /**
     * 撤销窗口**剩余毫秒数**（由状态层按 deadline 现算）。
     *
     * 为什么面板需要它：`canUndoClearUpNext` 读的是注入时钟而非快照状态，
     * 没有重组就不会重新求值——旧实现没有任何计时驱动，4 秒窗口过后撤销条仍挂着，
     * 用户点它队列不恢复、条子消失、零提示。面板据此到期主动收条。
     */
    undoWindowMs: () -> Long = { 0L },
) {
    val listState = rememberLazyListState()
    val density = LocalDensity.current
    val glass = LocalGlassColors.current
    val rowPx = with(density) { SongRowHeight.toPx() }
    // 空队列时 lastIndex=-1，coerceIn(0,-1) 会抛 IAE（冷启动默认屏=Player，权限拒/无音频即触发）
    val safeIndex = if (queue.isEmpty()) 0 else currentIndex.coerceIn(0, queue.lastIndex)
    // 长拖中自动切歌会推进 currentIndex：onMove 换算必须读实时值，否则松手提交时用的是
    // 拖拽起点的陈旧基准，会把「接下来」段内的相对位置搬到错误的队列绝对索引上
    val liveSafeIndex by rememberUpdatedState(safeIndex)
    val upcomingCount = (queue.size - safeIndex - 1).coerceAtLeast(0)

    // ── 撤销窗口的两条收口（都由面板驱动，状态层只负责真值）──
    //
    // 1. **到期收条**：canUndoClearUpNext 读注入时钟、非快照，无重组即不失效。
    //    这里按状态层给的剩余时长计时，到点调 onDismissUndo 让条子消失。
    // 2. **失败回执**：onUndoClearUpNext 返回 false = 现场已失效（超时/队列被改过）。
    //    AppState 与 QueueSheet 的 KDoc 都要求给一句「队列已变化，无法撤销」，
    //    旧调用点把 Boolean 直接丢了 —— 用户点了撤销，条子消失、队列没恢复、没有任何解释。
    var undoFailed by remember { mutableStateOf(false) }
    LaunchedEffect(undoClearCount) {
        if (undoClearCount <= 0) return@LaunchedEffect
        undoFailed = false // 新一次清空：清掉上一轮的失败提示
        delay(undoWindowMs().coerceAtLeast(0L))
        onDismissUndo()
    }
    LaunchedEffect(undoFailed) {
        if (undoFailed) {
            delay(2_500)
            undoFailed = false
        }
    }

    // ── 「接下来」段顶部在列表内容坐标里的 y（px）──
    //
    // **纯算术，不测量**（理由见 rememberReorderableLazyState 的 KDoc）：
    // 段头之前的内容结构是确定的——
    //   已播放段头 + 已播行数×行高 + 正在播放段头 + 1×行高 + 接下来段头
    // 段内第 0 行的顶边就是这些之和。
    //
    // 三个段头高度相同（同一个 SectionLabel / 同一套内边距），所以只有一个常量。
    // **改段头内边距或字号时必须同步改 SheetSectionLabelHeight**，否则目标槽位整体偏移。
    val sectionTopPx = with(density) {
        val header = SheetSectionLabelHeight.toPx()
        val played = if (safeIndex > 0) header + safeIndex * rowPx else 0f
        val current = header + rowPx          // 「正在播放」段头 + 当前曲一行
        played + current + header             // + 「接下来」段头
    }

    // 打开面板时定位到当前曲（与原实现同语义）。
    // Lazy 版改用 scrollToItem：直接按「第几个 item」定位，不再需要手算像素——
    // 这本身是 Lazy 化的一个附带收益（原实现要计入段头高度 labelPx 的估算误差）。
    LaunchedEffect(visible, currentIndex) {
        if (!visible) return@LaunchedEffect
        // 用户正在滚动/拖拽时跳过跟随（A2-03）：自动切歌不该把浏览中的视口拽走
        if (listState.isScrollInProgress) return@LaunchedEffect
        // item 布局：0 = 已播放段头（仅当 safeIndex > 0）、之后是已播行、
        // 然后是「正在播放」段头 + 当前行。目标 = 让当前曲**上一行**停在顶部。
        val playedHeader = if (safeIndex > 0) 1 else 0
        val currentRowItemIndex = playedHeader + safeIndex + 1 // +1 = 「正在播放」段头
        val target = (currentRowItemIndex - 1).coerceAtLeast(0)
        listState.scrollToItem(target)
    }

    // 拖拽状态：段内行数 = upcomingCount，段首位置由上面量得的 sectionTopPx 提供
    //
    // 内容版本指纹 = 「行下标 → 队列索引」映射的基准：只取「当前曲索引 + 当前曲 id」，
    // 段内重排不会改变它，而自动切歌 / 引擎队列回流都会改变它 → 覆盖全部会导致错位的
    // 场景，又不干扰刚落位的补间。
    //
    // **必须只有一份**：状态层（syncLive）与行级（beginDrag）拿它做相等比较来决定
    // 「松手时基准有没有变」。两处各写一份表达式曾让一边是 Pair、另一边是 Int，
    // 相等恒 false → endDrag 永远提前 return → 拖拽重排看着有让位预览、松手全部弹回。
    val reorderVersion = safeIndex to queue.getOrNull(safeIndex)?.id
    val reorder = rememberReorderableLazyState(
        listState = listState,
        rowHeight = SongRowHeight,
        count = upcomingCount,
        sectionTopPx = { sectionTopPx },
        onMove = { from, to -> onMove(from + liveSafeIndex + 1, to + liveSafeIndex + 1) },
        dataVersion = reorderVersion,
    )

    GlassSheetLazy(
        visible = visible, onDismiss = onDismiss,
        title = "播放队列 · ${queue.size} 首",
        listState = listState,
        // 撤销条走脚注槽（面板内、LazyColumn 之外）：
        // 它不能是列表 item —— 「接下来」段必须一直延伸到列表末尾，
        // 段内出现任何变高元素都会让拖拽的行数学（列表偏移 = 首可见下标 × 行高）失效。
        footer = when {
            undoFailed -> {
                { QueueUndoFailedNote() }
            }
            undoClearCount > 0 -> {
                {
                    QueueUndoBar(
                        count = undoClearCount,
                        onUndo = {
                            // 返回值必须消费：false = 现场已失效，给用户一句解释，
                            // 而不是让条子无声消失（见 AppState.undoClearUpNext 的 KDoc）
                            if (!onUndoClearUpNext()) undoFailed = true
                            onDismissUndo()
                        },
                    )
                }
            }
            else -> null
        },
    ) {
        if (queue.isEmpty()) {
            // 空队列空态：旧实现先画「正在播放」段头、再在取行时提前 return，
            // 结果是一个孤立的「正在播放」标签 + 大片空白（曲库未装载/权限被拒时点队列即可见）。
            item(key = "queue-empty") {
                Text(
                    "队列是空的 · 去曲库挑一首开始播放",
                    color = glass.textTertiary, style = GlassText.Body,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(vertical = S.Gap32.dp),
                )
            }
            return@GlassSheetLazy
        }
        // ── 已播放段（懒加载）──
        //
        // ## key 为什么是**段内下标**而不是曲目 id（P0 修复）
        //
        // 队列**允许重复曲目**：`AppState.playNext / playLast / insertQueueItems`
        // （AppState.kt 的 playNext/playLast 系列）的 KDoc 明写「重复添加允许」，
        // 而它们确实会往队列里塞同一首歌的第二份、第三份。旧实现的 key 是
        // `"played-${song.id}"` / `"upcoming-${queue[queueIndex].id}"` ——
        // 同一首歌出现两次就得到**两个相同的 key**，LazyColumn 在组合期直接抛
        // `IllegalArgumentException: Key "played-f/sdcard/a.mp3" was already used`。
        //
        // 触发路径很日常，不是边角：
        // 1. 对同一首歌连点两次「下一首播放」（长按 → 下一首播放 → 再长按 → 仍下一首播放）；
        // 2. 先播放某张专辑，再对该专辑点「下一首播放全部」——整张专辑的曲目各出现两次。
        // 随后打开播放队列即崩溃。这是可静态证实的崩溃路径（不是概率问题）。
        //
        // ## 为什么不用「id + 出现序号」而直接用下标
        //
        // 本面板从行到状态层的**所有**寻址都是下标：`onPick(index)` / `onRemove(index)` /
        // `onMove(from, to)`，拖拽数学（ReorderableLazyState）也完全是「槽位 = 下标」
        // 的模型（见该文件的「行数学前提」）。id + 出现序号虽然也能唯一，却会在
        // 拖拽/插入后让 key 随内容变化 —— 行节点被重建，而落位补间的目标是**槽位**，
        // 两者语义立刻分叉。下标作为 key 唯一且恒稳定，与既有数学同源。
        //
        // 代价（如实记录）：插入/删除中间行时，后续行是「原地换内容」而不是
        // 「带位移的增删」。但队列编辑本来就是瞬时的列表语义（且拖拽有自己的
        // settle 补间），这里没有 `Modifier.animateItem()` 依赖 key 位移做动画，
        // 所以不存在观感回归。
        if (safeIndex > 0) {
            item(key = "played-header") { SectionLabel("已播放 · $safeIndex 首") }
            itemsIndexed(
                items = queue.take(safeIndex),
                key = { index, _ -> "played-$index" },
            ) { index, song ->
                SongRow(song = song, onClick = { onPick(index) })
            }
        }
        item(key = "current-header") { SectionLabel("正在播放") }
        item(key = "current-row") {
            val current = queue.getOrNull(safeIndex)
            if (current != null) {
                SongRow(
                    song = current,
                    playing = true,
                    wavePlaying = isPlaying,
                    onClick = { onPick(safeIndex) },
                )
            }
        }
        // 「接下来」段头：**必须在这个位置**——它下方的所有行都是可拖区段，
        // 且区段内不能有任何变高元素（见 KDoc 的段头位置说明）。
        item(key = "upcoming-header") {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = S.Gap20.dp, end = S.Gap20.dp, top = S.Gap12.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "接下来 · $upcomingCount 首",
                    color = glass.textTertiary, style = GlassText.SectionLabelMicro,
                )
                Spacer(Modifier.weight(1f))
                if (upcomingCount > 0) {
                    val clearInteraction = remember { MutableInteractionSource() }
                    // A4 破坏性色：「清空接下来」会丢掉整个待播段，与「全部播放」这类
                    // 常规动作用同一个强调色等于没有风险信号 → 读 glass.destructive
                    Text(
                        "清空",
                        color = glass.destructive, style = GlassText.CaptionMedium,
                        modifier = Modifier
                            .pressScale(clearInteraction, pressed = 0.92f)
                            .clickable(interactionSource = clearInteraction, indication = null, onClick = onClearUpNext)
                            // 读屏：可见文字只有「清空」，脱离上下文有歧义——
                            // 用 contentDescription 把**作用域**说清楚（只清「接下来」），
                            // 并补 role = Button（纯 Text + clickable 不会自动获得按钮角色）
                            .semantics {
                                this.role = Role.Button
                                this.contentDescription = SheetA11y.CLEAR_UP_NEXT
                            }
                            .padding(horizontal = 6.dp, vertical = 4.dp),
                    )
                }
            }
        }
        // ── 接下来段（可拖拽，懒加载）──
        //
        // key = **队内绝对下标**（`queueIndex` 而非段内下标）：本段是「接下来」的
        // 后半段，插入只会发生在当前曲之后（playNext 系列）或队尾（playLast 系列），
        // 用队内下标能保证「已播放段 + 接下来段」整份 key 空间不重叠、也不随段长变化。
        // 为什么不能用曲目 id：同上一段的 KDoc —— 队列允许重复曲目，id 会重复。
        if (upcomingCount > 0) {
            itemsIndexed(
                items = List(upcomingCount) { safeIndex + 1 + it },
                key = { _, queueIndex -> "upcoming-$queueIndex" },
            ) { upcomingIndex, queueIndex ->
                SongRow(
                    song = queue[queueIndex],
                    // 拖拽手势 + 让位/落位位移全挂在这一行的根节点上
                    modifier = reorder.rowModifier(upcomingIndex, dataVersion = reorderVersion),
                    // ⚠ 拖拽期间**不可点击**：长按起拖后松手会同时触发 click（Compose 的
                    // clickable 与 detectDragGesturesAfterLongPress 会各自收到事件）。
                    // 原实现同样有此问题但被「拖完通常不在同一行」掩盖；Lazy 下更容易误触，
                    // 这里显式在拖拽中忽略点击。
                    onClick = { if (reorder.dragFrom < 0) onPick(queueIndex) },
                    onRemoveClick = { onRemove(queueIndex) },
                )
            }
        }
    }
}

/**
 * 队列撤销浮条（面板底部）。
 *
 * ## 为什么它不在 LazyColumn 里
 * 「接下来」段必须一直延伸到列表末尾——段内出现任何变高元素（包括这条撤销条），
 * 拖拽的行数学（`列表偏移 = 首可见下标 × 行高`）立刻失效。这是 Lazy 化的硬约束。
 *
 * ## 为什么不做成覆盖在面板上的浮层
 * 覆盖会挡住列表最后一行（用户刚清空、正想看还剩下什么）。这里用**纵向追加**：
 * 它占面板底部的固定高度，列表在它上方滚动——不遮任何内容。
 */
@Composable
private fun QueueUndoBar(count: Int, onUndo: () -> Unit) {
    AnimatedVisibility(
        visible = count > 0,
        enter = androidx.compose.animation.expandVertically() + androidx.compose.animation.fadeIn(),
        exit = androidx.compose.animation.shrinkVertically() + androidx.compose.animation.fadeOut(),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = S.Gap20.dp, end = S.Gap20.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "已清空 $count 首",
                color = LocalGlassColors.current.textTertiary,
                style = GlassText.Caption,
            )
            Spacer(Modifier.weight(1f))
            val undoInteraction = remember { MutableInteractionSource() }
            // 热区 44dp（全 App 下限）：旧写法只有文字本体 + 4dp padding ≈ 36×24dp，
            // 而它是一个**限时 4 秒**的动作——窗口本来就短，再难点中等于没有撤销。
            // 用 sizeIn 而不是加大 padding：图标/文字视觉尺寸不变，只扩命中范围。
            Box(
                Modifier
                    .sizeIn(minWidth = 44.dp, minHeight = 44.dp)
                    .pressScale(undoInteraction, pressed = 0.92f)
                    .clickable(interactionSource = undoInteraction, indication = null, onClick = onUndo)
                    // 读屏：可见文字只有「撤销」，脱离上下文同样歧义
                    .semantics {
                        this.role = Role.Button
                        this.contentDescription = "撤销清空，恢复 $count 首待播曲目"
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "撤销",
                    // 用强调色而不是 destructive：撤销是**挽回**动作，与「删除」的风险
                    // 语义相反。红色在这里会让用户以为这一步也有风险。
                    color = auroraAccentColor(),
                    style = GlassText.CaptionMedium,
                )
            }
        }
    }
}

/**
 * 撤销失败回执（占用撤销条同一个脚注槽）。
 *
 * 为什么必须有：`AppState.undoClearUpNext()` 返回 false 表示现场已失效
 * （超时 / 队列在此期间被改过）。静默返回会让用户以为「撤销按钮坏了」——
 * 他点了，条子消失，队列却没恢复，没有任何解释。
 * 文案与 AppState KDoc 承诺的一致；用中性色而不是 destructive：
 * 这不是操作失败，是「窗口已过」的事实陈述。
 */
@Composable
private fun QueueUndoFailedNote() {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = S.Gap20.dp, end = S.Gap20.dp, top = S.Gap8.dp, bottom = S.Gap8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "队列已变化，无法撤销",
            color = LocalGlassColors.current.textTertiary,
            style = GlassText.Caption,
        )
    }
}
