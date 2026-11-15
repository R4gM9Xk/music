package com.local.music.player

import android.content.Context
import android.util.Log
import com.local.music.core.ui.state.AppStateSnapshot
import com.local.music.core.ui.state.AudioFxSettings
import com.local.music.core.ui.state.SongSpec
import org.json.JSONArray
import org.json.JSONObject

/**
 * 播放现场的「服务端可读」副本（files/queue_state.json）。
 *
 * 存在的理由：进程被杀后，系统媒体入口（通知栏恢复 / 蓝牙重连 / Android Auto）会直接
 * 拉起 MediaSessionService 并回调 onPlaybackResumption，此时**没有 Activity**、
 * 也就没有 AppState——服务必须能自己从磁盘重建队列现场。
 *
 * 而服务读 DataStore 会把播放层反向耦合到状态层（且 DataStore 是 suspend + 单进程约束），
 * 所以这里用一份与快照同 schema 的独立 JSON 做单向桥：
 * - 写：AppState.scheduleSave 时同步写（与快照同一触发点，不会两套时机）
 * - 读：仅 MusicPlaybackService.onPlaybackResumption 使用
 *
 * 只存恢复播放必需的字段（队列 specs + 当前索引 + 进度 + 播放模式/音效），
 * 不重复存歌单/收藏等用户数据。
 */
object QueueStateStore {

    private const val FILE = "queue_state.json"

    /**
     * schema 版本。
     *
     * v2 追加了播放模式与音效字段（见 [QueueState] 的 KDoc）。**读取侧接受 v1**：
     * 升级后第一次冷启动时磁盘上仍是 v1 现场，若因为版本号直接丢弃，用户会看到
     * 「更新完 App 之后通知栏续播没了」——那正是这段代码存在的理由。
     * 所以读侧判据是 `v in 1..VERSION`，缺的字段走 opt 默认值。
     */
    private const val VERSION = 2
    private const val TAG = "QueueStateStore"

    /**
     * 服务端恢复所需的现场。
     *
     * ## 为什么除了队列还要存「播放模式 + 音效」（P2 修复）
     *
     * 进程被杀后，系统媒体入口（通知栏恢复 / 蓝牙重连 / Android Auto）会直接拉起
     * `MediaSessionService` 并回调 `onPlaybackResumption`，此时**没有 Activity**、
     * 也就没有 AppState 与 DataStore 读取链路。旧实现只存 queue/index/position，
     * 于是恢复出来的播放器是「默认参数」：随机/循环回到 off、变速回到 1.0x、
     * EQ 与音效链回到全关 —— 用户上次明明开着随机与低音增强。
     *
     * 这些字段的**权威副本**在 AppState（快照 v12），这里存的是「服务能独立读到」的
     * 冗余副本，与 specs 同一理由（见类 KDoc 的「单向桥」说明）。写入时机与 specs
     * 完全相同（AppState.scheduleSave 一次写全），所以两者不会分叉。
     *
     * @property shuffleOn 随机模式
     * @property repeatMode "Off"/"All"/"One" 的**名字**（同快照口径：名字不会因枚举增删错位）
     * @property playbackSpeed 变速倍率
     * @property eqPreset 均衡器预设名（"Off"/"Pop"/...）
     * @property audioFx 音效链三项开关与档位
     */
    data class QueueState(
        val specs: List<SongSpec>,
        val index: Int,
        val positionSec: Float,
        val shuffleOn: Boolean = false,
        val repeatMode: String = "Off",
        val playbackSpeed: Float = 1f,
        val eqPreset: String = "Off",
        val audioFx: AudioFxSettings = AudioFxSettings(),
    )

    /**
     * 写现场：从快照还原出 SongSpec 列表。
     * 快照只存 id（稳定 id = "f" + 绝对路径），specs 的其余字段（标题/艺术家/封面/时长）
     * 在这里无法从 id 反推——所以**由调用方传入完整的 specs**（AppState 侧手里就有 DemoSong）。
     */
    fun write(
        context: Context,
        specs: List<SongSpec>,
        index: Int,
        positionSec: Float,
        // 播放模式 + 音效（第二轮新增）：默认值 = 中立状态，让旧调用点（若还有）
        // 不会因为签名变化编译失败，也不会因此写坏现场。
        shuffleOn: Boolean = false,
        repeatMode: String = "Off",
        playbackSpeed: Float = 1f,
        eqPreset: String = "Off",
        audioFx: AudioFxSettings = AudioFxSettings(),
    ) {
        runCatching {
            val arr = JSONArray()
            specs.forEach { s ->
                arr.put(
                    JSONObject().apply {
                        put("id", s.id)
                        put("uri", s.uri)
                        put("title", s.title)
                        put("artist", s.artist)
                        put("album", s.album)
                        put("artworkUri", s.artworkUri ?: "")
                        put("durationMs", s.durationMs)
                    },
                )
            }
            val json = JSONObject().apply {
                put("v", VERSION)
                put("index", index)
                put("positionSec", positionSec.toDouble())
                put("songs", arr)
                // 播放模式 + 音效（v2）：见 QueueState 的 KDoc。全是可选字段，
                // 读侧 opt 带默认值 → v1 老现场照常能读（零迁移）。
                put("shuffleOn", shuffleOn)
                put("repeatMode", repeatMode)
                put("playbackSpeed", playbackSpeed.toDouble())
                put("eqPreset", eqPreset)
                put(
                    "audioFx",
                    JSONObject().apply {
                        put("loudnessNorm", audioFx.loudnessNorm)
                        put("bassBoost", audioFx.bassBoost)
                        put("bassLevel", audioFx.bassLevel)
                        put("virtualizer", audioFx.virtualizer)
                        put("virtualLevel", audioFx.virtualLevel)
                    },
                )
            }
            // 原子写：先写临时文件再 rename，避免进程在写一半时被杀留下半截 JSON
            val dir = context.filesDir
            val tmp = java.io.File(dir, "$FILE.tmp")
            val dst = java.io.File(dir, FILE)
            tmp.writeText(json.toString())
            if (!tmp.renameTo(dst)) {
                // rename 失败（跨卷/占用）回退直写
                dst.writeText(json.toString())
                tmp.delete()
            }
        }.onFailure {
            // 写失败要留痕：这条链的唯一消费者是「进程被杀后的系统续播」，
            // 静默失败的表现是「用户重连蓝牙/点通知栏恢复时队列莫名清空」，无从排查
            Log.e(TAG, "queue state write failed", it)
        }
    }

