package com.local.music.state

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.local.music.core.ui.state.AppStateSnapshot
import com.local.music.core.ui.state.PlaylistSnapshot
import com.local.music.core.ui.state.SnapshotStore
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

private val Context.appDataStore: androidx.datastore.core.DataStore<Preferences> by preferencesDataStore(name = "glass_app_state")

/**
 * P2-27：快照读取三态。旧 [SettingsStore.read] 的「null = 首次启动 or 损坏」二义性
 * 让 GlassApp 把损坏数据当首次启动处理（markHydrated 放行存盘）→ 残留的坏字节被默认值
 * 快照覆盖，用户全部设置/歌单静默归零。三态让宿主能区分「安全放行」与「需要提示」。
 */
sealed class SnapshotReadResult {
    /** 键不存在：真正的首次启动（宿主可安全 markHydrated 放行存盘） */
    object Absent : SnapshotReadResult()

    /** 解码成功（宿主正常 restore） */
    data class Ok(val snapshot: AppStateSnapshot) : SnapshotReadResult()

    /** 数据损坏或来自更新版本（P3-62）：原始串已由 [SettingsStore.readResult] 留档到私有目录 */
    data class Corrupt(val raw: String) : SnapshotReadResult()
}

/**
 * 持久化实现（DataStore Preferences）：
 * - 快照编码为带版本号的 JSON（v6），歌单名 / 搜索词等用户文本无需转义
 * - 旧版控制字符分隔格式（v1）在读取时一次性迁移
 * - schedule 防抖 500ms 写盘；write 失败只记日志（不杀死防抖 collector）；
 *   flush 供退后台 / 关键节点立即落盘
 * - scanFilterShort 另存独立 Preferences 键：MediaLibrary 扫描不经 AppState 就能读到当前开关（见 readScanFilterShort）
 * - All-Files 引导的一次性标记同样走独立键（见 allFilesPrompted）：权限页无回调，只能靠磁盘记住「已提示过」
 * - 生命周期由宿主掌控：Activity 重建前须调 [cancel]，否则自建 scope 与防抖 collector 逐次泄漏
 */
class SettingsStore(context: Context) : SnapshotStore {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pending = MutableStateFlow<AppStateSnapshot?>(null)

    init {
        scope.launch {
            pending.filterNotNull().debounce(500).collect { write(it) }
        }
    }

    override fun schedule(snapshot: AppStateSnapshot) {
        pending.value = snapshot
    }

    /** 立即落盘最近一次待写快照（退后台 / 进程可能被杀的关键节点调用） */
    suspend fun flush() {
        pending.value?.let { write(it) }
    }

    /**
     * 释放自建 IO 作用域（宿主在 onDispose 调用）。
     *
     * 为什么必须显式提供：scope 是类内自建的（不是 rememberCoroutineScope 的），
     * Activity 每次重建（配置变更 / 深浅色切换 / 进程内重组）GlassApp 的 remember{}
     * 都会新建一个 store——旧实例没人取消的话，它的 scope 与 [init] 里那条
     * debounce collector 会永久活着：重建几次就泄漏几条协程链，而且它们还攥着
     * 最后一份 pending 快照与 Application 上下文。取消后本实例不可再用（宿主随重建新建）。
     */
    fun cancel() {
        scope.cancel()
    }

    /**
     * 「所有文件访问（MANAGE_EXTERNAL_STORAGE）」引导是否已提示过——跨进程的一次性标记。
     *
     * 为什么需要持久化：该权限只能跳系统授权页，**没有授权结果回调**。用户拒绝后返回，
     * GlassApp 里的 `jumpedAllFiles` 只是进程内变量，重启即失忆 → 每次冷启动都再弹一次
     * 系统设置页，属于骚扰。把「已提示过」落到磁盘，才能真正做到「每台设备只引导一次」。
     *
     * 与 [readScanFilterShort] 同款：独立 Preferences 键（不走 SharedPreferences，免第二条 IO 通道），
     * 也不进 AppStateSnapshot——引导状态不是用户设置，不该随备份/恢复曲库迁移。
     */
    suspend fun allFilesPrompted(): Boolean = withContext(Dispatchers.IO) {
        runCatching { appContext.appDataStore.data.first()[KEY_ALL_FILES_PROMPTED] ?: false }
            .getOrDefault(false)
    }

