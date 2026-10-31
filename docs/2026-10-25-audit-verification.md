# 全项目只读审查报告 · 复核结论

> 复核对象：`docs/` 外提交的《全项目只读审查报告（HEAD=2c95774）》
> 复核方式：逐条对源码取证（read / grep / git blame / git log -S / javap 读 aar），**全程只读，未改动任何源码**
> 复核日期：2026-10-25

## 0. 基线与方法

| 项 | 报告 | 复核实测 |
|---|---|---|
| HEAD | `2c95774` | 实际 `852d9b7`（release v1.0.3） |
| 源码一致性 | — | `git diff 2c95774 852d9b7 -- '*.kt'` **空输出**，Kotlin 源完全一致，仅 `apk/`、`update.json`、`app/build.gradle.kts` 版本号差异 → **报告行号可直接对 HEAD 引用** |
| 规模 | 35 文件约 1 万行 | 35 文件 **9,681 行**（吻合） |
| P2 数量 | 声称 19 项 | 实际只枚举出 **18 项**（见 §1），**计数有 1 项误差** |

**复核结论一句话**：报告的**方向判断基本可信**（18 项里 14 项确认、3 项部分确认），但**严重度分布整体失真**——4 项该升 P1（封面/OOM 链）、3 项该降 P3、1 项不成立；同时**漏掉 1 项 P1（更新包签名校验缺失）**和 2 项真实 P2。

---

## 1. 19 项 P2 逐条裁决

### 簇 1 · MostPlayedId 动态歌单守卫（原判 2 项 P2）

| # | 报告主张 | 裁决 | 严重度 |
|---|---|---|---|
| 1 | `PlaylistDetailScreen.kt:126` 加歌入口漏 MostPlayedId | **部分确认** | P2 → **P3** |
| 2 | `ActionsSheet.kt:316` isDynamic 白名单漏 MostPlayedId | **确认** | P2 → **P3** |

**事实**：两处白名单都硬编码 `FavoritesId || RecentId`（`PlaylistDetailScreen.kt:125-127`、`ActionsSheet.kt:316-318`），`deletePlaylist`（`AppState.kt:1172-1180`）对 `__most_played` 两分支都不进 → 删除按钮**会显示但点了无效**。加歌写入落 `addedTracks["__most_played"]`，而读取侧 `AppState.kt:1332-1340` 只读 `playCounts`，**写入永无出口**。

**报告隐含前提错误（重要）**：报告行文让人以为 MostPlayed 是"未上线功能"。实际读取侧完整实现（`AppState.kt:1332-1340`）、UI 已渲染（`PlaylistScreen.kt:187-198`）、双点位计数（`AppState.kt:411` / `:904`）、快照 v7 持久化全在位。**这是"新功能接入漏同步白名单"的回归型遗漏，不是死代码。**

**降级理由**：后果仅"点了没反馈"+ 快照里多一条 `__most_played` 脏键，无数据损坏、无崩溃，用户可正常用其他出口。建议 **1+2 合并为 1 条 P3**。

**根因与修法**：全仓动态 id 只有 `DemoCatalog.kt:46-48` 三个常量，两处各自硬编码 → 加 `val dynamicIds = setOf(FavoritesId, RecentId, MostPlayedId)`，两处改 `id !in DemoCatalog.dynamicIds`，一处根除。

---

### 簇 2 · 睡眠定时「播完档」双侧不同步（原判 2 项 P2）

| # | 报告主张 | 裁决 | 严重度 |
|---|---|---|---|
| 3 | UI 归零不回发服务端，旗标成孤儿 | **确认**（归因需改） | P2（偏轻） |
| 4 | `PLAYLIST_CHANGED` 也消费旗标 | **部分确认**（结局判定需改） | P2 → **P3** |

**#3 确认**：全仓唯一归零入口 `AppState.kt:404` 只改本地 `var`，**无** `engine?.setSleepTimer(0)`；下发链只有 `AppState.kt:1000-1003` → `MediaControllerEngine.kt:195-216`，调用点仅两个 UI 面板。服务端 `stopAfterCurrent` 置位 `MusicPlaybackService.kt:261`、消费 `:159-161`、唯一清除 `:251-252`，`onTaskRemoved`/`onDestroy` 都不清 → **孤儿成立**。

