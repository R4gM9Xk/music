# 第二轮修复记录（问题清单 P2/P3 全量落地）

> 基线：HEAD `852d9b7` + 上一轮未提交改动（见 `2026-10-25-fix-report.md`）
> 本轮范围：用户给出的两份问题清单（`P1：无` 那份 + `二、问题清单` 那份）逐条核实并修复
> 不在范围：`2026-10-25-audit-verification.md` 已列、上一轮已在修的项目（封面生命周期、飞行协程、签名校验、幽灵曲库、allowBackup、release.sh 等）
> 验证：`:app:assembleDebug` + `:app:assembleRelease` 均 BUILD SUCCESSFUL；`compileDebugKotlin` 零 error，仅 2 条**既存** warning（media3 deprecated override / kotlinx `debounce` FlowPreview）
> 未改版本号、未 commit

---

## 一、核实结论：先证伪，再动手

清单里若干条在动手前就对源码取证过，结论与报告不完全一致，如实记录：

| 报告主张 | 核实结果 |
|---|---|
| P2-3「最常播放」删除歌单 no-op | **上一轮已修**（`DemoCatalog.dynamicIds` 集合判定）。本轮复核：`ActionsSheet` 用 `target.id in DemoCatalog.dynamicIds`，删除项对三个动态歌单都不再渲染；`PlaylistDetailScreen` 的加歌入口同款。无需再改 |
| P2-4 重命名无重名校验 | **上一轮已修**（`duplicateName` 三态 + `renamePlaylist` 内部查重）。本轮补了报告「附带」提到的另一半：M3U 导出文件名未 sanitize |
| P2-1/P2-2（第一份）搜索页/详情页 10Hz 全库派生 | **成立**。`SearchScreen` 两处裸调 + `PlaylistDetailScreen` 的 `detailData` 裸调，且 `progressFraction` 在屏 body 里被读 → 整屏 10Hz 重组 |
| P2-1（第二份）AppState 派生无缓存 O(n·m) | **成立且比报告更糟**：`songById` 是 `library.firstOrNull` 线性扫，而 `resolvePlaylist` 是 `favorites.mapNotNull { songById(it) }` → 真机上歌单墙一次组合就是数十万次字符串比较；`detailData` 的艺术家分支还第二次调 `albumGroups()` |
| P3-9 GlassTheme 三级动画（报告标「存疑」） | **确认为笔误**。注释只描述「深浅两套各自动画后按主题进度混合」两层，第三层 `animatePalette(effectivePalette)` 无任何注释，实际让取色收敛变成约 2×400ms |
| P3-10（第二份）QueueSheet scrollTo 与 autoscroll 竞争（报告标「存疑」） | **确认为真实风险**，但与 P3-1（拖拽中切歌错位）同源。修 P3-1 时把「拖拽中切歌 → 本次拖拽作废」作为语义后，竞争窗口随之消失（切歌即取消拖拽，不存在两个写入方并存） |
| 存疑：封面缓存 32 位 `String.hashCode` 碰撞 | **确认成立**（生日问题：1 万首约 1%）。碰撞后果不是「少一张图」而是**串图**（后落盘者覆写，两首歌永久共用一张 PNG）。已改 SHA-256 前 8 字节 |

---

## 二、第一份清单（P2/P3）逐条落地

### P2-1 / P2-2：10Hz 全库派生 + 进度读取位置

**根因是两件事叠在一起**，只修一件都不够：

1. **派生计算没缓存** → 在状态层根除（见 P2-1 第二份）
2. **`progressFraction` 在屏 body 里被读** → 屏幕 10Hz 重组，进而把第 1 件事放大 10 倍

修法（第 2 件）：把进度参数从 `Float` 改成 **`() -> Float` 取值函数**，读取推迟到绘制期。

| 文件 | 改动 |
|---|---|
| `Chrome.kt` | `ProgressTrack` / `MiniPlayerBar` / `GlassChrome` 的进度形参改为 `progressProvider: () -> Float`；`ProgressTrack` 用 `drawWithCache` 在 `onDrawBehind` 里读值（读快照状态只失效绘制，不触发重组）；读屏语义隔离到极小的 `ProgressSemantics`；`ProgressTrack` 保留 Float 重载供更新下载进度等低频场景 |
| `GlassApp.kt` / `SearchScreen.kt` / `PlaylistDetailScreen.kt` | 三处调用点改 `progressProvider = { state.progressFraction }` |
| `PlayerScreen.kt` | 同上；另外已播时长文本抽成 `ElapsedTimeText`（Text 必须组合期排版，绕不开，那就把重组范围缩到一张文本） |

