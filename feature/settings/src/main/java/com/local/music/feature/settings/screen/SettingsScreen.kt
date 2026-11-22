package com.local.music.feature.settings.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.local.music.core.ui.components.AuroraMiniPreview
import com.local.music.core.ui.components.BackgroundMode
import com.local.music.core.ui.components.DesktopLyricsStyle
import com.local.music.core.ui.components.ChromeBottomInset
import com.local.music.core.ui.components.DropdownOption
import com.local.music.core.ui.components.GlassLevel
import com.local.music.core.ui.components.GlassIconButton
import com.local.music.core.ui.components.GlassSurface
import com.local.music.core.ui.components.GlassToggle
import com.local.music.core.ui.components.LocalModalState
import com.local.music.core.ui.components.ModalSheet
import com.local.music.core.ui.components.PageHeader
import com.local.music.core.ui.components.ProgressTrack
import com.local.music.core.ui.components.SectionHeader
import com.local.music.core.ui.components.SettingDropdown
import com.local.music.core.ui.components.SettingRow
import com.local.music.core.ui.components.SheetAction
import com.local.music.core.ui.components.ThemeColorSwatches
import com.local.music.core.ui.components.asTime
import com.local.music.core.ui.components.pressScale
import com.local.music.core.ui.theme.AuroraPalette
import com.local.music.core.ui.state.AppState
import com.local.music.core.ui.state.BassBoostLevelLabels
import com.local.music.core.ui.state.BassBoostStrengths
import com.local.music.core.ui.state.EqPreset
import com.local.music.core.ui.state.GlassScreen
import com.local.music.core.ui.state.SleepTimerOptions
import com.local.music.core.ui.state.ThemeMode
import com.local.music.core.ui.state.UpdateUiState
import com.local.music.core.ui.state.VirtualizerLevelLabels
import com.local.music.core.ui.state.VirtualizerStrengths
import com.local.music.core.ui.state.VisualStyle
import com.local.music.core.ui.state.bassBoostSubtitle
import com.local.music.core.ui.state.loudnessNormSubtitle
import com.local.music.core.ui.state.sleepTimerLabel
import com.local.music.core.ui.state.sleepTimerSubtitle
import com.local.music.core.ui.state.virtualizerSubtitle
import com.local.music.core.ui.theme.GlassIcons
import com.local.music.core.ui.theme.GlassMotion
import com.local.music.core.ui.theme.GlassText
import com.local.music.core.ui.theme.GlassIntensity
import com.local.music.core.ui.theme.MotionIntensity
import com.local.music.core.ui.theme.LocalGlassColors
import com.local.music.core.ui.theme.R
import com.local.music.core.ui.theme.S
import com.local.music.core.ui.theme.auroraAccentColor
import kotlinx.coroutines.delay

/**
 * 设置：外观 / 播放 / 曲库 三组玻璃卡。全部接入 AppState——
 * 取色与歌词开关实时生效；玻璃/动效强度改全局 token；均衡器经服务端 Equalizer 生效；
 * 扫描入口真实重扫 MediaStore；文件夹过滤选项从当前曲库派生。
 * 五个面板（更新/玻璃/动效/均衡器/文件夹）经 LocalModalState.show 挂 GlassApp 根层宿主
 * （屏内不再渲染 GlassSheet）。
 *
 * ## 三级分类改造（本轮）
 *
 * 改动前是一屏到底的纵向长列表：5 个 SectionHeader（外观/播放/曲库/数据/关于）+
 * 30 余行，全部挤在同一条 `verticalScroll` 里。实测问题有三个：
 * 1. **找不到**——用户想改「扫描过滤」要滑过整个「播放」组（均衡器+三条音效链+定时+
 *    歌词+桌面歌词共 8 行）；想找「过滤文件夹」还要再往下。而这些行与他要找的那行
 *    在视觉上**完全同级**（同样的玻璃行、同样的字号）。
 * 2. **扫不动**——30 行全是可点行，没有层次可言。眼睛无法用「跳读」定位，
 *    只能逐行扫。
 * 3. **无法回头**——一屏到底意味着没有「我现在在哪一组」的定位感，
 *    滑到一半完全不知道自己上下还有哪些组。
 *
 * 改造成「首页分组导航 + 五个二级页」：
 * - 首页只保留 5 张分组卡片，每张一行（图标 + 组名 + 一句话摘要 + 箭头）；
 * - 点进子页后是可返回的二级页（页头带返回箭头，返回键也回首页）；
 * - 分组划分沿用原有的 5 个 SectionHeader，**不重新分类**——用户已有的
 *   「这条设置大概在哪个组」的记忆仍然有效，只是从「滑过去」变成「点进去」。
 *
 * ## 为什么摘要文字很重要（而不是纯标题导航）
 * 三行式导航（「外观 / 播放 / 曲库」）会把「找得到」的成本从滚动转移到记忆：
 * 用户还是得记住「扫描过滤」属于「曲库」。所以每张卡都带一句**动态摘要**
 * （当前主题色名、均衡器档位、已收录曲目数），让用户能在不进去的情况下
 * 确认「就是这一组」。摘要同时承担了「一眼看到当前配置」的作用。
 *
 * ## 为什么不用二级路由（GlassScreen.Settings / Settings.Appearance 之类）
 * 那要给 `AppState.screen` 加枚举、给 GlassApp 的 AnimatedContent 加分支、
 * 并处理「从播放页/详情页返回时该回到哪一层」的语义——影响面远超本功能收益。
 * 分组页是**设置模块内部**的导航，因此不需要独立的 GlassScreen。
 *
 * ## 但也不能存在屏内（本轮改写：原实现是 `var openGroup by rememberSaveable`）
 *
 * 上一轮的结论是「屏内 rememberSaveable 即可，退出设置页再进来回到首页是合理行为」。
 * 这句话只对了一半——**系统返回键不归本屏管**。决定返回键去向的 BackHandler 在
 * `GlassApp`（:app 模块），它看不到 feature:settings 内部的局部状态，于是
 * 「设置 → 外观 → 系统返回」一路落到全局兜底分支，把 screen 打回 Library：
 * 用户被弹出设置页，跳过了设置首页这一层（旋转/分屏保得住，返回键保不住，
 * 这种「只坏一条路」的缺陷读代码极难发现，只有按返回键时才暴露）。
 *
 * 修法是把这一层导航态提到唯一的跨屏单源 `AppState.settingsGroup`——瞬态导航态，
 * 与 `libraryTab` 同一口径，**不进 DataStore 快照**（下次冷启动应当从首页开始）。
 * BackHandler 因此可以问一句「设置页还有下级可退吗」（[AppState.backInSettings]）。
 * 本屏从此只**读写** state，不再自己持有这一层。
 *
 * ### 代价（如实记录，不要当成回归）
 * 这一层现在活在进程级：切到别的 tab 再回设置页会停在上次的分组页，而不是回到首页。
 * 这与底部导航各 tab 各自保留位置是同一种预期（曲库的 tab/滚动位置本轮做了同样的提升），
 * 用户不会觉得停在原地更怪。真正丢掉的只有「每次进设置都是首页」这一条旧行为。
 *
 * ## 三级页（首页 → 关于 → 支持开发者）
 * 「支持开发者」是**三级页**：从「关于」里的入口进去，返回要回到「关于」而不是首页。
 * 所以进入时记 `state.settingsReturnGroup = 关于`（经 `openSettingsGroup(..., returnGroup)`），
 * 返回统一走 `state.backInSettings()`——它先退到 returnGroup，没有时才退首页。
 * 二级页与三级页共用同一个 [SettingsSubPageHeader]，组名只定义一次（见文件末尾的分组名常量）。
 */
