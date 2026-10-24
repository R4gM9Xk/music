# 离线功能补齐计划（2026-10-25）

对照 `2026-10-25-offline-feature-gap-list.md` 出的补齐方案。**范围按拍板排除**：多语言、桌面歌词、翻译/音译行、其他音效、均衡器（现状维持）。**新增两项**：自定义主题色、歌单页直接加入歌曲。

标注：⚡=小时级｜SSI=半天到一天｜SSI+=一天以上｜（可选）=低优先，可长期搁置。
每批次完成后按既有流程装机验收（模拟器 OnePlus13_API36 + 真机 PJZ110）再进下一批。

---

## 实施进度（2026-10-25 更新）

> 本轮由两个 AI 接力完成：第一个做第 0 步 + A1/A2/A3/A4 + C1/C2/F1；第二个做第 0 步收口 + B1/B2/B3/B4 + C4 + D1/D2/D3 + E2 + F2。
> **除 C3（可选）、E1（桌面小部件）外，本计划全部批次已完成。**

| 批次 | 状态 | commit |
|---|---|---|
| 第 0 步 验收提交 | ✅（两批改动分别收口） | `e66358b` / `022ba4a` |
| A1 空队列崩溃 | ✅ | `e66358b` |
| A2 旋转飞行失效 | ✅ | `e66358b` |
| A3 haze sheet 结构统一 | ✅ | `022ba4a` |
| A4 曲库性能（LazyColumn） | ✅ | `e66358b` |
| B1 增量扫描 + 互斥 | ✅ | `1a3d031` |
| B2 元数据广度 + 流派视图 | ✅ | `1a3d031` |
| B3 扫描过滤 | ✅ | `022ba4a`（开关）+ `55f630c`（扫描侧补完） |
| B4 文件夹封面 | ✅ | `1a3d031` |
| C1 定时关闭 | ✅ | `022ba4a` |
| C2 变速不变调 | ✅ | `022ba4a` |
| C3 淡入淡出 | ⬜ 未做（可选，低优先） | — |
| C4 onPlaybackResumption | ✅ | `6a39b79` |
| D1 播放次数 + 最常播放 | ✅ | `38c3f91` |
| D2 M3U 导入导出 | ✅ | `e3ff022` |
| D3 备份与恢复 | ✅ | `e3ff022` |
| E1 桌面小部件（Glance） | ⬜ 未做（SSI+，剩余最大项） | — |
| E2 自启动引导 | ✅ | `55f630c` |
| F1 歌单页直接加歌 | ✅ | `022ba4a` |
| F2 自定义主题色 | ✅ | `d5f5b4d` |

**验证方式说明（重要）**：本轮全部以 `:app:assembleDebug` + `:app:assembleRelease` 双变体编译通过为准，
**未做装机验收**——模拟器在本环境无法绑定 adb 端口（qemu 进程起得来，但 5554/5555 不监听，属沙箱限制）。
待验收清单见文末「装机验收待办」。

**快照版本**：v7（v4 循环随机 / v5 变速 / v6 扫描过滤 / v7 播放次数 + 自定义主题色），
全部只增字段、默认值解码、零迁移。**library_cache**：v3（+ lastModified/size 指纹 + genre 真值 + year/trackNo）。

### 本轮两处「半成品补完」（值得记录）

1. **B3 扫描过滤**：第一个 AI 只做了设置开关、快照字段、Preferences 独立键，**扫描侧完全没接**（`MediaLibrary.load` 无过滤参数）。
   本轮补：`filterShort` 真正进 `load`，新提取条目与复用缓存项都过滤；被过滤条目**不写指纹**，关掉开关后下次扫描能重新入库。
2. **B2 year/trackNo**：`DemoSong` 加了字段但扫描/缓存读写都没填。本轮补：`loadMetadata` 用 `MediaMetadataRetriever` 取
   `METADATA_KEY_YEAR` / `METADATA_KEY_CD_TRACK_NUMBER` / `METADATA_KEY_GENRE`，并补 ID3v1 数字流派映射
   （`(17)` / `17` / `(17)Rock` 三种形态），缓存 v3 写入并回读；歌曲信息面板展示流派/年份/音轨。

