import java.util.Properties

// :app —— 应用壳：MainActivity + GlassApp（7 屏导航宿主，共享极光背景）

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.local.music"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.local.music"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 5
        versionName = "1.1.0"
    }

    /**
     * 正式签名：从 `local.properties` / 环境变量读取，**读不到就回退 debug 签名**。
     *
     * 为什么必须回退而不是直接硬编码正式 keystore（04 报告 #8 的建议要打折执行）：
     * 硬编码 `signingConfig = signingConfigs.getByName("release")` 之后，**任何没有该
     * keystore 的环境都构建不了 release**——包括 CI、包括别人 clone 下来的机器、
     * 包括换一台构建机。那会把「仓库 clone 后可构建」这个上一轮刚修好的事又打断一次。
     * 所以这里做成「有就正式、没有就 debug + 显式警告」：
     * - 开发者本机配了 keystore → 打出的包是正式签名
     * - CI / 新人 clone → 仍能 `assembleRelease` 成功，只是日志明确告诉你「这次用的是 debug 签名」
     *
     * 正式密钥的口令**不进版本库**：`local.properties` 已在 `.gitignore` 里
     * （本工程既有约定，见 .gitignore:11-12）。
     *
     * **⚠️ storeFile 路径必须纯 ASCII**：`Properties.load` 按 ISO-8859-1 解码，
     * 路径含中文（本工程目录就是「ColorOS音乐」）时会解成乱码 → `file().isFile`
     * 判 false → **静默回退 debug 签名**且构建不报错。真机踩过一次：
     * keystore 放在工程目录里时打出的包一直是 debug 签名，只有 apksigner 验证才发现。
     * 所以 keystore 实际放在 `C:/Users/a/.workbuddy-ai/keystore/`（纯 ASCII 路径）。
     *
     * 支持的键：
     * ```
     * release.storeFile=/abs/path/to/release.jks
     * release.storePassword=****
     * release.keyAlias=music
     * release.keyPassword=****
     * ```
     * 环境变量同名（RELEASE_STORE_FILE 等）亦可，便于 CI 注入。
     *
     * **注意**：本文件只负责「怎么签」，不负责「改不改包名」。
     * `applicationIdSuffix = ".debug"` 是否移除是**产品决策**——改包名意味着老用户
     * 必须卸载重装且丢数据（`allowBackup=false`），这份痛只能由项目所有者决定何时付。
     * 代码侧留原样，交付说明里单独列为待决策项。
     */
    // 注意：Kotlin DSL 里 `java` 会被解析成 Gradle 的 `java` 扩展（JavaPluginExtension），
    // 所以这里**不能**写 `java.util.Properties`——会被当成在 java 扩展上找 util 属性，
    // 报 "Unresolved reference: util"。把类型全限定成 java.util.Properties 即可绕过。
    val localProps = Properties().apply {
        val f = rootProject.file("local.properties")
        if (f.isFile) {
            runCatching {
                f.inputStream().use { stream -> load(stream) }
            }
        }
    }
    fun secret(key: String, env: String): String? =
        localProps.getProperty(key)?.takeIf { it.isNotBlank() }
            ?: System.getenv(env)?.takeIf { it.isNotBlank() }

    val releaseStoreFile = secret("release.storeFile", "RELEASE_STORE_FILE")
    val hasReleaseSigning = releaseStoreFile != null && file(releaseStoreFile).isFile

    buildTypes {
        debug {
            // 与原 APK 调试变体一致：com.local.music.debug
            applicationIdSuffix = ".debug"
        }
        release {
            // 分发变体保持与已装基线同 ID 同签名：侧载 release 包 = 「更新」而非另装一个应用
            applicationIdSuffix = ".debug"
            signingConfig = if (hasReleaseSigning) {
                signingConfigs.create("release") {
                    storeFile = file(releaseStoreFile!!)
                    storePassword = secret("release.storePassword", "RELEASE_STORE_PASSWORD")
                    keyAlias = secret("release.keyAlias", "RELEASE_KEY_ALIAS")
                    keyPassword = secret("release.keyPassword", "RELEASE_KEY_PASSWORD")
                }
            } else {
                logger.lifecycle(
                    "[signing] 未找到正式 keystore（local.properties 的 release.storeFile 或 " +
                        "环境变量 RELEASE_STORE_FILE），本次 release 将回退 debug 签名。" +
                        "分发前请务必配置正式密钥。",
                )
                signingConfigs.getByName("debug")
            }
            // R8 开启：改造前 dex 占 APK 的 97.6%（28.9MB / 29.6MB），
            // 用户每个版本多下 20MB 纯粹是没裁过的库代码。
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin { jvmToolchain(17) }

    buildFeatures {
        compose = true
    }

    /**
     * 单元测试兜底（P0-5 B 的验收前提，修的是一个**此前一直存在的测试基础设施缺陷**）。
     *
     * `android.util.Log` 在 JVM 单测里是尚未实现的桩：AGP 默认让它的每个方法抛
     * `RuntimeException("Method e in android.util.Log not mocked")`。而
     * `SettingsStore.decode()` 在「判定损坏」与「拒绝更新版本快照」两条路径上**必然**调
     * `Log.e` —— 于是那些断言「应当返回 null」的用例在 `assertNull` 之前就抛了，
     * 报错信息还指向 `Log` 本身，看起来像测试写错了，实际是测试环境没被配置。
     * 之前没人发现是因为这套用例是随本功能一起加的（此前 app 模块只有 ReplayGain/M3U 的
     * 纯函数测试，完全不碰 Log）。
     *
     * `isReturnDefaultValues = true` 让所有 android.jar 里的方法返回默认值（Log 变空操作），
     * 这正是纯 JVM 单测需要的语义：我们测的是解码逻辑，不是日志输出。
     *
     * 为什么不给每个用例加 try/finally 或 mock 框架（Mockito/Robolectric）：
     * 「让 Log 变成空操作」是这一整类问题的统一解，一行配置覆盖所有现在与将来的用例；
     * 引 Mockito/Robolectric 要新增依赖（本机离线环境也拉不到），成本与收益完全不成比例。
     */
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(project(":core:ui"))
    implementation(project(":feature:library"))
    implementation(project(":feature:player"))
    implementation(project(":feature:playlist"))
    implementation(project(":feature:search"))
    implementation(project(":feature:settings"))

    implementation(libs.kotlin.stdlib)
    implementation(libs.ui)
    implementation(libs.ui.graphics)
    implementation(libs.foundation)
    implementation(libs.material3)
    implementation(libs.activity.compose)
    implementation(libs.palette)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)
    implementation(libs.datastore.preferences)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
