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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

private val Context.appDataStore: androidx.datastore.core.DataStore<Preferences> by preferencesDataStore(name = "glass_app_state")

/**
 * 持久化实现（DataStore Preferences）：
 * - 快照编码为带版本号的 JSON（v6），歌单名 / 搜索词等用户文本无需转义
 * - 旧版控制字符分隔格式（v1）在读取时一次性迁移
 * - schedule 防抖 500ms 写盘；write 失败只记日志（不杀死防抖 collector）；
 *   flush 供退后台 / 关键节点立即落盘
 * - scanFilterShort 另存独立 Preferences 键：MediaLibrary 扫描不经 AppState 就能读到当前开关（见 readScanFilterShort）
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

    suspend fun read(): AppStateSnapshot? = withContext(Dispatchers.IO) {
        val raw = appContext.appDataStore.data.first()[KEY_STATE] ?: return@withContext null
        decode(raw)
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
        private const val VERSION = 6

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
        }.toString()

        /** JSON 优先；非 JSON（旧格式）走 legacy 解析迁移；损坏数据记日志后返回 null */
        private fun decode(raw: String): AppStateSnapshot? {
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