**归因修正**：`AppState.kt:404` 由 **2c95774 引入**（`git blame` 实测），`022ba4a` 里根本没有这行。022ba4a 是"取消后面板仍显示已开启"，**2c95774 的修复才制造了"UI 归零但服务端仍 armed"**。报告把引入 commit 写错了。

**复现（真机 + 真实引擎，演示模式不可复现）**：≥2 首队列播放 → 设「播完当前首」→ **来电接听**（焦点抑制致 `isPlaying=false` 触发 404 归零）→ 挂断、面板显示"未开启" → 本曲播完仍暂停。一次性、自愈（消费即清）。

**#4 部分确认**：`MusicPlaybackService.kt:157-163` 确实不判 `reason`（同仓 `MediaControllerEngine.kt:52` 自己就过滤了 AUTO，属漏写）。但报告给的两个互斥猜测，**实际是"猜测二"**：`loadQueue` 是 `setMediaItems` → `prepare()` → `playWhenReady = autoPlay` 三个**有序**远程任务（`MediaControllerEngine.kt:146-156`），服务端在 `setMediaItems` 回调里 `pause()` 后立刻被随后的 `setPlayWhenReady(true)` 覆盖 → **新歌照常播、旗标已被消费 → 定时被静默吞掉**，不存在"无故暂停下一首"。

**降级理由**：只让"用户刚主动切歌时少停一次"，无异常暂停、无状态残留。**且"修复成只认 AUTO"反而更危险**（手动切歌后旗标永久残留），正确修法是 SEEK/PLAYLIST_CHANGED 时**只清旗标不 pause**。

---

### 簇 3 · 封面生命周期四连（原判 4 项 P2）— **整体被低估**

| # | 报告主张 | 裁决 | 严重度 |
|---|---|---|---|
| 5 | `persistCover` 见文件存在即跳过，换图不覆写 | **确认** | P2 ✔ |
| 6 | reuse 分支不查源 cover.jpg，删图不回退（B4 必失败） | **确认** | P2 → **P1** |
| 7 | 清缓存不自愈，内嵌封面永久消失 | **确认** | P2 → **P1** |
| 8 | `loadCache` 无输出层过滤，冷启动绕过 B3 | **确认** | P2 ✔ |

**#5**：`MediaLibrary.kt:318` `if (!file.exists() || file.length() == 0L)`。换内嵌封面后：扫描#1 走提取 → in-app 新图但 `artworkUri` 仍指旧 PNG → **通知栏立刻旧图**；扫描#2 指纹命中 reuse → 解码旧 PNG → **in-app 也回滚**，且 sweep 保留集永远保住该 PNG，**无任何删除路径**。

**#6 升 P1（发布阻塞）**：reuse 分支（`MediaLibrary.kt:151-155`）只查 `coverFileFor` / `folderCoverCacheFile` 两个缓存 PNG，**从不调用 `folderCoverFileFor` 去探真 cover.jpg**。因此：

- B4 验收项"删掉 cover.jpg 后重扫应回退" → **必失败**
- B4 正向验收"放 cover.jpg → 重扫应显示" → **在增量路径下同样失败**（`d<hash>.png` 从未生成，图恒为 null）

**关键补充**：指纹 = `dataPath + DATE_MODIFIED + SIZE`，只跟音频文件走，**删/换 cover.jpg 完全不影响它** → 首扫之后 reuse 就是**常态路径**，缺陷从第 2 次扫描起 100% 暴露。唯一遮蔽情形是 `DATE_MODIFIED=0` 或 `SIZE=0`（adb push）的行，**B4 验收步骤一条都不满足，无兜底路径**。

**#7 升 P1**：`externalCacheDir` 被系统/清理助手清空后，缓存 JSON 仍在 `filesDir` → `artworkUri` 指向已删文件（`:600-601` takeIf 归 null）→ 重扫走 reuse：`hasOwnCover=false` + `folderCoverCacheFile=null` → `restoredCoverFile=null` → **只置空、不触发重提取**；指纹不变故永不恢复。**清一次缓存 = 全库封面 + 通知栏配图永久变占位。**

**#8 确认**：`loadCache(context)` 无 `filterShort` 形参（`MediaLibrary.kt:573-575`），且 `writeCache` 刻意把被滤掉的短音频**补回缓存**（`:656`）→ 缓存必然含 <30s 条目 → 冷启动 `loadLibrary(cached)` 先上屏，**每次冷启动复现**。

