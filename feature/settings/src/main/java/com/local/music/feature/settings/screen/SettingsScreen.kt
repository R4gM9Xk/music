package com.local.music.feature.settings.screen

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.local.music.core.ui.components.AuroraMiniPreview
import com.local.music.core.ui.components.ChromeBottomInset
import com.local.music.core.ui.components.GlassLevel
import com.local.music.core.ui.components.GlassSurface
import com.local.music.core.ui.components.GlassToggle
import com.local.music.core.ui.components.LocalModalState
import com.local.music.core.ui.components.ModalSheet
import com.local.music.core.ui.components.PageHeader
import com.local.music.core.ui.components.ProgressTrack
import com.local.music.core.ui.components.SectionHeader
import com.local.music.core.ui.components.SettingRow
import com.local.music.core.ui.components.SheetAction
import com.local.music.core.ui.components.ThemeColorSwatches
import com.local.music.core.ui.components.asTime
import com.local.music.core.ui.theme.AuroraPalette
import com.local.music.core.ui.state.AppState
import com.local.music.core.ui.state.BassBoostLevelLabels
import com.local.music.core.ui.state.BassBoostStrengths
import com.local.music.core.ui.state.EqPreset
import com.local.music.core.ui.state.GlassScreen
import com.local.music.core.ui.state.SleepTimerOptions
import com.local.music.core.ui.state.ThemeMode
import com.local.music.core.ui.state.VirtualizerLevelLabels
import com.local.music.core.ui.state.VirtualizerStrengths
import com.local.music.core.ui.state.bassBoostSubtitle
import com.local.music.core.ui.state.loudnessNormSubtitle
import com.local.music.core.ui.state.sleepTimerLabel
import com.local.music.core.ui.state.sleepTimerSubtitle
import com.local.music.core.ui.state.virtualizerSubtitle
import com.local.music.core.ui.theme.GlassIcons
import com.local.music.core.ui.theme.GlassMotion
import com.local.music.core.ui.theme.GlassIntensity
import com.local.music.core.ui.theme.MotionIntensity
import com.local.music.core.ui.theme.LocalGlassColors
import com.local.music.core.ui.theme.R
import kotlinx.coroutines.delay