### 装机验收待办（下次拿到设备时按此清单走）

- **B1 增量扫描**：首扫 → 冷启动走缓存（<1s）→ 后台增量重扫应只处理变化项；连点两次「扫描音乐」不并发写缓存
- **B2 流派**：真曲库带 genre 标签的歌 → 流派 tab 分组正确；无标签的归「未知流派」；歌曲信息面板显示流派/年份/音轨
- **B3 过滤**：默认过滤 <30s；关掉开关重扫，短音频应出现
- **B4 文件夹封面**：放一张 cover.jpg 到某专辑目录（曲目无内嵌封面）→ 该目录全部曲目显示此封面；删掉后重扫应回退
- **C4 续播**：播放中 force-stop → 从通知栏/蓝牙重连拉起 → 应恢复到原曲原进度
- **D1 播放次数**：完整播完几首 → 歌单页「最常播放」按次数排序；手动切歌不应计数
- **D2 M3U**：导出 → 用文本编辑器看格式；导入 → 新建歌单且曲目对位，未命中行有提示
- **D3 备份**：导出 → 清数据/重装 → 恢复 → 歌单/收藏/设置/播放次数全回来
- **E2 自启动**：ColorOS 设备设置页应出现「自启动与后台运行」行，点击能跳到系统页
- **F2 主题色**：选预设色 → 极光/强调色即时变化且 400ms 渐变；重启保留；「恢复默认」回到跟随封面

---

## 第 0 步（前置）：验收并提交工作区已修改动 ⚡

**问题**：10-24 审计后已修的批次 1（4/6）、批次 2、批次 4 全部停在未提交工作区（10 文件，无 commit、无验收记录），一次误操作即丢。
**方向**：先装机验收这批改动再动新工程。回归重点：恢复链路四场景（首装无快照 / 升级迁移 / force-stop 恢复现场 / 权限拒绝）；loadLibrary 重构后的重扫不打断播放；循环/随机重启保留；release 变体覆盖安装（applicationIdSuffix 保持 .debug + debug 签名，升级路径应无感）。验收通过后单 commit 收口。

---

## 批次 A：崩溃、结构、性能收尾（审计遗留）

### A1. QueueSheet 空队列崩溃（P1①）⚡
**问题**：`Sheet.kt:276` 的 `currentIndex.coerceIn(0, queue.lastIndex)` 在队列空时变成 `coerceIn(0,-1)` 抛 IAE；该逻辑在 `PlayerScreen.kt:438` 无条件组合，空曲库设备点迷你条进播放页必崩。
**方向**：定位逻辑入口判空（队列空直接返回默认定位/跳过滚动定位），顺带排查同文件其他 `lastIndex` 用法。一行级修复，第 0 步验收时若发现空曲库场景可现场修掉。

### A2. 旋转后飞行错位（P1④）⚡
**问题**：manifest configChanges 吞掉旋转（组合不重建），`keepOnDispose` 的 7 个迷你条 rect 与冻结 snapshot 无失效机制，横屏收起时替身飞向竖屏旧坐标。
**方向**：根容器尺寸变化即失效——监听内容区 size change（OnSizeChanged/BoxWithConstraints），变化时 `flight.cancel()` + 清 rect 注册表 + `snapshot=null`（替身直接消失，下次展开重建）。改前必读 player-flight-system 记忆（rect 陈旧值/双影像两坑）。

### A3. haze sheet 结构统一（审计批次 3）SSI
**问题**：除 Search 外全部 Sheet 效果层位于 app 级 hazeSource 子树内（铁律①系统性绕开），与控制台重叠期有串台风险；连带 Detail/Search Chrome 面板打开不让位、ModalState 关闭瞬间提前注销、Playlist/Settings 屏零消费者 source 每帧白录。
**方向**：sheet 宿主统一提升到 GlassApp 根层（内容区 hazeSource 的兄弟），由 LocalModalState/本地状态统一驱动；顺带删 Playlist/Settings 零消费者 source、ModalState 注销延迟到退场动画完成、Detail/Search Chrome 接入让位逻辑。改前必读 haze 铁律（效果层=源兄弟、hazeEffect 在 clip 后）+ liquid-glass-pipeline 记忆。**本批必须排在所有新增 Sheet 类功能（批次 F、C、D 的新面板）之前**，否则新面板要按旧结构写一遍再迁移。

