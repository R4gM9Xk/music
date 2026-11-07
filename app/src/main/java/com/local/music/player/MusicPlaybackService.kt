package com.local.music.player

import android.content.Intent
import android.app.PendingIntent
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.media.audiofx.Virtualizer
import android.os.Bundle
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ShuffleOrder
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import com.local.music.MainActivity
import com.local.music.core.ui.state.AudioFxSettings
import com.local.music.core.ui.state.SongSpec
import com.local.music.lyrics.DesktopLyricsController
import com.local.music.widget.MusicWidgetRefresher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 前台播放服务：ExoPlayer + MediaSession 在此托管。
 * - 播放开始即自动进入前台 + 媒体通知（media3 DefaultMediaNotificationProvider）
 * - 锁屏媒体控制 / ColorOS 流体云胶囊读取的就是这个系统媒体会话
 * - 整条播放队列在服务端播放器上：退后台、锁屏、界面销毁后完播仍自动切换
 * - 音效链：Equalizer / LoudnessEnhancer / BassBoost / Virtualizer 全部挂在播放器音频会话上，
 *   预设与开关经自定义命令下发（详见 [applyAudioFxSettings] 与 [ensureAudioFx]）
 * - 定时关闭：分钟档协程到点暂停；播完当前首/队列由播放器监听承接（服务端权威）
 */
class MusicPlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private var equalizer: Equalizer? = null
    private var eqSessionId = 0 // 均衡器绑定的音频会话（设备切换会变化，需重建）
    private var lastPreset = "Off" // 最近一次请求的预设（音频会话就绪后重放）

    // ── 音效链（响度归一 / 低音增强 / 虚拟环绕）────────────────────────
    // 每个效果一个字段 + 共用 [audioFxSessionId]。**必须记住上一次下发的完整设置**：
    // 音频会话在切歌/切设备时可能重建，重建后新实例是「出厂默认」状态，
    // 不重放就等于用户上次开的低音增强在一次切歌后悄悄失效（他还以为是自己耳机的问题）。
    private var loudnessEnhancer: LoudnessEnhancer? = null
    private var bassBoost: BassBoost? = null
    private var virtualizer: Virtualizer? = null

    /** 音效链绑定的音频会话 id（与 [eqSessionId] 区分：EQ 有自己的重建路径，
     *  两者共用同一个会话但生命周期独立，任一失败不牵连另一条链） */
    private var audioFxSessionId = 0

    /** 最近一次下发的音效设置（会话重建后原样重放；**默认全关** = 不改变用户听到的声音） */
    private var audioFxSettings = AudioFxSettings()

    /**
     * 当前曲目的响度增益（毫贝）。null = 这首没有可用标签 → **不做任何补偿**。
     *
     * 为什么区分 null 与 0：0 表示「标签说这首不需要调整」，null 表示「压根没标签」。
     * 两者对 LoudnessEnhancer 的处理虽然都是「关掉」，但日志与后续诊断要能分清，
     * 而且将来接「无标签时的保守归一」时需要这个区分。
     */
    private var currentTrackGainMb: Int? = null

    /** 读取响度标签的串行 IO 线程：切歌是主线程回调，标签解析要读文件头几十 KB~几 MB。
     *
     *  **为什么串行且要带代次**：用户连续快速切歌时，读标签的任务会排队，
     *  等第 1 首的解析结果回来时可能已经在放第 3 首了 —— 直接把旧曲的增益套到新曲上，
     *  表现为「切歌后音量莫名变大/变小」。用 [trackGainToken] 让过期结果自己作废。 */
    private val tagReadExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "replaygain-reader").apply { isDaemon = true }
    }
    private var trackGainToken = 0

    // ── 桌面歌词（P0-8）────────────────────────────────────────────────
    // 悬浮窗控制器的唯一持有方。**为什么放在服务而不是 Activity**：悬浮窗要跟随
    // 播放进度滚动，而播放进度的权威在服务端播放器上——Activity 退后台/销毁后
    // UI 心跳就停了，悬浮窗会冻在最后一行；服务进程存活即更新，链路最短。
    // 构造即注入两个数据源（当前曲路径 / 播放进度），见 onCreate。
    // 所有 addView/removeView 异常已在控制器内部 runCatching 兜住（部分 ROM 拒绝悬浮窗），
    // 这里绝不把异常接到播放器回调链上。
    private lateinit var desktopLyrics: DesktopLyricsController

    // 定时关闭（不持久化）：分钟档协程 + 播完档旗标；重复下发新定时先取消旧协程/清旗标
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    // 续播读盘线程（见 onPlaybackResumption）：单线程即可——同一进程内恢复请求是低频且串行语义，
    // 不需要并发；随服务实例创建/销毁（onDestroy 里 shutdown），不跨实例共享，
    // 避免 companion 静态执行器被首个实例关掉后，第二个实例再也用不了
    private val queueReadExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "queue-state-reader").apply { isDaemon = true }
    }
    private var sleepJob: Job? = null
    private var stopAfterCurrent = false // -1 档：切到下一首的瞬间暂停
    private var stopAtQueueEnd = false // -2 档：队列自然播完（ENDED）暂停

    // ── 睡眠定时渐弱的状态位（只有分钟档用；见 applySleepTimer 的档位说明） ──
    // 渐弱是否正在进行：决定「用户手动暂停」要不要中止渐弱并复位音量
    private var fadeInProgress = false
    // 用户手动暂停导致渐弱提前收手（不撤销定时，见 onIsPlayingChanged）
    private var fadeAborted = false
    // 是否是我们自己（定时到点）在暂停：用来区分「用户手动暂停」与「渐弱收尾的自动暂停」。
    // 没有这个标记，渐弱自己调的 pause() 会被 onIsPlayingChanged 当成用户暂停处理
    private var pausingForSleep = false

    override fun onCreate() {
        super.onCreate()
        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true) // 拔耳机自动暂停
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
        mediaSession = MediaSession.Builder(this, player)
            // **必须设置 sessionActivity**：media3 的 sessionActivity 默认 null，而
            // DefaultMediaNotificationProvider 会无条件 `setContentIntent(getSessionActivity())`
            // → 通知能显示但**没有 contentIntent**，于是下拉通知栏点媒体卡片、锁屏点封面、
            // ColorOS 流体云点胶囊都不会打开 App，只有播放控制按钮可用。
            .setSessionActivity(
                PendingIntent.getActivity(
                    this, 0,
                    Intent(this, MainActivity::class.java)
                        .setAction(Intent.ACTION_MAIN)
                        .addCategory(Intent.CATEGORY_APP_MUSIC)
                        .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            .setCallback(
                object : MediaSession.Callback {
                    // 自定义命令必须在此声明，否则控制器 sendCustomCommand 被拒（EQ 预设 / shuffle 插入修正 / 定时关闭共用此通道）
                    override fun onConnect(
                        session: MediaSession,
                        controller: MediaSession.ControllerInfo,
                    ): MediaSession.ConnectionResult {
                        val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                            .add(SessionCommand(CMD_SET_EQ, Bundle.EMPTY))
                            .add(SessionCommand(CMD_INSERT_SHUFFLE_FIX, Bundle.EMPTY))
                            .add(SessionCommand(CMD_SET_SLEEP_TIMER, Bundle.EMPTY))
                            .add(SessionCommand(CMD_SET_AUDIO_FX, Bundle.EMPTY))
                            .add(SessionCommand(CMD_SET_DESKTOP_LYRICS, Bundle.EMPTY))
                            .build()
                        return MediaSession.ConnectionResult.accept(
                            sessionCommands,
                            MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS,
                        )
                    }

                    /**
                     * 进程被杀后系统媒体入口（通知栏恢复 / 蓝牙重连 / 车机）拉起服务时的续播回调。
                     *
                     * 此时没有 Activity，AppState 不存在——现场只能从 [QueueStateStore] 读
                     * （AppState.scheduleSave 时同步写的 queue_state.json）。
                     * 返回的 MediaItemsWithStartPosition 让播放器直接装载队列并定位到上次进度。
                     * 无现场（首装 / 用户清空过队列）返回空列表：系统按默认行为处理，不强行播放。
                     *
                     * **必须异步返回**：本回调在 media3 的主 looper 上执行，而 QueueStateStore.read
                     * 是 readText + 整份 JSON 解析（整库队列数百 KB，低端机可达数百毫秒）。
                     * 在这里同步读会把主线程连同正在启动的播放器初始化一起卡住（黑屏/ANR 风险）。
                     * 因此先在回调里立刻返回一个 SettableFuture，把读盘丢到 [queueReadExecutor]，
                     * 读完再 set —— media3 只是把这个 Future 当挂起中的结果，不会阻塞它自己的 looper。
                     *
                     * 失败兜底（保持既有语义）：读不到 / 解析异常 / 空队列一律 set 合法空结果，
                     * 绝不 setException —— 让系统走默认行为，比抛出去导致恢复流程崩掉安全。
                     */
                    override fun onPlaybackResumption(
                        mediaSession: MediaSession,
                        controller: MediaSession.ControllerInfo,
                    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
                        val future = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
                        // 整个后台执行体都兜住：任何异常都必须落成空结果，否则 Future 永不完成，
                        // 系统侧会一直挂着等一个不会来的恢复结果
                        val readTask = Runnable {
                            val result = runCatching { buildResumptionState() }
                                .getOrElse { e ->
                                    Log.e(TAG, "onPlaybackResumption read failed", e)
                                    null
                                }
                                ?: MediaSession.MediaItemsWithStartPosition(emptyList(), 0, C.TIME_UNSET)
                            future.set(result)
                        }
                        try {
                            queueReadExecutor.execute(readTask)
                        } catch (e: java.util.concurrent.RejectedExecutionException) {
                            // 服务正在销毁（执行器已 shutdown）时仍可能收到回调：
                            // 同线程直接算掉，保证 Future 一定有结果，不把系统侧挂死
                            Log.e(TAG, "queueReadExecutor rejected, reading inline", e)
                            readTask.run()
                        }
                        return future
                    }

                    override fun onCustomCommand(
                        session: MediaSession,
                        controller: MediaSession.ControllerInfo,
                        customCommand: SessionCommand,
                        args: Bundle,
                    ): ListenableFuture<SessionResult> {
                        // 自定义命令仅接受本应用（服务保持 exported 供系统/锁屏会话使用）
                        if (controller.packageName != packageName) {
                            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_PERMISSION_DENIED))
                        }
                        if (customCommand.customAction == CMD_SET_EQ) {
                            applyEqPreset(args.getString(KEY_PRESET))
                        } else if (customCommand.customAction == CMD_SET_AUDIO_FX) {
                            val next = AudioFxSettings(
                                loudnessNorm = args.getBoolean(KEY_FX_LOUDNESS, false),
                                bassBoost = args.getBoolean(KEY_FX_BASS, false),
                                bassLevel = args.getInt(KEY_FX_BASS_LEVEL, AudioFxSettings.DEFAULT_LEVEL),
                                virtualizer = args.getBoolean(KEY_FX_VIRTUAL, false),
                                virtualLevel = args.getInt(KEY_FX_VIRTUAL_LEVEL, AudioFxSettings.DEFAULT_LEVEL),
                            )
                            applyAudioFxSettings(next)
                        } else if (customCommand.customAction == CMD_SET_SLEEP_TIMER) {
                            applySleepTimer(args.getInt(KEY_SLEEP_MODE, 0))
                        } else if (customCommand.customAction == CMD_SET_DESKTOP_LYRICS) {
                            // 桌面歌词开关（P0-8）：show/hide 内部自兜全部异常，
                            // 失败（权限/ROM 拒绝）只影响歌词窗本身，不影响播放链路。
                            // **为什么只在这里做不进状态重放**：见 CMD_SET_DESKTOP_LYRICS 常量处注释
                            if (args.getBoolean(KEY_DESKTOP_LYRICS_ON, false)) {
                                desktopLyrics.show()
                            } else {
                                desktopLyrics.hide()
                            }
                        } else if (customCommand.customAction == CMD_INSERT_SHUFFLE_FIX) {
                            @Suppress("DEPRECATION", "UNCHECKED_CAST")
                            val specs = args.getSerializable(KEY_SPECS) as? ArrayList<SongSpec>
                            if (specs != null) {
                                insertWithShuffleFix(
                                    args.getInt(KEY_INDEX, 0),
                                    specs.map { it.toMediaItem() }.withArtwork(specs, 0),
                                    args.getString(KEY_PIN_MODE, PIN_NEXT) == PIN_LAST,
                                )
                            }
                        } else {
                            // 服务保持 exported：未知命令返回错误码而非 SUCCESS，暴露真实命令边界
                            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_BAD_VALUE))
                        }
                        return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                    }
                },
            )
            .build()
        // 音频会话 id 在解码器首次初始化（首个 READY）才有值：到点补挂均衡器并重放最后预设；
        // 定时关闭「播完档」的暂停触发也在这个监听（播放器事件只在服务端可见）
        player.addListener(
            object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_READY) {
                        runCatching { ensureEqualizer(player.audioSessionId) }
                        applyEqPreset(lastPreset)
                        // 音效链与 EQ 同一时机补挂（音频会话首个 READY 才有真 id）
                        runCatching { ensureAudioFx(player.audioSessionId) }
                        applyAudioFxSettings(audioFxSettings)
                    } else if (playbackState == Player.STATE_ENDED && stopAtQueueEnd) {
                        // -2 档「播完整个队列」：自然播完（ENDED）即暂停并清旗标。
                        // repeatAll 开着时播放器回绕、永不 ENDED，该档等价不触发（符合语义，不管）
                        stopAtQueueEnd = false
                        player.pause()
                    }
                }

                /**
                 * 音频会话变更（最常见的触发源：**切蓝牙耳机 / 插拔 USB DAC / 系统重建音频通路**）。
                 *
                 * 为什么必须单独处理：`player.audioSessionId` 在内核换掉输出设备时不保证保持不变。
                 * 会话一变，挂在旧会话上的四个效果实例就全部沉默——**听感上它们「消失」了**，
                 * 而 UI 上开关还亮着，用户只会觉得「这 App 的音效时灵时不灵」。
                 * 这里重建两条链并重放最后一次的设置（[audioFxSettings] / [lastPreset] 就是为此存的）。
                 *
                 * 全程 runCatching：A2DP / USB DAC 路由上构造 audiofx 常常直接抛
                 * UnsupportedOperationException 或 IllegalArgumentException，绝不能让它冒到
                 * 播放器回调里（那会把一次设备切换变成一次崩溃）。
                 */
                override fun onAudioSessionIdChanged(audioSessionId: Int) {
                    if (audioSessionId <= 0) return
                    runCatching { ensureEqualizer(audioSessionId) }
                    applyEqPreset(lastPreset)
                    runCatching { ensureAudioFx(audioSessionId) }
                    applyAudioFxSettings(audioFxSettings)
                }

                /**
                 * 切歌：刷新当前曲的响度增益。
                 *
                 * **必须在这里读标签而不是在装载队列时批量读**：千首队列批量解析 = 启动时
                 * 扫完整库的文件头，冷启动直接卡死（本工程已经因为「扫描时预解码全库封面」
                 * 踩过一次同型坑，见 MediaLibrary 的注释）。按需在读、且只在开关打开时才读。
                 */
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    // 小部件刷新：切歌了，桌面上的标题/封面/进度必须跟着换。
                    // 放这里而不是 onIsPlayingChanged 是因为「切歌」才是内容变化的真正时刻
                    // —— 播放态可能不变（一直在播），但歌已经换了。
                    // refresher 内部自带节流 + try/catch + 幂等，重复调用无副作用；
                    // 它有实例才会真的动，用户没放小部件时是一次极轻的空判。
                    MusicWidgetRefresher.onPlaybackStateChanged(this@MusicPlaybackService)

                    // 桌面歌词切歌重读（P0-8）：与 小部件刷新 同一时刻（内容变化才是刷新时机）。
                    // REPEAT 重入时路径未变，控制器内部按 loadedPath 比对幂等；重读失败显示「暂无歌词」
                    desktopLyrics.onTrackChanged(
                        mediaItem?.mediaId?.let { id ->
                            if (id.startsWith(FILE_ID_PREFIX)) id.substring(FILE_ID_PREFIX.length) else null
                        },
                    )

                    // 响度标签按需读取：只在「响度归一」开着时才付出这个 IO 代价
                    if (audioFxSettings.loudnessNorm) scheduleTrackGainRead(mediaItem)

                    // -1 档「播完当前首」：只认**自然播完**（AUTO）这一次切换。
                    // 为什么必须判 reason：用户在播放中手动点下一首 / 拖动进度到下一区段（SEEK）、
                    // 播放队列被替换（PLAYLIST_CHANGED）、单曲循环重入（REPEAT）都会触发同一个回调。
                    // 旧实现不判 reason，于是「设了播完当前首 → 用户主动切歌」也会被暂停——
                    // 用户明确要听下一首却被拦下，是纯粹的误伤。
                    // 非 AUTO 的处理：**仍清旗标、但不暂停**。清掉是必须的——旗标没有回执，
                    // 留着就是悬空的「下一次自动切歌时莫名暂停」，那是比丢失定时更糟的体验；
                    // UI 侧手动切歌本就会自行撤销定时（AppState.setSleepTimer(0)），这里只是兜住
                    // 「UI 没来得及下发撤销」的窗口期，语义上一致。
                    if (stopAfterCurrent) {
                        stopAfterCurrent = false
                        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) player.pause()
                    }
                }

                /**
                 * 渐弱期间用户手动暂停：立即中止渐弱并复位音量（不停定时）。
                 *
                 * 为什么用 [fadeAborted] 旗标而不是 cancel 掉 sleepJob：定时器的语义是
                 * 「到点暂停」。用户此刻手动暂停只说明「现在不需要继续淡出」，**不等于撤销定时**——
                 * cancel 会把整个协程连定时期限一起丢掉，于是用户暂停 5 秒再继续听，
                 * 到点就不再停了，而 UI 上定时还显示着（AppState 的 deadline 仍在走）。
                 * 改成置旗标后：淡出循环提前退出、音量复位，协程继续走到 deadline 执行 pause()，
                 * 此时若用户已恢复播放就正常停，若仍暂停则 pause() 是无副作用的空操作。
                 *
                 * 必须判 [pausingForSleep]：渐弱自己到点调的 pause() 也会进这个回调，
                 * 不区分就会把「刚淡出完的那一次」当成用户暂停。
                 */
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (!isPlaying && fadeInProgress && !pausingForSleep) {
                        fadeAborted = true
                    }
                    // 小部件的播放/暂停图标必须跟着变；暂停/继续是用户在小部件上
                    // 点一下就期待立刻看到反馈的动作，不能等到下次切歌才更新
                    MusicWidgetRefresher.onPlaybackStateChanged(this@MusicPlaybackService)
                }
            },
        )
        // 均衡器挂播放器音频会话；音频会话未就绪时由首次 EQ 命令/首个 READY 延迟创建
        runCatching { ensureEqualizer(player.audioSessionId) }
        // 音效链同理（会话未就绪时 ensureAudioFx 直接返回，等首个 READY 再来）
        runCatching { ensureAudioFx(player.audioSessionId) }

        // 桌面歌词控制器（P0-8）：构造 + 注入两个数据源。不在这里自动 show——
        // 开关状态在 UI 侧持久化，服务重建后由 GlassApp 重连时重放（见 CMD_SET_DESKTOP_LYRICS 注释）
        desktopLyrics = DesktopLyricsController(applicationContext).apply {
            // 当前曲音频路径：mediaId 约定 = "f" + 绝对路径（MediaLibrary 扫描时写入，
            // 见 MediaLibrary.load 的 DemoSong 构造）。removePrefix 一次解出；
            // 演示曲目 / 备份残留的非 "f" 前缀 id 解不出路径，返回 null（显示「暂无歌词」）
            currentTrackPathProvider = {
                val id = player.currentMediaItem?.mediaId
                if (id?.startsWith(FILE_ID_PREFIX) == true) id.substring(FILE_ID_PREFIX.length) else null
            }
            // 播放进度：播放器暂停时 currentPosition 也保持值（恒可用）；
            // STATE_IDLE 时是 0，行同步自然落在首行，无需特判
            positionMsProvider = { player.currentPosition }
        }
    }

    /**
     * 组装续播现场（在 [queueReadExecutor] 的后台线程执行，见 onPlaybackResumption）。
     * 无现场返回 null，由调用方落成空结果——空列表即「按系统默认处理」，不强行播放。
     */
    private fun buildResumptionState(): MediaSession.MediaItemsWithStartPosition? {
        val state = QueueStateStore.read(this) ?: return null
        if (state.specs.isEmpty()) return null
        val index = state.index.coerceIn(0, (state.specs.size - 1).coerceAtLeast(0))
        val items = state.specs.map { it.toMediaItem() }.withArtwork(state.specs, index)
        val positionMs = (state.positionSec * 1000f).toLong().coerceAtLeast(0L)
        return MediaSession.MediaItemsWithStartPosition(items, index, positionMs)
    }

    /**
     * 插入并修正随机播放顺序：shuffle 开启时 DefaultShuffleOrder 会把新增项随机放置，
     * 「下一首播放/最后播放」因此不成立。这里在插入后重建播放序——
     * next 模式 = 当前曲 → 插入项 → 其余曲目（重新随机）；last 模式 = 当前曲 → 其余 → 插入项。
     * 关闭随机时 ExoPlayer 原生插入即符合窗口序，无需修正。
     */
    private fun insertWithShuffleFix(index: Int, items: List<MediaItem>, pinLast: Boolean) {
        val player = mediaSession?.player ?: return
        if (items.isEmpty()) return
        val current = player.currentMediaItemIndex
        player.addMediaItems(index, items)
        if (!player.shuffleModeEnabled) return
        val exo = player as? ExoPlayer ?: return
        val count = player.mediaItemCount
        val inserted = index.coerceIn(0, count - 1)..(index + items.size - 1).coerceIn(0, count - 1)
        if (current in inserted) return // 防御：插入点不应落在当前曲
        val rest = (0 until count).filter { it != current && it !in inserted }.shuffled(java.util.Random())
        val sequence = if (pinLast) {
            listOf(current) + rest + inserted.toList()
        } else {
            listOf(current) + inserted.toList() + rest
        }
        exo.setShuffleOrder(ShuffleOrder.DefaultShuffleOrder(sequence.toIntArray(), System.nanoTime()))
    }

    /**
     * 构造 MediaItem。**刻意不下发 artworkUri**（F2）：
     * 它指向应用私有 cache 的 file:// 路径，系统进程读不到——真机稳定刷
     * `EACCES: .../Android/data/&lt;pkg&gt;/cache/covers/ 下的 png`、模拟器刷 ENOENT，
     * 每次切歌都刷一片。控制中心/锁屏之所以看着正常，是因为它们走通知的 bitmap 通道
     * （media3 在**本进程内**解码），与 metadata 那条无关；真正拿不到封面的是
     * 车载 / 手表 / 第三方「正在播放」面板这类只读 metadata 的下游。
     *
     * 封面改由 [withArtwork] 内嵌小 JPEG，原因见 CoverArtBitmap 类注释
     * （content:// 走不通：metadata 是纯数据，带不了 grant flag，实测 SecurityException）。
     */
    private fun SongSpec.toMediaItem(): MediaItem =
        MediaItem.Builder()
            .setMediaId(id)
            .setUri(uri)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setArtist(artist)
                    .setAlbumTitle(album)
                    .build(),
            )
            .build()

    /**
     * 给队首到当前曲附近的一小段内嵌封面（F2）。
     *
     * 为什么不整队内嵌：单张压到 ~5–15KB，千首队列仍会撞 Binder 的 1MB 事务上限，
     * 届时 setMediaItems 整个失败、播放直接不可用——比没有封面严重得多。
     * 系统面板/通知显示的永远是**当前曲**封面，所以只覆盖 [startIndex] 起的一段即可。
     */
    private fun List<MediaItem>.withArtwork(specs: List<SongSpec>, startIndex: Int): List<MediaItem> {
        var budget = CoverArtBitmap.BUDGET_BYTES
        val art = HashMap<String, ByteArray>()
        val order = ((startIndex until specs.size) + (0 until startIndex))
            .take(CoverArtBitmap.MAX_ATTEMPTS)
        for (i in order) {
            val spec = specs.getOrNull(i) ?: continue
            if (art.containsKey(spec.id)) continue
            val bytes = CoverArtBitmap.smallJpeg(this@MusicPlaybackService, spec.artworkUri) ?: continue
            // **装不下就整轮停手，不能 continue**：写成 continue 时只要剩余预算还>0
            // 却小于单张尺寸，退出条件就永不成立，会把队列剩下的每一首都完整解码+压缩
            // 一遍再丢弃 —— 千首队列近千次无用解码（单张 8~20ms）。本函数由
            // buildResumptionState / shuffle 插入命令在服务主线程调用，同样会卡住。
            if (bytes.size > budget) break
            budget -= bytes.size
            art[spec.id] = bytes
        }
        if (art.isEmpty()) return this
        return map { item ->
            val bytes = art[item.mediaId] ?: return@map item
            item.buildUpon()
                .setMediaMetadata(
                    item.mediaMetadata.buildUpon()
                        .setArtworkData(bytes, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
                        .build(),
                )
                .build()
        }
    }

    /** 按预设名应用频段增益（5 段增益表 → 实际频段数线性重采样）；Off = 关闭音效 */
    private fun applyEqPreset(presetName: String?) {
        if (presetName != null) lastPreset = presetName
        runCatching { ensureEqualizer(mediaSession?.player?.audioSessionId ?: 0) }
        val eq = equalizer ?: return
        val gains = eqGainsFor(presetName)
        if (gains == null) {
            runCatching { eq.enabled = false }
            return
        }
        runCatching {
            val bands = eq.numberOfBands
            val range = eq.bandLevelRange
            for (band in 0 until bands) {
                val t = if (bands <= 1) 0f else band.toFloat() * (GAIN_STEPS - 1) / (bands - 1)
                val i = t.toInt().coerceIn(0, GAIN_STEPS - 1)
                val j = (i + 1).coerceAtMost(GAIN_STEPS - 1)
                val db = gains[i] + (gains[j] - gains[i]) * (t - i)
                val level = (db * 100).toInt().coerceIn(range[0].toInt(), range[1].toInt())
                eq.setBandLevel(band.toShort(), level.toShort())
            }
            eq.enabled = true
        }
    }

    /** 音频会话 id 就绪后创建均衡器（幂等；会话 id 变化时重建并重放预设） */
    private fun ensureEqualizer(audioSessionId: Int) {
        if (audioSessionId <= 0) return
        if (equalizer != null && eqSessionId == audioSessionId) return
        runCatching { equalizer?.release() }
        equalizer = runCatching { Equalizer(0, audioSessionId) }.getOrNull()
        eqSessionId = if (equalizer != null) audioSessionId else 0
        if (equalizer != null) applyEqPreset(lastPreset)
    }

    // ── 音效链：响度归一 / 低音增强 / 虚拟环绕 ────────────────────────────

    /**
     * 会话就绪后创建三个效果实例（幂等；会话变化时**先释放旧的再建新的**）。
     *
     * 与 [ensureEqualizer] 同一套模式，但有一处关键差异：这里三个实例**各自独立 runCatching**。
     * 为什么：BassBoost / Virtualizer 依赖设备的 audio HAL 提供对应 effect 库，
     * 很多机器（尤其是国产 ROM 的精简 audio_effects.conf）只有一个甚至一个都没有。
     * 若像 EQ 那样把三个包在一个 runCatching 里，Virtualizer 失败会连累 BassBoost 一起不生效。
     * 分开隔离后：能用的照常用，不能用的只是那一个开关不产生效果（且日志留痕）。
     */
    private fun ensureAudioFx(audioSessionId: Int) {
        if (audioSessionId <= 0) return
        if (audioFxSessionId == audioSessionId &&
            (loudnessEnhancer != null || bassBoost != null || virtualizer != null)
        ) {
            return // 同一会话且至少一条链健在：不重建（重建会有一瞬的断音）
        }
        releaseAudioFx()
        // 逐个独立兜底：任一设备不支持只影响它自己（见方法注释）
        loudnessEnhancer = runCatching { LoudnessEnhancer(audioSessionId) }
            .onFailure { Log.w(TAG, "LoudnessEnhancer unavailable on session $audioSessionId", it) }
            .getOrNull()
        bassBoost = runCatching { BassBoost(0, audioSessionId) }
            .onFailure { Log.w(TAG, "BassBoost unavailable on session $audioSessionId", it) }
            .getOrNull()
        virtualizer = runCatching { Virtualizer(0, audioSessionId) }
            .onFailure { Log.w(TAG, "Virtualizer unavailable on session $audioSessionId", it) }
            .getOrNull()
        audioFxSessionId =
            if (loudnessEnhancer != null || bassBoost != null || virtualizer != null) audioSessionId else 0
        Log.i(
            TAG,
            "audiofx attached session=$audioSessionId loudness=${loudnessEnhancer != null} " +
                "bass=${bassBoost != null} virtual=${virtualizer != null}",
        )
    }

    /**
     * 释放全部音效实例并把会话号清零。
     *
     * **必须在三处调用**：会话重建前（[ensureAudioFx] 开头）、[onDestroy]、
     * 以及服务被停掉时。不释放的后果不是「内存泄漏」这么抽象——是本工程历史上真实踩过的
     * **「上一个会话的 EQ 还在生效」**：旧的 Effect 对象仍持有底层 effect 句柄，
     * 有些设备上它会在新会话上叠加，用户听到的是一层完全无法解释的染色。
     * 每个 release 单独 runCatching：一个句柄已经失效不该阻断其余释放。
     */
    private fun releaseAudioFx() {
        runCatching { loudnessEnhancer?.release() }
        runCatching { bassBoost?.release() }
        runCatching { virtualizer?.release() }
        loudnessEnhancer = null
        bassBoost = null
        virtualizer = null
        audioFxSessionId = 0
    }

    /**
     * 应用音效设置（幂等，可反复调用）。
     *
     * 设计要点：
     * 1. **先存后做**：立刻把 [settings] 落到 [audioFxSettings]，再做实例操作。
     *    这样即便此刻音频会话还没就绪（实例为 null），下一次 READY/切歌也会按最新值重放——
     *    旧实现只操作实例，用户「一启动就开低音」的设置在会话就绪前下发就丢了。
     * 2. **三项独立 runCatching**：蓝牙 A2DP / USB DAC 上 audiofx 常抛
     *    UnsupportedOperationException / IllegalArgumentException / IllegalStateException，
     *    绝不能让某一个失败阻断其余两个（也绝不能让异常冒到命令回调里）。
     * 3. **关掉时显式 `enabled = false` 而不是 release**：release 会销毁实例，
     *    下次开还得重建（有断音）；enabled=false 是零成本的静音路径。
     */
    private fun applyAudioFxSettings(settings: AudioFxSettings) {
        val loudnessJustEnabled = settings.loudnessNorm && !audioFxSettings.loudnessNorm
        audioFxSettings = settings
        runCatching { ensureAudioFx(mediaSession?.player?.audioSessionId ?: 0) }

        // 用户**正在听歌时**打开响度归一：当前曲的标签还没读过（关着的时候我们根本不读文件），
        // 若只等下一次切歌，用户会觉得「开了没用」。这里补一次当前曲的读取。
        // 只在「关 → 开」这个边沿触发：否则每次 applyAudioFxSettings（切档/切歌/会话重建）
        // 都会重新读一遍文件，白白产生 IO。
        if (loudnessJustEnabled && currentTrackGainMb == null) {
            scheduleTrackGainRead(mediaSession?.player?.currentMediaItem)
        }

        // ── 响度归一 ──
        // 增益来源优先级：当前曲读到的标签 → 没有标签则**完全不补偿**（不猜固定值）。
        // 为什么不做「无标签时给 -6dB 平均补偿」这种降级：曲库里同样有无损母带和 MP3，
        // 平均补偿会把一部分歌推爆（削波失真），用户听到的是破音而不是「音量一致」。
        runCatching {
            val gainMb = currentTrackGainMb
            loudnessEnhancer?.let { le ->
                if (settings.loudnessNorm && gainMb != null) {
                    le.setTargetGain(gainMb)
                    le.enabled = true
                } else {
                    le.enabled = false
                }
            }
        }.onFailure { Log.w(TAG, "loudness apply failed", it) }

        runCatching {
            bassBoost?.let { bb ->
                if (settings.bassBoost) {
                    bb.setStrength(settings.bassStrength.toShort())
                    bb.enabled = true
                } else {
                    bb.enabled = false
                }
            }
        }.onFailure { Log.w(TAG, "bass boost apply failed", it) }

        runCatching {
            virtualizer?.let { vz ->
                if (settings.virtualizer) {
                    vz.setStrength(settings.virtualStrength.toShort())
                    vz.enabled = true
                } else {
                    vz.enabled = false
                }
            }
        }.onFailure { Log.w(TAG, "virtualizer apply failed", it) }
    }

    /**
     * 起一个后台任务读当前曲的 ReplayGain 标签，读完套用。
     *
     * 为什么值得这么麻烦（而不是「固定增益」降级）：
     * 固定增益**不解决问题**——它只会整体加/减音量，而用户抱怨的是「这首歌轻、下首歌响」，
     * 是**曲目之间**的相对差异。只有读到每首自己的标签才叫归一。
     * 代价是解析容器头，见 [ReplayGainParser] 的覆盖范围说明。
     *
     * 三个必须处理的边界：
     * - **代次作废**：快速连续切歌时旧结果会晚到，套到新曲上就是「切歌后音量突变」。
     * - **开关关闭时不读**：关着还去读文件纯属浪费电与 IO。
     * - **可播 uri 才读**：`content://` 走 ContentResolver.openInputStream；
     *   `file://` 走 File；其余（http 流）直接跳过——网络流读头部代价不可控。
     */
    private fun scheduleTrackGainRead(mediaItem: MediaItem?) {
        val token = ++trackGainToken
        currentTrackGainMb = null
        // 标签丢了要立刻把补偿撤掉，否则上一首的增益会继续作用在新曲上（等于乱套增益）
        runCatching { loudnessEnhancer?.enabled = false }
        if (!audioFxSettings.loudnessNorm) return
        val uri = mediaItem?.localConfiguration?.uri ?: return
        val scheme = uri.scheme?.lowercase() ?: return
        if (scheme != "content" && scheme != "file") return

        val task = Runnable {
            val gainMb = runCatching {
                if (scheme == "content") {
                    contentResolver.openInputStream(uri)?.use { ReplayGainParser.readTrackGainMb(it) }
                } else {
                    uri.path?.let { java.io.File(it).takeIf { f -> f.isFile } }?.inputStream()
                        ?.use { ReplayGainParser.readTrackGainMb(it) }
                }
            }.getOrElse { e ->
                // 坏文件 / 权限 / 容器不支持：全部视为「没有标签」，绝不上报给用户
                Log.d(TAG, "replaygain read failed: $uri", e)
                null
            }
            serviceScope.launch {
                // 回来时可能已经切走好几首了：代次不等就丢弃（见方法注释「代次作废」）
                if (token != trackGainToken) return@launch
                currentTrackGainMb = gainMb
                if (gainMb != null) {
                    Log.i(TAG, "replaygain track gain=${gainMb}mB uri=$uri")
                    applyAudioFxSettings(audioFxSettings)
                }
            }
        }
        try {
            tagReadExecutor.execute(task)
        } catch (e: java.util.concurrent.RejectedExecutionException) {
            // 服务正在销毁：直接放弃这次读取（不影响正在播的音频）
            Log.d(TAG, "tagReadExecutor rejected; skip replaygain", e)
        }
    }

    /**
     * 定时关闭（服务端权威）：mode > 0 = N 分钟后暂停（协程 delay 到点）；
     * -1 = 播完当前首（**仅** Player.MEDIA_ITEM_TRANSITION_REASON_AUTO 那一瞬暂停）；
     * -2 = 播完整个队列（ENDED 暂停）；0 = 取消。
     *
     * -1 的边界（与 UI 的契约）：只有「这一首自己播完」才算达成。手动切歌 / 拖动进度 /
     * 队列被替换都不暂停，且顺手清掉旗标（悬空旗标会让下一次自然切歌莫名暂停，更危险）；
     * UI 侧（AppState）在用户手动切歌或手动暂停时本就会下发 0 撤销定时，两处口径一致。
     *
     * 重复下发新定时先取消旧协程/清旗标；到点执行后引用就地清理，随服务销毁整体取消。
     *
     * **只有分钟档做渐弱**（[FADE_MS] 秒线性淡出到 0 再暂停）。-1 / -2 两档刻意不做，原因：
     * 它们的语义是「这一首/这一队放完就停」，渐弱会吃掉最后 10 秒的音乐——那是用户还要听的内容，
     * 把它削掉等于定时器替用户删了歌尾。分钟档则相反：到点是硬截止，渐弱让「被一刀切断」
     * 变成自然收束，且不会少听任何内容。
     */
    private fun applySleepTimer(modeMinutes: Int) {
        sleepJob?.cancel() // 若正在渐弱，协程 finally 会把音量复位（见 fadeOutThenPause）
        sleepJob = null
        stopAfterCurrent = false
        stopAtQueueEnd = false
        when {
            modeMinutes > 0 -> {
                sleepJob = serviceScope.launch {
                    val player = mediaSession?.player
                    if (player != null) {
                        // 提前 FADE_MS 起渐弱；档位短于渐弱时长时立刻开始（协议最小 15 分钟，
                        // 实际不会发生，这里只是不让 delay 负数变成「永不触发」）
                        delay((modeMinutes * 60_000L - FADE_MS).coerceAtLeast(0L))
                        fadeOutThenPause(player)
                    }
                    sleepJob = null // 到点执行完清理引用（被 cancel 时走不到这里，不会误清新定时）
                }
            }
            modeMinutes == -1 -> stopAfterCurrent = true
            modeMinutes == -2 -> stopAtQueueEnd = true
            // 0 = 取消：旧协程与旗标已在上方清理
        }
    }

    /**
     * 睡眠定时的渐弱收尾：[FADE_MS] 内把播放器音量线性降到 0，再暂停，最后复位音量。
     *
     * **音量复位放在 finally，这是整套取消/恢复路径的唯一收口**：
     * - 用户中途取消定时 → [applySleepTimer] 的 `sleepJob?.cancel()` 打断 delay，finally 复位
     * - 用户中途手动暂停 → [fadeAborted] 旗标让循环提前退出，同样走 finally
     * - 服务销毁（serviceScope.cancel()）→ finally 仍会跑；此外 onDestroy 还显式兜了一层，
     *   因为销毁路径不保证 finally 在 player.release() 之前完成
     * 没有 finally 的话，任何一条提前退出都会把音量停在中间值，用户下次播放就是「半静音」，
     * 且他自己完全无法理解原因（音量条是满的）。
     *
     * 注意复位到的是**渐弱开始时的音量**而不是硬编码 1f：若上层将来把音量做成可调项，
     * 这里不会把它抹平。
     */
    private suspend fun fadeOutThenPause(player: Player) {
        if (!player.playWhenReady) {
            // 已经暂停：没有可渐弱的音频，直接收尾。不写 volume 是刻意的——
            // 避免无意义地 0→原值 抖一次（有些设备会听到轻微 pop）
            return
        }
        val restoreVolume = player.volume.coerceIn(0f, 1f).let { if (it <= 0f) 1f else it }
        fadeInProgress = true
        fadeAborted = false
        try {
            val steps = (FADE_MS / FADE_STEP_MS).toInt().coerceAtLeast(1)
            for (i in 1..steps) {
                if (fadeAborted) return // 用户手动暂停：交 finally 复位音量，定时照旧保留
                player.volume = restoreVolume * (1f - i.toFloat() / steps)
                delay(FADE_STEP_MS)
            }
            // 先立旗再暂停：pause() 会同步回调 onIsPlayingChanged，
            // 没有这面旗，渐弱自己的暂停会被当成「用户手动暂停」
            pausingForSleep = true
            player.pause()
        } finally {
            fadeInProgress = false
            pausingForSleep = false
            fadeAborted = false
            runCatching { player.volume = restoreVolume }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        // 桌面歌词必须先摘窗（任务约束：服务销毁时若在显示必须 removeView）。
        // 放在最前面：此时窗还引用着 provider lambda，先断开数据源再收播放器。
        // 控制器内部 runCatching 兜底，ROM 拒绝/已摘除都不会抛
        if (::desktopLyrics.isInitialized) desktopLyrics.release()
        serviceScope.cancel() // 定时关闭协程随作用域取消，无泄漏
        // 渐弱兜底：若此刻正处在淡出中途，取消协程后 finally 未必赶在 player.release() 之前跑完，
        // 音量可能停在中间值（进程还活着时下一次播放就是「半静音」，用户无从察觉原因）。
        // 这里显式复位一次——幂等，正常路径下等于把 already-restored 的值再写一遍。
        runCatching { mediaSession?.player?.volume = 1f }
        queueReadExecutor.shutdown() // 续播读盘的守护线程收摊（任务已跑完/正在跑的那个自行结束）
        // 标签读盘线程同理收摊；先关执行器再释放效果，避免「读完了又去套增益」的竞态
        tagReadExecutor.shutdown()
        runCatching { equalizer?.release() }
        equalizer = null
        // 音效链必须释放：不释放会出现「上一个会话的效果还在生效」（见 releaseAudioFx 注释）
        releaseAudioFx()
        mediaSession?.run {
            release() // 先释放 session（解除控制器/通知绑定），再释放底层播放器
            player.release()
        }
        mediaSession = null
        super.onDestroy()
    }

    companion object {
        /** UI → 服务的均衡器预设命令（MediaControllerEngine.setEqPreset 经 MediaSession 下发） */
        const val CMD_SET_EQ = "com.local.music.SET_EQ_PRESET"
        const val KEY_PRESET = "preset"

        /** UI → 服务的队列插入命令：shuffle 下修正随机播放序（下一首播放/最后播放用） */
        const val CMD_INSERT_SHUFFLE_FIX = "com.local.music.INSERT_SHUFFLE_FIX"
        const val KEY_SPECS = "specs"
        const val KEY_INDEX = "index"
        const val KEY_PIN_MODE = "pinMode"
        const val PIN_NEXT = "next"
        const val PIN_LAST = "last"

        /** UI → 服务的定时关闭命令：KEY_SLEEP_MODE 分钟数（>0=N 分钟后暂停；-1=播完当前首；-2=播完队列；0=取消） */
        const val CMD_SET_SLEEP_TIMER = "com.local.music.SET_SLEEP_TIMER"
        const val KEY_SLEEP_MODE = "sleepMode"

        /**
         * UI → 服务的音效链命令（响度归一 / 低音增强 / 虚拟环绕）。
         *
         * **为什么一次命令带全 4 个参数**（而不是每项一个命令）：这四项是**一组状态**，
         * 分开下发时若其中一条命令丢失/延迟（binder 是异步的），服务端与 UI 就会短暂不一致，
         * 而下一首歌装载时是按服务端状态重放的 —— 表现为「设置里开着，但实际没生效」。
         * 一次带全 + 服务端整体覆盖，天然幂等，不会出现半套状态。
         */
        const val CMD_SET_AUDIO_FX = "com.local.music.SET_AUDIO_FX"
        const val KEY_FX_LOUDNESS = "fxLoudness"
        const val KEY_FX_BASS = "fxBass"
        const val KEY_FX_BASS_LEVEL = "fxBassLevel"
        const val KEY_FX_VIRTUAL = "fxVirtual"
        const val KEY_FX_VIRTUAL_LEVEL = "fxVirtualLevel"

        /**
         * UI → 服务的桌面歌词开关命令（P0-8）。
         *
         * **为什么没有像 AudioFxSettings 那样做「服务端状态重放」**：悬浮窗是纯显示层
         * 瞬态——服务重建时（进程被杀后系统拉起续播）直接 show 会在锁屏/后台场景凭空
         * 弹出一扇窗，用户没有当下操作却看到桌面多了东西，比「开关要重开一次」糟糕得多。
         * 折中：开关值仍持久化在 UI 侧快照里，服务重连时（GlassApp onConnected）
         * 按快照值下发一次；服务自己不记住。这也是任务描述「服务端驱动」的边界——
         * 服务驱动的是**进度同步与切歌重读**，开关方向仍由 UI 主导。
         */
        const val CMD_SET_DESKTOP_LYRICS = "com.local.music.SET_DESKTOP_LYRICS"
        const val KEY_DESKTOP_LYRICS_ON = "desktopLyricsOn"

        /**
         * 曲目 id 前缀约定：MediaLibrary 扫描写入的稳定 id = "f" + 文件绝对路径
         * （见 MediaLibrary.load 的 DemoSong 构造）。桌面歌词从这里还原音频路径找同名 .lrc。
         * MediaLibrary 已封板，故不把常量挪过去，此处引用同一字面量并注释互指。
         */
        private const val FILE_ID_PREFIX = "f"

        private const val GAIN_STEPS = 5
        private const val TAG = "MusicPlaybackService"

        /** 睡眠定时渐弱总时长（毫秒）：到点前这么久开始淡出 */
        private const val FADE_MS = 10_000L

        /** 渐弱步进间隔：10s / 150ms ≈ 67 步，听感连续，主线程开销可忽略 */
        private const val FADE_STEP_MS = 150L

        /** 5 频段（低→高）增益表，单位 dB，幅度控制在 ±6 内 */
        private fun eqGainsFor(presetName: String?): FloatArray? = when (presetName) {
            "Pop" -> floatArrayOf(2f, 4f, 2f, 0f, 1f)
            "Rock" -> floatArrayOf(4f, 2f, -1f, 2f, 3f)
            "Classic" -> floatArrayOf(3f, 1f, 0f, 2f, 3f)
            "Vocal" -> floatArrayOf(-2f, 0f, 4f, 2f, -1f)
            else -> null // Off / 未知预设：禁用
        }
    }
}
