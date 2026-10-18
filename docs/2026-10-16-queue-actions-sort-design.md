# 队列编辑 + 统一动作菜单 + 歌单编辑 + 排序 · 设计方案

日期：2026-10-16 ｜ 覆盖清单第 1–14 项（Apple Music 对比缺口的前 14 项）
状态：待审阅 —— 批准（「开工」）后按阶段 A→D 实施，每阶段独立可构建、可装机验收。

---

## 1. 目标

| # | 功能 | 落点 |
|---|------|------|
| 1 | 下一首播放（Play Next） | 引擎增量 API + AppState mutator |
| 2 | 最后播放（Play Last / 队尾追加） | 同上 |
| 3 | 队列拖动重排 | QueueSheet + 共享重排手势 |
| 4 | 队列删除单项 | 同上 |
| 5 | 清空队列 | clearUpNext（保留当前曲） |
| 6 | 队列两段式视图（正在播放 / 接下来） | QueueSheet 改版 |
| 7 | 歌曲行操作菜单（⋯ / 长按） | SongActionsSheet 统一组件 |
| 8 | 任意页面加歌入歌单 | SongActionsSheet 内嵌 AddToPlaylistSheet |
| 9 | 从歌单移除歌曲 | 歌单编辑 mutator + 菜单 |
| 10 | 歌单内拖动重排 | 歌单详情复用共享重排手势 |
| 11 | 重命名歌单 | renamePlaylist + 命名面板 |
| 12 | 跳转查看专辑 / 艺术家 | openDetail（现有路由，零新机制） |
| 13 | 专辑 / 艺人 / 歌单卡快捷操作 | CollectionActionsSheet（长按卡） |
| 14 | 排序选项（歌曲 tab） | SongSort + SortSheet + DATE_ADDED 扫描 |

## 2. 现状关键事实（设计的地基）

- **队列是"只换不修"**：`AppState.queue` `private set`，只在 playSong/loadLibrary/恢复链路整体替换；引擎侧对应 `setMediaItems` 全量下发。全量重发会打断播放，**队列编辑必须走 MediaController 增量 API**（addMediaItems / moveMediaItem / removeMediaItems——不中断当前播放）。
- **PlayerEngine 只有一个实现**（MediaControllerEngine），接口扩展无第二实现要跟。
- **演示/真曲库双态**贯穿 AppState（engine==null 或 mediaUri==null 走本地定时器）。真曲库模式下队列不含演示曲（loadQueueOnEngine 的 mapNotNull 剔除只对混合队列有意义，真曲库不出现）；演示模式全本地。**队列编辑 mutator 内部分支即可，边界天然闭合**。
- **服务端权威回流**：`syncFromEngine` / `attachPlayer` 按 `engine.mediaIds()` 重建 UI 队列。增量编辑由 mutator 同时改 UI 列表与引擎，两侧同源一致，回流逻辑零改动。
- **SongRow 的 onMoreClick 是现成死代码**（Lists.kt:201-213），补菜单只需接线。
- **openDetail(DetailTarget(Kind.Album/Artist, key)) 已存在**（detailData 五分支全通），交叉导航零新机制。
- **持久化天然兼容**：歌单曲目顺序（JSONArray 有序）、队列 ids、歌单标题都已在快照 v3 字段里；排序只需**新增一个字段**（optString 默认值解码，无迁移分支）。
- **GlassSheet + SheetAction + GlassTextField** 是既有面板语言；单选面板（玻璃强度/动效）的 emphasized 模式可直接复刻为排序面板。
- **QueueSheet 行高 66dp 已是滚动定位依赖的常量**，重排手势复用同一常量（抽成共享常量）。

## 3. 方案选择（为什么这样不做那样）

**采用：原地分层扩展（引擎按概念加方法组 + AppState 加区段 + 面板组件统一抽取到 core/ui）**

否决的两个方向：

