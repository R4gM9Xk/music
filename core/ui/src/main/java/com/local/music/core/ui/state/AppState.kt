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
import androidx.compose.ui.graphics.Color
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
    val playCounts: Map<String, Int>? = null, // 播放次数（v7 起编码）；null = 备份缺该字段（≤v6 旧备份），恢复时保留现有不清零
    val customSeedArgb: Int? = null, // 自定义主题色（v7 起编码；null = 未自定义）
    val queueSource: String? = null, // 队列语境标签（v8 起编码；null = 整库/未知，播放页「来自『…』」展示用）
    val sleepTimerMode: Int = 0, // 定时关闭模式（v9 起编码；Activity 重建后回显用）
    val sleepTimerDeadlineMs: Long = 0L, // 分钟档到期时间戳（v9 起编码）
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
    // 文案必须与服务端语义一致：「播完当前首」只认自然完播，用户手动切歌/拖动进度都不暂停
    // （服务端只对 REASON_AUTO 暂停；UI 侧手动切歌还会把定时撤销）。
    // 原副标题「当前曲播完后暂停」会让用户以为手动切歌也会停 —— 用户视角这就是个 bug。
    option == -1 -> "当前曲自然播完后暂停 · 手动切歌不触发"
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
    /** 引擎自动切换（完播/自动下一首）回调：仅用于播放次数统计（D1）——手动切歌不计 */
    var onAutoAdvanced: ((String?) -> Unit)?
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

    /** 首曲封面地址：扫描不再预解码全库封面，卡片封面改为按需解码（ArtworkBitmapCache） */
    val coverUri get() = songs.first().artworkUri
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
    // 初值是曲库页：旧实现是 Player，于是首装权限被拒 / 无音频的用户启动后直接看到
    // 一个没有 Chrome、没有「去授权」入口的占位播放页，必须自己按返回才找得到出路。
    var screen by mutableStateOf(GlassScreen.Library)
    var detail by mutableStateOf(DetailTarget(DetailTarget.Kind.Playlist, DemoCatalog.playlists.first().id))

    /** 详情页返回来源：从搜索/曲库进详情，返回时回到原页面而非固定歌单 */
    var detailReturn by mutableStateOf(GlassScreen.Playlist)
        private set

    /** 详情页自绘 Chrome 的底部导航高亮位：跟随「进入详情时的来源屏」，
     *  而不是硬编码 1（歌单位）——从曲库进专辑详情、或从搜索进详情时，
     *  硬编码会让导航一直亮着「歌单」，与用户认知的来源页不符（返回键反倒是正确的：
     *  它走 detailReturn）。取值语义与 GlassApp 的 tab 序一致：0 曲库 / 1 歌单 / 2 设置。 */
    val detailNavSelected: Int
        get() = when (detailReturn) {
            GlassScreen.Playlist -> 1
            GlassScreen.Settings -> 2
            else -> 0
        }

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
    /** 是否正在播放。
     *  **初值必须是 false**：旧实现是 true，于是「无曲库 / 权限拒绝」设备启动瞬间会按播放态
     *  渲染悬浮 Chrome 的波形与走动进度线（占位曲时长 1s，tick 约 1s 后走完才熄火），
     *  同时把「playCounts["placeholder"] += 1」写进快照。真实播放态只由 startCurrent /
     *  attachPlayer 回读 / loadLibrary 三处写入，不依赖初值。 */
    var playing by mutableStateOf(false)
    var progressSec by mutableFloatStateOf(0f)
        private set
    var shuffleOn by mutableStateOf(false)
    var repeatMode by mutableStateOf(RepeatMode.Off)
    val playedIds = mutableStateListOf<String>() // 最近播放（新→旧，按 id 去重）
    /** 播放次数（id → 累计完播次数）：D1 最常播放的依据；快照 v7 持久化 */
    val playCounts = mutableStateMapOf<String, Int>()

    /** 播放中实时格式（ExoPlayer Format 回流）；null 时展示扫描值 */
    var liveFormat by mutableStateOf<SongFormat?>(null)
        private set

    /** 播放错误（文件丢失 / 格式不支持等）；非 null 时 UI 显示可关闭的提示条 */
    var playbackError by mutableStateOf<String?>(null)
        private set

    // ── 定时关闭（服务端权威倒计时；本地回显状态，**随快照持久化**）──
    //
    // 为什么必须持久化：服务端定时器独立存活（不随 Activity 销毁），而 AppState 在
    // Activity 重建（改字体大小/语言——manifest configChanges 未吞 fontScale/locale）时
    // 会被 remember{} 重建。旧实现不持久化，于是重建后 UI 显示「未开启」、15 分钟后
    // 音乐却自己停了，用户完全不知道原因。
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
    /** 跟随封面取色。与 [customSeedArgb] **互斥**：打开即取消自定义色（见 [applyFollowCover]） */
    var followCover by mutableStateOf(true)
        private set
    /** F2 自定义主题色（ARGB；null = 未自定义，走「跟随封面 / 品牌」二态）；快照 v7 持久化 */
    var customSeedArgb by mutableStateOf<Int?>(null)
        private set

    /**
     * 设置自定义主题色（null = 清除自定义，回到跟随封面/品牌）。
     *
     * **互斥语义**：设了自定义色就自动关掉 followCover——旧实现里 customSeedArgb 非空会
     * 短路 palette 计算，导致「跟随封面取色」开关拨到哪边都毫无视觉效果，副标题
     * 「极光背景取自当前专辑封面」成了假话。现在两者严格二选一，开关始终真实生效。
     */
    fun setCustomSeed(argb: Int?) {
        customSeedArgb = argb
        if (argb != null) followCover = false
        scheduleSave()
    }

    /**
     * 切换「跟随封面取色」。**打开时清除自定义色**（回到跟随封面），关闭时回落到品牌靛紫。
     * 这样开关的两个方向都有确定的视觉结果，不再出现「拨了没反应」。
     *
     * 命名避开 `var followCover` 的 JVM setter（`setFollowCover(Z)V`）——
     * Kotlin 会把同名函数与属性 setter 编成同一签名，直接报 Platform declaration clash
     * 让整个模块编译失败（与 [setCustomSeed]/[applyPlaybackSpeed] 同一约定）。
     */
    fun applyFollowCover(enabled: Boolean) {
        followCover = enabled
        if (enabled) customSeedArgb = null
        scheduleSave()
    }
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

    /**
     * 扫描过滤：滤除短于 [MIN_TRACK_SEC] 的音频（通知音/录音碎片）；快照 v6 持久化。
     *
     * 切换**立即**影响可见曲库（[visibleLibrary] 是本开关的输出层），不再要求重扫：
     * 缓存/扫描结果始终是全量的（B3 两层分离），过滤只决定「展示哪些」。
     */
    var scanFilterShort by mutableStateOf(true)

    /** 切换扫描过滤开关（持久化；可见曲库由 [visibleLibrary] 即时重算，无需重扫） */
    fun toggleScanFilter() {
        scanFilterShort = !scanFilterShort
        scheduleSave()
    }

    /** 曲库页排序面板：歌曲 tab 排序（快照持久化） */
    fun selectSongSort(sort: SongSort) {
        songSort = sort
        scheduleSave()
    }

    /** 排序比较器缓存（键 = songSort）：Collator 实例化较重，切排序才重建（Sort.kt 只读，就地缓存） */
    private var sortComparatorCache: Pair<SongSort, Comparator<DemoSong>>? = null

    /** 歌曲 tab 内容：按当前排序（文本键中文拼音序；最近添加按 MediaStore 入库时间）。
     *  走 [visibleLibrary]：被「过滤文件夹」排除的目录不会出现在歌曲 tab，也不会进整库播放队列。 */
    fun sortedSongs(): List<DemoSong> {
        val comparator = sortComparatorCache
            ?.takeIf { it.first == songSort }
            ?.second
            ?: songSort.comparator().also { sortComparatorCache = songSort to it }
        return visibleLibrary().sortedWith(comparator)
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

    /** 是否有可播放的曲目（队列非空）：空曲库/权限拒绝终态下所有播放推进都必须停手，
     *  否则占位曲会被 tick 当成 1 秒的演示曲「播完」→ 触发 handleTrackEnd → 给
     *  "placeholder" 记一次播放次数并写进快照（脏计数，且波形/进度线假装在播） */
    val hasSongs: Boolean get() = queue.isNotEmpty()

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
        get() {
            // F2 主题色三态，但 followCover 与 customSeedArgb 严格互斥（见 setCustomSeed/applyFollowCover）：
            // 自定义色存在 ⇔ followCover 为 false，所以这个分支顺序不会掩盖开关的作用。
            // 仍保留 customSeedArgb 优先，是为了兼容「旧备份里两者同时为真」的历史数据——
            // 那种情况下以显式选过的颜色为准（restore 后会经 applyFollowCover 语义归一）。
            customSeedArgb?.let { return AuroraPalette.fromSeed(Color(it)) }
            return if (followCover) {
                currentSong.palette ?: currentSong.art.palette
            } else {
                GlassPalettes.Purple
            }
        }

    /** 当前生效的主题色 seed（主题色面板高亮用）：三态都折算成一个具体颜色 */
    val effectiveSeed: Color
        get() = customSeedArgb?.let { Color(it) } ?: palette.seed

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
                // 定时关闭播完档（-1/-2）以「播放停止」为使命完成信号：服务端到点暂停会走到这里，
                // 就地归零回显状态（服务端无回执，UI 侧自行推断）；用户手动暂停视为放弃定时
                // （重新设置即可）；分钟档（>0）不受影响，仍按 deadline 期限推导。
                // **归零必须同时下发服务端**：只清 UI 会让 stopAfterCurrent 变成孤儿旗标——
                // 面板显示「未开启定时」，但服务端仍 armed，恢复播放后当前曲播完的瞬间会被
                // 无故暂停（用户视角：明明取消了定时却还是停了）。旗标无回执，只能主动撤销。
                if (!isPlaying && sleepTimerMode < 0) {
                    sleepTimerMode = 0
                    sleepTimerDeadlineMs = 0L
                    engine?.setSleepTimer(0) // 撤销服务端「播完当前首/整队」旗标
                }
                scheduleSave()
            }
        }
        engine.onFormatChanged = { liveFormat = it }
        engine.onMediaItemChanged = { mediaId -> syncFromEngine(mediaId) }
        // 完播计数（D1）：引擎报告「自动切换到下一首」时才 +1（手动切歌不计数）
        engine.onAutoAdvanced = { finishedId -> finishedId?.let { bumpPlayCount(it) } }
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
        //
        // **节流**：tick 每 5s 调一次本函数，而曲库 tab 播放是把整个排序曲库当队列
        // （LibraryScreen 传 songs）——万首曲库即每次约 1MB 的 SongSpec 列表 + JSON 分配，
        // 稳态每 5s 一次纯属浪费（GC 压力与闪存写放大，而播放位置 20s 粒度对续播完全够用）。
        // 只在「队列/索引变了、或距上次写超过 QUEUE_STATE_MIN_INTERVAL_MS」时才写；
        // 暂停态恢复播放位置不再变化，此时 scheduleSave 由用户行为触发（低频），直接放行
        // 以保证「刚暂停就杀进程」也能恢复到准确位置。
        if (shouldWriteQueueState(snap.queueIndex)) {
            // specs 是**过滤后**的列表（toSpec() 对 mediaUri == null 返回 null），而 snap.queueIndex
            // 是未过滤 queue 的下标——两者混用会让续播从错位的曲目开始（偏移 = 被剔除条数）。
            // 与 loadQueueOnEngine 同口径：条目数一致时直接用原索引，否则按当前曲 id 重查。
            val specs = queue.mapNotNull { it.toSpec() }
            val specIndex = if (specs.size == queue.size) {
                snap.queueIndex
            } else {
                specs.indexOfFirst { it.id == currentSong.id }.coerceAtLeast(0)
            }
            queueStateWriter?.invoke(specs, specIndex, snap.progressSec)
            lastQueueStateQueue = queue
            lastQueueStateIndex = snap.queueIndex
            lastQueueStateAtMs = System.currentTimeMillis()
        }
    }

    // 播放现场节流状态（见 scheduleSave 注释）：队列引用 + 索引 + 上次写出时刻
    private var lastQueueStateQueue: List<DemoSong>? = null
    private var lastQueueStateIndex: Int = -1
    private var lastQueueStateAtMs: Long = 0L

    private fun shouldWriteQueueState(index: Int): Boolean {
        if (queueStateWriter == null) return false
        if (!hasSongs) return false // 空队列终态：不写（占位曲的现场没有恢复价值）
        // 队列换了（整库重扫/切换语境）或索引变了：立即写
        if (queue !== lastQueueStateQueue || index != lastQueueStateIndex) return true
        // 暂停/未播放：位置不再漂移，写一次是廉价的且能保住精确续播点
        if (!playing) return true
        return System.currentTimeMillis() - lastQueueStateAtMs >= QUEUE_STATE_MIN_INTERVAL_MS
    }

    /** 服务端可读的播放现场写出器（实现在 app 模块：写 files/queue_state.json）。
     *  与快照同一触发点，避免两套存盘时机不一致。
     *  注意：实现方必须自行切到 IO 线程——本函数在 UI tick 里被调（每 5s）。 */
    var queueStateWriter: ((specs: List<SongSpec>, index: Int, positionSec: Float) -> Unit)? = null

    /** 设置指纹：GlassApp 用 snapshotFlow 监听设置变更触发存盘 */
    fun settingsKey(): String =
        "$followCover|$lyricEnabled|${glassLevel.name}|${motionLevel.name}|${eqPreset.name}|${themeMode.name}|" +
            "$shuffleOn|${repeatMode.name}|$playbackSpeed|$scanFilterShort|$customSeedArgb|${queueSource.orEmpty()}|" +
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
                playCounts = playCounts.toMap(),
                customSeedArgb = customSeedArgb,
                queueSource = queueSource,
                sleepTimerMode = sleepTimerMode,
                sleepTimerDeadlineMs = sleepTimerDeadlineMs,
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
                playCounts = playCounts.toMap(),
                customSeedArgb = customSeedArgb,
                queueSource = queueSource,
                sleepTimerMode = sleepTimerMode,
                sleepTimerDeadlineMs = sleepTimerDeadlineMs,
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
        // 旧备份（≤v6）缺 playCounts 字段（null）：保留用户现有播放次数，不用空表覆盖清零
        if (snapshot.playCounts != null) {
            playCounts.clear()
            playCounts.putAll(snapshot.playCounts)
        }
        customSeedArgb = snapshot.customSeedArgb // 旧版本（≤v6）无此字段：null 零迁移
        queueSource = snapshot.queueSource // 旧版本（≤v7）无此字段：null 零迁移（「来自『…』」语境标签）
        // 定时关闭回显（v9）：分钟档仅在 deadline 未过期时恢复；播完档（-1/-2）依赖服务端旗标，
        // 进程重启后服务端旗标也已丢失，所以一律丢弃（避免 UI 显示「已开启」但实际不生效）。
        sleepTimerMode = when {
            snapshot.sleepTimerMode > 0 && snapshot.sleepTimerDeadlineMs > System.currentTimeMillis() ->
                snapshot.sleepTimerMode
            else -> 0
        }
        sleepTimerDeadlineMs = if (sleepTimerMode > 0) snapshot.sleepTimerDeadlineMs else 0L
        // 互斥语义归一：旧备份可能同时有 followCover=true 与 customSeedArgb 非空
        if (customSeedArgb != null) followCover = false
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
        // 分钟档定时随快照恢复后，把服务端定时器重新武装（服务端定时器不持久化，必须重下发）
        if (sleepTimerMode > 0) engine?.setSleepTimer(sleepTimerMode)
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

    /** 应用刚恢复的播放现场（备份恢复入口专用）：运行时曲库已装载时 loadLibrary 不会再调，
     *  pending 现场无人消费——主动触发 applyPendingPlayback 就地收敛（保持其幂等 /
     *  realLibraryLoaded 门控语义；引擎未连接时经 resumePending / pendingStart 既有补发机制闭环）。
     *
     *  **播放中恢复必须接管引擎**：prepareResume 首行是 `if (engineLoadedUri != null) return`，
     *  播放中恒成立 → 旧现场永远不会被替换：UI 队列/进度显示备份里的歌，音频却还在播旧队列
     *  （进度条拿引擎位置套新歌时长，串台），下一次切歌 syncFromEngine 又把队列卷回旧现场，
     *  备份的播放现场等于静默丢失。正确做法是先停播并清空引擎现场（旧歌必须真的停），
     *  之后 prepareResume 才能把备份队列装载为「暂停待播」态。 */
    fun applyRestoredPlayback() {
        if (engineLoadedUri != null) {
            engine?.stopAndClear()
            engineLoadedUri = null
            liveFormat = null
            playing = false
            resumePending = false
        }
        applyPendingPlayback()
        scheduleSave()
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

    /**
     * P1-②：用户确认「放弃损坏快照、以当前状态重建」后放行存盘。
     *
     * 与直接调 [markHydrated] 的区别只有一处：这里**先**把待恢复现场与队列一起收敛掉。
     * 场景是宿主读到损坏快照 → 保持 hydrated=false（不覆盖磁盘）→ 用户在设置页点确认。
     * 此时内存里还是默认设置 + 服务端恢复出来的播放现场（queue_state.json 那条链路）：
     * 若只放行存盘，第一次 tick 落盘的是「默认设置 + 真实播放现场」的混合体——
     * 正是 P1-② 报告里描述的那种半截快照。所以这里显式收敛成一份完整、自洽的新状态。
     */
    fun rebuildFromCurrentState() {
        // 待恢复现场已无对应设置可校验（快照损坏）：就地放弃，避免半截现场写盘
        abandonPendingPlayback()
        hydrated = true
        scheduleSave()
    }

    /** 扫描为空/失败且无缓存：确认无曲库（演示模式终态），放行启动门面 */
    fun markLibrarySettled() {
        // 终态再无解析机会：挂起的恢复现场就地放弃（否则 tick 演示分支被冻结 → 播放键假死、快照回写死数据）
        if (pendingPlayback != null) abandonPendingPlayback()
        librarySettled = true
    }

    /**
     * 扫描**失败**（MediaStore 瞬时错误 / 权限竞态）时放行启动门面，但**保留**待恢复现场。
     *
     * 与 [markLibrarySettled] 的区别是唯一但关键：后者认为「已确认没有曲库」，于是把
     * pendingPlayback 一并丢掉——一次瞬时失败就把用户的播放现场永久抛弃（之后重扫成功
     * 曲库会回来，队列却回不来了）。失败是可重试的中间态，pending 必须活下来：
     * 保留后 tick 的演示分支仍被 pending 冻结（不会乱走假进度），下次 loadLibrary 成功时
     * 由 applyPendingPlayback 正常消费；若后续真的扫到 0 首，走 markLibrarySettled 收终态。
     */
    fun settleLibraryKeepingPending() {
        librarySettled = true
        scheduleSave()
    }

    /**
     * 装载真曲库（缓存/扫描共用）：library 整体切换；UI 队列按「服务队列权威」收敛、不偷换语境——
     * 服务在播按服务实际队列重建引用（保留歌单/搜索语境与进度）；有待恢复现场按快照幂等应用；
     * 对已装载曲库的重扫只刷新队列引用（剔除失效项）；全新装载才落整库默认值。
     */
    fun loadLibrary(songs: List<DemoSong>) {
        // 空库必须真的落空态：旧实现 `if (songs.isEmpty()) return` 让 library 一旦非空就
        // 本进程内永不可清空——用户删光音乐后重扫，曲库页仍列着全部已删歌、点播报「文件已丢失」，
        // 而缓存已被写成空数组（GlassApp 的 isSuccess 分支），UI 与磁盘分叉，只能杀进程重启自愈。
        // 现在：空列表同样装载（library 换空引用），并把队列/进度一并归零。
        val rescan = realLibraryLoaded // 本次装载前曲库已就位（重扫/缓存刷新，区别于全新装载）
        library = songs
        realLibraryLoaded = true
        librarySettled = true
        // 空库终态（删光音乐 / 全部被过滤 / 首次扫描无结果）：队列与播放态一并归零，
        // 否则 currentSong 会落到占位曲而 playing 仍为 true（假播放 + 脏计数）。
        // 这里必须早于下面所有分支——空库时那些分支只会把 queue 设成空列表。
        if (songs.isEmpty()) {
            queue = emptyList()
            queueIndex = 0
            progressSec = 0f
            playing = false
            engineLoadedUri = null
            resumePending = false
            pendingPlayback = null
            currentSongIdPending = null
            // 服务里可能还挂着已删除文件的队列：停掉，避免通知栏继续显示幽灵曲目
            engine?.stopAndClear()
            scheduleSave()
            return
        }
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
        // 空队列终态（无曲库/权限拒绝）：playing 初值 true，若不在此拦下，占位曲会被当成
        // 1 秒的演示曲「播完」→ handleTrackEnd → bumpPlayCount("placeholder") 把脏计数写进快照，
        // 期间悬浮 Chrome 还会显示「暂无歌曲 · 未扫描」+ 进度线走动 + 波形按播放态渲染。
        // 这里直接熄火并把 playing 归 false，让 UI 如实呈现「没有在播」。
        if (!hasSongs) {
            playing = false
            return
        }
        val eng = engine
        if (eng != null && engineLoadedUri != null && currentSong.mediaUri != null) {
            progressSec = eng.positionSec().coerceIn(0f, durationSec)
        } else if (pendingPlayback != null && !realLibraryLoaded) {
            // 恢复现场待落地：不推进演示定时器（避免夜航西飞假进度在启动窗口期乱走）
        } else {
            val next = progressSec + 0.1f
            if (next >= durationSec) handleTrackEnd() else progressSec = next
        }
        // 播放进度存盘：每 20s 一次（原为 5s）。
        // 快照是整份 JSON（歌单/收藏/播放次数全在里面），播放中每 5s 重写一次纯属写放大：
        // DataStore 每次都要序列化整份快照并原子替换文件，曲库大 + 歌单多时是几百 KB 级写盘。
        // 进度本身只服务「进程被杀后恢复现场」，20s 误差对续播完全无感；而切歌 / 暂停 / 收藏 /
        // 设置等所有真正的状态变化都有各自的即时 scheduleSave，不受这里影响。
        if (++saveTick >= TICK_SAVE_INTERVAL) {
            saveTick = 0
            scheduleSave()
        }
    }

    /** 定时关闭「播完档」（-1 播完当前首 / -2 播完整队）在用户主动切歌时就地撤销回显。
     *
     *  服务端侧的语义是「切歌事件即清旗标、不暂停」（见 MusicPlaybackService）：手动切歌
     *  说明用户还要继续听，不该在切歌瞬间停；但**旗标已被消费**，若 UI 不回显归零，
     *  面板会一直显示「当前曲播完后暂停」——用户以为定时还在，实际早已失效。
     *  归零不下发服务端（服务端已自行清掉，重复下发无害但也无必要）。 */
    private fun cancelOneShotSleepTimer() {
        if (sleepTimerMode < 0) {
            sleepTimerMode = 0
            sleepTimerDeadlineMs = 0L
        }
    }

    /** 播放指定曲目；context 为其所在队列（同队列引用时仅切索引）；source 为播放来源展示名 */
    fun playSong(song: DemoSong, context: List<DemoSong> = queue, source: String? = null) {
        val queueChanged = context !== queue
        if (queueChanged) queue = context
        if (source != null) queueSource = source
        // indexOf 找不到时返回 -1：旧写法 coerceAtLeast(0) 会静默改成队首，于是「要播的歌不在
        // 传入 context 里」这种调用方 bug 表现为「点了 A 播了 B」，且没有任何痕迹。
        // 宁可原地不动（不切歌、不改索引），也不播错——当前各调用点传参都正确，这是边界守卫。
        val found = context.indexOf(song)
        if (found < 0) {
            android.util.Log.e("AppState", "playSong: 曲目 ${song.id} 不在传入的队列中（size=${context.size}），已忽略")
            return
        }
        val index = found
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
        // 空队列：播放键无事可做（否则会翻成 playing=true 让空曲库「假装在播」）
        if (!hasSongs) return
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
        // 空队列：没有下一首可切（否则会走到 startCurrent 后 rememberPlayed("placeholder")，
        // 把占位 id 写进「最近播放」列表——展示层虽会过滤，但脏数据会一直留在快照里）
        if (!hasSongs) return
        // 手动切歌 = 用户还想继续听：服务端「播完当前首」旗标会被这次切歌消费掉（且不该暂停），
        // UI 侧同步归零，避免面板一直显示一个已经失效的定时
        if (manual) cancelOneShotSleepTimer()
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
        // 空队列：同上（避免把占位 id 写进最近播放）
        if (!hasSongs) return
        cancelOneShotSleepTimer() // 同上：手动切歌消费掉「播完当前首」旗标
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
        // 空队列：没有可 seek 的东西。放行会让空曲库下拖动进度条把 progressSec
        // 写到 0..1 之间（占位曲时长 1s），视觉上"进度被改了"却不会播放
        if (!hasSongs) return
        progressSec = sec.coerceIn(0f, durationSec)
        val eng = engine
        if (eng != null && engineLoadedUri != null && currentSong.mediaUri != null) eng.seekTo(progressSec)
        scheduleSave()
    }

    fun seekToFraction(fraction: Float) = seekTo(fraction * durationSec)

    /** 演示模式完播（引擎路径的完播 / 单曲循环由服务端播放器承担） */
    private fun handleTrackEnd() {
        // 完播计数（D1）：只在真正播到结尾时 +1，手动切歌/拖动进度不计
        bumpPlayCount(currentSong.id)
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
        // 空队列（无曲库 / 权限拒绝 / 队列被清空）：没有可播的东西。
        // 若放行，currentSong 会落到占位曲（mediaUri == null）→ 走下面的演示分支把
        // playing 置 true、progressSec 归 0，于是空曲库也能「播放」并让波形/进度线动起来
        // （tick 会在 100ms 后把 playing 熄回 false，但那一帧的假播放已经可见）。
        if (!hasSongs) {
            playing = false
            return
        }
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
        scheduleSave() // v9：随快照持久化，Activity 重建后仍能回显
    }

    /** 生效中的定时模式（面板高亮 / 设置副标题用）：分钟档过了期限视为结束——
     *  服务端到点自行暂停且无回执，本地按期限推导归零；播完档（-1/-2）同样无回执，
     *  由 onPlayingChanged 收到播放停止时就地归零（用户手动暂停视为放弃定时） */
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
        // 占位曲不是真曲目：空曲库时「歌曲操作→下一首播放」会把 placeholder 塞进空队列，
        // 使 hasSongs 变 true，波形/进度线假装播放约 1 秒后才自行停住
        val valid = songs.filter { it.id != DemoData.placeholder.id }
        if (valid.isEmpty()) return
        val engineActive = engineDriving() && alignQueueWithEngine()
        val index = at.coerceIn(0, queue.size)
        // 显式下发插入意图（playNext/playLast），shuffle 下服务端据此固定位置，不按 index 猜
        if (engineActive) engine!!.addQueueItems(index, valid.mapNotNull { it.toSpec() }, pinLast)
        queue = queue.toMutableList().apply { addAll(index, valid) }
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
        // 占位曲不是真曲目：空曲库时「歌曲操作」面板仍可打开，误点收藏会把 "placeholder"
        // 永久写进 favorites（与 bumpPlayCount 同款守卫）
        if (id == DemoData.placeholder.id) return
        if (id in favorites) favorites.remove(id) else favorites.add(id)
        scheduleSave()
    }

    /** 歌单是否可编辑（加歌/移除/重排/重命名/删除）：
     *  动态歌单（收藏/最近播放/最常播放）由状态实时解析，**不可写**；
     *  内建常驻歌单与用户自建歌单**都可编辑**（决策 B：放开内建歌单编辑，
     *  旧实现只允许 user- 前缀，导致内建歌单能加歌却删不掉、也不能重排）。 */
    fun isEditablePlaylist(playlistId: String): Boolean =
        playlistId !in DemoCatalog.dynamicIds && resolvePlaylist(playlistId) != null

    /** 加入歌单：按曲目 id 去重；返回是否真正新增 */
    fun addToPlaylist(playlistId: String, songId: String): Boolean {
        // 动态歌单不可写；不存在的 id 也不写（否则会造出一条永无出口的 addedTracks 脏记录）
        if (playlistId in DemoCatalog.dynamicIds) return false
        if (resolvePlaylist(playlistId) == null) return false
        if (songId == DemoData.placeholder.id) return false
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
        val finalTitle = title.trim().ifEmpty { "新建歌单" }
        // 已存在同名 user- 歌单：返回现有歌单（M3U 导入合并语义——调用方随后 addToPlaylist
        // 按 id 去重，同名重复导入不再堆出重复歌单）
        userPlaylists.firstOrNull { it.title.trim().equals(finalTitle, ignoreCase = true) }
            ?.let { return it }
        // 与内建/动态歌单重名时追加序号，而不是静默造出第二张同名卡。
        // 旧实现只查 userPlaylists，于是 M3U 里写 `#PLAYLIST:深夜巡航` 就能造出第二个「深夜巡航」，
        // 歌单墙出现两张同名卡、内容不同，用户无法分辨。
        val uniqueTitle = if (playlistTitleExists(finalTitle)) {
            var n = 2
            while (playlistTitleExists("$finalTitle ($n)")) n++
            "$finalTitle ($n)"
        } else {
            finalTitle
        }
        val playlist = DemoPlaylist(
            id = "user-${System.currentTimeMillis()}",
            title = uniqueTitle,
            meta = "0 首",
            art = ArtStyle.Room,
            tracks = emptyList(),
        )
        userPlaylists.add(playlist)
        scheduleSave()
        return playlist
    }

    /** 动态歌单的展示名（不可创建同名自建歌单） */
    private val dynamicPlaylistTitles = listOf("我喜欢的音乐", "最近播放", "最常播放")

    /** 歌单名是否已被占用（新建/重命名/导入重名提示用）：user- 歌单、内建歌单、**动态歌单**
     *  的标题都算冲突（trim 后忽略大小写比对；空名不算冲突——由调用方先行拦截） */
    fun playlistTitleExists(title: String): Boolean {
        val t = title.trim()
        if (t.isEmpty()) return false
        return userPlaylists.any { it.title.trim().equals(t, ignoreCase = true) } ||
            DemoCatalog.playlists.any { it.title.trim().equals(t, ignoreCase = true) } ||
            dynamicPlaylistTitles.any { it.equals(t, ignoreCase = true) }
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

    // ── 歌单编辑（移除 / 重排 / 重命名）──
    //
    // 可编辑范围（决策 B）：内建常驻歌单 + 用户自建歌单。动态歌单（收藏/最近播放/最常播放）
    // 由状态实时解析，不可写——旧实现只允许 user- 前缀，导致内建歌单「能加歌、删不掉、
    // 不能重排」：加歌入口只排除 dynamicIds，而移除/重排/菜单三处都用 startsWith("user-")
    // 拦住，用户加错了歌没有任何自救途径。

    /** 从歌单移除曲目（内建 + 自建）；返回是否真正移除 */
    fun removeFromPlaylist(playlistId: String, songId: String): Boolean {
        if (playlistId in DemoCatalog.dynamicIds) return false
        val list = addedTracks[playlistId] ?: return false
        val removed = list.remove(songId)
        if (removed) {
            syncPlaylistMeta(playlistId)
            scheduleSave()
        }
        return removed
    }

    /** 歌单内拖动重排：addedTracks 顺序即曲目序，快照原样持久化。
     *  **只对「纯叠加」歌单有效**（内建基座曲目不可变，重排索引无法表达基座内位移）——
     *  可重排判定见 [canReorderPlaylist]。 */
    fun movePlaylistTrack(playlistId: String, from: Int, to: Int) {
        if (playlistId in DemoCatalog.dynamicIds || from == to) return
        val list = addedTracks[playlistId] ?: return
        if (from !in list.indices || to !in list.indices) return
        list.add(to, list.removeAt(from))
        scheduleSave()
    }

    /** 重命名歌单：**仅用户自建歌单**（内建歌单名是目录数据的一部分，重命名需要额外存储，
     *  不在本轮范围）；空白名忽略。
     *  **重名拒绝**：与新建路径同口径（playlistTitleExists）。重命名过去完全不查重，
     *  而 createPlaylist 有同名合并语义，能造出两个同名歌单 → 后续合聚会命中较早那个，
     *  内容落到意料之外的歌单。排除「改成自己原来的名字」这一无害情形。 */
    fun renamePlaylist(id: String, title: String) {
        val newTitle = title.trim()
        if (newTitle.isEmpty() || !id.startsWith("user-")) return
        val index = userPlaylists.indexOfFirst { it.id == id }
        if (index < 0) return
        if (newTitle == userPlaylists[index].title.trim()) return // 名字没改：静默成功
        if (playlistTitleExists(newTitle)) return // 与现有歌单重名：拒绝
        userPlaylists[index] = userPlaylists[index].copy(title = newTitle)
        scheduleSave()
    }

    /**
     * 歌单详情页可重排判定。
     *
     * 重排靠 `movePlaylistTrack` 改写 addedTracks 的顺序，而 addedTracks 只承载「叠加层」——
     * 内建常驻歌单的基座曲目（`DemoCatalog.playlist(id).tracks`）不在其中，无法被重排。
     * 所以判据是：非动态歌单 **且** 展示列表恰好等于叠加层（无基座曲目、无失效引用剔除）。
     */
    fun canReorderPlaylist(playlistId: String): Boolean {
        if (playlistId in DemoCatalog.dynamicIds) return false
        val resolved = resolvePlaylist(playlistId) ?: return false
        val added = addedTracks[playlistId].orEmpty()
        return resolved.tracks.size == added.size && added.isNotEmpty()
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

    /**
     * 可见曲库：整库剔除被「过滤文件夹」排除的目录 + B3 短音频输出层过滤（视图层过滤，决策 A）。
     *
     * 为什么必须是视图层而不是扫描期：用户取消排除/关掉过滤后要**立即**生效，扫描期过滤会要求重扫。
     * 为什么必须集中在这一处：旧实现只有文件夹 tab 调 `folderGroups(excluded)`，
     * 而歌曲/专辑/艺术家/流派 tab、搜索、整库播放队列全部直接遍历 `library` ——
     * 设置页写「N 个目录已排除」，实际只在 1/6 的界面生效。
     *
     * **P2-④：短音频过滤也放在这一层。** 旧实现的 B3 过滤只在 MediaLibrary 的
     * `load()` / `loadCache()` 返回处各做一次，共两条入口；任何绕过它们把 songs 塞进
     * `library` 的路径（缓存先到、重扫竞态、将来的新入口）都会把 <30s 的通知音直接
     * 展现在曲库页——实测「扫描过滤=ON 却显示全量 11 首」正是这么发生的。
     * 移到状态层的输出口后，「显示什么」只由这一个函数决定，扫描侧加不了、也绕不过。
     *
     * 两层过滤互不替代：MediaLibrary 仍按开关全量扫描并**全量存缓存**（指纹留在缓存里，
     * 关过滤不用重解码），本函数只负责「当前该给用户看哪些」。
     *
     * 缓存键 = (library 引用, excludedFolders 内容指纹, scanFilterShort)：排除集是
     * SnapshotStateSet，元素变化不换引用，所以不能只按引用比较。
     */
    private var visibleLibraryCache: Triple<List<DemoSong>, Int, List<DemoSong>>? = null

    fun visibleLibrary(): List<DemoSong> {
        val lib = library
        val excluded = excludedFolders
        // 指纹用 size + 元素哈希：排除集很小（通常个位数），O(k) 可忽略；
        // 再把 scanFilterShort 并进指纹低位——开关是 Boolean，不占额外比较成本
        val fingerprint = excluded.size * 31 + excluded.hashCode() * 2 + if (scanFilterShort) 1 else 0
        visibleLibraryCache?.let { (cachedLib, cachedFp, cachedResult) ->
            if (cachedLib === lib && cachedFp == fingerprint) return cachedResult
        }
        val filtered = lib
            .filter { excluded.isEmpty() || it.folder !in excluded }
            .filter { !scanFilterShort || it.durationSec >= MIN_TRACK_SEC }
        visibleLibraryCache = Triple(lib, fingerprint, filtered)
        return filtered
    }

    /** 文件夹选项（folder → 曲目数）：从**整库**派生，设置页「过滤文件夹」用。
     *  必须用整库而不是 visibleLibrary——否则已排除的目录会从选项列表里消失，用户无法取消排除。 */
    fun folderOptions(): List<Pair<String, Int>> =
        library.groupBy { it.folder }.map { (folder, songs) -> folder to songs.size }.sortedBy { it.first }

    /**
     * 一次扫描算好四套分组（专辑 / 艺术家 / 文件夹 / 流派），按 [visibleLibrary] 缓存。
     *
     * 为什么必须在状态层缓存而不是让各屏 remember：这些派生原先在组合体里裸调，
     * 而播放中 `progressSec` 每 100ms 写一次 → 任何读了 progressFraction 的屏幕整棵树
     * 10Hz 重组 → 每次重跑四遍全库 groupBy + 排序（几千首即每 tick 数毫秒 + 大量短命
     * Map/List 分配）。曲库页只对 `sortedSongs`/`albumCountByArtist` 做了 remember，
     * 而详情页、搜索页、加入歌单面板、歌单墙全都直接吃这份派生，逐处补 remember 既容易
     * 漏又要求每个调用方都记得。把缓存放进状态层（唯一的 library 持有者）是唯一一处根除。
     *
     * 失效条件 = visibleLibrary 的缓存键变化（library 引用或排除集指纹），
     * 由 [visibleLibrary] 统一判定；这里再按结果引用比一次即可。
     */
    private class LibraryGroups(
        val album: List<LibraryGroup>,
        val artist: List<LibraryGroup>,
        val folder: List<LibraryGroup>,
        val genre: List<LibraryGroup>,
        /** 艺术家 → 专辑数：艺术家详情页 meta 与曲库艺术家 tab 共用（原先各自整库分组再数） */
        val albumCountByArtist: Map<String, Int>,
    )

    private var groupCache: Pair<List<DemoSong>, LibraryGroups>? = null

    /** 分组排序统一用中文 Collator（拼音序），与歌曲 tab 的 SongSort 同口径 */
    private val groupCollator: java.text.Collator =
        java.text.Collator.getInstance(java.util.Locale.CHINA)

    private fun groups(): LibraryGroups {
        val lib = visibleLibrary()
        groupCache?.let { (cachedLib, cached) -> if (cachedLib === lib) return cached }
        val byKey = compareBy<LibraryGroup, String>(groupCollator) { it.key }
        val album = lib.groupBy { it.album }.map { LibraryGroup(it.key, it.value) }.sortedWith(byKey)
        val computed = LibraryGroups(
            album = album,
            artist = lib.groupBy { it.artist }.map { LibraryGroup(it.key, it.value) }.sortedWith(byKey),
            folder = lib.groupBy { it.folder }.map { LibraryGroup(it.key, it.value) }.sortedWith(byKey),
            // 空值归一为「未知流派」（扫描真值为空的歌不落「独立」，统一在此展示归一，
            // 详情入口 target.id 与分组键同源）
            genre = lib.groupBy { if (it.genre.isBlank()) "未知流派" else it.genre }
                .map { LibraryGroup(it.key, it.value) }.sortedWith(byKey),
            // 每专辑归其首曲艺术家（与曲库页原预计算同口径），再按艺术家计数
            albumCountByArtist = album.map { it.songs.first().artist }.groupingBy { it }.eachCount(),
        )
        groupCache = lib to computed
        return computed
    }

    /** 艺术家 → 专辑数（曲库艺术家 tab 与艺术家详情页 meta 共用同一份缓存） */
    fun albumCountByArtist(): Map<String, Int> = groups().albumCountByArtist

    fun albumGroups(): List<LibraryGroup> = groups().album

    fun artistGroups(): List<LibraryGroup> = groups().artist

    /** 文件夹分组：排除集已在 [visibleLibrary] 层生效，这里不再二次过滤
     *  （保留形参是为了不破坏既有调用点签名） */
    @Suppress("UNUSED_PARAMETER")
    fun folderGroups(excluded: Set<String>): List<LibraryGroup> = groups().folder

    fun genreGroups(): List<LibraryGroup> = groups().genre

    /** 搜索范围：真曲库装载后搜**可见曲库**（尊重过滤文件夹）；演示模式搜全量演示曲 */
    private fun searchScope(): List<DemoSong> = if (realLibraryLoaded) visibleLibrary() else DemoData.songs

    fun searchSongs(query: String): List<DemoSong> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        return searchScope().filter {
            it.title.contains(q, true) || it.artist.contains(q, true) || it.album.contains(q, true)
        }
    }

    /** 搜索范围的专辑分组：直接过滤整库分组缓存，**不再对全库重新 groupBy**。
     *
     *  语义严格等价：原实现对「整库按专辑分组」后再按（专辑名 / 首曲艺术家 / 任一曲名）
     *  命中过滤，且返回的是**整张专辑的全部曲目**——所以先分组再过滤与先过滤再分组结果相同，
     *  只是少了每次击键一次完整的 groupBy + 排序分配（大库输入卡顿的主因之一）。 */
    fun searchAlbumGroups(query: String): List<LibraryGroup> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        // 演示模式下 searchScope 与 library 不同源（演示库已移除，此处仅为语义完整）
        val groups = if (realLibraryLoaded) albumGroups() else searchScope().groupBy { it.album }
            .map { LibraryGroup(it.key, it.value) }.sortedWith(compareBy(groupCollator) { it.key })
        return groups.filter { g ->
            g.key.contains(q, true) || g.songs.first().artist.contains(q, true) ||
                g.songs.any { it.title.contains(q, true) }
        }
    }

    /**
     * 曲目 id → 曲目索引（按 [library] 引用缓存）。
     *
     * 为什么必须建索引：[songById] 原先是 `library.firstOrNull { it.id == id }`，单次 O(n)。
     * 而歌单解析是 `favorites.mapNotNull { songById(it) }` 这类 O(|集合|×|曲库|) 的形状，
     * 「我喜欢的音乐 / 最近播放 / 最常播放」三个动态歌单各自都走一遍；歌单墙又会把
     * 所有内建歌单（含这三个动态歌单）逐个 resolve 一次。真实曲库几千首 + 数百首收藏时，
     * 一次歌单墙组合就是数十万到数百万次 String.equals——在 10Hz 重组下必然掉帧。
     *
     * 索引使单次查找降到 O(1)，整个派生链随之从 O(n·m) 降到 O(n+m)。
     * 只在 library 引用变化时重建（重扫/缓存刷新），重建本身是一次 O(n) 遍历。
     */
    private var songIndexCache: Pair<List<DemoSong>, Map<String, DemoSong>>? = null

    private fun songIndex(): Map<String, DemoSong> {
        val lib = library
        songIndexCache?.let { (cachedLib, index) -> if (cachedLib === lib) return index }
        // 重名 id 取首个（与 firstOrNull 的语义保持一致）
        val built = HashMap<String, DemoSong>(lib.size * 2)
        for (song in lib) built.putIfAbsent(song.id, song)
        songIndexCache = lib to built
        return built
    }

    /** 按曲目 id 找歌：真曲库装载后不再解析演示 id（失效引用自然过滤，存储原样保留） */
    private fun songById(id: String): DemoSong? =
        if (realLibraryLoaded) {
            songIndex()[id]
        } else {
            DemoCatalog.songById(id) ?: songIndex()[id]
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
        if (id == DemoCatalog.MostPlayedId) {
            // 最常播放（D1）：按完播次数降序取前 50；次数相同按**曲名**拼音序稳定排序
            // （避免每次进页面抖动）。失效 id 必须先被 songById 过滤再 take——
            // 旧实现 `.take(50).mapNotNull{}` 让已删文件的 id 白占名额（60 条里 15 条失效
            // 就只显示 35 首，实际有 45 首够格）。
            val tracks = playCounts.entries
                .mapNotNull { e -> songById(e.key)?.let { e.value to it } }
                .sortedWith(
                    compareByDescending<Pair<Int, DemoSong>> { it.first }
                        .thenBy(groupCollator) { it.second.title },
                )
                .take(50)
                .map { it.second }
            return DemoPlaylist(id, "最常播放", "${tracks.size} 首", ArtStyle.Tape, tracks)
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

    /** 详情页数据：歌单 / 专辑 / 艺术家 / 文件夹 / 流派 共用模板。
     *  封面走 coverUri（按需解码）：扫描已不再预解码全库封面，这里也不该持有位图。
     *
     *  性能约束：本函数在详情页组合体里被直接调用（播放中 10Hz 重组会反复触发），
     *  因此内部一律走 [groups]/[songIndex] 的引用缓存，**不得**再出现「为找一个 key 而
     *  整库 groupBy/sortedBy」的写法；艺术家分支的专辑数也改读同一份缓存映射
     *  （原先是第二次 albumGroups() + count，是全 App 最贵的单次派生）。 */
    fun detailData(target: DetailTarget): DetailData? {
        return when (target.kind) {
            DetailTarget.Kind.Playlist -> resolvePlaylist(target.id)?.let {
                DetailData("歌单", it.title, it.meta, it.art, it.tracks, coverUri = it.tracks.firstOrNull()?.artworkUri)
            }
            DetailTarget.Kind.Album -> albumGroups().firstOrNull { it.key == target.id }?.let {
                DetailData("专辑", it.key, it.meta, it.art, it.songs, coverUri = it.coverUri)
            }
            DetailTarget.Kind.Artist -> artistGroups().firstOrNull { it.key == target.id }?.let {
                DetailData(
                    "艺术家", it.key,
                    "${it.songs.size} 首 · ${albumCountByArtist()[it.key] ?: 0} 张专辑",
                    it.art, it.songs, coverUri = it.coverUri,
                )
            }
            DetailTarget.Kind.Folder -> folderGroups(emptySet()).firstOrNull { it.key == target.id }?.let {
                DetailData("文件夹", it.key, "${it.songs.size} 首", it.art, it.songs, coverUri = it.coverUri)
            }
            DetailTarget.Kind.Genre -> genreGroups().firstOrNull { it.key == target.id }?.let {
                DetailData("流派", it.key, "${it.songs.size} 首", it.art, it.songs, coverUri = it.coverUri)
            }
        }
    }

    private fun rememberPlayed(id: String) {
        playedIds.remove(id)
        playedIds.add(0, id)
        if (playedIds.size > 50) playedIds.removeAt(playedIds.lastIndex)
    }

    /** 累计播放次数（D1）：只在「完播」时计（切歌/手动跳转不算），与 rememberPlayed 同点位 */
    private fun bumpPlayCount(id: String) {
        // 占位曲不是真曲目：空曲库终态下 tick 若漏拦会把 "placeholder" 当完成一首计数，
        // 写进快照后「最常播放」会凭空多一条失效 id（展示层会过滤，但脏数据会永久留着）
        if (id == DemoData.placeholder.id) return
        playCounts[id] = (playCounts[id] ?: 0) + 1
    }

    companion object {
        /**
         * 播放现场（queue_state.json）稳态最小写出间隔：20s。
         *
         * 该文件的唯一用途是「进程被杀后从通知栏/蓝牙重连恢复现场」，20s 的位置误差对续播
         * 完全无感；而曲库语境下的队列就是整个曲库（万首量级 ≈ 1MB JSON），每 5s 重写一次
         * 是纯浪费的 GC 与闪存写放大。切歌/暂停/换队列仍立即写（见 shouldWriteQueueState）。
         */
        private const val QUEUE_STATE_MIN_INTERVAL_MS = 20_000L

        /**
         * tick 里「仅因播放进度」触发整份快照存盘的间隔（tick 是 100ms 一次 → 200 次 = 20s）。
         *
         * 原为 50（5s）：DataStore 每次写都要序列化整份快照（歌单 + 收藏 + 播放次数）并原子替换
         * 文件，播放中每 5s 一次是纯写放大；而进度对续播 20s 粒度足够。真正的状态变化
         * （切歌 / 暂停 / 收藏 / 设置 / 加歌）都有各自的即时 scheduleSave，不走这个节流。
         */
        private const val TICK_SAVE_INTERVAL = 200

        /**
         * B3 短音频阈值（秒）：短于该值视为通知音/录音碎片。
         *
         * 与 `app` 模块 `MediaLibrary.MIN_TRACK_SEC` **必须同值**（两边都靠「扫描全量 +
         * 输出层过滤」两层分离工作：扫描侧用它决定展示，状态层用它作为最后一道输出闸门）。
         * 状态层不能反向依赖 app 模块，故此处留同值常量；改阈值时两处一起改。
         */
        const val MIN_TRACK_SEC = 30
    }
}
