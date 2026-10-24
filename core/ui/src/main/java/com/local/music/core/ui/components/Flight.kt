package com.local.music.core.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import com.local.music.core.ui.state.GlassScreen
import com.local.music.core.ui.theme.GlassMotion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** 飞行元素键：迷你条源 mini:*，播放页目标 player:* */
object FlightKeys {
    const val MiniCover = "mini:cover"
    const val MiniGlass = "mini:glass"
    const val MiniTitle = "mini:title"
    const val MiniArtist = "mini:artist"
    const val MiniPlay = "mini:play"
    const val MiniNext = "mini:next"
    const val MiniNav = "mini:nav"
    const val PlayerCover = "player:cover"
    const val PlayerGlass = "player:glass"
    const val PlayerTitle = "player:title"
    const val PlayerArtist = "player:artist"
    const val PlayerPlay = "player:play"
    const val PlayerNext = "player:next"

    /** 几何闸门：app 根布局（恒定）与播放页根布局（进入首帧可能与 Chrome 卸载竞态） */
    const val AppRoot = "app:root"
    const val PlayerRoot = "player:root"

    val miniKeys = listOf(MiniGlass, MiniCover, MiniTitle, MiniArtist, MiniPlay, MiniNext, MiniNav)
    val playerKeys = listOf(PlayerGlass, PlayerCover, PlayerTitle, PlayerArtist, PlayerPlay, PlayerNext)
}

/**
 * 播放页共享元素飞行动效状态机（GlassApp 持有并经 [LocalPlayerFlight] 下发）。
 * - t: 0 = 迷你条态，1 = 播放页态；进入 0→1，退出 1→0，透明度公式进出共用天然镜像
 * - 飞行几何一律读 [snapshot]（起飞瞬间冻结，防飞行中切歌/离场组合失效）；
 *   [rects] 实时注册表仅在等待对端就绪时使用
 * - 直线平移 = 端点 rect 边缘线性插值（中心走直线、尺寸线性增长）；
 *   R角与尺寸同一 t 同帧插值，面板真改尺寸不做纹理缩放
 */
class PlayerFlight(private val scope: CoroutineScope) {
    enum class Phase { Idle, Entering, Exiting }

    /** 播放页视觉模式：决定退出时封面是否参与飞行 */
    enum class VisualMode { Cover, Lyrics }

    var phase by mutableStateOf(Phase.Idle)
        private set

    /** 播放页当前视觉模式（PlayerScreen 按 showLyrics 回写） */
    var visualMode by mutableStateOf(VisualMode.Cover)

    /** 进入飞行起点的导航胶囊选中项（覆盖层渐隐替身用） */
    var entryNavSelected by mutableStateOf(0)

    /** 进入播放页暂落封面模式（瞬态；手势/点击封面即清除，不写持久化 lyricEnabled） */
    var entryShowCover by mutableStateOf(false)

    /** 替身已接手（对端 rect 快照完成），真身从此按公式隐身 */
    var started by mutableStateOf(false)
        private set

    /** 起飞瞬间的歌曲快照：飞行中切歌不换图 */
    var songSnapshot by mutableStateOf<DemoSong?>(null)
        private set

    /** 飞行进度（0=迷你条态 1=播放页态）。**仅供读取**：外部 snapTo/animateTo 会绕过
     *  phase/started/snapshot 的协同时序破坏状态机——驱动只发生在 beginEntry/beginExit 内部 */
    val t = Animatable(0f)

    /** 实时几何注册表（root 坐标），trackFlight 持续回填 */
    val rects = mutableStateMapOf<String, Rect>()

    /** 起飞瞬间冻结的两端 rect，飞行全程只读 */
    var snapshot by mutableStateOf<Map<String, Rect>?>(null)
        private set

    val isActive: Boolean get() = phase != Phase.Idle

    /** 非共享组（图中没有的按键等）：原地由虚到实；Idle 时必须全显（t 停在 0/1 不代表开合度） */
    fun softAlpha(): Float = when (phase) {
        Phase.Idle -> 1f
        else -> ((t.value - 0.15f) / 0.85f).coerceIn(0f, 1f)
    }

