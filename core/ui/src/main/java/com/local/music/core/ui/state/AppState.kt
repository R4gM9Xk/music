package com.local.music.core.ui.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateSetOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import com.local.music.core.ui.components.ArtStyle
import com.local.music.core.ui.components.DemoCatalog
import com.local.music.core.ui.components.DemoData
import com.local.music.core.ui.components.DemoPlaylist
import com.local.music.core.ui.components.DemoSong
import com.local.music.core.ui.components.DetailData
import com.local.music.core.ui.components.DetailTarget
import com.local.music.core.ui.components.LyricLine
import com.local.music.core.ui.theme.AuroraPalette
import com.local.music.core.ui.theme.GlassIntensity
import com.local.music.core.ui.theme.GlassPalettes
import com.local.music.core.ui.theme.MotionIntensity

/** 6 屏路由（设计稿 7 屏去掉「流体云/小部件」展示页：系统级流体云已由媒体会话承担） */
enum class GlassScreen { Player, Library, Playlist, Detail, Search, Settings }

/** 循环模式：关 / 列表循环 / 单曲循环 */
enum class RepeatMode { Off, All, One }

/** 自建歌单快照（trackIds = 加入歌单叠加的曲目 id） */
data class PlaylistSnapshot(val id: String, val title: String, val trackIds: List<String>)

/** 持久化快照：SettingsStore 负责落盘/读盘，全部为可序列化纯类型 */
data class AppStateSnapshot(
    val followCover: Boolean,
    val lyricEnabled: Boolean,
    val glassLevel: String,
    val motionLevel: String,
    val eqPreset: String,
    val themeMode: String,
    val excludedFolders: Set<String>,
    val favorites: Set<String>,
    val searchHistory: List<String>,
    val playedIds: List<String>,
    val userPlaylists: List<PlaylistSnapshot>,
    val addedTracks: Map<String, List<String>>,
    val queueIds: List<String>,
    val queueIndex: Int,
    val progressSec: Float,
    val currentSongId: String? = null, // 队列过滤后仍能定位当前曲（失效曲目剔除不改索引语义）
)

/** 持久化桥接：AppState 只面向它，实现在 app 模块（DataStore） */
fun interface SnapshotStore {
    fun schedule(snapshot: AppStateSnapshot)
}

/** 均衡器预设：经 MediaSession 自定义命令下发服务端 Equalizer（Off = 关闭音效） */
enum class EqPreset(val label: String) {
    Off("关闭"), Pop("流行"), Rock("摇滚"), Classic("古典"), Vocal("人声"),
}

/** 深浅色模式 */
enum class ThemeMode { Dark, Light }

/** 引擎装载队列所需的元数据（id = 稳定 mediaId，媒体通知 / 锁屏 / 流体云展示用） */
data class SongSpec(
    val id: String,
    val uri: String,
    val title: String,
    val artist: String,
    val album: String,
    val artworkUri: String?,
    val durationMs: Long,
)

/** 播放中实时音频参数（ExoPlayer Format，最权威；null = 未在播放/取不到） */
data class SongFormat(
    val codec: String,
    val sampleRateHz: Int,
    val bitDepth: Int,
    val bitrateKbps: Int,
)

/**
 * 真实播放引擎接口：AppState 只面向它；演示模式（engine=null 或曲目无 uri）走定时器。
 * 整条队列交给引擎（服务端播放器）：循环 / 随机 / 完播切换由播放器承担，
 * UI 经 onMediaItemChanged 按 mediaId 回流同步——界面销毁、退后台、锁屏后仍连续播放。
 */
interface PlayerEngine {
    var onPlayingChanged: ((Boolean) -> Unit)?
    var onFormatChanged: ((SongFormat?) -> Unit)?
    var onMediaItemChanged: ((String?) -> Unit)?
    var onError: ((String) -> Unit)?
    fun loadQueue(specs: List<SongSpec>, startIndex: Int, startSec: Float, autoPlay: Boolean)
    fun setRepeatMode(repeatAll: Boolean, repeatOne: Boolean)
    fun setShuffleMode(enabled: Boolean)
    fun setEqPreset(presetName: String)
    fun play()
    fun pause()
    fun seekTo(sec: Float)
    fun skipTo(index: Int)
    fun skipToNext()
    fun skipToPrevious()
    fun hasNextMediaItem(): Boolean
    fun hasPreviousMediaItem(): Boolean
    fun currentMediaId(): String?
    fun isEnginePlaying(): Boolean
    fun positionSec(): Float
    fun durationSec(): Float
    fun stopAndClear()
    fun release()
}

