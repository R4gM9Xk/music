package com.local.music.ui

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import com.local.music.crash.CrashHandler
import com.local.music.core.ui.components.AuroraBackground
import com.local.music.core.ui.components.GlassChrome
import com.local.music.core.ui.components.GlassLevel
import com.local.music.core.ui.components.GlassSurface
import com.local.music.core.ui.components.FlightKeys
import com.local.music.core.ui.components.LocalHazeState
import com.local.music.core.ui.components.LocalModalState
import com.local.music.core.ui.components.LocalPlayerFlight
import com.local.music.core.ui.components.LocalSystemActions
import com.local.music.core.ui.components.SystemActions
import com.local.music.core.ui.components.ModalSheetHost
import com.local.music.core.ui.components.ModalState
import com.local.music.core.ui.components.PlayerFlight
import com.local.music.core.ui.components.asTime
import com.local.music.core.ui.components.trackFlight
import com.local.music.core.ui.state.AppState
import com.local.music.core.ui.state.GlassScreen
import com.local.music.core.ui.state.RestoreReport
import com.local.music.core.ui.state.ThemeMode
import com.local.music.core.ui.state.restoreReportMessage
import com.local.music.core.ui.theme.GlassMotion
import com.local.music.core.ui.theme.GlassText
import com.local.music.core.ui.theme.LibraryOrigin
import com.local.music.core.ui.theme.LocalLibraryOrigin
import com.local.music.core.ui.theme.GlassTheme
import com.local.music.core.ui.theme.LocalGlassColors
import com.local.music.core.ui.theme.MotionIntensity
import com.local.music.core.ui.theme.R
import com.local.music.core.ui.theme.S
import com.local.music.feature.library.screen.LibraryScreen
import com.local.music.feature.player.screen.PlayerScreen
import com.local.music.feature.playlist.screen.PlaylistDetailScreen
import com.local.music.feature.playlist.screen.PlaylistScreen
import com.local.music.feature.search.screen.SearchScreen
import com.local.music.feature.settings.screen.SettingsScreen
import com.local.music.lyrics.DesktopLyricsStore
import com.local.music.player.AutoStartGuide
import com.local.music.player.MediaControllerEngine
import com.local.music.player.MediaLibrary
import com.local.music.player.MusicPlaybackService
import com.local.music.player.M3uPlaylistIO
import com.local.music.player.QueueStateStore
import com.local.music.share.ShareActions
import com.local.music.state.SettingsStore
import com.local.music.state.SnapshotReadResult
import com.local.music.update.UpdateManager
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
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
    // 导航态跨 Activity 重建保活（P2-26）。
    //
    // 为什么不用 AppState 的 DataStore 快照：那是**跨进程持久化**，重启应用后回到上次那一页
    // 反而是骚扰；而纯 remember 又撑不过重建。中间这层「只活在本次任务栈」的状态正是
    // rememberSaveable 的语义。真机症状：在播放页改系统字号（fontScale 不在 manifest 的
    // configChanges 里 → 必重建）→ 被弹回曲库首页，正在看的详情/搜索词一并丢失。
    //
    // 顺序**必须**是「先取回 savable，再造 AppState」：还原要在构造时同步做完，
    // 不能放进 LaunchedEffect —— 下面还有一条 effect 会在每次导航变化时把当前值写回
    // savedNav，两者同帧竞争时谁先跑取决于声明顺序，写回先跑就把待还原的值冲掉了。
    // 首次组合 savedNav 为 null（无还原），重建后才有值。
    val savedNav = rememberSaveable { mutableStateOf<List<String>?>(null) }
    val state = remember {
        AppState().also { s -> savedNav.value?.let { s.restoreNavigationState(it) } }
    }
    val scope = rememberCoroutineScope()
    val store = remember { SettingsStore(context) }

    // 播放现场写盘串行器（C4）：单线程 + 后写覆盖先写，避免 tick 高频写乱序
    val queueStateExecutor = remember {
        java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "queue-state-writer").apply { isDaemon = true }
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            // **必须先解绑写入器再关执行器**：state.queueStateWriter 是个存活到 AppState 销毁的
            // lambda，它内部会 queueStateExecutor.execute(...)。若只 shutdown 不解绑，
            // dispose 之后任何一次 scheduleSave()（tick 每 20s / 用户操作）都会在已关闭的
            // executor 上 execute → RejectedExecutionException → UI 线程未捕获 → 崩溃。
            state.queueStateWriter = null
            queueStateExecutor.shutdown()
        }
    }
    val modalState = remember { ModalState() }
    // 播放页共享元素飞行动效（迷你条 ⇄ 播放页），纯 UI 态不进 AppState
    val flight = remember { PlayerFlight(scope) }

    // ── 所有文件访问（MANAGE_EXTERNAL_STORAGE）：读取任意公共目录的同名 .lrc 歌词 ──
    var allFilesGranted by remember { mutableStateOf(Environment.isExternalStorageManager()) }
    // All-Files 自动跳转的「已提示过」标记：**跨进程持久化**，不是进程内布尔。
    // 旧实现用 remember 的 Boolean（jumpedAllFiles），注释自认「每进程一次」——但对坚持不授权的
    // 用户，"每进程一次"实际等于「每次冷启动都把人拽到系统页一次」，属每次启动的打扰。
    // 现在改为：只在从未提示过时自动跳一次，之后再进全靠设置页的手动入口（功能重复但不再打扰）。
    var allFilesPrompted by remember { mutableStateOf(true) } // 初值 true = 偏好未读完前不跳
    var allFilesPromptRead by remember { mutableStateOf(false) } // 持久化标记已读过（下面据此单点触发）
    var jumpedAllFiles by remember { mutableStateOf(false) } // 本进程内是否已自动跳过（防反复跳）
    // 音频权限上次授权态：ON_RESUME 对比检测（用户在系统设置手动授权后返回能自动装载真曲库）
    var audioGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_AUDIO) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    /** 音频权限被拒（运行时弹窗拒绝 / 从未授权）：UI 据此在曲库页与设置页给出应用内再引导入口 */
    var audioDenied by remember { mutableStateOf(false) }
    /** 本次进程内是否已经弹过一次运行时权限弹窗（判断「不再询问」态用，见 requestAudioPermission）。
     *  P3-46：rememberSaveable 而不是 remember——Activity 重建（配置变更/内存回收重启）会把
     *  remember 清零，用户勾了「不再询问」后系统弹窗秒拒，旧实现却因标记丢失再次 launch，
     *  表现为「点『去授权』没任何反应」每进程复现一次；Saveable 随实例状态恢复，重启后
     *  直接走系统详情页引导（最小修：不引入 SettingsStore 新键，该文件不在本修复写域） */
    var audioDenialShown by rememberSaveable { mutableStateOf(false) }
    /**
     * P1-②：快照读取失败（损坏/来自更新版本/IO 异常）。
     *
     * 为 true 时【不】markHydrated → [com.local.music.core.ui.state.AppState.scheduleSave]
     * 全程空转，磁盘上的旧快照原样保留（readResult 已把坏串留档到 corrupt_snapshot/）。
     * 这既是防止「默认值覆盖用户设置」的闸门，也是给 UI 的提示信号：
     * 设置页据此显示「设置快照读取失败，本次改动不会保存」，而不是静默丢设置。
     */
    var corruptSnapshot by remember { mutableStateOf(false) }

    /** 打开本应用的系统详情页（权限在「权限」子页，用户可在此手动开音频访问） */
    fun openAppDetailsPage() {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }.onFailure {
            runCatching {
                context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
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
        // 读持久化标记之前不得自动跳（初值是 true，读完后才可能放开）
        if (!allFilesPromptRead || allFilesGranted || jumpedAllFiles || allFilesPrompted) return
        allFilesPrompted = true // 先置位：跳转失败也不该每次启动重跳
        scope.launch { runCatching { store.markAllFilesPrompted() } }
        openAllFilesAccessPage()
    }

    fun loadRealLibrary(onDone: () -> Unit = {}) {
        // P2-19 取消探针：MediaLibrary.load 是阻塞式扫描（IO 线程内联跑游标循环），
        // 探针 lambda 由扫描线程调用——非 suspend lambda 里取不到协程上下文，只能
        // 在启动前捕获组合作用域的 Job 引用给探针判活。Activity 重建/离开组合时
        // rememberCoroutineScope 被取消 → Job 失活 → 游标循环开头命中 break 提前返回
        // （aborted 半截结果由 MediaLibrary.writeCache 默认路径自守卫，不会覆盖好缓存）
        val probeJob = scope.coroutineContext[Job]
        scope.launch {
            // 扫描可能因权限被撤销抛 SecurityException：容错并上报，不让协程崩溃
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    // P2-④：**始终全量扫描**（filterShort = false）。
                    //
                    // B3 的设计是「扫描/缓存全量、过滤只在输出层」，但旧实现又把开关传了进来，
                    // 于是 library 里根本没有短音频条目——输出层再想过滤也无从滤起，反过来
                    // 用户关掉过滤时也没有条目可放出来（必须重扫）。两个后果都实测到了：
                    // 「扫描过滤=ON 却显示全量 11 首」（输出层被绕过时无处兜底）以及
                    // 「拨开关不立即生效，要重扫才对」。
                    //
                    // 现在扫描恒定全量，过滤唯一生效点是 AppState.visibleLibrary()：
                    // 开关两个方向都即时生效、零重扫，library_cache.json 也自然是全量的
                    // （writeCache 里原先要靠 lastScan 兜底并入短音频的那段因此变成纯冗余）。
                    // 同时传取消探针（见上）：Activity 重建/离开后阻塞式扫描能提前 break。
                    MediaLibrary.load(
                        context,
                        filterShort = false,
                        isCancelled = { probeJob?.isActive != true },
                    )
                }
            }
            result.exceptionOrNull()?.let {
                android.util.Log.e("MediaLibrary", "scan failed", it)
                state.reportEngineFailure("读取媒体库失败，请检查存储权限")
            }
            val songs = result.getOrDefault(emptyList())
            state.loadLibrary(songs)
            // 缓存写回策略按「扫描是否成功」分流，而不是按「有没有歌」——
            // 旧写法把「扫到 0 首」和「扫描失败」混在同一个 else 里，两种情况都不写缓存，
            // 于是用户删空曲库后旧 library_cache.json 仍保留全部已删曲目：冷启动读回幽灵曲库，
            // 而 librarySettled 已被置 true 使后续纠正分支永不触发，只能清应用数据才好。
            // - 成功：无论空不空都写（空库写成空数组，覆盖掉陈旧缓存）
            // - 失败：不写（一次权限异常不该把好缓存清空）
            if (result.isSuccess) {
                // P2-④ 起扫描恒为全量，songs 即完整曲库，缓存如实全量落盘
                // （原先「并入 lastScan 以保留被滤掉的短音频」的兜底已成冗余，无需再依赖）。
                withContext(Dispatchers.IO) { runCatching { MediaLibrary.writeCache(context, songs) } }
                if (songs.isEmpty() && !state.librarySettled) {
                    state.markLibrarySettled() // 扫描成功但确实没有曲目：确认无曲库终态
                }
            } else if (!state.librarySettled) {
                // **扫描失败 ≠ 确认无曲库**：旧实现在这里也调 markLibrarySettled()，而该函数会
                // abandonPendingPlayback()——一次 MediaStore 瞬时错误/权限竞态就把用户的恢复队列
                // 永久丢掉（之后重扫成功曲库会回来，播放现场却回不来了）。
                // 失败时只放行启动门面（让 UI 不再卡在「正在整理曲库…」），**不清 pending**：
                // pending 在后续 loadLibrary 成功时仍会被消费，扫到空库时才由成功分支收终态。
                state.settleLibraryKeepingPending()
            }
            // onDone 必须无条件执行：它是设置页「扫描中…」的唯一复位信号。
            // 若上面任一步抛异常（loadLibrary 内部、写缓存等）而跳过它，按钮会永久停在
            // 「扫描中…」直到离开该页。用 runCatching 包住整个尾部，最后 finally 语义地调 onDone。
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
            // runCatching：executor 可能在 dispose 后已被 shutdown（queueStateWriter 的解绑
            // 与 shutdown 之间仍有一个极窄窗口），此处兜底避免抛到 UI 线程
            runCatching {
                queueStateExecutor.execute {
                    QueueStateStore.write(context, specs, index, positionSec)
                }
            }
        }
        // All-Files 引导是否已提示过（跨进程）：决定启动时会不会再自动跳系统页
        allFilesPrompted = withContext(Dispatchers.IO) {
            runCatching { store.allFilesPrompted() }.getOrDefault(false)
        }
        allFilesPromptRead = true
        // 音频权限终态记忆：冷启动时若已拒绝过，曲库页/设置页直接给出应用内再引导入口
        audioDenied = ContextCompat.checkSelfPermission(
            context, Manifest.permission.READ_MEDIA_AUDIO,
        ) != PackageManager.PERMISSION_GRANTED
        // P1-②：三态读取，区分「真首装」与「快照损坏」。
        //
        // 旧写法 `store.read()` 只有 null/非 null 两态，而 null 同时表示「键不存在（首装）」
        // 和「解码失败（损坏）」。损坏时走 markHydrated() 放行存盘 → 服务端 queue_state.json
        // 恢复出的播放字段（queue/queueIndex/progressSec）被填进默认状态，tick 存盘把
        // **全默认设置**（favorites=[]、eqPreset=Off、songSort=Title…）覆盖写盘，用户改动
        // 不可恢复。实测就是这么丢的（报告 §1 P1-②）。
        //
        // 现在：损坏时**不**放行存盘（hydrated 保持 false，scheduleSave 全部空转），
        // 原坏串已由 readResult() 留档到 files/corrupt_snapshot/，磁盘上的快照保持原样；
        // 同时把「快照已损坏」这一事实暴露给 UI（corruptSnapshot 标志），让用户知道
        // 设置回落到默认是**因为读取失败**，而不是自己改丢了。
        // 只有真正的「键不存在（首装）」才 markHydrated 放行首份快照。
        val readOutcome = runCatching { store.readResult() }
        when (val readResult = readOutcome.getOrNull()) {
            // 冷启动恢复走普通 restore（不是 restoreFromBackup），场景语义见 AppState 里
            // restoreFromBackupFile 的注释：同一台设备冷启动出现失效引用是日常（删过歌），
            // 不该弹横幅；只有**备份文件恢复**才需要无条件报告迁移结果。
            is SnapshotReadResult.Ok -> state.restore(readResult.snapshot)
            // 首装：无现场可保护，放行存盘写出第一份快照
            SnapshotReadResult.Absent -> state.markHydrated()
            // 读取/解析异常：不放行存盘（保住磁盘上的旧快照，留待后续人工/版本修复），
            // 也不 restore（无可用数据）——UI 显示默认值 + 损坏提示
            is SnapshotReadResult.Corrupt -> {
                corruptSnapshot = true
                android.util.Log.e(
                    "GlassApp",
                    "snapshot corrupt (len=${readResult.raw.length}); holding writes to preserve on-disk state",
                )
            }
            // readResult 自身抛异常（DataStore IO 错误）：同损坏处理，宁可只读也不覆盖
            null -> {
                corruptSnapshot = true
                android.util.Log.e("GlassApp", "snapshot read threw; holding writes", readOutcome.exceptionOrNull())
            }
        }
        // 曲库缓存即时真实化（应用私有文件、无需权限）：恢复场景 <1s 呈现，
        // 随后的全量重扫在后台对账（新增/删除歌曲、封面刷新）并重写缓存。
        // filterShort 取**已恢复的内存态**（state.scanFilterShort）而不是磁盘独立键：
        // 两者正常时同值，但快照损坏/防抖窗口内会分叉，读磁盘会让「设置页开关」与
        // 「实际显示的曲目」互相打脸（P2-④）。快照读取在本 effect 中已先完成，
        // 这里取到的就是用户当前看到的那个开关值，两个界面必然一致。
        val cached = withContext(Dispatchers.IO) {
            runCatching {
                MediaLibrary.loadCache(context, filterShort = state.scanFilterShort)
            }.getOrNull()
        }
        // **必须门控 realLibraryLoaded**：本 effect 与「扫描 effect」是并发的两条链。
        // 增量扫描（热缓存，几十 ms）可能先于 loadCache（全量读缓存 + 每首 readLrc，数百次小文件
        // IO）完成；扫描先到会把 library 换成新结果，随后陈旧的缓存再无条件 loadLibrary 进来，
        // 走 loadLibrary 的重扫分支整引用换回旧列表 → 新歌消失 / 已删歌复活，持续整个会话。
        if (cached != null && !state.realLibraryLoaded) state.loadLibrary(cached)
    }
    // SettingsStore 持有自建 IO scope + 常驻 collector：Activity 重建（密度/字号/语言变更等
    // manifest 未吞的配置变更）会再 remember 一个新实例，旧实例的 scope 无人取消 → 泄漏。
    // 显式在组合销毁时停掉，flush 一次保住最近快照（防抖窗口内被杀会丢最后一次修改）。
    DisposableEffect(store) {
        onDispose {
            runCatching { store.flushBlocking() }
            store.cancel()
        }
    }
    // 设置变更 → 存盘（播放进度的存盘由 tick 每 5s 节流 + 各行为触发）
    LaunchedEffect(Unit) {
        snapshotFlow { state.settingsKey() }
            .drop(1)
            .collect { state.scheduleSave() }
    }
    // 导航态 → rememberSaveable（P2-26）。只在导航四项真的变化时重跑：
    // 阅读这四个 state 就会建立快照订阅，其余状态（进度/播放态每 100ms 变）不会触发。
    // 写回自身这一份是纯值拷贝，不反写 AppState，因此不存在回环。
    LaunchedEffect(state.screen, state.detail, state.detailReturn, state.playerOrigin) {
        val nav = state.navigationState()
        if (savedNav.value != nav) savedNav.value = nav
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
                // **不要绑 jumpedAllFiles**：那个标记只在「应用主动拉起系统页」时置位，
                // 用户自行去系统设置开「所有文件访问」再返回时它是 false，于是不重扫、
                // .lrc 不被拾取。grantedNow && !grantedBefore 已经足够表达「刚刚获得授权」。
                if (grantedNow && !grantedBefore) {
                    jumpedAllFiles = false
                    loadRealLibrary() // 授权回来：重扫拾取各目录 .lrc
                }
                // 音频权限同款对比：系统设置里手动授权（不经运行时弹窗）返回时也能触发装载
                val audioNow = ContextCompat.checkSelfPermission(
                    context, Manifest.permission.READ_MEDIA_AUDIO,
                ) == PackageManager.PERMISSION_GRANTED
                val audioBefore = audioGranted
                audioGranted = audioNow
                audioDenied = !audioNow
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
                audioDenied = false
                loadRealLibrary()
                maybeRequestAllFiles() // 音频权限落定后再引导所有文件访问
            }
            false -> {
                audioDenied = true // UI 据此给出应用内「去授权」入口（不再只能靠用户自己去系统设置）
                audioDenialShown = true // 已弹过一次：再点「去授权」要改走系统设置页
                // 曲库不可用只是**当前**状态，不是终态：保留待恢复现场（用户随时可能去授权，
                // 授权回来后 loadRealLibrary 仍能把播放现场接上）。旧实现这里用 markLibrarySettled
                // 会连带 abandonPendingPlayback，把一次拒绝升级成「现场永久丢失」。
                state.settleLibraryKeepingPending()
            }
            null -> Unit // 本次只补申请了通知权限
        }
    }

    /**
     * 申请音频权限：能弹运行时弹窗就弹，弹不出来就跳应用详情页。
     *
     * 为什么需要这条分支：READ_MEDIA_AUDIO 被拒一次之后（尤其用户勾了「不再询问」），
     * `permissionLauncher.launch(...)` 会**立刻以 denied 返回、系统连弹窗都不显示** ——
     * 用户点了「去授权」却什么都没发生，比不给入口更糟。
     * 判据（两条缺一不可）：
     * - `shouldShowRequestPermissionRationale == true` → 系统还愿意再弹；
     * - 本次进程没问过（audioDenialShown == false）→ 首装首问，弹窗必然会出现。
     * 两者都为否 = 「不再询问」态 → 只能引导用户去系统设置页。
     */
    fun requestAudioPermission() {
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.READ_MEDIA_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            loadRealLibrary() // 已授权：直接重扫（用户可能刚从系统设置回来）
            return
        }
        val canAskAgain = (context as? Activity)?.let { act ->
            androidx.core.app.ActivityCompat.shouldShowRequestPermissionRationale(
                act, Manifest.permission.READ_MEDIA_AUDIO,
            )
        } ?: true
        if (canAskAgain || !audioDenialShown) {
            audioDenialShown = true
            permissionLauncher.launch(arrayOf(Manifest.permission.READ_MEDIA_AUDIO))
        } else {
            openAppDetailsPage()
        }
    }

    LaunchedEffect(Unit) {
        fun granted(permission: String) = ContextCompat.checkSelfPermission(
            context, permission,
        ) == PackageManager.PERMISSION_GRANTED
        val audioGranted = granted(Manifest.permission.READ_MEDIA_AUDIO)
        audioDenied = !audioGranted
        val toRequest = buildList {
            if (!audioGranted) add(Manifest.permission.READ_MEDIA_AUDIO)
            if (!granted(Manifest.permission.POST_NOTIFICATIONS)) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (audioGranted) {
            loadRealLibrary()
            // 等「已提示过」标记从磁盘读完再决定是否跳（否则会按初值跳过、或按默认值误跳）
            snapshotFlow { allFilesPromptRead }.first { it }
            maybeRequestAllFiles() // 已提示过则不再打扰：之后只从设置页手动进入
        }
        if (toRequest.isNotEmpty()) permissionLauncher.launch(toRequest.toTypedArray())
    }

    // 结果提示（成功/失败/未命中汇总）：全屏级顶部横幅的唯一数据源。
    // 声明点必须早于所有写入者（桌面歌词 / 文件 IO / 分享 / 恢复报告），
    // Kotlin 局部变量不支持前向引用——原先声明在文件 IO 段（约 650 行）时，
    // 桌面歌词段（约 570 行）赋值会报 Unresolved。
    var ioMessage by remember { mutableStateOf<String?>(null) }

    // 桌面歌词（P0-8）开关状态与接线。
    // 为什么不放进 AppState：AppStateSnapshot（SettingsStore 快照 v1→v10 迁移链）已封板，
    // 为一个布尔键升版+三路迁移的风险远超功能本身，改用 lyrics 包内独立 DataStore
    // （DesktopLyricsStore，注释里有完整取舍）。UI 持有状态，服务只做显示与进度驱动。
    var desktopLyricsOn by remember { mutableStateOf(false) }

    // 服务连接成功后的 MediaController 引用：开关命令的发送通道。
    // 为什么不走 PlayerEngine：那是 AppState 的封板接口，为显示层开关加方法不值得；
    // 直接握 controller.sendCustomCommand（一次性命令，无监听器，随 Activity 销毁释放，
    // 不与小部件的「短命 controller」模式冲突——App 主 controller 本就常驻）。
    var desktopLyricsController by remember { mutableStateOf<MediaController?>(null) }

    /** 桌面歌词开关命令下发（见 [desktopLyricsController] 的通道说明）。controller 未连上时静默丢弃 */
    fun sendDesktopLyricsCommand(controller: MediaController?, on: Boolean) {
        if (controller == null) return
        val args = Bundle().apply { putBoolean(MusicPlaybackService.KEY_DESKTOP_LYRICS_ON, on) }
        runCatching {
            controller.sendCustomCommand(
                SessionCommand(MusicPlaybackService.CMD_SET_DESKTOP_LYRICS, Bundle.EMPTY),
                args,
            )
        }.onFailure { Log.w("GlassApp", "send desktop lyrics command failed", it) }
    }

    // 启动：读回持久化的开关值（只改本地状态，不发命令——此刻服务多半还没连上，
    // 真正的重放在下面「controller 变为非空」的 effect 里做）
    LaunchedEffect(Unit) {
        desktopLyricsOn = DesktopLyricsStore.read(context)
    }
    // 服务连上（controller 就绪）后按最新状态重放一次：覆盖「进程被杀 → 系统续播拉起
    // 服务 → 用户再打开 App」的场景。服务端不持久化开关（悬浮窗是显示瞬态，自动弹窗
    // 反而打扰），所以每次 App 启动都要重放
    LaunchedEffect(desktopLyricsController) {
        val controller = desktopLyricsController ?: return@LaunchedEffect
        sendDesktopLyricsCommand(controller, desktopLyricsOn)
    }

    /**
     * 设置页「桌面歌词」开关回调（含权限引导）。
     *
     * 未授权悬浮窗权限时**不真正开开关**、跳系统授权页并给出横幅提示；
     * 用户授权后回到 App 再拨一次开关即可（授权页跳转不 kill 本进程，状态都在）。
     * 关闭方向无权限门槛，直接生效。
     */
    fun onDesktopLyricsChange(enable: Boolean) {
        if (!enable) {
            desktopLyricsOn = false
            sendDesktopLyricsCommand(desktopLyricsController, false)
            scope.launch { DesktopLyricsStore.write(context, false) }
            return
        }
        // 权限判定 + 授权页跳转都在这里（feature:settings 只拿哑回调，framework 细节不外泄）
        if (!Settings.canDrawOverlays(context)) {
            val opened = DesktopLyricsStore.openPermissionPage(context)
            ioMessage = if (opened) {
                "请在系统设置中允许「显示在其他应用上层」，返回后再开启桌面歌词"
            } else {
                "未能打开系统设置，请手动前往「设置 → 应用 → 音乐 → 显示在其他应用上层」"
            }
            return
        }
        desktopLyricsOn = true
        sendDesktopLyricsCommand(desktopLyricsController, true)
        scope.launch {
            val ok = DesktopLyricsStore.write(context, true)
            // 服务此刻可能还没连上（controller=null，命令被丢弃）：状态已落盘，
            // 服务连上后重放会补发。这里不做 UI 回滚——回滚会让「刚才明明开了」的
            // 用户看到开关自己弹回去，反而困惑。
            if (!ok) Log.w("GlassApp", "desktop lyrics switch persist failed")
        }
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
                    state.attachPlayer(MediaControllerEngine(controller, context.applicationContext))
                    // 桌面歌词（P0-8）：握住 controller 作为开关命令通道（随 Activity 销毁置空）
                    desktopLyricsController = controller
                } else {
                    state.reportEngineFailure("播放服务连接失败，无法播放本地音频")
                }
            },
            ContextCompat.getMainExecutor(context),
        )
        onDispose {
            // 非阻塞释放：内部等 future 就绪后再 release（future.get() 会卡主线程）
            desktopLyricsController = null
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

    // ── D2/D3 文件 IO：SAF 选择器（导出 M3U / 导入 M3U / 导出备份 / 恢复备份）──
    // 结果通过一次性回调交给发起方（设置页行 / 歌单详情菜单），避免把 SAF 逻辑散到各屏
    var pendingM3uExportPlaylist by remember { mutableStateOf<com.local.music.core.ui.components.DemoPlaylist?>(null) }
    // ioMessage 已上移到「桌面歌词」段之前：它是全屏级的横幅状态，被桌面歌词、文件 IO、
    // 分享、恢复报告等多处写入，声明点必须早于所有写入点（Kotlin 局部变量不支持前向引用）。

    val m3uExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("audio/x-mpegurl"),
    ) { uri ->
        val playlist = pendingM3uExportPlaylist
        pendingM3uExportPlaylist = null
        if (uri == null || playlist == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { M3uPlaylistIO.export(context, uri, playlist) }
            }
            ioMessage = result.fold(
                onSuccess = { "已导出「${playlist.title}」${it.written} 首" },
                onFailure = { "导出失败：${it.message ?: "无法写入"}" },
            )
        }
    }
    val m3uImportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { M3uPlaylistIO.import(context, uri, state.library) }
            }
            ioMessage = result.fold(
                onSuccess = { r ->
                    if (r.matchedIds.isEmpty()) {
                        "未匹配到任何曲目（共 ${r.unmatched.size} 行未命中）"
                    } else {
                        // 新建 user- 歌单承载，复用现有 addToPlaylist 单点提交
                        val name = r.title?.takeIf { it.isNotBlank() }
                            ?: M3uPlaylistIO.displayNameOf(context, uri)
                            ?: M3uPlaylistIO.fallbackNameOf(uri)
                        val pl = state.createPlaylist(name)
                        r.matchedIds.forEach { state.addToPlaylist(pl.id, it) }
                        buildString {
                            append("已导入「${pl.title}」${r.matchedIds.size} 首")
                            if (r.unmatched.isNotEmpty()) append("，${r.unmatched.size} 首未匹配")
                        }
                    }
                },
                onFailure = { "导入失败：${it.message ?: "无法读取"}" },
            )
        }
    }
    val backupExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val json = SettingsStore.encodeForBackup(state.snapshot())
                    context.contentResolver.openOutputStream(uri)?.use {
                        it.write(json.toByteArray(Charsets.UTF_8))
                    } ?: error("无法写入所选位置")
                }.isSuccess
            }
            ioMessage = if (ok) "备份已导出" else "备份导出失败"
        }
    }
    // ── 崩溃日志导出（04 报告 #3 的最后一环）──
    // CrashHandler 早就把未捕获异常写进了 filesDir/crash/，但此前**没有任何导出入口**：
    // 日志躺在用户手机里，开发者永远拿不到，等于「用户反馈闪退时你手上只有 0 条信息」
    // 这个问题根本没解决。这里复用备份导出的同一套 SAF CreateDocument 通道，
    // 把它做成「用户崩了 → 进设置 → 导出 → 发给你」的闭环。
    //
    // 为什么用 text/plain 而不是 application/json：内容是人类可读的崩溃栈合集，
    // 用 text/plain 能让用户在文件管理器里直接点开预览，不必先猜该用什么打开。
    val crashLogCount = remember { mutableStateOf(0) }
    // 每次进入设置页时重新数一遍：用户可能在别处崩过、也可能刚清空过
    LaunchedEffect(state.screen) {
        if (state.screen == GlassScreen.Settings) {
            crashLogCount.value = withContext(Dispatchers.IO) { CrashHandler.list(context).size }
        }
    }
    val crashExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    // bundle() 返回 null 表示「此刻没有日志」——可能是刚才被系统清理了。
                    // 这时不能写空文件骗用户说导出成功，必须如实提示
                    val text = CrashHandler.bundle(context) ?: error("没有可导出的崩溃日志")
                    context.contentResolver.openOutputStream(uri)?.use {
                        it.write(text.toByteArray(Charsets.UTF_8))
                    } ?: error("无法写入所选位置")
                    CrashHandler.list(context).size
                }
            }
            ok.onSuccess { count ->
                ioMessage = "已导出 $count 份崩溃日志"
            }.onFailure {
                ioMessage = "崩溃日志导出失败：${it.message ?: "无法写入"}"
            }
        }
    }
    val backupImportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val snapshot = withContext(Dispatchers.IO) {
                runCatching {
                    val raw = context.contentResolver.openInputStream(uri)?.use {
                        it.readBytes().toString(Charsets.UTF_8)
                    } ?: error("无法读取所选文件")
                    SettingsStore.decodeBackup(raw) ?: error("备份文件格式无法识别")
                }
            }
            snapshot.onSuccess {
                // 与启动恢复同一路径：设置/歌单/收藏立即生效，播放现场走 pending。
                // **必须走 restoreFromBackup 而不是 restore**：前者多打一个「本次是备份文件恢复」
                // 的标记，供 re-match 决定提示强度——冷启动读本地快照时出现失效引用是日常
                // （用户删过歌），不该弹横幅；而备份恢复出现失效项必须告诉用户。
                state.restoreFromBackup(it)
                // 运行时曲库已装载，loadLibrary 不会再调来消费 pending：就地应用恢复的播放现场
                // （否则界面不切到备份现场，scheduleSave 还会把 pending 原样回写）
                state.applyRestoredPlayback()
                state.scheduleSave()
                // 旧实现这里固定是「备份已恢复」——对「换了台手机、路径全变」的用户是句谎话：
                // 他的歌单可能是空的，而界面只说了「已恢复」。改为把 re-match 的统计
                // （复用/重匹配/未找到）原样呈现，见 SongRematch.RestoreReport 的 KDoc。
                ioMessage = restoreReportMessage(
                    state.restoreReport ?: RestoreReport(reused = 0, resolved = 0, missing = 0),
                )
                // 报告已上屏：消费掉，避免它在下一次无关的 I/O 提示里又冒出来
                state.consumeRestoreReport()
            }.onFailure {
                ioMessage = "恢复失败：${it.message ?: "文件无法解析"}"
            }
        }
    }

    // 返回键：模态面板优先关闭 → 详情回来源页 → 播放页逆向飞行收起 → 其余回曲库
    // （曲库页面板开着时也要拦截：否则返回直接穿透 finish 掉 Activity）
    BackHandler(enabled = state.screen != GlassScreen.Library || modalState.isOpen) {
        when {
            modalState.isOpen -> modalState.requestDismiss()
            state.screen == GlassScreen.Detail -> state.backFromDetail()
            // 播放页收起走 closePlayer（回 playerOrigin），而不是飞行算出的 entrySource：
            // 覆盖层模型下「来源页」由 AppState 单点持有，两者语义相同但前者永远正确
            state.screen == GlassScreen.Player -> flight.beginExit(state.currentSong) { state.closePlayer() }
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

    // ── 系统集成动作（P0-5 A：分享歌曲 / 设为铃声 / 分享歌单）──
    //
    // 这里把 :app 侧的实现（ShareActions，依赖 Intent/FileProvider/RingtoneManager）
    // 经 CompositionLocal 下发到 :core:ui 的操作面板。面板在 core:ui 不能反向依赖 :app，
    // 所以走 SystemActions 这层函数式桥（见该文件 KDoc 里「为什么不用形参」的说明）。
    //
    // remember 的空 key：三个 lambda 只捕获 context（Application 级，跨重组稳定）与
    // 会随每次调用重新读 state 的闭包；不捕获任何会变的 Compose 状态，
    // 所以整棵树的 LocalSystemActions 引用在进程内恒定 —— 这也是用
    // staticCompositionLocalOf 的前提（该值永不变，读取不记订阅）。
    val systemActions = remember {
        SystemActions(
            shareSong = { song -> ioMessage = ShareActions.shareSong(context, song).message },
            setRingtone = { song -> ioMessage = ShareActions.setAsRingtone(context, song).message },
            sharePlaylist = { title, tracks ->
                ioMessage = ShareActions.sharePlaylist(context, title, tracks).message
            },
        )
    }

    CompositionLocalProvider(
        LocalModalState provides modalState,
        LocalPlayerFlight provides flight,
        LocalSystemActions provides systemActions,
    ) {
        GlassTheme(
            palette = state.palette,
            isDark = state.themeMode == ThemeMode.Dark,
            glassIntensity = state.glassLevel,
        ) {
            val appHazeState = rememberHazeState()
            // 真曲库无封面占位走极光派生色；演示内容保留设计稿色（R8 #10/#11）
            val origin = if (state.realLibraryLoaded) LibraryOrigin.Live else LibraryOrigin.Demo
            CompositionLocalProvider(LocalHazeState provides appHazeState, LocalLibraryOrigin provides origin) {
                // 播放页是**盖在内容之上的覆盖层**，不是路由里的一屏。
                // 下层始终渲染「播放页的来源页」（playerOrigin），于是收起时无需页面切换动画。
                val playerOpen = state.screen == GlassScreen.Player
                val baseScreen = if (playerOpen) state.playerOrigin else state.screen
                // 下层随播放页升起而后退，营造纵深（0 = 正常，1 = 完全后退）
                //
                // **退出飞行期间提前回稳**（衔接修复①）：旧实现只看 playerOpen，而收起时
                // playerOpen 要等飞行跑到 t≤0.15 才翻 false —— 于是 500ms 飞行的最后
                // ~165ms（迷你条真身淡入窗口）里，真身仍被压在 0.962~0.989 的缩放里，
                // 替身却稳稳落在 unscaleMini 还原出的 1.0 几何上：双影错位最宽可达 ~14px，
                // 落地后底栏还要再花 ~245ms 才「长」到 1.0，观感就是落地那一下「顿一下再撑开」。
                // 把 phase != Exiting 并入条件：点收起即开始回稳，420ms 回稳与 500ms 飞行
                // 并行，进入 12% 落地窗口时缩放已到 ~0.998（错位 <1px），且「背景随收起前移」
                // 的纵深叙事也更连贯。
                // 中止路径安全：abort()/忙时中止都会把 phase 立刻置 Idle → 条件回退为
                // playerOpen（仍是 1f），不会出现「播放页还开着但背景已复原」的跳变。
                val recededForExit = flight.phase == PlayerFlight.Phase.Exiting
                val recede by animateFloatAsState(
                    targetValue = if (playerOpen && !recededForExit) 1f else 0f,
                    animationSpec = GlassMotion.tweenSheet(),
                    label = "playerRecede",
                )
                // 退出飞行的迷你条端点修正（见 PlayerFlight.backingScaleProvider）：播放页盖在
                // tab 页上时 Chrome 仍组合在 0.96 后退层里，其 trackFlight 注册的是**缩放后**
                // 的 root 坐标；飞行落点必须对齐接管瞬间（recede 已归零）的真身，据此在
                // beginExit 内把端点还原。Chrome 不在组合中（详情/搜索来源）时端点本就是
                // 未缩放的冻结值，返回 1 保持原样。
                flight.backingScaleProvider = {
                    if (state.playerOrigin in TAB_SCREENS) lerp(1f, RecedeScale, recede) else 1f
                }
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
                        // 飞行期指针闸门（P3-22 收窄）：只吞 **up**（Initial 隧道先行），down/move 全放行。
                        // 旧实现吞掉所有事件——等待期（最长 1.8s）全屏点不动也滑不动，体感像卡死。
                        // 为什么只吞 up 就能挡「点」：点击类手势（clickable/combinedClickable）必须
                        // 拿到未被消费的 up 才成立，up 在隧道层被吃掉后 tap 全部落空（按压涟漪闪
                        // 一下即复位，可接受；双击迷你条的第二击同样在此被拦）。
                        // 为什么**不能**连 down 一起吞：滑动检测（awaitFirstDown 默认要求未被消费的
                        // down）看到 consumed down 会跳过继续等，滑动手势永远起不来——那比挡不住
                        // 点击更糟。代价（设计取舍）：长按在超时瞬间即触发、不看 up，飞行期长按菜单
                        // 仍可能弹出；滑动手势最后一帧的 up 被吞会让该次 drag 以 cancel 收场（无
                        // 惯性 fling），仅发生在 0.5~2.3s 的飞行窗口内。
                        .then(
                            if (flight.isActive) Modifier.pointerInput(Unit) {
                                awaitPointerEventScope {
                                    while (true) {
                                        awaitPointerEvent(PointerEventPass.Initial).changes.forEach {
                                            if (it.changedToUp()) it.consume()
                                        }
                                    }
                                }
                            } else Modifier,
                        )
                        .trackFlight(FlightKeys.AppRoot, keepOnDispose = true),
                ) {
                    // 根层极光（视觉审计 D4 / 增-P2-B）：播放页完全盖住它之后停表。
                    //
                    // 改前：播放页覆盖层自带一份极光（见下方 playerOpen 分支），根层这份
                    // 被完全遮住却仍在逐帧重绘三条 infinite tween——纯浪费 GPU。
                    // 现在按 recede 门控：稳态全开/全关时单份极光在跑，420ms 过渡期两份
                    // 并存（softAlpha 交叉淡化需要下面那份真的在动，否则过渡看起来是静止的）。
                    //
                    // **recede 不能直接进组合期读**：它是 animateFloatAsState 的值，每帧变，
                    // 直接 `if (recede < 1f)` 会让整个 GlassApp（含曲库列表/Chrome/覆盖层）
                    // 在 420ms 内逐帧重组——这正是本文件压暗层注释里点名的错误写法。
                    // 用 derivedStateOf 把连续值折成**布尔**：只有「走满 1f」那一刻结果才变，
                    // 组合期只重组两次（开、关），过渡期零额外重组。
                    val rootAuroraVisible by remember { derivedStateOf { recede < 1f } }
                    if (rootAuroraVisible) {
                        AuroraBackground()
                    }

                    // 后退层：内容 + chrome 一起随播放页升起而后退（缩放由 recede 驱动）。
                    // hazeSource 的父层被缩放会让模糊采样产生约 4% 偏移，只在 420ms 过渡期存在
                    // 且作用于一个正被覆盖的模糊迷你条，视觉不可辨（方案「已知代价」③）。
                    Box(
                        Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                val s = lerp(1f, RecedeScale, recede)
                                scaleX = s
                                scaleY = s
                            },
                    ) {
                        // 内容区：三个主屏（曲库/歌单/设置）按 tab 顺序整屏左右平移，其余页淡入上移。
                        // 恒全高——Chrome 悬浮在根层后绘，显隐不再挤占布局（飞行两端/模态开合零 reflow）
                        //
                        // baseContentAlpha：飞行期让位给飞行替身。旧结构靠 AnimatedContent 换屏时的
                        // fadeOut 实现，覆盖层结构下不再换屏，必须显式补这条曲线（见 Flight 注释）。
                        Box(
                            Modifier
                                .fillMaxSize()
                                .hazeSource(appHazeState)
                                .graphicsLayer { alpha = flight.baseContentAlpha() },
                        ) {
                            if (!state.librarySettled) {
                                // 启动收敛中（曲库缓存/首扫未到）：不渲染任何演示内容。
                                // 视觉审计 B1/E2：这是「首帧用户看到的唯一文字」，用 Caption 档
                                // 而不是 12sp 裸值——它是状态说明，不该比正式内容更轻也不该更重
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Text(
                                        "正在整理曲库…",
                                        color = LocalGlassColors.current.textSecondary,
                                        style = GlassText.Caption,
                                    )
                                }
                            } else AnimatedContent(
                            // 播放页是覆盖层（见下方 playerOpen），不进这层 AnimatedContent。
                            // 下层始终渲染「播放页的来源页」：收起播放页时下层已经是正确的那一页，
                            // 无需任何页面切换动画 —— 播放页离场，来源页原地显现。
                            targetState = baseScreen,
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
                                    // 播放页由根层覆盖层渲染（见下方 AnimatedVisibility），
                                    // 不进内容层：它盖在内容之上，而不是与之平级的一屏。
                                    // baseScreen 永不等于 Player，此分支仅为穷尽性而存在。
                                    GlassScreen.Player -> Unit
                                    // 音频权限被拒时曲库页给出空态 + 应用内「去授权」入口
                                    // （否则拒绝后只剩一片空白，用户不知道该怎么救）
                                    GlassScreen.Library -> LibraryScreen(
                                        state,
                                        audioDenied = audioDenied,
                                        onRequestAudioPermission = ::requestAudioPermission,
                                    )
                                    GlassScreen.Playlist -> PlaylistScreen(state)
                                    GlassScreen.Detail -> PlaylistDetailScreen(
                                        state,
                                        // D2：导出 M3U（选位置 → 写文件）
                                        onExportM3u = { _, title, tracks ->
                                            pendingM3uExportPlaylist = com.local.music.core.ui.components.DemoPlaylist(
                                                id = state.detail.id,
                                                title = title,
                                                meta = "${tracks.size} 首",
                                                art = tracks.firstOrNull()?.art
                                                    ?: com.local.music.core.ui.components.ArtStyle.Night,
                                                tracks = tracks,
                                            )
                                            // 文件名经 sanitize：歌单名不受限（用户可输入 / 等非法字符），
                                            // 原实现直接拼 "$title.m3u8" 交给 SAF，部分 ROM 会拒绝或截断
                                            m3uExportLauncher.launch(M3uPlaylistIO.suggestedFileName(title))
                                        },
                                    )
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
                                                // 仅影响设置页「当前版本」文案（显示空串）；记一条日志便于排查
                                                android.util.Log.e("GlassApp", "read versionName failed", e)
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
                                        // 音频权限被拒时的应用内再引导（否则只能靠用户自己去系统设置）
                                        audioDenied = audioDenied,
                                        onRequestAudioPermission = ::requestAudioPermission,
                                        // P1-②：快照损坏 → 横幅可见 + 用户确认后才解除写冻结
                                        corruptSnapshot = corruptSnapshot,
                                        onRebuildFromCorrupt = {
                                            // 先清标志再放行：横幅据此即时消失，
                                            // 且顺序保证 rebuild 内部第一次 scheduleSave 已不受限
                                            corruptSnapshot = false
                                            state.rebuildFromCurrentState()
                                        },
                                        // D2 导入 M3U / D3 备份恢复
                                        onImportM3u = {
                                            m3uImportLauncher.launch(
                                                arrayOf("audio/x-mpegurl", "audio/mpegurl", "text/plain", "*/*"),
                                            )
                                        },
                                        onExportBackup = {
                                            backupExportLauncher.launch("music-backup.json")
                                        },
                                        onImportBackup = {
                                            backupImportLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
                                        },
                                        // 崩溃日志：有记录才让用户点导出（无记录时按钮点击会落到
                                        // 下面的「清空」分支，所以这里显式判空并给提示，避免打开一个
                                        // 空的选择器让用户白跑一趟）
                                        crashLogCount = crashLogCount.value,
                                        onExportCrashLogs = {
                                            if (crashLogCount.value <= 0) {
                                                ioMessage = "暂无崩溃记录可导出"
                                            } else {
                                                crashExportLauncher.launch("music-crash-logs.txt")
                                            }
                                        },
                                        onClearCrashLogs = {
                                            scope.launch {
                                                val removed = withContext(Dispatchers.IO) {
                                                    CrashHandler.clear(context)
                                                }
                                                crashLogCount.value = withContext(Dispatchers.IO) {
                                                    CrashHandler.list(context).size
                                                }
                                                ioMessage = if (removed > 0) {
                                                    "已删除 $removed 份崩溃日志"
                                                } else {
                                                    "没有可删除的崩溃日志"
                                                }
                                            }
                                        },
                                        // E2：自启动引导（仅国产省电 ROM 显示）
                                        autoStartGuideSubtitle = if (AutoStartGuide.shouldSuggest()) {
                                            AutoStartGuide.subtitle()
                                        } else {
                                            null
                                        },
                                        onAutoStartGuide = {
                                            if (!AutoStartGuide.open(context)) {
                                                ioMessage = "未能打开系统设置，请手动前往「设置 → 电池 → 应用启动管理」"
                                            }
                                        },
                                        // 桌面歌词（P0-8）：哑开关 + 权限引导都在 app 层做（见 onDesktopLyricsChange）
                                        desktopLyricsEnabled = desktopLyricsOn,
                                        onDesktopLyricsEnabledChange = ::onDesktopLyricsChange,
                                        onDesktopLyricsPermission = {
                                            // 副标题引导专用入口（开关回调内已含权限分支，这里保底给手动路径）
                                            if (!DesktopLyricsStore.openPermissionPage(context)) {
                                                ioMessage = "未能打开系统设置，请手动前往「设置 → 应用 → 音乐 → 显示在其他应用上层」"
                                            }
                                        },
                                    )
                                }
                            }
                        }
                        // 底部 Chrome（迷你条 + 导航）：悬浮于根层、与内容区同层后绘——
                        // 显隐只控组合不控布局，内容区恒全高，飞行两端/模态开合/tab 切换均无 reflow。
                        //
                        // 飞行期条件与旧结构等价：
                        // - **进入期不组合**：覆盖层里有「导航胶囊渐隐替身」按 entryNavSelected
                        //   原地绘制，Chrome 若还在就会在替身下再画一个真胶囊（双份重叠）。
                        //   旧结构靠 screen=Player 让这个条件自然失效，现在 baseScreen 恒为来源页。
                        // - **退出期组合**：miniSharedAlpha / navAlpha 只在最后 12% 淡入，
                        //   与替身接位；不组合的话迷你条与胶囊会在飞行结束时整块pop出来。
                        // 注意这里只读 phase（低频），不读 t.value —— 后者每帧变，会让整个
                        // GlassApp 每帧重组。淡入淡出交给 Chrome 内部的 graphicsLayer lambda。
                        if (baseScreen in TAB_SCREENS && state.librarySettled && !modalState.isOpen &&
                            flight.phase != PlayerFlight.Phase.Entering
                        ) {
                            GlassChrome(
                                song = state.currentSong,
                                playing = state.playing,
                                // 传取值函数而不是 Float：进度 10Hz 变，以值传入会让调用方
                                // （整个 GlassApp 组合）每 tick 重组一次。见 GlassChrome 的说明。
                                progressProvider = { state.progressFraction },
                                // 空曲库时迷你条不渲染播放/下一首（否则是可点但无反应的死按钮）
                                hasSongs = state.hasSongs,
                                navSelected = when (baseScreen) {
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
                                    val navSelected = when (baseScreen) {
                                        GlassScreen.Playlist -> 1
                                        GlassScreen.Settings -> 2
                                        else -> 0
                                    }
                                    // 忙时兜底直切（同 PlaylistDetailScreen）：飞行中不重入共享元素动画。
                                    // **必须先 abort()**：只改 screen 的话，正在跑的 exitJob 尾部仍会
                                    // navigate(目标屏)，用户落进播放页后被弹回曲库（约 100-225ms 后）。
                                    if (flight.isActive) {
                                        flight.abort()
                                        state.openPlayer()
                                    } else {
                                        flight.beginEntry(state.currentSong, navSelected, { state.openPlayer() }, baseScreen)
                                    }
                                },
                                onPlayPause = state::togglePlayPause,
                                onNext = { state.next() },
                                modifier = Modifier.align(Alignment.BottomCenter),
                            )
                        }

                        // 模态面板宿主已移到播放页覆盖层之后（见下方），此处不再挂载。
                    } // ← 后退层结束（内容 + Chrome 同受 recede 缩放）

                    // 压暗层：播放页升起时渐显，强化「下层后退」的纵深。
                    // **recede 只在绘制阶段读**（drawBehind 的 lambda）：它是动画值，
                    // 若在组合期读（如 if (recede > 0f)），420ms 过渡期每一帧都会重组整个
                    // GlassApp（含曲库列表/Chrome/覆盖层），纯属浪费；这里只失效绘制。
                    Box(
                        Modifier
                            .fillMaxSize()
                            .drawBehind {
                                val a = 0.28f * recede
                                if (a > 0f) drawRect(Color.Black, alpha = a)
                            },
                    )

                    // ── 播放页覆盖层 ──
                    // 盖在内容之上，而不是与之平级的一屏。自带极光背景：否则整屏透明，
                    // 会透视到下层曲库（下层此刻仍在渲染来源页）。
                    //
                    // 与旧实现的关键差异：旧结构里播放页是 AnimatedContent 的一屏，
                    // 进出必然伴随「换屏」——下层页面被销毁重建，于是收起时下层要重排、
                    // 返回目标要靠 entrySource 猜。现在下层恒为 playerOrigin 且不被销毁，
                    // 收起即「播放页离场、来源页原地显现」，无换屏、无重排。
                    AnimatedVisibility(
                        visible = playerOpen,
                        modifier = Modifier.fillMaxSize(),
                        // 进入/退出都走整屏升降（方案语义）；共享元素飞行可用时由飞行接管，
                        // 此处只做「无飞行兜底」——两条转场语言不能同时对同一动作生效
                        enter = if (flight.isActive) {
                            EnterTransition.None
                        } else {
                            fadeIn(GlassMotion.tweenSheet()) +
                                slideInVertically(GlassMotion.tweenSheet()) { it }
                        },
                        exit = if (flight.isActive) {
                            ExitTransition.None
                        } else {
                            fadeOut(GlassMotion.tweenPage()) +
                                slideOutVertically(GlassMotion.tweenPage()) { it }
                        },
                    ) {
                        Box(Modifier.fillMaxSize()) {
                            // 覆盖层自带极光（否则整屏透明会透视到下层曲库），
                            // 但**透明度必须跟播放页内容的揭示曲线同步**（softAlpha）：
                            // 飞行期播放页是「先不可见、随 t 渐显」的，极光若一上来就不透明，
                            // 点击瞬间背景会从根层极光硬换成覆盖层极光（两份漂移相位不同）→ 闪一下。
                            // 跟着 softAlpha：起飞时透出根层极光（与旧结构连续），
                            // 随播放页内容一起显影；收起时反向淡出，下层曲库重新露出来。
                            Box(
                                Modifier
                                    .fillMaxSize()
                                    .graphicsLayer { alpha = flight.softAlpha() },
                            ) {
                                AuroraBackground()
                            }
                            PlayerScreen(state)
                        }
                    }

                    // ── 模态面板宿主：全 App GlassSheet 的唯一挂载点 ──
                    // **必须挂在播放页覆盖层之后**（z 序最高）：播放页的「播放选项」
                    // 与「歌曲操作」也经 modal.show 打开，宿主若在覆盖层之下，
                    // 面板会被整屏播放页盖住 —— 点了没反应。
                    // 同时它不在后退层内，面板玻璃不受 recede 缩放影响（本就该全屏正立）。
                    // 内容区 hazeSource 的兄弟层：面板玻璃不在 app 级源子树内（铁律①），
                    // 消费的模糊源统一为 app 级（appHazeState）。
                    // 飞行期不渲染：结构性保证「飞行期无面板」（旧设计=屏内 sheet 随屏卸载）
                    if (!flight.isActive) {
                        ModalSheetHost(modalState)
                    }

                    // 顶部提示区：IO 结果（导出/导入/备份）与播放错误共用同一个纵向栈。
                    //
                    // **必须同栈**：旧实现两条提示各自 `align(TopCenter) + padding(top = 8.dp)`，
                    // 是同一个父 Box 里的两个兄弟 → 同时出现时后绘的那条直接盖住前一条，
                    // 用户只看到一条、另一条 4s 后无声消失（例如导出失败期间恰好播放出错）。
                    // 放进 Column 后两条按顺序堆叠，谁也盖不住谁；单条时视觉与原来完全一致。
                    Column(
                        Modifier
                            .align(Alignment.TopCenter)
                            .statusBarsPadding()
                            .padding(top = S.Gap8.dp)
                            .fillMaxWidth(),
                    ) {
                        // 文件 IO 结果提示（D2/D3：导出/导入/备份恢复）：4s 自动消退。
                        // 视觉审计 E2：按消息内容判档——失败类给 Error 色条，
                        // 成功类保持中性。判据用消息前缀（横幅的唯一数据源就是这一条 String，
                        // 为它引入结构化状态会牵动 8 个写入点；而中文失败文案在本工程是
                        // 统一的「X 失败：原因」句式，前缀判据与实际语义一致）。
                        ioMessage?.let { message ->
                            LaunchedEffect(message) {
                                delay(4000)
                                ioMessage = null
                            }
                            TopBanner(
                                message,
                                maxLines = 3,
                                onClick = { ioMessage = null },
                                tone = if (message.contains("失败")) BannerTone.Error else BannerTone.Neutral,
                            )
                        }
                        // 备份恢复的 re-match 报告（P0-5 B）：冷启动/后台恢复也可能产生
                        // （备份文件恢复那条路径已经就地消费掉了自己的报告，走到这里的一般
                        // 是「曲库稍后才装载完」的时序）。**停留时间比普通 IO 提示长**：
                        // 「已恢复 37 首，12 首未找到」是用户需要读完并据此行动的信息，
                        // 4s 一闪而过等于没提示。点击可提前关闭。
                        state.restoreReport?.let { report ->
                            if (ioMessage != null) Spacer(Modifier.height(6.dp))
                            LaunchedEffect(report) {
                                delay(9000)
                                state.consumeRestoreReport()
                            }
                            // 恢复报告里有「N 首未找到」这类坏消息时给 Error 档——
                            // 但注意它是**信息性**的（「12 首未找到」不等于操作失败，
                            // 是换设备/删歌后的正常结果），所以只有措辞里带「失败」才升档，
                            // 未找到仍保持中性。这就是为什么判据不是「有坏消息」而是「操作失败」。
                            val text = restoreReportMessage(report)
                            TopBanner(
                                text,
                                maxLines = 3,
                                onClick = { state.consumeRestoreReport() },
                                tone = if (text.contains("失败")) BannerTone.Error else BannerTone.Neutral,
                            )
                        }
                        // 播放错误提示条（4s 自动消退，点击关闭）
                        state.playbackError?.let { message ->
                            LaunchedEffect(message) {
                                delay(4000)
                                state.dismissPlaybackError()
                            }
                            if (ioMessage != null) Spacer(Modifier.height(6.dp)) // 两条并存才需要间距
                            TopBanner(
                                message,
                                maxLines = 2,
                                onClick = { state.dismissPlaybackError() },
                                // 这条通道只有错误语义（引擎失败/读取媒体库失败），恒 Error
                                tone = BannerTone.Error,
                            )
                        }
                    }

                    // 共享元素飞行覆盖层：6 个替身（玻璃/封面/歌名/作者/播放/下一首）+ 迷你进度线，
                    // 纯绘制不挡触摸。进度传取值函数（10Hz 变，绘制期读，不牵动重组）
                    PlayerFlightOverlay(flight, playing = state.playing, progressProvider = { state.progressFraction })
                }
            }
        }
    }
}

