package org.json

/**
 * org.json 测试夹具（**仅存在于 test sourceSet，不进 APK**）——真实语义的最小实现。
 *
 * 语义基准与已知差异统一记录在 [JSONObject] 的类注释里。本文件只补充 JSONArray 特有行为：
 *
 * - `optString(index, fallback)`：越界 → fallback；元素是 NULL → fallback（同 JSONObject）。
 *   `trackRefs` 的 4 元数组解析依赖它对**越界安全**的保证（`optString(0..3, "")`）。
 * - `getJSONObject(index)` / `getString(index)`：userPlaylists 逐项解析在用，
 *   类型错必须抛——外层 runCatching 依赖这个抛出触发「坏一条只丢一条」的降级。
 * - `constructor(collection: Collection<*>)`：encode 侧 `JSONArray(s.favorites.toList())`
 *   在用；集合里的 null 元素按 json.org 语义存 NULL 哨兵。
 */
class JSONArray {

    private val list = ArrayList<Any?>()

    // ── 构造 ──────────────────────────────────────────────

    /** 从 JSON 文本解析。非法输入抛 [JSONException]。 */
    constructor(json: String) {
        val p = JsonParser(json)
        p.skipWhitespace()
        p.expectArrayStart()
        p.parseInto(this)
        p.skipWhitespace()
        p.expectEnd()
    }

    /** 空数组 */
    constructor()

    /** 由集合构造；null 元素存 [JSONObject.NULL] 哨兵（json.org 语义） */
    constructor(collection: Collection<*>) {
        collection.forEach { put(it ?: JSONObject.NULL) }
    }

    // ── 写入 ──────────────────────────────────────────────

    /** null → NULL 哨兵（json.org：null 永远不会被存成真 null） */
    fun put(value: Any?): JSONArray {
        list.add(value ?: JSONObject.NULL)
        return this
    }

    fun put(value: Boolean): JSONArray = putAny(value)
    fun put(value: Int): JSONArray = putAny(value)
    fun put(value: Long): JSONArray = putAny(value)
    fun put(value: Double): JSONArray = putAny(value)
    fun put(value: String): JSONArray = putAny(value)

    private fun putAny(v: Any): JSONArray {
        list.add(v)
        return this
    }

    // ── 查询 ──────────────────────────────────────────────

    fun length(): Int = list.size

    /** 原始取值：越界 → null（json.org opt(i) 的越界安全） */
    fun opt(i: Int): Any? = list.getOrNull(i)

    /** 越界 / NULL / 非字符串 → fallback；其他非字符串值 toString 强转（同 JSONObject） */
    fun optString(i: Int, fallback: String = ""): String {
        val v = list.getOrNull(i) ?: return fallback
        if (v === JSONObject.NULL) return fallback
        return v.toString()
    }

    fun optInt(i: Int, fallback: Int = 0): Int = (list.getOrNull(i) as? Number)?.toInt() ?: fallback

    /** 强类型读取：越界/类型错抛 JSONException（降级路径依赖这个抛出） */
    fun getString(i: Int): String =
        list.getOrNull(i) as? String ?: throw JSONException("Value at $i is not a String")

    fun getJSONObject(i: Int): JSONObject =
        list.getOrNull(i) as? JSONObject ?: throw JSONException("Value at $i is not a JSONObject")

    // ── 序列化 ────────────────────────────────────────────

    override fun toString(): String {
        val sb = StringBuilder()
        sb.append('[')
        var first = true
        for (v in list) {
            if (!first) sb.append(',')
            first = false
            JsonWriter.writeValue(sb, v)
        }
        sb.append(']')
        return sb.toString()
    }
}