### P2-3 / P2-4：动态歌单守卫 / 重命名校验

上一轮已修，本轮复核确认。补做报告「附带」项：

- `M3uPlaylistIO.suggestedFileName(title)`：歌单名用户可输入 `/ \ : * ? " < > |`，原实现直接拼 `"$title.m3u8"` 交给 SAF，部分 ROM 会拒绝或把名字截断到第一个 `/` 之后。现统一替换成下划线，空名回退「歌单」。

### P3-1：无曲库/权限拒绝终态的「假播放」与脏计数

三个缺陷点，逐一处：

| 缺陷 | 修法 |
|---|---|
| `playing` 初值 `true`，空队列时 tick 仍推进占位曲（1 秒）→ `handleTrackEnd` → `bumpPlayCount("placeholder")` 把脏计数写进快照 | `AppState.hasSongs`（`queue.isNotEmpty()`）；`tick()` 首部判空即熄火并 `playing = false`；`bumpPlayCount` 对占位 id 直接 return |
| 悬浮 Chrome 显示「暂无歌曲·未扫描」+ 进度线走动 + 波形按播放态渲染 | 同上：`playing` 归 false 后波形/进度都如实静止 |
| 启动后约 1 秒走 `stopAtEnd` | 同上 |

### P3-2：播放中每 5s 全量重写 queue_state.json + DataStore 快照

两处写放大分别节流：

| 目标 | 旧 | 新 |
|---|---|---|
| `files/queue_state.json`（服务端恢复现场） | tick 每 5s 一次，整库队列（万首 ≈ 1MB JSON）序列化 | `shouldWriteQueueState()`：队列引用/索引变了立即写；稳态下至少间隔 `QUEUE_STATE_MIN_INTERVAL_MS = 20s`；暂停态放行（位置不再漂移，写一次保住精确续播点） |
| DataStore 快照 | tick 每 5s 一次，整份快照（歌单+收藏+播放次数）序列化 + 原子替换 | `TICK_SAVE_INTERVAL = 200`（20s）；切歌/暂停/收藏/设置等真实状态变化各有即时 `scheduleSave`，不受此节流影响 |

### P3-3：播放中「恢复备份」的现场不生效

`prepareResume` 首行 `if (engineLoadedUri != null) return`，播放中恒成立 → 备份队列永远接管不了引擎：UI 显示备份现场、音频还在播旧队列，下一次切歌 `syncFromEngine` 又把队列卷回旧现场。

`applyRestoredPlayback()` 改为：先 `stopAndClear()` 停掉旧队列并清 `engineLoadedUri`，再走 `applyPendingPlayback()`，让 `prepareResume` 能把备份队列装载为「暂停待播」态，末尾 `scheduleSave()`。

### P3-4：详情/搜索结果非懒加载

| 屏 | 改法 |
|---|---|
| `PlaylistDetailScreen` | 抽出 `DetailHeader` / `DetailHero` / `DetailTrackRow` / `EmptyTracksHint` 四个私有 composable，两个分支共用。**不可重排分支**改 `LazyColumn` + `itemsIndexed(key = song.id)` 真懒加载；**可重排分支**保留 `Column + verticalScroll + ReorderableColumn`（`ReorderableColumn` 的行数学依赖 `ScrollState` 与「直接父级即滚动内容」两条前提，改 LazyColumn 会破坏它，v1 已明示取舍）。<br>视觉：原「一整块 GlassSurface 包住全部行」在 Lazy 下不可行，改为按行切**首尾相接的色带**（`Modifier.detailCardSegment(isFirst, isLast)`），描边手绘只画左右竖边 + 首段上圆角/顶高光 + 末段下圆角，避免 `Modifier.border` 在中段每行间多出 1dp 横线 |
| `SearchScreen` | 外层 `Column(statusBarsPadding().imePadding())` 保留，内层换 `LazyColumn`；搜索框/历史/空态/结果 `items(key = id)`/专辑两列 `item`/展开按钮都作为 item |

