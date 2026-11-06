package com.local.music.player

import android.content.Intent
import android.app.PendingIntent
import android.media.audiofx.Equalizer
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
import com.local.music.core.ui.state.SongSpec
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
 * - 均衡器：android.media.audiofx.Equalizer 挂在播放器音频会话上，预设经自定义命令下发
 * - 定时关闭：分钟档协程到点暂停；播完当前首/队列由播放器监听承接（服务端权威）
 */
class MusicPlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private var equalizer: Equalizer? = null
    private var eqSessionId = 0 // 均衡器绑定的音频会话（设备切换会变化，需重建）
    private var lastPreset = "Off" // 最近一次请求的预设（音频会话就绪后重放）

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
                        } else if (customCommand.customAction == CMD_SET_SLEEP_TIMER) {
                            applySleepTimer(args.getInt(KEY_SLEEP_MODE, 0))
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
                    } else if (playbackState == Player.STATE_ENDED && stopAtQueueEnd) {
                        // -2 档「播完整个队列」：自然播完（ENDED）即暂停并清旗标。
                        // repeatAll 开着时播放器回绕、永不 ENDED，该档等价不触发（符合语义，不管）
                        stopAtQueueEnd = false
                        player.pause()
                    }
                }

                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
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
            },
        )
        // 均衡器挂播放器音频会话；音频会话未就绪时由首次 EQ 命令/首个 READY 延迟创建
        runCatching { ensureEqualizer(player.audioSessionId) }
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
     */
    private fun applySleepTimer(modeMinutes: Int) {
        sleepJob?.cancel()
        sleepJob = null
        stopAfterCurrent = false
        stopAtQueueEnd = false
        when {
            modeMinutes > 0 -> {
                sleepJob = serviceScope.launch {
                    delay(modeMinutes * 60_000L)
                    mediaSession?.player?.pause()
                    sleepJob = null // 到点执行完清理引用（被 cancel 时走不到这里，不会误清新定时）
                }
            }
            modeMinutes == -1 -> stopAfterCurrent = true
            modeMinutes == -2 -> stopAtQueueEnd = true
            // 0 = 取消：旧协程与旗标已在上方清理
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
        serviceScope.cancel() // 定时关闭协程随作用域取消，无泄漏
        queueReadExecutor.shutdown() // 续播读盘的守护线程收摊（任务已跑完/正在跑的那个自行结束）
        runCatching { equalizer?.release() }
        equalizer = null
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

        private const val GAIN_STEPS = 5
        private const val TAG = "MusicPlaybackService"

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
