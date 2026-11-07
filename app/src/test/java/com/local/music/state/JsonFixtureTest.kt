package com.local.music.state

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * org.json 夹具自身的**语义自检**（不是产品测试）。
 *
 * 为什么存在：这个夹具是手工实现，若它与真实 org.json 行为有偏差，
 * 那些依赖它的测试（[SettingsSnapshotTest]）就会「绿得没有意义」。
 * 这组用例把夹具的关键语义逐条钉死——任何一条红，都说明夹具本身坏了，
 * 必须修夹具，而不是去改依赖它的测试。
 */
class JsonFixtureTest {

    @Test
    fun `put_null_移除键_与_json_org_一致`() {
        val o = JSONObject()
        o.put("a", "x")
        o.put("b", null as Any?)
        // 真 null = remove：键消失（json.org put(String,null) 的语义）
        assertTrue(o.has("a"))
        assertFalse(o.has("b"))
    }

    @Test
    fun `NULL_哨兵保留键位_has与isNull可区分缺失`() {
        val o = JSONObject()
        o.put("explicit", JSONObject.NULL)
        // 显式 NULL：键存在（has=true）且值是 null（isNull=true）
        assertTrue(o.has("explicit"))
        assertTrue(o.isNull("explicit"))
        // 完全缺失：has=false，isNull 同样 true（json.org 合并语义）
        assertFalse(o.has("missing"))
        assertTrue(o.isNull("missing"))
    }

    @Test
    fun `optString_对_NULL返回fallback_对缺失返回fallback`() {
        val o = JSONObject()
        o.put("n", JSONObject.NULL)
        o.put("s", "val")
        assertEquals("FB", o.optString("n", "FB")) // NULL → fallback
        assertEquals("FB", o.optString("missing", "FB")) // 缺失 → fallback
        assertEquals("val", o.optString("s", "FB")) // 正常字符串原样
        o.put("i", 42)
        // json.org 的 optString 对非字符串做 toString 强转
        assertEquals("42", o.optString("i", "FB"))
    }

    @Test
    fun `三态语义_playCounts_依赖的has组合`() {
        val o = JSONObject("""{"playCounts": {"f/a.mp3": 42}}""")
        assertTrue(o.has("playCounts"))
        val pc = o.optJSONObject("playCounts")!!
        assertEquals(42, pc.optInt("f/a.mp3", 0))
        // 缺失分支
        val empty = JSONObject("""{"v": 6}""")
        assertFalse(empty.has("playCounts"))
    }

    @Test
    fun `数字往返无损_含Double整数值`() {
        val o = JSONObject()
        o.put("i", 42)
        o.put("l", 9_000_000_000L)
        o.put("d", 0.0)
        o.put("d2", 1.5)
        val back = JSONObject(o.toString())
        assertEquals(42, back.optInt("i", 0))
        assertEquals(9_000_000_000L, back.optLong("l", 0L))
        assertEquals(0.0, back.optDouble("d", -1.0), 1e-9)
        assertEquals(1.5, back.optDouble("d2", -1.0), 1e-9)
    }

    @Test
    fun `CJK与特殊字符转义往返`() {
        val o = JSONObject()
        o.put("中文", "值含\"引号\"与\\反斜杠 和换行\n")
        val back = JSONObject(o.toString())
        // CJK 原样（不转义成 \\uXXXX），但引号/反斜杠/换行正确转义
        assertTrue(o.toString().contains("中文"))
        assertEquals("值含\"引号\"与\\反斜杠 和换行\n", back.optString("中文", ""))
    }

    @Test
    fun `损坏输入抛JSONException_而不是静默`() {
        val inputs = listOf(
            """{"v": 9, "favorites": ["f/a.mp3" """, // 截断
            "这不是备份文件，随便一段文字", // 非 JSON
            "", // 空串
            """{"v": 1, "favorites": "不是数组"}""", // 类型错乱（读侧才降级，构造不抛）
        )
        // 前三个必须抛 JSONException（decode 的 runCatching 降级依赖）
        for (bad in inputs.take(3)) {
            val r = runCatching { JSONObject(bad) }
            assertTrue("'$bad' 应抛 JSONException，实际=${r.exceptionOrNull()?.javaClass?.simpleName}", r.isFailure)
        }
        // 第四个：构造成功（值本身是合法 JSON），但读侧类型不匹配时回落默认值
        val r4 = runCatching { JSONObject(inputs[3]) }
        assertTrue(r4.isSuccess)
        assertEquals("不是数组", r4.getOrNull()?.optString("favorites", "X"))
    }

    @Test
    fun `toString_键序与结构正确`() {
        val o = JSONObject()
        o.put("z", 1)
        o.put("a", 2)
        // LinkedHashMap 保持插入序
        assertEquals("""{"z":1,"a":2}""", o.toString())
    }
}