### P3-5：底部导航文案与实际页面不符

`GlassNavBar` 文案 `曲库 / 发现 / 我的` → `曲库 / 歌单 / 设置`，图标 `Home / Compass / User` → `Home / Queue / Gear`。新增 `object NavLabel { Library/Playlist/Settings }` 单点定义，与 `GlassApp.TAB_SCREENS` 同序。

### P3-6：详情页 Chrome `navSelected = 1` 硬编码

新增 `AppState.detailNavSelected`，跟随 `detailReturn`（0 曲库 / 1 歌单 / 2 设置）。`PlaylistDetailScreen` 的 Chrome 与 `onOpenPlayer` 的 `beginEntry` 终点同用该值（否则高亮位和飞行终点会不一致）。

### P3-7：两条顶部提示条同位叠放

`ioMessage` 与 `playbackError` 原来各自 `align(TopCenter) + padding(top = 8.dp)`，是同一父 `Box` 的兄弟 → 同时出现时后绘者直接盖住前一条。改为共用 `Column` 纵向栈 + 抽出 `TopBanner(message, maxLines, onClick)` 统一样式；两条并存时才插入 6dp 间距（单条视觉与原来完全一致）。

### P3-8：READ_MEDIA_AUDIO 拒绝后无应用内再引导

| 位置 | 改动 |
|---|---|
| `SettingsScreen` | 「曲库」组新增「音乐与音频访问」行（仅 `audioDenied` 时出现，同 `SettingRow` 风格） |
| `LibraryScreen` | 歌曲 tab 空态新增 `LibraryEmptyState`：`audioDenied` 为真 → 可点击的授权行；否则中性文案「曲库里还没有歌曲 · 去设置页扫描音乐」 |
| `GlassApp` | 新增 `requestAudioPermission()`：能弹运行时弹窗就弹，**弹不出来就跳应用详情页**——被拒一次后 `launcher.launch()` 会立刻以 denied 返回且系统不显示弹窗，用户点了「去授权」却什么都不发生，比不给入口更糟。判据是 `shouldShowRequestPermissionRationale` + 本进程是否已问过 |

另：拒绝分支原来调 `markLibrarySettled()`（会连带 `abandonPendingPlayback`，把一次拒绝升级成「播放现场永久丢失」），改为 `settleLibraryKeepingPending()`——曲库不可用是当前状态而非终态，用户随时可能去授权，授权回来 `loadRealLibrary` 仍能把现场接上。

### P3-9：All-Files 引导「每进程一次」= 每次冷启动都跳

新增 `SettingsStore.allFilesPrompted()` / `markAllFilesPrompted()`（DataStore 独立键 `all_files_prompted`，与 `scan_filter_short` 同款；不进快照——引导状态不是用户设置，不该随备份迁移）。

`GlassApp` 改为：启动读持久化标记 → 只在**从未提示过**时自动跳一次；之后全靠设置页手动入口。读取完成前不跳（`allFilesPromptRead` 门控 + `snapshotFlow{...}.first{it}` 等待），避免「按初值跳过」或「按默认值误跳」。

### P3-10：飞行进行中按返回键被静默吞掉

`PlayerFlight.beginExit` 在 `phase != Idle` 时原来直接 `return` → 进入/退出飞行的约 500ms 窗口内按返回（或点收起键）什么都不发生。

改为：先中止在跑的飞行（`cancelJobs()` + `generation++` + 清瞬态），再直接翻页。手势意图是「离开播放页」，动画只是装饰，不该因为动画在跑就丢掉这个意图。

### P3-11：定时关闭「播完当前首」在手动切歌时立即暂停

两侧同时修，口径一致：

| 侧 | 改动 |
|---|---|
| 服务端 `MusicPlaybackService.onMediaItemTransition` | 只对 `REASON_AUTO`（自然播完）`pause()`；其它 reason（SEEK/PLAYLIST_CHANGED/REPEAT）**仍清旗标但不暂停**（悬空旗标会让下一次自然切歌莫名暂停，比丢定时更糟） |
| 客户端 `AppState.next(manual)` / `prev()` | 手动切歌时 `cancelOneShotSleepTimer()` 归零 UI 回显（服务端旗标已被消费，UI 不回显就会一直显示一个已失效的定时） |
| 文案 `sleepTimerSubtitle(-1)` | 「当前曲播完后暂停」→「当前曲自然播完后暂停 · 手动切歌不触发」，与行为一致 |

