// :core:model —— 领域模型（43 个数据类）
//
// ⚠️ 参考骨架：模块划分由反编译包结构推断，依赖关系由 import 分析推断。
//    不是原始构建脚本，不保证可直接编译。
//    源码文件数：43

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.local.music.core.model"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin { jvmToolchain(17) }

    buildFeatures {
        compose = false
    }
}

dependencies {
    // 注意：骨架原写的 project(":core:ui") 与 core:ui → core:model 构成循环依赖，已移除。
    // 领域模型层不应反向依赖 UI 层。

    implementation(libs.kotlin.stdlib)
    implementation(libs.kotlinx.coroutines.core)
}
