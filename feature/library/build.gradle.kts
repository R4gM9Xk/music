// :feature:library —— 首页曲库（大标题 + 玻璃搜索 + tab 胶囊 + 歌曲列表）

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.local.music.feature.library"
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
        compose = true
    }
}

dependencies {
    implementation(project(":core:ui"))

    implementation(libs.kotlin.stdlib)
    implementation(libs.ui)
    implementation(libs.ui.graphics)
    implementation(libs.foundation)
    implementation(libs.material3)
}