---

### 簇 4 · 恢复链路接缝（原判 3 项 P2）

| # | 报告主张 | 裁决 | 严重度 |
|---|---|---|---|
| 9 | 播放中恢复备份被反向覆盖 | **部分确认** | P2 ✔ |
| 10 | 启动竞态（报告自注"待验证"） | **不成立** | **撤销** |
| 11 | `onPlaybackResumption` 丢模式 | **确认** | P2 ✔ |

**#9 部分确认**：`prepareResume` 确实早退（`AppState.kt:755` `if (engineLoadedUri != null) return`），播放中恒成立 → 只换 UI 不换引擎；`syncFromEngine`（`AppState.kt:451-460`）确实按服务队列权威冲回。前提真实可达（`GlassApp.kt:398-421` 运行期随时可点）。

**但"现场无效"被高估**：`restore()` 已同步下发 shuffle/repeat/speed/EQ（`AppState.kt:623-624`），`syncFromEngine` 不回写 playing/进度。真实后果 = **备份队列没接管引擎、旧歌继续播**，5s 后 tick 把服务现场写回快照 → **播放现场丢失，但设置/歌单/收藏/模式已生效**。P2 而非 P1。

**#10 撤销（报告判断错误）**：`store.read()` 与 `loadRealLibrary()` **不是并发协程**——两者在**同一个** `LaunchedEffect(Unit)` 内顺序执行（`GlassApp.kt:182-204`），`store.read()` 在 `:192` 同步 suspend，`loadRealLibrary` 在更晚的 `:259-273` 才 `scope.launch`。且即便扫描先完成，`loadLibrary` 的 `when` **首分支就是 `pendingPlayback != null ->`**（`AppState.kt:716-724`），全新装载分支（`:738-744`）根本轮不到。

报告说"末尾缺 `if (realLibraryLoaded) applyPendingPlayback()`"——**实际已有等价物三重覆盖**：`loadLibrary` 内联消费（`:717-723`）+ 备份入口显式 `applyRestoredPlayback()`（`AppState.kt:667` / `GlassApp.kt:415`）。pending 消费点共 4 处（`:723` / `:667` / `markLibrarySettled:684` / `abandonPendingPlayback:670`）。**"待验证（机制确定）"应改为"已由并发模型排除"**。

**#11 确认**：`QueueStateStore.kt:29-33` + `:56-61` 只存 specs/index/positionSec，条目只含 id/uri/title/artist/album/artworkUri/durationMs，**无 shuffle/repeat/speed/EQ**；`onPlaybackResumption`（`MusicPlaybackService.kt:90-106`）不加任何模式；`onConnect` 只声明命令白名单。服务端读不到 DataStore（`QueueStateStore.kt:16` 注释明示）。

**措辞修正**：不是"直到打开 App 才纠正"，而是 **`attachPlayer` 一重连就纠正**（`AppState.kt:441 syncEngineModes`）。后台蓝牙/通知续播**全程默认模式**成立，EQ 最明显（`lastPreset` 仅服务存活期内有效）。

---

### 簇 5 · 飞行系统协程取消（原判 2 项）

| # | 报告主张 | 裁决 | 严重度 |
|---|---|---|---|
| 12 | 中止不 cancel 协程，残尾 `navigate(Library)` 劫持导航 | **确认** | **P1 ✔ 保持** |
| 13 | 替身可见性依赖 t==0，等待期闪没 | **确认** | P2 ✔ |

**#12 确认**：`PlayerFlight` **根本没有 Job 字段**（`Flight.kt:57`）；`beginEntry`/`beginExit` 的协程及 `flip` 子协程全不取消；退出协程尾部 `navigate(Library)` 无条件执行（`Flight.kt:216-227`，两处：220 与 224）。

**最强复现（无需新交互）**：播放页点"收起"开始退出飞行 → 飞行窗口内**旋转/拖拽分屏**（manifest 吞配置变更，组合不重建，仅 `onSizeChanged` 触发）→ `onRootSizeChanged()` 置 Idle 清 snapshot → 用户仍停在播放页什么都没点 → 旧协程的 `flip`/`animateTo` 继续跑完 → **约 350ms 后两次 `navigate(Library)` 把用户弹回曲库**。