    /** 标记引导已提示过（见 [allFilesPrompted]）：写失败只记日志，下次冷启动仍会再提示一次 */
    suspend fun markAllFilesPrompted() {
        runCatching { appContext.appDataStore.edit { it[KEY_ALL_FILES_PROMPTED] = true } }
            .onFailure { Log.e(TAG, "markAllFilesPrompted failed", it) }
    }

    /**
     * [flush] 的阻塞版：供 `DisposableEffect.onDispose` 这类**没有协程可挂起**的收尾点调用
     * （Activity 销毁时要把防抖窗口里的最后一次修改抢在 [cancel] 之前落盘）。
     *
     * 为什么这里可以阻塞主线程：调用点只有一个，即组合销毁，而且写的是几十 KB 的 JSON；
     * 阻塞上限由 [timeoutMs] 兜住（DataStore edit 自己也在 IO 线程跑，正常情况下是个位数毫秒）。
     * 超时只记日志不抛：宁可丢这一次快照，也不能在 onDispose 里卡住甚至 ANR——
     * 丢失的代价是「最后一次设置改动回退」，卡死的代价是整个界面销毁流程停摆。
     */
    fun flushBlocking(timeoutMs: Long = FLUSH_BLOCKING_TIMEOUT_MS) {
        val snapshot = pending.value ?: return
        // 用独立 runBlocking 作用域而不是类内 scope：onDispose 里 flush 与 cancel 的调用顺序
        // 是宿主决定的，即便反过来调（scope 已取消）也要尽力写出去，不能静默跳过
        runBlocking {
            withTimeoutOrNull(timeoutMs) { write(snapshot) }
                ?: Log.w(TAG, "flushBlocking timed out after ${timeoutMs}ms; last snapshot dropped")
        }
    }

    suspend fun read(): AppStateSnapshot? = withContext(Dispatchers.IO) {
        val raw = appContext.appDataStore.data.first()[KEY_STATE] ?: return@withContext null
        decode(raw)
    }

    /**
     * P2-27：三态读取（宿主接线点：GlassApp 启动恢复）。
     *
     * 为什么要三态：旧 [read] 的 `AppStateSnapshot?` 把「键不存在（真首装）」与
     * 「解码失败（损坏）」混成同一个 null，宿主只能一律 markHydrated 放行存盘——
     * 于是损坏快照被全默认状态覆盖写盘，用户设置不可恢复（P1-②）。
     *
     * decode 失败时把原始串另存一份到私有目录（`app_state_snapshot.corrupt.<ts>`，保留最近
     * [CORRUPT_KEEP] 份），给「数据损坏」留人工恢复的证据，然后返回 [SnapshotReadResult.Corrupt]——
     * 宿主据此可以提示用户而不是静默重置。文件级 IO 也可能失败（磁盘满等）：留档失败不影响
     * 读取结果本身，照常返回 Corrupt。
     */
    suspend fun readResult(): SnapshotReadResult = withContext(Dispatchers.IO) {
        val raw = appContext.appDataStore.data.first()[KEY_STATE]
            ?: return@withContext SnapshotReadResult.Absent
        // 损坏判定前先重试一次：DataStore 首次读取可能撞上「atomic 重命名刚完成」的瞬时窗口，
        // 读到半截文件会让 decode 失败，而一次失败就判损坏会白白冻住存盘（反而更糟）。
        // 只重试一次（总耗时 <10ms 量级）：真损坏重试也不会变好，不做无谓反复。
        val snapshot = decode(raw) ?: run {
            kotlinx.coroutines.delay(READ_RETRY_DELAY_MS)
            val retryRaw = runCatching {
                appContext.appDataStore.data.first()[KEY_STATE]
            }.getOrNull() ?: raw
            decode(retryRaw)?.let { return@withContext SnapshotReadResult.Ok(it) }
            null
        }
        if (snapshot != null) {
            SnapshotReadResult.Ok(snapshot)
        } else {
            dumpCorrupt(raw)
            SnapshotReadResult.Corrupt(raw)
        }
    }