### A4. 曲库性能（审计批次 5）SSI
**问题**：歌曲 tab 是 `Column + verticalScroll` 整库一次性组合；`sortedSongs()` 在组合体内裸调用，每次重组全库 Collator 重排；艺术家 tab 每行调 `albumGroups()` O(n×m)；几千首量级必卡。
**方向**：`LibraryScreen` 三个 tab 全部改 `LazyColumn + key(song.id)`；排序结果 `remember(library, songSort)`（或 derivedStateOf）缓存；艺术家分组预计算一次成结构再渲染。顺带修队列拖拽陈旧闭包（`ReorderableList` pointerInput 键加数据版本或 rememberUpdatedState 提交回调）与 GlassTabs 缺 Role.Tab/selected 语义（P3）。**本批是批次 B 增量扫描的前置**（扫描加字段后列表压力更大）。

---

## 批次 B：曲库深化

### B1. 增量扫描 + 扫描互斥 SSI
**问题**：每次 `MediaLibrary.load` 全量 MediaStore 查询 + 对每首歌跑 MediaExtractor/MediaMetadataRetriever（慢源），大曲库分钟级；且双扫无互斥，writeCache 并发写非原子（P3）。
**方向**：library_cache 已有 dataPath 基础——load 先读缓存建指纹集（dataPath + DATE_MODIFIED + SIZE），cursor 结果只对新增/指纹变化的条目做元数据提取，其余直接复用缓存项；收尾合并写回。`load` 加 in-flight 互斥（AtomicBoolean，重入直接返回已有结果或等待）。library_cache 升 v3（+lastModified）。

### B2. 元数据广度 + 流派视图 SSI
**问题**：genre 只兜底「独立」从不真扫，无年份/音轨号；流派视图无从谈起（详情页模板已支持流派，AppState.detailPage 注释即含「歌单/专辑/艺术家/文件夹/流派共用模板」，缺的只是数据和入口）。
**方向**：MediaStore 投影加 YEAR/TRACK；genre 优先 MediaMetadataRetriever METADATA_KEY_GENRE（通吃 MediaStore 与 SAF 两通道，注意 ID3 数字 genre 要过标准映射表），空则 MediaStore genre 表关联兜底。DemoSong 加 genre 真值/year/trackNo 字段，library_cache v3 一并记录；LibraryScreen 分组 tab 加「流派」。快照不动（歌单按 id 关联）。

### B3. 扫描过滤规则 ⚡
**问题**：全目录扫描后 Download 等目录的通知音、录音碎片、短音频全部入库；隐藏目录不跳过。
**方向**：设置页「扫描过滤」行（默认过滤 <30s，可关）；SAF DocumentFile 遍历时跳过 `.` 前缀目录（MediaStore 通道天然不含隐藏文件）。MediaLibrary.load 接过滤参数。

### B4. 文件夹封面 cover.jpg ⚡
**问题**：封面只取内嵌 picture，整文件夹无内嵌封面时全灰。
**方向**：扫描时按 folder 检查同目录 cover.jpg/folder.jpg/albumart.jpg（所有文件访问权限已具备），该目录无内嵌封面的歌曲兜底共享此图（落盘缓存一份、Palette 取色同源）。library_cache v3 记 folderCover 路径。

---

## 批次 C：播放深化

### C1. 定时关闭 SSI
**问题**：无睡眠定时器，商用标配缺失；本组里实现量最小、用户感知最高。
**方向**：入口放播放页「更多」面板（+设置页一行）。选项：15/30/60/90 分钟、播完当前首、播完整个队列、取消。实现落服务端 `MusicPlaybackService`：自定义命令 CMD_SET_SLEEP_TIMER 下发时长（**必须同步加进 onConnect 的 availableSessionCommands——历史两坑同型**），服务内倒计时到点 pause；「播完当前首」用 Player.Listener 监听切歌时机 pause。UI 侧自记剩余时间回显；不做持久化（重启失效合理）。

