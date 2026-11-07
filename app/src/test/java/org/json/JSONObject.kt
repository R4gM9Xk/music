package org.json

/**
 * org.json 测试夹具（**仅存在于 test sourceSet，不进 APK**）——真实语义的最小实现。
 *
 * ## 这个文件为什么存在
 *
 * Android 单元测试的 `android.jar` 里 `org.json.*` 是 `Stub!` 桩：每个方法要么抛
 * `RuntimeException("Stub!")`，要么（在 `isReturnDefaultValues = true` 下）静默返回
 * null/0/false。`SettingsStore` 的 encode/decode 是用户数据安全的唯一防线
 * （快照 v1→v10 迁移链 + 备份格式），没有真实 JSON 实现它根本没法测。
 * 本机离线缓存没有 `org.json:json` 正品，所以手工提供**语义一致**的最小实现。
 *
 * ## 语义基准（对照 json.org / AOSP 的 org.json，不是「能跑就行」）
 *
 * - [NULL] 哨兵：`put(key, null)` 移除该键（json.org 语义）；写入 NULL 哨兵则保留键但
 *   `isNull(key) == true`。**它与「键不存在」必须可区分**——本工程的播放次数三态
 *   （缺失=保留 / 空表=清零）、customSeedArgb 的「缺失=零迁移」全靠 `has()` 与
 *   `isNull()` 的组合，二者缺一不可。
 * - `optString(key, fallback)`：键缺失 → fallback；值为 NULL → fallback；
 *   其他非字符串值 → **toString 强转**（json.org 的 optString 对 Integer 等做 coerce）。
 *   依据：json.org 原版 `optString(key, defaultValue)` =
 *   `NULL.equals(opt(key)) ? defaultValue : object.toString()`，且 NULL 的 equals
 *   采用 SQL 语义（NULL.equals(null) == true，所以「缺失」同样落 fallback）。
 * - `optInt/optLong/optDouble/optBoolean`：类型不匹配或键缺失 → fallback，绝不抛
 *   （decode 侧「坏数据给默认值而不是崩溃」防线的实现基础）；Boolean 接受字符串
 *   "true"/"false"（json.org getBoolean 的 coerce 行为）。
 * - `toString()`：合法 JSON；键序 = 插入序（json.org 内部即 HashMap→ LinkedHashMap 序，
 *   这里统一 LinkedHashMap 保证与真实实现一致）；**CJK 不转义**（理由见 JsonRuntime）。
 * - 数字：Int/Long 原样；整数值 Double 序列化为不带小数点的整数（json.org 行为，
 *   `0.0 → "0"`），读回经 Number 统一转换，往返无损。
 *
 * ## 已知差异（都记录在这里，别让下一个人猜）
 *
 * 1. 只实现 `SettingsStore` 与测试实际用到的 API 面；缺的方法以编译错误形式暴露——
 *    那是显式的好事，不是疏漏。
 * 2. `optInt` 系列**不接受数字字符串**（json.org 会把 "42" coerce 成 42）。本工程
 *    encode 侧从不把数字写成字符串，测试也不依赖该 coerce，为控制实现面积而省略。
 * 3. 未实现 json.org 的严格校验（如 reject 重复键、非对象顶层）；夹具只服务受控输入，
 *    非法输入统一走「抛 JSONException」——这正是 decode 降级路径需要的行为。
 */
class JSONObject {

    // ── 构造 ──────────────────────────────────────────────

    /** 从 JSON 文本解析（decode 与测试的边界样本都在用）。非法输入抛 [JSONException]。 */
    constructor(json: String) {
        val p = JsonParser(json)
        p.skipWhitespace()
        p.expectObjectStart()
        p.parseInto(this)
        p.skipWhitespace()
        p.expectEnd()
    }

    /** 空对象（encode 侧大量 `JSONObject().apply { ... }`） */
    constructor()

    // ── 写入（encode 侧在用）──────────────────────────────

    /**
     * json.org 语义：`put(name, (Object) null)` 等价于 `remove(name)`。
     * 要表达「显式 null」必须写 [NULL] 哨兵（SettingsStore 正是这么做的）。
     */
    fun put(key: String, value: Any?): JSONObject {
        if (value == null || value === NULL) {
            if (value === NULL) {
                map[key] = NULL // 保留键位：has(key)==true 而 isNull(key)==true
            } else {
                map.remove(key) // 真 null：移除
            }
        } else {
            map[key] = value
        }
        return this
    }

    fun put(key: String, value: Boolean): JSONObject = putAny(key, value)
    fun put(key: String, value: Int): JSONObject = putAny(key, value)
    fun put(key: String, value: Long): JSONObject = putAny(key, value)
    fun put(key: String, value: Double): JSONObject = putAny(key, value)
    fun put(key: String, value: String): JSONObject = putAny(key, value)

    private fun putAny(key: String, value: Any): JSONObject {
        map[key] = value
        return this
    }

    // ── 查询（decode 侧在用）──────────────────────────────