**一处细节需如实记录**：新飞行时 `Animatable` 的 mutex 会**意外**取消旧 `animateTo`（旧协程在 `:222` 抛 CancellationException 从而跳过 223-226）——但这是巧合不是设计，且此时旧协程也跳过了收尾清理。另存在"代际守卫不足"：中止后新 `beginEntry` 把 phase 又置 Entering，残旧协程的 `phase != Entering` 守卫即失真。

**#13 确认**：替身几何只读 mini，但**整体 alpha 仍由 t 派生**（`PlayerFlightOverlay.kt:93-98`）；`t` 只在起飞瞬间 `Flight.kt:185` 才 `snapTo(0)`。中止路径留下的 t 残留（1 或 0.88~1）使 `enteringWait` 期 alpha≈0 → **正是该机制要消灭的迷你条闪没**。`Flight.kt:139-141` 注释自述"t 恒为 0"，**是未强制的假设**。

**修复方案复核（报告"一个 Job + 起飞前 snapTo(0)"只能修主体，漏 4 点）**：
1. `snapTo` 是 suspend，`beginEntry` 同步段无法立刻归零 → **首帧竞态仍在**；正解是让替身 alpha 在 `enteringWait` 时直接取 1，不依赖 t
2. 退出侧对称缺口：加了 `job.cancel` 后中止会留 t=中间值，而 `beginExit` 的 `snapTo(1f)` 在协程内（`:217`）→ 退出首帧"替身 alpha=0 且真身 miniSharedAlpha=0"**双隐闪**
3. 中止重置集合不完整：`t` 与 `started` 未纳入（`:238-246` 只清 phase/entryShowCover/songSnapshot/snapshot/rects）
4. Job 要 entry/exit 各一个，守卫应由 job 相等 / generation 计数判定，**不能靠 phase**

---

### 簇 6 · tick 全库派生泄漏（原判 1 项 P2）

| # | 报告主张 | 裁决 | 严重度 |
|---|---|---|---|
| 14 | 搜索/详情页在组合体裸调全库派生，被 0.1s tick 驱动 | **确认**（归因需修正） | P2 ✔ |

**确认**：`SearchScreen.kt:63-65`（`searchSongs` + `searchAlbumGroups`）、`PlaylistDetailScreen.kt:83`（`detailData`）都在 composition body 直接算；**全项目 0 处 `derivedStateOf`**，这两屏无 `remember` 包裹。对照 `LibraryScreen.kt:68-77` 对 `sortedSongs` + `albumCountByArtist` 做了 `remember(library, songSort)`。

**归因修正**：`GlassChrome` 只是形参消费者（`Chrome.kt:284-302`，progress 仅用于 `:190` 进度线）。真正读 state 的是**调用方屏幕 body**：`SearchScreen.kt:204`、`PlaylistDetailScreen.kt:308` 的 `progress = state.progressFraction`（`AppState.kt:351` 读 `progressSec`，`mutableFloatStateOf` at `:251`）→ 这两屏 body 每 tick 重组 → 派生重算。**结论仍成立，但排查方向要改：不是修 Chrome，是修这两屏的 progress 读取位置。**

**tick 来源裁决**：**本地 launch 循环**（`GlassApp.kt:299-304` `while(true){ delay(100); state.tick() }`），不是服务端推。`!playing` 时 tick 早退（`AppState.kt:768`）→ **暂停时不漏，仅播放中漏**。

**量级（1 万首、播放中）**：搜索页 ≈ 2-5ms/tick（10Hz ≈ 单核 2-5% + 每 tick 2 个 n 级列表与专辑 Map 的 GC 压力）；详情页更差——`resolvePlaylist` 对每首走 `songById = library.firstOrNull`（`AppState.kt:1314-1319`）⇒ 200 首收藏 × 1 万库 = **200 万次 String.equals ≈ 10-20ms/tick**。

**补充（修正报告"LibraryScreen 已整治"）**：LibraryScreen 只缓存了 2 项，tab 1-4 的 `albumGroups()`/`artistGroups()`/`folderGroups()`/`genreGroups()` 仍是 body 内裸调（`:170/193/210/227`）→ **性能整治只覆盖了歌曲 tab**，报告的"A4 只治了曲库页"实际还要再收紧为"只治了曲库页的 2 个派生"。

---

### 簇 7 · MediaController 断连无重建（原判 1 项 P2）

| # | 报告主张 | 裁决 | 严重度 |
|---|---|---|---|
| 15 | Builder 未挂 onDisconnected、无重绑路径 | **确认**（比报告更严重） | P2 ✔ |

