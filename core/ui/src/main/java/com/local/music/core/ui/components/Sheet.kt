package com.local.music.core.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.local.music.core.ui.theme.GlassMotion
import com.local.music.core.ui.theme.LocalGlassColors
import com.local.music.core.ui.theme.LocalIsDark
import com.local.music.core.ui.theme.auroraAccentColor
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

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
     */
    var exitAnimationId by mutableIntStateOf(0)
        private set

    /** 面板存在（含退场期）：返回键 / Chrome 门控 / 控制台让位统一读这里 */
    val isOpen: Boolean get() = content != null

    /**
     * 打开面板（同刻只保有一个：再 show 即原位替换，与旧单可见面板语义一致）。
     *
     * 已知取舍（P3-69）：面板**已打开**（visible=true）时再次 [show] 换内容是原位硬切——
     * visible 值不变，GlassSheet 的 MutableTransitionState 不产生状态变化，新旧内容之间
     * 不播任何过渡。退场中途重开则不同：visible 翻回 true 会从退场中间态反向播入场。
     * 要做「旧内容退场 → 新内容入场」需给 content 引入代际 key（新旧两棵子树并存各自
     * 动画），牵动宿主清理时机与嵌套子面板的退场信号语义，风险大于收益——保守保留硬切。
     * **需产品决策**：若要求二次打开必须播入场，应立项做 contentKey 方案，勿在本文件顺手改。
     */
    fun show(content: @Composable () -> Unit) {
        this.content = content
        visible = true
    }

    /** 请求关闭当前面板（返回键 / scrim 点击 / 动作完成统一走这里） */
    fun requestDismiss() {
        visible = false
    }

    /** 退场动画结束时由 GlassSheet 回传（宿主机制不外泄：仅 ModalSheet 接线） */
    internal fun notifyExitAnimationFinished() {
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
    val exitSignal = remember(modal) { { modal.notifyExitAnimationFinished() } }
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
        CompositionLocalProvider(LocalSheetExitFinished provides exitSignal) {
            content()
        }
    }
}

/**
 * 面板退场收尾信号：由 [ModalSheetHost] 在 content 外层提供，内层 [GlassSheet] 的
 * AnimatedVisibility 退场**动画结束**时回调。
 *
 * 为什么走 CompositionLocal 而不是给每个调用点加参数：GlassSheet 的直接调用点分散在
 * 各 feature 屏（PlayerScreen 等），而宿主清理只需要一份信号；用一个「宿主作用域内的
 * 环境回调」可以让既有调用点零改动（新增参数必须默认值、不能逼别人改文件，见写域约束）。
 * 默认空实现 = 非宿主场景（如二级面板自渲染、将来预览）静默降级。
 */
internal val LocalSheetExitFinished = staticCompositionLocalOf<() -> Unit> { {} }

/**
 * 退场收尾信号的活性兜底余量（ms，约 4 帧@60Hz）：只在「本次关闭没有任何子面板跑过退场
 * 动画」（信号永不到达）时用于收场，正常路径由真动画结束信号触发。**这是兜底不是精度依赖**：
 * 若把它当主路径（旧实现），就会因起表差帧 / 掉帧而截断退场末帧。
 */