@Composable
fun SettingsScreen(
    state: AppState,
    onRescan: (onDone: () -> Unit) -> Unit = { it() },
    // getter 形态：更新面板挂根层宿主后，进度/状态须活读 snapshot 态才能持续刷新
    update: () -> UpdateUiState = { UpdateUiState() },
    currentVersionName: String = "",
    onCheckUpdate: () -> Unit = {},
    onStartUpdate: () -> Unit = {},
    onInstallUpdate: () -> Unit = {},
    allFilesGranted: Boolean = false,
    onAllFilesAccess: () -> Unit = {},
    // 音频权限（READ_MEDIA_AUDIO）：被拒后才在「曲库」组出现再引导行
    audioDenied: Boolean = false,
    onRequestAudioPermission: () -> Unit = {},
    // D2/D3：文件 IO 入口（SAF 选择器在 app 层持有，屏内只发起）
    onImportM3u: () -> Unit = {},    onExportBackup: () -> Unit = {},
    onImportBackup: () -> Unit = {},
    /**
     * 崩溃日志入口（04 报告 #3 的最后一环）。
     *
     * 崩溃日志的**实现**在 app 模块（`com.local.music.crash.CrashHandler`），而本屏属于
     * `feature:settings`——feature 模块不能反向依赖 app 模块，所以这里只能拿到「数量」与
     * 「动作」两个抽象。数量用来决定这一行显不显示、副标题写什么；动作由 app 层用 SAF
     * 拉起导出（与备份导出同一套 CreateDocument 通道）。
     *
     * 为什么把「数量」而不是「是否有日志」传进来：用户看到「有 3 份」比看到「有崩溃日志」
     * 更有实感，也更容易意识到「原来我崩过三次」而不是「大概是别人的设备问题」。
     */
    crashLogCount: Int = 0,
    onExportCrashLogs: () -> Unit = {},
    onClearCrashLogs: () -> Unit = {},
    // E2：自启动引导（仅国产省电 ROM 显示该行）
    autoStartGuideSubtitle: String? = null,
    onAutoStartGuide: () -> Unit = {},
    /**
     * P1-②：设置快照读取失败（损坏 / 来自更新版本 / IO 异常）。
     *
     * 为 true 时宿主**冻结了存盘**（磁盘上的旧快照原样保留，不再被默认值覆盖），
     * 所以必须让用户看见这件事——否则「改了设置、重启又回去了」会被当成又一个 bug。
     * 同时给出唯一出口 [onRebuildFromCorrupt]：用户明确同意放弃旧快照后才会重新开始存盘。
     */
    corruptSnapshot: Boolean = false,
    onRebuildFromCorrupt: () -> Unit = {},
    /**
     * 桌面歌词开关（P0-8）。与 crashLogCount 同一套解耦模式：开关**状态**由 app 层
     * 持久化（SettingsStore 快照）并经 MediaSession 命令下发服务端，本屏只拿布尔与
     * 两个动作。权限引导也整个丢回 app 层——canDrawOverlays / ACTION_MANAGE_OVERLAY_PERMISSION
     * 都是 framework 细节，feature:settings 不该知道。
     */
    desktopLyricsEnabled: Boolean = false,    onDesktopLyricsEnabledChange: (enabled: Boolean) -> Unit = {},
    /**
     * 桌面歌词外观三档（本轮新增）。
     *
     * 与 [desktopLyricsEnabled] 同一套解耦模式：真值与持久化都在 app 层
     * （DesktopLyricsStore 独立 DataStore），本屏只拿到「当前值 + 变更回调」。
     * 默认值取 DesktopLyricsStyle 的默认档，保证未接线时（预览/单测）也能渲染。
     *
     * [desktopLyricsOnChangeAvailable] 是「宿主是否接线了外观设置」的门面：
     * 为 false 时这三行**整体不渲染**——比渲染出点了没反应的假行好
     * （与 SystemActions 逐项判 null 的既有取舍一致）。
     */
    desktopLyricsFontStep: Int = DesktopLyricsStyle.DEFAULT_FONT_STEP,
    onDesktopLyricsFontStepChange: (Int) -> Unit = {},
    desktopLyricsOpacityStep: Int = DesktopLyricsStyle.DEFAULT_OPACITY_STEP,
    onDesktopLyricsOpacityStepChange: (Int) -> Unit = {},
    desktopLyricsLocked: Boolean = false,
    onDesktopLyricsLockedChange: (Boolean) -> Unit = {},
    /** 宿主是否接线了桌面歌词外观设置（false = 三行不渲染） */
    desktopLyricsOnChangeAvailable: Boolean = false,
    /**
     * 照片背景（v11）。
     *
     * 与桌面歌词同一套解耦模式：SAF 选择器与文件 IO 全在 app 层，本屏只拿到
     * 「三个动作」。为什么不在本屏直接拉选择器——`feature:settings` 不该知道
     * `ActivityResultContracts` / uri 持久授权 / 私有目录落盘这些 framework 细节。
     *
     * [backgroundPhotoSelected] 只用来决定副标题写什么（「已设置」/「未设置」），
     * 不承载任何状态判定：真正的生效与否由 [AppState.backgroundMode] 决定。
     */
    onPickBackgroundPhoto: () -> Unit = {},
    onClearBackgroundPhoto: () -> Unit = {},
    backgroundPhotoSelected: Boolean = false,
    // 悬浮窗权限未授予时的引导跳转（去系统「显示在其他应用上层」页）
    onDesktopLyricsPermission: () -> Unit = {},
    /**
     * 「到发布页手动下载安装」的唯一出口（P2 修复：验签失败死行）。
     *
     * ## 为什么需要这个形参
     * `UpdateUiState.FailStage.Verify` 意味着「下载到的 APK 与本机已装应用的签名不一致」，
     * 应用内更新这条路**必然**失败（同一份坏字节再下一次还是失败），所以不能给「重试」。
     * 正确的出口是去发布页手动下载安装（需先卸载旧版）。但「打开浏览器」是
     * framework 细节（Intent / Uri），`feature:settings` 不该知道——与
     * [onExportCrashLogs] / [onPickBackgroundPhoto] 同一套解耦模式。
     *
     * ## 为什么是可空的
     * 这个回调由 :app 的 GlassApp 接线。可空 + `null` 默认值的语义是
     * **「宿主没接线 ⇒ 我不假装有出口」**：此时那一行传 `SettingRow(onClick = null)`，
     * 变成纯说明行（无按压反馈、不挂 role = Button、读屏不念「双击激活」）。
     *
     * 这条区分是本轮的要害：改造前该行在 Verify 态**有按压反馈、有按钮语义，
     * 点了却什么都不做**（when 的 Verify 分支是空实现）——一个承诺了出口却没有出口
     * 的死行，比没有这一行更伤。宁可让它读起来是一段**说明**，也不要让它读起来
     * 像一个**坏掉的按钮**。这与 [desktopLyricsOnChangeAvailable] 的取舍同源
     * （「未接线即不渲染假行」），只是这里文案本身有价值，所以退一档成「不可点的说明行」。
     */
    onOpenReleasePage: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    var scanning by remember { mutableStateOf(false) }
    // 模态面板宿主（GlassApp 根层）
    val modal = LocalModalState.current

    // ── 分组层级：读写 AppState.settingsGroup（null = 首页导航）──
    //
    // **本屏不再持有这一层状态**（本轮改写，原实现是 `var openGroup by rememberSaveable`）。
    // 原因见文件头 KDoc「但也不能存在屏内」：决定系统返回键去向的 BackHandler 在
    // :app 模块，看不到 feature:settings 的局部状态，于是「设置 → 外观 → 返回」
    // 会被打回曲库、跳过设置首页。提升到 AppState 后，返回键与页头返回箭头
    // 走**同一条**通路（state.backInSettings()），两条入口不可能再分叉。
    //
    // 读法注意：`state.settingsGroup` 是普通 `var`（mutableStateOf 委托），**不是**本函数
    // 的 `by` 委托属性。因此下面统一用本地 `openGroup` 做**只读**镜像
    // （少改几十处读点、也让 condition 读起来和改造前一致），
    // 而所有**写**处必须调 `state.openSettingsGroup(...)` / `state.backInSettings()`
    // ——写成 `openGroup = "x"` 只改这个局部 val 的副本，编译器会直接报错（val 不可赋值），
    // 这是刻意让「忘了提升写点」变成编译错误而不是静默失效。
    val openGroup: String? = state.settingsGroup

    // 更新面板的可见语义组已下沉为 UpdateUiState.keepsSheetOpen（见该属性的 KDoc）：
    // 「哪些状态算正在进行中」属于领域知识，与 Status 定义放在一起才不会漏改。

    // 扫描完成（曲库对象被整体替换）后结束扫描态
    LaunchedEffect(state.library) {
        if (scanning) scanning = false
    }

    // 定时关闭行副标题：每秒读一次剩余秒数做倒计时（分钟档到点后 effectiveSleepTimerMode 按期限归零）
    var sleepRemainingSec by remember { mutableIntStateOf(0) }
    // A3-06：循环按剩余秒数有界化——旧实现 while(true) 在未开定时（deadline=0 常驻态）时
    // 也每秒空转唤醒，开启后到点也不停。remaining 为 null（未开启）或 0（到期）即退出
    LaunchedEffect(state.sleepTimerDeadlineMs) {
        while (true) {
            val remaining = state.sleepTimerRemainingSec() ?: break
            sleepRemainingSec = remaining
            if (remaining <= 0) break
            delay(1000)
        }
        sleepRemainingSec = 0
    }
    val sleepActiveMode = state.effectiveSleepTimerMode()
    val sleepSubtitle = when {
        sleepActiveMode > 0 -> "剩余 ${sleepRemainingSec.asTime()}（${sleepTimerLabel(sleepActiveMode)}）"
        sleepActiveMode == -1 -> "播完当前首后暂停"
        // 与面板同源：-2 档在会回绕的模式下必须如实说明「不会生效」，
        // 否则设置首页这行摘要会承诺一个永远不会发生的暂停
        sleepActiveMode == -2 -> sleepTimerSubtitle(
            option = -2,
            activeMode = -2,
            remainingSec = 0,
            repeatMode = state.repeatMode,
            shuffleOn = state.shuffleOn,
        ) ?: "播完整个队列后暂停"
        else -> "未开启"
    }

    // 无自有 haze source：本屏内容与面板均无 hazeEffect 消费（面板挂 app 级），整屏录制纯开销
    Column(modifier.fillMaxSize().statusBarsPadding()) {
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
            // ── 页头：首页用大标题，二级页用「返回 + 组名」──
            //
            // **必须是二选一**：两者同时渲染会出现两行标题（「设置」大标题 + 「外观」
            // 返回头），既重复又互相挤压首屏高度。第一版就是这么写的（PageHeader 恒显示），
            // 自查时发现——这类错误编译期完全看不出来，只能靠读结构。
            if (openGroup == null) {
                PageHeader(title = "设置")
            }

            // P1-②：快照读取失败时的显式横幅。放在最顶部——它是「本页所有改动都不会保存」
            // 这种状态的前提，藏在任一分组下面都会让人先改了再发现白改。
            // A3-07：横幅出现/消失走展开+渐变——旧实现 if 硬切，页首内容一帧被顶开/塌缩
            // 增-P2-C：expand/shrink 原来用裸 tween(GlassMotion.Page, Curve) ——**没乘动效强度
            // 乘数**（GlassMotion.scale），而同一次转场里的 fade 用的是 tweenPage()（按档缩放）。
            // 后果：选「增强」档时 expand 360ms 固定、fade 504ms，两者不同步收束，展开到位前
            // 文字已经全显/全隐，观感是「方框先动、内容抢跑」。同一转场必须同一个 token。
            androidx.compose.animation.AnimatedVisibility(
                // 横幅只在**首页**显示：它是「本页所有改动都不会保存」的前提说明，
                // 而二级页里再去复述一遍只会占掉首屏高度（用户在首页已经看到过，
                // 且首页是必经之路）。若某天真需要在子页也提示，应该做成吸顶条。
                visible = corruptSnapshot && openGroup == null,
                enter = androidx.compose.animation.expandVertically(GlassMotion.tweenPage()) +
                    androidx.compose.animation.fadeIn(GlassMotion.tweenPage()),
                exit = androidx.compose.animation.shrinkVertically(GlassMotion.tweenPage()) +
                    androidx.compose.animation.fadeOut(GlassMotion.tweenPage()),
            ) {
                SectionHeader("设置未保存")
                SettingsCard {
                    // 纯说明行：不传 onClick（避免"可点但无反应"的假交互），
                    // 尾部也不留箭头——它不是一个能进去的设置项
                    SettingRow(
                        GlassIcons.Book, "设置快照读取失败",
                        "旧快照已保留在应用私有目录；当前改动不会写盘",
                        onClick = null,
                        trailing = {},
                    )
                    // 唯一出口：用户明确同意后才放弃旧快照重新开始存盘。
                    //
                    // **必须二次确认**（本轮修复）：这是全 App 唯一一处「一次点击永久丢弃
                    // 歌单/收藏/设置」的入口，而它过去点下去就执行——警示色只是让它看起来
                    // 危险，并没有给用户反悔的机会。同仓「删除歌单」早就做了两步确认
                    // （CollectionActionsSheet 的 showDeleteConfirm），这里对齐：本行只负责
                    // 打开确认面板，真正执行由确认面板里的破坏性行触发。
                    SheetAction(
                        icon = GlassIcons.Trash,
                        label = "放弃旧快照并重新开始",
                        subtitle = "以当前显示的状态覆盖保存（不可撤销）",
                        // 破坏性动作必须走警示色：副标题写了「不可撤销」却渲染成普通行，
                        // 与同页「清除背景照片」等破坏性项的视觉语言不一致，一次点击即永久
                        // 丢弃旧快照里的歌单/收藏/设置
                        destructive = true,
                        onClick = {
                            modal.show {
                                ModalSheet(modal, "放弃旧快照？") {
                                    Text(
                                        "旧快照里的歌单、收藏与设置将被永久丢弃，" +
                                            "并以当前显示的状态重新开始保存。此操作不可撤销。",
                                        color = LocalGlassColors.current.textSecondary,
                                        style = GlassText.Body,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 6.dp, vertical = S.Gap8.dp),
                                    )
                                    Spacer(Modifier.height(S.Gap8.dp))
                                    SheetAction(
                                        icon = GlassIcons.Trash,
                                        label = "确认放弃并重新开始",
                                        subtitle = "不可撤销",
                                        destructive = true,
                                        onClick = {
                                            modal.requestDismiss()
                                            onRebuildFromCorrupt()
                                        },
                                    )
                                }
                            }
                        },
                    )
                }
            }

            // ── 首页导航（openGroup == null 时显示）──
            if (openGroup == null) {
                SettingsGroupNav(
                    groups = settingsGroups(
                        state = state,
                        scanning = scanning,
                        backgroundPhotoSelected = backgroundPhotoSelected,
                        // 桌面歌词开关由 app 层持有（独立 DataStore），经参数下传——
                        // 摘要要写真话，不能在模块内凭空猜
                        desktopLyricsEnabled = desktopLyricsEnabled,
                    ),
                    // 首页卡片 → 二级页。必须走 state.openSettingsGroup（不能写局部变量），
                    // 且**显式传 returnGroup = null**：从首页进来的一律回首页，
                    // 不能继承上一次三级页留下的返回目标（否则「关于 → 支持开发者 → 返回 →
                    // 外观 → 返回」会落回关于，用户会以为返回键坏了）。
                    onOpen = { state.openSettingsGroup(it, returnGroup = null) },
                )
            } else {
                // 二级页头：返回箭头 + 组名。返回箭头是页内**主**出口，所以它必须显眼：
                // 用 GlassIconButton 而非纯文本（读屏与触摸都有明确边界）。
                //
                // onBack 直接调 `state.backInSettings()`，与 GlassApp 的系统返回键
                // **同一条通路**。这不是省事：两条返回路径各写一份实现时必然出现
                // 「箭头能回、返回键不能回」（上一轮就是这么坏的）。同理**不要**在这里
                // 写 `state.settingsGroup = null`——那会跳过 settingsReturnGroup，
                // 把三级页（支持开发者）的返回直接扔回设置首页，用户在关于页和
                // 开发者页之间往返时会被踢出两层。
                val title = settingsGroupTitle(openGroup.orEmpty())
                SettingsSubPageHeader(
                    title = title,
                    onBack = { state.backInSettings() },
                )
            }

            // ── 三级页：支持开发者（从「关于」的入口进来，返回回「关于」）──
            //
            // 判据用组名而不是另外加一个布尔：`settingsGroup` 已经是「当前在设置的第几层」
            // 的唯一真相，再加一个屏内标志就是第二个真相，返回时两者迟早不同步。
            // 注意「支持开发者」**不在** settingsGroups(...) 的 5 张导航卡里——
            // 它是三级页，首页不该列出它（首页只列 5 个分组，见该函数的 KDoc）。
            //
            // **这里不画页头**：上面那个 `if (openGroup == null) ... else ...` 已经给
            // 所有非首页分组统一画了「返回 + 组名」头（组名就是「支持开发者」）。
            // 第一版在这里又画了一次 SettingsSubPageHeader —— 一屏两行同样的标题，
            // 正是文件头 KDoc 记过的那类「必须二选一」错误。三级页与二级页的页头
            // 视觉上本来就应当完全一致，区别只在**返回落点**（由 settingsReturnGroup 承载，
            // 见 `AppState.backInSettings` 的 KDoc），不在页头样式。
            if (openGroup == SupportDeveloperGroup) {
                SettingsSupportPage()
            }

            if (openGroup == "外观") {
            SectionHeader("外观")
            SettingsCard {
                // 整行可点 + 内层开关双通道（GlassToggle 的 clickable 在内层会消费，不会双触发）
                SettingRow(
                    // A5 图标语义：取色这件事的**来源是封面**——唱片（Disc）一眼指向「封面」，
                    // 而 Wave 已被整组音效占用，取色再用它等于两件不同的事共用一个符号
                    GlassIcons.Disc, "跟随封面取色",
                    // P2-6：副标题必须与三态真相一致——自定义色存在时封面取色被短路，开关说了不算
                    //
                    // P2 文案修复（本轮）：旧文案写「点上方主题色可恢复」，但「主题色」这一行
                    // 在**本行下方**（顺序：跟随封面取色 → 背景来源 → 主题色 → 玻璃强度）。
                    // 用户按「上方」抬头找不到出口，而这一行又正处在「开关拨了没反应」的
                    // 最容易上火的状态（被短路的是它自己）。方位词写错不是错别字级别的问题：
                    // 它是这一行唯一的可行动指引。写「下方」后与界面事实一致。
                    if (state.customSeedArgb != null) {
                        "已被自定义主题色覆盖（点下方主题色可恢复）"
                    } else if (state.visualStyle == VisualStyle.Skeuomorph) {
                        // 拟物档：背景是机身，主题色只以极低强度掺入环境光。
                        // 旧文案恒写「极光背景取自当前专辑封面」——拟物档下屏幕上根本没有
                        // 极光，这行承诺了一件不存在的事（摘要的唯一职责是指对路）。
                        "机身环境光取自当前专辑封面"
                    } else {
                        "极光背景取自当前专辑封面"
                    },
                    onClick = { state.applyFollowCover(!state.followCover) },
                    trailing = { GlassToggle(state.followCover, onToggle = state::applyFollowCover) },
                )
                RowDivider()
                // 照片背景（v11）：极光 / 照片二选一。
                //
                // 为什么用行内 SettingDropdown 而不是「选图按钮」：模式与图片是**两个决策**
                // （要不要照片背景 / 用哪张），但用户心里其实是同一件事。折中做法——
                // 下拉切模式，行本身点了就拉选择器：切到「照片」时自动进选图流程，
                // 已经是照片时点击 = 换一张。比两个独立控件少一次点击，也不容易「切了模式但没选图」。
                SettingRow(
                    // 图标语义：Moon 已被「深色模式」占用、Disc 被取色占用。这里用 Wave（背景是
                    // 整屏铺开的氛围），与音效那一组的 Wave 分属不同分组，且副标题已自解释
                    GlassIcons.Wave, "背景来源",
                    when {
                        state.backgroundMode == BackgroundMode.Photo && state.photoBacking?.bitmap != null ->
                            "照片 · 玻璃会模糊这张图（再点可更换）"
                        state.backgroundMode == BackgroundMode.Photo ->
                            "照片（载入中 / 已失效，暂时显示极光）"
                        backgroundPhotoSelected -> "极光 · 已保存一张照片，可切换"
                        else -> "极光 · 从当前专辑封面派生"
                    },
                    onClick = {
                        when {
                            // 照片模式且已就绪：点击 = 换一张
                            state.backgroundMode == BackgroundMode.Photo -> onPickBackgroundPhoto()
                            // 已有照片但当前是极光：点击 = 切回照片（不再让用户选一次图）
                            backgroundPhotoSelected -> state.selectBackgroundMode(BackgroundMode.Photo)
                            // 从没有过照片：拉选择器
                            else -> onPickBackgroundPhoto()
                        }
                    },
                    trailing = {
                        SettingDropdown(
                            value = state.backgroundMode.label,
                            label = "背景来源",
                            selected = state.backgroundMode,
                            options = BackgroundMode.entries.map { mode ->
                                DropdownOption(
                                    label = mode.label,
                                    subtitle = when (mode) {
                                        BackgroundMode.Aurora -> "由当前专辑封面取色派生的极光"
                                        BackgroundMode.Photo -> "用一张自己的照片铺满整屏"
                                    },
                                    payload = mode,
                                )
                            },
                            onSelect = { mode ->
                                // 切到照片但从未选过图：直接接上选择器，免得多点一次
                                if (mode == BackgroundMode.Photo && !backgroundPhotoSelected) {
                                    onPickBackgroundPhoto()
                                } else {
                                    state.selectBackgroundMode(mode)
                                }
                            },
                        )
                    },
                )
                if (backgroundPhotoSelected) {
                    RowDivider()
                    SheetAction(
                        icon = GlassIcons.Trash,
                        label = "清除背景照片",
                        subtitle = "连同私有副本一起删除，回到极光",
                        destructive = true,
                        onClick = onClearBackgroundPhoto,
                    )
                }
                RowDivider()
                // F2 自定义主题色：三态（自定义 > 跟随封面 > 品牌靛紫），选中即生效
                SettingRow(
                    // A5 图标语义：主题色是**用户手动指定**的颜色（10 色预设 + 可自定义），
                    // 用铅笔表达「自己动手改」，与上一行「跟随封面」的自动取色（Disc）形成
                    // 自动 vs 手动的对照。原 Wave 与整组音效撞车，且与上一行同名同形
                    GlassIcons.Edit, "主题色",
                    when {
                        state.customSeedArgb != null -> "自定义颜色（点击更换）"
                        // P3-9/P2-2：不虚标「可改为自定义」——只有预设色板，如实说
                        state.followCover -> "跟随封面 · 可选 10 种预设色"
                        else -> "品牌靛紫 · 可选 10 种预设色"
                    },
                    onClick = {
                        modal.show {
                            ModalSheet(modal, "主题色") {
                                // 实时预览：随选中色变化（取自同一 fromSeed 派生）
                                val previewSeed = state.customSeedArgb?.let { Color(it) }
                                AuroraMiniPreview(
                                    palette = previewSeed?.let { AuroraPalette.fromSeed(it) } ?: state.palette,
                                )
                                Spacer(Modifier.height(S.Gap16.dp))
                                // A1 字号 token：本节小标题与全 App 分组标题同档（12sp + 0.3 字距）——
                                // 层级靠**字距**表达而不是靠一个游离的 12sp 裸值；颜色仍是 tertiary 保持弱层级
                                Text(
                                    "预设色板",
                                    style = GlassText.SectionLabel,
                                    color = LocalGlassColors.current.textTertiary,
                                    modifier = Modifier.padding(start = 4.dp, bottom = S.Gap8.dp),
                                )
                                ThemeColorSwatches(
                                    seeds = AuroraPalette.PresetSeeds,
                                    // P2-5：高亮「当前生效色」三态折算（自定义 > 封面取色 > 品牌靛紫）——
                                    // 旧实现只在用户自定义后才有高亮，跟随封面/品牌态 10 个色点全灰
                                    selected = state.effectiveSeed,
                                    onSelect = { state.setCustomSeed(it.toArgb()) },
                                )
                                Spacer(Modifier.height(S.Gap12.dp))
                                // 恢复默认：清掉自定义 → 回到「跟随封面 / 品牌」二态
                                if (state.customSeedArgb != null) {
                                    SheetAction(
                                        icon = GlassIcons.Back,
                                        label = "恢复默认",
                                        subtitle = "回到跟随封面 / 品牌靛紫",
                                        onClick = {
                                            state.setCustomSeed(null)
                                            modal.requestDismiss()
                                        },
                                    )
                                }
                                SheetAction(
                                    icon = GlassIcons.Down, label = "完成", onClick = modal::requestDismiss,
                                )
                            }
                        }
                    },
                )
                RowDivider()
                // ── 视觉风格（v12，本轮新增）：全 App 的**材质语言**二选一 ──
                //
                // ## 为什么放在「玻璃强度」**之前**
                // 这两行是「先选语言，再调浓度」的关系：视觉风格决定用的是不是液态玻璃，
                // 玻璃强度只调液态玻璃本身的浓淡。放反了会读成「浓度档下面还有一个浓度档」，
                // 而实际上切到拟物档后下面那行只剩「你切回玻璃时会用到」的预备作用。
                //
                // ## 为什么图标用 Tag（以及为什么**不能**用 Sun）
                //
                // 图标集里没有一枚「材质 / 表面」语义的符号（本任务不能改 core/ui 去新画一枚），
                // 所以只能在既有集里找语义最不误导的那枚：Tag = **分类标签**，
                // 「选哪一种视觉语言」正是一次分类选择，不夸大也不误导。
                //
                // **Sun 是看着最像、但必须排除的那一枚**：本工程里 Sun/Moon 是成对的
                // 「切换深浅色」符号（曲库页头用 Sun 切主题），而「深色模式」那一行就在
                // 本组下方第 4 行、用的正是 Moon。把 Sun 放在这里会让两枚相邻的
                // 太阳/月亮读成同一对亮度控制，用户会以为「视觉风格」也是调明暗的
                // ——这正是本轮 A5 在清算的那类跨语义复用（Gear 被当太阳用的翻版）。
                // 「拟物 vs 玻璃确实是光的行为」这个联想不足以抵消这个误读。
                SettingRow(
                    GlassIcons.Tag, "视觉风格",
                    // 本轮（2026-11）文案重写：两套语言都做了重设计，旧描述（「不透明贴面 +
                    // 斜面 + 投影」）会让人以为拟物是「整屏浮雕」。新描述强调各自的**取舍**：
                    // 玻璃是「内容透出来」，拟物是「内容安静、控件有实体感」。
                    when (state.visualStyle) {
                        VisualStyle.Glass -> "液态玻璃 · 晨雾质感，背景柔和透出"
                        VisualStyle.Skeuomorph -> "拾音拟物 · 机身平面、按键有实体手感"
                    },
                    // 整行点击 = 在两个语言之间直接对切（与下方的下拉双通道，同既有各行口径）。
                    // 二选一时这是最省事的路径：不必展开下拉再点一次。
                    onClick = {
                        state.selectVisualStyle(
                            if (state.visualStyle == VisualStyle.Glass) {
                                VisualStyle.Skeuomorph
                            } else {
                                VisualStyle.Glass
                            },
                        )
                    },
                    trailing = {
                        SettingDropdown(
                            value = state.visualStyle.label,
                            label = "视觉风格",
                            selected = state.visualStyle,
                            options = VisualStyle.entries.map { style ->
                                DropdownOption(
                                    label = style.label,
                                    // 副标题必须说清「这不是同一套东西的另一档」：
                                    // 用户看到两个并列选项时的默认预期是「浓度/强弱差异」，
                                    // 而这里是两套互不兼容的材质语法（见 AppState.VisualStyle 的 KDoc）。
                                    subtitle = when (style) {
                                        VisualStyle.Glass -> "半透明 + 背景模糊，内容与背景连成一片"
                                        VisualStyle.Skeuomorph -> "不透明机身 + 实体按键，内容安静、控件有手感"
                                    },
                                    payload = style,
                                )
                            },
                            onSelect = { state.selectVisualStyle(it) },
                        )
                    },
                )
                RowDivider()
                SettingRow(
                    // A5（本轮修正）：玻璃强度是**外观/材质**档位，用 Wave 表达「浓度曲线」——
                    // 三档玻璃差异本质是填充与描边的浓淡变化，与音效那条频响曲线同属
                    // 「程度」语义。原 Gear（且当时其实是太阳形）与下方「动效强度」完全同形，
                    // 相邻两行看起来是同一个功能，是这三行里最严重的一处混淆。
                    GlassIcons.Wave, "玻璃强度",
                    // ## 副标题必须**按档位**分支（本轮修复「看起来像死控件」）
                    //
                    // 拟物档下 glassLevel 仍然被持久化、切回玻璃档立刻生效（见
                    // AppState.visualStyle 的 KDoc），但拟物**渲染不读它**。若副标题
                    // 仍然恒写「填充与描边浓淡，影响全部玻璃表面」，用户切到拟物档后
                    // 拖这一行会发现屏幕毫无变化——他会判定「这个控件坏了」，
                    // 而不是「这一档不用它」。**不生效 ≠ 失效**，这句差别只能靠文案说清。
                    //
                    // 也不用「更浓/更强」这类词（那些是同一套材质内部的程度差异），
                    // 拟物是另一种材质语言，两者不构成程度关系。
                    when (state.visualStyle) {
                        VisualStyle.Glass -> "填充与描边浓淡，影响全部玻璃表面"
                        VisualStyle.Skeuomorph ->
                            "拟物档不读取此项 · 切回液态玻璃后立即生效（当前档位会被记住）"
                    },
                    // 真机反馈改为**行内下拉**（系统设置「自动息屏」同款）：
                    // 三选一的档位不值得付一次模态转场 + 整屏遮挡的代价。
                    // 回调在选中瞬间生效，没有「确定」——单选没有可失败的提交。
                    //
                    // 拟物档下**不禁用**这个下拉：禁用会让用户以为「拟物下不能设玻璃浓度」，
                    // 而事实是他可以提前设好、切回玻璃时正好是想要的那档。文案已经说明
                    // 「不读取此项」，控制本身保持可操作是诚实的。
                    trailing = {
                        SettingDropdown(
                            value = state.glassLevel.label,
                            label = "玻璃强度",
                            selected = state.glassLevel,
                            options = GlassIntensity.entries.map { level ->
                                DropdownOption(
                                    label = level.label,
                                    subtitle = when (level) {
                                        GlassIntensity.Transparent -> "填充与描边减淡，更通透"
                                        GlassIntensity.Standard -> "设计稿默认档位"
                                        GlassIntensity.Rich -> "填充加浓，玻璃感更强"
                                    },
                                    payload = level,
                                )
                            },
                            onSelect = { state.glassLevel = it },
                        )
                    },
                )
                RowDivider()
                SettingRow(
                    // A5（本轮修正）：动效强度是**时间维度**的档位（快/慢），
                    // 用 Compass 表达「节奏/指针走动」——它与上一行（材质浓度）在语义上
                    // 是两件不同的事，必须给不同的符号。原与玻璃强度共用 Gear，两行同形。
                    GlassIcons.Compass, "动效强度", "过渡动画的快慢，不影响极光与波形",
                    trailing = {
                        SettingDropdown(
                            value = state.motionLevel.label,
                            label = "动效强度",
                            selected = state.motionLevel,
                            options = MotionIntensity.entries.map { level ->
                                DropdownOption(
                                    label = level.label,
                                    subtitle = when (level) {
                                        MotionIntensity.Restrained -> "过渡更快（×0.6）"
                                        MotionIntensity.Standard -> "设计稿默认档位"
                                        MotionIntensity.Enhanced -> "过渡更绵长（×1.4）"
                                    },
                                    payload = level,
                                )
                            },
                            onSelect = { state.motionLevel = it },
                        )
                    },
                )
                RowDivider()
                // P3-59：深浅色常驻入口（此前只有悬浮 Chrome 右上角的太阳图标一处，不可发现）
                SettingRow(
                    GlassIcons.Moon, "深色模式",
                    if (state.themeMode == ThemeMode.Dark) "已开启" else "已关闭",
                    onClick = { state.toggleTheme() },
                    trailing = { GlassToggle(state.themeMode == ThemeMode.Dark, onToggle = { state.toggleTheme() }) },
                )
            }

            }

            if (openGroup == "播放") {
            SectionHeader("播放")
            SettingsCard {
                // E2：自启动引导（仅国产激进省电 ROM 提供，subtitle 为 null 即不显示）
                if (autoStartGuideSubtitle != null) {
                    SettingRow(
                        // A5 图标语义：自启动/后台保活是**系统行为**开关（受 ROM 省电策略支配），
                        // 与音效无关；齿轮表达「系统设置」。原 Wave 让它看起来像一个音效选项，
                        // 而它就排在均衡器正上方——两项视觉上完全无法区分（本轮的核心问题）
                        GlassIcons.Gear, "自启动与后台运行", autoStartGuideSubtitle,
                        onClick = onAutoStartGuide,
                    )
                    RowDivider()
                }
                SettingRow(
                    // A5 图标语义：均衡器保留 Wave —— 它本来就是一条频响曲线，
                    // 是 Wave 这个符号**唯一名副其实**的用法。同时它是下方三条音效链的
                    // 组头，Wave 在这里代表「整组音效」这一族
                    GlassIcons.Wave, "均衡器", state.eqPreset.label,
                    onClick = {
                        modal.show {
                            ModalSheet(modal, "均衡器") {
                                EqPreset.entries.forEach { preset ->
                                    SheetAction(
                                        icon = GlassIcons.Wave,
                                        label = preset.label,
                                        subtitle = if (preset == EqPreset.Off) "不启用音效" else "预设 · 实时生效",
                                        emphasized = state.eqPreset == preset,
                                        onClick = {
                                            state.selectEqPreset(preset) // 立即下发服务端 Equalizer
                                            modal.requestDismiss()
                                        },
                                    )
                                }
                            }
                        }
                    },
                )
                RowDivider()
                // ── 音效链：响度归一 / 低音增强 / 虚拟环绕 ──────────────────────
                // 为什么这三项放在「均衡器」下面：它们是同一类东西（都挂播放器音频会话的
                // audiofx），用户的心智模型是「音效」一组。也正因为它们会改变听感，
                // 三项**默认全关**，与上面 EQ 默认「关闭」保持一致口径。
                //
                // A5 图标语义（本轮唯一的**有意复用**）：这三项与上面的均衡器共用 Wave。
                // 复用在这里是对的而不是偷懒——它们确实是一族（同一 audiofx 会话、同一种
                // 「改变听感」的效果），同一符号反而把「这是同一组音效」说清楚了；
                // 真正要修的是**跨语义**复用（取色/自启动/桌面歌词/文件访问此前也都用 Wave），
                // 那些已在本轮各自拆走。
                SettingRow(
                    GlassIcons.Wave, "响度归一", loudnessNormSubtitle(state.audioFx),
                    onClick = { state.setLoudnessNorm(!state.audioFx.loudnessNorm) },
                    trailing = {
                        GlassToggle(
                            state.audioFx.loudnessNorm,
                            onToggle = { state.setLoudnessNorm(it) },
                        )
                    },
                )
                RowDivider()
                // 低音增强：整行可点开档位面板，尾部开关直接开关（与「扫描过滤」同款双通道）
                SettingRow(
                    GlassIcons.Wave, "低音增强", bassBoostSubtitle(state.audioFx),
                    onClick = {
                        modal.show {
                            ModalSheet(modal, "低音增强") {
                                // 「关闭」作为显式一档：否则用户进了这个面板却无法从这里关掉，
                                // 只能退出去拨尾部开关（面板里关不掉是常见的体验断点）
                                SheetAction(
                                    icon = GlassIcons.Wave,
                                    label = "关闭",
                                    subtitle = "不改变原始低频",
                                    emphasized = !state.audioFx.bassBoost,
                                    onClick = {
                                        state.setBassBoost(false)
                                        modal.requestDismiss()
                                    },
                                )
                                BassBoostStrengths.forEachIndexed { level, _ ->
                                    SheetAction(
                                        icon = GlassIcons.Wave,
                                        label = BassBoostLevelLabels[level],
                                        subtitle = "强度 ${BassBoostStrengths[level]}（0-1000）",
                                        // 只有「已开启且档位相同」才高亮：关闭状态下没有任何一档在生效
                                        emphasized = state.audioFx.bassBoost &&
                                            level == state.audioFx.safeBassLevel,
                                        onClick = {
                                            // 选档位隐含「打开」：用户点「强」的意图是听到强低音，
                                            // 而不是「记住这个档但先别生效」（旧式两段操作反直觉）
                                            state.selectBassLevel(level)
                                            state.setBassBoost(true)
                                            modal.requestDismiss()
                                        },
                                    )
                                }
                            }
                        }
                    },
                    trailing = {
                        GlassToggle(
                            state.audioFx.bassBoost,
                            onToggle = { state.setBassBoost(it) },
                        )
                    },
                )
                RowDivider()
                SettingRow(
                    GlassIcons.Wave, "虚拟环绕", virtualizerSubtitle(state.audioFx),
                    onClick = {
                        modal.show {
                            ModalSheet(modal, "虚拟环绕") {
                                SheetAction(
                                    icon = GlassIcons.Wave,
                                    label = "关闭",
                                    subtitle = "保持原始声场",
                                    emphasized = !state.audioFx.virtualizer,
                                    onClick = {
                                        state.setVirtualizer(false)
                                        modal.requestDismiss()
                                    },
                                )
                                VirtualizerStrengths.forEachIndexed { level, _ ->
                                    SheetAction(
                                        icon = GlassIcons.Wave,
                                        label = VirtualizerLevelLabels[level],
                                        subtitle = "强度 ${VirtualizerStrengths[level]}（0-1000）· 耳机下更明显",
                                        emphasized = state.audioFx.virtualizer &&
                                            level == state.audioFx.safeVirtualLevel,
                                        onClick = {
                                            state.selectVirtualLevel(level)
                                            state.setVirtualizer(true)
                                            modal.requestDismiss()
                                        },
                                    )
                                }
                            }
                        }
                    },
                    trailing = {
                        GlassToggle(
                            state.audioFx.virtualizer,
                            onToggle = { state.setVirtualizer(it) },
                        )
                    },
                )
                RowDivider()
                SettingRow(
                    // A5 图标语义：定时关闭到点后是「睡着」——月亮直接对应夜间/入睡场景；
                    // 与同组音效的 Wave、系统项的 Gear 区分开。下方档位面板沿用同一枚，
                    // 让「行 → 面板」保持同一个符号（用户不必重新认一遍）
                    GlassIcons.Moon, "定时关闭", sleepSubtitle,
                    onClick = {
                        modal.show {
                            ModalSheet(modal, "定时关闭") {
                                val activeMode = state.effectiveSleepTimerMode()
                                SleepTimerOptions.forEach { option ->
                                    SheetAction(
                                        icon = GlassIcons.Moon,
                                        label = sleepTimerLabel(option),
                                        subtitle = sleepTimerSubtitle(
                                            option = option,
                                            activeMode = activeMode,
                                            remainingSec = sleepRemainingSec,
                                            // 必须传：-2 档在列表循环/随机下**永不触发**，
                                            // 副标题要如实说明并给出可执行动作（见 sleepTimerSubtitle
                                            // 的 KDoc）。漏传会走默认 null/false，恒显示
                                            // 「整个队列播完后暂停」——用户开着循环选它，整夜放歌。
                                            repeatMode = state.repeatMode,
                                            shuffleOn = state.shuffleOn,
                                        ),
                                        // P3-47（与播放页同款）：无定时时「取消定时」不高亮
                                        emphasized = option != 0 && option == activeMode,
                                        onClick = {
                                            state.setSleepTimer(option)
                                            modal.requestDismiss()
                                        },
                                    )
                                }
                            }
                        }
                    },
                )
                RowDivider()
                SettingRow(
                    // A5 图标语义：歌词是「唱出来的字」，Mic 的发声语义贴它；
                    // 与音效族的 Wave、系统档的 Gear 都拉开
                    GlassIcons.Mic, "歌词", "优先读取本地 .lrc 歌词文件",
                    onClick = { state.lyricEnabled = !state.lyricEnabled },
                    trailing = { GlassToggle(state.lyricEnabled, onToggle = { state.lyricEnabled = it }) },
                )
                RowDivider()
                // 桌面歌词（P0-8）：悬浮窗单行歌词。开关形态与「歌词」同款双通道。
                // 权限判定整个在 app 层的 [onDesktopLyricsEnabledChange] 里做：
                // 未授权时它不真正开开关、而是跳系统授权页——本屏不需要知道 canDrawOverlays
                SettingRow(
                    // A5 图标语义：桌面歌词 = 悬浮在**桌面（Home 屏）**上的一行字，
                    // Home 的房子直接表达「出现在桌面上」；比建议的 Mic 更好——
                    // Mic 已给「歌词」（本地 .lrc 是唱出来的字），两行同为歌词但一个是
                    // 应用内一个是桌面悬浮，必须用不同符号区分开启范围
                    GlassIcons.Home, "桌面歌词",
                    if (desktopLyricsEnabled) "播放中显示悬浮歌词，可拖动" else "未开启 · 需悬浮窗权限",
                    onClick = { onDesktopLyricsEnabledChange(!desktopLyricsEnabled) },
                    trailing = { GlassToggle(desktopLyricsEnabled, onToggle = onDesktopLyricsEnabledChange) },
                )
                // ── 桌面歌词外观（本轮新增）──
                //
                // ## 为什么这两行在**未开启时也显示**
                // 允许「先调好外观再打开」——用户调完再开，第一次看到的就是想要的样子；
                // 若隐藏，他得「先开、看不对、回来调、再开」，而悬浮窗每次改动都会
                // 立刻反映在桌面上，反复开关是纯干扰。副标题补「开启后生效」以免误解。
                //
                // ## 为什么是「循环点选」而不是下拉/滑杆
                // 档位数量少（字号 5 档、透明度 4 档），且见 DesktopLyricsStyle 的 KDoc：
                // 连续滑杆会让悬浮窗每帧 updateViewLayout（低端机抖动）。循环点选
                // 每次点击只是一次离散变更，且「一直点」总能回到起点，不必额外提供方向键。
                if (desktopLyricsOnChangeAvailable) {
                    RowDivider()
                    SettingRow(
                        // Edit 图标：与「主题色」那行同为「用户手动指定某个值」的语义，
                        // 与自动派生（Disc 取色）区分开
                        GlassIcons.Edit, "桌面歌词字号",
                        DesktopLyricsStyle.FontSteps.getOrNull(desktopLyricsFontStep)?.let {
                            if (desktopLyricsEnabled) "$it · 点按切换" else "$it · 开启后生效"
                        } ?: "标准",
                        // 去掉右箭头：本行是**原地循环点选**，不是「进入下一级」。
                        // 本文件自己的规矩是「箭头＝可以进去」，带箭头会让用户期待一个
                        // 五档面板，结果值静默跳一档、看不到其余档也无法直接回退。
                        trailing = {},
                        onClick = {
                            val next = (desktopLyricsFontStep + 1).mod(DesktopLyricsStyle.FontSteps.size)
                            onDesktopLyricsFontStepChange(next)
                        },
                    )
                    RowDivider()
                    SettingRow(
                        GlassIcons.Edit, "桌面歌词不透明度",
                        DesktopLyricsStyle.OpacitySteps.getOrNull(desktopLyricsOpacityStep)?.let {
                            if (desktopLyricsEnabled) "$it · 点按切换" else "$it · 开启后生效"
                        } ?: "清晰",
                        // 同上：原地循环点选，不该带「可进入」的右箭头
                        trailing = {},
                        onClick = {
                            val next = (desktopLyricsOpacityStep + 1).mod(DesktopLyricsStyle.OpacitySteps.size)
                            onDesktopLyricsOpacityStepChange(next)
                        },
                    )
                    RowDivider()
                    SettingRow(
                        // Lock 图标（本轮新增）：锁的图形直接就是「不能拖了」，
                        // 与 Gear（设置语义）区分开——用 Gear 会让用户以为点进去是面板
                        GlassIcons.Lock, "锁定歌词位置",
                        if (desktopLyricsLocked) "不可拖动 · 触摸穿透到下层应用" else "可拖动到任意位置",
                        onClick = { onDesktopLyricsLockedChange(!desktopLyricsLocked) },
                        trailing = {
                            GlassToggle(desktopLyricsLocked, onToggle = onDesktopLyricsLockedChange)
                        },
                    )
                }
            }

            }

            if (openGroup == "曲库") {
            SectionHeader("曲库")
            SettingsCard {
                // 音频权限行：运行时弹窗被拒后应用内的唯一再引导入口。
                // 旧实现的拒绝分支只做 abandonPendingPlayback + markLibrarySettled，曲库页呈现
                // 空列表、设置页又只有「歌词文件访问」（MANAGE_EXTERNAL_STORAGE 那条），
                // 想再授权只能靠用户自己摸进系统设置 → 应用内全程无引导。仅未授权时出现。
                if (audioDenied) {
                    SettingRow(
                        GlassIcons.Mic, "音乐与音频访问",
                        "未授权 · 点击授权后才能扫描本地曲库",
                        onClick = onRequestAudioPermission,
                    )
                    RowDivider()
                }
                SettingRow(
                    GlassIcons.Folder, "扫描音乐",
                    // P2-④：计数走可见曲库（visibleLibrary）而不是原始 library ——
                    // 开着过滤时「已收录」若报全量（含已过滤的短音频），会与曲库页实际条数不符
                    if (scanning) "扫描中…" else "已收录 ${state.visibleLibrary().size} 首",
                    onClick = {
                        if (!scanning) {
                            scanning = true
                            onRescan { scanning = false } // 成功/失败/结果相同都复位，不依赖曲库状态变化
                        }
                    },
                )
                RowDivider()
                // B3 开关：滤除 <30s 音频。P2-④：过滤已下沉到 visibleLibrary() 输出层，
                // 切换后**立即**影响曲库/文件夹/搜索，不再需要重扫（副标题同步改口径）
                SettingRow(
                    GlassIcons.Folder, "扫描过滤",
                    if (state.scanFilterShort) "已过滤短于 30 秒的音频 · 立即生效" else "收录全部音频 · 立即生效",
                    // P3-60：整行可点（此前只有尾部开关可点，命中区太小）
                    onClick = { state.toggleScanFilter() },
                    trailing = { GlassToggle(state.scanFilterShort, onToggle = { state.toggleScanFilter() }) },
                )
                RowDivider()
                SettingRow(
                    GlassIcons.Folder, "过滤文件夹",
                    "${state.excludedFolders.size} 个目录已排除",
                    onClick = {
                        modal.show {
                            ModalSheet(modal, "过滤文件夹") {
                                // P2-28：列表 = 当前曲库目录 ∪ 已排除的失效目录。旧实现只遍历
                                // folderOptions()（当前曲库存在的目录），目录被移出/改名后其排除项
                                // 从面板消失，永远无法取消——排除集只增不减
                                val options = state.folderOptions()
                                val orphans = state.excludedFolders.filter { f -> options.none { it.first == f } }
                                options.forEach { (folder, count) ->
                                    val excluded = folder in state.excludedFolders
                                    SheetAction(
                                        icon = GlassIcons.Folder,
                                        label = folder,
                                        subtitle = if (excluded) "$count 首 · 已排除" else "$count 首",
                                        emphasized = excluded,
                                        onClick = {
                                            if (excluded) state.excludedFolders.remove(folder) else state.excludedFolders.add(folder)
                                        },
                                    )
                                }
                                orphans.forEach { folder ->
                                    SheetAction(
                                        icon = GlassIcons.Folder,
                                        label = folder,
                                        subtitle = "0 首 · 目录当前不存在",
                                        emphasized = true,
                                        onClick = { state.excludedFolders.remove(folder) },
                                    )
                                }
                                if (state.excludedFolders.isNotEmpty()) {
                                    SheetAction(
                                        icon = GlassIcons.Folder,
                                        label = "清空全部排除项",
                                        subtitle = "共 ${state.excludedFolders.size} 个目录",
                                        onClick = { state.excludedFolders.clear() },
                                    )
                                }
                                SheetAction(icon = GlassIcons.Down, label = "完成", onClick = modal::requestDismiss)
                            }
                        }
                    },
                )
                RowDivider()
                SettingRow(
                    // A5 图标语义：这一行要的是**文件访问授权**（MANAGE_EXTERNAL_STORAGE，
                    // 去下载目录读 .lrc），主体是「文件系统」而不是「歌词内容」，
                    // 所以归入同组 Folder 的「目录/文件」家族；原 Book 与日志/版本行混淆
                    GlassIcons.Folder, "歌词文件访问",
                    if (allFilesGranted) "已授权 · 点击重新扫描歌词" else "未授权 · 点击去授权（读取下载目录等歌词）",
                    onClick = onAllFilesAccess,
                )
                if (state.hiddenBuiltInPlaylists.isNotEmpty()) {
                    RowDivider()
                    SettingRow(
                        GlassIcons.Book, "恢复内置歌单",
                        "重新显示 ${state.hiddenBuiltInPlaylists.size} 个内置歌单",
                        onClick = { state.restoreBuiltInPlaylists() },
                    )
                }
            }

            // D2/D3：歌单迁移与全量备份（SAF 选择器由 app 层拉起）
            }

            if (openGroup == "数据") {
            SectionHeader("数据")
            SettingsCard {
                SettingRow(
                    GlassIcons.Down, "导入 M3U 歌单",
                    "从 .m3u / .m3u8 文件新建歌单（按文件路径匹配曲库）",
                    onClick = onImportM3u,
                )
                RowDivider()
                SettingRow(
                    GlassIcons.Disc, "导出备份",
                    "歌单 / 收藏 / 设置 / 播放次数 → JSON",
                    onClick = onExportBackup,
                )
                RowDivider()
                SettingRow(
                    GlassIcons.Book, "恢复备份",
                    "从备份文件还原全部数据（覆盖当前）",
                    onClick = onImportBackup,
                )
                RowDivider()
                // ── 崩溃日志（04 报告 #3）──
                // 崩溃采集本身早已实现（CrashHandler 把未捕获异常写到 filesDir/crash/），
                // 但此前**没有任何导出入口** —— 日志躺在用户手机里，开发者永远拿不到，
                // 等于「用户反馈闪退时你手上只有 0 条信息」这个问题没解决。
                // 这一行就是那条闭环的最后一米：用户崩了 → 进设置 → 导出 → 发给你。
                //
                // 无日志时**也显示**这一行（副标题写「暂无记录」）而不是整行隐藏：
                // 隐藏会让用户以为「这个 App 没有日志功能」，而它其实一直在记录；
                // 显式写「暂无」同时也是一种安抚——说明当前没有攒下崩溃。
                SettingRow(
                    GlassIcons.Book,
                    "崩溃日志",
                    if (crashLogCount > 0) {
                        "$crashLogCount 份记录 · 点击导出（纯本地文本，不含曲库与个人信息）"
                    } else {
                        "暂无记录"
                    },
                    onClick = onExportCrashLogs,
                )
                if (crashLogCount > 0) {
                    RowDivider()
                    SettingRow(
                        GlassIcons.Trash, "清空崩溃日志",
                        "删除本机已记录的全部崩溃日志",
                        onClick = onClearCrashLogs,
                    )
                }
            }

            }

            if (openGroup == AboutGroup) {
            SectionHeader("关于")
            SettingsCard {
                // P3-59：版本号常驻（旧实现挂在检查更新行的 Idle 分支里，检查中/失败后版本号就消失）
                SettingRow(GlassIcons.Book, "当前版本", "v$currentVersionName")
                RowDivider()
                val updateState = update()
                // 验签失败态的两个局部判据（提到副标题**之前**：副标题、onClick、trailing
                // 三处必须读同一对变量——若各判一次，某个中间态会出现「可点但没箭头」
                // 或「有箭头但不可点」的撕裂，正是本轮要消灭的那类不一致）。
                val verifying = updateState.status == UpdateUiState.Status.Failed &&
                    updateState.failStage == UpdateUiState.FailStage.Verify
                // 取成本地 val：可空函数类型在 `if` 分支里直接被 smart cast 时不稳（它是形参、
                // 不是局部不可变量做法的一部分），显式取样让「有没有出口」这个判断只有一处。
                val releaseAction = onOpenReleasePage
                val updateSubtitle = when (updateState.status) {
                    UpdateUiState.Status.Idle -> "检查新版本"
                    UpdateUiState.Status.Checking -> "正在检查…"
                    UpdateUiState.Status.UpToDate -> "已是最新版本"
                    UpdateUiState.Status.Available ->
                        "发现新版本 v${updateState.versionName}，点击更新"
                    UpdateUiState.Status.Downloading ->
                        // 总长未知（不定长响应，P3-50）：显示进度而非恒 0%
                        if (updateState.indeterminate) "下载中…" else "下载中 ${(updateState.progress * 100).toInt()}%"
                    UpdateUiState.Status.ReadyToInstall ->
                        // 「在等系统授权」与「已下载待安装」是两种不同的**下一步**：
                        // 前者要用户去系统页开开关（并承诺回来会继续），后者才是点一下就装。
                        // 不区分的话，用户被跳到系统页后回来看到的仍是「点击安装」，
                        // 会以为自己刚才那一下白点了。
                        if (updateState.awaitingInstallPermission) {
                            "已下载 v${updateState.versionName}，需先允许安装未知应用（去系统设置授权，返回后自动继续）"
                        } else {
                            "已下载 v${updateState.versionName}，点击安装"
                        }
                    UpdateUiState.Status.Failed ->
                        // P2-25：按失败阶段给准确文案——过去一律「下载失败」，检查失败也不例外；
                        // 验签失败再给重试是死循环（同一份坏字节必然再失败），文案改引导发布页
                        when (updateState.failStage) {
                            // 引导文案分两档，但**「到发布页手动下载安装」这句必须保留**：
                            // 有出口时补一个「点击」把出路口语化；没出口时不给动词——
                            // 副标题里出现「点击」而整行不可点，就是一句新的假承诺。
                            UpdateUiState.FailStage.Verify ->
                                if (releaseAction != null) {
                                    "安装包签名与本机不一致，无法应用内更新，点击到发布页手动下载安装（需先卸载旧版）"
                                } else {
                                    "安装包签名与本机不一致，无法应用内更新，请到发布页手动下载安装（需先卸载旧版）"
                                }
                            UpdateUiState.FailStage.Install -> "安装包已丢失，请重新下载"
                            UpdateUiState.FailStage.Download ->
                                "下载失败（${updateState.message ?: "网络异常"}），点击重试"
                            // 未细分阶段沿用旧分流：进过下载链路（versionName 已带上新版本）→ 下载失败，否则检查失败
                            else ->
                                if (updateState.versionName != null) "下载失败（${updateState.message ?: "网络异常"}），点击重试"
                                else "检查失败（${updateState.message ?: "网络异常"}），点击重试"
                        }
                }
                // ── 检查更新：onClick 必须与「这一行承诺了什么」严格一致 ──
                //
                // ## 验签失败态的修法（P2：可点但无动作的死行）
                //
                // 改造前：`onClick` 恒非 null，而 when 的 Verify 分支是**空实现**。
                // 于是这一行有 role = Button、有按压缩放、读屏念「双击激活」，
                // 点下去屏幕毫无变化——用户会重复点，然后把「应用内更新坏了」升级成
                // 「这个 App 一堆东西点了没反应」。
                //
                // 本轮把「这一行到底是不是一个动作」交给**状态**决定：
                // - 可退出的失败（Download / Install / 未细分 / 检查失败）：与以前一样可点重试。
                // - Verify：不可重试（同一份坏字节必然再失败），唯一出路是去发布页手动安装。
                //   宿主接线了 [onOpenReleasePage] → 点这里打开浏览器；
                //   宿主没接线（回调为 null）→ **整行不可点**（onClick = null，
                //   由 SettingRow 移除按压反馈与 role = Button，尾部箭头也必须去掉，
                //   否则与「能进去」的视觉承诺矛盾）。
                //
                // 副标题在两种情况下都保留「到发布页手动下载安装」的引导文案：
                // 即使这一行不可点，它仍然告诉用户出口在哪（这句话本身就是一次信息传递，
                // 不依赖点击能力）。有出口时才加「点击」二字（见 updateSubtitle 的分支）。
                SettingRow(
                    GlassIcons.Down, "检查更新", updateSubtitle,
                    // 三元表达式的三档：验签失败且已接线 → 打开发布页；
                    // 验签失败且未接线 → null（纯说明行，无按压反馈、无按钮语义）；
                    // 其余 → 原有的重试/更新/安装分流。
                    onClick = if (verifying) {
                        releaseAction
                    } else {
                        {
                            when (update().status) {
                                    UpdateUiState.Status.Available, UpdateUiState.Status.Downloading,
                                    UpdateUiState.Status.ReadyToInstall,
                                    -> modal.show {
                                        val current = update()
                                        // 状态滑出面板语义组（下载失败转 Failed 等）自动收起：
                                        // 与旧「visible 条件出组即关」的时序等价（退场动画照常播放）
                                        LaunchedEffect(current.status) {
                                            if (!current.keepsSheetOpen) modal.requestDismiss()
                                        }
                                        ModalSheet(
                                            modal,
                                            if (current.versionName != null) "更新到 v${current.versionName}" else "更新",
                                        ) {
                                            // 取成局部 val：changelog 是**跨模块**的公开属性，
                                            // 编译器不能对它做 smart cast（跨模块的属性可能是计算属性，
                                            // 多读一次未必同值）。这是 UpdateUiState 迁到 :core:ui 后
                                            // 才出现的编译错误——同一模块内是可以 smart cast 的。
                                            val changelog = current.changelog
                                            if (changelog != null) {
                                                Text(
                                                    changelog,
                                                    color = LocalGlassColors.current.textSecondary,
                                                    style = GlassText.Body,
                                                    modifier = Modifier.fillMaxWidth()
                                                        .padding(horizontal = 6.dp, vertical = S.Gap8.dp),
                                                )
                                            }
                                            Spacer(Modifier.height(6.dp))
                                            if (current.status == UpdateUiState.Status.Downloading) {
                                                // P3-58：进度由外部（下载协程）驱动、onSeek 是空实现 →
                                                // **必须传 enabled = false**，否则用户拖动会立刻被真实进度弹回，
                                                // 看起来像 bug。旧实现漏传，取了默认 true。
                                                // P3-50：不定长（代理丢 Content-Length）时 progress 恒 0，
                                                // 画一条空轨道会被读成「卡住不动」；此时整条隐藏，
                                                // 只留下面那句「总大小未知，完成后自动打开安装程序」表达仍在进行。
                                                if (!current.indeterminate) {
                                                    ProgressTrack(
                                                        progress = current.progress,
                                                        onSeek = {},
                                                        enabled = false,
                                                    )
                                                }
                                                Text(
                                                    if (current.indeterminate) {
                                                        "下载中，总大小未知，完成后自动打开安装程序"
                                                    } else {
                                                        "下载中 ${(current.progress * 100).toInt()}%，完成后自动打开安装程序"
                                                    },
                                                    color = LocalGlassColors.current.textTertiary,
                                                    style = GlassText.Micro,
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = S.Gap8.dp),
                                                )
                                            } else {
                                                SheetAction(
                                                    icon = GlassIcons.Down,
                                                    label = when (current.status) {
                                                        UpdateUiState.Status.ReadyToInstall -> "安装已下载的更新"
                                                        else -> "立即更新"
                                                    },
                                                    subtitle = when (current.status) {
                                                        UpdateUiState.Status.ReadyToInstall ->
                                                            if (current.awaitingInstallPermission) {
                                                                "在系统设置里允许后返回即自动安装"
                                                            } else {
                                                                "无需重新下载"
                                                            }
                                                        else -> "下载完成后自动打开安装程序"
                                                    },
                                                    onClick = {
                                                        if (current.status == UpdateUiState.Status.ReadyToInstall) {
                                                            onInstallUpdate()
                                                        } else {
                                                            onStartUpdate()
                                                        }
                                                    },
                                                )
                                            }
                                        }
                                    }
                                    // P2-25：Failed 的重试行为按阶段定——验签失败**不给重试**（重下同一份
                                    // 坏字节必然再失败）；其余沿用旧分流
                                    // （进过下载阶段的失败 versionName 已带上新版本：直接重新下载，不再重新检查）
                                    //
                                    // 注意这里**必须仍然判一次** Verify：虽然上面已经把 Verify 态
                                    // 分流到 onClick = null / 发布页，但 update() 是**活读**的
                                    // snapshot 态（getter 形参），点下的瞬间状态可能已经变了
                                    // （例如此行在手势未结束时被重组）。不判就会在验证失败态里
                                    // 落进「什么都没发生」——正是本轮要消灭的那个行为。
                                    UpdateUiState.Status.Failed ->
                                        if (update().failStage != UpdateUiState.FailStage.Verify) {
                                            if (update().versionName != null) onStartUpdate() else onCheckUpdate()
                                        }
                                    else -> onCheckUpdate()
                                }
                            }
                        },
                        // 尾部箭头只在真能点上时画：它是「这里可以进去」的视觉承诺，
                        // 与 role = Button 必须同步（理由见 SettingRow 的 KDoc）。
                        trailing = if (verifying && releaseAction == null) {
                            {}
                        } else {
                            {
                                Icon(
                                    GlassIcons.ChevronRight, null, Modifier.size(16.dp),
                                    tint = LocalGlassColors.current.textTertiary,
                                )
                            }
                        },
                    )
                // ── 支持开发者（本轮新增，三级页入口）──
                //
                // 用户的原始需求是「在设置页新增支持开发者页面的入口（把页面先做，先不放内容）」。
                // 所以这一行**必须真的能进去**——一个点了没反应、或点了只弹个占位 toast 的
                // 入口，比没有入口更糟（用户会以为 App 坏了）。进去之后那个页面本身可以空，
                // 但「能进、能回」这两件事必须是完整真实的。
                //
                // ## 为什么放在「关于」分组的**最末**
                // 「支持开发者」不是设置项，它是这个 App 的**作者信息**的一部分，与
                // 版本 / 更新 / 崩溃日志同组；但它又不是任何人日常要改的配置，
                // 所以压在组尾，不打断「检查更新」这类真正会被反复用的行。
                // 放组首会让每次进「关于」都先看到它，是典型的「开发者视角排序」。
                //
                // ## 为什么图标用 Heart
                // Heart 在图标集里的既有语义是「表达喜欢」（ActionsSheet 的收藏动作），
                // 而「支持开发者」的本质正是「如果你喜欢这个 App、愿意让它继续」——
                // 同一枚符号在这里指向同一件事（表达喜欢），不是跨语义复用。
                // 备选里 Book 已被版本 / 崩溃日志 / 恢复内置歌单这一族占满，
                // Gear 是全 App 的「系统设置」符号，都不适合。
                //
                // ## 为什么 returnGroup 传「关于」
                // 这是三级页：首页 → 关于 → 支持开发者。返回必须回到「关于」而不是设置首页，
                // 否则用户从关于页进去看一眼、返回就落到首页，还得再点一次「关于」。
                // 该返回目标由 `state.settingsReturnGroup` 承载（见 AppState 的 KDoc），
                // 本屏**不自己记**——屏内再记一份就是第二个真相。
                RowDivider()
                SettingRow(
                    GlassIcons.Heart, "支持开发者", "了解与支持这个小项目",
                    onClick = {
                        state.openSettingsGroup(SupportDeveloperGroup, returnGroup = AboutGroup)
                    },
                )
            }
            } // ← 「关于」分组闭合

            // 底部避让：悬浮 Chrome 高度由 core/ui 的 ChromeBottomInset 单点定义，
            // 三个主屏共用（原来各自硬编码 146.dp，Chrome 高度一改就得同步改三处）
            Spacer(Modifier.navigationBarsPadding().height(ChromeBottomInset))
            }
    }
}

