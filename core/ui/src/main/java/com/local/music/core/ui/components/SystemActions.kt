package com.local.music.core.ui.components

import androidx.compose.runtime.staticCompositionLocalOf
import com.local.music.core.ui.state.AppState

/**
 * 系统集成动作桥（P0-5 A）：分享歌曲 / 设为铃声 / 分享歌单。
 *
 * ## 为什么是 CompositionLocal，而不是给每个调用点加参数
 *
 * 这三个动作用的是 `Intent` / `FileProvider` / `RingtoneManager`，全部住在 :app 模块
 * （只有它持有 AndroidManifest 与 FileProvider 配置）；而操作面板 [SongActionsSheet] 与
 * [CollectionActionsSheet] 住在 :core:ui，**不能反向依赖 :app**。
 *
 * 常规解法是给组件加 `onShare: () -> Unit` 之类参数，再逐个调用点接线——但 [SongActionsSheet]
 * 有 5 个调用点（曲库 / 搜索 / 歌单详情 ×2 / 播放页），[CollectionActionsSheet] 有 3 个。
 * 为两个菜单项改 8 个调用点的签名，既制造大范围 diff（并行开发时冲突面极大），
 * 也让「新增一个系统动作」这件事的边际成本保持在高位（下次加「投射到设备」还得再改一遍）。
 *
 * 静态 CompositionLocal 一次提供、处处可读：**新增动作 = 往 [SystemActions] 加一个字段**，
 * 不动任何调用点。
 *
 * ## 为什么默认值全是 null 而不是抛异常的占位
 *
 * 组件必须能在没有 :app 宿主的环境下渲染——Compose Preview、单测、以及「宿主尚未接线」
 * 的启动窗口期。默认空实现让面板安静地不显示这几项（各菜单项逐项判 null，
 * 见 [songShareAction] / [songRingtoneAction] / [playlistShareAction]），
 * 比渲染出点了没反应的菜单项好得多：**点了没反应比没有这项更伤**。
 * 这条取舍也保证了本文件可以放在 :core:ui 而不引入任何 :app 依赖。
 *
 * ## 返回值约定
 *
 * 每个动作返回一句给用户看的文案（成功/失败都说清楚），由宿主统一走顶部横幅呈现。
 * 这样面板本身不需要知道「横幅在哪、长什么样」，与全 App「面板只发意图」的既有结构一致。
 */
data class SystemActions(
    /** 分享单曲；null = 宿主未接线（面板不显示该项） */
    val shareSong: ((DemoSong) -> Unit)? = null,
    /** 设为铃声；null = 同上 */
    val setRingtone: ((DemoSong) -> Unit)? = null,
    /** 分享歌单（导出 M3U8 后分享）；null = 同上 */
    val sharePlaylist: ((title: String, tracks: List<DemoSong>) -> Unit)? = null,

    // ── C-1（本轮）：桌面歌词从设置页深处提到播放现场 ──
    //
    // ## 为什么走 SystemActions 而不是给 PlayerScreen 加参数
    // 桌面歌词的整套实现（悬浮窗权限、WindowManager、独立 DataStore）全在 :app，
    // 而播放选项面板在 :feature:player。加参数要改 GlassApp 的调用点 + feature 模块的
    // 签名，且与既有分享/铃声那组「系统集成动作」是**同一类东西**（都靠 app 层能力）。
    // 归到 SystemActions 后，面板侧只需读一个 CompositionLocal，零签名改动。
    //
    // ## 为什么这里给的是「开关 + 三个设置」而不是一个「打开设置页」的跳转
    // 用户此刻正在听歌，让他离开播放页去找设置项，等于打断他正在做的事。
    // 面板内直接可调才是这个功能该有的位置——这也是它此前「可发现性几乎为零」
    // 的根因：不是入口隐藏了，是入口离使用现场太远。

    /** 桌面歌词当前开关状态；null = 宿主未接线（面板不渲染这一组） */
    val desktopLyricsOn: (() -> Boolean)? = null,
    /** 切换桌面歌词开关（宿主内部处理权限引导） */
    val toggleDesktopLyrics: (() -> Unit)? = null,
    /** 桌面歌词当前字号档（null = 未接线）；见 [DesktopLyricsStyle] */
    val desktopLyricsFontStep: (() -> Int)? = null,
    /** 设置字号档 */
    val setDesktopLyricsFontStep: ((Int) -> Unit)? = null,
    /** 桌面歌词当前不透明度百分比（0-100；null = 未接线） */
    val desktopLyricsOpacity: (() -> Int)? = null,
    /** 设置不透明度百分比 */
    val setDesktopLyricsOpacity: ((Int) -> Unit)? = null,
)
// 已删除：`val available: Boolean get() = shareSong != null || ...`（本轮清理）。
// 它的 KDoc 写「面板不渲染分享分组时靠它判断」，但全仓**零调用** ——
// ActionsSheet 的真实实现是逐项判 null（各菜单项分别 `if (onShareSong != null)`
// 与 `sys.playlistShareAction(...)`），根本没有「分组级」的显隐。
// 留着一个语义更粗、且与真实实现不一致的判据，只会诱导下一个改动去用它，
// 于是出现「available 为 false 但某一项其实已接线」的分组消失 bug。