/**
 * 设置：外观 / 播放 / 曲库 三组玻璃卡。全部接入 AppState——
 * 取色与歌词开关实时生效；玻璃/动效强度改全局 token；均衡器经服务端 Equalizer 生效；
 * 扫描入口真实重扫 MediaStore；文件夹过滤选项从当前曲库派生。
 * 五个面板（更新/玻璃/动效/均衡器/文件夹）经 LocalModalState.show 挂 GlassApp 根层宿主
 * （屏内不再渲染 GlassSheet）。
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
    onImportM3u: () -> Unit = {},
    onExportBackup: () -> Unit = {},
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
    desktopLyricsEnabled: Boolean = false,
    onDesktopLyricsEnabledChange: (enabled: Boolean) -> Unit = {},
    // 悬浮窗权限未授予时的引导跳转（去系统「显示在其他应用上层」页）
    onDesktopLyricsPermission: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var scanning by remember { mutableStateOf(false) }
    // 模态面板宿主（GlassApp 根层）
    val modal = LocalModalState.current
    // 更新面板的可见语义组：状态滑出该组（下载失败等）即自动收起（与旧 visible 条件等价）
    val updateSheetStatuses = setOf(
        UpdateUiState.Status.Available, UpdateUiState.Status.Downloading, UpdateUiState.Status.ReadyToInstall,
    )

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
        sleepActiveMode == -2 -> "播完整个队列后暂停"
        else -> "未开启"
    }

    // 无自有 haze source：本屏内容与面板均无 hazeEffect 消费（面板挂 app 级），整屏录制纯开销
    Column(modifier.fillMaxSize().statusBarsPadding()) {
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
                PageHeader(title = "设置")

            // P1-②：快照读取失败时的显式横幅。放在最顶部——它是「本页所有改动都不会保存」
            // 这种状态的前提，藏在任一分组下面都会让人先改了再发现白改。
            // A3-07：横幅出现/消失走展开+渐变——旧实现 if 硬切，页首内容一帧被顶开/塌缩
            androidx.compose.animation.AnimatedVisibility(
                visible = corruptSnapshot,
                enter = androidx.compose.animation.expandVertically(
                    androidx.compose.animation.core.tween(GlassMotion.Page, easing = GlassMotion.Curve),
                ) + androidx.compose.animation.fadeIn(GlassMotion.tweenPage()),
                exit = androidx.compose.animation.shrinkVertically(
                    androidx.compose.animation.core.tween(GlassMotion.Page, easing = GlassMotion.Curve),
                ) + androidx.compose.animation.fadeOut(GlassMotion.tweenPage()),
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
                    // 唯一出口：用户明确同意后才放弃旧快照重新开始存盘
                    SheetAction(
                        icon = GlassIcons.Trash,
                        label = "放弃旧快照并重新开始",
                        subtitle = "以当前显示的状态覆盖保存（不可撤销）",
                        onClick = onRebuildFromCorrupt,
                    )
                }
            }

            SectionHeader("外观")
            SettingsCard {
                // 整行可点 + 内层开关双通道（GlassToggle 的 clickable 在内层会消费，不会双触发）
                SettingRow(
                    GlassIcons.Wave, "跟随封面取色",
                    // P2-6：副标题必须与三态真相一致——自定义色存在时封面取色被短路，开关说了不算
                    if (state.customSeedArgb != null) "已被自定义主题色覆盖（点上方主题色可恢复）" else "极光背景取自当前专辑封面",
                    onClick = { state.applyFollowCover(!state.followCover) },
                    trailing = { GlassToggle(state.followCover, onToggle = state::applyFollowCover) },
                )
                RowDivider()
                // F2 自定义主题色：三态（自定义 > 跟随封面 > 品牌靛紫），选中即生效
                SettingRow(
                    GlassIcons.Wave, "主题色",
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
                                Spacer(Modifier.height(16.dp))
                                Text(
                                    "预设色板",
                                    color = LocalGlassColors.current.textTertiary,
                                    fontSize = 12.sp,
                                    modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
                                )
                                ThemeColorSwatches(
                                    seeds = AuroraPalette.PresetSeeds,
                                    // P2-5：高亮「当前生效色」三态折算（自定义 > 封面取色 > 品牌靛紫）——
                                    // 旧实现只在用户自定义后才有高亮，跟随封面/品牌态 10 个色点全灰
                                    selected = state.effectiveSeed,
                                    onSelect = { state.setCustomSeed(it.toArgb()) },
                                )
                                Spacer(Modifier.height(12.dp))
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
                SettingRow(
                    GlassIcons.Book, "玻璃强度", "${state.glassLevel.label}（可改通透 / 浓郁）",
                    onClick = {
                        modal.show {
                            ModalSheet(modal, "玻璃强度") {
                                GlassIntensity.entries.forEach { level ->
                                    SheetAction(
                                        icon = GlassIcons.Book,
                                        label = level.label,
                                        subtitle = when (level) {
                                            GlassIntensity.Transparent -> "填充与描边减淡，更通透"
                                            GlassIntensity.Standard -> "设计稿默认档位"
                                            GlassIntensity.Rich -> "填充加浓，玻璃感更强"
                                        },
                                        emphasized = state.glassLevel == level,
                                        onClick = {
                                            state.glassLevel = level
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
                    GlassIcons.Gear, "动效强度", "${state.motionLevel.label}（可改克制 / 增强）",
                    onClick = {
                        modal.show {
                            ModalSheet(modal, "动效强度") {
                                MotionIntensity.entries.forEach { level ->
                                    SheetAction(
                                        icon = GlassIcons.Gear,
                                        label = level.label,
                                        subtitle = when (level) {
                                            MotionIntensity.Restrained -> "过渡更快（×0.6）"
                                            MotionIntensity.Standard -> "设计稿默认档位"
                                            MotionIntensity.Enhanced -> "过渡更绵长（×1.4）"
                                        },
                                        emphasized = state.motionLevel == level,
                                        onClick = {
                                            state.motionLevel = level
                                            modal.requestDismiss()
                                        },
                                    )
                                }
                            }
                        }
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

            SectionHeader("播放")
            SettingsCard {
                // E2：自启动引导（仅国产激进省电 ROM 提供，subtitle 为 null 即不显示）
                if (autoStartGuideSubtitle != null) {
                    SettingRow(
                        GlassIcons.Wave, "自启动与后台运行", autoStartGuideSubtitle,
                        onClick = onAutoStartGuide,
                    )
                    RowDivider()
                }
                SettingRow(
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
                    GlassIcons.Moon, "定时关闭", sleepSubtitle,
                    onClick = {
                        modal.show {
                            ModalSheet(modal, "定时关闭") {
                                val activeMode = state.effectiveSleepTimerMode()
                                SleepTimerOptions.forEach { option ->
                                    SheetAction(
                                        icon = GlassIcons.Moon,
                                        label = sleepTimerLabel(option),
                                        subtitle = sleepTimerSubtitle(option, activeMode, sleepRemainingSec),
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
                    GlassIcons.Mic, "歌词", "优先读取本地 .lrc 歌词文件",
                    onClick = { state.lyricEnabled = !state.lyricEnabled },
                    trailing = { GlassToggle(state.lyricEnabled, onToggle = { state.lyricEnabled = it }) },
                )
                RowDivider()
                // 桌面歌词（P0-8）：悬浮窗单行歌词。开关形态与「歌词」同款双通道。
                // 权限判定整个在 app 层的 [onDesktopLyricsEnabledChange] 里做：
                // 未授权时它不真正开开关、而是跳系统授权页——本屏不需要知道 canDrawOverlays
                SettingRow(
                    GlassIcons.Wave, "桌面歌词",
                    if (desktopLyricsEnabled) "播放中显示悬浮歌词，可拖动" else "未开启 · 需悬浮窗权限",
                    onClick = { onDesktopLyricsEnabledChange(!desktopLyricsEnabled) },
                    trailing = { GlassToggle(desktopLyricsEnabled, onToggle = onDesktopLyricsEnabledChange) },
                )
            }

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
                                        subtitle = "$count 首",
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
                    GlassIcons.Book, "歌词文件访问",
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

            SectionHeader("关于")
            SettingsCard {
                // P3-59：版本号常驻（旧实现挂在检查更新行的 Idle 分支里，检查中/失败后版本号就消失）
                SettingRow(GlassIcons.Book, "当前版本", "v$currentVersionName")
                RowDivider()
                val updateState = update()
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
                        "已下载 v${updateState.versionName}，点击安装"
                    UpdateUiState.Status.Failed ->
                        // P2-25：按失败阶段给准确文案——过去一律「下载失败」，检查失败也不例外；
                        // 验签失败再给重试是死循环（同一份坏字节必然再失败），文案改引导发布页
                        when (updateState.failStage) {
                            UpdateUiState.FailStage.Verify ->
                                "安装包签名与本机不一致，无法应用内更新，请到发布页手动下载安装（需先卸载旧版）"
                            UpdateUiState.FailStage.Install -> "安装包已丢失，请重新下载"
                            UpdateUiState.FailStage.Download ->
                                "下载失败（${updateState.message ?: "网络异常"}），点击重试"
                            // 未细分阶段沿用旧分流：进过下载链路（versionName 已带上新版本）→ 下载失败，否则检查失败
                            else ->
                                if (updateState.versionName != null) "下载失败（${updateState.message ?: "网络异常"}），点击重试"
                                else "检查失败（${updateState.message ?: "网络异常"}），点击重试"
                        }
                }
                SettingRow(
                    GlassIcons.Down, "检查更新", updateSubtitle,
                    onClick = {
                        when (update().status) {
                            UpdateUiState.Status.Available, UpdateUiState.Status.Downloading,
                            UpdateUiState.Status.ReadyToInstall,
                            -> modal.show {
                                val current = update()
                                // 状态滑出面板语义组（下载失败转 Failed 等）自动收起：
                                // 与旧「visible 条件出组即关」的时序等价（退场动画照常播放）
                                LaunchedEffect(current.status) {
                                    if (current.status !in updateSheetStatuses) modal.requestDismiss()
                                }
                                ModalSheet(
                                    modal,
                                    if (current.versionName != null) "更新到 v${current.versionName}" else "更新",
                                ) {
                                    if (current.changelog != null) {
                                        Text(
                                            current.changelog,
                                            color = LocalGlassColors.current.textSecondary,
                                            fontSize = 13.sp,
                                            lineHeight = 18.sp, // GlassType.LHBody：旧 20sp=1.54× 超出 1.27–1.4 规范区间
                                            modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 8.dp),
                                        )
                                    }
                                    Spacer(Modifier.height(6.dp))
                                    if (current.status == UpdateUiState.Status.Downloading) {
                                        ProgressTrack(progress = current.progress, onSeek = {})
                                        Text(
                                            if (current.indeterminate) {
                                                "下载中，总大小未知，完成后自动打开安装程序"
                                            } else {
                                                "下载中 ${(current.progress * 100).toInt()}%，完成后自动打开安装程序"
                                            },
                                            color = LocalGlassColors.current.textTertiary, fontSize = 11.sp, lineHeight = 14.sp, // GlassType.LHMicro
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 8.dp),
                                        )
                                    } else {
                                        SheetAction(
                                            icon = GlassIcons.Down,
                                            label = when (current.status) {
                                                UpdateUiState.Status.ReadyToInstall -> "安装已下载的更新"
                                                else -> "立即更新"
                                            },
                                            subtitle = when (current.status) {
                                                UpdateUiState.Status.ReadyToInstall -> "无需重新下载"
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
                            // 坏字节必然再失败），交给上面的引导文案；其余沿用旧分流
                            // （进过下载阶段的失败 versionName 已带上新版本：直接重新下载，不再重新检查）
                            UpdateUiState.Status.Failed ->
                                if (update().failStage != UpdateUiState.FailStage.Verify) {
                                    if (update().versionName != null) onStartUpdate() else onCheckUpdate()
                                }
                            else -> onCheckUpdate()
                        }
                    },
                )
            }
            // 底部避让：悬浮 Chrome 高度由 core/ui 的 ChromeBottomInset 单点定义，
            // 三个主屏共用（原来各自硬编码 146.dp，Chrome 高度一改就得同步改三处）
            Spacer(Modifier.navigationBarsPadding().height(ChromeBottomInset))
            }
    }
}

/** 设置分组玻璃卡：18dp 圆角（.card glass → md 层级），行间 7% 白通栏分隔线 */
@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    GlassSurface(
        modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 14.dp),
        level = GlassLevel.Md,
        shape = RoundedCornerShape(R.Card18.dp),
    ) {
        Column { content() }
    }
}

@Composable
private fun RowDivider() {
    // .setrow border-bottom：整行通栏
    HorizontalDivider(
        color = LocalGlassColors.current.textPrimary.copy(alpha = 0.07f),
        thickness = 1.dp,
    )
}