// ── 三级分类：分组导航（本轮新增）────────────────────────────────

/**
 * 分组名的**唯一来源**（本轮抽出）。
 *
 * 这些字符串同时出现在三处：首页导航卡的 title、二级页页头的标题、以及
 * `openGroup == "…"` 的分支判据。以前它们是散落的字面量——把「外观」写错成
 * 「外覌」不会编译报错，只会让那一段**整块不渲染**，而且只有手动点进去才看得见。
 *
 * 只抽出**被多处引用**的那几个（关于 / 支持开发者）：其余组名目前仍只在
 * settingsGroups 与分支里各出现一次，抽出来只会增加间接层。
 * 将来若某个组名要在第四处出现，先在这里登记，不要复制字面量。
 */
private const val AboutGroup = "关于"

/**
 * 三级页「支持开发者」的组名。
 *
 * 它**不是**首页导航卡（`settingsGroups(...)` 只返回 5 个分组），而是从「关于」
 * 里的入口行进入的第三级页面。因此它不会出现在首页列表里，但会出现在
 * `state.settingsGroup` 中——这正是三级页的判据（见 SettingsScreen 主体里的分支）。
 */
private const val SupportDeveloperGroup = "支持开发者"

/**
 * 一个设置分组在首页导航卡上的展示信息。
 *
 * @param title 组名（与二级页页头、SectionHeader 三处同源——**只在这里定义一次**，
 *   避免「首页叫外观、子页叫个性化」这类漂移）
 * @param subtitle **动态摘要**：一句话说清「这一组现在是什么状态」。
 *   它不是装饰：用户靠它在不进去的情况下确认「这就是我要找的组」
 *   （详见 SettingsScreen 的 KDoc）。
 */
