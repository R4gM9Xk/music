// ColorOS 音乐 —— 工程设置
//
// 当前仓库为「液态玻璃流」UI 复刻工程：仅纳入真实存在源码的模块。
// （历史反编译骨架中的 core:domain / core:player 等模块无源码，已从构建移除。）

pluginManagement {
    repositories {
        // 阿里云镜像优先：本机走代理时 google()/mavenCentral() 直连极慢
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        google()
        mavenCentral()
    }
}

rootProject.name = "ColorOSMusic"

// ── 应用壳：MainActivity / GlassApp 导航宿主 ─────────────
include(":app")

// ── 基础层 ──────────────────────────────────────────────
include(":core:ui")          // 玻璃组件、极光背景、主题 token、图标、演示数据

// ── 功能层（设计稿 7 屏去掉「流体云/小部件」展示页：系统级流体云由媒体会话承担）──
include(":feature:library")  // 首页曲库
include(":feature:player")   // 播放页（歌词主角）
include(":feature:playlist") // 歌单库 + 歌单详情
include(":feature:search")   // 搜索
include(":feature:settings") // 设置
