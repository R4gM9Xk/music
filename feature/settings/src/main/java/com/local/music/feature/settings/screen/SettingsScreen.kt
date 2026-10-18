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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.local.music.core.ui.components.GlassLevel
import com.local.music.core.ui.components.GlassSheet
import com.local.music.core.ui.components.GlassSurface
import com.local.music.core.ui.components.GlassToggle
import com.local.music.core.ui.components.PageHeader
import com.local.music.core.ui.components.ProgressTrack
import com.local.music.core.ui.components.SectionHeader
import com.local.music.core.ui.components.SettingRow
import com.local.music.core.ui.components.SheetAction
import com.local.music.core.ui.components.LocalHazeState
import com.local.music.core.ui.state.AppState
import com.local.music.core.ui.state.EqPreset
import com.local.music.core.ui.state.GlassScreen
import com.local.music.core.ui.theme.GlassIcons
import com.local.music.core.ui.theme.GlassIntensity
import com.local.music.core.ui.theme.MotionIntensity
import com.local.music.core.ui.theme.LocalGlassColors
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState

/** 设置页内底部面板 */
private enum class SettingsSheet { Glass, Motion, Eq, Folders }

/**
 * 设置：外观 / 播放 / 曲库 三组玻璃卡。全部接入 AppState——
 * 取色与歌词开关实时生效；玻璃/动效强度改全局 token；均衡器经服务端 Equalizer 生效；
 * 扫描入口真实重扫 MediaStore；文件夹过滤选项从当前曲库派生。
 */