private data class SettingsGroup(
    val title: String,
    val subtitle: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
)

/**
 * 构造五个分组的展示信息（摘要全部**实时取自 state**）。
 *
 * 为什么在最外层构造而不是让 SettingsGroupNav 自己读 state：导航卡只需要
 * `title/subtitle/icon` 三元组，把「摘要怎么算」留在 SettingsScreen 里，
 * 组件本身就可以是纯展示、无依赖（便于将来单独预览）。
 *
 * **只返回 5 个分组**：三级页（支持开发者）不在这里——首页列出它等于让用户
 * 跳过「关于」这一层，而它本来就是关于页里的一个入口。这一条不要顺手「补全」。
 */
@Composable
private fun settingsGroups(
    state: AppState,
    scanning: Boolean,
    backgroundPhotoSelected: Boolean,
    desktopLyricsEnabled: Boolean,
): List<SettingsGroup> {
    // 外观摘要：材质语言 + 主题色三态（自定义 > 跟随封面 > 品牌）+ 深浅 + 强度档。
    //
    // 材质语言（液态玻璃 / 拟物）放在**最前**：它是这一组里最顶层的一个选择
    // （决定用的哪套语法），也是本轮唯一新增的可见配置。摘要里不带它的话，
    // 用户切到拟物档后首页那张卡的摘要仍然只在说主题色，看不出「我已经换过材质」。
    val styleSummary = state.visualStyle.label
    val themeSummary = when {
        state.customSeedArgb != null -> "自定义主题色"
        state.followCover -> "跟随封面取色"
        else -> "品牌靛紫"
    }
    val darkSummary = if (state.themeMode == ThemeMode.Dark) "深色" else "浅色"
    // 背景摘要必须按**材质语言**分支（2026-11 真机修正）：
    // 拟物档下根层画的是机身（SkeuomorphBackground），不再是极光。
    // 旧文案恒写「极光背景」，切到拟物后用户会看到摘要与屏幕事实相反——
    // 这正是本仓反复点名的「摘要的唯一职责就是指对路」。
    val bgSummary = when {
        state.visualStyle == VisualStyle.Skeuomorph -> "机身背景"
        state.backgroundMode == BackgroundMode.Photo -> "照片背景"
        else -> "极光背景"
    }

    // 播放摘要：均衡器档位 + 定时/桌面歌词这类「正在生效」的状态。
    // 顺序按「用户最可能特意来确认的」排：定时是时间敏感状态（还剩多久），
    // 桌面歌词是显隐状态，均衡器档位最稳定所以放最后。
    val sleep = state.effectiveSleepTimerMode()
    val playSummary = buildString {
        if (sleep != 0) append("定时已开启 · ")
        if (desktopLyricsEnabled) append("桌面歌词 · ")
        append(state.eqPreset.label)
    }

    // 曲库摘要：扫描态优先（用户点进来多半是为了这个）
    val libSummary = when {
        scanning -> "扫描中…"
        state.excludedFolders.isNotEmpty() ->
            "已收录 ${state.visibleLibrary().size} 首 · 排除 ${state.excludedFolders.size} 个目录"
        state.scanFilterShort -> "已收录 ${state.visibleLibrary().size} 首 · 已过滤短音频"
        else -> "已收录 ${state.visibleLibrary().size} 首"
    }

    return listOf(
        SettingsGroup(
            "外观",
            "$styleSummary · $themeSummary · $darkSummary · $bgSummary",
            GlassIcons.Edit,
        ),
        SettingsGroup(
            "播放",
            playSummary,
            GlassIcons.Wave,
        ),
        SettingsGroup(
            "曲库",
            libSummary,
            GlassIcons.Folder,
        ),
        SettingsGroup(
            "数据",
            "M3U 导入 / 备份与恢复 / 崩溃日志",
            GlassIcons.Down,
        ),
        SettingsGroup(
            // 摘要里补上「支持开发者」的存在感：它是关于组里新增的一行，
            // 而摘要的作用正是「不进去也能确认这一组里有什么」（见本函数 KDoc）。
            // 不写进摘要的话，用户想知道「支持开发者去哪了」只能挨个组点进去。
            //
            // **崩溃日志不在本组**：那两行实际渲染在「数据」组（openGroup == "数据"），
            // 摘要写在这里会把用户送进一个没有该行的页——摘要的唯一职责就是指对路。
            AboutGroup,
            "版本 / 更新 / 支持开发者",
            GlassIcons.Book,
        ),
    )
}

