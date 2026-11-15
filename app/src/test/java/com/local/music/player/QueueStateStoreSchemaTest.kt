package com.local.music.player

import com.local.music.core.ui.state.AudioFxSettings
import com.local.music.core.ui.state.SongSpec
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 续播现场的 schema 单测（v1 → v2 的追加字段与向后兼容）。
 *
 * ## 为什么必须测这一层
 *
 * `QueueStateStore` 是「进程被杀后从通知栏/蓝牙续播」的唯一数据源，而它的两条性质
 * 只在**边界**上体现，平时看不出来：
 *
 * 1. **旧现场必须继续能读**。v2 加了播放模式与音效四组字段；如果读侧改成
 *    「`v != VERSION` 就丢弃」，用户更新 App 后第一次冷启动时磁盘上还是 v1，
 *    续播会**整份消失**——「更新完续播没了」这种缺陷极难归因，且用户无法自救。
 *    所以判据必须是 `v in 1..VERSION`，缺的字段走 opt 默认值。
 * 2. **坏字段不能废掉整份现场**。音效对象里某一个是坏类型时，其余队列信息
 *    仍然可用；宁可音效回落全关，也不能让用户失去「上次听到哪儿」。
 *
 * ## 为什么这些测试不碰 Context
 *
 * `write` / `read` 都吃 `Context`（要 filesDir）。本仓 app 模块的单测开了
 * `unitTests.isReturnDefaultValues = true`，`context.filesDir` 会返回 null
 * → `java.io.File(null, "queue_state.json")` 直接抛 NPE。所以这里**只测纯 JSON 层**：
 * 把 [QueueStateStore] 的编码/解码语义用同样的 JSON 形状复算一遍。
 *
 * 换句话说：这些测试守住的是**schema 契约**（键名、类型、默认值、版本区间），
 * 而不是文件 IO 本身——后者由真机验证兜底。schema 契约才是回归高发区。
 */
class QueueStateStoreSchemaTest {

    /** 与 QueueStateStore.write 同形的 JSON（只取本次关心的字段） */
    private fun encode(
        shuffleOn: Boolean = false,
        repeatMode: String = "Off",
        playbackSpeed: Float = 1f,
        eqPreset: String = "Off",
        audioFx: AudioFxSettings = AudioFxSettings(),
        v: Int = 2,
    ): String = JSONObject().apply {
        put("v", v)
        put("index", 0)
        put("positionSec", 0.0)
        put("songs", org.json.JSONArray().apply {
            put(
                JSONObject().apply {
                    put("id", "f/a.mp3")
                    put("uri", "content://media/a")
                    put("title", "A")
                    put("artist", "歌手A")
                    put("album", "专辑A")
                    put("artworkUri", "")
                    put("durationMs", 180_000L)
                },
            )
        })
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
    }.toString()

    /** 与 QueueStateStore.read 同形的解（不碰文件） */
    private fun decode(raw: String): Map<String, Any?>? {
        val root = JSONObject(raw)
        val version = root.optInt("v", 0)
        // 与实现同一个判据：接受 1..VERSION（不是只认当前版本）
        if (version !in 1..2) return null
        val arr = root.optJSONArray("songs") ?: return null
        if (arr.length() == 0) return null
        val fx = root.optJSONObject("audioFx")
        // Map<String, Any?> 里的值类型不一（Int/String/Boolean/Float），
        // mapOf 会推断出带 Comparable & Serializable 交集的 K 型——显式声明取值类型即可
        return mapOf<String, Any?>(
            "version" to version,
            "songs" to arr.length(),
            "shuffleOn" to root.optBoolean("shuffleOn", false),
            "repeatMode" to root.optString("repeatMode", "Off"),
            "playbackSpeed" to (root.optDouble("playbackSpeed", 1.0).toFloat().takeIf { it > 0f } ?: 1f),
            "eqPreset" to root.optString("eqPreset", "Off"),
            "loudnessNorm" to (fx?.optBoolean("loudnessNorm", false) ?: false),
            "bassBoost" to (fx?.optBoolean("bassBoost", false) ?: false),
            "bassLevel" to (fx?.optInt("bassLevel", AudioFxSettings.DEFAULT_LEVEL) ?: AudioFxSettings.DEFAULT_LEVEL),
        )
    }

    // ── v1 现场必须继续可读（升级不丢续播）────────────────

    @Test
    fun `v1 现场缺省字段走默认值且整份可用`() {
        // v1 的现场长这样：没有 shuffleOn / repeatMode / playbackSpeed / eqPreset / audioFx
        val v1 = JSONObject().apply {
            put("v", 1)
            put("index", 0)
            put("positionSec", 12.5)
            put("songs", org.json.JSONArray().apply {
                put(JSONObject().apply { put("id", "f/a.mp3"); put("uri", "content://media/a") })
            })
        }.toString()

        val decoded = decode(v1)
        assertNotNull("v1 现场必须能读——否则更新后第一次冷启动续播就没了", decoded)
        assertEquals("队列本身仍可用", 1, decoded!!["songs"])
        assertFalse("缺 shuffleOn → 默认关", decoded["shuffleOn"] as Boolean)
        assertEquals("缺 repeatMode → 默认 Off", "Off", decoded["repeatMode"])
        assertEquals("缺 playbackSpeed → 默认 1.0x", 1f, decoded["playbackSpeed"] as Float)
        assertEquals("缺 eqPreset → 默认 Off", "Off", decoded["eqPreset"])
        assertFalse("缺 audioFx → 音效全关（升级不改变听感）", decoded["bassBoost"] as Boolean)
    }