    /** 播放页共享真身：进入飞行期让位替身（等待期即隐身），收尾 12% 交叉淡入；退出等待期仍可见 */
    fun playerSharedAlpha(): Float = when {
        phase == Phase.Idle -> 1f
        phase == Phase.Entering -> if (!started) 0f else ((t.value - 0.88f) / 0.12f).coerceIn(0f, 1f)
        else -> if (started) 0f else 1f
    }

    /** 迷你条共享真身：进入期 Chrome 已卸载（不参与）；退出期最后 12% 淡入接位 */
    fun miniSharedAlpha(): Float = when {
        phase == Phase.Idle -> 1f
        phase == Phase.Entering -> 0f
        else -> if (!started) 0f else ((0.12f - t.value) / 0.12f).coerceIn(0f, 1f)
    }

    /** 导航胶囊：进入期由覆盖层替身负责（Chrome 已卸载）；退出期与迷你条真身同窗淡入接位 */
    fun navAlpha(): Float = when (phase) {
        Phase.Idle -> 1f
        Phase.Entering -> 1f - t.value
        else -> ((0.12f - t.value) / 0.12f).coerceIn(0f, 1f)
    }

    /**
     * 迷你条 → 播放页（落封面模式）。此刻 Chrome 可见、迷你条 rect 可用先冻结；
     * 随后 Chrome 立即退出布局（播放页从第一帧即全屏几何，落点精确无跳变），
     * 播放页 rect 到齐（约一帧）后起飞。导航胶囊由覆盖层渐隐替身补位。
     */
    fun beginEntry(song: DemoSong, navSelected: Int, navigate: (GlassScreen) -> Unit) {
        if (phase != Phase.Idle) return
        val mini = rectsSnapshot(FlightKeys.miniKeys) ?: run {
            navigate(GlassScreen.Player)
            return
        }
        songSnapshot = song
        entryNavSelected = navSelected
        entryShowCover = true
        phase = Phase.Entering
        started = false
        // 等待期就先挂 mini-only 快照：替身按 t=0 几何原地绘制迷你条（Chrome 此刻已卸载，
        // 不挂的话替身要等 player rects 到齐才出现，起点有 1~N 帧空窗）
        snapshot = mini
        navigate(GlassScreen.Player)
        scope.launch {
            if (awaitRects(FlightKeys.playerKeys) == null) {
                // 对端 rect 超时未到：放弃飞行，播放页已按通用过渡呈现
                phase = Phase.Idle
                entryShowCover = false // 中止不得残留封面模式，否则歌词偏好被卡住
                songSnapshot = null
                snapshot = null
                return@launch
            }
            // 几何闸门：等播放页根布局达到全屏（Chrome 卸载与 AnimatedContent 首帧测量存在竞态，
            // 中间态会出现半高控制台/零矩形），就绪后**重新快照**（等待期间 rect 已更新）
            val settled = withTimeoutOrNull(600) {
                while (true) {
                    val app = rects[FlightKeys.AppRoot]
                    val root = rects[FlightKeys.PlayerRoot]
                    if (app != null && root != null && root.height >= app.height * 0.95f) {
                        break
                    }
                    withFrameNanos { }
                }
                true
            } ?: false
            if (!settled || phase != Phase.Entering) {
                phase = Phase.Idle
                entryShowCover = false
                songSnapshot = null
                snapshot = null
                return@launch
            }
            // 起飞前重快照：entryShowCover 清除会触发 modeT 500ms 动画（封面 rect 移动中），
            // 直接采集会冻结移动中的几何——复用 awaitRects 双帧一致判稳，采稳定后的 rect；
            // 期间飞行被取消（phase 已非 Entering，如根尺寸变化）或判稳超时 → 走既有中止路径
            val playerFinal = awaitRects(FlightKeys.playerKeys)
            if (playerFinal == null || phase != Phase.Entering) {
                phase = Phase.Idle
                entryShowCover = false
                songSnapshot = null
                snapshot = null
                return@launch
            }
            snapshot = mini + playerFinal
            started = true
            t.snapTo(0f)
            t.animateTo(1f, GlassMotion.tweenFlight())
            phase = Phase.Idle
            snapshot = null
        }
    }