/** 组名 → 二级页页头标题（与 settingsGroups 的 title 同源检查点） */
private fun settingsGroupTitle(group: String): String = group

/**
 * 首页分组导航卡：五行入口，每行「图标 + 组名 + 动态摘要 + 箭头」。
 *
 * ## 为什么用一整张玻璃卡装五行
 * 与各二级页的设置卡同材质、同圆角、同边距——用户在首页与子页之间移动时
 * **卡片形状不变**，只有内容替换，读起来像「翻页」而不是「跳转」。
 * 五张独立卡片会制造五个并列的视觉块，反而更像内容列表而非导航。
 *
 * ## 为什么箭头（Chevron）必须画出来
 * 行内没有开关、没有选中态，唯一能表达「这里可以进去」的就是右箭头。
 * 曲库页的 songRow 有 mini 波形等表达「点了会怎样」的线索，本页没有——
 * 少了箭头，五行会读成一串静态说明文字。
 */
@Composable
private fun SettingsGroupNav(
    groups: List<SettingsGroup>,
    onOpen: (String) -> Unit,
) {
    val glass = LocalGlassColors.current
    SettingsCard {
        groups.forEachIndexed { index, group ->
            if (index > 0) RowDivider()
            val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(R.Card14.dp))
                    // 0.98 的按压缩放：与 SheetAction 同一档。
                    // 导航行不是「开关」而是「进入下一层」，缩放过大会像按钮被按下，
                    // 缩小幅度小则保留「轻点即达」的感觉。
                    .pressScale(interaction, pressed = 0.98f)
                    .clickable(interactionSource = interaction, indication = null) { onOpen(group.title) }
                    // 语义：整行合并成单个按钮节点（与 SheetAction 同款写法）。
                    // 不合并的话读屏会先把图标、组名、摘要各念一遍，一行要划四下。
                    .semantics(mergeDescendants = true) {
                        this.role = androidx.compose.ui.semantics.Role.Button
                        // 动作标签：告诉读屏「双击会进入外观设置」，而不是只说「按钮」
                        this.onClick(label = "进入${group.title}设置") { onOpen(group.title); true }
                    }
                    .padding(horizontal = S.Gap16.dp, vertical = S.Gap16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(S.Gap16.dp),
            ) {
                // 32dp 图标块：与 SheetAction / SettingRow 的图标块同尺寸，
                // 让「设置里的每一行」在全 App 范围内有一致的左缘节奏
                Box(
                    Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(R.Thumb10.dp))
                        .background(glass.md),
                    contentAlignment = Alignment.Center,
                ) {
                    // null 有意为之：语义由整行合并后承担，图标再给描述会重复播报
                    Icon(group.icon, null, Modifier.size(18.dp), tint = glass.textPrimary)
                }
                Column(Modifier.weight(1f)) {
                    Text(group.title, color = glass.textPrimary, style = GlassText.Row, maxLines = 1)
                    Spacer(Modifier.height(S.Gap4.dp))
                    // 摘要用 Micro（11）：它是「注脚」而非第二标题——
                    // 若与组名同档（Row 15/21），两行会争视觉焦点，卡片变成两个标题。
                    Text(
                        group.subtitle,
                        color = glass.textTertiary,
                        style = GlassText.Micro,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                }
                Icon(
                    // 披露符用单 chevron（层级导航语义）；旧误用 Next（跳曲字形 ▶▶|）
                    // 会让「进入下一层」读作「下一首」，与传输控制图标混淆。
                    GlassIcons.ChevronRight,
                    null,
                    Modifier.size(16.dp),
                    // 箭头比文字更弱：它是「可进去」的提示，不是一个独立的信息
                    tint = glass.textTertiary,
                )
            }
        }
    }
}