### P3-12：无效模块的孤儿构建脚本

删除 `core/{coloros,common,data,database,datalocal,datastore,domain,model,player}` 九个目录（每个只有一份自述「参考骨架」的 `build.gradle.kts`，无 `src`，不在 `settings.gradle.kts` 里，零引用）。同步更新 `settings.gradle.kts` 注释与 `README.md`（原文仍在描述「反编译骨架推断产物」，与现状不符）。

### P3-13：硬编码与魔数

`Spacer(height = 146.dp)` 三处（曲库/歌单/设置）→ `core/ui` 新增 `val ChromeBottomInset: Dp = 146.dp` 单点定义；`GlassChrome` 内部的迷你条/导航间距也提为私有常量。Chrome 高度调整从此只改一处。

> 「全部用户可见文案硬编码中文、无 strings.xml」按报告自己的定性（「对设计稿复刻工程是有意为之」「纯中文分发可接受」）未动——那是产品决策，不是缺陷。

### 存疑：封面缓存文件名 32 位 hashCode

`coverFileFor` 的 `"c" + Integer.toHexString(hashCode)` 与 `folderCoverKey` 的 `"d" + ...` → 统一改 `stableHash(s)`：SHA-256 前 8 字节，**手写 hex 查表**（不用 `"%02x".format()`，避免又踩 locale 问题且文件名出现非 ASCII 数字字形）。

迁移链（已写进 KDoc，四步）：
1. `readCacheEntries` 新名找不到 → 回落缓存里记录的旧 `artworkUri`
2. 该回落让 `artworkUri != null` 而新名文件不存在 → `coverLost` 成立 → 落回全量提取并按新名落盘
3. `sweepCovers` 保留集只含新名 → 旧 PNG 当孤儿删除
4. `writeCache` 写新名，此后稳定

⚠️ 第 1 步不只是「冷启动别变灰」，它还是第 2 步的**触发条件**：不回落的话条目会以 `artworkUri=null` 被当成「本来没封面」直接复用，随后 `writeCache` 把空 uri 写回 → 封面**永久**丢失、再也不自愈。为此补了 `legacyArtworkFile()`（只接受 `file://` 且盘上真实存在）。代价：升级后首次全量扫描要为有封面的歌各重提取一次，一次性。

---

## 三、第二份清单（P2/P3）逐条落地

### P2-1：派生查询无缓存，O(n·m) 线性扫库与全库重复分组

三处状态层缓存，全部按「引用相等」失效（`library` 只在装载/重扫时整体换新 List）：

| 缓存 | 内容 | 解决的形状 |
|---|---|---|
| `groups()` | 一次算好专辑/艺术家/文件夹/流派四套分组 + `albumCountByArtist` 映射 | 原先每次调用都整库 `groupBy + sortedBy + map`；详情页艺术家分支还调两次 |
| `songIndex()` | `id → DemoSong` HashMap | `songById` 原 O(n) 线性扫，`resolvePlaylist` 是 O(集合×曲库) → 降到 O(n+m) |
| `searchAlbumGroups` | 直接过滤 `albumGroups()` 缓存，不再对全库重新 groupBy | 语义严格等价（原实现也是先分组再按专辑名/艺术家/曲名过滤，返回整张专辑） |

`detailData` 的艺术家分支改读 `albumCountByArtist()[key]`，消除第二次整库分组。

屏层配合：`SearchScreen` 的 `searchSongs`/`searchAlbumGroups` 加 `remember(state.library, query)`；`PlaylistDetailScreen` 用 `derivedStateOf` 包 `detailData`（**不能用纯 remember**：`favorites`/`playedIds`/`playCounts`/`userPlaylists`/`addedTracks`/`hiddenBuiltInPlaylists` 都是稳定引用的 SnapshotState 集合，既不能当 key 也不会让 remember 失效 → 收藏/完播计数/加歌后会给出陈旧数据；`derivedStateOf` 的 lambda 只在被读时求值并自动登记读到的 snapshot 状态为依赖）。

### P3-1：队列长拖中自动切歌 → 槽位内容位移 → 松手提交错位的歌