/** 底部 Chrome 承载的三个主屏（导航顺序：曲库 0 / 歌单 1 / 设置 2） */
private val TAB_SCREENS = setOf(GlassScreen.Library, GlassScreen.Playlist, GlassScreen.Settings)

/** 播放页升起时下层后退缩放：Chrome 在播放页打开期间也被缩放（退出飞行端点还原用） */
private const val RecedeScale = 0.96f

/**
 * 顶部单条玻璃提示（IO 结果 / 播放错误 / 备份恢复报告共用）：样式单点定义，避免多条各写
 * 一份后漂移。
 *
 * **状态语义（视觉审计 E2）**：改前成功/失败/错误共用同一套 Hi 玻璃 + 13sp 白字，
 * 「备份已导出」和「恢复失败：文件无法解析」长得一模一样——错误信息混在成功提示里，
 * 用户扫一眼根本不会停下来读。现在加 [tone] 分两档：
 * - [Tone.Neutral]：确认类（已导出/已删除/恢复报告）。保持原观感。
 * - [Tone.Error]：失败类。左缘加一条 3dp 的 destructive 色条。
 *
 * 为什么用「左缘色条」而不是整块染红/加红色边框：整块染红会把玻璃变成告警条（廉价感，
 * 且深色模式红底上白字对比反而更差）；红色边框则与玻璃描边打架、四个角都在喊。
 * 左缘一条 3dp 竖条是**最低成本的语义标记**——它不改变底色的通透，只在视觉扫描路径
 * （左对齐阅读的起点）上给一个「这条不一样」的信号，成熟系统通知条普遍这么做。
 *
 * 读屏（无障碍 P0-4）：这个横幅是**可点的**（点击关闭），但 GlassSurface 内部只走
 * combinedClickable，不带 role——读屏会把 [message] 当成一整段文字念完就过去，
 * 盲用户既不知道它可点、也不知道点了会怎样。补 role = Button 与 onClick 的标签
 * （「关闭提示」）后，读屏念「<消息内容>，按钮，双击即可关闭提示」。
 *
 * 为什么不把「关闭提示」写进 contentDescription：那会**覆盖** message，把横幅真正的
 * 内容（播放错误原因、备份恢复了几首）吞掉——而内容才是这条横幅的全部价值。
 * 用 onClick(label=...) 的方式追加动作名，才能既保留内容又表达可点性。
 */
