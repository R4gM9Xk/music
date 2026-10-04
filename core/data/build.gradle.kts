// :core:data —— 数据层接口：Repository 契约
//
// ⚠️ 参考骨架：模块划分由反编译包结构推断，依赖关系由 import 分析推断。
//    不是原始构建脚本，不保证可直接编译。
//    源码文件数：4

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.local.music.core.data"
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
    implementation(project(":core:common"))
    implementation(project(":core:model"))

    // implementation(libs.androidx-core-ktx)   // 版本未记录，请确认
    implementation(libs.kotlin.stdlib)
}