/**
 * 「支持开发者」页的**空壳**（用户原话：「把页面先做，先不放内容」）。
 *
 * ## 这一版为什么什么都没有，却仍然要认真设计
 *
 * 需求是先把**页面**做出来，内容以后填。这类「先占位」的页面最容易掉进两个坑：
 * 1. **白屏**——用户点进来看到一片空白，第一反应是「加载失败 / 这个 App 有 bug」；
 * 2. **假按钮**——放几个「赞助 / 反馈 / 关注」按钮，因为还没有落地页，点了只能弹个
 *    toast 或者干脆没反应。**这比白屏更糟**：白屏只是「没东西」，假按钮是
 *    「它承诺了事情，然后骗了你」。
 *
 * 所以这一页只做两件事，且两件都是**真的**：
 * - 用一个视觉语言完整的头图（强调色徽章 + 标题 + 一段实话）说清「还在筹备」，
 *   让「空」读起来是**刻意的留白**而不是故障；
 * - 把「之后会有什么」做成三行**纯说明行**（`onClick = null`、`trailing = {}`）——
 *   既传达了规划，又不提供任何可点的假入口。理由见 SettingRow 的 KDoc：
 *   不可点的行不该被念成按钮，也不该带「可以进去」的右箭头。
 *
 * ## 为什么没有「返回」按钮
 *
 * 返回头由调用方（SettingsScreen 主体）统一画：二级页与三级页共用
 * [SettingsSubPageHeader]，差别只在返回落点（settingsReturnGroup）。
 * 本函数只负责**内容区**，不重复画头——第一版在这里画过一次，结果是
 * 一屏两行同样的「支持开发者」标题（文件头 KDoc 记过同类错误）。
 *
 * ## 为什么用强调色徽章（而不是灰色占位图标）
 *
 * 这一页在灰度上与「设置快照读取失败」那类**故障提示**必须拉开距离：后者是
 * 警告，用中性色 + 说明行；前者是**邀请**，用 auroraAccentColor（全 App
 * 「值得看一眼」的唯一强调色来源）。同一套材质、同一档圆角，只换颜色——不新造任何 token。
 *
 * ## 内容规划的措辞口径（写在这里，避免下一个人以为我在乱许诺）
 *
 * 下面三段文案刻意都用了「之后会放 / 还在筹备」的**将来时**，没有一处写成
 * 「点击这里可以…」。这一页目前**没有任何功能**，任何现在时的动作描述都会立刻变成谎言。
 */