    /** 读现场：无/损坏/版本不符返回 null（服务端走默认行为，不阻塞） */
    fun read(context: Context): QueueState? = runCatching {
        val file = java.io.File(context.filesDir, FILE)
        if (!file.isFile) return@runCatching null
        val root = JSONObject(file.readText())
        val version = root.optInt("v", 0)
        // **接受 v1..VERSION**（不是只认当前版本）：v1 → v2 的升级正好发生在
        // 「用户已经装过一版、磁盘上有现场」的时刻，严判会把唯一能让续播工作的现场丢掉。
        // 缺失字段一律走下面的 opt 默认值（与快照「只增字段、逐字段带默认值」同口径）。
        if (version !in 1..VERSION) {
            // 版本不符是「升级后的正常降级」，但要能看到——否则老现场永不恢复会被当成 bug
            Log.w(TAG, "queue state version mismatch (v=$version, expect 1..$VERSION); ignored")
            return@runCatching null
        }
        val arr = root.optJSONArray("songs") ?: return@runCatching null
        if (arr.length() == 0) return@runCatching null
        val specs = (0 until arr.length()).mapNotNull { i ->
            val o = arr.getJSONObject(i)
            val id = o.optString("id")
            val uri = o.optString("uri")
            // 缺 id/uri 的条目无法播放：跳过（队列其余部分仍可用）
            if (id.isEmpty() || uri.isEmpty()) return@mapNotNull null
            SongSpec(
                id = id,
                uri = uri,
                title = o.optString("title"),
                artist = o.optString("artist"),
                album = o.optString("album"),
                artworkUri = o.optString("artworkUri").ifEmpty { null },
                durationMs = o.optLong("durationMs"),
            )
        }
        if (specs.isEmpty()) return@runCatching null
        // 音效链整组容错：坏数据回落默认（全关），不让一个字段废掉整份现场——同快照口径
        val fxObj = root.optJSONObject("audioFx")
        val audioFx = AudioFxSettings(
            loudnessNorm = fxObj?.optBoolean("loudnessNorm", false) ?: false,
            bassBoost = fxObj?.optBoolean("bassBoost", false) ?: false,
            bassLevel = fxObj?.optInt("bassLevel", AudioFxSettings.DEFAULT_LEVEL) ?: AudioFxSettings.DEFAULT_LEVEL,
            virtualizer = fxObj?.optBoolean("virtualizer", false) ?: false,
            virtualLevel = fxObj?.optInt("virtualLevel", AudioFxSettings.DEFAULT_LEVEL) ?: AudioFxSettings.DEFAULT_LEVEL,
        )
        QueueState(
            specs = specs,
            index = root.optInt("index", 0).coerceIn(0, specs.lastIndex),
            positionSec = root.optDouble("positionSec", 0.0).toFloat().coerceAtLeast(0f),
            shuffleOn = root.optBoolean("shuffleOn", false),
            repeatMode = root.optString("repeatMode", "Off"),
            // 变速 0 / 负数 / 荒谬大值都不可用：非正回落 1.0（同快照 restore 的口径）
            playbackSpeed = root.optDouble("playbackSpeed", 1.0).toFloat().takeIf { it > 0f } ?: 1f,
            eqPreset = root.optString("eqPreset", "Off"),
            audioFx = audioFx,
        )
    }.onFailure {
        // 损坏现场（半截 JSON / 编码异常）降级为 null 是既定策略，但要能在日志里区分
        // 「本来就没有现场」（上面各类 return null，不报错）与「有文件但读坏了」
        Log.e(TAG, "queue state read failed; falling back to empty", it)
    }.getOrNull()

    // 已删除：`fun clear(context: Context)`（本轮清理）。
    // 全仓零调用。它的 KDoc 举的场景（「用户清空队列」）实际上走的是
    // AppState.clearUpNext + scheduleSave（后者会用新队列重写本文件），
    // 并不需要单独删文件。留着一个没人调的「删除现场」API，
    // 反而容易被误当成「退出登录/清空队列」的标准收尾而半途接错。
}