/**
 * 默认空实现（见 [SystemActions] 的「为什么默认值全是 null」）。
 *
 * 用 `staticCompositionLocalOf` 而不是 `compositionLocalOf`：这组 lambda 的生命周期
 * 等于整个 App（由 GlassApp 在根层 `remember` 一次），不会在运行中变化；
 * static 版本的读取不开销订阅记录，适合「提供一次、全树只读」的场景。
 */
val LocalSystemActions = staticCompositionLocalOf { SystemActions() }

/**
 * 面板侧统一取用：宿主未接线时返回 null，调用方据此不渲染菜单项。
 *
 * 包一层而不是让调用方直接读 [LocalSystemActions] 的理由：这里要顺带判断
 * 「这一项具体可不可用」（例如歌单分享在空歌单时应隐藏），判断逻辑集中在此，
 * 面板里就不会散落 `state.library.isEmpty() && ...` 这类条件。
 */
fun SystemActions.songShareAction(song: DemoSong): (() -> Unit)? =
    shareSong?.let { action -> { action(song) } }

/** 是否展示「设为铃声」：宿主已接线即可（曲目没有 mediaUri 时由实现侧给文案提示） */
fun SystemActions.songRingtoneAction(song: DemoSong): (() -> Unit)? =
    setRingtone?.let { action -> { action(song) } }

/** 是否展示「分享歌单」：宿主已接线且歌单非空（空歌单分享出去毫无意义） */
fun SystemActions.playlistShareAction(title: String, tracks: List<DemoSong>): (() -> Unit)? =
    if (tracks.isEmpty()) null else sharePlaylist?.let { action -> { action(title, tracks) } }

/**
 * 桌面歌词的外观档位（本轮新增）。
 *
 * ## 为什么是「档位」而不是连续滑杆
 * 桌面歌词是一条悬浮在别的内容上的窄条，它只有两个可调维度，而且都不是精细调参：
 * - **字号**决定「一眼能否看清」，5 档足够覆盖「想小一点别挡内容」到「老花眼看不清」；
 * - **不透明度**决定「读得清」与「别抢背景」之间的平衡，4 档（60/75/90/100）够用。
 *
 * 连续滑杆会带来一个具体的坏处：悬浮窗每次参数变化都要 `updateViewLayout`，
 * 拖动滑杆会让它连续重排几十次，在低端机上表现为歌词条抖动。档位的离散特性
 * 天然避免这个问题，同时用户也更容易「调回刚才那个值」。
 *
 * ## 为什么定义在 :core:ui 而不是 :app
 * 档位标签是**UI 文案**（「标准」「特大」），要由面板渲染；而 :feature:player
 * 不能依赖 :app。数值本身（sp / 百分比）属于实现细节，留在 :app 的控制器里
 * 按档位索引取值即可——两边共享的只有「档位个数与标签」这一个契约。
 */
object DesktopLyricsStyle {

    /**
     * 字号档的展示标签（5 档，升序）。
     *
     * 索引即档位号，持久化也只存这个 int——标签文案改了不影响已存数据。
     * 默认档位是索引 2（「标准」），见 [DEFAULT_FONT_STEP]。
     */
    val FontSteps: List<String> = listOf("小", "偏小", "标准", "偏大", "特大")

    /** 默认字号档：中间档。选中间值是为了「往上往下都还有余地」——
     *  默认值取端点会让一个方向的调节立刻失效。 */
    const val DEFAULT_FONT_STEP = 2

    /** 不透明度档的展示标签（4 档，升序） */
    val OpacitySteps: List<String> = listOf("淡", "适中", "清晰", "不透明")

    /** 对应的百分比：与标签一一对应，索引相同 */
    val OpacityPercents: List<Int> = listOf(60, 75, 90, 100)

    /** 默认不透明度档：索引 2（90%）。
     *  选 90% 而不是 100%：桌面歌词要能看出「它浮在别的内容上」，
     *  全不透明会让它像一块贴纸，而 60% 在白底桌面/浅色壁纸上几乎读不清。 */
    const val DEFAULT_OPACITY_STEP = 2

    /** 字号档 → sp 字号（14/16/18/21/24）。
     *  基准 18sp：比系统正文（16sp）略大，因为桌面歌词是**远距离扫读**，
     *  用户通常在手机放下时瞄一眼，而不是贴近看。 */
    fun fontSp(step: Int): Float = listOf(14f, 16f, 18f, 21f, 24f)[step.coerceIn(0, 4)]

    /** 不透明度档 → 0-255 alpha 值（与 0xE6 这类写法同口径） */
    fun alphaOf(step: Int): Int = (OpacityPercents[step.coerceIn(0, OpacityPercents.lastIndex)] * 255) / 100
}
