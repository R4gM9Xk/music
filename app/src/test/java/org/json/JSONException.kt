package org.json

/**
 * JSONException —— org.json 测试夹具的一部分（**仅存在于 test sourceSet，不进 APK**）。
 *
 * 为什么整个夹具都放在 `app/src/test/java/org/json/`：Android 单元测试的 `android.jar`
 * 里 `org.json.*` 只有 `Stub!` 桩（每个方法抛异常，或在
 * `testOptions.unitTests.isReturnDefaultValues = true` 下静默返回 null/0/false）。
 * 而 `SettingsStore` 的 encode/decode 是「用户数据安全」的唯一防线（快照 v1→v10 迁移链、
 * 备份格式），没有真实 JSON 实现，那批测试就只能全部假红或假绿。
 *
 * 同包同名类在 test sourceSet 里会**遮蔽** android.jar 桩（AGP 把 mockable android.jar
 * 排在模块 classpath 之后），产线 APK 不受任何影响。
 */
class JSONException(message: String) : Exception(message)