private const val EXIT_FALLBACK_MS = 64L

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
    // 用 MutableTransitionState 而不是 visible 布尔 + AnimatedVisibility：那是唯一能
    // 「知道退场动画真的跑完了」的公开入口（isIdle 且 targetState=false）。宿主据此清 content，
    // 取代「按 tweenPage 时长 delay」——delay 与动画不同源（起表差一帧 / 退场掉帧 /
    // 动效强度乘数变化），会先于动画结束，导致退场最后 1-2 帧被截断。
    // 初值取当前 visible（与 AnimatedVisibility(visible) 重载内部一致）：面板首次组合时
    // visible 已是 true，两态相等即 idle → 打开时不播入场、直接呈现，保持既有观感不变。
    val visibilityState = remember { MutableTransitionState(visible) }
    visibilityState.targetState = visible
    val onExitFinished = LocalSheetExitFinished.current
    // 只有「先真的可见过、现在退场跑完」才算一次退场结束：
    // - 初次组合就是 visible=false 的嵌套子面板（动作面板里 sub 未选中的那些）一上来
    //   就是 idle + target=false，若只看这个组合会立刻误报一次退场；
    // - 入场结束时 idle + target=true，也必须不报（否则宿主会把刚打开的面板清掉）。
    var hasBeenVisible by remember { mutableStateOf(visible) }
    LaunchedEffect(visibilityState.isIdle, visibilityState.currentState, visibilityState.targetState) {
        if (!visibilityState.isIdle) {
            hasBeenVisible = true
        } else if (hasBeenVisible && !visibilityState.targetState) {
            hasBeenVisible = false
            onExitFinished()
        }
    }
    AnimatedVisibility(
        visibleState = visibilityState,
        enter = fadeIn(GlassMotion.tweenPage()) +
            slideInVertically(GlassMotion.tweenPage()) { it },
        exit = fadeOut(GlassMotion.tweenPage()) +
            slideOutVertically(GlassMotion.tweenPage()) { it },
    ) {
        Box(
            modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.35f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                ),
        ) {
            // 面板本体：吃掉自身点击，避免透传到 scrim；imePadding 让输入面板浮在键盘之上
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .imePadding()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                    ),
            ) {
                GlassSurface(
                    modifier = Modifier.fillMaxWidth(),
                    level = GlassLevel.Md,
                    shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                    blur = GlassLevel.Md,
                ) {
                    // 模态面板是最高层级：在玻璃压底之上再加一层近不透明内衬（深浅主题各配，
                    // 浅色黑衬会蒙灰、压不住背景透入）
                    val veil = if (LocalIsDark.current) Color.Black.copy(alpha = 0.32f) else Color.White.copy(alpha = 0.62f)
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .background(veil)
                            .navigationBarsPadding()
                            .padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 16.dp),
                    ) {
                        // 顶部拖拽把手
                        Box(
                            Modifier
                                .align(Alignment.CenterHorizontally)
                                .width(32.dp)
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(LocalGlassColors.current.textTertiary.copy(alpha = 0.55f)),
                        )
                        Text(
                            title,
                            color = LocalGlassColors.current.textSecondary,
                            fontSize = 12.sp,
                            modifier = Modifier
                                .align(Alignment.CenterHorizontally)
                                .padding(top = 10.dp, bottom = 6.dp),
                        )
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(max = 420.dp)
                                .verticalScroll(scrollState),
                            content = content,
                        )
                    }
                }
            }
        }
    }
}

/** 面板动作行：32dp 玻璃图标块 + 主副标题；emphasized = 选中态（强调色） */
@Composable
fun SheetAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    emphasized: Boolean = false,
) {
    val glass = LocalGlassColors.current
    val accent = auroraAccentColor()
    val interactionSource = remember { MutableInteractionSource() }
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .pressScale(interactionSource, pressed = 0.98f)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(glass.md),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon, null, Modifier.size(18.dp),
                tint = if (emphasized) accent else LocalGlassColors.current.textPrimary,
            )
        }
        Column(Modifier.weight(1f)) {
            Text(
                label,
                color = if (emphasized) accent else LocalGlassColors.current.textPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(subtitle, color = LocalGlassColors.current.textTertiary, fontSize = 11.5.sp)
            }
        }
    }
}

/** 段头小标签（队列面板内：已播放 / 正在播放），与行内文案左对齐 */
@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        color = LocalGlassColors.current.textTertiary,
        fontSize = 11.sp,
        letterSpacing = 0.3.sp,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 4.dp),
    )
}