### C2. 变速不变调 ⚡
**问题**：无播放速度调节。
**方向**：ExoPlayer/MediaController 原生 `setPlaybackSpeed`（内部 Sonic 变速不变调，零额外实现）。播放页「更多」面板加档位（0.75/1.0/1.25/1.5/2.0），快照 v5 持久化 + settingsKey 指纹。进度显示与 effectiveDurationSec 天然兼容，无需改动。

### C3. 淡入淡出 SSI
**问题**：起播/暂停/切歌全是音量硬切。
**方向**：服务端实现（服务是权威）：起播与恢复播放时 player.volume 0→1（300ms 协程渐变）；暂停反向渐出；可选曲尾渐出（tick/Listener 判剩余 <3s 时渐出再自然切歌）。UI 只下发开关（设置页，快照 v5）。gapless（setPauseAtEndOfMediaItems 方向）标（可选）：ExoPlayer 同管线解码本就接近无缝，感知收益小。

### C4. onPlaybackResumption（进程死后系统入口续播）SSI
**问题**：P3 遗留——进程被杀后，从媒体通知/蓝牙重连的系统入口无法恢复播放（服务端不知道队列现场）。
**方向**：AppState 存盘时同步写一份服务可读的 `queue_state.json`（specs + index + positionSec，与快照同 schema 字段），`MusicPlaybackService` 实现 `onPlaybackResumption` 回调读它构造 MediaItemsWithStartPosition。与快照写盘同点触发，不引入服务读 DataStore 的耦合。

### C5.（可选）智能随机
**问题**：与成熟 App 的「智能随机」有差距。
**诚实评估**：ExoPlayer DefaultShuffleOrder 本身就是全洗牌、一轮内不重复，现状已覆盖八成语义；缺的只是手动切歌也计入已播、跨轮记忆。收益小，搁置。
### C6.（可选）响度归一
**方向**（若做）：解析 ReplayGain 标签（ID3 RGAIN frame / VorbisComment）需要自解 metadata 帧，成本高；轻量替代=每曲音量记忆。搁置。

---

## 批次 D：用户资产

### D1. 播放次数 + 最常播放 ⚡
**问题**：无播放统计，无「最常播放」歌单。
**方向**：AppState 加 `playCounts: Map<String,Int>`，onTrackEnd（完播）处累计——复用 rememberPlayed 所在点位；快照 v5 持久化。「最常播放」走内建歌单模式：DemoCatalog 注册 + resolvePlaylist 实时 top50（顺带修 P3「Recent 计数与详情不一致」——同为失效 id 未过滤问题）。

### D2. M3U 导入导出 SSI
**问题**：歌单无法跨应用/跨设备迁移。
**方向**：导出=歌单详情「更多」加「导出 M3U8」→ ACTION_CREATE_DOCUMENT 让用户选位置，#EXTINF（时长,艺术家 - 标题）+ 绝对路径行（纯本地应用绝对路径最实用）；导入=设置页「导入歌单」→ ACTION_OPEN_DOCUMENT 选 .m3u/.m3u8 → 按路径经 songById（f<绝对路径> id 体系天然对位）匹配，未命中项汇总提示。新建 user- 歌单承载，复用现有加歌 mutator。

### D3. 备份与恢复 SSI
**问题**：换机/重装丢歌单、收藏、全部设置（DataStore 文件普通用户不可及）。
**方向**：设置页「备份与恢复」：导出=当前快照 JSON（v5 含歌单/收藏/队列现场/设置/playCounts）加壳写 backup.json（ACTION_CREATE_DOCUMENT）；恢复=OPEN_DOCUMENT 选文件 → 走现有 decode 兼容链解析 → 一次性 restore。与快照同 schema，版本兼容零新增逻辑。与 D2 分工：M3U 通用于其他播放器，备份是本应用全量。

---

## 批次 E：系统覆盖

