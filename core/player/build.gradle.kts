// :core:player —— 播放内核：media3/ExoPlayer 封装、歌词、封面、音效、FFmpeg
//
// ⚠️ 参考骨架：模块划分由反编译包结构推断，依赖关系由 import 分析推断。
//    不是原始构建脚本，不保证可直接编译。
//    源码文件数：46

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.local.music.core.player"
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
    implementation(project(":core:ui"))
    implementation(project(":core:model"))
    implementation(project(":core:coloros"))

    // implementation(libs.androidx-core-ktx)   // 版本未记录，请确认
    implementation(libs.kotlin.stdlib)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)
    implementation(libs.media3.common)
    implementation(libs.media3.extractor)
    implementation(libs.media3.datasource)
    implementation(libs.media3.decoder)
    implementation(libs.media3.container)
    implementation(libs.media3.database)
    implementation(libs.coil)
    implementation(libs.okhttp)
}