**确认**：`GlassApp.kt:276-296` **未调 `.setListener(...)`**（整仓 `onDisconnected` 零命中，仅 `MediaControllerEngine.kt:128-131` 注释自承"无处挂"）。`future.addListener` 只在 future 完成时跑一次，失败即结束，无重试。全仓 `MediaController.Builder` / `attachPlayer` 都只有这一个点，`startService` 零命中。

**media3 1.11.1 字节码实证**（javap 读 aar）：`MediaControllerImplBase$SessionServiceConnection.onServiceDisconnected` / `onBindingDied` 的实现是 **`runOnApplicationLooper(MediaController::release)`** —— 框架标准动作是**直接 release controller，不自动重绑**。所以断连后 controller 进 released 态，`isConnected` 永久 false。

**比报告更差的一点**：断连后是**静默死亡**。`checkConnected` 在 `positionSec()` 被调用（tick 每 100ms），报错后 `AppState.kt:412-417` 置 `playing=false` → `tick()` 立即 `if (!playing) return`（`:768`）**不再检测**；`togglePlayPause` 走 `startCurrent` → `loadQueue`（**未走 checkConnected**，`:146-156` 直调）→ released controller 上的调用**被静默丢弃**。结果是**点了没反应也不再报错**，只能杀进程。

---

### 簇 8 · 重命名绕过重名拦截（原判 1 项 P2）

| # | 报告主张 | 裁决 | 严重度 |
|---|---|---|---|
| 16 | 重命名路径不查 `playlistTitleExists` | **确认**（比报告多一层） | **P2 ✔ 保持** |

**确认**：新建路径双入口预检（`PlaylistScreen.kt:97` `duplicate` + `:104/:122`），重命名路径（`ActionsSheet.kt:254-259`）**无任何预检**，子面板（`:330-346`）只有 `isBlank()` 三态；`AppState.kt:1234-1241 renamePlaylist` 内部也不查。

**报告漏掉的一层（这才是 P2 的实质）**：`createPlaylist` 有同名合并语义（`AppState.kt:1145-1150` `firstOrNull{...}?.let { return it }`），而 `renamePlaylist` **既不拦截也不合并**，直接 `copy(title=...)` → 造出**两个同名歌单**。之后同名合聚会命中 `firstOrNull` 里较早那个 → **内容落到意料之外的歌单**，属持久化状态歧义。

---

### 簇 9 · 位图内存失控（原判 2 项 P2）— **整体被低估**

| # | 报告主张 | 裁决 | 严重度 |
|---|---|---|---|
| 17 | 文件夹 cover.jpg 裸解码，全尺寸无降采样 | **确认** | P2 → **P1** |
| 18 | 全库封面常驻 `coverBitmap` | **确认** | P2 → **P1** |

**#17**：`MediaLibrary.kt:201-203` `decodeFile(file.absolutePath)` 无 `Options`/无 `inSampleSize`；`folderCoverCache` 只缓存 **File** 不缓存 Bitmap（`:266` 注释声称"只查一次盘"——查盘确实只一次，**解码每首一次**）；落盘 PNG 同为全尺寸（`:303-306`）。

**内存算式**：`W×H×4 B × N`（N = 同目录无内嵌封面曲目数，**无去重**）
- 3000×3000 扫描封面：34.3 MiB/次；N=15 → **515 MiB**；N=50 → 1.7 GiB
- 常见 1200×1200：5.5 MiB/次；N=15 → 82 MiB
- **一个专辑夹 + 一张扫描封面就足以打穿默认堆**（Manifest **无 largeHeap**）

**#18**：`MediaLibrary.kt:616-618` 首帧前同步把每条 `artworkUri` PNG 解码进 `DemoSong.coverBitmap`，随 `AppState.library` 常驻；重扫 reuse 分支（`:164-166`）再全量解一遍。**全仓 `LruCache` = 0、无按需加载、无 recycle、无跨曲共享。**

**内存算式**：内嵌封面落盘前已降到宽约 160-250px → ≈250 KB/首
- 1,000 首 ≈ **244 MB**；3,000 首 ≈ **715 MB**；5,000 首 ≈ **1.19 GB**
- 叠加未降采样的文件夹兜底图 + reuse 重解码 → 瞬时峰值 **2×**
- 屏上同时可见缩略图仅 42-80dp（约 10-20 张），全尺寸常驻纯浪费

