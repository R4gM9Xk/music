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

    /**
     * 进入飞行时**底部 Chrome 是否由覆盖层代画导航胶囊**（衔接修复③）。
     *
     * 来自 tab 屏（曲库/歌单/设置）时，GlassApp 在 phase=Entering 期间把 Chrome 整块卸载，
     * 屏内没有真胶囊 → 覆盖层必须画一份替身，否则胶囊凭空消失。
     * 但来自详情/搜索时，那两屏**自带 GlassChrome**（屏内真胶囊），且它已被
     * baseContentAlpha 乘掉一次 —— 覆盖层若再画一份，两枚半透明胶囊同位置叠加 =
     * 胶囊偏亮、消退节奏不对（旧审计 P3-23）。此flag 让覆盖层只在**无人代画**时补位。
     */
    var entryNavOwnedByOverlay by mutableStateOf(true)
        private set

    /**
     * 起飞瞬间的按压回弹接续（1 = 正常，<1 = 按压态）——衔接修复②。
     *
     * 迷你条是 GlassSurface(onClick)，自带 pressScale(.97)。点按的 up 事件同时触发
     * 「beginEntry（Chrome 当帧卸载）」与「pressScale 回弹（.97→1，180ms）」：
     * 真身连同回弹动画一起被卸载截断，替身按 scale=1 绘制 —— 用户看到迷你条从按压态
     * **瞬跳**到全尺寸才起飞。这里让替身承接这段回弹：起飞瞬间从 .97 弹回 1，
     * 与真实手感一致（Apple Music 同款「按下去的方向接着长」）。
     *
     * 只作用于进入方向；退出起点是点「收起」按钮，迷你条真身没有按压缩放。
     */
    val pressRecover = Animatable(1f)

    /**
     * [pressRecover] 是否已布防（snapTo(.97) 已落地）。
     *
     * 存在的唯一原因：`snapTo` 是 suspend，要到协程**第一次执行**才改值；而 beginEntry
     * 是同步返回、同一帧就出画面。若这一帧直接读 [pressRecover]，读到的还是初始值 1f →
     * 替身先闪一帧全尺寸、下一帧才缩回 .97 再弹起（比原来的「瞬跳」更难看）。
     * 未布防期间 [pressScale] 直接返回按压值，与真身按下那一刻严格一致。
     */
    private var pressArmed by mutableStateOf(false)

    /**
     * 替身层应按的缩放（绘制期读，每帧取）：
     * - 非进入方向 → 1（退出起点没有按压态；也天然免疫中止残留值）
     * - 进入期未布防 → 按压值（首帧就位，不闪）
     * - 进入期已布防 → 跟回弹动画
     */
    fun pressScale(): Float = when {
        phase != Phase.Entering -> 1f
        !pressArmed -> MINI_PRESS_SCALE
        else -> pressRecover.value
    }

    /** 进入飞行时的来源屏（退出飞行回到这里，而不是永远回曲库） */
    var entrySource by mutableStateOf<GlassScreen?>(null)
        private set

    /** 进入播放页暂落封面模式（瞬态；手势/点击封面即清除，不写持久化 lyricEnabled） */
    var entryShowCover by mutableStateOf(false)

    /** 替身已接手（对端 rect 快照完成），真身从此按公式隐身 */
    var started by mutableStateOf(false)
        private set

    /**
     * 退出起飞的 snap 尚未落地（见 [beginExit]）。snapTo 是 suspend、在协程首帧才生效，
     * 而被中止的飞行会把 t 残留在任意中间值——落地前的头一两帧里，替身几何若照读 raw t
     * 会画在半途位置再瞬移到播放页端。此标记为 true 期间 [displayT] 强制返回 1，
     * 与「等待期强制 0」构成进出两个方向对称的首帧防线。
     */
    var exitSnapPending by mutableStateOf(false)
        private set

    /**
     * 下层后退层缩放注入点（GlassApp 持有 recede 动画值）。播放页盖在 tab 页上时，
     * Chrome 仍组合在 0.96 后退层里，其 trackFlight 注册的是**缩放后**的 root 坐标；
     * 而 [beginExit] 的飞行落点必须对齐接管瞬间（recede 已归零）的真身——
     * 据此把缩放空间量到的迷你条端点还原成未缩放几何。Chrome 不在组合中
     * （详情/搜索来源）时端点本就是未缩放的冻结值，provider 应返回 1。
     */
    var backingScaleProvider: () -> Float = { 1f }

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

    /**
     * 飞行几何/样式的显示 t：**仅限布局期/绘制期 lambda 调用**（每帧变化，组合期读会逐帧重组）。
     * - 进入等待期强制 0：替身原地按迷你条形态绘制（起飞前样式不被残留 t 污染）
     * - 退出 snap 未落地强制 1：替身第一帧就落在播放页端几何（残 t 不再把首帧画在半途）
     * 全部 alpha 公式与替身层共用本函数，保证同一帧内几何与透明度读的是同一个 t。
     */
    fun displayT(): Float = when {
        phase == Phase.Entering && !started -> 0f
        phase == Phase.Exiting && exitSnapPending -> 1f
        else -> t.value
    }

    /** 非共享组（图中没有的按键等）：原地由虚到实；Idle 时必须全显（t 停在 0/1 不代表开合度） */
    fun softAlpha(): Float = when (phase) {
        Phase.Idle -> 1f
        else -> ((displayT() - 0.15f) / 0.85f).coerceIn(0f, 1f)
    }

    /**
     * 播放页共享真身（大封面 / 控制台根层）：进入飞行期让位替身（等待期即隐身），收尾 12% 交叉淡入；
     * 退出**起飞端按时间等效窗口渐出**——旧实现直接回 0，真实控制台（含非共享的心形/进度/时间/
     * 随机/上一首/循环）在点收起的那一帧整块消失、副标题瞬间被替身的短文本换掉（录屏帧 132→133 实证）。
     * 窗口取路径 50% 而非对称的 12%：飞行缓动起步快、收尾慢，12% 路径在起飞端只相当于 ~28ms
     * （依旧近似硬切），50% 才等效于进场端 12% 慢尾巴的 ~165ms 手感；替身按互补曲线渐入（见 Overlay）。
     */
    fun playerSharedAlpha(): Float = when {
        phase == Phase.Idle -> 1f
        phase == Phase.Entering -> if (!started) 0f else ((displayT() - 0.88f) / 0.12f).coerceIn(0f, 1f)
        else -> ((displayT() - 0.5f) / 0.5f).coerceIn(0f, 1f)
    }

    /**
     * 播放页控制台**共享子元素**（歌名/歌者/播放/下一首）的透明度：进入期与 [playerSharedAlpha]
     * 同窗渐入；退出期恒 1——真身控制台根层已按 12% 窗渐出，子元素若再乘同一系数会平方衰减
     * （中点 0.25），与替身交叉淡化时亮度凹陷加深，交给根层一层曲线即可。
     */
    fun playerSharedInnerAlpha(): Float = when {
        phase == Phase.Entering && started -> ((displayT() - 0.88f) / 0.12f).coerceIn(0f, 1f)
        else -> 1f
    }

    /** 迷你条共享真身：进入期 Chrome 已卸载（不参与）；退出期最后 12% 淡入接位 */
    fun miniSharedAlpha(): Float = when {
        phase == Phase.Idle -> 1f
        phase == Phase.Entering -> 0f
        else -> if (!started) 0f else ((0.12f - displayT()) / 0.12f).coerceIn(0f, 1f)
    }

    /** 导航胶囊：进入期由覆盖层替身负责（Chrome 已卸载）；退出期与迷你条真身同窗淡入接位 */
    fun navAlpha(): Float = when (phase) {
        Phase.Idle -> 1f
        Phase.Entering -> 1f - displayT()
        else -> ((0.12f - displayT()) / 0.12f).coerceIn(0f, 1f)
    }

    /**
     * 下层内容在飞行期的透明度：进入 1→0（来源页让位给飞行替身），退出 0→1（先于播放页落定）。
     *
     * 为什么需要：播放页改为**覆盖层**后，下层恒渲染 playerOrigin 且不换屏 ——
     * 旧结构靠 AnimatedContent 的 `fadeOut(tweenFlight)` 让来源页在飞行期淡出，
     * 新结构没有换屏就不会触发。若不补这条曲线，飞行替身会一直画在**全不透明的来源页**
     * 之上，收尾播放页变不透明时整屏内容一次性硬切。
     *
     * 退出段的 /0.6 对齐旧结构「库页以 0.6×Flight 时长淡入接管」的口径。
     */
    fun baseContentAlpha(): Float = when (phase) {
        Phase.Idle -> 1f
        Phase.Entering -> (1f - displayT()).coerceIn(0f, 1f)
        else -> ((1f - displayT()) / 0.6f).coerceIn(0f, 1f)
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
        // 导航胶囊替身归属（衔接修复③）：只有 tab 来源才需要覆盖层补位。
        // 详情/搜索屏自带 GlassChrome，其真胶囊自己在 baseContentAlpha 下淡出，
        // 覆盖层再画一份就是双份叠加偏亮（P3-23）。来源未知（null）时保守代画——
        // 宁可多画一枚（与真身同位置、同位置渐隐，肉眼不可辨）也不能让胶囊凭空消失。
        entryNavOwnedByOverlay = source == null || source !in NON_TAB_SOURCES_WITH_CHROME
        entryShowCover = true
        phase = Phase.Entering
        started = false
        // 按压回弹接续（衔接修复②）：迷你条刚被按下 .97，Chrome 本帧即卸载。
        // 替身从这里起步、180ms 弹回 1，飞行本体同步起飞 —— 不再有「瞬跳回全尺寸」。
        // **snap 与 animate 必须在同一个协程里顺序执行**：拆成两个 launch 时调度顺序
        // 不保证（可能先 animateTo(1) 再 snapTo(.97) → 回弹被吞掉、替身直接闪到 1）。
        // 同一协程里先把 t 归零（原有语义，见下），再做回弹接续。
        scope.launch {
            if (gen != generation) return@launch
            // **等待期 t 必须归零**：beginExit 的忙时中止分支只翻 phase 不复位 t，残留 t（如 0.4）
            // 会让等待期的替身样式按半程 lerp 渲染（圆角/字号/播放钮圆底/导航胶囊全部半形态），
            // 起飞瞬间 snapTo(0) 再全部跳变。这里用代际守卫的协程复位，不阻塞当前同步块。
            t.snapTo(0f)
            pressRecover.snapTo(MINI_PRESS_SCALE)
            pressArmed = true
            if (gen != generation) return@launch
            pressRecover.animateTo(1f, GlassMotion.tweenPress())
        }
        // 等待期就先挂 mini-only 快照：替身按 t=0 几何原地绘制迷你条（Chrome 此刻已卸载，
        // 不挂的话替身要等 player rects 到齐才出现，起点有 1~N 帧空窗）
        snapshot = mini
        navigate(GlassScreen.Player)
        entryJob = scope.launch {
            // 几何闸门：等播放页根布局达到全屏（Chrome 卸载与 AnimatedContent 首帧测量存在竞态，
            // 中间态会出现半高控制台/零矩形），就绪后**重新快照**（等待期间 rect 已更新）。
            // 旧实现在闸门前还有一次 awaitRects——它只兜「rects 永不到齐」的超时中止，而其后
            // 的 awaitRects(600ms) 覆盖同一情形，平白让替身多静止 ~2 帧（主观「点了没反应」）。
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
                pressArmed = false
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
                pressArmed = false
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
            exitSnapPending = false
            entrySource = null
            pressArmed = false
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
        // 迷你条端点还原：Chrome 在播放页打开期间组合在 0.96 后退层内（tab 来源时），
        // rects 注册的是缩放后几何；飞行落点必须对齐接管瞬间（recede 已归零）的真身。
        // 旧实现直接用缩放坐标当端点，落地替身比真身小 ~4%、高 ~10dp——收尾交叉淡入
        // 时两套几何叠画，迷你条「落地前最后一瞬还在缩小」。
        snapshot = player + unscaleMini(mini)
        started = true
        // snapTo(1f) 在协程首帧才生效（与 beginEntry 的 snapTo(0) 同一时序盲区）：
        // 落地前替身几何强制按 t=1 绘制，防中止残留的中间 t 把退出首帧画在半途
        exitSnapPending = true
        // **entryShowCover 不能在起飞时清**（P2-⑤ 回归的根因）：
        // 它决定 PlayerScreen 的 showLyrics，而 showLyrics 驱动 modeT ——
        // 若在这里同步清掉，PlayerScreen 下一帧重组就会看到 showLyrics 由 false 翻 true，
        // modeT 立刻从 0 动画到 1：封面**同时**朝歌词小窗（左上）移动，与「缩回右下迷你条」
        // 的飞行几何打架（用户看到向左上飞），歌词 rail 也按 modeT 淡入（闪一下歌词页）。
        // 真机逐帧截图确认过这个现象（.zcode/screens/realdev/mid2.png）。
        // 正确时机是**飞行结束、phase 归 Idle 之后**——那时播放页已离开组合，模式的翻转
        // 无从影响飞行几何；同时仍满足「不留泄漏标记」的原意（见下方收尾）。
        cancelJobs()
        val gen = ++generation
        exitJob = scope.launch {
            t.snapTo(1f)
            exitSnapPending = false // snap 落地：替身几何恢复读真实 t
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
            pressArmed = false
            // 收尾再清瞬态标记：此刻播放页已离开组合，翻转不再影响任何飞行几何。
            // 保留清理是必要的——否则标记泄漏到下一次「直入播放页」（不经飞行）会先落
            // 封面态再滑走，且同一首歌时 PlayerScreen 的守卫不成立 → 歌词页永久进不去。
            entryShowCover = false
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
        exitSnapPending = false
        entrySource = null
        pressArmed = false
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
            exitSnapPending = false
            entrySource = null
            pressArmed = false
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

    /**
     * 取消在跑的飞行动画协程（entry / exit 各一）。取消后调用方负责清状态。
     *
     * [pressRecover] 不在这里复位，也**不需要**复位：
     * - 它只在 phase == Entering 时被覆盖层读取，中止后 phase 立刻离开 Entering →
     *   残留值（如 0.985）不会作用于任何一帧；
     * - 每次 beginEntry 都先 `snapTo(MINI_PRESS_SCALE)` 再 animateTo(1)，旧值必被覆盖；
     * - 在同一 Animatable 上补一个 snapTo 反而会和 beginEntry 的 snapTo 抢执行顺序，
     *   凭空引入竞态。
     */
    private fun cancelJobs() {
        entryJob?.cancel()
        entryJob = null
        exitJob?.cancel()
        exitJob = null
    }

    private fun rectsSnapshot(keys: List<String>): Map<String, Rect>? =
        keys.mapNotNull { k -> rects[k]?.let { k to it } }.toMap()
            .takeIf { it.size == keys.size }

    /**
     * 把缩放空间量到的迷你条端点还原成未缩放几何（[beginExit] 落点对齐接管瞬间真身）。
     * 后退层的缩放以上一层中心为轴（graphicsLayer 默认 Center pivot），反向变换即
     * 各边坐标按同一中心除以 scale；端点本就未缩放时 provider 返回 1，原样返回。
     */
    private fun unscaleMini(mini: Map<String, Rect>): Map<String, Rect> {
        val scale = backingScaleProvider()
        if (scale == 1f || scale <= 0f) return mini
        val root = rects[FlightKeys.AppRoot] ?: return mini
        val cx = root.center.x
        val cy = root.center.y
        val out = HashMap<String, Rect>(mini.size)
        mini.forEach { (key, r) ->
            out[key] = Rect(
                cx + (r.left - cx) / scale, cy + (r.top - cy) / scale,
                cx + (r.right - cx) / scale, cy + (r.bottom - cy) / scale,
            )
        }
        return out
    }

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
 * 迷你条按压态缩放（与 GlassSurface 的 pressScale(pressed = 0.97f) **必须同值**）：
 * 起飞瞬间替身由这里起步回弹到 1，两端对齐才不会跳变。改一处必须改另一处。
 */
private const val MINI_PRESS_SCALE = 0.97f

/**
 * 自带 GlassChrome 的非 tab 屏：进入飞行时屏内真导航胶囊自己在淡出，
 * 覆盖层**不得**再画一枚（双份叠加偏亮，见 PlayerFlight.entryNavOwnedByOverlay）。
 */
private val NON_TAB_SOURCES_WITH_CHROME = setOf(GlassScreen.Detail, GlassScreen.Search)

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