@Composable
private fun SettingsSupportPage() {
    val glass = LocalGlassColors.current
    // 强调色只在这里出现一次（徽章）；标题正文仍走 textPrimary/secondary，
    // 否则整块会被读成「一个警告」而不是「一段邀请」
    val accent = auroraAccentColor()
    // 入场：与 GlassEmptyState 同一条语言（淡入 + 上移 8dp，tweenPage 已含动效强度乘数）。
    // 值只在 graphicsLayer 的绘制期读——不为了 8dp 位移每帧重组整棵子树。
    val appear = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, GlassMotion.tweenPage()) }

    Column(
        Modifier
            .fillMaxWidth()
            // **横向不留内边距**：本页末段要用 SectionHeader + SettingsCard，这两个组件
            // 自身已经各带一份 S.Gap20 横向边距（见它们的定义）。外层再包一层 20dp 会变成
            // 40dp 缩进——卡片比页头窄一截，与所有二级页的分组卡左缘对不齐。
            // 只有标题/正文那一段需要自己的横向边距，见下方内层 Column。
            .padding(top = S.Gap8.dp)
            .graphicsLayer {
                val p = appear.value
                alpha = p
                translationY = (1f - p) * 8.dp.toPx()
            },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // ── 头图 + 文案：这一段是「裸文字」，没有卡片外壳，所以要自己带页面边距 ──
        Column(
            Modifier.padding(horizontal = S.Gap20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // ── 头图：强调色徽章（72dp 圆盘 + 同色细描边）──
            //
            // 72dp 与 GlassEmptyState 的图标盘（64/48）同量级：这一页只有一个视觉焦点，
            // 比空态的盘大 8dp 是为了让「这是一整页的主角」成立，而不是某个列表的空态。
            Box(
                Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    // 14% 填充 + 28% 描边：同色两档，与 GlassIconButton（lo 填充 + strokeSoft 描边）
                    // 同一套「可识别边界」的构造法，只是把中性白换成强调色
                    .background(accent.copy(alpha = 0.14f))
                    .border(1.dp, accent.copy(alpha = 0.28f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                // 有意 null：装饰性图形，语义由下方标题承担（同 GlassEmptyState 的纪律）
                Icon(GlassIcons.Heart, null, Modifier.size(28.dp), tint = accent)
            }
            Spacer(Modifier.height(S.Gap20.dp))
            Text(
                "这一页还在筹备",
                // Title（20 Bold）而不是 Heading：这是**一页**的标题，不是区块标题。
                // 页头已经用 Display 显示了「支持开发者」，此处是内容区的第二层，
                // 用 Title 形成「页名 → 章节」的层级，不会与页头抢。
                style = GlassText.Title,
                color = glass.textPrimary,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(S.Gap8.dp))
            Text(
                "先把入口留下，内容慢慢来。这里之后会放：自愿支持的方式、反馈与建议的入口，" +
                    "以及开发者想对你说的一段话。",
                // Body + textSecondary：这是需要被读完的一段话（同 GlassEmptyState 副标题的取向），
                // 不是注脚。textTertiary 在这个位置会被读成「不重要的小字」而直接跳过。
                style = GlassText.Body,
                color = glass.textSecondary,
                textAlign = TextAlign.Center,
            )
        }

        // ── 之后会有什么：三行纯说明行（不可点、无箭头）──
        //
        // 刻意**不**用 SheetAction / 胶囊按钮：那两个组件天生带按压反馈与按钮语义，
        // 而这里没有任何动作可做。必须让读屏与视觉同时读作「说明」而非「入口」。
        Spacer(Modifier.height(S.Gap8.dp))
        SectionHeader("之后会有什么")
        SettingsCard {
            SettingRow(
                GlassIcons.Heart, "自愿支持",
                "不改变任何功能，也不解锁任何东西 —— 只是如果你愿意",
                onClick = null,
                // 尾部落空：右箭头是「可以进去」的视觉承诺（见 SettingRow 的 KDoc），
                // 不可点的行绝不能带它
                trailing = {},
            )
            RowDivider()
            SettingRow(
                // Edit（铅笔）=「写点什么」，语义是**写消息**而不是跳转；
                // 与「主题色 / 桌面歌词字号」那两行的「手动指定某个值」同属
                // 「用户亲手写/定的东西」这一族，不构成跨语义复用
                GlassIcons.Edit, "反馈与建议",
                "发现问题、想要某个功能，都可以在那时告诉我",
                onClick = null,
                trailing = {},
            )
            RowDivider()
            SettingRow(
                // User（人）：这一行说的是**开发者本人**，同一枚符号在「查看艺术家」
                // 那里的语义也是「一个人」，属于同一族。Book 已被版本/日志占满。
                GlassIcons.User, "开发者的话",
                "关于这个小项目的来历，以及为什么它是现在这个样子",
                onClick = null,
                trailing = {},
            )
        }

        // ── 页脚：把「为什么现在还不能点」说明白 ──
        //
        // 这一段不是凑数：用户看到三行「之后」之后，最自然的疑问就是「那我现在能干嘛」。
        // 与其让他去戳那三行然后发现没反应，不如直接把答案写在这里——
        // 这也是整页唯一一句「现在时」的承诺，而它承诺的是**没有假动作**。
        Text(
            "在内容准备好之前，这一页不会有任何可点的按钮 —— " +
                "一个点了没反应的按钮，比一段诚实的留白更让人失望。",
            style = GlassText.Micro,
            color = glass.textTertiary,
            textAlign = TextAlign.Center,
            // 这里**不用** SettingsCard 的 bottom 20dp，而是负回一点：它接在卡片后面，
            // 属于卡片的注脚而不是新的一段；再加一行 20dp 会让页脚显得被孤立。
            modifier = Modifier.padding(horizontal = S.Gap20.dp, vertical = S.Gap8.dp),
        )
    }
}

/**
 * 二级页头：返回箭头 + 组名。
 *
 * 为什么不用 [PageHeader] 的 actionIcon 塞返回箭头：PageHeader 的标题是 Display
 * 大字号（30sp）且箭头在**右侧**——那是「页面级操作」的语序（如「新建」「排序」）。
 * 返回是**层级导航**，按平台惯例必须在左侧、且字号应与「当前层」而非「首页大标题」
 * 匹配，否则从首页进子页时标题会缩小一半（30sp → 20sp 会读成「进入了更深的一层，
 * 但这是一个不那么重要的页」）。
 *
 * 代价是本组件要自己处理 statusBars 之外的顶部节奏——这里直接复用
 * PageHeader 的 padding 数值（start/end 20dp、top 4dp、bottom 12dp），
 * 让首页与子页的页头在垂直位置上完全对齐。
 */
@Composable
private fun SettingsSubPageHeader(title: String, onBack: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = S.Gap20.dp, end = S.Gap20.dp, top = 4.dp, bottom = S.Gap12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(S.Gap12.dp),
    ) {
        val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
        GlassIconButton(
            onClick = onBack,
            modifier = Modifier
                .pressScale(interaction, pressed = 0.9f)
                .semantics {
                    this.role = androidx.compose.ui.semantics.Role.Button
                    this.contentDescription = "返回设置"
                },
        ) {
            // Back 图标（左箭头）：全 App 唯一的「返回」符号
            Icon(GlassIcons.Back, null, Modifier.size(20.dp), tint = LocalGlassColors.current.textPrimary)
        }
        Text(title, color = LocalGlassColors.current.textPrimary, style = GlassText.Display)
    }
}

/** 设置分组玻璃卡：18dp 圆角（.card glass → md 层级），行间 7% 白通栏分隔线 */
@Composable
private fun SettingsCard(content: @Composable () -> Unit) {    GlassSurface(
        modifier = Modifier.fillMaxWidth().padding(start = S.Gap20.dp, end = S.Gap20.dp, bottom = S.Gap20.dp),
        level = GlassLevel.Md,
        shape = RoundedCornerShape(R.Card18.dp),
    ) {
        Column { content() }
    }
}

@Composable
private fun RowDivider() {
    // 分隔线缩进到文本左缘（行内 padding 16 + 图标块 32 + 间隙 16 = 64dp），
    // 不再通栏穿过图标块下方——分组列表的惯例是分隔线对齐内容而非容器边缘，
    // 通栏线会把图标块「切」进上一行，读作表格线而非列表分隔。
    HorizontalDivider(
        modifier = Modifier.padding(start = 64.dp),
        color = LocalGlassColors.current.textPrimary.copy(alpha = 0.07f),
        thickness = 1.dp,
    )
}
