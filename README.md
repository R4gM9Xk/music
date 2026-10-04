# project/ —— Gradle 工程骨架

**这是推断产物，不是原始构建脚本。**

原 APK 只包含编译后的字节码，构建脚本（`build.gradle.kts`、`settings.gradle.kts`、
`libs.versions.toml`）本身不会被打进 APK，因此**无法从反编译产物中直接恢复**。

本目录的内容是依据以下证据**反向推断**出来的：

| 推断项 | 证据来源 | 可信度 |
| --- | --- | --- |
| `applicationId` / `minSdk` / `targetSdk` | APK 内 `AndroidManifest.xml` | **高**（打包事实） |
| 依赖坐标与版本 | APK 内 `META-INF/*.version` + 源码常量 | **高 / 中**（见 `../docs/03-依赖清单.md`） |
| 模块划分（17 个） | 反编译源码的包结构 | **中高**（分层清晰，但模块边界可能有出入） |
| 模块间依赖 | 源码 `import` 关系自动分析 | **中**（存在少量噪声，见下） |
| 各模块依赖的库 | 按模块职责推测 | **低**（仅为合理猜测，需按实际代码校正） |

## 已知的推断噪声

- **`:core:model` ↔ `:core:ui` 循环**：`core.model.BackupPayload` 与 `core.database` 都引用了
  `com.local.music.core.ui.navigation.Routes`。反编译把多模块压平成一个包树后，这类"常量类被
  下层引用"的情况会造成看似循环的依赖。原始工程中 `Routes` 很可能位于更底层的模块。
- **`:core:database` 引用 `:core:ui`**：同上，仅因 `Routes` 一个类。
- 各模块的**库依赖**是按职责猜的，不准确。要看真实用法请直接读 `../app/` 下的源码。

## 目录

```
project/
├── settings.gradle.kts        17 个模块的 include 声明
├── build.gradle.kts           根脚本（插件声明）
├── gradle.properties
├── gradle/
│   └── libs.versions.toml     117 条依赖坐标（含传递依赖）
├── app/build.gradle.kts
├── core/
│   ├── common/  model/  ui/  domain/  data/
│   ├── database/  datastore/  datalocal/
│   └── player/  coloros/
└── feature/
    ├── library/  player/  playlist/
    └── search/  settings/  widget/
```

## 怎么用

把它当**阅读辅助**，不要当**构建输入**：

- 想知道"这个应用分几层、谁依赖谁" → 看 `settings.gradle.kts` 和各模块 `build.gradle.kts` 顶部的注释。
- 想知道"用了哪些库、什么版本" → 看 `../docs/03-依赖清单.md`（比 toml 更权威）。
- 想真正编译 → 需要按 `../app/` 的源码逐个补齐模块依赖，工作量不小。