/** 曲库分组（专辑/艺术家/文件夹/流派共用） */
data class LibraryGroup(
    val key: String,
    val songs: List<DemoSong>,
) {
    val art: ArtStyle get() = songs.first().art
    val coverBitmap get() = songs.first().coverBitmap
    val meta: String get() = "${songs.size} 首 · ${songs.first().artist}"
}

/**
 * 全局单源状态（"后端"）：导航、播放、收藏、歌单、搜索、设置、真曲库与播放引擎。
 * 所有屏幕只读写这里；替换为 ViewModel/DataStore 时 UI 层无感。
 *
 * 初始化时序约定：restore()（快照）与 loadLibrary()（曲库扫描）谁先到都正确收敛——
 * restore 落 pendingPlayback 后立即尝试应用（演示模式快照可直接生效），loadLibrary
 * 装载真曲库后再次尝试；服务端已在播则以 MediaSession 为权威。恢复落地前 scheduleSave
 * 是 no-op（hydrated 门控），避免初始化期间的默认演示状态覆盖磁盘快照。
 */
class AppState {
    // ── 导航 ──────────────────────────────────────────────
    var screen by mutableStateOf(GlassScreen.Player)
    var detail by mutableStateOf(DetailTarget(DetailTarget.Kind.Playlist, DemoCatalog.playlists.first().id))

    /** 详情页返回来源：从搜索/曲库进详情，返回时回到原页面而非固定歌单 */
    var detailReturn by mutableStateOf(GlassScreen.Playlist)
        private set

    // ── 真曲库 / 播放引擎 ─────────────────────────────────
    var engine: PlayerEngine? = null
        private set
    private var engineLoadedUri: String? = null
    private var pendingStart = false // 服务连接前已点播：连接成功后补发

    // ── 持久化 ────────────────────────────────────────────
    private var store: SnapshotStore? = null
    private var pendingPlayback: Pair<List<String>, Pair<Int, Float>>? = null
    private var currentSongIdPending: String? = null
    private var hydrated = false
    private var saveTick = 0

    /** 当前曲库（默认演示曲库；装载真曲库后整体切换） */
    var library by mutableStateOf(DemoData.librarySongs)
        private set

    /** 真曲库已装载：此后不再解析演示曲目 id（收藏/歌单中的失效引用自然过滤） */
    var realLibraryLoaded by mutableStateOf(false)
        private set

    // ── 播放 ──────────────────────────────────────────────
    var queue by mutableStateOf(DemoData.songs)
        private set
    var queueIndex by mutableIntStateOf(0)
        private set
    var playing by mutableStateOf(true)
    var progressSec by mutableFloatStateOf(98f) // 初始 01:38，对齐设计稿
        private set
    var shuffleOn by mutableStateOf(false)
    var repeatMode by mutableStateOf(RepeatMode.Off)
    val playedIds = mutableStateListOf<String>() // 最近播放（新→旧，按 id 去重）

    /** 播放中实时格式（ExoPlayer Format 回流）；null 时展示扫描值 */
    var liveFormat by mutableStateOf<SongFormat?>(null)
        private set

    /** 播放错误（文件丢失 / 格式不支持等）；非 null 时 UI 显示可关闭的提示条 */
    var playbackError by mutableStateOf<String?>(null)
        private set

    // ── 收藏 / 歌单 ───────────────────────────────────────
    val favorites = mutableStateSetOf("night-flight")
    val userPlaylists = mutableStateListOf<DemoPlaylist>()

    /** 「加入歌单」叠加层：playlistId → 追加的曲目 id（常驻歌单曲目不可变） */
    private val addedTracks = mutableStateMapOf<String, SnapshotStateList<String>>()

    // ── 设置 ──────────────────────────────────────────────
    var followCover by mutableStateOf(true)
    var lyricEnabled by mutableStateOf(true)
    var glassLevel by mutableStateOf(GlassIntensity.Standard)
    var motionLevel by mutableStateOf(MotionIntensity.Standard)
    var eqPreset by mutableStateOf(EqPreset.Off)
    var themeMode by mutableStateOf(ThemeMode.Dark)
    val excludedFolders = mutableStateSetOf<String>()

    /** 右上角"太阳"按钮：深浅色切换 */
    fun toggleTheme() {
        themeMode = if (themeMode == ThemeMode.Dark) ThemeMode.Light else ThemeMode.Dark
        scheduleSave()
    }