---

## 2. 严重度变更汇总

| 变更 | 项 |
|---|---|
| **升 P1（4 项）** | #6 B4 封面回退必失败 · #7 清缓存封面永久消失 · #17 文件夹封面裸解码 · #18 全库封面常驻 |
| **升 P2（4 项，原 P3 或未列）** | 幽灵曲库（见 §4）· `CMD_INSERT_SHUFFLE_FIX` 静默丢歌（见 §4）· allowBackup（见 §3）· APK 签名缺失（见 §4，实为 P1） |
| **降 P3（4 项）** | #1 加歌守卫 · #2 删除守卫 · #4 PLAYLIST_CHANGED · sleepTimer 归因修正后仍 P2 但偏轻 |
| **撤销（2 项）** | #10 启动竞态 · P3 清单「restore 后 meta 虚高」「M3U 演示曲垃圾路径」「同目录 .lrc 重复 IO」 |
| **数字修正（3 项）** | 换色延迟 800ms → **1200ms**（增强档 1680ms）· 琥珀对比度 ~2:1 → **2.15:1** · Brush "每帧 5 次" → 数量对但只在重绘帧分配 |

---

## 3. 「声称已修但只落一半」清单 · 复核

| 声称 | 复核结论 |
|---|---|
| sleepTimerMode 播完档暂停即清 | **成立**，但归因错：是 `2c95774` 引入的双侧不同步，不是 022ba4a |
| 备份恢复立即应用现场 | **成立**，限非播放态；播放中被反向覆盖（但设置/歌单已生效，P2 非 P1） |
| B4 删 cover.jpg 后重扫回退 | **成立且更坏**：不是"必失败"，连正向"放图应显示"在增量路径下也失败 → **P1** |
| 0146128 消费层提升到根层 | **成立（复制非迁移）**，但报告措辞错：是**指针事件**消费，不是 WindowInsets（全仓 WindowInsets 零命中）。两份都在且都只在飞行期挂（`GlassApp.kt:466-474` + `PlayerScreen.kt:178-192`），Initial 隧道幂等 → 无实害。真风险是**两处闸门条件不同源**（`flight.isActive` vs `flight?.isActive == true`） |
| allowBackup 关闭（两轮都没执行） | **确认，实锤**：`git log --all -S 'android:allowBackup'` → **零提交**（从来没人写过，不是写了被回退）。两轮 manifest 改动只加 WAKE_LOCK 与 `<queries>`。`res/xml/` 只有 `file_paths.xml` → **升 P2**（`filesDir` 下 DataStore + queue_state.json + 31MB 未校验来源的 APK 全进备份集） |
| F2 HSV 滑杆自定（计划标 ✅） | **确认虚报**：`ThemeColorPicker.kt` 全文 120 行**无 Slider/HSV/hex**；`git grep Slider` 空，**全历史从未存在**。`PresetSeeds` 硬编码 10 色。用户可见文案 `SettingsScreen.kt:132-134`「…可改为自定义」是真坑（不只是注释吹牛）→ **P2**。附带证实「自定义色生效时跟随封面变死开关」（`AppState.kt:369` 最前短路，但 `SettingsScreen.kt:123-126` 开关仍可点可存） |
| release.sh 无半提交 | **成立，且比报告更全**：见 §4 |

---

## 4. 报告漏报的真问题（复核过程中新发现）

### 新-1 · APK 更新包无签名校验 + release 用公开 debug keystore（**P1，最高优先级**）

- 全仓 `*.kt` grep `signature|Signature|getSignedApk` → **零命中**。下载完只验 sha256（防裁剪）+ 1MB 下限 + "PK" 魔数，`install()` 不比对 `PackageManager.getPackageArchiveInfo(...).signatures`
- `app/build.gradle.kts:26-31`：release 变体 `applicationIdSuffix = ".debug"` + `signingConfig = signingConfigs.getByName("debug")`
- **后果**：debug keystore 是公开通用密钥 → **任何人可用同一密钥签恶意包做成"更新"覆盖安装**。sha256 防不住（清单与 APK 走同一不受信通道，可同时改）
- 报告把「sha256 只防裁剪，签名校验是最后防线」写对了方向，但**没意识到最后防线并不存在**

