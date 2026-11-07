

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

