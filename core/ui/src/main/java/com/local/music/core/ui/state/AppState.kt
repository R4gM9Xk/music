package com.local.music.core.ui.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
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
import com.local.music.core.ui.components.asTime
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
    val hiddenBuiltInPlaylists: Set<String> = emptySet(),
    val queueIds: List<String>,
    val queueIndex: Int,
    val progressSec: Float,
    val currentSongId: String? = null, // 队列过滤后仍能定位当前曲（失效曲目剔除不改索引语义）
    val songSort: String = SongSort.Title.name, // 歌曲 tab 排序（默认值解码：无版本迁移）
    val shuffleOn: Boolean = false, // 随机模式（v4 起编码，旧版本解码走默认值）
    val repeatMode: String = RepeatMode.Off.name, // 循环模式名（同上）
    val playbackSpeed: Float = 1.0f, // 播放速度（v5 起编码，旧版本解码走默认值）
    val scanFilterShort: Boolean = true, // 扫描过滤短音频（v6 起编码，旧版本解码走默认值）
)

/** 持久化桥接：AppState 只面向它，实现在 app 模块（DataStore） */
fun interface SnapshotStore {
    fun schedule(snapshot: AppStateSnapshot)
}

/** 均衡器预设：经 MediaSession 自定义命令下发服务端 Equalizer（Off = 关闭音效） */
enum class EqPreset(val label: String) {
    Off("关闭"), Pop("流行"), Rock("摇滚"), Classic("古典"), Vocal("人声"),
}

/** 定时关闭档位：分钟数（-1=播完当前首，-2=播完整个队列，0=取消）；顺序即面板展示顺序 */
val SleepTimerOptions = listOf(15, 30, 60, 90, -1, -2, 0)

/** 定时关闭档位展示名（播放页/设置页两个面板共用） */
fun sleepTimerLabel(option: Int): String = when {
    option > 0 -> "$option 分钟"
    option == -1 -> "播完当前首"
    option == -2 -> "播完整个队列"
    else -> "取消定时"
}

/** 定时关闭选项副标题：生效中项显示进行中语义（分钟档带秒级倒计时），其余无副标题 */
fun sleepTimerSubtitle(option: Int, activeMode: Int, remainingSec: Int): String? = when {
    option != activeMode -> null
    option > 0 -> "剩余 ${remainingSec.asTime()}"
    option == -1 -> "当前曲播完后暂停"
    option == -2 -> "整个队列播完后暂停"
    else -> "未开启"
}

/** 深浅色模式 */
enum class ThemeMode { Dark, Light }

/** 引擎装载队列所需的元数据（id = 稳定 mediaId，媒体通知 / 锁屏 / 流体云展示用）。
 *  Serializable：shuffle 下经 SessionCommand 整批传给服务端做随机序修正 */
data class SongSpec(
    val id: String,
    val uri: String,
    val title: String,
    val artist: String,
    val album: String,
    val artworkUri: String?,
    val durationMs: Long,
) : java.io.Serializable

/** 播放中实时音频参数（ExoPlayer Format，最权威；null = 未在播放/取不到） */
data class SongFormat(
    val codec: String,
    val sampleRateHz: Int,
    val bitDepth: Int,
    val bitrateKbps: Int,
)

