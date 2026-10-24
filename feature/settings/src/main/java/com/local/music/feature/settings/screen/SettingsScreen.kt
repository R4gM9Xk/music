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
import com.local.music.core.ui.state.EqPreset
import com.local.music.core.ui.state.GlassScreen
import com.local.music.core.ui.state.SleepTimerOptions
import com.local.music.core.ui.state.sleepTimerLabel
import com.local.music.core.ui.state.sleepTimerSubtitle
import com.local.music.core.ui.theme.GlassIcons
import com.local.music.core.ui.theme.GlassIntensity
import com.local.music.core.ui.theme.MotionIntensity
import com.local.music.core.ui.theme.LocalGlassColors
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
    // D2/D3：文件 IO 入口（SAF 选择器在 app 层持有，屏内只发起）
    onImportM3u: () -> Unit = {},
    onExportBackup: () -> Unit = {},
    onImportBackup: () -> Unit = {},
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
    LaunchedEffect(state.sleepTimerDeadlineMs) {
        while (true) {
            sleepRemainingSec = state.sleepTimerRemainingSec() ?: 0
            delay(1000)
        }
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

            SectionHeader("外观")
            SettingsCard {
                SettingRow(
                    GlassIcons.Wave, "跟随封面取色", "极光背景取自当前专辑封面",
                    trailing = { GlassToggle(state.followCover, onToggle = { state.followCover = it }) },
                )
                RowDivider()
                // F2 自定义主题色：三态（自定义 > 跟随封面 > 品牌靛紫），选中即生效
                SettingRow(
                    GlassIcons.Wave, "主题色",
                    when {
                        state.customSeedArgb != null -> "自定义颜色（点击更换）"
                        state.followCover -> "跟随封面 · 可改为自定义"
                        else -> "品牌靛紫 · 可改为自定义"
                    },
                    onClick = {
                        modal.show {
                            ModalSheet(modal, "主题色") {
                                // 实时预览：随选中色变化（取自同一 fromSeed 派生）
                                val previewSeed = state.customSeedArgb?.let { Color(it) }
                                AuroraMiniPreview(
                                    palette = previewSeed?.let { AuroraPalette.fromSeed(it) } ?: state.palette,
                                )
                                Spacer(Modifier.height(14.dp))
                                Text(
                                    "预设色板",
                                    color = LocalGlassColors.current.textTertiary,
                                    fontSize = 12.sp,
                                    modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
                                )
                                ThemeColorSwatches(
                                    seeds = AuroraPalette.PresetSeeds,
                                    selected = state.customSeedArgb?.let { Color(it) },
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
            }

            SectionHeader("播放")
            SettingsCard {
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
                                        emphasized = option == activeMode,
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
                    trailing = { GlassToggle(state.lyricEnabled, onToggle = { state.lyricEnabled = it }) },
                )
            }

            SectionHeader("曲库")
            SettingsCard {
                SettingRow(
                    GlassIcons.Folder, "扫描音乐",
                    if (scanning) "扫描中…" else "已收录 ${state.library.size} 首",
                    onClick = {
                        if (!scanning) {
                            scanning = true
                            onRescan { scanning = false } // 成功/失败/结果相同都复位，不依赖曲库状态变化
                        }
                    },
                )
                RowDivider()
                SettingRow(
                    GlassIcons.Folder, "过滤文件夹",
                    "${state.excludedFolders.size} 个目录已排除",
                    onClick = {
                        modal.show {
                            ModalSheet(modal, "过滤文件夹") {
                                state.folderOptions().forEach { (folder, count) ->
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
            }

            SectionHeader("关于")
            SettingsCard {
                val updateState = update()
                val updateSubtitle = when (updateState.status) {
                    UpdateUiState.Status.Idle ->
                        if (currentVersionName.isEmpty()) "检查新版本" else "当前 v$currentVersionName · 检查新版本"
                    UpdateUiState.Status.Checking -> "正在检查…"
                    UpdateUiState.Status.UpToDate -> "已是最新版本"
                    UpdateUiState.Status.Available ->
                        "发现新版本 v${updateState.versionName}，点击更新"
                    UpdateUiState.Status.Downloading ->
                        "下载中 ${(updateState.progress * 100).toInt()}%"
                    UpdateUiState.Status.ReadyToInstall ->
                        "已下载 v${updateState.versionName}，点击安装"
                    UpdateUiState.Status.Failed ->
                        // 下载链路的失败由既态 copy 而来（versionName 已带上新版本），与检查失败区分
                        if (updateState.versionName != null) "下载失败（${updateState.message ?: "网络异常"}），点击重试"
                        else "检查失败（${updateState.message ?: "网络异常"}），点击重试"
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
                                            lineHeight = 20.sp,
                                            modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 8.dp),
                                        )
                                    }
                                    Spacer(Modifier.height(6.dp))
                                    if (current.status == UpdateUiState.Status.Downloading) {
                                        ProgressTrack(progress = current.progress, onSeek = {})
                                        Text(
                                            "下载中 ${(current.progress * 100).toInt()}%，完成后自动打开安装程序",
                                            color = LocalGlassColors.current.textTertiary, fontSize = 11.5.sp,
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
                            // 曾进入下载阶段的失败（versionName 已带上新版本）：重试直接重新下载，不再重新检查
                            UpdateUiState.Status.Failed ->
                                if (update().versionName != null) onStartUpdate() else onCheckUpdate()
                            else -> onCheckUpdate()
                        }
                    },
                )
            }
            // 底部避让：悬浮 Chrome（迷你条+导航 ≈ 138dp）+ 手势条
            Spacer(Modifier.navigationBarsPadding().height(146.dp))
            }
    }
}

/** 设置分组玻璃卡：18dp 圆角（.card glass → md 层级），行间 7% 白通栏分隔线 */
@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    GlassSurface(
        modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 14.dp),
        level = GlassLevel.Md,
        shape = RoundedCornerShape(18.dp),
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