    /** 键是否存在。三态语义的根基：has=false = 字段缺失（isNull 同为 true 但含义不同） */
    fun has(key: String): Boolean = map.containsKey(key)

    /** 键缺失**或**值为 NULL → true（json.org 语义：isNull 合并这两种情况） */
    fun isNull(key: String): Boolean {
        val v = map[key] ?: return true
        return v === NULL
    }

    /** 原始取值：缺失 → null。要区分「缺失」与「NULL」必须先 has()。 */
    fun opt(key: String): Any? = map[key]

    /**
     * json.org 语义：缺失/NULL → ""；**其他非字符串值 toString 强转**；String 原样。
     *
     * **为什么必须是真重载而不是默认参数**：产品代码（SettingsStore）是针对 android.jar
     * 桩编译的，`optString("currentSongId")` 这类单参调用在字节码里绑定的就是
     * `optString(Ljava/lang/String;)Ljava/lang/String;` 这个签名。Kotlin 的默认参数
     * 只生成合成 `optString$default` 桥，**不生成单参重载**，运行期就是
     * `NoSuchMethodError: optString(java.lang.String)`——这是夹具踩过的第二个真实坑
     * （第一个是 NULL 需要 @JvmField，同一类问题：夹具必须提供**与桩一致的 JVM 签名**，
     * 光「Kotlin 里能调用」是不够的）。
     */
    fun optString(key: String): String = optString(key, "")

    /** 双参形态（json.org 语义：缺失/NULL → fallback；非字符串值 toString 强转） */
    fun optString(key: String, fallback: String): String {
        val v = map[key] ?: return fallback
        if (v === NULL) return fallback
        return v.toString()
    }

    /** 单参形态：android.jar 桩里存在 `optInt(String)`，见 optString 的签名说明 */
    fun optInt(key: String): Int = optInt(key, 0)

    fun optInt(key: String, fallback: Int): Int = numberOrNull(key)?.toInt() ?: fallback
    fun optLong(key: String, fallback: Long = 0L): Long = numberOrNull(key)?.toLong() ?: fallback

    /** 无 fallback 实参时 json.org 的默认是 NaN；本工程调用点全部显式传 fallback */
    fun optDouble(key: String, fallback: Double = Double.NaN): Double =
        numberOrNull(key)?.toDouble() ?: fallback

    /** Int/Long/Double 统一经 Number 取值；Boolean/String 不是 Number（回落 fallback） */
    private fun numberOrNull(key: String): Number? = map[key] as? Number

    fun optBoolean(key: String, fallback: Boolean = false): Boolean {
        val v = map[key] ?: return fallback
        if (v is Boolean) return v
        // json.org getBoolean 的 coerce：字符串 "true"/"false"（忽略大小写）按布尔处理
        if (v is String) {
            return when (v.lowercase()) {
                "true" -> true
                "false" -> false
                else -> fallback
            }
        }
        return fallback
    }

    fun optJSONArray(key: String): JSONArray? = map[key] as? JSONArray
    fun optJSONObject(key: String): JSONObject? = map[key] as? JSONObject

    fun getJSONObject(key: String): JSONObject =
        map[key] as? JSONObject ?: throw JSONException("Value for $key is not a JSONObject")

    fun getString(key: String): String {
        val v = map[key]
        if (v is String) return v
        // json.org：NULL/缺失走抛异常路径（getString 对缺失抛 JSONException）
        throw JSONException("Value for $key is not a String")
    }

    /** 动态键容器（playCounts/addedTracks/trackRefs）的键迭代 */
    fun keys(): MutableIterator<String> = map.keys.iterator()

    /** 对象内键数（trackRefs 容量预分配在用） */
    fun length(): Int = map.size

    // ── 序列化 ────────────────────────────────────────────

    override fun toString(): String {
        val sb = StringBuilder()
        sb.append('{')
        var first = true
        for ((k, v) in map) {
            if (!first) sb.append(',')
            first = false
            JsonWriter.writeString(sb, k)
            sb.append(':')
            JsonWriter.writeValue(sb, v)
        }
        sb.append('}')
        return sb.toString()
    }

    companion object {
        /**
         * json.org 的 NULL 哨兵（`public static final Object NULL`）。
         *
         * **必须用 @JvmField**：产品代码（SettingsStore）是针对 android.jar 桩编译的，
         * 桩里 NULL 是 **public static 字段**，编译产物是直接 getstatic 访问字段；
         * 普通 `val` 会编译成 getter + 私有字段，运行期就是
         * `IllegalAccessError: tried to access private field NULL`——
         * 这是夹具第一版踩过的真实坑，测试报的不是「断言失败」而是这个错。
         */
        @JvmField
        val NULL: Any = object : Any() {
            override fun equals(other: Any?): Boolean = other === this || other == null
            override fun hashCode(): Int = java.lang.System.identityHashCode(this)
            override fun toString(): String = "null"
        }
    }

    // 键值存储：LinkedHashMap 保持插入序（对齐 json.org 的键序行为）
    private val map = LinkedHashMap<String, Any?>()
}
