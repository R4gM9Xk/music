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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.local.music.core.ui.theme.GlassIcons
import com.local.music.core.ui.theme.GlassMotion
import com.local.music.core.ui.theme.LocalAuroraPalette
import com.local.music.core.ui.theme.LocalGlassColors

/**
 * 模态面板全局状态：GlassSheet 打开 / 关闭时注册注销，宿主（GlassApp）据此——
 * - 隐藏底部 Chrome（面板画在页面内容区，scrim 盖不住同级后绘制的 Chrome，只能主动让位）
 * - 返回键优先关闭面板而不是离开页面
 */
class ModalState {
    var openCount by mutableIntStateOf(0)
        private set

    /** 当前打开面板的关闭回调（同屏同时最多一个可见面板持有） */
    private var dismiss: (() -> Unit)? = null

    val isOpen: Boolean get() = openCount > 0

    /** 请求关闭当前面板（返回键优先走这里） */
    fun requestDismiss() {
        dismiss?.invoke()
    }

    internal fun register(onDismiss: () -> Unit) {
        openCount++
        dismiss = onDismiss
    }

    internal fun unregister(onDismiss: () -> Unit) {
        openCount = (openCount - 1).coerceAtLeast(0)
        if (dismiss === onDismiss) dismiss = null
    }
}

val LocalModalState = compositionLocalOf { ModalState() }

/**
 * 底部玻璃动作面板：半透明 scrim + 顶部圆角玻璃卡，360ms 弹性上滑入场。
 * 设置强度选择 / 播放队列 / 更多操作 / 新建歌单 的共用容器。
 */
@Composable
fun GlassSheet(
    visible: Boolean,
    onDismiss: () -> Unit,
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val modal = LocalModalState.current
    // 打开时注册到全局模态状态（含退场动画结束后的注销）
    DisposableEffect(visible) {
        if (visible) modal.register(onDismiss)
        onDispose {
            if (visible) modal.unregister(onDismiss)
        }
    }
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
            // 面板本体：吃掉自身点击，避免透传到 scrim
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                    ),
            ) {
                GlassSurface(
                    modifier = Modifier.fillMaxWidth().glassHaze(GlassLevel.Md),
                    level = GlassLevel.Md,
                    shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                ) {
                    // 模态面板是最高层级：在玻璃压底之上再加深一层，近似不透明
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .background(Color.Black.copy(alpha = 0.32f))
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
                                .verticalScroll(rememberScrollState()),
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
    val palette = LocalAuroraPalette.current
    val glass = LocalGlassColors.current
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
                tint = if (emphasized) palette.seed3 else LocalGlassColors.current.textPrimary,
            )
        }
        Column(Modifier.weight(1f)) {
            Text(
                label,
                color = if (emphasized) palette.seed3 else LocalGlassColors.current.textPrimary,
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

/** 播放队列面板：当前队列列表，当前行高亮 + 波形，点击切歌（不重置队列） */
@Composable
fun QueueSheet(
    visible: Boolean,
    onDismiss: () -> Unit,
    queue: List<DemoSong>,
    currentIndex: Int,
    onPick: (Int) -> Unit,
) {
    GlassSheet(visible = visible, onDismiss = onDismiss, title = "当前队列 · ${queue.size} 首") {
        queue.forEachIndexed { index, song ->
            SongRow(
                song = song,
                playing = index == currentIndex,
                onClick = { onPick(index) },
            )
        }
    }
}