`ReorderableColumn` 新增 `dataVersion: Any? = null`（默认值 → `PlaylistDetailScreen` 调用点零改动）：行槽 `key(dataVersion, index)`、`pointerInput` 键含 `dataVersion`、`LaunchedEffect(dataVersion)` 显式取消拖拽与落位补间，另加「拖拽起点版本快照 vs 松手实时版本」提交前比对作为最后一道闸。

`QueueSheet` 传 `dataVersion = safeIndex to queue.getOrNull(safeIndex)?.id`。

**关键取舍**：版本**不掺段内顺序**——段内重排的 `onMove` 提交本身会改变段内顺序，算进版本就会在提交那一帧重建槽位、顺手取消掉刚落位的补间（第 4 项直接失效）。只取「当前曲索引 + 当前曲 id」：段内重排只动 `> safeIndex` 的区间（两者不变），而自动切歌/引擎队列回流都会变，覆盖全部错位情形。代价：位移那一帧拖拽视觉中断、需重新长按——正在自动切歌本就不该拖队列，丢一次手势远比提交一次不可撤销的错位移动安全。

### P3-2：ModalSheetHost 固定延时清空与退场动画竞态

原实现 `delay(tweenPage 时长)` 与 `AnimatedVisibility` 退场同源同值同时起表，帧抖动下 content 提前清空 → 退场最后 1-2 帧被截断。

走**正路**而非加余量：
- `GlassSheet` 改 `AnimatedVisibility(visibleState = MutableTransitionState)`，退场真正空闲时回传信号；信号经 `internal LocalSheetExitFinished`（CompositionLocal）下发，**既有 `GlassSheet` 调用点零改动**
- `ModalState.exitAnimationId` 代数 + `ModalSheetHost` 用 `snapshotFlow{visible}.collectLatest`：`visible` 翻回 true 即取消等待（重开不误清）；落笔前再判一次 `!visible`
- `withTimeoutOrNull(时长 + 64ms)` 只作**活性兜底**（嵌套子面板在打开期已切走、这次关闭没有任何子面板真跑动画时），注释写明是兜底不是精度依赖
- 用 `hasBeenVisible` 门控，防「初次组合就 `visible=false` 的嵌套子面板立刻误报退场」

### P3-3：LocalModalState 默认工厂产生孤儿实例

`compositionLocalOf { ModalState() }` → `compositionLocalOf<ModalState> { error(...) }`。provider 外读取会拿到孤儿实例：面板能 `show`、`visible` 也翻 true，但没有 `ModalSheetHost` 渲染它 → 表现为「点了没反应」且不报错。漏 provider 是编排错误，应当在开发期就炸掉。

已 grep 全仓确认：`GlassApp.kt` 唯一 provides，各屏均在 provider 内 `current` 读取；无 `@Preview` / 测试依赖默认值。

### P3-4：AddSongsSheet 自身不随 visible 重置

补 `LaunchedEffect(visible) { if (!visible) { query = ""; selected.clear() } }`。原实现只在 KDoc 里声明「每次打开都是新组合，天然重置」——那依赖调用方必须经 `modal.show`。组件契约不该寄托在调用方式上。同步修正 KDoc。

### P3-5：松手瞬间被拖行跳变，无落位补间

`ReorderableColumn` 引入 settle 过渡态 + 150ms 落位补间：
- 共享状态（`settleRow`/`settleFrom`/`settleValue`/`settleGen`）+ 手写 `withFrameNanos` 帧循环，而非每行一个 `Animatable`（行数可达数百）；`Animatable.snapTo` 是挂起函数，无法保证「初值写入」与 `settleRow` 切换同帧，会先按槽位画一帧再跳回（等于没修）
- 补间起点 = `visual + (from-target)*rowPx`（提交后新槽位下视觉不变的位移）；缓动 `FastOutSlowIn` 与邻居 150ms 让位同步收束；缩放 1.02→1.0 同步收回避免「啪」一下
- `onMove` 提交语义与行列数学未变

### P3-6：WaveIndicator 高度动画走组合期状态

`animateFloat` 值直接进 `Modifier.height(h.dp)`（组合期读状态）→ 无限动画下每帧重组 + 重新测量/布局。