    // ── 搜索 ──────────────────────────────────────────────
    var searchQuery by mutableStateOf("")
    val searchHistory = mutableStateListOf("林之遥", "深夜巡航", "FLAC", "潮汐信号")

    // ── 派生 ──────────────────────────────────────────────
    val currentSong: DemoSong get() = queue[queueIndex.coerceIn(0, queue.lastIndex)]
    val durationSec: Float get() = currentSong.durationSec.toFloat()
    val progressFraction: Float get() = (progressSec / durationSec).coerceIn(0f, 1f)

    /** 极光取色：真封面取色优先 → 无封面回退占位风格 → 取色开关关闭时回退品牌靛紫 */
    val palette: AuroraPalette
        get() = if (followCover) {
            currentSong.palette ?: currentSong.art.palette
        } else {
            GlassPalettes.Purple
        }

    /**
     * 当前歌的歌词：演示曲库永远给演示歌词；
     * 真曲库有 .lrc 给真歌词，没有则返回 null（播放页显示「纯音乐，请欣赏」空态，不再配假歌词）
     */
    val currentLyrics: List<LyricLine>?
        get() = if (currentSong.mediaUri == null) DemoCatalog.lyrics else currentSong.lyricLines

    /** 当前行歌词 = 最后一条 timeSec <= 进度（无歌词时无意义） */
    val currentLyricIndex: Int
        get() = (currentLyrics ?: emptyList()).indexOfLast { it.timeSec <= progressSec }.coerceAtLeast(0)

    // ── 真曲库装载 ────────────────────────────────────────

    /** 注入播放引擎（GlassApp 的 MediaController 连接成功后调用）：先同步服务现状，再补发暂存意图 */
    fun attachPlayer(engine: PlayerEngine) {
        this.engine = engine
        engine.onPlayingChanged = { isPlaying ->
            // 仅在引擎驱动真实播放时回流；演示模式（stopAndClear 之后）的 false 回调不打断定时器
            if (engineLoadedUri != null) {
                playing = isPlaying
                scheduleSave()
            }
        }
        engine.onFormatChanged = { liveFormat = it }
        engine.onMediaItemChanged = { mediaId -> syncFromEngine(mediaId) }
        engine.onError = { message ->
            playbackError = message
            playing = false
            engineLoadedUri = null
            scheduleSave()
        }
        // 服务为权威：读回现有曲目 / 进度 / 播放态（重新打开界面时服务可能仍在播）
        val currentId = engine.currentMediaId()
        if (currentId != null) {
            val song = songById(currentId)
            when {
                song != null -> {
                    if (queue.none { it.id == currentId }) queue = library
                    queueIndex = queue.indexOfFirst { it.id == currentId }.coerceAtLeast(0)
                    engineLoadedUri = song.mediaUri
                    progressSec = engine.positionSec().coerceIn(0f, song.durationSec.toFloat())
                    playing = engine.isEnginePlaying()
                    // 服务在播，待恢复现场以服务为准
                    pendingPlayback = null
                    currentSongIdPending = null
                    resumePending = false
                }
                realLibraryLoaded -> engine.stopAndClear() // 曲库已装载仍找不到：文件已被删除
                // 曲库尚未装载：不打断服务播放，等 loadLibrary() 对齐
            }
        }
        syncEngineModes(engine)
        if (resumePending && engineLoadedUri == null) prepareResume()
        if (pendingStart) {
            pendingStart = false
            startCurrent()
        }
        scheduleSave()
    }

    /** 引擎切换到新曲目（含后台完播自动切歌）：按 mediaId 对齐队列索引与进度 */
    private fun syncFromEngine(mediaId: String?) {
        if (mediaId == null) return
        val index = queue.indexOfFirst { it.id == mediaId }
        if (index >= 0 && index != queueIndex) {
            queueIndex = index
            progressSec = 0f
            liveFormat = null
            rememberPlayed(mediaId)
            scheduleSave()
        }
    }

    /** 循环 / 随机 / 均衡器 → 服务端播放器（切换即时下发） */
    private fun syncEngineModes(eng: PlayerEngine) {
        eng.setRepeatMode(repeatMode == RepeatMode.All, repeatMode == RepeatMode.One)
        eng.setShuffleMode(shuffleOn)
        eng.setEqPreset(eqPreset.name)
    }

    // ── 持久化：快照 / 恢复 / 存盘调度 ────────────────────

    /** 绑定存盘实现（GlassApp 启动时；传 null 解绑） */
    fun bindStore(store: SnapshotStore?) {
        this.store = store
    }