    /**
     * 播放页 → 迷你条（收起键/系统返回共用；flight 忙时静默忽略）。
     * 封面是否飞行按 [visualMode]：歌词模式封面原地淡出，其余照常缩回。
     * 播放页 rect 在 Chrome 恢复组合**之前**同步冻结（全屏几何）；
     * Chrome 随 phase=Exiting 立即恢复（迷你条区域随后由真身按曲线淡入）。
     * t≤0.15 时翻回 [GlassScreen.Library]（此时播放页非共享组已全透明）。
     */
    fun beginExit(song: DemoSong, navigate: (GlassScreen) -> Unit) {
        if (phase != Phase.Idle) return
        // 两端 rect 同步冻结：播放页此刻为全屏几何；迷你条为 Chrome 卸载时定格的正确位置。
        // 快照/started/phase 必须在同一同步块完成——Chrome 恢复组合的首帧替身即就位，
        // 不留「真身被压缩布局」的中间帧（否则收起起点会跳一下）。
        val player = rectsSnapshot(FlightKeys.playerKeys) ?: run {
            navigate(GlassScreen.Library)
            return
        }
        val mini = rectsSnapshot(FlightKeys.miniKeys) ?: run {
            navigate(GlassScreen.Library)
            return
        }
        songSnapshot = song
        phase = Phase.Exiting
        snapshot = player + mini
        started = true
        scope.launch {
            t.snapTo(1f)
            val flip = launch {
                snapshotFlow { t.value }.first { it <= 0.15f }
                navigate(GlassScreen.Library)
            }
            t.animateTo(0f, GlassMotion.tweenFlight())
            flip.cancel()
            navigate(GlassScreen.Library) // 兜底翻页（t 未到 0.15 的极端情况）
            phase = Phase.Idle
            snapshot = null
        }
    }

    /**
     * 根尺寸变化失效（旋转/分屏/自由窗口——manifest configChanges 吞掉配置变更，组合不重建）：
     * - 飞行进行中：立即取消。各 alpha 均从 phase/t 派生，置 Idle 即复原；中止三件套
     *   snapshot/entryShowCover/songSnapshot 同步清空，不留悬挂瞬态
     * - 无论是否在飞行都清空 rect 注册表：keepOnDispose 冻结的迷你条 rect 与活动
     *   snapshot 在新尺寸下全部视为陈旧，下次 trackFlight 重新回填 / awaitRects 重新等待，
     *   杜绝替身飞向旧坐标
     */
    fun onRootSizeChanged() {
        if (phase != Phase.Idle) {
            phase = Phase.Idle
            entryShowCover = false
            songSnapshot = null
            snapshot = null
        }
        rects.clear()
    }

    private fun rectsSnapshot(keys: List<String>): Map<String, Rect>? =
        keys.mapNotNull { k -> rects[k]?.let { k to it } }.toMap()
            .takeIf { it.size == keys.size }

    /** 等一组 rect 全部注册**且连续两帧一致**（首帧布局可能仍是旧几何，如 Chrome 卸载前的压缩框）；
     *  超时放弃飞行走兜底切屏 */
    private suspend fun awaitRects(keys: List<String>): Map<String, Rect>? =
        withTimeoutOrNull(600) {
            var last: Map<String, Rect>? = null
            var stable: Map<String, Rect>? = null
            while (stable == null) {
                val cur = keys.mapNotNull { k -> rects[k]?.let { k to it } }.toMap()
                // 全部注册且与上一帧一致（双帧判稳）才采纳；首帧布局可能仍是旧几何
                if (cur.size == keys.size && cur == last) {
                    stable = cur
                } else {
                    if (cur.size == keys.size) last = cur else last = null
                    withFrameNanos { }
                }
            }
            stable
        }
}

val LocalPlayerFlight = compositionLocalOf<PlayerFlight?> { null }

/**
 * 元素挂载点：把自身 root 坐标持续回填进 [PlayerFlight.rects]。
 * 默认离场即清除（防止陈旧几何污染下一次飞行）；迷你条元素传 keepOnDispose=true——
 * Chrome 卸载后退出飞行仍需其冻结位置（tab 几何在会话内不变）。
 */
@Composable
fun Modifier.trackFlight(key: String, keepOnDispose: Boolean = false): Modifier {
    val flight = LocalPlayerFlight.current ?: return this
    if (!keepOnDispose) {
        DisposableEffect(flight, key) {
            onDispose { flight.rects.remove(key) }
        }
    }
    return onGloballyPositioned { coords ->
        flight.rects[key] = coords.boundsInRoot()
    }
}
