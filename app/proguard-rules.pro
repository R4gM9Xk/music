# R8 规则 —— ColorOS 音乐
#
# ## 为什么这份文件很短
#
# Compose 与 Media3 都在自己的 AAR 里带 consumer rules（`META-INF/proguard/*.pro`），
# AGP 会自动合并，所以正常情况下应用侧不需要补任何规则。本文件刻意保持最小，
# 只处理「库没覆盖到、但一开 R8 就会踩」的几类真实风险。
#
# ## 排查方式（留给下一个接手的人）
#
# 若 release 包出现 `ClassNotFoundException` / `NoSuchMethodError` / 界面空白，
# 先确认是不是 R8 裁多了：
#   1. 临时把 `isMinifyEnabled` 改回 false 验证问题是否消失
#   2. 若确认是 R8，用 `app/build/outputs/mapping/release/mapping.txt` 反混淆崩溃栈
#      （该文件每次发版必须归档，否则线上崩溃栈不可读）
#   3. 定位到具体类后，在下面加**尽可能窄**的 keep 规则

# ── 崩溃采集：必须保住堆栈信息，否则 R8 出来的是 a.b.c.d 毫无用处 ──
# 这一条是"为了让已有的崩溃日志功能在 release 包上仍然可用"，不是可选项。
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ── Media3 / ExoPlayer ──
# 播放器通过反射加载音频渲染器与提取器（如 DefaultRenderersFactory 按 MIME 探测），
# 类名被混淆后探测会失败，表现为「格式不支持播放」。
-keep class androidx.media3.exoplayer.** { *; }
-keep class androidx.media3.extractor.** { *; }
-keep class androidx.media3.common.** { *; }
-dontwarn androidx.media3.**

# ── Kotlin 元数据 / 协程 ──
-keepclassmembers class kotlin.Metadata { public <methods>; }
-dontwarn kotlinx.coroutines.**

# ── 小部件（RemoteViews 路线）──
# AppWidgetProvider 由系统按 manifest 里的类名反射实例化，
# 被混淆成短名后系统找不到类，表现是「小部件添加后一片空白」。
-keep class * extends android.appwidget.AppWidgetProvider { *; }
-keep class com.local.music.widget.** { *; }

# ── MediaSession 的 ControllerInfo / 自定义命令 ──
# Media3 的自定义 SessionCommand 走包名 + action 字符串匹配，
# 混淆后 onCustomCommand 可能收不到（症状：EQ / 定时 / 音频链开关全部失效）。
-keep class com.local.music.player.** { *; }

# ── 枚举与序列化（快照 v10 的字段名参与 JSON 键）──
# 本工程的快照是手写 JSONObject 键名（非反射序列化），不受混淆影响；
# 但枚举的 valueOf/name() 在快照里用于持久化（如 EqPreset / RepeatMode），
# 名字被改会让旧快照读不回来。
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# ── 移除日志代码（release 包减少体积与信息泄漏）──
# 注意：**不要**移除 Log.e / Log.w —— 崩溃与错误路径的日志是用户反馈问题时
# 唯一能对上的线索，且本工程的 Log 调用已全部在 runCatching 或错误分支里。
-assumenosideeffects class android.util.Log {
    public static *** v(...);
    public static *** d(...);
    public static *** i(...);
}