    fun scheduleSave() {
        if (!hydrated) return // 恢复完成前不落盘，防止默认演示状态覆盖磁盘快照
        store?.schedule(snapshot())
    }

    /** 设置指纹：GlassApp 用 snapshotFlow 监听设置变更触发存盘 */
    fun settingsKey(): String =
        "$followCover|$lyricEnabled|${glassLevel.name}|${motionLevel.name}|${eqPreset.name}|${themeMode.name}|" +
            excludedFolders.joinToString(",")

    fun snapshot(): AppStateSnapshot = AppStateSnapshot(
        followCover = followCover,
        lyricEnabled = lyricEnabled,
        glassLevel = glassLevel.name,
        motionLevel = motionLevel.name,
        eqPreset = eqPreset.name,
        themeMode = themeMode.name,
        excludedFolders = excludedFolders.toSet(),
        favorites = favorites.toSet(),
        searchHistory = searchHistory.toList(),
        playedIds = playedIds.toList(),
        userPlaylists = userPlaylists.map {
            PlaylistSnapshot(it.id, it.title, addedTracks[it.id]?.toList() ?: emptyList())
        },
        addedTracks = addedTracks.mapValues { it.value.toList() },
        queueIds = queue.map { it.id },
        queueIndex = queueIndex,
        progressSec = progressSec,
        currentSongId = currentSong.id,
    )

    /** 恢复快照：设置/收藏/历史/歌单立即生效；播放现场由 applyPendingPlayback 应用（幂等） */
    fun restore(snapshot: AppStateSnapshot) {
        followCover = snapshot.followCover
        lyricEnabled = snapshot.lyricEnabled
        glassLevel = runCatching { GlassIntensity.valueOf(snapshot.glassLevel) }.getOrDefault(GlassIntensity.Standard)
        motionLevel = runCatching { MotionIntensity.valueOf(snapshot.motionLevel) }.getOrDefault(MotionIntensity.Standard)
        eqPreset = runCatching { EqPreset.valueOf(snapshot.eqPreset) }.getOrDefault(EqPreset.Off)
        themeMode = runCatching { ThemeMode.valueOf(snapshot.themeMode) }.getOrDefault(ThemeMode.Dark)
        excludedFolders.clear()
        excludedFolders.addAll(snapshot.excludedFolders)
        favorites.clear()
        favorites.addAll(snapshot.favorites)
        searchHistory.clear()
        searchHistory.addAll(snapshot.searchHistory)
        playedIds.clear()
        playedIds.addAll(snapshot.playedIds)
        userPlaylists.clear()
        userPlaylists.addAll(
            snapshot.userPlaylists.map { p ->
                DemoPlaylist(p.id, p.title, "${p.trackIds.size} 首", ArtStyle.Room, emptyList())
            },
        )
        addedTracks.clear()
        snapshot.addedTracks.forEach { (id, tracks) ->
            addedTracks[id] = mutableStateListOf<String>().apply { addAll(tracks) }
        }
        snapshot.userPlaylists.forEach { p ->
            if (p.trackIds.isNotEmpty()) {
                addedTracks.getOrPut(p.id) { mutableStateListOf() }.addAll(p.trackIds.filterNot { it in addedTracks[p.id]!! })
            }
        }
        pendingPlayback = snapshot.queueIds to (snapshot.queueIndex to snapshot.progressSec)
        currentSongIdPending = snapshot.currentSongId
        hydrated = true // 恢复完成：允许存盘
        applyPendingPlayback()
    }

    private var resumePending = false // 播放现场已应用，等引擎就绪后装载（不自动播放）

    /** 应用待恢复的播放现场：解析不到的歌跳过；按快照里的当前曲 id 定位索引 */
    private fun applyPendingPlayback() {
        val (ids, position) = pendingPlayback ?: return
        val songs = ids.mapNotNull { songById(it) }
        if (songs.isEmpty()) return // 曲库尚未装载：保留 pending，等 loadLibrary() 再试
        pendingPlayback = null
        val savedSongId = currentSongIdPending
        currentSongIdPending = null
        queue = songs
        queueIndex = savedSongId?.let { id -> songs.indexOfFirst { it.id == id } }
            ?.takeIf { it >= 0 }
            ?: position.first.coerceIn(0, songs.lastIndex)
        progressSec = position.second.coerceIn(0f, songs.getOrNull(queueIndex)?.durationSec?.toFloat() ?: durationSec)
        resumePending = true
        prepareResume()
    }

