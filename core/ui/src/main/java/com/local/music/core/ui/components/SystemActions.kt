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
 * 的启动窗口期。默认空实现让面板安静地不显示这几项（见 [SystemActions.available]），
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
) {
    /** 有任意一项可用（宿主已接线）。全不可用时面板不渲染「分享」分组，避免空动作 */
    val available: Boolean
        get() = shareSong != null || setRingtone != null || sharePlaylist != null
}

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