改为：固定条高 `13dp`，`graphicsLayer { scaleY = h / 13f; transformOrigin = TransformOrigin(0.5f, 1f) }`，动画值只在绘制期 lambda 读 → 零重组零重测量。视觉等价：竖条是纯色不透明 2dp 圆角矩形，纵向缩放不改颜色与横向轮廓，底边钉住后顶边起伏 = 原「底对齐 + height 变化」；唯一差异是 1dp 圆角纵向变扁，在 4~13dp 区间不可辨。`WaveDelays` 提为顶层 `intArrayOf`（原组合内 `listOf` 每帧分配）。

### P3-7：AuroraBackground / glassTopLine 每帧分配 Brush

`drawBehind` 内每帧新建 ≥5 个 Brush（底色 1 + 光源 3 + 暗角 1），背景常驻全 app、60fps 下约 300 次/秒。

改 `drawWithCache`：
- 底色与暗角只依赖 `(palette, isLight, size)` → 在 cache 块内预算，帧间复用
- 3 团光源的圆心/半径每帧随漂移变化 → 把 Brush 建成**单位空间**（`center = Offset.Zero, radius = 1f`，只依赖配色），绘制时用 `withTransform { translate; scale }` 承担几何、`drawCircle(radius = 1f)` 由画布变换放大。**每帧零 Shader 分配**，palette/明暗变化时 cache 自然失效
- `Glass.glassTopLine` 同改 `drawWithCache`

### P3-8：播放进度经参数逐层下传，Chrome/播放页 10Hz 整树重组

见第一份 P2-1/P2-2：`progressProvider` 方案，读取下移到 `drawWithCache` / `graphicsLayer` 绘制期。

### P3-9：GlassTheme 取色三级动画级联（报告标「存疑」）

核实为笔误：去掉最外层 `LocalAuroraPalette provides animatePalette(effectivePalette)`，直接 `provides effectivePalette`。保留 dark/light 两次 `animatePalette` + 按 `themeFraction` 混合（那才是注释描述的两层设计）。取色收敛从约 2×400ms 回到单个 400ms，与 `GlassColors` 交叉渐变同步收束。

### P3-10：队列面板打开期间 currentIndex 变化触发 scrollTo 与拖拽 autoscroll 竞争（报告标「存疑」）

核实为真实风险，但与 P3-1 同源。P3-1 把语义定为「拖拽中内容位移 → 本次拖拽作废」后，切歌即取消拖拽，不存在两个写入方并存的窗口。**未额外加锁**——加锁会掩盖「拖拽中切歌」这个本就异常的场景，作废语义更简单也更安全。

### P3-11：playSong 对「歌不在 context」静默回退到 context[0]

`val index = context.indexOf(song).coerceAtLeast(0)` → 显式守卫：`index < 0` 时记日志并**原地不动**（不切歌、不改索引），不再静默播成队首。当前各调用点传参都正确，这是边界守卫。

---

## 四、第二份清单里 A/B/C 三组

| 组 | 项 | 修法 |
|---|---|---|
| A1 | SettingsStore 自建 IO scope 永不取消 | 新增 `cancel()`；`GlassApp` 用 `DisposableEffect(store) { onDispose { flushBlocking(); cancel() } }`（Activity 重建时不再泄漏 scope + collector） |
| A2 | 服务恢复路径主线程同步读盘 | `onPlaybackResumption` 改返回 `SettableFuture`，读盘丢到**实例级**单线程执行器（不是 companion 静态——首个实例 `onDestroy` 关掉后第二个实例就永久不可用）；`RejectedExecutionException` 兜底就地执行；失败**绝不 `setException`**，一律 set 合法空结果（否则 Future 永不完成、系统侧一直挂着） |
| A3 | UpdateManager 从 IO 线程高频写 Compose 状态 | 节流：整数百分比变化**或**距上次 ≥100ms 才写（后者给龟速网络补偿，避免几十秒跨不过 1% 时进度条看起来卡死）；`startUpdate` 重置基准 |
| A4 | check()/checkSilently() 可并发重入 | `AtomicBoolean checkInFlight` **同步** CAS 置位后再 launch（`checkSilently` 共用同一把锁）；`try/finally` 释放，协程被取消也放 |
| B1 | 持久层静默吞异常簇 | 补 `Log.e/w`（不改降级逻辑）：QueueStateStore 写/读/清、MediaLibrary 写缓存/封面落盘/标签读取/格式提取 |
| B2 | 首扫失败收敛为演示终态并丢弃待恢复现场 | 新增 `AppState.settleLibraryKeepingPending()`：放行启动门面但**不清 pending**。失败是可重试的中间态，pending 必须活下来（下次 `loadLibrary` 成功时正常消费） |
| B3 | release 残留调试日志 | 删除 `Log.d("MediaLibrary", "loadRealLibrary got ...")` |
| B4 | 宽 catch 群 | 补日志：`GlassApp` 的 versionName 读取、`UpdateManager.currentVersionCode()`（后者退 0 的理论后果是「任何清单都判定有新版」，真出现要有痕迹可查） |
| C1 | 全量硬编码中文、无 strings.xml | **未动**。报告自己定性「对纯中文分发可接受」「对设计稿复刻工程是有意为之」——属产品决策。若将来要公开发布，建议单独一轮整体抽取 |
| C2 | 默认 locale 格式化三处 | `DemoSong.duration` / `Int.asTime()` / `UpdateManager` 的 hex 全部固定 `Locale.US`。默认 locale 在 ar-EG / fa-IR 会输出本地化数字字形（如 ٣:٢٥），`sha256`/签名比对会因此恒不相等 → 更新永远失败、fail-closed 永远拒装 |

