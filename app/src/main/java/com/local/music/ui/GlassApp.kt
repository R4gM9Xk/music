package com.local.music.ui

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.local.music.core.ui.components.AuroraBackground
import com.local.music.core.ui.components.GlassChrome
import com.local.music.core.ui.components.GlassLevel
import com.local.music.core.ui.components.GlassSurface
import com.local.music.core.ui.components.LocalHazeState
import com.local.music.core.ui.components.LocalModalState
import com.local.music.core.ui.components.ModalState
import com.local.music.core.ui.components.asTime
import com.local.music.core.ui.state.AppState
import com.local.music.core.ui.state.GlassScreen
import com.local.music.core.ui.state.ThemeMode
import com.local.music.core.ui.theme.GlassMotion
import com.local.music.core.ui.theme.GlassTheme
import com.local.music.core.ui.theme.LocalGlassColors
import com.local.music.core.ui.theme.MotionIntensity
import com.local.music.feature.library.screen.LibraryScreen
import com.local.music.feature.player.screen.PlayerScreen
import com.local.music.feature.playlist.screen.PlaylistDetailScreen
import com.local.music.feature.playlist.screen.PlaylistScreen
import com.local.music.feature.search.screen.SearchScreen
import com.local.music.feature.settings.screen.SettingsScreen
import com.local.music.player.MediaControllerEngine
import com.local.music.player.MediaLibrary
import com.local.music.player.MusicPlaybackService
import com.local.music.state.SettingsStore
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 液态玻璃导航宿主（瘦壳）：
 * - 全部状态在 [AppState]（core:ui/state），此处只创建、驱动心跳并下发
 * - 启动申请 READ_MEDIA_AUDIO：通过后装载 /sdcard/Music 真曲库；
 *   播放经 MediaController 走 MusicPlaybackService（通知/锁屏/流体云/后台播放）
 * - 拒绝或无文件则保持演示曲库（定时器模拟播放）
 * - 所有页面共享一个极光背景；取色 = 当前歌曲封面主色（可被设置关闭）
 */