### 新-2 · 全库删空后缓存不重写 → 幽灵曲库（原 P3 → **P2**）

- `GlassApp.kt:172-176`：`if (songs.isNotEmpty()) writeCache else if (!state.librarySettled) markLibrarySettled()` —— 扫描为空时**根本不调 `writeCache`**
- 雪崩链：旧 `library_cache.json` 保留已删曲目 → 冷启动 `loadCache` 照读 → `librarySettled=true` 使 `!librarySettled` 恒 false → **永不纠正**；同时 `sweepCovers` 以空保留集清空 covers → 幽灵条目封面全空、点击报"音频文件已丢失"
- **每次冷启动复现，只能清数据修复**。同型触发：全部曲目 <30s 时 `outputFiltered` 返回空也走此路
- 根因：`else` 分支无法区分「扫描失败」与「扫到 0 首」

### 新-3 · `CMD_INSERT_SHUFFLE_FIX` 失败静默 → shuffle 下丢歌（原 P3 → **P2**）

- 服务端：`MusicPlaybackService.kt:122-136` 对 `specs == null` 什么都不做但**仍返回 `RESULT_SUCCESS`**；`insertWithShuffleFix` 三处早退（`:177/178/182`）无错误回传
- 客户端：`MediaControllerEngine.kt:257-275` 用 `runCatching` 包 `sendCustomCommand` 且**不检查返回的 SessionResult**（对比 `setEqPreset:178-190` 有监听检查）→ 命令被拒时**既不插入也不报错**
- **后果**：shuffle 开启时「下一首播放/最后播放」新增项可能**静默消失**

### 新-4 · LibraryScreen 性能整治只覆盖 2/6

- 只 `remember` 了 `sortedSongs` + `albumCountByArtist`；tab 1-4 的 `albumGroups()/artistGroups()/folderGroups()/genreGroups()` 仍是 body 内裸调（`:170/193/210/227`）
- 报告说「A4 只治了曲库页」，实际要收紧为「A4 只治了曲库页的 2 个派生」

### 新-5 · 冷启动 lrc/封面双趟重复解码（P3）

- 冷启动 `loadCache` 对全库 `readLrc`（`:620`）+ 解码全部封面（`:616-618`）；随后后台扫描 `readLrc`（`:168`）+ 再解码（`:164-166`）→ **每首的 .lrc 与封面各做 2 次**
- `folderCoverCache`（`:266`）只缓存文件夹兜底图，**不覆盖歌词与单曲封面**

---

## 5. 报告误报 / 不成立（4 项）

| 项 | 撤销理由 |
|---|---|
| 启动竞态（簇4 第2项） | 同协程顺序执行，非并发；`loadLibrary` 首分支就是 pending 消费；已有三重兜底 |
| restore 后歌单 meta 虚高（P3 #13） | restore 确实写 `trackIds.size` 未过滤，但**展示层 `resolvePlaylist` 的 meta = `merged.tracks.size` 已 `mapNotNull{songById}` 过滤** → 用户不可见，仅内部字段短暂冗余 |
| M3U 导出演示曲垃圾路径（P3 #17） | **演示曲库已移除**（`DemoData.songs` 恒 `emptyList()`），真曲 id 必为 `f<绝对路径>`（`MediaLibrary.kt:211`）→ 前提失效 |
| 同目录多首 .lrc 重复 IO（P3 #8） | 两候选都是本曲专属路径，`firstNotNullOfOrNull` 短路，同目录不同曲无重复读。真问题见 新-5 |

**另有 3 处描述需修正**（不影响成立性）：
- 「每帧 5 次 Brush 分配」→ 数量对（1 线性 + 3 blob + 1 暗角），但**只在重绘帧分配**，成本可忽略；真问题是无生命周期门控（core 目录 grep `LocalLifecycleOwner/ON_STOP/isVisible` **零命中**）
- 「换色迟滞约 800ms」→ 实际 **1200ms**（`animatePalette` 被调 3 次 × 400ms 串联），增强档 ×1.4 → **1680ms**
- 「琥珀对比度约 2:1」→ 实算 **2.15:1**；且 10 个预设里 **8 个不达 4.5:1**（草绿 2.93 / 青绿 3.39 / 蓝 3.59 / 靛紫 3.76），仅棕 4.62 / 石蓝 5.48 达标 → 属**设计系统级缺陷**

---

## 6. 修复优先级建议