---

## 五、需要真机验收的点

代码层无法再验证的（建议拿到设备后按此清单走）：

| 项 | 验收方法 | 期望 |
|---|---|---|
| 搜索/详情页播放中不卡 | 播放中停在搜索页，单字查询（命中数百行），观察帧率/发热 | 不再每 100ms 全库扫描 |
| 详情页分段卡片观感 | 进任意专辑详情，上下滑动 | 曲目区看起来是**一张**卡（左右竖边连续、首尾圆角、无每行横线），与原单卡视觉等价 |
| 队列拖拽 | 队列面板长按拖拽；另一路：拖拽中恰好一首播完自动切歌 | 正常拖拽有 150ms 落位补间；切歌瞬间拖拽作废（不提交错位移动） |
| 面板退场 | 反复快速开关任一动作面板 | 退场动画完整播完，不被截断 |
| 定时关闭 | 设「播完当前首」→ 手动点下一首 | **不暂停**，且面板定时状态归零 |
| 定时关闭 | 设「播完当前首」→ 让它自然播完 | 暂停，面板归零 |
| 权限拒绝引导 | 首装拒绝音频权限 → 曲库页/设置页 | 都有「去授权」入口；点第二次应跳应用详情页（而非无反应） |
| 权限恢复 | 拒绝后去系统设置手动授权 → 返回 app | 自动重扫装载，且恢复的播放现场仍在 |
| All-Files 引导 | 拒绝 All-Files 后冷启动 3 次 | 只在首次自动跳系统页，之后不再打扰 |
| 恢复备份（播放中） | 播放中点设置→恢复备份 | 旧队列**停止**，UI 与引擎都切到备份现场（暂停待播），下一次切歌不再卷回旧队列 |
| 飞行中返回 | 播放页点收起，500ms 内立刻按返回 | 直接回曲库（不再无反应） |
| 封面换图/删图 | B4 三项验收 | 放图→重扫显示；删图→重扫回退占位；换图→重扫更新 |
| 升级封面命名 | 从旧版本升级（旧 `c<hash32>.png` 缓存） | 首次重扫后封面全部正常（允许一次重提取），无灰块、无串图 |
| 内存 | 万首级曲库滚动 + 反复进播放页 | `adb shell dumpsys meminfo` 不随曲目数线性增长 |

---

## 六、本轮改动面

30 个文件修改（+2504 / −817），新增 2 个文件：

- `core/ui/.../components/ArtworkBitmapCache.kt`（上一轮新增，本轮沿用）
- `app/src/main/res/xml/data_extraction_rules.xml`（上一轮新增，本轮沿用）
- `core/{coloros,common,data,database,datalocal,datastore,domain,model,player}/build.gradle.kts` 九个文件**删除**

未改 `versionCode` / `versionName`，未 commit。

*核实与修复由 Lead + 3 个并行修复员完成，写域按文件切分互不重叠；最终由 Lead 统一编译验证并复核 diff。*