- ❌ **拆 AppState / 引入 ViewModel 域层**：要重写刚修稳的恢复链路（hydrated 门控、pendingPlayback、librarySettled 门面）和全部 6 屏接线，风险与收益完全不成比例。AppState 单源是本项目已确立且被验证的架构，不是屎山来源；屎山风险在"组件复制"和"接口无概念生长"。
- ❌ **各屏顺手实现**：PlayerScreen 的 More/AddTo/Info 面板逻辑复制到曲库/搜索/详情 = 最快出屎山的路，也正是本次要消除的。

## 4. 分层设计

### 4.1 引擎层：PlayerEngine 增量队列方法组

`core/ui/state/AppState.kt` 的 PlayerEngine 接口新增一组（一个概念）：

```kotlin
/** 增量队列编辑：不重发整条队列，不打断当前播放 */
fun addQueueItems(index: Int, specs: List<SongSpec>)
fun moveQueueItem(fromIndex: Int, toIndex: Int)
fun removeQueueItems(fromIndex: Int, toIndex: Int)   // [from, to)
```

MediaControllerEngine 实现为 controller 直通（addMediaItems / moveMediaItem / removeMediaItems）。清空"接下来" = 两段 removeQueueItems（0..current 与 current+1..end）。

**已知风险（需装机实测）**：shuffle 开启时 addMediaItems 对新插入项在**洗牌播放序**中的位置由 media3 DefaultShuffleOrder 决定（实现细节，可能不落在"当前曲之后"）。实测若不符：走既有 SessionCommand 模式（EQ 预设已趟通的服务端命令通道），服务端拿 ExoPlayer 实例 addMediaItem 后用 setShuffleOrder 修正——该通道是既定模式，不算新架构。

### 4.2 状态层：AppState 新增两个区段（不拆家）

```
// ── 队列编辑（引擎活跃走增量 op + UI 列表同步改；演示模式本地列表操作）──
fun playNext(song)              // 插 queueIndex+1
fun playLast(song)              // 插队尾
fun playNextAll(songs)          // 集合版（详情页/卡片用）
fun playLastAll(songs)
fun removeQueueItem(index)      // 当前曲不可删（UI 置灰）
fun moveQueueItem(from, to)     // current 在区间内时平移 queueIndex
fun clearUpNext()               // 保留当前曲，清其余

// ── 歌单编辑 ──
fun removeFromPlaylist(playlistId, songId): Boolean   // 只删 addedTracks；基座曲目返回 false（UI 不出菜单项）
fun movePlaylistTrack(playlistId, from, to)           // 仅用户歌单
fun renamePlaylist(id, title)                          // 仅 user- 前缀
```

- 引擎分支判定复用 next() 的既有条件（`engine != null && engineLoadedUri != null && currentSong.mediaUri != null`）；演示分支 `queue = queue.toMutableList().apply{...}` 一次性重赋，保持 private set 纪律。
- 每个 mutator 末尾 `scheduleSave()`；队列编辑已有 queueIds/queueIndex 字段承接，**快照零结构变更**。
- `queueSource` 语义：插入不改来源名（队列仍是原语境的延伸）。

**排序**（纯逻辑独立文件 `core/ui/state/Sort.kt`）：

```kotlin
enum class SongSort(val label: String) { Title("标题"), Artist("艺术家"), Album("专辑"), Recent("最近添加") }
```

- AppState 加 `var songSort` + `fun sortedSongs(): List<DemoSong>`（比较器放 Sort.kt，主键之外统一标题升序兜底；标题/艺术家/专辑用 `java.text.Collator`（中文拼音序，本地播放器的正确预期））。
- 快照 v3 追加 `"songSort"` 字段（put + optString 默认 Title，**不 bump 版本号、不写迁移**——与 hiddenPlaylists 字段当初的加法完全同型）。

### 4.3 数据层：MediaLibrary 补一列

- DemoSong 加 `dateAddedSec: Long = 0`；扫描读 `MediaStore.Audio.Media.DATE_ADDED`。
- library_cache.json 版本 1→2：编码新字段，解码缺省 0（v1 缓存兼容，重扫后自动补全）。id 是 `f<路径>`，DATE_ADDED 随文件稳定，缓存语义不变。

### 4.4 组件层：四个动作面板一个文件 + 一套重排手势

