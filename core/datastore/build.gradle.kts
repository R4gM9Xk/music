// :core:datastore —— DataStore 偏好存储：序列化器、迁移
//
// ⚠️ 参考骨架：模块划分由反编译包结构推断，依赖关系由 import 分析推断。
//    不是原始构建脚本，不保证可直接编译。
//    源码文件数：5

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.local.music.core.datastore"
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
    implementation(project(":core:model"))

    // implementation(libs.androidx-core-ktx)   // 版本未记录，请确认
    implementation(libs.kotlin.stdlib)
    // implementation(libs.androidx-datastore-preferences)   // 版本未记录，请确认
    // implementation(libs.androidx-datastore)   // 版本未记录，请确认
}