@Composable
fun GlassApp() {
    val context = LocalContext.current
    val state = remember { AppState() }
    val scope = rememberCoroutineScope()
    val store = remember { SettingsStore(context) }
    val modalState = remember { ModalState() }

    // 持久化：绑定存盘桥 + 启动恢复现场（restore 与曲库装载谁先到都幂等收敛）
    LaunchedEffect(Unit) {
        state.bindStore(store)
        runCatching { store.read() }.getOrNull()?.let { state.restore(it) }
    }
    // 设置变更 → 存盘（播放进度的存盘由 tick 每 5s 节流 + 各行为触发）
    LaunchedEffect(Unit) {
        snapshotFlow { state.settingsKey() }
            .drop(1)
            .collect { state.scheduleSave() }
    }
    // 退后台立即落盘：防抖 500ms 窗口内进程被杀会丢最近一次修改
    val activity = context as? ComponentActivity
    DisposableEffect(activity) {
        val lifecycleObserver = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                scope.launch { store.flush() }
            }
        }
        activity?.lifecycle?.addObserver(lifecycleObserver)
        onDispose { activity?.lifecycle?.removeObserver(lifecycleObserver) }
    }

    fun loadRealLibrary() {
        scope.launch {
            val songs = withContext(Dispatchers.IO) { MediaLibrary.load(context) }
            android.util.Log.d("MediaLibrary", "loadRealLibrary got ${songs.size}")
            state.loadLibrary(songs)
        }
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) loadRealLibrary() }

    LaunchedEffect(Unit) {
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.READ_MEDIA_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) loadRealLibrary() else permissionLauncher.launch(Manifest.permission.READ_MEDIA_AUDIO)
    }

    // 连接播放服务：MediaController 就绪后注入 AppState（通知/锁屏/后台由 service 承担）
    DisposableEffect(Unit) {
        val future = MediaController.Builder(
            context,
            SessionToken(context, ComponentName(context, MusicPlaybackService::class.java)),
        ).buildAsync()
        future.addListener(
            {
                val controller = runCatching { future.get() }.getOrNull()
                if (controller != null) {
                    state.attachPlayer(MediaControllerEngine(controller))
                } else {
                    state.reportEngineFailure("播放服务连接失败，无法播放本地音频")
                }
            },
            ContextCompat.getMainExecutor(context),
        )
        onDispose {
            runCatching { future.get().release() }
            future.cancel(false)
        }
    }

    // 播放进度心跳：真引擎读播放位置，演示模式定时推进（tick 内部处理歌尾）
    LaunchedEffect(Unit) {
        while (true) {
            delay(100)
            state.tick()
        }
    }
    // 动效强度 → 全局过渡时长乘数
    LaunchedEffect(state.motionLevel) {
        GlassMotion.scale = state.motionLevel.factor
    }
    // 深浅色 → 系统栏图标外观（浅色模式用深色图标）
    val view = LocalView.current
    LaunchedEffect(state.themeMode) {
        (context as? Activity)?.window?.let { window ->
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = state.themeMode == ThemeMode.Light
                isAppearanceLightNavigationBars = state.themeMode == ThemeMode.Light
            }
        }
    }
    // 返回键：模态面板优先关闭 → 详情回来源页 → 其余回曲库
    BackHandler(enabled = state.screen != GlassScreen.Library) {
        when {
            modalState.isOpen -> modalState.requestDismiss()
            state.screen == GlassScreen.Detail -> state.backFromDetail()
            else -> state.screen = GlassScreen.Library
        }
    }

    CompositionLocalProvider(LocalModalState provides modalState) {
        GlassTheme(
            palette = state.palette,
            isDark = state.themeMode == ThemeMode.Dark,
            glassIntensity = state.glassLevel,
        ) {
            val appHazeState = rememberHazeState()
            CompositionLocalProvider(LocalHazeState provides appHazeState) {
                Box(Modifier.fillMaxSize()) {
                    AuroraBackground()

                    Column(Modifier.fillMaxSize()) {
                        // 内容区：三个主屏（曲库/发现/我的）按 tab 顺序整屏左右平移，其余页淡入上移
                        Box(Modifier.weight(1f).hazeSource(appHazeState)) {
                            AnimatedContent(
                            targetState = state.screen,
                            transitionSpec = {
                                val order = mapOf(
                                    GlassScreen.Library to 0,
                                    GlassScreen.Playlist to 1,
                                    GlassScreen.Settings to 2,
                                )
                                val from = order[initialState]
                                val to = order[targetState]
                                if (from != null && to != null) {
                                    val forward = to > from
                                    (
                                        slideInHorizontally(GlassMotion.tweenPage()) { full ->
                                            if (forward) full else -full
                                        } + fadeIn(GlassMotion.tweenPage())
                                        ).togetherWith(
                                        slideOutHorizontally(GlassMotion.tweenPage()) { full ->
                                            if (forward) -full else full
                                        } + fadeOut(GlassMotion.tweenPage()),
                                    )
                                } else {
                                    (fadeIn(GlassMotion.tweenPage()) +
                                        slideInVertically(GlassMotion.tweenPage()) { it / 20 })
                                        .togetherWith(fadeOut(GlassMotion.tweenPage()))
                                }
                            },
                            label = "screen",
                            ) { target ->
                                when (target) {
                                    GlassScreen.Player -> PlayerScreen(state)
                                    GlassScreen.Library -> LibraryScreen(state)
                                    GlassScreen.Playlist -> PlaylistScreen(state)
                                    GlassScreen.Detail -> PlaylistDetailScreen(state)
                                    GlassScreen.Search -> SearchScreen(state)
                                    GlassScreen.Settings -> SettingsScreen(state, onRescan = { loadRealLibrary() })
                                }
                            }
                        }
                        // 底部 Chrome（迷你条 + 导航）：三个主屏共享，切换时完全静止；
                        // 模态面板打开时让位（面板 scrim 盖不住同级后绘制的 Chrome）
                        if (state.screen in TAB_SCREENS && !modalState.isOpen) {
                            GlassChrome(
                                song = state.currentSong,
                                playing = state.playing,
                                progress = state.progressFraction,
                                navSelected = when (state.screen) {
                                    GlassScreen.Playlist -> 1
                                    GlassScreen.Settings -> 2
                                    else -> 0
                                },
                                onNavSelect = { index ->
                                    state.screen = when (index) {
                                        0 -> GlassScreen.Library
                                        1 -> GlassScreen.Playlist
                                        else -> GlassScreen.Settings
                                    }
                                },
                                onOpenPlayer = { state.screen = GlassScreen.Player },
                                onPlayPause = state::togglePlayPause,
                                onNext = { state.next() },
                            )
                        }
                    }

                    // 播放错误提示条（4s 自动消退，点击关闭）
                    state.playbackError?.let { message ->
                        LaunchedEffect(message) {
                            delay(4000)
                            state.dismissPlaybackError()
                        }
                        GlassSurface(
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .statusBarsPadding()
                                .padding(top = 8.dp)
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp),
                            level = GlassLevel.Hi,
                            shape = RoundedCornerShape(16.dp),
                            onClick = state::dismissPlaybackError,
                        ) {
                            Text(
                                message,
                                color = LocalGlassColors.current.textPrimary,
                                fontSize = 12.5.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 底部 Chrome 承载的三个主屏（导航顺序：曲库 0 / 发现 1 / 我的 2） */
private val TAB_SCREENS = setOf(GlassScreen.Library, GlassScreen.Playlist, GlassScreen.Settings)