    /** 装载真曲库：整体切换 library 与队列；服务在播则以服务为准，有待恢复现场则按快照恢复 */
    fun loadLibrary(songs: List<DemoSong>) {
        if (songs.isEmpty()) return
        library = songs
        realLibraryLoaded = true
        val eng = engine
        val currentId = eng?.currentMediaId()
        val playingSong = currentId?.let { id -> songs.firstOrNull { it.id == id } }
        if (playingSong != null) {
            // 服务正在播曲库中的歌：重建队列引用，保留当前曲与进度
            queue = songs
            queueIndex = songs.indexOf(playingSong)
            progressSec = eng!!.positionSec().coerceIn(0f, playingSong.durationSec.toFloat())
            engineLoadedUri = playingSong.mediaUri
            pendingPlayback = null
            currentSongIdPending = null
            resumePending = false
            return
        }
        queue = songs
        queueIndex = 0
        progressSec = 0f
        resumePending = false
        if (eng == null || eng.currentMediaId() == null) playing = false
        if (pendingPlayback == null) playedIds.clear()
        applyPendingPlayback()
    }

    /** 引擎就绪时装载恢复现场（不自动播放）：队列 + 起始位置原子下发，点播放即从原位续播 */
    private fun prepareResume() {
        val eng = engine ?: return
        if (engineLoadedUri != null) return
        val song = currentSong
        if (song.mediaUri == null) return
        resumePending = false
        loadQueueOnEngine(eng, autoPlay = false, startSec = progressSec)
        syncEngineModes(eng)
        engineLoadedUri = song.mediaUri
    }

    // ── 播放行为 ──────────────────────────────────────────

    /** 100ms 心跳：引擎驱动时读播放位置（完播切换由服务端播放器承担），演示模式推进定时器；每 5s 节流存盘 */
    fun tick() {
        if (!playing) return
        val eng = engine
        if (eng != null && engineLoadedUri != null && currentSong.mediaUri != null) {
            progressSec = eng.positionSec().coerceIn(0f, durationSec)
        } else {
            val next = progressSec + 0.1f
            if (next >= durationSec) handleTrackEnd() else progressSec = next
        }
        if (++saveTick >= 50) {
            saveTick = 0
            scheduleSave()
        }
    }

    /** 播放指定曲目；context 为其所在队列（同队列引用时仅切索引） */
    fun playSong(song: DemoSong, context: List<DemoSong> = queue) {
        if (context !== queue) queue = context
        val index = context.indexOf(song).coerceAtLeast(0)
        if (index == queueIndex && song.mediaUri != null && engineLoadedUri == song.mediaUri) {
            engine?.play() // 同曲已装载：不重载，只确保播放
            playing = true
        } else {
            queueIndex = index
            startCurrent()
        }
        rememberPlayed(song.id)
        scheduleSave()
    }

    fun togglePlayPause() {
        val eng = engine
        val uri = currentSong.mediaUri
        if (eng != null && uri != null) {
            when {
                engineLoadedUri != uri -> {
                    // 尚未装载（含恢复现场）：从当前进度起播（>0 即续播）
                    startCurrent(startPositionSec = progressSec.takeIf { it > 0f } ?: 0f)
                }
                playing -> {
                    eng.pause()
                    playing = false
                }
                else -> {
                    eng.play()
                    playing = true
                }
            }
        } else {
            // 演示曲目：先停掉引擎里可能残留的真实音频（避免 UI 切走后声音继续）
            if (eng != null && engineLoadedUri != null) {
                eng.stopAndClear()
                engineLoadedUri = null
                liveFormat = null
            }
            playing = !playing
        }
        scheduleSave()
    }

    fun next(manual: Boolean = true) {
        val eng = engine
        if (eng != null && engineLoadedUri != null && currentSong.mediaUri != null) {
            // 队列在服务端：切换交由播放器，UI 经 onMediaItemChanged 回流同步
            when {
                eng.hasNextMediaItem() -> eng.skipToNext()
                manual || repeatMode == RepeatMode.All -> eng.skipTo(0) // 队尾手动切歌回绕到开头
                // 自然完播且不循环：播放器已停在队尾，无需动作
            }
            return
        }
        // 演示模式：本地推进。随机优先选未播放曲目；循环关且无下一首时停止（进度停在末尾）
        when {
            shuffleOn && queue.size > 1 -> {
                val unplayed = queue.indices.filter { it != queueIndex && queue[it].id !in playedIds }
                if (manual || repeatMode != RepeatMode.Off || unplayed.isNotEmpty()) {
                    queueIndex = unplayed.randomOrNull()
                        ?: (0..queue.lastIndex).filter { it != queueIndex }.random()
                } else {
                    stopAtEnd()
                    return
                }
            }
            queueIndex < queue.lastIndex -> queueIndex++
            manual || repeatMode == RepeatMode.All -> queueIndex = 0
            else -> {
                stopAtEnd()
                return
            }
        }
        startCurrent()
        rememberPlayed(currentSong.id)
        scheduleSave()
    }

