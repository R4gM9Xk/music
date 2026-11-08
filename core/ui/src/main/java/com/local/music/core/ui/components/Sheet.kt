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
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import com.local.music.core.ui.theme.GlassMotion
import com.local.music.core.ui.theme.GlassText
import com.local.music.core.ui.theme.R
import com.local.music.core.ui.theme.LocalGlassColors
import com.local.music.core.ui.theme.LocalThemeFraction
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
    val onExitFinished = LocalSheetExitFinished.current
    // 只有「先真的可见过、现在退场跑完」才算一次退场结束：
    // - 初次组合就是 visible=false 的嵌套子面板（动作面板里 sub 未选中的那些）一上来
    //   就是 idle + target=false，若只看这个组合会立刻误报一次退场；
    // - 入场结束时 idle + target=true，也必须不报（否则宿主会把刚打开的面板清掉）。
    var hasBeenVisible by remember { mutableStateOf(false) }
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
            // 面板本体：吃掉自身点击，避免透传到 scrim；imePadding 让输入面板浮在键盘之上
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .imePadding()
                    // 语义清洗：这个 Box 存在的唯一目的是「吞掉打在面板上的点击」，
                    // 防止透传到下面的 scrim 把面板关掉。它是纯手势挡板，
                    // **有意无描述、且从无障碍树里摘掉**（clearAndSetSemantics {}）——
                    // 否则读屏会在整片面板内容之前多插一个「无描述的可点节点」。
                    // 注意 clearAndSetSemantics 只清本节点语义，子级（真正的菜单项）不受影响。
                    .clearAndSetSemantics { }
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                    ),
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
                    blur = GlassLevel.Md,
                ) {
                    // 模态面板是最高层级：在玻璃压底之上再加一层近不透明内衬（深浅主题各配，
                    // 浅色黑衬会蒙灰、压不住背景透入）。A1-03：按主题进度连续插值，
                    // 旧实现按 LocalIsDark 阈值黑白硬切，主题渐变中点面板衬底「啪」一下变色
                    val veil = lerp(
                        Color.Black.copy(alpha = 0.32f),
                        Color.White.copy(alpha = 0.62f),
                        LocalThemeFraction.current,
                    )
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
                                .padding(top = 10.dp, bottom = 6.dp),
                        )
                        // E5 面板高度：固定 420dp → 屏幕高度的 65%。
                        //
                        // 为什么不再用固定值：420dp 在小屏（≈640dp 高）上占 66%、在
                        // 一加13 这类 900+dp 大屏上只占 46%——同一个面板在大屏上「显得局促」
                        // 而小屏上「几乎铺满」，面板的呼吸感随设备漂移。按屏高取比例后，
                        // 「面板占屏幕多少」这个观感在设计上是常量。
                        //
                        // **下限 280dp 是必须的**：横屏时 screenHeightDp 只剩 360–420dp，
                        // 纯 65% 会算出 234–273dp，长队列（几百首）就只能在一条窄缝里滚动。
                        // 280dp 保证横屏下至少露出约 4 行，队列面板仍然可用。
                        //
                        // **不需要再手写上限**：外层 Column 在测量非 weight 子项时会递减
                        // 剩余空间（把手 4 + 标题 38 已占用，另有 navigationBarsPadding 与
                        // 内边距先行扣减），把「剩余可用高度」作为 mainAxisMax 传给本列。
                        // 于是横屏极小屏（360dp）上 280dp 会被父约束自动压到约 244dp，
                        // 面板不会被顶穿、也不会被 GlassSurface 的 clip 裁掉。
                        // 换句话说：**下限表达「别太矮」，上限由布局本身负责**——
                        // 手写 `coerceAtMost(screenHeight - 骨架)` 与父约束效果等价，
                        // 只是多一处要跟着改的魔数（已按 920/640/420/360dp 逐项核算）。
                        //
                        // 顺序要求：heightIn 必须作用在滚动**视口**上（排在 verticalScroll
                        // 之前）。反过来的话它约束的是滚动内容而不是视口，max 就不再是
                        // 「面板最多多高」而是「内容最多多高」，语义和实际效果都变了。
                        val screenHeight = LocalConfiguration.current.screenHeightDp
                        val contentMaxHeight = (screenHeight * 0.65f).dp.coerceAtLeast(280.dp)
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(max = contentMaxHeight)
                                .verticalScroll(scrollState),
                            content = content,
                        )
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
            .padding(horizontal = 6.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
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
    val glass = LocalGlassColors.current
    // 定位数学全程 Float（A2-04）：rowPx/labelPx 先 toInt 再乘索引会让截断误差随行数
    // 线性放大（66dp×2.75 密度每行丢 0.5px，500 行 ≈250px≈1.4 行）——乘完再取整
    val rowPx = with(density) { SongRowHeight.toPx() }
    // 段头标签（已播放/正在播放）近似高度：定位公式要计入，否则当前曲定位偏低
    val labelPx = with(density) { 34.dp.toPx() }
    // 空队列时 lastIndex=-1，coerceIn(0,-1) 会抛 IAE（冷启动默认屏=Player，权限拒/无音频即触发）
    val safeIndex = if (queue.isEmpty()) 0 else currentIndex.coerceIn(0, queue.lastIndex)
    // 长拖中自动切歌会推进 currentIndex：onMove 换算必须读实时值，否则松手提交时用的是
    // 拖拽起点的陈旧基准，会把「接下来」段内的相对位置搬到错误的队列绝对索引上
    val liveSafeIndex by rememberUpdatedState(safeIndex)
    LaunchedEffect(visible, currentIndex) {
        if (!visible) return@LaunchedEffect
        // 用户正在滚动/拖拽时跳过跟随（A2-03）：自动切歌不该把浏览中的视口无动画拽走，
        // 下一次 currentIndex 变化会重试；打开面板的首次定位不受影响（此时无人滚动）
        if (scrollState.isScrollInProgress) return@LaunchedEffect
        // 等面板完成布局（maxValue>0）后按行高定位
        withTimeoutOrNull(500) {
            while (scrollState.maxValue <= 0) withFrameNanos { }
        }
        // 当前曲上方内容 = 前段行 + 段头标签；目标停在当前曲上一行（无前段则顶到 0）
        val above = safeIndex * rowPx + (if (safeIndex > 0) 2 else 1) * labelPx
        val target = (above - rowPx).roundToInt().coerceIn(0, scrollState.maxValue)
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
                color = glass.textTertiary, style = GlassText.Body,
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
                color = glass.textTertiary, style = GlassText.SectionLabelMicro,
            )
            Spacer(Modifier.weight(1f))
            if (upcomingCount > 0) {
                val clearInteraction = remember { MutableInteractionSource() }
                // A4 破坏性色：这里原来用 accent（auroraAccentColor）——
                // 「清空接下来」会**不可撤销**地丢掉整个待播段，却与「全部播放」这类
                // 常规动作用了同一个强调色，等于没有任何风险信号。
                // 改读 glass.destructive（低饱和暖红，见 GlassColors.destructive 的推导）。
                //
                // 本轮范围说明：只改「清空接下来」这一处。删除歌单的确认行在
                // ActionsSheet.kt（别人写域），那些调用点走 SheetAction 新增的
                // `destructive = true` 形参；本文件只负责把参数实现出来。
                Text(
                    "清空",
                    color = glass.destructive, style = GlassText.CaptionMedium,
                    modifier = Modifier
                        .pressScale(clearInteraction, pressed = 0.92f)
                        .clickable(interactionSource = clearInteraction, indication = null, onClick = onClearUpNext)
                        // 读屏（无障碍 P0-4）：可见文字只有「清空」两个字，脱离视觉上下文时
                        // 读屏念出来是歧义的——「清空」什么？整个队列？搜索框？当前筛选？
                        // 它实际只清「接下来」这一段（当前曲与已播段不受影响），
                        // 所以这里用 contentDescription 把**作用域**说清楚，并补 role = Button
                        // （纯 Text + clickable 不会自动获得按钮角色，读屏不提示「双击激活」）。
                        // 注意 contentDescription 会**覆盖**子 Text 的文字，不再重复播报。
                        .semantics {
                            this.role = Role.Button
                            this.contentDescription = SheetA11y.CLEAR_UP_NEXT
                        }
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
