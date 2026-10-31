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
import kotlinx.coroutines.Job
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

    /** 进入飞行时的来源屏（退出飞行回到这里，而不是永远回曲库） */
    var entrySource by mutableStateOf<GlassScreen?>(null)
        private set

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

    /**
     * 在跑的两条飞行动画协程（entry / exit 各一条，**不能只留一个引用**：
     * 中止后新的 entry 与残留的 exit 语义不同，共用一份引用会漏取消）。
     * 取消是「中止」的唯一可靠手段——旧实现只翻 phase，协程醒来后照旧把尾部
     * `navigate(Library)` 执行完，等于把用户从新页面拽回曲库。
     */
    private var entryJob: Job? = null
    private var exitJob: Job? = null

    /**
     * 代际计数：每次 begin / 中止都 +1。协程内所有「有副作用」的节点（navigate、
     * 清状态、覆盖 snapshot）先比对捕获时的代际，不符即静默退出。
     * 只靠 phase 判定不够——中止后新的 beginEntry 会把 phase 又置回 Entering，
     * 残留协程的 `phase != Entering` 守卫随即失真，会用旧几何覆盖新快照。
     */
    private var generation = 0

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
    fun beginEntry(song: DemoSong, navSelected: Int, navigate: (GlassScreen) -> Unit, source: GlassScreen? = null) {
        if (phase != Phase.Idle) return
        val mini = rectsSnapshot(FlightKeys.miniKeys) ?: run {
            navigate(GlassScreen.Player)
            return
        }
        // 先掐掉可能残留的旧协程（上一轮中止后仍在跑的 exit/entry），再开新代际
        cancelJobs()
        val gen = ++generation
        songSnapshot = song
        entryNavSelected = navSelected
        entrySource = source
        entryShowCover = true
        phase = Phase.Entering
        started = false
        // **等待期 t 必须归零**：beginExit 的忙时中止分支只翻 phase 不复位 t，残留 t（如 0.4）
        // 会让等待期的替身样式按半程 lerp 渲染（圆角/字号/播放钮圆底/导航胶囊全部半形态），
        // 起飞瞬间 snapTo(0) 再全部跳变。这里用代际守卫的协程复位，不阻塞当前同步块。
        scope.launch {
            if (gen != generation) return@launch
            t.snapTo(0f)
        }
        // 等待期就先挂 mini-only 快照：替身按 t=0 几何原地绘制迷你条（Chrome 此刻已卸载，
        // 不挂的话替身要等 player rects 到齐才出现，起点有 1~N 帧空窗）
        snapshot = mini
        navigate(GlassScreen.Player)
        entryJob = scope.launch {
            if (awaitRects(FlightKeys.playerKeys) == null) {
                // 对端 rect 超时未到：放弃飞行，播放页已按通用过渡呈现
                if (gen != generation) return@launch // 已被中止/被新飞行接管：不碰状态
                phase = Phase.Idle
                entryShowCover = false // 中止不得残留封面模式，否则歌词偏好被卡住
                songSnapshot = null
                snapshot = null
                started = false
                return@launch
            }
            if (gen != generation) return@launch
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
            if (!settled || gen != generation || phase != Phase.Entering) {
                if (gen != generation) return@launch
                phase = Phase.Idle
                entryShowCover = false
                songSnapshot = null
                snapshot = null
                started = false
                return@launch
            }
            // 起飞前重快照：entryShowCover 清除会触发 modeT 500ms 动画（封面 rect 移动中），
            // 直接采集会冻结移动中的几何——复用 awaitRects 双帧一致判稳，采稳定后的 rect；
            // 期间飞行被取消（代际已变 / phase 已非 Entering，如根尺寸变化）或判稳超时 → 走既有中止路径
            val playerFinal = awaitRects(FlightKeys.playerKeys)
            if (playerFinal == null || gen != generation || phase != Phase.Entering) {
                if (gen != generation) return@launch
                phase = Phase.Idle
                entryShowCover = false
                songSnapshot = null
                snapshot = null
                started = false
                return@launch
            }
            snapshot = mini + playerFinal
            started = true
            t.snapTo(0f)
            t.animateTo(1f, GlassMotion.tweenFlight())
            if (gen != generation) return@launch // 动画期间被中止：收尾状态由中止方负责
            phase = Phase.Idle
            snapshot = null
        }
    }

    /**
     * 播放页 → 迷你条（收起键/系统返回共用）。
     * 封面是否飞行按 [visualMode]：歌词模式封面原地淡出，其余照常缩回。
     * 播放页 rect 在 Chrome 恢复组合**之前**同步冻结（全屏几何）；
     * Chrome 随 phase=Exiting 立即恢复（迷你条区域随后由真身按曲线淡入）。
     * t≤0.15 时翻回 [GlassScreen.Library]（此时播放页非共享组已全透明）。
     *
     * **忙时不再静默吞掉**：旧实现在 phase != Idle 时直接 return，于是进入/退出飞行的
     * ~500ms 窗口内按返回键（或点收起键）什么都不发生——用户看到的是「返回键失灵」。
     * 现在改为先中止在跑的飞行（取消协程 + 清瞬态，与根尺寸变化同一套清理），再直接翻页：
     * 手势的意图是「离开播放页」，动画只是装饰，不该因为动画在跑就丢掉这个意图。
     */
    /** 退出飞行的目标屏：优先回进入时的来源屏（详情/搜索），否则回曲库 */
    private fun exitTarget(): GlassScreen = entrySource ?: GlassScreen.Library

    fun beginExit(song: DemoSong, navigate: (GlassScreen) -> Unit) {
        val target = exitTarget()
        if (phase != Phase.Idle) {
            // 中止在跑的 entry/exit，瞬态清干净后直接翻页（不重入共享元素动画：
            // 几何已在新页面的途中，再来一段飞行只会更乱）
            cancelJobs()
            generation++
            phase = Phase.Idle
            entryShowCover = false
            songSnapshot = null
            snapshot = null
            started = false
            entrySource = null
            navigate(target)
            return
        }
        // 两端 rect 同步冻结：播放页此刻为全屏几何；迷你条为 Chrome 卸载时定格的正确位置。
        // 快照/started/phase 必须在同一同步块完成——Chrome 恢复组合的首帧替身即就位，
        // 不留「真身被压缩布局」的中间帧（否则收起起点会跳一下）。
        val player = rectsSnapshot(FlightKeys.playerKeys) ?: run {
            entrySource = null
            navigate(target)
            return
        }
        val mini = rectsSnapshot(FlightKeys.miniKeys) ?: run {
            entrySource = null
            navigate(target)
            return
        }
        songSnapshot = song
        phase = Phase.Exiting
        snapshot = player + mini
        started = true
        // **正常退出也必须清 entryShowCover**：它是「本次进入暂落封面模式」的瞬态标记，
        // 只在忙时中止分支清会让它泄漏到下一次「直入播放页」（state.screen = Player 不经飞行），
        // 表现为先落封面态再滑走；若再次点的正是同一首（songSnapshot.id 相同），
        // PlayerScreen 的清理守卫不成立 → 歌词页永久进不去。
        entryShowCover = false
        cancelJobs()
        val gen = ++generation
        exitJob = scope.launch {
            t.snapTo(1f)
            val flip = launch {
                snapshotFlow { t.value }.first { it <= 0.15f }
                // 代际校验：飞行已被中止时不得翻页（否则把用户从新页面拽回曲库）
                if (gen == generation) navigate(target)
            }
            t.animateTo(0f, GlassMotion.tweenFlight())
            flip.cancel()
            if (gen != generation) return@launch // 中止：状态清理已由 onRootSizeChanged 完成
            navigate(target) // 兜底翻页（t 未到 0.15 的极端情况）
            phase = Phase.Idle
            snapshot = null
            started = false
            entrySource = null
        }
    }

    /**
     * 外部强制中止飞行（忙时兜底直切用）。
     *
     * 为什么需要：`GlassApp.onOpenPlayer` 的兜底分支只 `state.screen = Player`，
     * 而正在跑的 exitJob 尾部仍会 `navigate(目标)` —— 用户落进播放页后被弹回曲库。
     * 这里把「取消协程 + 代际 + 清瞬态」这套中止语义公开出来，让所有直切入口都能先中止。
     */
    fun abort() {
        cancelJobs()
        generation++
        phase = Phase.Idle
        entryShowCover = false
        songSnapshot = null
        snapshot = null
        started = false
        entrySource = null
    }

    /**
     * 根尺寸变化失效（旋转/分屏/自由窗口——manifest configChanges 吞掉配置变更，组合不重建）：
     * - 飞行进行中：**取消协程**并置 Idle。仅翻 phase 不够——协程醒来后仍会执行尾部
     *   `navigate(Library)`，把用户拽回曲库（旋转中止后快速再点迷你条即可触发）
     * - 中止三件套 snapshot/entryShowCover/songSnapshot + started 同步清空，并把 t 复位到
     *   0：t 残留在中间值会让「进入等待期」的替身 alpha≈0，出现迷你条闪没
     *   （正是该机制本来要消灭的问题）
     * - 无论是否在飞行都清空 rect 注册表：keepOnDispose 冻结的迷你条 rect 与活动
     *   snapshot 在新尺寸下全部视为陈旧，下次 trackFlight 重新回填 / awaitRects 重新等待，
     *   杜绝替身飞向旧坐标
     */
    fun onRootSizeChanged() {
        val wasActive = phase != Phase.Idle
        cancelJobs()
        val gen = ++generation // 让残留协程的尾部副作用全部失效
        if (wasActive) {
            phase = Phase.Idle
            entryShowCover = false
            songSnapshot = null
            snapshot = null
            started = false
            entrySource = null
            // t 复位需 suspend：放到协程里，不阻塞尺寸变化回调。
            // **代际守卫**：若复位队列期间用户立刻发起新的飞行（beginExit 要 t=1），
            // 迟到的 snapTo(0) 会把 t 冲掉、让退出动画从 0 开始。代际不符即放弃复位。
            scope.launch {
                if (gen != generation) return@launch
                t.snapTo(0f)
            }
        }
        rects.clear()
    }

    /** 取消在跑的飞行动画协程（entry / exit 各一）。取消后调用方负责清状态。 */
    private fun cancelJobs() {
        entryJob?.cancel()
        entryJob = null
        exitJob?.cancel()
        exitJob = null
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