    fun prev() {
        val eng = engine
        if (eng != null && engineLoadedUri != null && currentSong.mediaUri != null) {
            if (eng.positionSec() > 3f) { // 播放中按上一首 = 回到本曲开头
                eng.seekTo(0f)
                progressSec = 0f
            } else if (eng.hasPreviousMediaItem()) {
                eng.skipToPrevious()
            } else {
                eng.seekTo(0f)
                progressSec = 0f
            }
            return
        }
        if (progressSec > 3f) { // 播放中按上一首 = 回到本曲开头
            seekTo(0f)
            return
        }
        queueIndex = if (queueIndex > 0) queueIndex - 1 else queue.lastIndex
        startCurrent()
        rememberPlayed(currentSong.id)
    }

    fun seekTo(sec: Float) {
        progressSec = sec.coerceIn(0f, durationSec)
        val eng = engine
        if (eng != null && engineLoadedUri != null && currentSong.mediaUri != null) eng.seekTo(progressSec)
        scheduleSave()
    }

    fun seekToFraction(fraction: Float) = seekTo(fraction * durationSec)

    fun releasePlayer() {
        engine?.release()
    }

    /** 演示模式完播（引擎路径的完播 / 单曲循环由服务端播放器承担） */
    private fun handleTrackEnd() {
        when (repeatMode) {
            RepeatMode.One -> {
                progressSec = 0f
                playing = true
            }
            else -> next(manual = false)
        }
    }

    /** 自然播完且不循环：停止并停在末尾（避免每 100ms 重复处理完播） */
    private fun stopAtEnd() {
        playing = false
        progressSec = durationSec
        scheduleSave()
    }

    /** 从当前位置起播：真引擎装载整条队列（含通知元数据），演示模式只重置进度 */
    private fun startCurrent(startPositionSec: Float = 0f) {
        liveFormat = null
        resumePending = false
        val eng = engine
        val uri = currentSong.mediaUri
        if (uri == null) {
            // 演示曲目：先停掉引擎里可能残留的真实音频，再回到定时器模拟
            if (eng != null && engineLoadedUri != null) {
                eng.stopAndClear()
                engineLoadedUri = null
            }
            progressSec = 0f
            playing = true
            return
        }
        progressSec = startPositionSec.coerceIn(0f, durationSec)
        playing = true
        if (eng == null) {
            pendingStart = true // 服务尚未连接：暂存播放意图，attachPlayer 成功后补发
            return
        }
        pendingStart = false
        loadQueueOnEngine(eng, autoPlay = true, startSec = startPositionSec)
        syncEngineModes(eng)
        engineLoadedUri = uri
    }

    /** 把当前队列交给服务端播放器（混合队列时演示曲被剔除，按 mediaId 重新定位起始索引） */
    private fun loadQueueOnEngine(eng: PlayerEngine, autoPlay: Boolean, startSec: Float = 0f) {
        val specs = queue.mapNotNull { song ->
            song.mediaUri?.let { uri ->
                SongSpec(
                    id = song.id,
                    uri = uri,
                    title = song.title,
                    artist = song.artist,
                    album = song.album,
                    artworkUri = song.artworkUri,
                    durationMs = song.durationSec * 1000L,
                )
            }
        }
        val startIndex = if (specs.size == queue.size) {
            queueIndex
        } else {
            specs.indexOfFirst { it.id == currentSong.id }.coerceAtLeast(0)
        }
        eng.loadQueue(specs, startIndex, startSec, autoPlay)
    }