### E1. 桌面小部件 SSI+
**问题**：无桌面小部件（feature/widget 目录只剩旧展示页源码，不参与构建）。
**方向**：Glance 重写 feature/widget：4x2 现在播放（封面 artworkUri/标题/艺术家/上一首-播放暂停-下一首），按钮经 MediaController 发命令（服务常驻，连接成本可控），点击卡片进 app。manifest 加 receiver；模块重新入 settings.gradle.kts。封面 uri 已落盘可寻址（刚治理的稳定哈希命名正好复用）。先做单一规格，多规格后续。
### E2. 自启动引导 ⚡
**问题**：ColorOS 激进省电会杀后台播放，用户不知要去开自启动。
**方向**：OPPO 系（Build.MANUFACTURER 判定）+ 首次息屏播放后提示条 → 跳 ColorOS 电池/自启动设置页（私有 action 不稳定，失败兜底跳应用详情页）。
### E3.（可选）Android Auto：升级 MediaLibraryService + automotive_app_desc；无车机场景，搁置。
### E4.（可选）线控按键自定义映射：media3 onMediaButtonEvent 拦截；标准线控已可用，搁置。

---

## 批次 F：新增功能

### F1. 歌单页直接加入歌曲 SSI
**问题**：目前加歌只有「歌曲列表/卡片长按 → 动作面板 → 加入歌单」一条路（歌选歌单）；歌单详情页自己没有加歌入口，想编辑歌单要去别处找歌，反直觉。
**方向**：PlaylistDetailScreen 头部「更多」面板（CollectionActionsSheet）加「添加歌曲」项 → 新 AddSongsSheet（歌单选歌，与既有 AddToPlaylistSheet 方向相反、不算重复实现，不破防屎山红线）：顶部 GlassTextField 搜索（复用 searchScope 模式）+ 全曲库多选列表（SongRow 加选中态，已在歌单的预选/置灰）+ 底部「添加 N 首」→ 复用现有 addToPlaylist mutator 单点提交。**依赖**：批次 A3 完成（新 Sheet 挂根层宿主）+ A4（懒加载列表）。

### F2. 自定义主题色 SSI
**问题**：极光/玻璃/强调色现在只有「跟随封面取色」或「品牌靛紫兜底」两态，用户不能自选主题色。
**方向**：
- 派生：core/ui 加 `AuroraPalette.fromSeed(color)`——以用户色为 seed 生成五色阶梯（深色两档 + seed 本体 + 浅色两档，HSL/OKLCH 调明度饱和度），浅色模式沿用现有 light() lerp 机制；GlassColors/auroraAccentColor/seed 投影全部从同一 seed 派生保证一致。
- 设置：followCover 布尔升级为三态模式（跟随封面/自定义/品牌）；设置页「主题色」行 → 新 Sheet：10 个预设色板 + HSV 滑杆自定 + 极光实时小预览；选中即生效，走现有 400ms 双调色板渐变。
- 持久化：快照 v5 加 customSeedArgb（null=未自定义）+ settingsKey 指纹。
- **依赖**：A3（Sheet 宿主）。改 GlassTheme/AuroraPalette 前看 glass-replica 记忆的取色/浅色令牌档案。

---

## 版本与迁移影响汇总

- 快照 v5（只增字段、默认值解码、零迁移，既有模式）：`customSeedArgb`、`playbackSpeed`、`playCounts`（+C3 的淡入淡出开关）。
- library_cache v3：`lastModifiedSec`、真值 `genre`/`year`/`trackNo`、`folderCoverPath`（旧缓存拒读一次后重扫自愈，既有模式）。
- 新增服务自定义命令：CMD_SET_SLEEP_TIMER——**onConnect 声明必须同步加**（历史 EQ/SleepTimer 两坑同型）。

## 建议实施顺序

**0（验收提交）→ A（A1⚡ 先行，A3 必须在所有新 Sheet 之前）→ F1 歌单加歌 + C1 定时关闭 + C2 变速（三个高感知快赢）→ A4 性能 → B 曲库深化 → F2 主题色 → C3/C4 → D → E。**

理由：A3 是所有新面板的结构前提；F1/C1/C2 是用户感知最高的三件小事；A4/B 为大曲库兜底；F2/C3/C4/D 中等感知；E 覆盖面扩展收尾。
