# project/ —— ColorOS 音乐（液态玻璃流 UI 复刻）

真实可构建的 Android 工程：`app` 壳 + `core:ui` 基础层 + 5 个 feature 模块。

> 早期版本这里曾是一份「反编译骨架推断产物」说明（17 个推断模块、依赖关系靠 import 猜），
> 其中 9 个 core 子模块只有一份自述「参考骨架」的 `build.gradle.kts`、没有任何源码，
> 既不参与构建也不被任何模块引用，已整体删除。想要那份历史推断记录请翻 git 历史。

## 模块

```
project/
├── settings.gradle.kts        实际纳入构建的 7 个模块
├── build.gradle.kts           根脚本（插件声明）
├── libs.versions.toml         依赖坐标与版本（gradle/libs.versions.toml）
├── app/                       应用壳：MainActivity / GlassApp 导航宿主 / 播放服务 / 扫描 / 更新
├── core/
│   └── ui/                    玻璃组件、极光背景、主题 token、图标、AppState
└── feature/
    ├── library/               曲库（歌曲/专辑/艺术家/文件夹/流派/歌单 六 tab）
    ├── player/                播放页（歌词主角）
    ├── playlist/              歌单库 + 歌单详情
    ├── search/                搜索
    └── settings/              设置
```

## 怎么用

- 想知道「应用分几层、谁依赖谁」→ 看 `settings.gradle.kts` 与各模块 `build.gradle.kts`。
- 想知道「用了哪些库、什么版本」→ 看 `gradle/libs.versions.toml`。
- 想真正编译 → 见 `release.sh`（本机固定使用 workbuddy-ai 下的 JDK 17 与 Gradle 8.13）。