/** 歌曲格式展示串（无损显位深、有损显码率）；live 非空时优先播放中实时参数（AppState.currentFormatText / 歌曲信息面板共用） */
fun songFormatText(song: DemoSong, live: SongFormat? = null): String {
    val codec = (live?.codec ?: song.codec).uppercase().ifEmpty { "音频" }
    val sampleRate = live?.sampleRateHz ?: song.sampleRateHz
    val bitDepth = live?.bitDepth ?: song.bitDepth
    val bitrate = live?.bitrateKbps ?: song.bitrateKbps
    val lossless = codec in setOf("FLAC", "APE", "WAV", "ALAC", "AIFF", "DSF", "DFF")
    return buildString {
        append(codec)
        if (sampleRate > 0) {
            append(" · ")
            append(
                if (sampleRate % 1000 == 0) {
                    "${sampleRate / 1000}"
                } else {
                    String.format(java.util.Locale.US, "%.1f", sampleRate / 1000f)
                },
            )
            append("kHz")
        }
        if (bitDepth > 0) append(" / ").append(bitDepth).append("bit")
        if (!lossless && bitrate > 0) append(" · ").append(bitrate).append("kbps")
    }
}

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
    fun setSleepTimer(modeMinutes: Int)
    fun setPlaybackSpeed(speed: Float)
    fun play()
    fun pause()
    fun seekTo(sec: Float)
    fun skipTo(index: Int)
    fun skipToNext()
    fun skipToPrevious()
    fun hasNextMediaItem(): Boolean
    fun hasPreviousMediaItem(): Boolean

    /** 增量队列编辑：不重发整条队列、不打断当前播放。索引语义与 UI 队列一致（Kotlin removeAt/add），
     *  调用方先经 alignQueueWithEngine 保证两侧同源；pinLast = 意图是「最后播放」——shuffle 下
     *  服务端据此固定队尾/当前曲后（不能按插入位置猜：当前曲在队尾时「下一首播放」会被误判） */
    fun addQueueItems(index: Int, specs: List<SongSpec>, pinLast: Boolean)
    fun moveQueueItem(fromIndex: Int, toIndex: Int)
    fun removeQueueItems(fromIndex: Int, toIndex: Int)

    /** 服务端完整队列的 mediaId 列表（服务队列是恢复/对齐的权威） */
    fun mediaIds(): List<String>
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

    /** 当前曲库（唯一来源 = 真实扫描/曲库缓存；演示曲库已移除） */
    var library by mutableStateOf(emptyList<DemoSong>())
        private set

    /** 真曲库已装载：此后不再解析演示曲目 id（收藏/歌单中的失效引用自然过滤） */
    var realLibraryLoaded by mutableStateOf(false)
        private set

    /** 曲库源已定稿（缓存或首扫到达；或确认无曲库走演示）：启动门面据此放行真实内容 */
    var librarySettled by mutableStateOf(false)
        private set

    // ── 播放 ──────────────────────────────────────────────
    var queue by mutableStateOf(emptyList<DemoSong>())
        private set
    var queueIndex by mutableIntStateOf(0)
        private set

    /** 当前队列的播放来源展示名（曲库/歌单名/搜索…），播放页顶栏「来自」文案用 */
    var queueSource by mutableStateOf<String?>(null)
        private set
    var playing by mutableStateOf(true)
    var progressSec by mutableFloatStateOf(0f)
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

    // ── 定时关闭（服务端权威倒计时；本地只留回显状态，不持久化——重启失效合理）──
    /** 当前定时模式：0=无；>0=N 分钟；-1=播完当前首；-2=播完整个队列 */
    var sleepTimerMode by mutableIntStateOf(0)
        private set

    /** 分钟档到期时间戳（epoch ms；0=无分钟级定时）：UI 据此每秒刷新剩余时间 */
    var sleepTimerDeadlineMs by mutableLongStateOf(0L)
        private set

    // ── 收藏 / 歌单 ───────────────────────────────────────
    val favorites = mutableStateSetOf<String>()
    val userPlaylists = mutableStateListOf<DemoPlaylist>()

    /** 已删除（隐藏）的内建歌单 id：内建歌单是目录数据，"删除"= 隐藏并持久化，可在设置恢复 */
    val hiddenBuiltInPlaylists = mutableStateSetOf<String>()

    /** 「加入歌单」叠加层：playlistId → 追加的曲目 id（常驻歌单曲目不可变） */
    private val addedTracks = mutableStateMapOf<String, SnapshotStateList<String>>()

    // ── 设置 ──────────────────────────────────────────────
    var followCover by mutableStateOf(true)
    var lyricEnabled by mutableStateOf(true)
    var glassLevel by mutableStateOf(GlassIntensity.Standard)
    var motionLevel by mutableStateOf(MotionIntensity.Standard)
    var eqPreset by mutableStateOf(EqPreset.Off)
    var playbackSpeed by mutableFloatStateOf(1f) // 播放速度档（快照 v5 持久化；服务端 Sonic 变速不变调）
        private set
    var themeMode by mutableStateOf(ThemeMode.Dark)
    var songSort by mutableStateOf(SongSort.Title)
        private set
    val excludedFolders = mutableStateSetOf<String>()

    /** 扫描过滤：默认滤除短于 30 秒的音频（通知音/录音碎片）；切换后需在设置页手动重扫生效（快照 v6 持久化） */
    var scanFilterShort by mutableStateOf(true)

    /** 曲库页排序面板：歌曲 tab 排序（快照持久化） */
    fun selectSongSort(sort: SongSort) {
        songSort = sort
        scheduleSave()
    }

    /** 排序比较器缓存（键 = songSort）：Collator 实例化较重，切排序才重建（Sort.kt 只读，就地缓存） */
    private var sortComparatorCache: Pair<SongSort, Comparator<DemoSong>>? = null

    /** 歌曲 tab 内容：按当前排序（文本键中文拼音序；最近添加按 MediaStore 入库时间） */
    fun sortedSongs(): List<DemoSong> {
        val comparator = sortComparatorCache
            ?.takeIf { it.first == songSort }
            ?.second
            ?: songSort.comparator().also { sortComparatorCache = songSort to it }
        return library.sortedWith(comparator)
    }

    /** 右上角"太阳"按钮：深浅色切换 */
    fun toggleTheme() {
        themeMode = if (themeMode == ThemeMode.Dark) ThemeMode.Light else ThemeMode.Dark
        scheduleSave()
    }

    // ── 搜索 ──────────────────────────────────────────────
    var searchQuery by mutableStateOf("")
    val searchHistory = mutableStateListOf<String>() // 演示词已移除：真曲库下预置词只会搜出空结果

    // ── 派生 ──────────────────────────────────────────────

    /** 当前曲：队列为空（曲库未装载/无曲库）时回落占位曲，防越界 */
    val currentSong: DemoSong
        get() = queue.getOrElse(queueIndex.coerceAtLeast(0)) { DemoData.placeholder }
    val durationSec: Float get() = effectiveDurationSec()
    val progressFraction: Float get() = (progressSec / durationSec).coerceIn(0f, 1f)

    /** 有效时长（秒）：引擎装载着当前曲时以服务端实际时长为准——元数据缺失的扫描值兜底 1s
     *  不得钳死真实进度（tick 钳制 / 进度条 / seek 统一走这里）；其余场景用扫描值 */
    private fun effectiveDurationSec(): Float {
        val eng = engine
        val live = if (eng != null && engineLoadedUri != null && engineLoadedUri == currentSong.mediaUri) {
            eng.durationSec().takeIf { it > 0f }
        } else {
            null
        }
        return live ?: currentSong.durationSec.toFloat()
    }

    /** 极光取色：真封面取色优先 → 无封面回退占位风格 → 取色开关关闭时回退品牌靛紫 */
    val palette: AuroraPalette
        get() = if (followCover) {
            currentSong.palette ?: currentSong.art.palette
        } else {
            GlassPalettes.Purple
        }

    /**
     * 当前歌的歌词：队列为空（占位曲）不给词；有 uri 的真曲给 .lrc/内嵌词，
     * 没有则 null（播放页显示「纯音乐，请欣赏」空态）
     */
    val currentLyrics: List<LyricLine>?
        get() = when {
            queue.isEmpty() -> null
            currentSong.mediaUri == null -> null
            else -> currentSong.lyricLines
        }

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
                    // 服务队列即权威：按服务实际队列重建 UI 队列（不得偷换成整个曲库，
                    // 否则歌单/搜索语境的切歌顺序全错）
                    val serviceSongs = engine.mediaIds().mapNotNull { songById(it) }
                    if (serviceSongs.isNotEmpty()) queue = serviceSongs
                    queueIndex = queue.indexOfFirst { it.id == currentId }.coerceAtLeast(0)
                    engineLoadedUri = song.mediaUri
                    progressSec = engine.positionSec().coerceIn(0f, effectiveDurationSec())
                    playing = engine.isEnginePlaying()
                    // 服务在播，待恢复现场以服务为准
                    pendingPlayback = null
                    currentSongIdPending = null
                    resumePending = false
                }
                // 解析不到（曲库未装载 / 缓存过期 / 文件刚删）：服务正在播就绝不 stopAndClear 掐断，
                // 保持服务现状等 loadLibrary() 刷新 library 引用对齐；文件确已删除时服务端播放器自行报错
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
        var index = queue.indexOfFirst { it.id == mediaId }
        if (index < 0) {
            // UI 队列不含引擎当前曲（服务端权威，如挂起的恢复现场被服务抢先）：按服务队列重建
            val serviceSongs = engine?.mediaIds()?.mapNotNull { songById(it) } ?: return
            index = serviceSongs.indexOfFirst { it.id == mediaId }
            if (index < 0) return
            queue = serviceSongs
        }
        if (index != queueIndex) {
            queueIndex = index
            progressSec = 0f
            liveFormat = null
            rememberPlayed(mediaId)
            scheduleSave()
        }
    }

    /** 循环 / 随机 / 均衡器 / 播放速度 → 服务端播放器（切换即时下发；
     *  attachPlayer / prepareResume / startCurrent / restore 都经此补同步，速度随队列装载重下发） */
    private fun syncEngineModes(eng: PlayerEngine) {
        eng.setRepeatMode(repeatMode == RepeatMode.All, repeatMode == RepeatMode.One)
        eng.setShuffleMode(shuffleOn)
        eng.setEqPreset(eqPreset.name)
        eng.setPlaybackSpeed(playbackSpeed)
    }

    // ── 持久化：快照 / 恢复 / 存盘调度 ────────────────────

    /** 绑定存盘实现（GlassApp 启动时；传 null 解绑） */
    fun bindStore(store: SnapshotStore?) {
        this.store = store
    }

    fun scheduleSave() {
        if (!hydrated) return // 恢复完成前不落盘，防止默认演示状态覆盖磁盘快照
        val snap = snapshot()
        store?.schedule(snap)
        // C4：同步写一份「服务可读」的播放现场（specs + index + position），供进程被杀后
        // 系统媒体入口（通知/蓝牙重连）经 onPlaybackResumption 恢复——服务端读不了 DataStore，
        // 也不想让它反向依赖状态层，故用同 schema 的独立 JSON 文件做桥。
        // specs 由 AppState 侧构造（快照只存 id，无法反推标题/封面等字段）。
        queueStateWriter?.invoke(queue.mapNotNull { it.toSpec() }, snap.queueIndex, snap.progressSec)
    }

    /** 服务端可读的播放现场写出器（实现在 app 模块：写 files/queue_state.json）。
     *  与快照同一触发点，避免两套存盘时机不一致。
     *  注意：实现方必须自行切到 IO 线程——本函数在 UI tick 里被调（每 5s）。 */
    var queueStateWriter: ((specs: List<SongSpec>, index: Int, positionSec: Float) -> Unit)? = null

    /** 设置指纹：GlassApp 用 snapshotFlow 监听设置变更触发存盘 */
    fun settingsKey(): String =
        "$followCover|$lyricEnabled|${glassLevel.name}|${motionLevel.name}|${eqPreset.name}|${themeMode.name}|" +
            "$shuffleOn|${repeatMode.name}|$playbackSpeed|$scanFilterShort|" +
            excludedFolders.joinToString(",")

    fun snapshot(): AppStateSnapshot {
        // 播放现场尚未落地（pending 等 loadLibrary 解析）时，快照原样回写 pending——
        // 否则窗口期内的任何存盘都会用演示队列/进度覆盖磁盘上的真实现场
        val pending = pendingPlayback
        return if (pending != null) {
            AppStateSnapshot(
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
        hiddenBuiltInPlaylists = hiddenBuiltInPlaylists.toSet(),
                queueIds = pending.first,
                queueIndex = pending.second.first,
                progressSec = pending.second.second,
                currentSongId = currentSongIdPending,
                songSort = songSort.name,
                shuffleOn = shuffleOn,
                repeatMode = repeatMode.name,
                playbackSpeed = playbackSpeed,
                scanFilterShort = scanFilterShort,
            )
        } else {
            AppStateSnapshot(
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
                hiddenBuiltInPlaylists = hiddenBuiltInPlaylists.toSet(),
                queueIds = queue.map { it.id },
                queueIndex = queueIndex,
                progressSec = progressSec,
                currentSongId = currentSong.id,
                songSort = songSort.name,
                shuffleOn = shuffleOn,
                repeatMode = repeatMode.name,
                playbackSpeed = playbackSpeed,
                scanFilterShort = scanFilterShort,
            )
        }
    }

    /** 恢复快照：设置/收藏/历史/歌单立即生效；播放现场由 applyPendingPlayback 应用（幂等） */
    fun restore(snapshot: AppStateSnapshot) {
        followCover = snapshot.followCover
        lyricEnabled = snapshot.lyricEnabled
        glassLevel = runCatching { GlassIntensity.valueOf(snapshot.glassLevel) }.getOrDefault(GlassIntensity.Standard)
        motionLevel = runCatching { MotionIntensity.valueOf(snapshot.motionLevel) }.getOrDefault(MotionIntensity.Standard)
        eqPreset = runCatching { EqPreset.valueOf(snapshot.eqPreset) }.getOrDefault(EqPreset.Off)
        themeMode = runCatching { ThemeMode.valueOf(snapshot.themeMode) }.getOrDefault(ThemeMode.Dark)
        songSort = runCatching { SongSort.valueOf(snapshot.songSort) }.getOrDefault(SongSort.Title)
        shuffleOn = snapshot.shuffleOn
        repeatMode = runCatching { RepeatMode.valueOf(snapshot.repeatMode) }.getOrDefault(RepeatMode.Off)
        playbackSpeed = if (snapshot.playbackSpeed > 0f) snapshot.playbackSpeed else 1f
        scanFilterShort = snapshot.scanFilterShort // 旧版本（≤v5）无此字段：走默认值 true 零迁移
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
        hiddenBuiltInPlaylists.clear()
        hiddenBuiltInPlaylists.addAll(snapshot.hiddenBuiltInPlaylists)
        snapshot.userPlaylists.forEach { p ->
            if (p.trackIds.isNotEmpty()) {
                addedTracks.getOrPut(p.id) { mutableStateListOf() }.addAll(p.trackIds.filterNot { it in addedTracks[p.id]!! })
            }
        }
        pendingPlayback = snapshot.queueIds to (snapshot.queueIndex to snapshot.progressSec)
        currentSongIdPending = snapshot.currentSongId
        hydrated = true // 恢复完成：允许存盘
        // restore 可能晚于 attachPlayer 到达：循环/随机/EQ/速度就地补同步到服务端（否则引擎停留在默认模式）
        engine?.let { syncEngineModes(it) }
        // 播放现场不在此处应用：演示 id 不提前解析（防止消费掉真实快照），
        // 统一等 loadLibrary（真曲库装载）后由 applyPendingPlayback 收敛
    }

    private var resumePending = false // 播放现场已应用，等引擎就绪后装载（不自动播放）

    /** 应用待恢复的播放现场：解析不到的歌跳过；按快照里的当前曲 id 定位索引 */
    private fun applyPendingPlayback() {
        val (ids, position) = pendingPlayback ?: return
        val songs = ids.mapNotNull { songById(it) }
        if (songs.isEmpty()) {
            // 曲库已装载仍解析不到：现场引用的文件已全部失效，放弃等待（否则快照永远回写死数据）
            if (realLibraryLoaded) {
                pendingPlayback = null
                currentSongIdPending = null
            }
            return
        }
        pendingPlayback = null
        val savedSongId = currentSongIdPending
        currentSongIdPending = null
        queue = songs
        val resolvedIndex = savedSongId?.let { id -> songs.indexOfFirst { it.id == id } }?.takeIf { it >= 0 }
        queueIndex = when {
            resolvedIndex != null -> resolvedIndex
            // 当前曲 id 失效（文件被删）：宁可回到队首，不做旧索引的错位对齐
            savedSongId != null -> 0
            // 旧版快照没有 currentSongId 字段：按原索引兜底
            else -> position.first.coerceIn(0, songs.lastIndex)
        }
        progressSec = if (resolvedIndex != null || savedSongId == null) {
            position.second.coerceIn(0f, songs.getOrNull(queueIndex)?.durationSec?.toFloat() ?: durationSec)
        } else {
            0f
        }
        resumePending = true
        prepareResume()
    }

    /** 曲库不可用（权限拒绝/扫描失败）时放弃待恢复现场：此后按当前（演示）状态如实存盘 */
    fun abandonPendingPlayback() {
        pendingPlayback = null
        currentSongIdPending = null
    }

    /** 首次启动（磁盘无快照）或快照损坏：无现场需要保护，直接放行存盘。
     *  否则 hydrated 永远为 false，scheduleSave 全部空转，永远写不出第一份快照 */
    fun markHydrated() {
        hydrated = true
    }

    /** 扫描为空/失败且无缓存：确认无曲库（演示模式终态），放行启动门面 */
    fun markLibrarySettled() {
        // 终态再无解析机会：挂起的恢复现场就地放弃（否则 tick 演示分支被冻结 → 播放键假死、快照回写死数据）
        if (pendingPlayback != null) abandonPendingPlayback()
        librarySettled = true
    }

    /**
     * 装载真曲库（缓存/扫描共用）：library 整体切换；UI 队列按「服务队列权威」收敛、不偷换语境——
     * 服务在播按服务实际队列重建引用（保留歌单/搜索语境与进度）；有待恢复现场按快照幂等应用；
     * 对已装载曲库的重扫只刷新队列引用（剔除失效项）；全新装载才落整库默认值。
     */
    fun loadLibrary(songs: List<DemoSong>) {
        if (songs.isEmpty()) return
        val rescan = realLibraryLoaded // 本次装载前曲库已就位（重扫/缓存刷新，区别于全新装载）
        library = songs
        realLibraryLoaded = true
        librarySettled = true
        val eng = engine
        val currentId = eng?.currentMediaId()
        val playingSong = currentId?.let { id -> songs.firstOrNull { it.id == id } }
        if (playingSong != null) {
            // 服务正在播曲库中的歌：按服务实际队列重建引用（整库替换会偷换歌单/搜索的切歌顺序），
            // 保留当前曲与进度
            val serviceSongs = eng!!.mediaIds().mapNotNull { songById(it) }
            queue = serviceSongs.ifEmpty { songs } // 服务队列引用解析为空才退回整库
            queueIndex = queue.indexOfFirst { it.id == currentId }.coerceAtLeast(0)
            engineLoadedUri = playingSong.mediaUri
            progressSec = eng.positionSec().coerceIn(0f, effectiveDurationSec())
            playing = eng.isEnginePlaying()
            pendingPlayback = null
            currentSongIdPending = null
            resumePending = false
            return
        }
        when {
            pendingPlayback != null -> {
                // 有待恢复现场：先落整库默认值（现场解析全失败时自然退回整库空态），再幂等应用快照
                queue = songs
                queueIndex = 0
                progressSec = 0f
                resumePending = false
                applyPendingPlayback()
            }
            rescan -> {
                // 对已装载曲库的重扫：只刷新队列引用（剔除失效项），保留当前语境/进度/待装载恢复现场
                val refreshed = queue.mapNotNull { songById(it.id) }
                if (refreshed.isEmpty()) {
                    queue = songs
                    queueIndex = 0
                    progressSec = 0f
                    resumePending = false
                } else {
                    queue = refreshed
                    queueIndex = queueIndex.coerceIn(0, refreshed.lastIndex)
                }
            }
            else -> {
                // 全新装载：整库为默认队列
                queue = songs
                queueIndex = 0
                progressSec = 0f
                resumePending = false
            }
        }
        // playing 与服务实际一致：引擎空 = 未播放；引擎在则如实回读（重发恢复现场后为暂停态，
        // 服务在播解析不到的曲目时不误置 false）
        playing = eng?.isEnginePlaying() ?: false
        // 重扫不再清空最近播放（历史中的失效 id 在展示层自然过滤）
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
        } else if (pendingPlayback != null && !realLibraryLoaded) {
            // 恢复现场待落地：不推进演示定时器（避免夜航西飞假进度在启动窗口期乱走）
        } else {
            val next = progressSec + 0.1f
            if (next >= durationSec) handleTrackEnd() else progressSec = next
        }
        if (++saveTick >= 50) {
            saveTick = 0
            scheduleSave()
        }
    }

    /** 播放指定曲目；context 为其所在队列（同队列引用时仅切索引）；source 为播放来源展示名 */
    fun playSong(song: DemoSong, context: List<DemoSong> = queue, source: String? = null) {
        val queueChanged = context !== queue
        if (queueChanged) queue = context
        if (source != null) queueSource = source
        val index = context.indexOf(song).coerceAtLeast(0)
        if (!queueChanged && index == queueIndex && song.mediaUri != null && engineLoadedUri == song.mediaUri) {
            // 同曲已装载且队列未变：不重载，只补同步循环/随机等模式并确保播放
            engine?.let { syncEngineModes(it) }
            engine?.play()
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
        queueIndex = (if (queueIndex > 0) queueIndex - 1 else queue.lastIndex).coerceAtLeast(0)
        startCurrent()
        rememberPlayed(currentSong.id)
        scheduleSave()
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

    /** 引擎装载元数据（null = 无 uri 的曲目，不进服务端队列） */
    private fun DemoSong.toSpec(): SongSpec? = mediaUri?.let { uri ->
        SongSpec(
            id = id,
            uri = uri,
            title = title,
            artist = artist,
            album = album,
            artworkUri = artworkUri,
            durationMs = durationSec * 1000L,
        )
    }

    /** 把当前队列交给服务端播放器（混合队列时演示曲被剔除，按 mediaId 重新定位起始索引） */
    private fun loadQueueOnEngine(eng: PlayerEngine, autoPlay: Boolean, startSec: Float = 0f) {
        val specs = queue.mapNotNull { it.toSpec() }
        val startIndex = if (specs.size == queue.size) {
            queueIndex
        } else {
            specs.indexOfFirst { it.id == currentSong.id }.coerceAtLeast(0)
        }
        eng.loadQueue(specs, startIndex, startSec, autoPlay)
    }

    /** 当前曲格式展示串：优先播放中实时参数，回退扫描值（格式化逻辑统一在 songFormatText） */
    val currentFormatText: String
        get() = songFormatText(currentSong, liveFormat)

    fun toggleShuffle() {
        shuffleOn = !shuffleOn
        engine?.setShuffleMode(shuffleOn)
        scheduleSave()
    }

    /** 均衡器预设：立即下发服务端（Off = 关闭音效），不等下一首歌装载。
     *  命名避开 var eqPreset 的 JVM setter（setEqPreset）签名冲突 */
    fun selectEqPreset(preset: EqPreset) {
        eqPreset = preset
        engine?.setEqPreset(preset.name)
        scheduleSave()
    }

    /** 播放速度：立即下发服务端（Media3 原生 Sonic 变速不变调）。
     *  命名避开 var playbackSpeed 的 JVM setter（setPlaybackSpeed）签名冲突 */
    fun selectPlaybackSpeed(speed: Float) {
        playbackSpeed = speed
        engine?.setPlaybackSpeed(speed)
        scheduleSave()
    }

    /** 定时关闭：下发服务端（服务端权威到点暂停）+ 记录本地回显状态。不持久化（重启失效合理） */
    fun setSleepTimer(modeMinutes: Int) {
        engine?.setSleepTimer(modeMinutes)
        sleepTimerMode = modeMinutes
        sleepTimerDeadlineMs = if (modeMinutes > 0) System.currentTimeMillis() + modeMinutes * 60_000L else 0L
    }

    /** 生效中的定时模式（面板高亮 / 设置副标题用）：分钟档过了期限视为结束——
     *  服务端到点自行暂停且无回执，本地按期限推导归零 */
    fun effectiveSleepTimerMode(): Int {
        val mode = sleepTimerMode
        if (mode > 0 && System.currentTimeMillis() >= sleepTimerDeadlineMs) return 0
        return mode
    }

    /** 分钟级定时的剩余秒数（非分钟档 = null；已到期钳 0）：UI 每秒读一次驱动倒计时刷新 */
    fun sleepTimerRemainingSec(): Int? {
        if (sleepTimerMode <= 0 || sleepTimerDeadlineMs <= 0L) return null
        return ((sleepTimerDeadlineMs - System.currentTimeMillis()) / 1000L).toInt().coerceAtLeast(0)
    }

    fun cycleRepeat() {
        repeatMode = when (repeatMode) {
            RepeatMode.Off -> RepeatMode.All
            RepeatMode.All -> RepeatMode.One
            RepeatMode.One -> RepeatMode.Off
        }
        engine?.setRepeatMode(repeatMode == RepeatMode.All, repeatMode == RepeatMode.One)
        scheduleSave()
    }

    // ── 队列编辑（下一首播放 / 最后播放 / 重排 / 删项 / 清空接下来）───────────
    //
    // 引擎活跃时 UI 队列与引擎队列一一对应（真曲库歌曲均有 mediaUri；演示模式引擎非活跃，
    // mutator 走纯本地列表）。重扫等场景可能造成两侧分叉：编辑前先 alignQueueWithEngine()
    // 收敛（服务队列权威，与 syncFromEngine / attachPlayer 同源原则），收敛失败则放弃引擎侧
    // 增量操作、只改 UI 列表，等下一次曲目切换自然回流。

    /** 引擎驱动真实播放中（区别于演示定时器模式）——队列编辑与播放行为的共用判定 */
    private fun engineDriving(): Boolean =
        engine != null && engineLoadedUri != null && currentSong.mediaUri != null

    /** 服务队列权威：引擎活跃时确保 UI 队列与引擎队列同源（分叉在此收敛到服务实际队列）。
     *  返回 false = 引擎队列引用存在 library 解析不到的曲目，放弃引擎侧增量编辑 */
    private fun alignQueueWithEngine(): Boolean {
        val eng = engine ?: return false
        val ids = eng.mediaIds()
        if (ids == queue.map { it.id }) return true
        val aligned = ids.mapNotNull { songById(it) }
        if (aligned.size != ids.size) return false
        queue = aligned
        queueIndex = ids.indexOf(eng.currentMediaId()).coerceAtLeast(0)
        return true
    }

    /** 下一首播放：插入到当前曲之后（重复添加允许） */
    fun playNext(song: DemoSong) = insertQueueItems(listOf(song), queueIndex + 1, pinLast = false)

    /** 最后播放：追加到队列末尾 */
    fun playLast(song: DemoSong) = insertQueueItems(listOf(song), queue.size, pinLast = true)

    /** 集合版下一首播放（详情页 / 卡片菜单用），保持给定顺序 */
    fun playNextAll(songs: List<DemoSong>) = insertQueueItems(songs, queueIndex + 1, pinLast = false)

    /** 集合版最后播放 */
    fun playLastAll(songs: List<DemoSong>) = insertQueueItems(songs, queue.size, pinLast = true)

    private fun insertQueueItems(songs: List<DemoSong>, at: Int, pinLast: Boolean) {
        if (songs.isEmpty()) return
        val engineActive = engineDriving() && alignQueueWithEngine()
        val index = at.coerceIn(0, queue.size)
        // 显式下发插入意图（playNext/playLast），shuffle 下服务端据此固定位置，不按 index 猜
        if (engineActive) engine!!.addQueueItems(index, songs.mapNotNull { it.toSpec() }, pinLast)
        queue = queue.toMutableList().apply { addAll(index, songs) }
        scheduleSave()
    }

    /** 删除队列单曲（仅可删非当前曲；UI 置灰当前曲，此处兜底拒绝） */
    fun removeQueueItem(index: Int) {
        if (index == queueIndex || index !in queue.indices) return
        if (engineDriving() && alignQueueWithEngine()) engine!!.removeQueueItems(index, index + 1)
        queue = queue.toMutableList().apply { removeAt(index) }
        if (index < queueIndex) queueIndex--
        scheduleSave()
    }

    /** 队列内拖动重排：from → to（语义 = Kotlin removeAt(from) 后在 to 位插回） */
    fun moveQueueItem(from: Int, to: Int) {
        if (from == to || from !in queue.indices || to !in queue.indices) return
        if (engineDriving() && alignQueueWithEngine()) engine!!.moveQueueItem(from, to)
        queue = queue.toMutableList().apply { add(to, removeAt(from)) }
        queueIndex = when {
            queueIndex == from -> to
            from < queueIndex && to >= queueIndex -> queueIndex - 1
            from > queueIndex && to <= queueIndex -> queueIndex + 1
            else -> queueIndex
        }
        scheduleSave()
    }

    /** 清空「接下来」：保留已播前缀与当前曲，移除其后全部 */
    fun clearUpNext() {
        if (queueIndex >= queue.lastIndex) return
        if (engineDriving() && alignQueueWithEngine()) engine!!.removeQueueItems(queueIndex + 1, queue.size)
        queue = queue.take(queueIndex + 1)
        scheduleSave()
    }

    // ── 收藏 / 歌单 ───────────────────────────────────────

    fun isFavorite(id: String): Boolean = id in favorites

    /** 我喜欢的有效曲目（过滤失效 id——如已移除的演示曲遗留收藏） */
    val resolvedFavorites: List<DemoSong>
        get() = favorites.mapNotNull { songById(it) }

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
        if (id.startsWith("user-")) {
            userPlaylists.removeAll { it.id == id }
            addedTracks.remove(id)
        } else if (DemoCatalog.playlist(id) != null) {
            hiddenBuiltInPlaylists.add(id) // 内建歌单：删除即隐藏（持久化，可在设置恢复）
        }
        scheduleSave()
    }

    /** 可见内建歌单（未被删除的）：歌单墙 / 曲库歌单 tab / 加入歌单面板统一走这里。
     *  经 resolvePlaylist 实时化计数（加歌后不再恒「0 首」；目录静态 meta 仅兜底） */
    fun visibleBuiltInPlaylists(): List<DemoPlaylist> =
        DemoCatalog.playlists.filter { it.id !in hiddenBuiltInPlaylists }
            .map { resolvePlaylist(it.id) ?: it }

    /** 设置页「恢复内置歌单」：清空隐藏集 */
    fun restoreBuiltInPlaylists() {
        hiddenBuiltInPlaylists.clear()
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

    /** 集合播放（详情页 / 卡片菜单统一入口）：随机 = 从随机一首开始；无曲目忽略 */
    fun playCollection(tracks: List<DemoSong>, source: String?, shuffle: Boolean) {
        if (tracks.isEmpty()) return
        shuffleOn = shuffle
        playSong(if (shuffle) tracks.random() else tracks.first(), tracks, source)
    }

    // ── 歌单编辑（移除 / 重排 / 重命名，仅自建歌单；内建基座与动态歌单只读）──

    /** 从歌单移除曲目：仅自建歌单（addedTracks 叠加层）；返回是否真正移除 */
    fun removeFromPlaylist(playlistId: String, songId: String): Boolean {
        if (!playlistId.startsWith("user-")) return false
        val list = addedTracks[playlistId] ?: return false
        val removed = list.remove(songId)
        if (removed) {
            syncPlaylistMeta(playlistId)
            scheduleSave()
        }
        return removed
    }

    /** 歌单内拖动重排：仅自建歌单（addedTracks 顺序即曲目序，快照原样持久化） */
    fun movePlaylistTrack(playlistId: String, from: Int, to: Int) {
        if (!playlistId.startsWith("user-") || from == to) return
        val list = addedTracks[playlistId] ?: return
        if (from !in list.indices || to !in list.indices) return
        list.add(to, list.removeAt(from))
        scheduleSave()
    }

    /** 重命名歌单：仅自建歌单；空白名忽略 */
    fun renamePlaylist(id: String, title: String) {
        val newTitle = title.trim()
        if (newTitle.isEmpty() || !id.startsWith("user-")) return
        val index = userPlaylists.indexOfFirst { it.id == id }
        if (index < 0) return
        userPlaylists[index] = userPlaylists[index].copy(title = newTitle)
        scheduleSave()
    }

    /** 歌单详情页可重排判定：自建歌单且展示列表与 addedTracks 一一对应（无失效引用剔除） */
    fun canReorderPlaylist(playlistId: String): Boolean =
        playlistId.startsWith("user-") &&
            resolvePlaylist(playlistId)?.tracks?.size == addedTracks[playlistId]?.size

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

    /** 流派分组：空值归一为「未知流派」（扫描真值为空的歌不落「独立」，统一在此展示归一，
     *  详情入口 target.id 与分组键同源） */
    fun genreGroups(): List<LibraryGroup> =
        library.groupBy { if (it.genre.isBlank()) "未知流派" else it.genre }
            .map { LibraryGroup(it.key, it.value) }.sortedBy { it.key }

    /** 搜索范围：真曲库装载后只搜真曲库；演示模式搜全量演示曲（含「仅搜索可见」的单曲/伴奏） */
    private fun searchScope(): List<DemoSong> = if (realLibraryLoaded) library else DemoData.songs

    fun searchSongs(query: String): List<DemoSong> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        return searchScope().filter {
            it.title.contains(q, true) || it.artist.contains(q, true) || it.album.contains(q, true)
        }
    }

    fun searchAlbumGroups(query: String): List<LibraryGroup> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        return searchScope().groupBy { it.album }.map { LibraryGroup(it.key, it.value) }.filter { g ->
            g.key.contains(q, true) || g.songs.first().artist.contains(q, true) ||
                g.songs.any { it.title.contains(q, true) }
        }.sortedBy { it.key }
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
        if (id in hiddenBuiltInPlaylists) return null // 已删除的内建歌单：详情兜底退回歌单页
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
        // 用户歌单与常驻歌单的曲目数都实时化（常驻基座 + 加入歌单叠加后的真实数量）
        return merged.copy(meta = "${merged.tracks.size} 首")
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