    /** 损坏原始串留档（保留最近 [CORRUPT_KEEP] 份，旧的清掉）；失败只记日志 */
    private fun dumpCorrupt(raw: String) {
        runCatching {
            val dir = File(appContext.filesDir, "corrupt_snapshot")
            dir.mkdirs()
            val file = File(dir, "app_state_snapshot.corrupt.${System.currentTimeMillis()}")
            file.writeText(raw)
            // 超额清理：按修改时间留最新 N 份
            dir.listFiles { f -> f.name.startsWith("app_state_snapshot.corrupt.") }
                ?.sortedByDescending { it.lastModified() }
                ?.drop(CORRUPT_KEEP)
                ?.forEach { runCatching { it.delete() } }
            Log.e(TAG, "corrupt snapshot dumped to ${file.name} (len=${raw.length})")
        }.onFailure { Log.e(TAG, "corrupt snapshot dump failed", it) }
    }

    private suspend fun write(snapshot: AppStateSnapshot) {
        try {
            appContext.appDataStore.edit {
                it[KEY_STATE] = encode(snapshot)
                it[KEY_SCAN_FILTER_SHORT] = snapshot.scanFilterShort // 与快照原子同写：扫描侧同步读取的单一事实源
            }
        } catch (e: Exception) {
            // 写失败不能让 collector 协程死亡（否则之后所有 schedule 都不再落盘）
            Log.e(TAG, "snapshot write failed", e)
        }
    }

