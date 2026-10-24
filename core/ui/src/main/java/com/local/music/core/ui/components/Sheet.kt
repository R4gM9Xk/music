package com.local.music.core.ui.components

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.local.music.core.ui.theme.GlassIcons
import com.local.music.core.ui.theme.GlassMotion
import com.local.music.core.ui.theme.LocalGlassColors
import com.local.music.core.ui.theme.LocalIsDark
import com.local.music.core.ui.theme.auroraAccentColor
import kotlinx.coroutines.delay
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
 * 退场动画时长后 [ModalSheetHost] 清空 content → 面板卸载、让位解除。
 */
class ModalState {
    /** 当前面板内容：打开→组合；退场动画结束后清空→卸载（期间保持非 null 维持让位语义） */
    var content: (@Composable () -> Unit)? by mutableStateOf(null)
        private set

    /** 可见标志：false = 退场中（退场动画由 GlassSheet 的 AnimatedVisibility 播放） */
    var visible by mutableStateOf(false)
        private set

    /** 面板存在（含退场期）：返回键 / Chrome 门控 / 控制台让位统一读这里 */
    val isOpen: Boolean get() = content != null

    /** 打开面板（同刻只保有一个：再 show 即原位替换，与旧单可见面板语义一致） */
    fun show(content: @Composable () -> Unit) {
        this.content = content
        visible = true
    }

    /** 请求关闭当前面板（返回键 / scrim 点击 / 动作完成统一走这里） */
    fun requestDismiss() {
        visible = false
    }

    /** 退场动画结束后由 [ModalSheetHost] 清空内容（宿主机制不外泄） */
    internal fun clearContent() {
        content = null
    }
}

val LocalModalState = compositionLocalOf { ModalState() }

/**
 * 模态面板宿主渲染点（GlassApp 根层、内容区 hazeSource 兄弟位置调用）：
 * - content 非空即渲染；面板经自身参数接 [ModalState.visible] 驱动 GlassSheet 进出场动画
 * - 退场动画时长后清空 content（isOpen 归 false，Chrome/控制台让位解除）：
 *   中途重开（visible 翻回 true）取消本次清理，与旧「退场中途重开撤销待注销注册」逐帧等价
 */
@Composable
fun ModalSheetHost(modal: ModalState) {
    LaunchedEffect(modal.visible) {
        if (!modal.visible) {
            // 退场时长与 GlassSheet 的 AnimatedVisibility exit 同源（tweenPage）
            delay(GlassMotion.tweenPage<Float>().durationMillis.toLong())
            modal.clearContent()
        }
    }
    val content = modal.content
    if (content != null) {
        content()
    }
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
    AnimatedVisibility(
        visible = visible,
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
    // 拖拽手势闭包被 ReorderableList 的 pointerInput 冻结（键不含数据版本，长拖期间不重启），
    // 长拖中自动切歌会推进 currentIndex：经 rememberUpdatedState 让换算读实时值，
    // 避免松手提交时用拖拽起点的陈旧 safeIndex 提交错误移动
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
            ReorderableColumn(
                count = upcomingCount,
                rowHeight = SongRowHeight,
                scrollState = scrollState,
                onMove = { from, to -> onMove(from + liveSafeIndex + 1, to + liveSafeIndex + 1) },
                autoscroll = true,
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
