package com.local.music.ui

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
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
import com.local.music.core.ui.components.FlightKeys
import com.local.music.core.ui.components.LocalHazeState
import com.local.music.core.ui.components.LocalModalState
import com.local.music.core.ui.components.LocalPlayerFlight
import com.local.music.core.ui.components.ModalSheetHost
import com.local.music.core.ui.components.ModalState
import com.local.music.core.ui.components.PlayerFlight
import com.local.music.core.ui.components.asTime
import com.local.music.core.ui.components.trackFlight
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
import com.local.music.player.QueueStateStore
import com.local.music.state.SettingsStore
import com.local.music.update.UpdateManager
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
    // 播放现场写盘串行器（C4）：单线程 + 后写覆盖先写，避免 tick 高频写乱序
    val queueStateExecutor = remember {
        java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "queue-state-writer").apply { isDaemon = true }
        }
    }
    DisposableEffect(Unit) {
        onDispose { queueStateExecutor.shutdown() }
    }
    val modalState = remember { ModalState() }
    // 播放页共享元素飞行动效（迷你条 ⇄ 播放页），纯 UI 态不进 AppState
    val flight = remember { PlayerFlight(scope) }

    // ── 所有文件访问（MANAGE_EXTERNAL_STORAGE）：读取任意公共目录的同名 .lrc 歌词 ──
    var allFilesGranted by remember { mutableStateOf(Environment.isExternalStorageManager()) }
    var jumpedAllFiles by remember { mutableStateOf(false) } // 每进程只自动跳一次，避免拒绝后反复打扰
    // 音频权限上次授权态：ON_RESUME 对比检测（用户在系统设置手动授权后返回能自动装载真曲库）
    var audioGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_AUDIO) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    fun openAllFilesAccessPage() {
        jumpedAllFiles = true
        val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}"))
        runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { // 个别 ROM 无直达页：退到总列表
                runCatching {
                    context.startActivity(
                        Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            }
    }
    fun maybeRequestAllFiles() {
        if (!allFilesGranted && !jumpedAllFiles) openAllFilesAccessPage()
    }

    fun loadRealLibrary(onDone: () -> Unit = {}) {
        scope.launch {
            // 扫描可能因权限被撤销抛 SecurityException：容错并上报，不让协程崩溃
            val result = withContext(Dispatchers.IO) { runCatching { MediaLibrary.load(context) } }
            result.exceptionOrNull()?.let {
                android.util.Log.e("MediaLibrary", "scan failed", it)
                state.reportEngineFailure("读取媒体库失败，请检查存储权限")
            }
            val songs = result.getOrDefault(emptyList())
            android.util.Log.d("MediaLibrary", "loadRealLibrary got ${songs.size}")
            state.loadLibrary(songs)
            if (songs.isNotEmpty()) {
                withContext(Dispatchers.IO) { runCatching { MediaLibrary.writeCache(context, songs) } }
            } else if (!state.librarySettled) {
                state.markLibrarySettled() // 无缓存且扫描为空：确认无曲库（演示模式终态）
            }
            onDone()
        }
    }

    // 持久化：绑定存盘桥 + 启动恢复现场（restore 与曲库装载谁先到都幂等收敛）
    LaunchedEffect(Unit) {
        state.bindStore(store)
        // C4：播放现场的服务端副本（进程被杀后 onPlaybackResumption 靠它恢复）。
        // 切 IO 线程写：scheduleSave 由 tick 每 5s 调用，同步写会卡 UI；
        // 用单线程串行执行器保证「后写的覆盖先写的」，不会出现乱序写
        state.queueStateWriter = { specs, index, positionSec ->
            queueStateExecutor.execute {
                QueueStateStore.write(context, specs, index, positionSec)
            }
        }
        val snapshot = runCatching { store.read() }.getOrNull()
        if (snapshot != null) {
            state.restore(snapshot)
        } else {
            state.markHydrated() // 首次启动/快照损坏：无现场可保护，放行存盘
        }
        // 曲库缓存即时真实化（应用私有文件、无需权限）：恢复场景 <1s 呈现，
        // 随后的全量重扫在后台对账（新增/删除歌曲、封面刷新）并重写缓存
        val cached = withContext(Dispatchers.IO) {
            runCatching { MediaLibrary.loadCache(context) }.getOrNull()
        }
        if (cached != null) state.loadLibrary(cached)
    }
    // 设置变更 → 存盘（播放进度的存盘由 tick 每 5s 节流 + 各行为触发）
    LaunchedEffect(Unit) {
        snapshotFlow { state.settingsKey() }
            .drop(1)
            .collect { state.scheduleSave() }
    }
    // 退后台立即落盘：防抖 500ms 窗口内进程被杀会丢最近一次修改；
    // 回前台刷新所有文件访问态——用户在系统页授权后回来时自动重扫曲库拾取歌词
    val activity = context as? ComponentActivity
    DisposableEffect(activity) {
        val lifecycleObserver = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                scope.launch { store.flush() }
            }
            if (event == Lifecycle.Event.ON_RESUME) {
                val grantedNow = Environment.isExternalStorageManager()
                val grantedBefore = allFilesGranted
                allFilesGranted = grantedNow
                if (jumpedAllFiles && grantedNow && !grantedBefore) {
                    jumpedAllFiles = false
                    loadRealLibrary() // 授权回来：重扫拾取各目录 .lrc
                }
                // 音频权限同款对比：系统设置里手动授权（不经运行时弹窗）返回时也能触发装载
                val audioNow = ContextCompat.checkSelfPermission(
                    context, Manifest.permission.READ_MEDIA_AUDIO,
                ) == PackageManager.PERMISSION_GRANTED
                val audioBefore = audioGranted
                audioGranted = audioNow
                if (audioNow && !audioBefore && !state.realLibraryLoaded) {
                    loadRealLibrary() // 真曲库未装载才扫：记住上次授权态，普通回前台不重扫
                }
            }
        }
        activity?.lifecycle?.addObserver(lifecycleObserver)
        onDispose { activity?.lifecycle?.removeObserver(lifecycleObserver) }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        when (result[Manifest.permission.READ_MEDIA_AUDIO]) {
            true -> {
                audioGranted = true // 同步记忆授权态：随后的 ON_RESUME 对比不再重复触发扫描
                loadRealLibrary()
                maybeRequestAllFiles() // 音频权限落定后再引导所有文件访问
            }
            false -> {
                state.abandonPendingPlayback() // 曲库永远不可用：放弃待恢复现场，按演示态如实存盘
                state.markLibrarySettled()
            }
            null -> Unit // 本次只补申请了通知权限
        }
    }

    LaunchedEffect(Unit) {
        fun granted(permission: String) = ContextCompat.checkSelfPermission(
            context, permission,
        ) == PackageManager.PERMISSION_GRANTED
        val audioGranted = granted(Manifest.permission.READ_MEDIA_AUDIO)
        val toRequest = buildList {
            if (!audioGranted) add(Manifest.permission.READ_MEDIA_AUDIO)
            if (!granted(Manifest.permission.POST_NOTIFICATIONS)) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (audioGranted) {
            loadRealLibrary()
            maybeRequestAllFiles() // 启动即引导一次（每进程一次；拒绝可从设置页再进）
        }
        if (toRequest.isNotEmpty()) permissionLauncher.launch(toRequest.toTypedArray())
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
            // 非阻塞释放：内部等 future 就绪后再 release（future.get() 会卡主线程）
            runCatching { MediaController.releaseFuture(future) }
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
    // 深浅色 → 系统栏图标外观（浅色模式用深色图标）；首帧后重设一次，
    // 部分 ROM（ColorOS）在窗口装饰初始化时会吃掉启动期的 isAppearanceLight 设置
    val view = LocalView.current
    LaunchedEffect(state.themeMode) {
        val window = (context as? Activity)?.window ?: return@LaunchedEffect
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        val light = state.themeMode == ThemeMode.Light
        fun apply() {
            controller.isAppearanceLightStatusBars = light
            controller.isAppearanceLightNavigationBars = light
        }
        apply()
        withFrameNanos { }
        apply()
        view.post { apply() }
    }
    // 应用内更新：启动 5s 后静默检查（失败无声，有新版点亮设置行）
    val updateManager = remember { UpdateManager(context, scope) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(5_000)
        updateManager.checkSilently()
    }

    // 返回键：模态面板优先关闭 → 详情回来源页 → 播放页逆向飞行收起 → 其余回曲库
    // （曲库页面板开着时也要拦截：否则返回直接穿透 finish 掉 Activity）
    BackHandler(enabled = state.screen != GlassScreen.Library || modalState.isOpen) {
        when {
            modalState.isOpen -> modalState.requestDismiss()
            state.screen == GlassScreen.Detail -> state.backFromDetail()
            state.screen == GlassScreen.Player -> flight.beginExit(state.currentSong) { state.screen = it }
            else -> state.screen = GlassScreen.Library
        }
    }

    // 面板不跨屏存活（旧结构面板随屏卸载的等价语义）：切屏即请求关闭。
    // 覆盖唯一无 scrim 拦截的入口——详情/搜索页 alpha=0 让位的悬浮 Chrome 仍可点击
    // （graphicsLayer 只隐不挡触摸），其飞行进入播放页时面板由此在幕后收场；
    // 面板自身动作触发的切屏（如集合面板「全部播放」）已先 requestDismiss，此处为 no-op
    LaunchedEffect(state.screen) {
        if (modalState.isOpen) modalState.requestDismiss()
    }

    CompositionLocalProvider(LocalModalState provides modalState, LocalPlayerFlight provides flight) {
        GlassTheme(
            palette = state.palette,
            isDark = state.themeMode == ThemeMode.Dark,
            glassIntensity = state.glassLevel,
        ) {
            val appHazeState = rememberHazeState()
            CompositionLocalProvider(LocalHazeState provides appHazeState) {
                // 根尺寸变化失效：configChanges 吞掉旋转/分屏/自由窗口，组合不重建，只能在此感知。
                // remember 上次尺寸跳过首组合的初值回调，尺寸真变化才取消飞行并清空 rect
                // 注册表（防 keepOnDispose 冻结的迷你条旧几何把替身带向旧坐标）
                var lastRootSize by remember { mutableStateOf<IntSize?>(null) }
                Box(
                    Modifier
                        .fillMaxSize()
                        .onSizeChanged { size ->
                            val last = lastRootSize
                            if (last != null && last != size) flight.onRootSizeChanged()
                            lastRootSize = size
                        }
                        .trackFlight(FlightKeys.AppRoot, keepOnDispose = true),
                ) {
                    AuroraBackground()

                    Box(Modifier.fillMaxSize()) {
                        // 内容区：三个主屏（曲库/发现/我的）按 tab 顺序整屏左右平移，其余页淡入上移。
                        // 恒全高——Chrome 悬浮在根层后绘，显隐不再挤占布局（飞行两端/模态开合零 reflow）
                        Box(Modifier.fillMaxSize().hazeSource(appHazeState)) {
                            if (!state.librarySettled) {
                                // 启动收敛中（曲库缓存/首扫未到）：不渲染任何演示内容
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Text(
                                        "正在整理曲库…",
                                        color = LocalGlassColors.current.textSecondary,
                                        fontSize = 12.sp,
                                    )
                                }
                            } else AnimatedContent(
                            targetState = state.screen,
                            transitionSpec = {
                                when {
                                    // 共享元素飞行·进入：播放页元素自理（EnterTransition.None），旧 tab 页淡出
                                    flight.isActive && targetState == GlassScreen.Player -> {
                                        EnterTransition.None togetherWith fadeOut(GlassMotion.tweenFlight())
                                    }
                                    // 共享元素飞行·退出中段翻页：库页淡入接管，播放页元素已全透明离场
                                    flight.isActive && initialState == GlassScreen.Player -> {
                                        fadeIn(
                                            tween(((GlassMotion.Flight * GlassMotion.scale * 0.6f)).toInt().coerceAtLeast(1),
                                                easing = GlassMotion.Curve),
                                        ) togetherWith ExitTransition.None
                                    }
                                    else -> {
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
                                    }
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
                                    GlassScreen.Settings -> SettingsScreen(
                                        state,
                                        onRescan = { onDone -> loadRealLibrary(onDone) },
                                        // getter 形态：更新面板经 modal.show 挂根层宿主后，
                                        // 只有活读 snapshot 态才能在宿主作用域持续收到下载进度
                                        update = { updateManager.state },
                                        currentVersionName = remember {
                                            try {
                                                context.packageManager
                                                    .getPackageInfo(context.packageName, 0).versionName ?: ""
                                            } catch (e: Exception) {
                                                ""
                                            }
                                        },
                                        onCheckUpdate = updateManager::check,
                                        onStartUpdate = updateManager::startUpdate,
                                        onInstallUpdate = updateManager::install,
                                        allFilesGranted = allFilesGranted,
                                        onAllFilesAccess = {
                                            if (allFilesGranted) {
                                                loadRealLibrary() // 已授权：重扫刷新歌词
                                            } else {
                                                openAllFilesAccessPage()
                                            }
                                        },
                                    )
                                }
                            }
                        }
                        // 底部 Chrome（迷你条 + 导航）：悬浮于根层、与内容区同层后绘——
                        // 显隐只控组合不控布局，内容区恒全高，飞行两端/模态开合/tab 切换均无 reflow。
                        // 飞行期不组合：进入时随 screen=Player 卸载（替身按冻结 mini 几何原地无缝接位），
                        // 退出时等 t=0.15 翻页才随 Library 出现（迷你条/胶囊由真身曲线淡入接位）
                        if (state.screen in TAB_SCREENS && state.librarySettled && !modalState.isOpen) {
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
                                onOpenPlayer = {
                                    val navSelected = when (state.screen) {
                                        GlassScreen.Playlist -> 1
                                        GlassScreen.Settings -> 2
                                        else -> 0
                                    }
                                    // 忙时兜底直切（同 PlaylistDetailScreen）：飞行中不重入共享元素动画
                                    if (flight.isActive) state.screen = GlassScreen.Player
                                    else flight.beginEntry(state.currentSong, navSelected) { state.screen = it }
                                },
                                onPlayPause = state::togglePlayPause,
                                onNext = { state.next() },
                                modifier = Modifier.align(Alignment.BottomCenter),
                            )
                        }

                        // ── 模态面板宿主：全 App GlassSheet 的唯一挂载点 ──
                        // 内容区 hazeSource 的兄弟层（z 序在 Chrome 之后）：面板玻璃不再位于
                        // app 级源子树内（铁律①），scrim 全屏盖住内容与各屏自绘 Chrome，
                        // 消费的模糊源统一为 app 级（appHazeState）。
                        // 飞行期不渲染：结构性保证「飞行期无面板」（旧设计=屏内 sheet 随屏卸载）
                        if (!flight.isActive) {
                            ModalSheetHost(modalState)
                        }
                    }

                    // 共享元素飞行覆盖层：6 个替身（玻璃/封面/歌名/作者/播放/下一首），纯绘制不挡触摸
                    PlayerFlightOverlay(flight, playing = state.playing)

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