    companion object {
        private const val TAG = "SettingsStore"
        private val KEY_STATE = stringPreferencesKey("app_state_snapshot")
        private val KEY_SCAN_FILTER_SHORT = booleanPreferencesKey("scan_filter_short")
        /** All-Files 引导一次性标记（见 [allFilesPrompted]）：与快照无关，故独立键 */
        private val KEY_ALL_FILES_PROMPTED = booleanPreferencesKey("all_files_prompted")
        private const val VERSION = 9

        /** P2-27：损坏快照留档份数上限（见 [dumpCorrupt]），够人工排查即可 */
        private const val CORRUPT_KEEP = 3

        /** 判定损坏前的单次重试间隔：避开 DataStore 原子重命名的瞬时窗口（见 [readResult]） */
        private const val READ_RETRY_DELAY_MS = 30L

        /** [flushBlocking] 的阻塞上限：只是兜底，正常 DataStore edit 是个位数毫秒 */
        private const val FLUSH_BLOCKING_TIMEOUT_MS = 400L

        /**
         * 扫描过滤开关的同步读取（MediaLibrary.load 扫描线程专用；GlassApp 调用点无需感知该设置）：
         * 独立键随快照防抖落盘，切换后到下一次手动重扫之间可能有 ≤500ms 窗口读到旧值。
         * 必须在后台线程调用；读失败回退默认 true（默认过滤短音频）。
         */
        fun readScanFilterShort(context: Context): Boolean = runBlocking {
            runCatching {
                context.applicationContext.appDataStore.data.first()[KEY_SCAN_FILTER_SHORT] ?: true
            }.getOrDefault(true)
        }

        private fun JSONArray.strings(): List<String> = List(length()) { getString(it) }

        private fun JSONObject.stringSet(key: String): Set<String> =
            optJSONArray(key)?.strings()?.toSet() ?: emptySet()

        private fun JSONObject.stringList(key: String): List<String> =
            optJSONArray(key)?.strings() ?: emptyList()

        // ── v2：JSON 编码 ──────────────────────────────────

        /**
         * 备份导出（D3）：当前快照 → JSON 文本。与快照同 schema（含 v 号），
         * 恢复时直接走 [decode] 的兼容链，零新增解析逻辑。
         */
        fun encodeForBackup(s: AppStateSnapshot): String = encode(s)

        /** 备份恢复（D3）：解析备份文件文本（含旧格式兼容），失败返回 null */
        fun decodeBackup(raw: String): AppStateSnapshot? = decode(raw)

        private fun encode(s: AppStateSnapshot): String = JSONObject().apply {
            put("v", VERSION)
            put("followCover", s.followCover)
            put("lyricEnabled", s.lyricEnabled)
            put("glassLevel", s.glassLevel)
            put("motionLevel", s.motionLevel)
            put("eqPreset", s.eqPreset)
            put("themeMode", s.themeMode)
            put("excludedFolders", JSONArray(s.excludedFolders.toList()))
            put("favorites", JSONArray(s.favorites.toList()))
            put("searchHistory", JSONArray(s.searchHistory))
            put("playedIds", JSONArray(s.playedIds))
            put(
                "userPlaylists",
                JSONArray().apply {
                    s.userPlaylists.forEach { p ->
                        put(
                            JSONObject().apply {
                                put("id", p.id)
                                put("title", p.title)
                                put("tracks", JSONArray(p.trackIds))
                            },
                        )
                    }
                },
            )
            put(
                "addedTracks",
                JSONObject().apply {
                    s.addedTracks.forEach { (id, tracks) -> put(id, JSONArray(tracks)) }
                },
            )
            put("hiddenPlaylists", JSONArray(s.hiddenBuiltInPlaylists.toList()))
            put("queueIds", JSONArray(s.queueIds))
            put("queueIndex", s.queueIndex)
            put("progressSec", s.progressSec.toDouble())
            put("currentSongId", s.currentSongId ?: JSONObject.NULL)
            put("songSort", s.songSort)
            put("shuffleOn", s.shuffleOn)
            put("repeatMode", s.repeatMode)
            put("playbackSpeed", s.playbackSpeed.toDouble())
            put("scanFilterShort", s.scanFilterShort)
            // 播放次数（v7）：id → 次数；类型 nullable 但 encode 侧来源恒为 state 的非空快照值，
            // 空表也写（结构稳定，解码侧 has() 区分「字段缺失=保留现有」与「显式空=如实清零」）
            put(
                "playCounts",
                JSONObject().apply { s.playCounts.orEmpty().forEach { (id, n) -> put(id, n) } },
            )
            // 自定义主题色（v7）：null 存 JSONObject.NULL，解码侧 isNull 判断
            put("customSeedArgb", s.customSeedArgb ?: JSONObject.NULL)
            // 队列语境标签（v8）：null 存 JSONObject.NULL（「来自『…』」展示）
            put("queueSource", s.queueSource ?: JSONObject.NULL)
            // 睡眠定时器（v9）：分钟档模式 + 到期时间戳；0 值照写（结构稳定，读侧 opt 默认值同型）
            put("sleepTimerMode", s.sleepTimerMode)
            put("sleepTimerDeadlineMs", s.sleepTimerDeadlineMs)
        }.toString()

        /**
         * JSON 优先；非 JSON（旧格式）走 legacy 解析迁移；损坏数据记日志后返回 null。
         *
         * P3-62：解析前先做 v 号前瞻——备份来自更新版本（v > VERSION）未来 schema 会被
         * decodeJson 的 opt 默认值静默拆解成半空快照，宁可判失败也不吞。
         */
        private fun decode(raw: String): AppStateSnapshot? {
            // v 号前瞻（P3-62）：JSON 格式才看 v；旧格式（v1）无 v 号直接走 legacy
            if (raw.trimStart().startsWith("{")) {
                val v = runCatching { JSONObject(raw).optInt("v", 0) }.getOrDefault(0)
                if (v > VERSION) {
                    Log.e(TAG, "snapshot from newer version (v=$v > $VERSION); refusing to decode")
                    return null
                }
            }
            val snapshot = if (raw.trimStart().startsWith("{")) {
                runCatching { decodeJson(JSONObject(raw)) }
            } else {
                runCatching { decodeLegacy(raw) }
            }.getOrNull()
            if (snapshot == null) Log.e(TAG, "snapshot decode failed; ignoring (len=${raw.length})")
            return snapshot
        }

        private fun decodeJson(o: JSONObject): AppStateSnapshot = AppStateSnapshot(
            followCover = o.optBoolean("followCover", true),
            lyricEnabled = o.optBoolean("lyricEnabled", true),
            glassLevel = o.optString("glassLevel", "Standard"),
            motionLevel = o.optString("motionLevel", "Standard"),
            eqPreset = o.optString("eqPreset", "Off"),
            themeMode = o.optString("themeMode", "Dark"),
            excludedFolders = o.stringSet("excludedFolders"),
            favorites = o.stringSet("favorites"),
            searchHistory = o.stringList("searchHistory"),
            playedIds = o.stringList("playedIds"),
            userPlaylists = o.optJSONArray("userPlaylists")?.let { arr ->
                List(arr.length()) { i ->
                    arr.getJSONObject(i).let { p ->
                        PlaylistSnapshot(
                            id = p.getString("id"),
                            title = p.optString("title", ""),
                            trackIds = p.optJSONArray("tracks")?.strings() ?: emptyList(),
                        )
                    }
                }
            } ?: emptyList(),
            addedTracks = o.optJSONObject("addedTracks")?.let { obj ->
                buildMap {
                    obj.keys().forEach { id -> put(id, obj.optJSONArray(id)?.strings() ?: emptyList()) }
                }
            } ?: emptyMap(),
            hiddenBuiltInPlaylists = o.stringSet("hiddenPlaylists"),
            queueIds = o.stringList("queueIds"),
            queueIndex = o.optInt("queueIndex", 0),
            progressSec = o.optDouble("progressSec", 0.0).toFloat(),
            currentSongId = o.optString("currentSongId").takeIf { o.has("currentSongId") && !o.isNull("currentSongId") },
            songSort = o.optString("songSort", "Title"),
            shuffleOn = o.optBoolean("shuffleOn", false), // 旧版本（≤v3）无此字段：走默认值零迁移
            repeatMode = o.optString("repeatMode", "Off"),
            playbackSpeed = o.optDouble("playbackSpeed", 1.0) // 旧版本（≤v4）无此字段：走默认值零迁移
                .toFloat().takeIf { it > 0f } ?: 1f,
            scanFilterShort = o.optBoolean("scanFilterShort", true), // 旧版本（≤v5）无此字段：走默认值零迁移
            // 播放次数三态：键缺失（≤v6 旧备份）→ null（恢复时保留现有计数不清零）；
            // 键存在（含显式空对象）→ 解析出 Map（空表 = 如实清零）
            playCounts = if (o.has("playCounts")) {
                o.optJSONObject("playCounts")?.let { obj ->
                    buildMap { obj.keys().forEach { id -> put(id, obj.optInt(id, 0)) } }
                } ?: emptyMap()
            } else null,
            customSeedArgb = o.takeIf { it.has("customSeedArgb") && !it.isNull("customSeedArgb") }
                ?.optInt("customSeedArgb"), // 旧版本（≤v6）无此字段：null 零迁移
            queueSource = o.takeIf { it.has("queueSource") && !it.isNull("queueSource") }
                ?.optString("queueSource"), // 旧版本（≤v7）无此字段：null 零迁移
            sleepTimerMode = o.optInt("sleepTimerMode", 0), // 旧版本（≤v8）无此字段：默认 0 零迁移
            sleepTimerDeadlineMs = o.optLong("sleepTimerDeadlineMs", 0L), // 旧版本（≤v8）无此字段：默认 0 零迁移
        )

        // ── v1（旧版）：\u001F 分字段 / \u001E 分集合项 / \u001D 分子字段，仅作迁移 ──

        private const val F = "\u001F"
        private const val I = "\u001E"
        private const val S = "\u001D"

        @Suppress("DEPRECATION")
        private fun decodeLegacy(raw: String): AppStateSnapshot {
            val f = raw.split(F)
            require(f.size >= 15) { "bad legacy snapshot" }
            fun set(i: Int) = f[i].split(I).filter { it.isNotEmpty() }.toSet()
            fun list(i: Int) = f[i].split(I).filter { it.isNotEmpty() }
            fun playlists(i: Int) = list(i).mapNotNull { item ->
                val p = item.split(S)
                if (p.size < 2) null else PlaylistSnapshot(p[0], p[1], p.getOrElse(2) { "" }.split(',').filter { it.isNotEmpty() })
            }
            fun tracks(i: Int): Map<String, List<String>> = list(i).mapNotNull { item ->
                val p = item.split(S)
                if (p.size < 2) null else p[0] to p[1].split(',').filter { it.isNotEmpty() }
            }.toMap()
            return AppStateSnapshot(
                followCover = f[0] == "true",
                lyricEnabled = f[1] == "true",
                glassLevel = f[2],
                motionLevel = f[3],
                eqPreset = f[4],
                themeMode = f.getOrElse(5) { "Dark" },
                excludedFolders = set(6),
                favorites = set(7),
                searchHistory = list(8),
                playedIds = list(9),
                userPlaylists = playlists(10),
                addedTracks = tracks(11),
                queueIds = list(12),
                queueIndex = f[13].toIntOrNull() ?: 0,
                progressSec = f[14].toFloatOrNull() ?: 0f,
                currentSongId = null, // 旧格式没有当前曲 id：恢复时按原索引兜底
            )
        }
    }
}