    /** 当前曲格式展示串：优先播放中实时参数，回退扫描值；无损显位深、有损显码率 */
    val currentFormatText: String
        get() {
            val codec = (liveFormat?.codec ?: currentSong.codec).uppercase().ifEmpty { "音频" }
            val sampleRate = liveFormat?.sampleRateHz ?: currentSong.sampleRateHz
            val bitDepth = liveFormat?.bitDepth ?: currentSong.bitDepth
            val bitrate = liveFormat?.bitrateKbps ?: currentSong.bitrateKbps
            val lossless = codec in setOf("FLAC", "APE", "WAV", "ALAC", "AIFF", "DSF", "DFF")
            return buildString {
                append(codec)
                if (sampleRate > 0) {
                    append(" · ")
                    append(if (sampleRate % 1000 == 0) "${sampleRate / 1000}" else "%.1f".format(sampleRate / 1000f))
                    append("kHz")
                }
                if (bitDepth > 0) append(" / ").append(bitDepth).append("bit")
                if (!lossless && bitrate > 0) append(" · ").append(bitrate).append("kbps")
            }
        }

    fun toggleShuffle() {
        shuffleOn = !shuffleOn
        engine?.setShuffleMode(shuffleOn)
    }

    fun cycleRepeat() {
        repeatMode = when (repeatMode) {
            RepeatMode.Off -> RepeatMode.All
            RepeatMode.All -> RepeatMode.One
            RepeatMode.One -> RepeatMode.Off
        }
        engine?.setRepeatMode(repeatMode == RepeatMode.All, repeatMode == RepeatMode.One)
    }

    // ── 收藏 / 歌单 ───────────────────────────────────────

    fun isFavorite(id: String): Boolean = id in favorites

    fun toggleFavorite(id: String) {
        if (id in favorites) favorites.remove(id) else favorites.add(id)
        scheduleSave()
    }

    /** 加入歌单：按曲目 id 去重；返回是否真正新增 */
    fun addToPlaylist(playlistId: String, songId: String): Boolean {
        val list = addedTracks.getOrPut(playlistId) { mutableStateListOf() }
        if (songId in list) return false
        list.add(songId)
        syncPlaylistMeta(playlistId)
        scheduleSave()
        return true
    }

    /** 曲目是否已在歌单中（常驻歌单基座曲目或已叠加） */
    fun isSongInPlaylist(playlistId: String, songId: String): Boolean =
        songId in addedTracks[playlistId].orEmpty() ||
            DemoCatalog.playlist(playlistId)?.tracks?.any { it.id == songId } == true

    /** 用户歌单卡片数量实时化：加入/移除曲目后同步更新列表元素 */
    private fun syncPlaylistMeta(playlistId: String) {
        val index = userPlaylists.indexOfFirst { it.id == playlistId }
        if (index >= 0) {
            val playlist = userPlaylists[index]
            userPlaylists[index] = playlist.copy(meta = "${addedTracks[playlistId]?.size ?: 0} 首")
        }
    }

    fun createPlaylist(title: String): DemoPlaylist {
        val playlist = DemoPlaylist(
            id = "user-${System.currentTimeMillis()}",
            title = title.trim().ifEmpty { "新建歌单" },
            meta = "0 首",
            art = ArtStyle.Room,
            tracks = emptyList(),
        )
        userPlaylists.add(playlist)
        scheduleSave()
        return playlist
    }

    fun deletePlaylist(id: String) {
        userPlaylists.removeAll { it.id == id }
        addedTracks.remove(id)
        scheduleSave()
    }

    fun addSearchHistory(query: String) {
        val q = query.trim()
        if (q.isEmpty()) return
        searchHistory.remove(q)
        searchHistory.add(0, q)
        while (searchHistory.size > 8) searchHistory.removeAt(searchHistory.lastIndex)
        scheduleSave()
    }

    /** 进详情并记录返回来源（从详情里再进详情保持原来源） */
    fun openDetail(target: DetailTarget) {
        if (screen != GlassScreen.Detail) detailReturn = screen
        detail = target
        screen = GlassScreen.Detail
    }

    /** 从详情页返回：回到进入前的页面（兜底歌单页） */
    fun backFromDetail() {
        screen = if (detailReturn == GlassScreen.Detail) GlassScreen.Playlist else detailReturn
    }

    // ── 错误提示 ──────────────────────────────────────────

    /** GlassApp 在播放服务连接失败时上报（不静默吞掉点播意图） */
    fun reportEngineFailure(message: String) {
        playbackError = message
    }

    fun dismissPlaybackError() {
        playbackError = null
    }

    // ── 曲库分组与搜索（对当前 library 生效，真曲库/演示曲库通吃）──

    /** 文件夹选项（folder → 曲目数）：从当前曲库派生，设置页「过滤文件夹」用 */
    fun folderOptions(): List<Pair<String, Int>> =
        library.groupBy { it.folder }.map { (folder, songs) -> folder to songs.size }.sortedBy { it.first }