**新文件 `core/ui/components/ActionsSheet.kt`**（约 200 行，从 PlayerScreen 迁出并扩展）：

| 组件 | 职责 | 复用方 |
|------|------|--------|
| `SongActionsSheet(state, song, playlistContext?)` | 单曲菜单：下一首播放 / 最后播放 / 收藏 / 加入歌单 / 查看专辑 / 查看艺术家 / 从歌单移除（仅 playlistContext 且可移除）/ 歌曲信息 | 曲库歌曲 tab、搜索结果、歌单/专辑/艺术家/文件夹/流派详情、播放页 More（替换现有）、队列行（可选） |
| `AddToPlaylistSheet(state, song)` | 加歌入歌单选择（PlaylistPickRow 迁入） | SongActionsSheet 二级 |
| `SongInfoSheet(song, formatText)` | InfoRow 迁入 | SongActionsSheet 二级 |
| `CollectionActionsSheet(state, data)` | 集合菜单：全部播放 / 随机播放 / 下一首播放全部 / 最后播放全部 / 重命名歌单（仅 user-）/ 删除歌单（沿用现有规则） | 歌单详情 More（替换现有）、专辑/艺术家/歌单卡长按（item 13） |

净效果：PlayerScreen 删掉三个内联面板改挂组件；PlaylistDetailScreen 删掉 More 面板改挂组件——**全 App 单曲菜单与集合菜单各只有一个实现**。

**新文件 `core/ui/components/ReorderableList.kt`**（约 120 行）：
- `rememberReorderState(rowHeight: Dp, scrollState)` —— 统一行高拖拽：长按（或拖把手）进入、按 y/行高算目标索引、行位移动画、边缘 48dp 自动滚动。
- 只实现一次，QueueSheet（队列重排）与歌单详情（歌单重排）共用；行高用共享常量 `SongRowHeight = 66.dp`（QueueSheet 滚动定位同步改引用）。

**QueueSheet 改版**（两段式）：
```
播放队列
── 正在播放 ──
[当前曲行：波形高亮，不可拖/不可删]
── 接下来 · N 首          [清空] ──
[可拖拽行 · 尾部移除图标钮]
```
- 内容改 LazyColumn（GlassSheet 加可选懒内容承载，复用面板外壳），拖拽目标索引计算与滚动定位共用行高常量。
- 「清空」只清接下来（保留当前曲继续播）。
- 需新增图标：Sort（无则加 vector）、Trash/移除（同）；GlassIcons 现有库存先查再用。

**排序入口**：PageHeader 追加可选第二动作参数（`actionIcon2/onAction2/actionLabel2`，向后兼容）；曲库页主按钮=排序、副按钮=现有主题切换。`SortSheet` = GlassSheet 单选（SheetAction emphasized 模式，与玻璃强度/动效面板同语言）。

**长按**：SongRow / PlaylistCard / GroupRow 用 `combinedClickable`（foundation opt-in）挂长按打开对应菜单；⋯ 按钮与长按同源，两入口一套逻辑。

### 4.5 屏幕接线（每屏只加"本地面板宿主"，零新全局状态）

各屏模式统一（沿用 PlayerScreen 的 `sheet` 局部态惯例）：

```kotlin
var actionSong by remember { mutableStateOf<DemoSong?>(null) }
var collectionActions by remember { mutableStateOf(false) }
// 列表: SongRow(song, onMoreClick = { actionSong = song }, ...)
// 根级: actionSong?.let { SongActionsSheet(state, it, playlistContext) { actionSong = null } }
```

- 曲库：歌曲 tab 行接线 + tab 头排序按钮；专辑/艺术家/流派/歌单卡长按。
- 搜索：歌曲结果行接线。
- 详情：曲目行接线（歌单详情带 playlistContext + 可重排）；More 换 CollectionActionsSheet。
- 播放页：More 换 SongActionsSheet（当前曲）。

## 5. 防屎山规范（本方案的红线）