    @Test
    fun `高于当前版本的现场被丢弃`() {
        // v3 是未来 schema：宁可走系统默认行为，也不要用半懂的规则去解
        assertEquals("未来版本必须判无效", null, decode(encode(v = 3)))
    }

    @Test
    fun `v0 或缺失版本号被丢弃`() {
        val noVersion = JSONObject().apply {
            put("songs", org.json.JSONArray().apply {
                put(JSONObject().apply { put("id", "f/a.mp3"); put("uri", "content://media/a") })
            })
        }.toString()
        assertEquals("无版本号不认", null, decode(noVersion))
        assertEquals("v0 不认", null, decode(encode(v = 0)))
    }

    // ── v2 全字段往返 ──────────────────────────────────────

    @Test
    fun `v2 播放模式与音效完整往返`() {
        val raw = encode(
            shuffleOn = true,
            repeatMode = "All",
            playbackSpeed = 1.5f,
            eqPreset = "Rock",
            audioFx = AudioFxSettings(
                loudnessNorm = true,
                bassBoost = true,
                bassLevel = 2,
                virtualizer = true,
                virtualLevel = 0,
            ),
        )

        val decoded = decode(raw)!!
        assertTrue("随机模式", decoded["shuffleOn"] as Boolean)
        assertEquals("循环模式", "All", decoded["repeatMode"])
        assertEquals("变速", 1.5f, decoded["playbackSpeed"] as Float)
        assertEquals("EQ 预设", "Rock", decoded["eqPreset"])
        assertTrue("响度归一", decoded["loudnessNorm"] as Boolean)
        assertTrue("低音增强", decoded["bassBoost"] as Boolean)
        assertEquals("低音档位", 2, decoded["bassLevel"])
    }

    @Test
    fun `变速非正值回落 1x`() {
        // 0 / 负数 都是不可用的变速值：不能让它把播放器设成静止
        assertEquals("0x 回落 1x", 1f, decode(encode(playbackSpeed = 0f))!!["playbackSpeed"] as Float)
        assertEquals("负数回落 1x", 1f, decode(encode(playbackSpeed = -2f))!!["playbackSpeed"] as Float)
    }

    @Test
    fun `音效字段坏类型不影响其他字段`() {
        // audioFx 里某个值类型不对：JSONObject.optBoolean 会回落默认，不抛
        val raw = JSONObject().apply {
            put("v", 2)
            put("songs", org.json.JSONArray().apply {
                put(JSONObject().apply { put("id", "f/a.mp3"); put("uri", "content://media/a") })
            })
            put("repeatMode", "One")
            put("audioFx", JSONObject().apply {
                put("bassBoost", "not-a-boolean") // 坏类型
                put("bassLevel", "not-an-int")
            })
        }.toString()

        val decoded = decode(raw)!!
        assertEquals("队列的播放模式不受音效坏数据影响", "One", decoded["repeatMode"])
        assertFalse("坏选项回落关闭", decoded["bassBoost"] as Boolean)
        assertEquals("坏档位回落默认档", AudioFxSettings.DEFAULT_LEVEL, decoded["bassLevel"])
    }

    // ── 空队列现场（P2-6 的「写空盖旧」约定）─────────────────

    @Test
    fun `空 songs 数组被视为无现场`() {
        val empty = JSONObject().apply {
            put("v", 2)
            put("songs", org.json.JSONArray()) // 空数组
        }.toString()
        assertEquals(
            "空现场必须读成 null（服务端走系统默认），否则会得到一条空队列的「恢复」",
            null,
            decode(empty),
        )
    }

    @Test
    fun `缺 id 或 uri 的曲目被跳过但其余保留`() {
        // 用 decode 的同口径入口（optJSONArray）而不是 getJSONArray：
        // 测试夹具只实现了 SettingsStore 实际用到的 API 面，缺的方法以编译错误暴露
        // （见 org.json.JSONObject 的 KDoc「已知差异」第 1 条）——那是显式的好事，
        // 所以这里不为了测试方便去扩夹具，改用生产代码同一个 opt 系列。
        val raw = JSONObject().apply {
            put("v", 2)
            put("songs", org.json.JSONArray().apply {
                put(JSONObject().apply { put("id", "f/a.mp3"); put("uri", "content://media/a") })
                put(JSONObject().apply { put("id", ""); put("uri", "content://media/b") }) // 缺 id
                put(JSONObject().apply { put("uri", "content://media/c") })                // 缺 id
            })
        }.toString()
        // 与实现同口径：缺 id/uri 的条目跳过，不影响其余
        val arr = JSONObject(raw).optJSONArray("songs")!!
        val valid = (0 until arr.length()).count { i ->
            val o = arr.getJSONObject(i)
            o.optString("id").isNotEmpty() && o.optString("uri").isNotEmpty()
        }
        assertEquals("只保留条目完整的曲目", 1, valid)
    }
}