@Composable
fun SettingsScreen(
    state: AppState,
    onRescan: (onDone: () -> Unit) -> Unit = { it() },
    update: UpdateUiState = UpdateUiState(),
    currentVersionName: String = "",
    onCheckUpdate: () -> Unit = {},
    onStartUpdate: () -> Unit = {},
    onInstallUpdate: () -> Unit = {},
    allFilesGranted: Boolean = false,
    onAllFilesAccess: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var sheet by remember { mutableStateOf<SettingsSheet?>(null) }
    var showUpdateSheet by remember { mutableStateOf(false) }
    var scanning by remember { mutableStateOf(false) }

    // 扫描完成（曲库对象被整体替换）后结束扫描态
    LaunchedEffect(state.library) {
        if (scanning) scanning = false
    }

    val hazeState = rememberHazeState()
    CompositionLocalProvider(LocalHazeState provides hazeState) {
        Column(modifier.fillMaxSize().statusBarsPadding()) {
            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .hazeSource(hazeState),
            ) {
                PageHeader(title = "设置")

            SectionHeader("外观")
            SettingsCard {
                SettingRow(
                    GlassIcons.Wave, "跟随封面取色", "极光背景取自当前专辑封面",
                    trailing = { GlassToggle(state.followCover, onToggle = { state.followCover = it }) },
                )
                RowDivider()
                SettingRow(
                    GlassIcons.Book, "玻璃强度", "${state.glassLevel.label}（可改通透 / 浓郁）",
                    onClick = { sheet = SettingsSheet.Glass },
                )
                RowDivider()
                SettingRow(
                    GlassIcons.Gear, "动效强度", "${state.motionLevel.label}（可改克制 / 增强）",
                    onClick = { sheet = SettingsSheet.Motion },
                )
            }

            SectionHeader("播放")
            SettingsCard {
                SettingRow(
                    GlassIcons.Wave, "均衡器", state.eqPreset.label,
                    onClick = { sheet = SettingsSheet.Eq },
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
                    onClick = { sheet = SettingsSheet.Folders },
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

            SectionHeader("关于")
            SettingsCard {
                val updateSubtitle = when (update.status) {
                    UpdateUiState.Status.Idle ->
                        if (currentVersionName.isEmpty()) "检查新版本" else "当前 v$currentVersionName · 检查新版本"
                    UpdateUiState.Status.Checking -> "正在检查…"
                    UpdateUiState.Status.UpToDate -> "已是最新版本"
                    UpdateUiState.Status.Available ->
                        "发现新版本 v${update.versionName}，点击更新"
                    UpdateUiState.Status.Downloading ->
                        "下载中 ${(update.progress * 100).toInt()}%"
                    UpdateUiState.Status.ReadyToInstall ->
                        "已下载 v${update.versionName}，点击安装"
                    UpdateUiState.Status.Failed ->
                        "检查失败（${update.message ?: "网络异常"}），点击重试"
                }
                SettingRow(
                    GlassIcons.Down, "检查更新", updateSubtitle,
                    onClick = {
                        when (update.status) {
                            UpdateUiState.Status.Available, UpdateUiState.Status.Downloading,
                            UpdateUiState.Status.ReadyToInstall,
                            -> showUpdateSheet = true
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

    // ── 更新面板（changelog + 进度 + 一键更新）──────────────
    GlassSheet(
        visible = showUpdateSheet && update.status in setOf(
            UpdateUiState.Status.Available, UpdateUiState.Status.Downloading, UpdateUiState.Status.ReadyToInstall,
        ),
        onDismiss = { showUpdateSheet = false },
        title = if (update.versionName != null) "更新到 v${update.versionName}" else "更新",
    ) {
        if (update.changelog != null) {
            Text(
                update.changelog,
                color = LocalGlassColors.current.textSecondary,
                fontSize = 13.sp,
                lineHeight = 20.sp,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 8.dp),
            )
        }
        Spacer(Modifier.height(6.dp))
        if (update.status == UpdateUiState.Status.Downloading) {
            ProgressTrack(progress = update.progress, onSeek = {})
            Text(
                "下载中 ${(update.progress * 100).toInt()}%，完成后自动打开安装程序",
                color = LocalGlassColors.current.textTertiary, fontSize = 11.5.sp,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 8.dp),
            )
        } else {
            SheetAction(
                icon = GlassIcons.Down,
                label = when (update.status) {
                    UpdateUiState.Status.ReadyToInstall -> "安装已下载的更新"
                    else -> "立即更新"
                },
                subtitle = when (update.status) {
                    UpdateUiState.Status.ReadyToInstall -> "无需重新下载"
                    else -> "下载完成后自动打开安装程序"
                },
                onClick = {
                    if (update.status == UpdateUiState.Status.ReadyToInstall) {
                        onInstallUpdate()
                    } else {
                        onStartUpdate()
                    }
                },
            )
        }
    }

    // ── 玻璃强度（三档，改全局玻璃 token）──────────────────
    GlassSheet(visible = sheet == SettingsSheet.Glass, onDismiss = { sheet = null }, title = "玻璃强度") {
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
                    sheet = null
                },
            )
        }
    }
    // ── 动效强度（三档，缩放全局过渡时长）────────────────────
    GlassSheet(visible = sheet == SettingsSheet.Motion, onDismiss = { sheet = null }, title = "动效强度") {
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
                    sheet = null
                },
            )
        }
    }
    // ── 均衡器（预设选择，经服务端 Equalizer 实时生效）───────
    GlassSheet(visible = sheet == SettingsSheet.Eq, onDismiss = { sheet = null }, title = "均衡器") {
        EqPreset.entries.forEach { preset ->
            SheetAction(
                icon = GlassIcons.Wave,
                label = preset.label,
                subtitle = if (preset == EqPreset.Off) "不启用音效" else "预设 · 实时生效",
                emphasized = state.eqPreset == preset,
                onClick = {
                    state.selectEqPreset(preset) // 立即下发服务端 Equalizer
                    sheet = null
                },
            )
        }
    }
    // ── 过滤文件夹（多选，从当前曲库派生；曲库「文件夹」tab 同步隐藏）─────────
    GlassSheet(visible = sheet == SettingsSheet.Folders, onDismiss = { sheet = null }, title = "过滤文件夹") {
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
        SheetAction(icon = GlassIcons.Down, label = "完成", onClick = { sheet = null })
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