### 批次 A · 发布阻塞（P1，建议立即修）
1. **#17 / #18 位图内存** — 两处 `decodeFile` 补 `inSampleSize`（≤256px）+ 目录级 Bitmap 共享 + LruCache 或按需加载。这是唯一会直接 OOM 打穿用户的点
2. **#6 / #7 封面失效联动** — `persistCover`/`persistFolderCover` 改"源指纹变化才重写"；reuse 分支加 `folderCoverFileFor` 探测 + 源图消失时删旧 PNG 并回退；清缓存走"PNG 缺失即 miss → 重新提取"
3. **#12 飞行协程** — entry/exit 各持 Job + generation 守卫；替身 alpha 在 `enteringWait` 直接取 1
4. **新-1 更新包签名** — `install()` 前比对 `getPackageArchiveInfo().signatures` 与当前包；release 换正式 keystore（或至少显式记录风险）

### 批次 B · 用户可感（P2）
5. **新-2 幽灵曲库** — `GlassApp.kt:172` 改"扫描成功即写缓存（含空库）"，区分失败与 0 首
6. **新-3 丢歌** — 客户端检查 SessionResult，失败回退 `addMediaItems`
7. **#16 重命名重名** — 子面板复用 `duplicate` 三态，`renamePlaylist` 内查重
8. **#15 断连重建** — `Builder.setListener(onDisconnected → 重建 controller)`，或 `positionSec` 断连时触发重建
9. **#11 模式持久化** — `queue_state.json` 加 `shuffle/repeat/speed/eq` 字段，`onPlaybackResumption` 内应用
10. **#14 tick 派生** — 两屏 `remember(library, query/detail)`；`progressFraction` 读取下移进 Chrome/MiniPlayerBar
11. **#5 换图不覆写** + **#8 冷启动 B3 过滤**
12. **#3 睡眠定时双侧** — 归零时补 `engine.setSleepTimer(0)`；transition 按 reason 分类消费（**只清旗标不 pause**）
13. **allowBackup** — manifest 加 `android:allowBackup="false"` + `dataExtractionRules`
14. **F2 文案** — 要么补 HSV 滑杆，要么把「可改为自定义」改成「可选 10 种预设色」（当前承诺与实现不符）

### 批次 C · 打磨（P3）
15. 动态歌单守卫收敛为 `DemoCatalog.dynamicIds` 集合判定（一处根除）
16. 极光背景加生命周期门控；`ThemeColorPicker` 的 `AuroraMiniPreview` 改用 effectivePalette
17. `release.sh`：push 失败留本地 commit（重跑跳号）+ EXIT trap 无条件 `git checkout` 砸手工改动 + 自检在 push 后误报失败 → 三条都需改；`apk/app-debug.apk`（36.9 MiB，冻结于 v1.0.2）`git rm`
18. 6 个 tab 空态占位、搜索历史空态、播放页 a11y、IME 动作、`modal.show` 原位替换退场
19. `.lrc` 路径 `substringBeforeLast('.')` 作用于整条路径的 bug（`MediaLibrary.kt:497`）；四处读入加大小上限；扫描加 `ensureActive`
20. 对比度：8/10 预设不达 WCAG AA，需按 seed 亮度动态选前景色

---

## 7. 各区健康度 · 复核后修正

| 报告结论 | 复核修正 |
|---|---|
| 状态层最扎实 | **成立**。快照 v8 迁移、hydrated 门控、自愈全在位；连报告怀疑的"启动竞态"都被并发模型排除 |
| 更新链与权限面最硬 | **不成立**。更新链有 **P1（签名校验缺失）**；allowBackup 从未修（P2） |
| 播放链历史坑全清，薄弱在睡眠定时与断连 | **成立**，且断连比描述的更差（静默死亡） |
| 玻璃管线结构性债务已清 | **成立**，haze 两条铁律全工程零违规（`LocalHazeState` 兄弟关系无嵌套） |
| 扫描侧封面生命周期是最弱环节 | **成立且被低估**——不是"最弱"，是**唯一有 P1 的功能面**（4 项 P1 全在此） |
| feature 层接线完整零回归 | **基本成立**，但性能整治覆盖面比描述的更窄（2/6 tab） |

---

*复核过程：8 个并行核实员按簇分工，全部只读（read/grep/git 只读/javap），`git status --porcelain` 为空，未创建或修改任何源码文件。*