/**
 * 播放队列面板（两段式）：已播段（普通行）/ 正在播放（波形高亮、不可拖不可删）
 * / 接下来（可拖拽重排、可移除、可清空）。
 * 行序 = 播放器队列平铺序；拖拽域仅「接下来」段（onMove 索引已换算回队列空间，
 * 全部 > 当前曲索引 → 当前曲与引擎窗口不受影响）。
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
) {
    val scrollState = rememberScrollState()
    val density = LocalDensity.current
    val accent = auroraAccentColor()
    val glass = LocalGlassColors.current
    val rowPx = with(density) { SongRowHeight.toPx() }.toInt()
    // 段头标签（已播放/正在播放）近似高度：定位公式要计入，否则当前曲定位偏低
    val labelPx = with(density) { 34.dp.toPx() }.toInt()
    // 空队列时 lastIndex=-1，coerceIn(0,-1) 会抛 IAE（冷启动默认屏=Player，权限拒/无音频即触发）
    val safeIndex = if (queue.isEmpty()) 0 else currentIndex.coerceIn(0, queue.lastIndex)
    // 长拖中自动切歌会推进 currentIndex：onMove 换算必须读实时值，否则松手提交时用的是
    // 拖拽起点的陈旧基准，会把「接下来」段内的相对位置搬到错误的队列绝对索引上
    val liveSafeIndex by rememberUpdatedState(safeIndex)
    LaunchedEffect(visible, currentIndex) {
        if (!visible) return@LaunchedEffect
        // 等面板完成布局（maxValue>0）后按行高定位
        withTimeoutOrNull(500) {
            while (scrollState.maxValue <= 0) withFrameNanos { }
        }
        // 当前曲上方内容 = 前段行 + 段头标签；目标停在当前曲上一行（无前段则顶到 0）
        val above = safeIndex * rowPx + (if (safeIndex > 0) 2 else 1) * labelPx
        val target = (above - rowPx).coerceIn(0, scrollState.maxValue)
        scrollState.scrollTo(target)
    }
    GlassSheet(
        visible = visible, onDismiss = onDismiss,
        title = "播放队列 · ${queue.size} 首", scrollState = scrollState,
    ) {
        if (queue.isEmpty()) {
            // 空队列空态：旧实现先画「正在播放」段头、再在取行时 return@GlassSheet，
            // 结果是一个孤立的「正在播放」标签 + 大片空白（曲库未装载/权限被拒时点队列即可见）。
            Text(
                "队列是空的 · 去曲库挑一首开始播放",
                color = glass.textTertiary, fontSize = 13.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
            )
            return@GlassSheet
        }
        if (safeIndex > 0) {
            SectionLabel("已播放 · $safeIndex 首")
            queue.take(safeIndex).forEachIndexed { index, song ->
                SongRow(song = song, onClick = { onPick(index) })
            }
        }
        SectionLabel("正在播放")
        SongRow(
            song = queue.getOrNull(safeIndex) ?: return@GlassSheet,
            playing = true,
            wavePlaying = isPlaying,
            onClick = { onPick(safeIndex) },
        )
        val upcomingCount = queue.size - safeIndex - 1
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "接下来 · $upcomingCount 首",
                color = glass.textTertiary, fontSize = 11.sp, letterSpacing = 0.3.sp,
            )
            Spacer(Modifier.weight(1f))
            if (upcomingCount > 0) {
                val clearInteraction = remember { MutableInteractionSource() }
                Text(
                    "清空",
                    color = accent, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .pressScale(clearInteraction, pressed = 0.92f)
                        .clickable(interactionSource = clearInteraction, indication = null, onClick = onClearUpNext)
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                )
            }
        }
        if (upcomingCount > 0) {
            // 内容版本指纹 = 「行下标 → 队列索引」这段映射的基准。映射是
            // safeIndex + 1 + upcomingIndex，所以基准就是 safeIndex。
            //
            // 缺陷：行槽与手势闭包都按 upcomingIndex 冻结，提交时 onMove 才把 (from, to)
            // 换算成队列绝对索引；长拖期间自动切歌推进 currentIndex → 「接下来」段整体
            // 位移一格 → 同一批下标指向的歌整体错位 → 松手提交的是错歌（不可撤销）。
            // 传版本让 ReorderableColumn 重建行槽并重启手势，语义 =
            // 「拖拽中切歌则本次拖拽作废」。
            //
            // 为什么不掺「段内顺序」（段首曲 id / 队列长度）：段内重排的 onMove 提交本身
            // 就会改变段内顺序，若把它算进版本，提交那一刻版本就变 → 槽位重建 + 手势重启
            // 顺手取消掉刚落位的补间（第 4 项直接失效）。这里只取「当前曲索引 + 当前曲 id」
            // ——段内重排只动 > safeIndex 的区间，这两者都不变；而自动切歌、引擎队列回流
            // （当前曲被替换）都会变，覆盖全部会导致错位的情形。
            //
            // 代价：位移那一帧拖拽视觉中断（行回槽位、需重新长按）。取舍理由：正在自动
            // 切歌时本就不该拖队列，丢一次手势远比提交一次错位移动安全。
            val upcomingVersion = safeIndex to queue.getOrNull(safeIndex)?.id
            ReorderableColumn(
                count = upcomingCount,
                rowHeight = SongRowHeight,
                scrollState = scrollState,
                onMove = { from, to -> onMove(from + liveSafeIndex + 1, to + liveSafeIndex + 1) },
                autoscroll = true,
                dataVersion = upcomingVersion,
            ) { upcomingIndex ->
                val queueIndex = safeIndex + 1 + upcomingIndex
                SongRow(
                    song = queue[queueIndex],
                    onClick = { onPick(queueIndex) },
                    onRemoveClick = { onRemove(queueIndex) },
                )
            }
        }
    }
}
