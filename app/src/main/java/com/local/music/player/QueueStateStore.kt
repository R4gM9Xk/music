package com.local.music.player

import android.content.Context
import com.local.music.core.ui.state.AppStateSnapshot
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
 * 只存恢复播放必需的字段（队列 specs + 当前索引 + 进度），不重复存歌单/设置。
 */
object QueueStateStore {

    private const val FILE = "queue_state.json"
    private const val VERSION = 1

    /** 服务端恢复所需的现场 */
    data class QueueState(
        val specs: List<SongSpec>,
        val index: Int,
        val positionSec: Float,
    )

    /**
     * 写现场：从快照还原出 SongSpec 列表。
     * 快照只存 id（稳定 id = "f" + 绝对路径），specs 的其余字段（标题/艺术家/封面/时长）
     * 在这里无法从 id 反推——所以**由调用方传入完整的 specs**（AppState 侧手里就有 DemoSong）。
     */
    fun write(context: Context, specs: List<SongSpec>, index: Int, positionSec: Float) {
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
        }
    }

    /** 读现场：无/损坏/版本不符返回 null（服务端走默认行为，不阻塞） */
    fun read(context: Context): QueueState? = runCatching {
        val file = java.io.File(context.filesDir, FILE)
        if (!file.isFile) return@runCatching null
        val root = JSONObject(file.readText())
        if (root.optInt("v") != VERSION) return@runCatching null
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
        QueueState(
            specs = specs,
            index = root.optInt("index", 0).coerceIn(0, specs.lastIndex),
            positionSec = root.optDouble("positionSec", 0.0).toFloat().coerceAtLeast(0f),
        )
    }.getOrNull()

    /** 清空现场（用户清空队列 / 退出登录等场景） */
    fun clear(context: Context) {
        runCatching { java.io.File(context.filesDir, FILE).delete() }
    }
}