    fun albumGroups(): List<LibraryGroup> =
        library.groupBy { it.album }.map { LibraryGroup(it.key, it.value) }.sortedBy { it.key }

    fun artistGroups(): List<LibraryGroup> =
        library.groupBy { it.artist }.map { LibraryGroup(it.key, it.value) }.sortedBy { it.key }

    fun folderGroups(excluded: Set<String>): List<LibraryGroup> =
        library.filter { it.folder !in excluded }.groupBy { it.folder }
            .map { LibraryGroup(it.key, it.value) }.sortedBy { it.key }

    fun genreGroups(): List<LibraryGroup> =
        library.groupBy { it.genre }.map { LibraryGroup(it.key, it.value) }.sortedBy { it.key }

    fun searchSongs(query: String): List<DemoSong> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        return library.filter {
            it.title.contains(q, true) || it.artist.contains(q, true) || it.album.contains(q, true)
        }
    }

    fun searchAlbumGroups(query: String): List<LibraryGroup> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        return albumGroups().filter { g ->
            g.key.contains(q, true) || g.songs.first().artist.contains(q, true) ||
                g.songs.any { it.title.contains(q, true) }
        }
    }

    /** 按曲目 id 找歌：真曲库装载后不再解析演示 id（失效引用自然过滤，存储原样保留） */
    private fun songById(id: String): DemoSong? =
        if (realLibraryLoaded) {
            library.firstOrNull { it.id == id }
        } else {
            DemoCatalog.songById(id) ?: library.firstOrNull { it.id == id }
        }

    /** 解析歌单：收藏 / 最近播放为动态歌单；常驻 + 用户歌单叠加「加入歌单」曲目 */
    fun resolvePlaylist(id: String): DemoPlaylist? {
        if (id == DemoCatalog.FavoritesId) {
            val tracks = favorites.mapNotNull { songById(it) }
            return DemoPlaylist(id, "我喜欢的音乐", "${tracks.size} 首", ArtStyle.Room, tracks)
        }
        if (id == DemoCatalog.RecentId) {
            val tracks = playedIds.mapNotNull { songById(it) }
            return DemoPlaylist(id, "最近播放", "${tracks.size} 首", ArtStyle.Afternoon, tracks)
        }
        val base = DemoCatalog.playlist(id)
            ?: userPlaylists.firstOrNull { it.id == id }
            ?: return null
        val extra = addedTracks[id].orEmpty()
        val merged = if (extra.isEmpty()) base
        else base.copy(tracks = base.tracks + extra.mapNotNull { songById(it) })
        // 用户歌单曲目数是真实的；常驻歌单 meta 沿用目录展示文案
        return if (merged.id.startsWith("user-")) {
            merged.copy(meta = "${merged.tracks.size} 首")
        } else {
            merged
        }
    }

    /** 详情页数据：歌单 / 专辑 / 艺术家 / 文件夹 / 流派 共用模板 */
    fun detailData(target: DetailTarget): DetailData? {
        return when (target.kind) {
            DetailTarget.Kind.Playlist -> resolvePlaylist(target.id)?.let {
                DetailData("歌单", it.title, it.meta, it.art, it.tracks)
            }
            DetailTarget.Kind.Album -> albumGroups().firstOrNull { it.key == target.id }?.let {
                DetailData("专辑", it.key, it.meta, it.art, it.songs, it.coverBitmap)
            }
            DetailTarget.Kind.Artist -> artistGroups().firstOrNull { it.key == target.id }?.let {
                DetailData(
                    "艺术家", it.key,
                    "${it.songs.size} 首 · ${albumGroups().count { a -> a.songs.first().artist == it.key }} 张专辑",
                    it.art, it.songs, it.coverBitmap,
                )
            }
            DetailTarget.Kind.Folder -> folderGroups(emptySet()).firstOrNull { it.key == target.id }?.let {
                DetailData("文件夹", it.key, "${it.songs.size} 首", it.art, it.songs, it.coverBitmap)
            }
            DetailTarget.Kind.Genre -> genreGroups().firstOrNull { it.key == target.id }?.let {
                DetailData("流派", it.key, "${it.songs.size} 首", it.art, it.songs, it.coverBitmap)
            }
        }
    }

    private fun rememberPlayed(id: String) {
        playedIds.remove(id)
        playedIds.add(0, id)
        if (playedIds.size > 50) playedIds.removeAt(playedIds.lastIndex)
    }
}