private enum class BannerTone { Neutral, Error }

@Composable
private fun TopBanner(
    message: String,
    maxLines: Int,
    onClick: () -> Unit,
    tone: BannerTone = BannerTone.Neutral,
) {
    val glass = LocalGlassColors.current
    GlassSurface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = S.Gap20.dp)
            .semantics {
                this.role = Role.Button
                // 动作名而不是内容替换：见函数 KDoc
                onClick(label = "关闭提示") {
                    onClick()
                    true
                }
            },
        level = GlassLevel.Hi,
        shape = RoundedCornerShape(R.Card14.dp),
        onClick = onClick,
    ) {
        // 左缘语义色条：只在 Error 档画。
        //
        // **必须用 matchParentSize() 而不是 fillMaxHeight()**：GlassSurface 是
        // `Box(modifier, content)`，高度由子元素撑开（wrap content）。fillMaxHeight 会去
        // 填充父约束的 maxHeight（可能是整屏高），把横幅撑成一大块；而 matchParentSize
        // 的参与者在测量阶段被忽略、最后按父（=Text 撑出的）实际尺寸放置——这正是要的。
        // 画完后由 GlassSurface 的 clip(shape) 裁掉左端的圆角，色条自然贴合胶囊轮廓。
        if (tone == BannerTone.Error) {
            Box(
                Modifier
                    .matchParentSize()
                    .drawBehind {
                        drawRect(
                            color = glass.destructive,
                            size = androidx.compose.ui.geometry.Size(3.dp.toPx(), size.height),
                        )
                    },
            )
        }
        Text(
            message,
            color = glass.textPrimary,
            style = GlassText.Body,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
            // 胶囊内光学 padding，豁免 S 刻度（同「胶囊内 padding 不入刻度」的既有规则）：
            // 横向 14 要给 Error 档左缘那条 3dp 色条留出视觉间距，纵向 10 是单行/多行
            // 都能接受的居中量。两者都是**这块胶囊内部**的对齐参数，换成刻度值反而会
            // 让 3dp 色条贴住文字、或让两行文案上下发挤。
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
        )
    }
}