1. **面板单一实现**：单曲/集合菜单全 App 各一处（ActionsSheet.kt）；任何新页面要菜单 = 挂组件，不许复制面板代码。
2. **引擎接口按概念生长**：一次一组同概念方法（本次=增量队列编辑）；禁止 per-功能零散加方法。
3. **AppState 只加区段**：新 mutator 进"队列编辑/歌单编辑"banner 区段；纯逻辑（比较器、索引平移 helper）进同包独立小文件；不引入第二个状态源、不把 UI 临时态（面板开关）塞进 AppState。
4. **持久化只增字段**：快照加 songSort、缓存加 dateAdded，均默认值兼容解码，不写版本迁移分支。
5. **重排手势单一实现**：队列与歌单共用 ReorderableList；行高常量共享（顺手消灭 QueueSheet 里的裸 66dp）。
6. **交互语言复用既有**：面板=GlassSheet、动作=SheetAction、选择态=emphasized、输入=GlassTextField、模态让位=ModalState（自动获得返回键优先关面板与 Chrome 让位，无需额外处理）。
7. **每阶段独立可构建可装机**，验收截图入 `.zcode/screens/glass-v10/` 递增基线。

## 6. 风险与实测点

| 风险 | 缓解 |
|------|------|
| shuffle 下 Play Next 的播放序位置（media3 DefaultShuffleOrder 插入语义） | 阶段 A 装机实测；不符则走 SessionCommand 服务端修正（EQ 已趟通的通道） |
| moveQueueItem 的 queueIndex 平移（current 在移动区间内） | 索引 helper 写成纯函数 + 模拟器逐例验证（移前/移后/跨 current） |
| 拖拽 + 自动滚动手势（本轮最大 UI 工作量） | 统一行高简化数学；测试矩阵：长队列边缘拖动滚屏、拖拽中取消、拖动当前曲、shuffle 开启时拖动 |
| removeMediaItems 移除非当前项是否绝对不打断播放 | ExoPlayer 语义保证 + 装机听感验证 |
| combinedClickable 的 ExperimentalFoundationApi opt-in | 单注解，随用随标 |
| 队列编辑与恢复链路互踩（存盘窗口期） | mutator 内同步改 UI 列表 + scheduleSave（防抖既有），force-stop 回归用例保留 |

## 7. 实施阶段（A→D，各自可验收）

**阶段 A · 队列六件套（项 1–6）**
引擎 +3 方法 / AppState +7 mutator / QueueSheet 两段式+重排+删除+清空 / 播放页 More 面板加「下一首播放」。
验收：插队不打断播放、拖动/删除/清空即时生效且通知栏不受扰、force-stop 后队列现场完整、shuffle 下 Play Next 行为实测记录。

**阶段 B · 统一动作面板（项 7、8、12、13）**
ActionsSheet.kt 四组件 / PlayerScreen 面板迁出 / 曲库+搜索+详情行接线 / 卡片长按 / PageHeader 第二按钮。
验收：任意页 ⋯/长按出菜单，加歌/收藏/跳转专辑/艺术家全通；播放页 More 功能不回退。

**阶段 C · 歌单编辑（项 9、10、11）**
removeFromPlaylist / movePlaylistTrack / renamePlaylist / 歌单详情重排+行菜单扩展 / CollectionActionsSheet 重命名项。
验收：用户歌单增删改重排持久化（重装回归）；内建/动态歌单只读不误伤。

**阶段 D · 排序（项 14）**
DATE_ADDED 扫描 + 缓存 v2 / SongSort + SortSheet / 曲库歌曲 tab 接线。
验收：四种排序含中文拼音序、最近添加按真实入库时间、排序选择持久化。

## 8. 待定决策点（默认按推荐执行，审阅时可改）

| 决策 | 推荐 | 备选 |
|------|------|------|
| 拖拽重排实现 | 自研统一行高方案（零新依赖，符合工程惯例） | 引 reorderable 库 |
| 排序范围 | 仅歌曲 tab 四键（标题/艺术家/专辑/最近添加） | 扩展专辑/艺术家 tab 排序、增加时长键 |
| 队列当前曲 | 不可删除（UI 置灰） | 允许删除当前曲（自动切下一首） |
| 菜单入口 | ⋯ 按钮 + 长按双入口 | 仅长按（更贴 Apple Music）或仅 ⋯ |
