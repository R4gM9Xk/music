# 显示逻辑与 Bug 全面排查报告

> 基线：HEAD `852d9b7`（release v1.0.3）+ 工作区未提交改动（`git status` 显示 30+ 文件已改）
> 范围：`project/` 全部 Kotlin 源码（35 文件 / 约 9.7k 行）+ Manifest / 资源 / 构建脚本
> 方式：**全程只读**。10+ 个并行子智能体按域审查 + Lead 逐条 `file:line` 复核取证，每条结论都对应真实代码行
> 日期：2026-10-31

---

## 0. 结论摘要

| 级别 | 数量 | 说明 |
|---|---|---|
| **P1** | 5 | ①断连后播放键失效；②队列编辑**销毁播放现场**；③签名 key 换机断更；④**媒体通知点击无法回到 App**；⑤dispose 后 `scheduleSave` 崩溃 |
| **P2** | 32 | 用户可感知的显示/逻辑错误（含幽灵曲库回归、4 处布局错位、5 条飞行动画缺陷、1 条不可撤销数据错位、1 条设置项只在 1/6 界面生效、3 条扫描/歌词/导入链缺陷、1 条 docs 复核误判需重开、2 条启动/导航竞态、4 条设置/更新/定时器缺陷、3 条播放/搜索页交互缺陷） |
| **P3** | 80 | 打磨项与边界不一致 |
| **已证伪** | 7 | 子智能体/历史报告中的误报，逐条给出反证 |

**最值得优先处理的三件事**：
1. **P1-4 媒体通知点击无法回到 App** —— 核心体验缺陷，一行 `setSessionActivity` 即可修，用户会直接认为"点不动"
2. **P1-2 队列编辑销毁播放现场** —— 静默、不可恢复的用户数据销毁，触发门槛低，一行守卫即可堵住
3. **P1-3 签名 key** —— 换构建机即老用户永久断更，`release.sh` 加一道自检成本极低

> 另需注意：本轮做了**产物级实测**，确认 `update.json` 的 sha256 与仓库内 `apk/app-release.apk` **逐字节一致**（清单自洽 ✅），`minSdk=35/targetSdk=36/compileSdk=36` 与产物一致，`FileProvider` authority 与 `file_paths.xml` **完全匹配**（H9 证伪）。

---

## 1. P1 — 功能完全失效

### P1-1 · 播放服务断连后，播放键彻底失效且无任何恢复路径

| 项 | 内容 |
|---|---|
| 位置 | `app/.../ui/GlassApp.kt:385-405`（Builder 无 listener）、`app/.../player/MediaControllerEngine.kt:133-143`（checkConnected）、`MediaControllerEngine.kt:146-156`（loadQueue 无守卫）、`core/ui/.../state/AppState.kt:446-451`（onError 清 engineLoadedUri） |
| 现象 | 播放服务被系统杀 / crash 后，界面报一次「播放服务连接已断开」，随后**点播放、切歌、拖进度全部无反应**，也不再有任何提示。只能杀进程重启 App。 |

**成因链**（四步闭环，每一步都"合理"，合起来是死锁）：

1. `GlassApp.kt:386-389` 用 `MediaController.Builder(...).buildAsync()` 建控制器，**没有 `.setListener(onDisconnected = ...)`**（全仓 grep `onDisconnected` 零命中）。断连事件无人接收，也就没有重建路径。
2. `MediaControllerEngine.kt:133-143` 的 `checkConnected()` 只在 `positionSec()`（tick 每 100ms 调）里被触发；断连时上报一次错误并置 `reportedDisconnected = true`。
3. `AppState.kt:446-451` 的 `onError` 回调把 `playing = false`、`engineLoadedUri = null`。
4. `AppState.tick()` 首行 `if (!playing) return`（`AppState.kt:859`）—— **心跳停了**。第 2 步的检测入口随之消失。
5. 用户点播放键 → `togglePlayPause()`（`AppState.kt:925`）→ 因 `engineLoadedUri(null) != uri` 走 `startCurrent()` → `loadQueueOnEngine()` → `MediaControllerEngine.loadQueue()`。
6. **`loadQueue()` 是全类里唯一没有 `checkConnected()` 守卫的方法**（对比 `play/pause/seekTo/skipToNext/setPlaybackSpeed` 都有）。断连的 controller 上 `setMediaItems` 静默失败，`playing` 却被 `startCurrent()` 置回 `true`（`AppState.kt:1079`）——UI 显示"正在播放"，实际没有任何声音，且**不会再有错误提示**。

**用户可见后果**：假死。UI 状态与真实播放完全脱节，任何操作都不生效，也没有线索告诉用户发生了什么。

**最小修法**（三步，可分别落地）：
1. `GlassApp.kt` 的 Builder 加 `.setListener(object : MediaController.Listener { override fun onDisconnected(controller) { /* 重建 controller 并重新 attachPlayer */ } })`。
2. `MediaControllerEngine.loadQueue()` 首行加 `if (!checkConnected()) return`（与同类方法对齐）。
3. `AppState.onError` 里不要清 `engineLoadedUri`（或改为置一个 `engineBroken = true` 标志），让 tick 继续跑、让用户能再次看到错误；否则一次瞬时错误就永久掐断心跳。

---

### P1-2 · 服务断连（暂停态）后任何队列编辑把 UI 队列清空并**销毁持久化播放现场**

| 项 | 内容 |
|---|---|
| 位置 | `MediaControllerEngine.kt:285-286`（`mediaIds()` 断连返回空）；`AppState.kt:1185-1194`（`alignQueueWithEngine` 守卫失效）；`AppState.kt:520-541`（`scheduleSave` 写快照） |

**证据链**（三步全部成立）：

```kotlin
// 1. 断连时 mediaItemCount == 0 → 返回空列表，无任何错误信号
MediaControllerEngine.kt:285
override fun mediaIds(): List<String> =
    (0 until controller.mediaItemCount).map { controller.getMediaItemAt(it).mediaId }

// 2. 守卫是 aligned.size != ids.size —— 0 == 0 时放行
AppState.kt:1185
private fun alignQueueWithEngine(): Boolean {
    val eng = engine ?: return false
    val ids = eng.mediaIds()
    if (ids == queue.map { it.id }) return true
    val aligned = ids.mapNotNull { songById(it) }
    if (aligned.size != ids.size) return false   // ← 防的是「解析失败」，防不了「ids 本身为空」
    queue = aligned                               // ← queue = emptyList()
    queueIndex = ids.indexOf(eng.currentMediaId()).coerceAtLeast(0)
    return true
}
```

**触发前提**（很常见，不是极端场景）：
1. 用户在**暂停态**（`engineLoadedUri != null`、`playing = false`）。
2. 系统在后台杀掉播放服务（暂停态的前台服务最容易被回收）。
3. `checkConnected()` 只挂在 `play/pause/seek/skip/setPlaybackSpeed/positionSec` 上，而 `tick()` 只在 `playing == true` 时轮询 `positionSec()`（`AppState.kt:858-859` 首行 `if (!playing) return`）→ **暂停态下永远检测不到断连**，没有错误横幅，`engineLoadedUri` 不清，`engineDriving()` 仍返回 `true`。

**致命链**：用户此时做**任何**队列编辑（拖拽重排 / 删除某项 / 下一首播放 / 最后播放 / 清空接下来）→ `insertQueueItems`/`removeQueueItem`/`moveQueueItem` 首行都调 `engineDriving() && alignQueueWithEngine()` → `queue = emptyList()` → `scheduleSave()` → `snapshot()` 里 `queueIds = queue.map { it.id }` = `[]`、`currentSongId = currentSong.id` = `"placeholder"`（`AppState.kt:371` 的占位曲兜底）→ **写入 DataStore**。

**用户可见后果**：
- 队列面板变空、迷你条变「暂无歌曲」；
- **持久化播放现场被覆盖销毁** —— 重启 App 也回不来（`applyPendingPlayback` 拿到空 `ids` → `songs.isEmpty()` → `pendingPlayback = null`，永久丢弃）；
- 此时点播放键：`AppState.kt:940-943` 走 `eng.play()`（断连 no-op）后本地 `playing = true` → **UI 假播放、进度冻结**，且不再有错误提示。

**严重度定为 P1**：这是**静默的、不可恢复的用户数据销毁**（播放现场 + 队列），且触发门槛低（暂停 + 后台被杀 + 随手拖一下队列）。

**最小修法**（两处，独立生效）：
1. `alignQueueWithEngine` 加空集守卫：`if (ids.isEmpty() && queue.isNotEmpty()) return false` —— 一行，立刻堵住主路径；
2. 更彻底：`mediaIds()` 在 `!controller.isConnected` 时返回 `null`（签名改 `List<String>?`），让所有调用方显式处理"读不到"与"确实为空"的区别；
3. `AppState.kt:940-943` 的 play 分支：`checkConnected` 失败时不要把 `playing` 置 `true`。

---

### P1-3 · 分发版用「本机 debug keystore」签名 —— 换构建机即永久断更，且签名可信根是口令为 `android` 的调试密钥

| 项 | 内容 |
|---|---|
| 位置 | `app/build.gradle.kts:26-31`（release 用 `signingConfigs.getByName("debug")`）；`UpdateManager.kt:189-200`（fail-closed 签名比对） |

**实测证据**（本轮做了产物级验证，非静态推演）：

| 验证项 | 结果 |
|---|---|
| `sha256sum apk/app-release.apk` | `86fd9095…41afc9` —— 与 `update.json:8` **逐字节一致**（清单自洽 ✅） |
| `apksigner verify --print-certs` | `CN=Android Debug`，SHA-256 `c0ef91…` —— 与本机 `~/.android/debug.keystore`（口令 `android`）**指纹完全相同** |
| `git ls-files \| grep -iE '\.(jks\|keystore)$'` | **空** —— keystore 不在仓库，`release.sh` 也不备份 |

**成因链**：
1. release 变体的签名 key = 开发者本机自动生成的 debug keystore，**只存在于某一台机器的用户目录**。
2. `update.json` 的 sha256 与 APK 走同一条不受信通道（gh-proxy / raw / jsDelivr）→ sha256 只能防传输损坏，防不住「同时改清单 + APK」。
3. 真正拦伪造包的是签名比对（`UpdateManager.kt:199`），而其可信根是一把口令为 `android`、可被任意复制/拷走的调试密钥。
4. **反向后果更常见**：任何人在另一台机器 `./release.sh`，AGP 生成新 debug key → 新 APK 签名 ≠ 老用户已装包签名 → `verifyApkSignature()` 返回 false → 删包 + `Failed` → **老用户永久收不到更新**（Android 安装器也会以 `INSTALL_FAILED_UPDATE_INCOMPATIBLE` 拒绝）。

**用户可见后果**：换机发版后，老用户点「检查更新 → 立即更新」→ 下载完成 → 面板自动收起 → 设置行显示「下载失败（安装包签名校验失败，已拒绝安装），点击重试」→ 点重试**无限重复**（见 P2-25）。唯一出路是卸载重装，而 `allowBackup="false"` + 备份需手动导出 → 本地数据（收藏/歌单/播放次数）全部丢失。

**安全侧**：拿到该 keystore 的人可发布「同包名同签名」的包，应用内更新链会当合法更新接受。

> 补充纠正：`UpdateManager.kt:183-185` 的注释称 debug keystore 是「公开的通用密钥」——这个描述不准确（AGP 生成的是**每机随机** key），但它反过来暴露了真实风险：**这把 key 就是分发签名 key**。

**最小修法**：
1. 生成专用发布 keystore（`release.jks`），release 用它；debug keystore 与 `release.jks` 都纳入加密备份（**不进 git**）。
2. `release.sh` 增加**签名漂移自检**：新 APK 证书 SHA-256 与仓库内上一版 `apk/app-release.apk` 比对，不一致直接 `exit 1`（`apksigner verify --print-certs` 两行 diff 即可）。成本极低，能挡住最致命的失误。

---

### P1-4 · MediaSession 从未设置 `sessionActivity` —— 通知栏/锁屏/流体云点击**无法回到 App**

| 项 | 内容 |
|---|---|
| 位置 | `app/.../player/MusicPlaybackService.kt:71`（`MediaSession.Builder(this, player).setCallback(...)` —— 整段 71-166 行**只**调了 `.setCallback`）；全仓 `grep setSessionActivity` → **0 命中** |

**成因链**（media3 行为已用字节码核对）：
1. `MediaSession.BuilderBase` 里 `sessionActivity` **默认 `null`**（`MediaSessionImpl` 构造函数把该参数直接 `putfield`，Builder 未做任何默认赋值）。
2. `DefaultMediaNotificationProvider` **无条件**执行 `builder.setContentIntent(session.getSessionActivity())` —— `setContentIntent(null)` 合法 → **通知能显示但没有 contentIntent**。
3. `MediaSessionService` / `MediaNotificationManager` 均不会代填（已反编译核对，无 `setSessionActivity` 调用）。

**用户可见后果**：播放中下拉通知栏点媒体卡片、锁屏点封面、ColorOS 流体云点胶囊 —— **都不会打开 App**，只有播放/暂停/上一首/下一首按钮可用。用户被迫从桌面图标进入。

**复现**：播放任意歌曲 → 退到桌面 → 下拉通知栏 → 点通知卡片**主体**（非按钮区）→ 无任何反应（预期进入播放页）。

> 这是**核心体验缺陷**：一个音乐 App 的媒体通知不能点回应用，用户会认为"点不动"。

**最小修法**（在 `.setCallback(...)` 前加一行）：
```kotlin
.setSessionActivity(
    android.app.PendingIntent.getActivity(
        this, 0,
        Intent(this, MainActivity::class.java)
            .setAction(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_APP_MUSIC)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
    ),
)
```

---

### P1-5 · `queueStateExecutor` 在 `onDispose` 关闭，但 `queueStateWriter` 仍存活 → dispose 后任何 `scheduleSave()` 抛 `RejectedExecutionException` 崩溃

| 项 | 内容 |
|---|---|
| 位置 | `GlassApp.kt:121-127`（`remember { ... }` + `onDispose { queueStateExecutor.shutdown() }`）；`:233-237`（`state.queueStateWriter = { ... queueStateExecutor.execute { ... } }`） |

**成因链**：
1. `queueStateExecutor` 在 `DisposableEffect` 的 `onDispose` 里 `shutdown()`。
2. 但 `state.queueStateWriter` 这个 lambda **仍指向会 `execute` 该 executor 的实现**（`GlassApp.kt:234`），而 `AppState` 没有在 dispose 时把它置 `null`。
3. dispose 之后任何一次 `scheduleSave()`（`AppState.kt:535-536` 的 `shouldWriteQueueState` 通过即调 `queueStateWriter?.invoke`）→ `execute` 在已 shutdown 的 executor 上 → **`RejectedExecutionException`**。
4. 该异常在 UI 线程未被捕获 → **崩溃**。

**用户可见后果**：Activity 重建/退出过程中若恰好有一次 `scheduleSave`（tick 每 20s 一次、或用户操作触发），可能直接崩。触发窗口窄但真实存在。

**最小修法**（任一）：
1. `execute` 外层包 `runCatching`（最小改动）；
2. `DisposableEffect` 的 `onDispose` 里先 `state.queueStateWriter = null` 再 `shutdown()`（语义更正确）；
3. executor 生命周期改为跟随 `AppState` 而非组合。

---

## 2. P2 — 用户可感知的显示/逻辑错误

### P2-1 · 扫描结果为空时不清空内存曲库（幽灵曲库回归）⭐ 优先

| 项 | 内容 |
|---|---|
| 位置 | `core/ui/.../state/AppState.kt:784-785`；配合 `app/.../ui/GlassApp.kt:200-214` |
| 证据 | `fun loadLibrary(songs: List<DemoSong>) { if (songs.isEmpty()) return` —— 空列表直接早退，`library` 保持旧值 |

**成因链**：
1. 用户已加载过曲库（`librarySettled == true`），随后在文件管理器里**删掉全部音乐文件**。
2. 设置页点「扫描音乐」→ `GlassApp.loadRealLibrary()` → `MediaLibrary.load()` 返回**空列表**（扫描成功，只是没有歌）。
3. `GlassApp.kt:201` 调 `state.loadLibrary(emptyList())` → **早退，`library` 不变** → 曲库页继续显示所有已删除的歌。
4. `GlassApp.kt:211` 却把缓存写成了空数组（`result.isSuccess` 分支）；`GlassApp.kt:212` 的 `if (songs.isEmpty() && !state.librarySettled)` 因 `librarySettled` 已是 `true` 而**不触发纠正**。
5. 结果：UI 与磁盘缓存分叉。点任何一首歌 → `MediaStore` 里已无记录 / 文件已删 → 播放报「音频文件已丢失」。**重启 App 后才恢复正常**（冷启动读空缓存）。

> 这正是 `docs/2026-10-25-fix-report.md` P2-8「幽灵曲库」的**修复缺口**：上一轮只修了「扫描 0 首时也要写缓存」，没修「扫描 0 首时也要清空内存 `library`」。

**最小修法**：把 `if (songs.isEmpty()) return` 改为允许空库进入（保留 `loadLibrary` 内部的 `rescan` 分支逻辑，空库时把 `queue`/`library` 一起清空并 `markLibrarySettled()`）。若担心与「扫描失败」混淆，可在调用点用 `result.isSuccess` 显式区分：成功且空 → `state.loadLibrary(emptyList())` 必须生效。

---

### P2-2 · 两列卡片在「奇数个」时最后一张撑满整行（4 处同源）

**共性写法**：`list.chunked(2).forEach { pair -> Row { pair.forEach { Card(Modifier.weight(1f)) } } }` —— 当 `pair.size == 1` 时，唯一的卡片吃掉整行宽度，与上一行的半宽卡**宽度不一致**，视觉上像"最后一张卡突然变大"。

| # | 位置 | 场景 |
|---|---|---|
| 1 | `feature/library/.../LibraryScreen.kt:187-209` | 专辑 tab（专辑数为奇数时最后一张） |
| 2 | `feature/library/.../LibraryScreen.kt:265-287` | 歌单 tab（内建 4 + 用户歌单，合计奇数时） |
| 3 | `feature/playlist/.../PlaylistScreen.kt:134-156` | 歌单库封面墙（用户建 1、3、5… 个歌单时必现） |
| 4 | `feature/search/.../SearchScreen.kt:243-257` | 搜索结果的专辑两列（`AlbumRow`） |

**反证作者本来知道要补位**：`core/ui/.../ThemeColorPicker.kt:86` 有正确写法 `repeat(5 - row.size) { Spacer(Modifier.weight(1f)) }` —— 同一个代码库里已有对照实现。

**最小修法**：4 处统一在 `pair.forEach` 之后补 `if (pair.size == 1) Spacer(Modifier.weight(1f))`。或抽一个 `TwoColumnGrid` 组件单点实现。

---

### P2-3 · 内建歌单「能加歌，但删不掉、也不能重排」

| 项 | 内容 |
|---|---|
| 位置 | 加歌入口 `feature/playlist/.../PlaylistDetailScreen.kt:301-324`（守卫只排除 `dynamicIds`）；解析 `AppState.kt:1560-1567`（内建歌单也合并 `addedTracks`）；移除 `AppState.kt:1354-1355`（`if (!playlistId.startsWith("user-")) return false`）；重排 `AppState.kt:1366-1367`（同款 `user-` 守卫）；菜单 `ActionsSheet.kt:67-68`（`canRemove` 要求 `user-`） |

**成因链**：
1. 详情页顶栏「＋添加歌曲」的条件是 `kind == Playlist && id !in dynamicIds` —— **内建歌单（如「深夜巡航」）满足条件**，入口可见。
2. `addToPlaylist()`（`AppState.kt:1263`）不校验 id 前缀，直接 `addedTracks.getOrPut(playlistId)` 写入。
3. `resolvePlaylist()`（`AppState.kt:1560-1567`）会把 `addedTracks[id]` 合并进内建歌单的曲目列表 —— 加歌**显示上确实生效**（计数也对）。
4. 但「从歌单移除」三个入口全部被 `startsWith("user-")` 拦住：
   - `removeFromPlaylist()` 直接 `return false`
   - `canReorderPlaylist()` 返回 `false`（不能拖拽重排）
   - `SongActionsSheet` 的 `canRemove` 为 `false`（菜单项不渲染）
5. 用户加错了歌 → **没有任何删除途径**，只能删掉整个内建歌单（隐藏）再恢复。

**最小修法**：二选一 ——
- **A（推荐）**：把「＋添加歌曲」入口也限制到 `user-` 前缀（与 `dynamicIds` 判定并列），内建歌单保持只读；
- **B**：放开 `removeFromPlaylist` / `canReorderPlaylist` / `canRemove` 的 `user-` 限制，让内建歌单也支持完整编辑。

---

### P2-4 · 空队列时「播放队列」面板只剩一个孤立的「正在播放」标签

| 项 | 内容 |
|---|---|
| 位置 | `core/ui/.../components/Sheet.kt:444-450` |

```kotlin
SectionLabel("正在播放")                                    // ← 444 行，无条件渲染
SongRow(
    song = queue.getOrNull(safeIndex) ?: return@GlassSheet,  // ← 446 行，空队列直接 return
    ...
)
```

**成因**：`safeIndex` 在空队列时被钳为 `0`（`Sheet.kt:419` 的越界防护，防 `coerceIn(0,-1)` 崩溃），但 `queue.getOrNull(0)` 仍是 `null` → 提前 `return@GlassSheet`。此时 `SectionLabel("正在播放")` **已经画出来了**，下面的行、"接下来"段头、清空按钮全部被跳过。

**用户可见后果**：无曲库 / 权限被拒时点迷你条进播放页 → 点队列按钮 → 面板标题「播放队列 · 0 首」+ 一个孤零零的「正在播放」小标题 + 大片空白。

**最小修法**：把 `SectionLabel("正在播放")` 移到 `queue.getOrNull(safeIndex) != null` 判断之内；或空队列时整体渲染一句「队列是空的」空态。

---

### P2-5 · 主题色面板：从未自定义时 10 个色块全部不高亮

| 项 | 内容 |
|---|---|
| 位置 | `feature/settings/.../SettingsScreen.kt:155-159`；`core/ui/.../components/ThemeColorPicker.kt:54` |

```kotlin
// SettingsScreen.kt:157
ThemeColorSwatches(
    seeds = AuroraPalette.PresetSeeds,
    selected = state.customSeedArgb?.let { Color(it) },   // ← 未自定义时 = null
    ...
)
// ThemeColorPicker.kt:54
val on = selected != null && color.toArgbValue() == selected.toArgbValue()  // ← 恒 false
```

**成因**：`customSeedArgb == null` 表示「走跟随封面 / 品牌靛紫二态」，但面板把 `null` 直接当作"没有选中项"传给色板。色板逻辑本身没错（`null` = 无选中），是**调用方没有把"当前生效色"折算出来**。

**用户可见后果**：打开「主题色」面板，10 个色点**一个都没有选中描边**，用户看不出当前主题色是什么，也无法判断自己点哪个才会变成什么。而副标题明确写着「品牌靛紫」或「跟随封面」，与面板内的"全无选中"自相矛盾。

**最小修法**：调用方传**当前生效的 seed**，而不是只传"是否自定义"：
```kotlin
selected = Color(state.palette.seed)   // palette 已含 customSeedArgb / followCover / Purple 三态
```
（`AppState.palette` 在 `AppState.kt:394-403` 已经是三态收敛后的结果。）

---

### P2-6 · 设过自定义色后，「跟随封面取色」开关变成装饰品

| 项 | 内容 |
|---|---|
| 位置 | 开关 `SettingsScreen.kt:127-130`；取色优先级 `AppState.kt:394-403` |

```kotlin
val palette: AuroraPalette
    get() {
        customSeedArgb?.let { return AuroraPalette.fromSeed(Color(it)) }  // ← 最高优先级，短路返回
        return if (followCover) currentSong.palette ?: currentSong.art.palette else GlassPalettes.Purple
    }
```

**成因**：一旦设过自定义色，`customSeedArgb != null` 永远先命中并 `return`，`followCover` **再也读不到**。

**用户可见后果**：
- 用户先选了自定义色，之后想回到「跟随封面」→ 拨动开关，**界面毫无变化**，开关视觉上是"开"，实际取色仍是自定义色。
- 副标题此时写的是「自定义颜色（点击更换）」，也不提示"要去面板里点恢复默认才能回到跟随封面"。

**最小修法**（任一）：
- 拨动 `followCover` 开关时顺带 `setCustomSeed(null)`（语义："跟随封面"就是取消自定义）；
- 或把 `palette` 的优先级改为 `followCover` 优先，自定义色只在 `followCover == false` 时生效。

---

### P2-7 · 曲库页 tab 选择与滚动位置在切屏后丢失

| 项 | 内容 |
|---|---|
| 位置 | `feature/library/.../LibraryScreen.kt:77`（`var tab by remember { mutableIntStateOf(0) }`）；`LibraryScreen.kt:121`（`LazyColumn` 用默认 `rememberLazyListState()`） |

**成因**：`GlassApp.kt:601-728` 用 `AnimatedContent(targetState = state.screen)` 切换屏。切到播放页 / 详情页 / 搜索页时，`LibraryScreen` 的整个子树被移出组合，所有 `remember` 状态销毁。`AnimatedContent` 只保留动画中的前后两个 target。

**用户可见后果**：用户在「专辑」tab 点一首歌 → 进播放页 → 按返回 → **回到「歌曲」tab 且滚动到顶部**。每次进详情/播放页回来都要重新找位置。这对六 tab 的曲库页体感很差。

**最小修法**：`tab` 提到 `AppState`（或至少 `rememberSaveable`）；`LazyColumn` 用 `rememberLazyListState()` 提到屏外或 `rememberSaveable` 承载 `firstVisibleItemIndex`。

---

### P2-8 · 搜索历史为空时仍渲染「最近搜索」标题

| 项 | 内容 |
|---|---|
| 位置 | `feature/search/.../SearchScreen.kt:118-130` |

```kotlin
if (query.isBlank()) {
    item(key = "history-header") { SectionHeader("最近搜索") }   // ← 无条件
    item(key = "history-chips") {
        FlowRow { state.searchHistory.take(4).forEach { ... } }  // ← 空历史时 FlowRow 里什么都没有
    }
}
```

**用户可见后果**：首次安装 / 清过历史后打开搜索页 → 顶部一个「最近搜索」标题，下面一片空白。用户会以为加载失败。

**次要问题（同段代码）**：`addSearchHistory` 最多存 **8** 条（`AppState.kt:1340`），但这里只显示 **4** 条（`take(4)`），且**没有"查看全部"入口** —— 后 4 条历史用户永远看不到，也删不掉。

**最小修法**：`if (query.isBlank() && state.searchHistory.isNotEmpty())`；`take(4)` 改为展示全部或加"更多"。

---

### P2-9 · M3U 导入可造出与内建歌单同名的用户歌单

| 项 | 内容 |
|---|---|
| 位置 | `app/.../ui/GlassApp.kt:476-480`（导入路径直接 `createPlaylist(name)`）；`AppState.kt:1286-1291`（`createPlaylist` 只查 `userPlaylists` 同名） |

```kotlin
// AppState.kt:1290 —— 只查用户歌单，不查 DemoCatalog.playlists（内建歌单）
userPlaylists.firstOrNull { it.title.trim().equals(finalTitle, ignoreCase = true) }?.let { return it }
```

**成因**：`playlistTitleExists()`（`AppState.kt:1306-1311`）**两个都查**（用户歌单 + 内建歌单），所以 UI 层的新建/重命名入口能拦住重名。但 M3U 导入走的是 `createPlaylist()` 内部判定，**只查用户歌单** → M3U 文件里 `#PLAYLIST:深夜巡航` 就能造出第二个"深夜巡航"。

**用户可见后果**：歌单库出现两个同名卡片（一个内建、一个用户），点进去内容不同，用户无法分辨。

**最小修法**：`createPlaylist()` 内部改调 `playlistTitleExists()`（与 UI 同口径），命中内建歌单名时追加后缀（如「深夜巡航 (2)」）而非静默创建。

---

### P2-10 · 「最常播放」同次数曲目的排序不稳定

| 项 | 内容 |
|---|---|
| 位置 | `AppState.kt:1554-1557` |

```kotlin
val tracks = playCounts.entries
    .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
    .take(50)
```

**成因**：注释（`AppState.kt:1552`）声称「次数相同按曲名稳定排序（避免每次进页面抖动）」，但实际次级键是 **`it.key`（曲目 id）**，而 id 形如 `f/storage/emulated/0/Music/xxx.flac`（绝对路径）。排序结果确实是**确定的**（不是抖动），但用户看到的顺序是"按文件路径字典序"——与"按曲名"的意图不符，也与曲库页的 `Collator` 中文拼音序不一致。

**用户可见后果**：两首播放次数相同的歌，在「最常播放」里的先后由文件路径决定（例如 `/Music/a/` 永远排在 `/Music/b/` 前），与标题拼音序无关；用户跨页面看到的排序规则不统一。

**最小修法**：改用标题做次级键（`playCounts` 最多 50 条，`songById` 已是 O(1)）：
```kotlin
.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }
    .thenBy(collator) { songById(it.key)?.title ?: it.key })
```

---

### P2-11 · 迷你条在空曲库时仍渲染占位曲与可点播放键

| 项 | 内容 |
|---|---|
| 位置 | `core/ui/.../components/Chrome.kt:164-236`（`MiniPlayerBar` 无 `hasSongs` 门控）；`GlassApp.kt:734-766`（Chrome 只在 `librarySettled` 后渲染） |

**成因**：`GlassApp.kt:734` 的门控条件是 `state.screen in TAB_SCREENS && state.librarySettled && !modalState.isOpen` —— **不含 `hasSongs`**。权限被拒 / 曲库为空时 `librarySettled` 也是 `true`，迷你条照常渲染 `state.currentSong`（= `DemoData.placeholder`：标题「暂无歌曲」、艺术家「本地曲库」）。

**用户可见后果**：无曲库设备上，底部悬浮一条「暂无歌曲 / 本地曲库」的迷你条 + 播放键 + 下一首键。点播放键：`togglePlayPause()` 有 `hasSongs` 守卫 → **无任何反应**；点下一首：`next()` 同样 `return`。三个按钮全是死的，但视觉上是可用的。

**最小修法**：`MiniPlayerBar` 内部按 `song.id == DemoData.placeholder.id` 或调用方传 `hasSongs` 决定是否渲染控制键（或整条替换为「曲库为空 · 去扫描」提示条）。

---

### P2-12 · 飞行开始不复位 `t`，等待期用上一轮的残留进度渲染样式

| 项 | 内容 |
|---|---|
| 位置 | `core/ui/.../components/Flight.kt:146-163`（`beginEntry` 全程不碰 `t`，`t.snapTo(0f)` 在 **213 行**、即 `started = true` 之后）；消费点 `app/.../ui/PlayerFlightOverlay.kt:67`、`:99-107`、`:118`、`:220-224`、`:126/140/170/175/177/191/203`；`feature/player/.../PlayerScreen.kt:200` |

**成因链**：
1. `beginExit` 的「忙时中止」分支（`Flight.kt:234-245`）把 `phase` 置 `Idle`，但**不重置 `t`** —— `t` 冻结在中途值（例如 0.4）。
2. 用户立刻再点迷你条 → `beginEntry` 的 `if (phase != Phase.Idle) return` 检查**通过**（已回 Idle）。
3. `beginEntry` 从 146 行到 163 行**完全没有 `t` 的赋值**；`t.snapTo(0f)` 要等到 `awaitRects` + 几何闸门 + 二次 `awaitRects` 全部通过后（最长 600+600+600ms）才在 213 行执行。
4. 这段等待期里，`PlayerFlightOverlay` 的 `val t = flight.t.value`（`:67`）读到的是**残留的 0.4**，于是：
   - 玻璃面板 `lerp(20.dp, 28.dp, t)` 圆角偏大、`blurRadius = lerp(26.dp, 20.dp, t)` 模糊偏轻（`:126`、`:140`）
   - 歌名字号 `lerp(13.5f, 16f, 0.4f)` ≈ 14.5sp（`:191`）、作者字号同样偏大（`:203`）
   - 播放钮圆底 `if (t > 0.02f)` 成立且 `alpha = t` = 0.4 → **迷你条态出现半透明大圆底**（`:220-224`）
   - 导航替身 `alpha = 1f - t` = 0.6 → **胶囊半透明**（`:118`）
   - `enteringWait` 只冻结了**几何**（`:80` 的 `flightRect` 分支），**没有冻结样式** —— 这是关键缺口
5. 起飞瞬间 `snapTo(0f)` → 上述全部属性**同时跳变**回迷你条形态，视觉上"啪"一下。

**用户可见后果**：进播放页 → 飞行中按返回（中止，`t` 冻结）→ 立刻再点迷你条 → 等待期里迷你条替身是"圆角更大、字号更大、播放钮带半透明大圆底、导航胶囊半透明"的**畸形迷你条**，然后在起飞瞬间全部跳回正常。等待期越长（慢设备/冷启动）越明显。

**最小修法**（两处，互相独立）：
1. `PlayerFlightOverlay.kt:67` 改为 `val t = if (enteringWait) 0f else flight.t.value`（与 `:80` 的几何冻结对齐，样式也冻结）。
2. `beginEntry` 开头补 `t` 复位（需协程，可 `scope.launch { if (gen == generation) t.snapTo(0f) }`），或在中止分支（`:234-245`）里一并复位。

---

### P2-13 · 正常退出不清 `entryShowCover`，下次「直入播放页」先落封面态再滑走

| 项 | 内容 |
|---|---|
| 位置 | `Flight.kt:250-278`（正常退出分支，**无** `entryShowCover = false`）；对照 `:169`、`:192`、`:205`、`:240`、`:298` 五处中止/清理路径**都**清；消费点 `PlayerScreen.kt:122`、`:127-130` |

**成因链**：
1. 从迷你条**飞行进入**播放页 → `beginEntry` 设 `entryShowCover = true`（`Flight.kt:157`）。
2. 用户按返回 → `beginExit` 正常分支走完 → `phase = Idle`、`snapshot = null`、`started = false`，**但 `entryShowCover` 仍为 `true`**。
3. 用户在曲库/详情/搜索**直接点歌**进播放页（`state.screen = GlassScreen.Player` 直入，不经飞行；如 `PlaylistDetailScreen.kt:455`、`SearchScreen.kt:174`）→ `PlayerScreen.kt:122` 的 `showLyrics = state.lyricEnabled && lyrics != null && flight?.entryShowCover != true` 读到 `true` → **首帧强制封面模式**。
4. `PlayerScreen.kt:127-130` 的 `LaunchedEffect(song.id)` 才去清标志：`if (f.entryShowCover && f.songSnapshot?.id != song.id) f.entryShowCover = false`。
5. 若这次点的**正是上一次那首**（`songSnapshot.id == song.id`）→ 条件不成立 → 标志**永不清除** → 播放页**永久卡在封面模式**，用户必须手动点一次封面才能切回歌词。

**用户可见后果**：
- 普通情况：进播放页先看到大封面居中，500ms 后 `modeT` 动画才把封面滑到左上角、歌词淡入 —— 一次多余的"先封面后歌词"抖动。
- 同一首歌反复进出：**歌词永远不显示**，直到用户手点封面。

**最小修法**：`beginExit` 正常分支在 `songSnapshot = song`（`:258`）旁补 `entryShowCover = false`；或退出协程收尾（`:275-277` 那一段）统一清。

---

### P2-14 · 从播放页退出永远回到「曲库」，丢失详情/搜索来源

| 项 | 内容 |
|---|---|
| 位置 | `Flight.kt:244`、`:251`、`:255`、`:269`、`:274`（五处 `navigate(GlassScreen.Library)` **全部硬编码**）；调用点 `GlassApp.kt:539`、`PlayerScreen.kt:208` |

**成因链**：
1. 进入侧是**知道来源**的：`beginEntry(song, navSelected, navigate)` 的 `navSelected` 参数由调用方传入 `state.detailNavSelected`（`PlaylistDetailScreen.kt:265`）或当前 tab（`GlassApp.kt:754-758`）。
2. 退出侧 `beginExit(song, navigate)` **只接一个 `navigate`**，函数体内五处翻页目标全部写死 `GlassScreen.Library`。
3. 于是：从**歌单详情**进播放页 → 收起 → 落到「曲库」tab（导航胶囊选中项从 1 变 0）；从**搜索页**进播放页 → 收起 → 同样落到曲库，搜索词与结果栈丢失。

**用户可见后果**：用户在专辑详情里点一首歌试听，想返回继续看专辑 → 被扔回曲库首页，得重新找那张专辑。搜索场景更糟（搜索词还在但结果列表要从曲库重新进）。

**最小修法**：`PlayerFlight` 加 `entrySource: GlassScreen`（在 `beginEntry` 里记 `state.screen`），`beginExit` 的翻页目标改为 `entrySource`（或 `detailReturn` 语义），`Idle` 时清空。五处硬编码收敛为一处。

---

### P2-15 · 拖拽松手提交后，途经行先跳一行再滑回

| 项 | 内容 |
|---|---|
| 位置 | `core/ui/.../components/ReorderableList.kt:152-164`（行槽 key = `(dataVersion, index)`，让位偏移挂在**槽**上）、`:177-181`（`else -> rowShift`）、`:211-234`（`onDragEnd` 只给被拖行做 settle） |

**成因链**：
1. 让位偏移 `rowShift` 由 `animateFloatAsState(targetValue = shift)` 驱动，`shift` 按 `dragFrom`/`dragTarget` 计算（`:154-159`），状态**挂在行槽 key `(dataVersion, index)` 上**。
2. 松手时 `onDragEnd`（`:211`）先 `cancelDrag()`（`dragFrom = -1`、`dragTarget = -1`）→ 下一次重组 `shift` 重算为 `0` → `rowShift` 用 `tween(150)` 从 `±rowPx` 动画回 0。
3. **同时** `onMove(from, target)` 提交，列表内容重索引：下拖时槽 `i`（`i ∈ (from, target]`）的内容从 `item(i)` 变成 `item(i+1)`。
4. 槽 `i` 的组合 key 未变（`dataVersion` 不变、`index` 不变）→ **复用同一个 `rowShift` 状态**（仍是 `-rowPx`）。
5. 结果：`item(i+1)` 带着 `-rowPx` 的偏移被渲染到 `(i-1)*rowH`（**比它应有的位置高一行**），再在 150ms 内滑回 `i*rowH`。
6. `onDragEnd` 只对**被拖行**设了 `settleRow`/`settleFrom`（`:232-234`），**途经行的偏移没有在提交瞬间归零**。

**用户可见后果**：队列面板长按「接下来」第一首、下拖两行松手 → 途经的两行**先整体上跳 66dp**（第一行会被画到列表外或压到「接下来」段头之上）**再滑回**。上拖方向对称（先下跳一行）。

**最小修法**：让位偏移不要在提交后"动画回 0"，而应**在提交瞬间归零**（内容已经重索引，偏移的语义已失效）。做法：新增 `rowEpoch` 并在 `onMove` 后自增，行槽 key 改为 `key(dataVersion, rowEpoch, index)`（epoch 变化 = 槽重建 = `rowShift` 从 0 起步）；或把 `rowShift` 从 `animateFloatAsState` 改为每行 `Animatable`，在 `onMove` 后 `snapTo(0f)`。

---

### P2-16 · 进入播放页的等待期最长 1.8s，期间整屏点不动

| 项 | 内容 |
|---|---|
| 位置 | `Flight.kt:165`（`awaitRects` 600ms）、`:178`（几何闸门 `withTimeoutOrNull(600)`）、`:201`（再次 `awaitRects` 600ms）；`:123`（真身 `Entering && !started → 0f`）；`GlassApp.kt:576-583`（根层 Initial pass 消费全部指针） |

**成因链**：三段**串行**超时最坏 600+600+600 = 1800ms。这段期间：
- 播放页真实元素 `playerSharedAlpha()` 返回 `0f`（`Flight.kt:123`）→ 全隐
- 覆盖层只画冻结的迷你条替身（`PlayerFlightOverlay.kt:64` 的 `enteringWait`）
- `GlassApp.kt:576` 的根层闸门在 `PointerEventPass.Initial` **消费所有指针事件**

**用户可见后果**：慢设备 / 冷启动首帧重 / 曲库刚装载时点迷你条 → 屏幕只剩一个悬空的迷你条替身，**点击、滑动全部无响应约 1 秒以上**。用户会判定"卡死"并反复戳屏幕。600ms 超时后虽有兜底直切，但兜底本身也要等满超时。

**最小修法**：
1. 三段等待合并为一个总预算（例如总 600ms，第二段用 200ms）；
2. `awaitRects` 首帧即注册到 rect 就起飞，不必双帧判稳（双帧是防"移动中冻结"，可在 `entryShowCover` 已清的场景跳过）；
3. 等待期闸门只覆盖迷你条原区域，而不是整屏。

---

### P2-17 · 歌单详情可重排分支完全没传 `dataVersion` → 拖拽零保护，可提交错位移动

| 项 | 内容 |
|---|---|
| 位置 | `feature/playlist/.../PlaylistDetailScreen.kt:157-166`（调用 `ReorderableColumn` **未传 `dataVersion`**，默认 `null`）；对照 `Sheet.kt:493-500`（队列面板传了） |

**成因链**：`ReorderableColumn` 的四道防错闸（行槽 key、`pointerInput` 键、`LaunchedEffect(dataVersion)`、提交前版本比对，见 `ReorderableList.kt:152/193/107/217-219`）**全部以 `dataVersion` 为触发条件**。传 `null` 时 `liveDataVersion == dragDataVersion` 恒成立，四道闸全部退化为"永不变"。

**用户可见后果**：歌单详情页长按拖拽期间，若列表被外部改动（另一路径加歌/删歌、备份恢复 `state.restore()`、`addToPlaylist` 从其他入口进来），行槽不重建、手势不重启、提交前比对恒等 → **直接提交一次错位移动**。而 `AppState.movePlaylistTrack`（`AppState.kt:1366-1372`）只校验 `from/to in indices`，错位的内容**不可撤销**。

**最小修法**：补传 `dataVersion = data.tracks.map { it.id }`（或 `data.tracks.size to data.tracks.firstOrNull()?.id`，前者更严格）。同时建议把 `dataVersion` 从可选参数改为**必填**，避免新调用点再漏。

---

### P2-18 · 「过滤文件夹」只对文件夹 tab 生效，其余 5 个入口全部无视

| 项 | 内容 |
|---|---|
| 位置 | `excludedFolders` 全仓**唯一消费点**：`feature/library/.../LibraryScreen.kt:229`（`folderGroups(state.excludedFolders)`）；其余读取仅为持久化（`SettingsStore.kt:180/244/315`）与设置页自身（`SettingsScreen.kt:329/334/341`） |

**成因链**：`excludedFolders` 是一个「视图层过滤参数」，只被传给 `folderGroups()`。而以下所有派生函数都直接遍历完整 `library`，**没有任何过滤**：

| 入口 | 位置 | 是否过滤 |
|---|---|---|
| 歌曲 tab | `LibraryScreen.kt:82` → `AppState.sortedSongs()`（`:349-355`，`library.sortedWith`） | ❌ |
| 专辑 tab | `AppState.albumGroups()` → `groups().album`（`:1450`） | ❌ |
| 艺术家 tab | `AppState.artistGroups()`（`:1453`） | ❌ |
| 流派 tab | `AppState.genreGroups()`（`:1457`） | ❌ |
| 搜索 | `AppState.searchScope()`（`:1481`，返回 `library` 本身） | ❌ |
| 整库播放队列 | `LibraryScreen.kt:182` `state.playSong(song, songs, "曲库")`（`songs` = 未过滤的 `sortedSongs()`） | ❌ |
| 文件夹 tab | `LibraryScreen.kt:229` | ✅ |

**用户可见后果**：用户在设置页排除 `Download` 目录（比如里面全是微信语音、录音），设置页显示「1 个目录已排除」，但：
- 「歌曲」tab 里那些文件**照样列出来**，照常能播放
- 「专辑」/「艺术家」/「流派」tab 里同样出现
- 搜索能搜到
- 从曲库点「播放全部」时，被排除目录的歌**照样进队列**

即：设置项对用户是明确承诺，实际只在 **1/6** 的界面上生效。用户会认为"过滤坏了"。

**最小修法**（两种口径，需产品确认）：
- **A（视图过滤，与现状语义一致）**：在 `AppState` 加一个 `visibleLibrary()`（`library.filter { it.folder !in excludedFolders }`），`sortedSongs`/`groups`/`searchScope` 全部改为基于它。注意 `groups()` 的缓存键要加上 `excludedFolders` 的指纹（否则改了排除项不失效）。
- **B（扫描期过滤）**：`MediaLibrary.load()` 直接把被排除目录的条目剔除出返回列表（缓存仍全量）。这样所有入口天然一致，且省内存；代价是用户取消排除后必须重扫才能看到。

**推荐 A**：改动集中在状态层一处，且「取消排除立即生效」符合用户预期。

---

### P2-19 · 扫描循环无取消检查，且取消后仍会写缓存

| 项 | 内容 |
|---|---|
| 位置 | `app/.../player/MediaLibrary.kt:130-253`（`while (cursor.moveToNext())` 全循环）；`MediaLibrary.kt:52`（`load` 是普通阻塞函数，不接收协程上下文）；`GlassApp.kt:188-195`（`scope.launch { withContext(IO) { MediaLibrary.load(...) } }`） |

**证据**：`grep -c 'ensureActive\|isActive\|CancellationException' MediaLibrary.kt` → **0**。循环体内每轮做 `loadMetadata`（`:191`，`MediaMetadataRetriever` + `MediaExtractor` 两次打开）+ 可能 `persistCover`（`:211`，PNG 落盘）。

**成因链**：
1. 扫描由 `scope.launch` 驱动，Activity 销毁 / 用户离开时协程被取消。
2. 但 `load()` 是阻塞函数，**不响应取消信号** —— 全库继续扫完。
3. `scanning` 互斥标志（`:54`）被占住，期间任何重扫请求走重入分支（`:54-62`），拿到的是**旧语境下**的 `lastScan`。
4. 用户在设置页切换「扫描过滤」开关后立即重扫 → `outputFiltered(it, filterShort)` 虽按新开关过滤，但 `lastScan.songs` 是旧语义组装的集合。

**用户可见后果**：万首曲库 + 关闭短音频过滤 + 清数据后首扫，每首都要 retriever+extractor+PNG 落盘，全库十几秒到分钟级。期间退出 App / 旋转屏幕 / 重进设置页重扫**都无法中止**，旧线程继续吃 CPU/IO。连续快速重扫时第二次返回第一次的旧结果。

**最小修法**：`load(context, filterShort, isCancelled: () -> Boolean = { false })`，在 `:130` 每轮开头检查；中止时**不写缓存、不 sweep**（半量结果落盘会造成指纹表缺行）。同时设置开关切换处置 `lastScan = null` 强制下次真扫。

---

### P2-20 · LRC 候选优先级：私有目录**恒优先**于音频同目录 .lrc

| 项 | 内容 |
|---|---|
| 位置 | `MediaLibrary.kt:663-666` |

```kotlin
val candidates = listOfNotNull(
    context.getExternalFilesDir(null)?.let { java.io.File(it, "lrc/$fileName.lrc") },  // ← 恒第一个
    java.io.File(sibling),                                                            // ← 音频同目录
)
```

**成因链**：候选顺序写死 `files/lrc/` 在前，`firstNotNullOfOrNull`（`:670`）命中即返回。

**用户可见后果**：`/sdcard/Music/A/song1.lrc` 是正确的歌词，但 `files/lrc/` 里有一个半年前 adb 推进去的同名旧文件 → 重扫后显示旧歌词；**删掉/更新同目录 lrc 再重扫依然无效**（优先级不改永远读私有目录）。用户会认为"歌词不更新"。

**最小修法**：调换优先级（同目录 .lrc 优先，`files/lrc/` 仅作无权限兜底）；或两处按 `lastModified()` 取较新者。注意 `files/lrc/` 的存在理由是"scoped storage 下跨应用读 .lrc 会被拦"（`:653` 注释），所以兜底语义要保留。

---

### P2-21 · M3U 导入只认绝对路径逐字匹配，跨播放器导出的歌单基本全废

| 项 | 内容 |
|---|---|
| 位置 | `app/.../player/M3uPlaylistIO.kt:64-81`（`byPath = library.associateBy { pathOf(it) }`、`:79-80` 逐字 `byPath[line]`） |

**成因链**：解析层对路径行做**零归一化**：
- 不解析相对路径（相对 M3U 文件所在目录）
- 不识别 Windows 盘符 `D:\Music\xxx.mp3`（反斜杠 + 盘符）
- 不识别 `file://` URI
- 无大小写/分隔符容差

而索引键只能是绝对路径（`MediaLibrary.kt:229` `id = "f$dataPath"`，`dataPath` 来自 MediaStore `DATA` 列，恒为绝对路径）。文件头注释（`:54`）自称「相对路径（忽略）」，但实现是归入 **unmatched** 而非忽略。

**用户可见后果**：用 PC 播放器（foobar2000 / Windows Media Player）导出的含相对路径或 `D:\Music\xxx.mp3` 的 M3U，导入后提示"共 N 行未命中"，歌单为空或半空。用户会认为"文件明明在库里却匹配不上"——而这是**跨播放器迁移这一功能的唯一卖点**。

**最小修法**：路径行归一化后再查索引：① 反斜杠→斜杠、`file://` 去 scheme、盘符 `X:` 剥离后与 `/storage/emulated/0/` 拼接试探；② 索引同时建「绝对路径」+「basename」两级，路径未命中时按 basename 唯一命中兜底（多义则不匹配）。

---

### P2-22 · 用户歌单卡片计数与详情页不一致（失效 id 未过滤）—— **docs 复核误判，需重开**

| 项 | 内容 |
|---|---|
| 位置 | 写入端 `AppState.kt:667`（`restore`）、`:1282`（`syncPlaylistMeta`）；读取端 `LibraryScreen.kt:265`、`PlaylistScreen.kt:134`（歌单墙直接渲染 raw 元素）；对照 `AppState.kt:1567`（详情页 `resolvePlaylist` 已过滤） |

**证据链**（三处写入 meta 的路径，两处不过滤）：

```kotlin
// AppState.kt:667 —— restore 时用未过滤的 trackIds.size
DemoPlaylist(p.id, p.title, "${p.trackIds.size} 首", ArtStyle.Room, emptyList())

// AppState.kt:1282 —— syncPlaylistMeta 同样未过滤
userPlaylists[index] = playlist.copy(meta = "${addedTracks[playlistId]?.size ?: 0} 首")

// AppState.kt:1567 —— 只有详情页这条路过滤了
return merged.copy(meta = "${merged.tracks.size} 首")   // merged.tracks 来自 mapNotNull
```

```kotlin
// LibraryScreen.kt:265 / PlaylistScreen.kt:134 —— 歌单墙渲染 raw 元素
(state.visibleBuiltInPlaylists() + state.userPlaylists)
//   ↑ 内建走 resolvePlaylist（计数实时化 + 过滤）
//                    ↑ 用户歌单是 userPlaylists 原始元素（meta 字符串未过滤）
```

**成因链**：歌单曲目 id = `"f<绝对路径>"`（`MediaLibrary.kt:229`）。用户删掉某个音频文件 + 重扫后，`addedTracks` 里的失效 id **不会被清理**（`loadLibrary` 只刷新 `queue`，不动 `addedTracks`）。`resolvePlaylist` 在展示层用 `mapNotNull { songById(it) }` 过滤掉失效 id，但**卡片 meta 读的是 `DemoPlaylist.meta` 这个字符串字段**，它由上面两处未过滤的写入路径生成。

**用户可见后果**：往自建歌单加 5 首 → 删掉其中 1 个音频文件 → 重扫 → **歌单墙/曲库歌单 tab 显示「5 首」，点进去详情页显示「4 首」**。用户会认为"歌单里有首歌不见了但计数没变"。

> ⚠️ **docs 复核结论需更正**：`docs/2026-10-25-audit-verification.md` §5 曾判「restore 后 meta 虚高 → 展示层已过滤 → **不成立**」。该结论**只覆盖了 `resolvePlaylist` 路径，漏掉了歌单墙直接读 raw `meta` 的情况**。本条按新证据重开。

**最小修法**：歌单墙两处改读 `state.resolvePlaylist(playlist.id) ?: playlist`（与内建歌单同款，一处改动两个调用点）；或 `syncPlaylistMeta` / `restore` 写 meta 时改用过滤后计数（需访问 `songById`，`restore` 时曲库可能未装载，故推荐前者）。

---

### P2-23 · 退出飞行的残留协程把「忙时兜底直切」进播放页的用户弹回曲库

| 项 | 内容 |
|---|---|
| 位置 | `GlassApp.kt:760`（`if (flight.isActive) state.screen = GlassScreen.Player`）；`Flight.kt:271-274`（exitJob 尾部无条件 `navigate(Library)`） |

**成因链**：
```kotlin
// GlassApp.kt:760 —— 忙时兜底：只赋值 screen，不取消 exitJob、不 generation++
if (flight.isActive) state.screen = GlassScreen.Player
else flight.beginEntry(...)
```
```kotlin
// Flight.kt:271-274 —— exitJob 动画跑完后
t.animateTo(0f, GlassMotion.tweenFlight())
flip.cancel()
if (gen != generation) return@launch
navigate(GlassScreen.Library)   // ← 无条件执行，把用户从播放页拽回曲库
```

`flight.isActive` 为 true 时（退出飞行正在跑），兜底分支**只改 `screen`**，而 `exitJob` 仍在跑；它跑完时的 `gen == generation`（没人 ++），于是照旧 `navigate(Library)`。

**用户可见后果**：播放页点收起 → 库页刚出现（t≤0.15 flip 之后、退出动画还剩约 100-225ms 的减速段）→ 立即点迷你条 → **落进播放页后被弹回曲库**，期间底部还有替身迷你条幽灵淡入。

> 这是 `docs/2026-10-25-fix-report.md` P1-6（导航劫持）的**同类残留**：当时修了 `onRootSizeChanged` 和 `beginExit` 的中止路径，但 `onOpenPlayer` 这个兜底入口没接同一套中止语义。

**最小修法**：给 `PlayerFlight` 增加公开 `abort()`（`cancelJobs()` + `generation++` + 清瞬态），`GlassApp.kt:760` 改为 `if (flight.isActive) { flight.abort(); state.screen = GlassScreen.Player }`。

> 注：`SearchScreen.kt:228-232` 与 `PlaylistDetailScreen.kt:264-267` 是同型写法，但这两屏的 Chrome 在 `flight.isActive` 时不可点（详情/搜索页不在 `TAB_SCREENS` 内、无自绘 Chrome 的迷你条），实际不可达。

---

### P2-24 · 启动装载竞态：`loadCache` 晚于扫描到达时覆盖新扫描结果

| 项 | 内容 |
|---|---|
| 位置 | `GlassApp.kt:262`（`if (cached != null) state.loadLibrary(cached)` —— **无 `realLibraryLoaded` 门控**）；配合 `AppState.kt:816-827`（重扫分支整引用替换 `library`） |

**成因链**：
1. `GlassApp.kt:228` 的启动 effect 里：`store.read()` → `restore()` → `MediaLibrary.loadCache()`（**全量读缓存 + 每首歌 `readLrc`**，`MediaLibrary.kt:799`，数百次小文件 IO）。
2. 同时 `GlassApp.kt:365` 的权限 effect 里：`loadRealLibrary()` → `MediaLibrary.load()`（**增量扫描**，热缓存下只需几十 ms）。
3. 增量扫描可能**先完成**并调 `loadLibrary(新结果)` → `realLibraryLoaded = true`、`library = 新结果`。
4. 随后 `loadCache` 才返回 → `loadLibrary(陈旧缓存)` 无门控直接执行 → 落入 `AppState.kt:816` 的重扫分支 → `library = songs`（**整引用换回陈旧缓存**）。

**用户可见后果**：新歌消失、已删歌复活，**持续整个会话**（直到下次重扫或重启）。若 `cached == null`，`restore` 落下的 `pendingPlayback` 则无人消费 —— `AppState.kt:209-212` 注释声称「restore 与 loadLibrary 谁先到都幂等收敛」，但**对"缓存晚于扫描"这条路径不成立**。

**最小修法**：`if (cached != null && !state.realLibraryLoaded) state.loadLibrary(cached)` —— 一行。

---

### P2-25 · 更新失败文案把所有失败都说成「下载失败」，验签失败的「点击重试」是死循环

| 项 | 内容 |
|---|---|
| 位置 | `SettingsScreen.kt:402-405`（文案）、`:464-465`（重试动作）；`UpdateManager.kt:145-164`（`install()` 用同一个 `Failed` 状态承载三类失败） |

```kotlin
// SettingsScreen.kt:402 —— 仅凭 versionName != null 判定「下载失败」
UpdateUiState.Status.Failed ->
    if (updateState.versionName != null) "下载失败（${updateState.message ?: "网络异常"}），点击重试"
    else "检查失败（${updateState.message ?: "网络异常"}），点击重试"

// UpdateManager.kt:157-163 —— 验签失败也是 state.copy(...) → 保留 versionName
if (!verifyApkSignature()) {
    runCatching { apkFile.delete() }
    state = state.copy(status = Failed, message = "安装包签名校验失败，已拒绝安装")
    return
}
```

**成因链**：`Failed + versionName != null` 被当作「下载失败」的**唯一判据**，而 `install()` 的失败是 `state.copy(...)`（保留 `versionName`）→ 安装阶段/验签阶段的失败全被贴上「下载失败 · 点击重试」。验签失败时重试 = **重新下载同一份字节 → 同一签名 → 必然再失败**，且 APK 每次被 `delete()`。

**用户可见后果**：换签名后老用户看到「下载失败（安装包签名校验失败，已拒绝安装），点击重试」，点一次下 31MB、下完再失败、无限循环。更新面板转入 `Failed` 时自动收起（`:417-419`），所以用户只看到这一行误导文案，看不到真正原因。

**最小修法**：`UpdateUiState` 增加 `failStage: FailStage { Check, Download, Verify, Install }`（或直接加 `InstallFailed` 状态），文案按阶段给不同结论与动作：**验签失败 → 「安装包签名与本机不一致，无法应用内更新，请到发布页手动下载安装（需先卸载旧版）」，且不给重试按钮**。

---

### P2-26 · 定时关闭在 Activity 重建后显示「未开启」但到点仍会暂停

| 项 | 内容 |
|---|---|
| 位置 | `AppState.kt:289-296`（`sleepTimerMode`/`sleepTimerDeadlineMs` **不持久化**）；`GlassApp.kt:117`（`val state = remember { AppState() }`，重建 = 全新实例）；`AndroidManifest.xml:52`（`configChanges` 未含 `fontScale`/`locale`）；`MusicPlaybackService.kt:301-318`（服务端定时器独立存活） |

**成因链**：
1. `configChanges` 包含 `orientation|screenSize|screenLayout|smallestScreenSize|keyboardHidden|uiMode`，但**不含 `fontScale`/`locale`/`density`** → 用户改系统字体大小或语言会**重建 Activity**。
2. Activity 重建 → `remember { AppState() }` 新建 → `sleepTimerMode` 回到 `0`。
3. 服务端定时器**不随 Activity 销毁**（`serviceScope` 只在 `onDestroy` 取消）。
4. 两者之间**没有回读/对账通道**（`attachPlayer` 只同步 repeat/shuffle/eq/speed，`AppState.kt:506-511`）。

**用户可见后果**：设置「定时关闭 → 15 分钟」→ 退出应用去系统设置把字体调大 → 回到应用 → 设置页显示「未开启」、播放页面板无任何高亮 → **15 分钟后音乐自己暂停**，用户完全不知道原因。`-1 播完当前首` 同款。

> 这与 `AppState.kt:432-437` 花大篇幅解决的「孤儿旗标」是同一类问题，只是触发源是 Activity 重建而非手动暂停。

**最小修法**：`sleepTimerMode`/`sleepTimerDeadlineMs` 写进快照（`AppStateSnapshot` 加两字段，`SettingsStore` 编解码各加两行）；重启后分钟档按 deadline 判断是否仍有效，`-1`/`-2` 直接丢弃。

---

### P2-27 · 快照解码失败 = 静默重置全部设置与歌单，且随后被默认值覆盖

| 项 | 内容 |
|---|---|
| 位置 | `SettingsStore.kt:227-235`（解码失败只打一条 Log）；`GlassApp.kt:247-252`（损坏与「首次启动」合并成同一路径） |

```kotlin
// GlassApp.kt:247 —— 损坏与首装走同一条路
val snapshot = runCatching { store.read() }.getOrNull()
if (snapshot != null) { state.restore(snapshot) } else { state.markHydrated() }
```

**成因链**：`decode()` 失败返回 `null` → `markHydrated()` → `hydrated = true` 后第一次 `scheduleSave()` 就把**默认状态写回同一个键** → 原始数据被覆盖，不可挽回。两者唯一区别是一条 Log。

**用户可见后果**：任何一次 JSON 截断/字段类型漂移（手工改过备份、DataStore 写盘被强杀）→ 冷启动后收藏、自建歌单、播放次数、隐藏歌单、主题色**全部归零**，界面无任何提示；再操作一次就覆盖掉损坏的原文件。

**最小修法**：`read()` 区分「键不存在」与「解码失败」；解码失败时把原始串另存为 `app_state_snapshot.corrupt.<ts>`，UI 顶部提示「设置数据损坏，已保留副本，可从备份恢复」，且 `markHydrated` 之前不落盘。

---

### P2-28 · 排除集只增不减：已消失目录的排除项无法撤销，计数与面板列表自相矛盾

| 项 | 内容 |
|---|---|
| 位置 | `SettingsScreen.kt:333-344`（面板只遍历 `state.folderOptions()`，即**当前曲库存在**的目录）；`AppState.kt:1420-1421`；`SettingsStore.kt:180/244`（持久化，无清理逻辑） |

**成因链**：排除项按目录名持久化，但面板候选项来自「当前曲库」。某目录被删空/改名/换存储路径后，目录名从候选项消失，**排除记录却留在 Set 里**。

**用户可见后果**：设置页显示「3 个目录已排除」，点进去面板只有 2 行 —— 用户**无法取消第 3 个**。若该目录日后重新出现（插回 SD 卡、重命名回来），它会**静默**从文件夹 tab 消失且没有任何提示来源。

**最小修法**：面板列表取 `folderOptions()` 与 `excludedFolders` 的**并集**（不在曲库里的项显示「0 首 · 目录当前不存在」并允许取消），或提供「清空全部排除项」。

---

### P2-29 · 扫描过滤开关与「重扫后生效」之间有 ≤500ms 的旧值窗口

| 项 | 内容 |
|---|---|
| 位置 | `GlassApp.kt:193`（扫描读**磁盘键** `SettingsStore.readScanFilterShort`）；`SettingsScreen.kt:321-325`（开关改的是**内存态** `state.scanFilterShort`）；`SettingsStore.kt:46-50`（`debounce(500)`） |

**成因链**：两条事实源不同步 —— UI 开关改内存，扫描读 DataStore 独立键，而该键有 500ms 防抖才落盘。`SettingsStore.kt:142-151` 的注释**自己承认了**这个窗口。

**用户可见后果**：关掉「扫描过滤」→ **立刻**点「扫描音乐」→ 通知音/录音碎片仍然不出现（用的是旧值 `true`）；再点一次才生效。用户会认为开关没起作用。

**最小修法**：扫描入口直接用内存态（`onRescan` 改成 `(Boolean, () -> Unit) -> Unit`），或 `toggleScanFilter()` 里同步 `flush()` 后再允许重扫。

---

### P2-30 · 空曲库时占位曲可通过「歌曲操作」写进收藏/队列（脏数据持久化）

| 项 | 内容 |
|---|---|
| 位置 | `AppState.kt:1257-1260`（`toggleFavorite` **无占位曲守卫**）；`:1208-1209`（`insertQueueItems` 只判 `songs.isEmpty()`）；入口 `PlayerScreen.kt:723-730`（「歌曲操作」→ `SongActionsSheet(song = state.currentSong)`） |

**证据**（两处守卫均缺失，已实测确认）：

```kotlin
// AppState.kt:1257 —— 任意 id 都能进 favorites
fun toggleFavorite(id: String) {
    if (id in favorites) favorites.remove(id) else favorites.add(id)
    scheduleSave()
}

// AppState.kt:1208 —— 只判空列表，不判占位曲
private fun insertQueueItems(songs: List<DemoSong>, at: Int, pinLast: Boolean) {
    if (songs.isEmpty()) return
```

**成因链**：
1. 权限被拒 / 无音频 → `queue` 为空 → `currentSong` 回落 `DemoData.placeholder`（id=`"placeholder"`、`durationSec=1`、`mediaUri=null`）。
2. 播放页照样按「有歌」渲染（`P3-44` 的根因），控制台的 prev/play/next 被 `hasSongs` 守卫吞掉 —— 但**「更多」按钮没有守卫**（`PlayerScreen.kt:220`）。
3. 进「播放选项 → 歌曲操作」→ `SongActionsSheet(song = state.currentSong)` = 占位曲。
4. 点「收藏」→ `toggleFavorite("placeholder")` → **`"placeholder"` 进 `favorites` 并落盘**。
5. 或点「下一首播放」→ `insertQueueItems([placeholder], ...)` → 占位曲进队列 → `hasSongs` 变 `true` → 波形/进度线假装播放约 1 秒后自行停住（`AppState.kt:864-867` 只拦 tick，不拦队列污染）。

**用户可见后果**：新用户拒绝权限后打开 App 直接停在播放页，所有按钮「能按但没反应」，且**没有「去授权」入口**（那个入口只在曲库页 `LibraryScreen.kt:356-370`）；若误点收藏，`favorites` 里永久留下脏 id。

**最小修法**（三处，建议全做）：
1. `toggleFavorite` / `insertQueueItems` 补 `if (id == DemoData.placeholder.id) return`（与 `bumpPlayCount:1611` 同款守卫）；
2. 控制台在 `!state.hasSongs` 时整体降透明 + `enabled = false`；
3. 更彻底：`PlayerScreen` 顶部 `if (!state.hasSongs) { EmptyPlayer(); return }`，或 `screen` 初值改 `Library`（同 `P3-44`）。

---

### P2-31 · 搜索框被放进 LazyColumn 首项：滚动时滚走并丢焦点，滚回顶部又自动弹键盘

| 项 | 内容 |
|---|---|
| 位置 | `feature/search/.../SearchScreen.kt:84-89`（`LazyColumn { item(key = "search-field") { ... } }`）；`:100`（`autoFocus = true`）；`Field.kt:89-91`（`LaunchedEffect(autoFocus) { if (autoFocus) focusRequester.requestFocus() }`） |

**成因链**：
1. 搜索框（连同「取消」）是 LazyColumn 的**第 0 项**。
2. 向下滚动看结果 → 该项滑出屏幕 → LazyColumn 把屏幕外项**从组合中移除** → 输入框节点销毁 → **焦点丢失、键盘收起**。
3. 滚回顶部 → 该项重新组合 → `LaunchedEffect(autoFocus)` 以 `autoFocus = true` **再次执行** → `requestFocus()` 抢焦点并**弹出键盘**。

这是 Compose 的既定语义（`LaunchedEffect` 在每次进入组合时重跑），不是偶发。

**用户可见后果**：搜「a」得到数十条结果 → 向下翻看 → 搜索框连同已输入的文字一起滚出屏幕（看不到也改不了关键词），键盘莫名收起；往回滚到顶，键盘又自己弹出来。`SearchScreen.kt:61-63` 的注释说改成 LazyColumn 是为了性能，但把输入框一并塞进懒列表是**回归**。

**最小修法**：搜索框移出 LazyColumn —— `Column { SearchRow(); LazyColumn(Modifier.weight(1f)) { ... } }`；`hazeSource` 保持在列表上即可。

---

### P2-32 · 歌词 rail 每次进入歌词模式都从 offset=0 动画滑到居中

| 项 | 内容 |
|---|---|
| 位置 | `feature/player/.../PlayerScreen.kt:511-517`（`var targetOffset by remember { mutableFloatStateOf(0f) }` + `animateFloatAsState`）；`:522-530`（两个 `LaunchedEffect`）；`:274`（`if (lyrics != null && modeT > 0f)` —— rail 是**条件组合**） |

**成因链**：
1. `animateFloatAsState` 的初始值 = 首次组合时的 `targetValue = 0f`。
2. rail 首次组合时 `lineCenters` 为空 → 居中 effect 直接 `return`。
3. 一帧后 `onGloballyPositioned` 回填坐标 → effect 才把 target 设为 `stageH/2 - center`（长歌词列表可达数百 px）。
4. `animateFloatAsState` 从 **0** 起步，用 500ms（`GlassMotion.tweenLyric()`）追过去。

所以「居中」不是一次到位，而是**每次都播一段位移**。`LaunchedEffect(lines)` 的 `targetOffset = 0f` 又给切歌加了一次「先归零再重新居中」。

**用户可见后果**：封面模式 → 点封面切到歌词 → 歌词整块从「内容居中位」滑到「当前行居中位」（伴随 alpha 淡入）；切歌时同理再滑一次。复现：任意有 `.lrc` 的歌，播放页点封面。

**最小修法**：`targetOffset` 改 `Animatable(0f)`；在「首次拿到 `lineCenters[current]`」与「`lines` 变化」两个时机用 `snapTo` 而非 `animateTo`，之后的行切换才用 `animateTo`。

---

## 3. P3 — 打磨与边界不一致

| # | 位置 | 问题 | 修法 |
|---|---|---|---|
| P3-1 | `PlayerScreen.kt:426` vs `AppState.kt:378-391` | 右侧总时长用 `song.duration`（扫描值），进度比例用 `effectiveDurationSec()`（引擎真值）。元数据缺失时扫描值兜底 1s → 进度条瞬间走满而时间文本还显示 `0:01` | 总时长也读 `state.durationSec` |
| P3-2 | `PlayerScreen.kt:178-192` | 飞行期消费 `PointerEventPass.Initial` 全部事件。若 `awaitRects` 判稳超时（`Flight.kt:178` 的 600ms）期间用户点歌词行，点击被吞 | 可接受（600ms 窗口），但建议把闸门只作用于共享元素区域 |
| P3-3 | `LibraryScreen.kt:178` | `playing = song == state.currentSong` 用 **data class 全字段相等**，而 `SearchScreen.kt:149` / `PlaylistDetailScreen.kt:427` 用 `.id ==`。重扫后对象字段变化（如 `durationSec` 修正）会让曲库页高亮消失、其他页仍高亮 | 统一改为 `song.id == state.currentSong.id` |
| P3-4 | `LibraryScreen.kt:125-129` | 深色模式下主题切换按钮用 `GlassIcons.Gear`（齿轮），浅色下用 `Moon`。齿轮的通用语义是"设置"，会误导用户 | 新增 `Sun` 图标（`GlassIcons.kt` 目前无 Sun） |
| P3-5 | `AppState.kt:1475-1476` + `AppState.kt:1592` | `detailData()` 的 Folder 分支调 `folderGroups(emptySet())`，**忽略 `excludedFolders`**；而曲库文件夹 tab 用 `folderGroups(state.excludedFolders)`。目前因 tab 里已隐藏该文件夹而不可达，但若将来加"从搜索进文件夹"就是 bug | Folder 分支改传 `excludedFolders` |
| P3-6 | `AppState.kt:1032` + `PlayerScreen.kt:411-414` | 空队列时拖进度条 → `seekToFraction` → `seekTo` → `progressSec = sec.coerceIn(0f, 1f)`，进度条会停在新位置。虽然不会播放，但视觉上"进度被改了" | `seekTo` 首行加 `if (!hasSongs) return` |
| P3-7 | `Chrome.kt:249-252`（`ChromeBottomInset` 引用）vs `PlaylistDetailScreen.kt:237` | 详情页底部避让用硬编码 `18.dp`，三个主屏用 `ChromeBottomInset`。详情页自绘 Chrome 在屏内，语义不同，但常量散落 | 加注释说明或抽 `DetailBottomInset` |
| P3-8 | `SettingsScreen.kt:356-363` | 「恢复内置歌单」点击即生效、立即消失，无二次确认，且不可撤销（`restoreBuiltInPlaylists` 只清空隐藏集） | 加二次确认或 Toast 撤销入口 |
| P3-9 | `SettingsScreen.kt:136-139` + `GlassTheme.kt:70` | 文案「可改为自定义」+ 注释「用户也可用滑杆自定」，但面板只有 10 个预设色点，**没有 HSV 滑杆/取色器**（`ThemeColorPicker.kt` 全文 120 行只有色点网格） | 改文案为「点击色点更换」，或补滑杆 |
| P3-10 | `PlayerScreen.kt:724-728` | 播放选项面板的「歌曲操作」用 `state.currentSong`（不是打开面板时的快照）。若面板打开期间自动切歌，点进去操作的是**新歌** | 打开面板时快照 `val target = state.currentSong` 并闭包捕获 |
| P3-11 | `SearchScreen.kt:127` | 点历史 chip 只设 `state.searchQuery = keyword`，**不写回历史** → 该词不会移到最前，下次可能被 `take(4)` 挤出 | 点击时也调 `addSearchHistory` |
| P3-12 | `SearchScreen.kt:66` | `state.searchQuery` 是全局状态，离开搜索页不重置 → 下次进搜索页直接显示上次的词与结果 | 视产品意图保留；若期望"新搜索"则进屏时清空 |
| P3-13 | `AppState.kt:1263-1270` | `addToPlaylist()` 对任意 `playlistId` 都 `getOrPut`，无 id 合法性校验（与 P2-3 同源，但影响面更广：任何拼错的 id 都会造出一条 `addedTracks` 脏记录并持久化） | 加 `if (resolvePlaylist(playlistId) == null) return false` |
| P3-14 | `MediaLibrary.kt:80-84` + `MediaLibrary.kt:429-433`（两处同样的降采样循环） | `while (outWidth / (sample*2) >= MAX || outHeight / (sample*2) >= MAX)` —— 退出条件用的是 `sample*2`，导致**实际解码尺寸可能仍大于 MAX_EDGE_PX**（例：800×600、MAX=256：sample 停在 2 → 实解 400×300，长边仍超 256）。功能无害（只是比预期多占内存），但两处同款 | 条件改为 `outWidth / sample > MAX` 后再 `sample *= 2` 的写法，或解码后二次缩放 |
| P3-15 | `PlayerFlightOverlay.kt:67` | `val t = flight.t.value` 在**组合期**读动画状态 → 飞行期每帧整层重组（该层有 6 个替身 + hazeEffect，代价不小） | 下沉到 `graphicsLayer{}` / `drawWithCache` 的绘制期读，或把 t 拆成几何/样式两个绘制期 lambda |
| P3-16 | `Flight.kt:310`（`rects.clear()`）+ `:250-257` | 旋转/分屏后 `rects` 全清，若此时正停在播放页（Chrome 不在组合）→ 迷你条 rect 为空 → `beginExit` 走兜底 `navigate(Library)`，**退出动画静默降级为直切** | 可在 `onRootSizeChanged` 后主动测量一次迷你条几何（或退出时允许"无迷你条 rect"的淡出动画） |
| P3-17 | `PlaylistDetailScreen.kt:157-161` | `ReorderableColumn` 调用**完全没传 `dataVersion`**（默认 `null` = 视为不变）→ 该处拖拽零保护；队列面板传了（`Sheet.kt:493`）。两处不一致 | 补传 `dataVersion = data.tracks.map { it.id }`（或至少当前曲 id） |
| P3-18 | `ReorderableList.kt:117-121` | 拖拽闭包捕获 `count`，拖拽中列表变短时 `dragTarget` 可能越界（`AppState` 有校验不崩，但 `settleRow` 会指向不存在的行） | `updateTarget()` 里读实时 `count`（用 `rememberUpdatedState`） |
| P3-19 | `ReorderableList.kt:160-164`、`:269` | 让位与落位动画用硬编码 `tween(150)` / `SETTLE_MS = 150f`，**不随 `GlassMotion.scale`**（动效强度设置对它无效） | 改用 `GlassMotion.tweenPage()` 或乘 `GlassMotion.scale` |
| P3-20 | `ReorderableList.kt:166` | 行强制 `.height(rowHeight)`（固定 66dp）→ **大字号/大显示尺寸下 `SongRow` 内容被裁切**（两行文本 + 48dp 封面可能超 66dp） | 改 `heightIn(min = rowHeight)`，并让 `rowHeight` 由实测行高回填 |
| P3-21 | `Sheet.kt:423-433` | `LaunchedEffect(visible, currentIndex)` 在拖拽中自动切歌时**强制滚动**，与"拖拽作废"叠加成跳变（子智能体实测观察） | 拖拽中（`dragFrom >= 0`）跳过自动滚动 |
| P3-22 | `GlassApp.kt:576-583` + `Flight.kt:165/178/201` | 进入等待期最长可达 600+600+600ms，期间根层 `Initial` pass 消费**所有**指针 → 慢设备上"迷你条悬空 + 整屏点不动" | 缩短超时，或闸门只覆盖共享元素区域 |
| P3-23 | `PlayerFlightOverlay.kt:110-120` | 从 Detail/Search 进播放页时，退场中的旧屏**自带 GlassChrome**（`navAlpha = 1-t`），与 overlay 的导航替身**双份叠加** → 胶囊偏亮 | 详情/搜索屏在飞行期让 Chrome 的 `navAlpha` 归零（或 overlay 侧不再画导航替身） |
| P3-24 | `ReorderableList.kt:240-242` | settle 首帧空转一帧（先 `withFrameNanos` 再进循环），且 `|start| <= 1` 跳过 settle 时缩放 1.02→1.0 **硬跳** | 首帧直接用当前时刻；跳过 settle 时也补一个极短缩放动画 |
| P3-25 | `ReorderableList.kt:126-136` | autoscroll 固定 `4dp/帧` → **帧率相关**（60Hz=240dp/s，120Hz=480dp/s），且无边缘缓动；`viewportH` 公式（`:132`）少算面板底部 16dp padding | 改按时间积分（`withFrameNanos` 差值 × 速度），补 padding |
| P3-26 | `Artwork.kt:258` vs `:280` | 同一行两个时间文本**补零规则不同**：左侧 `ElapsedTimeText` 走 `asTime()` = `"%02d:%02d"` → 「03:07」；右侧 `song.duration` = `"%d:%02d"` → 「3:07」。不足 1 分钟时「00:05」vs「0:05」 | `DemoSong.duration` 改用 `asTime()` 同款格式串（或反之，但必须统一） |
| P3-27 | `GlassIcons.kt:104-107`（`Gear`） | 该图标实际是「圆 + 8 条短放射线」（太阳形，**无齿轮齿**），却被三处用于不同语义：底部导航「设置」（`Chrome.kt:268`）、曲库页右上角「切换深浅色」（`LibraryScreen.kt:126`，注释自称"太阳"造型）、设置页「动效强度」（`SettingsScreen.kt:214`） | 新增独立的 `Sun` 图标给深浅色切换；`Gear` 补齿轮齿或改名为 `Sun`/`Asterisk` |
| P3-28 | `MediaLibrary.kt:683-700` | `parseLrc` 的 `[offset:]` 是**行内即时扣减**（`:698` `- offsetMs / 1000f`）。两个后果：**a)** offset 标签写在带时间戳行**之后**（老工具常放文件尾）对前面已解析行**不生效**（不能回溯）；**b)** 某行同时含 stamps 与 offset 时，`:687` 的 offset 分支只在 `stamps.isEmpty()` 进入 → offset 被**完全忽略** | 两趟解析：先全文扫 `lrcOffsetRegex.findAll(text).last()` 得 offset，再逐行解析时间戳 |
| P3-29 | `MediaLibrary.kt:685-693` | 纯文本行（无时间戳）与空内容时间戳行被**直接丢弃** → 完全无时间戳的 .lrc 让 `parseLrc` 返回空列表；调用侧 `:671` `ifEmpty { null }` 把「文件存在但解析为空」与「文件不存在」混同 → 最终 `lyricLines = null` → UI 用**演示歌词兜底**（别的歌的内容） | `readLrc` 返回三态（无文件 / 有文件但无有效行 / 有歌词）；纯文本聚成单条或让 UI 提示"该歌词文件不支持" |
| P3-30 | `MediaLibrary.kt:671` | `readLrc` 用 `file.readText()` **一次读全文件，无大小上限**；且该函数在扫描主循环**每首歌都调**（`:182` 复用分支、`:243` 新提取分支，短音频重扫也触发） | 读取前 `if (file.length() > 512 * 1024) return null`（512KB 足够任何正常歌词） |
| P3-31 | `MediaLibrary.kt:464-479`（`persistCover`） | 「总是覆写」方向正确（docs #3），但没区分「本次提取结果与盘上已有文件是否相同」→ 缓存 JSON 损坏但 `covers/` 完好、或升级换键时，**每首有封面的歌都要重新解码 + 重压缩 + 覆写 PNG**。万首 × 30KB ≈ 单次事件多写 300MB 闪存 | 覆写前判断 `file.isFile && file.lastModified() >= 源音频 lastModified()` 则跳过并直接返回 `Uri.fromFile(file)` |
| P3-32 | `MediaLibrary.kt:858-861` + `GlassApp.kt:200-211` | `writeCache` 的 fingerprints 来自 `lastScan`（单例），而 songs 是**参数传递** → 两者生命周期不同步。两次扫描交错（权限恢复触发的 `loadRealLibrary` 与手动重扫并发）时，第一次的 `writeCache` 用第二次的指纹匹配第一次的 songs → 第二次扫描中消失的条目 `fp` 为 null → **写 0** → 下次扫描视为不匹配，全量重提取 | fingerprints 改为 `writeCache` 的显式参数（与 songs 同源传递） |
| P3-33 | `MediaLibrary.kt:851` | `put("dataPath", s.id.removePrefix("f"))` —— 对不以 `f` 开头的 id 会得到**空串**。`writeCache` 是 `public` 函数，未来任何调用方传入演示/占位 song 都会产出 `dataPath=""` 孤儿条目（`:777-783` 读缓存时走"沿用 artworkUri"分支），每次扫描都被判"新增"，永不复用 | 循环开头 `if (!s.id.startsWith("f")) return@forEach` |
| P3-34 | `M3uPlaylistIO.kt:68-69` + `GlassApp.kt:483` | `matched` 是 `LinkedHashSet`（**去重**），`unmatched` 是 `mutableListOf`（**不去重**）→ 同一未知路径出现两次就进两次 `unmatched`，消息「N 首未匹配」把重复行算成多首 | `unmatched` 也用 `LinkedHashSet`，或消息改为「未匹配 N 行」 |
| P3-35 | `AppState.kt:1554-1557` | 「最常播放」`.take(50).mapNotNull { songById(it.key) }` —— **先取前 50 再过滤**，失效 id 白占名额。`playCounts` 有 60 条（15 条指向已删文件）→ 只显示 35 首，实际有 45 首够格 | 交换顺序：先 `mapNotNull` 再 `take(50)`（排序键不变，语义不变） |
| P3-36 | `AppState.kt:1450/1453/1454/1458` vs `Sort.kt:19-25` | 分组 tab（专辑/艺术家/文件夹/流派）四处用 `sortedBy { it.key }`（**码点序**），而歌曲 tab 用 `Collator.getInstance(Locale.CHINA)`（**拼音序**）。中文艺术家如「五月天/周杰伦」在两处的相对顺序可能相反 | 分组排序改 `sortedWith(compareBy(collator) { it.key })`，collator 挂进 `groups()` 缓存一次即可 |
| P3-37 | `AppState.kt:1306-1311`（`playlistTitleExists`） | 只查 `userPlaylists` + `DemoCatalog.playlists`（4 个静态 `pl-*`），**不含三个动态歌单的展示名**（「我喜欢的音乐 / 最近播放 / 最常播放」）→ 可创建同名自建歌单，歌单墙出现两张同名卡 | 在 `DemoCatalog` 集中登记动态歌单标题，`playlistTitleExists` 一并查 |
| P3-38 | `LibraryScreen.kt:170-173`（仅 tab 0） | 6 个 tab **只有歌曲 tab 有空态**；tab 1-5（`:187/211/229/247/265`）列表为空时无任何占位分支 → 空库/权限拒绝时切到「专辑」等 tab，页头+搜索+胶囊之下**整片空白**，且「去授权」入口只在歌曲 tab 可见 | 抽一个 `TabEmptyState` 复用到 6 个 tab（docs round2 #18 已列待修，本轮确认**仍未落地**） |
| P3-39 | `LibraryScreen.kt:77`、`PlaylistScreen.kt:91`、`PlaylistDetailScreen.kt:127` | tab 与三处滚动状态全用 `remember`（`grep rememberSaveable` **全仓零命中**）。`GlassApp.kt:601` 的 `AnimatedContent(targetState = state.screen)` 换屏即卸载旧内容 → 去播放页再回来 = 全新组合 | `tab` 改 `rememberSaveable`；滚动状态改 `rememberLazyListState` / `rememberSaveable(rememberScrollState())` |
| P3-40 | `PlaylistDetailScreen.kt:204`、`LibraryScreen.kt:175`、`ActionsSheet.kt:417` | `items(key = { it.id })` 依赖 `song.id` **全局唯一**。若 MediaStore 对同一 `DATA` 返回两行（双卷/索引异常时确实发生），两个同 id 条目进同一列表 → `Key … was already used` **直接崩溃** | 扫描输出层 `distinctBy { it.id }`（单点根治三个调用点） |
| P3-41 | `PlaylistDetailScreen.kt:115` | `data == null` 时 `LaunchedEffect(Unit) { state.screen = GlassScreen.Playlist }` **硬编码回歌单页**；而返回键走 `state.backFromDetail()`（`AppState.kt:1402-1404`，回 `detailReturn`）→ 从曲库进专辑详情、目标因重扫消失时，被弹到**歌单页**而非曲库 | 改为 `state.backFromDetail()` |
| P3-42 | `AppState.kt:1450`（`lib.groupBy { it.album }`）+ `:201`（`meta` 只显示首曲 artist） | 分组只按**专辑名**、无 artist 维度 → 不同艺术家的同名专辑（两张 "Greatest Hits"）**混进同一张卡**，meta 只显示其中一位艺术家，详情页曲目混排。`albumCountByArtist`（`:1460`）按「专辑首曲艺术家」计数，口径一致故无二次错账 | 若要修需分组键加 artist 维度（`"$album\u0000$firstArtist"` 或 MediaStore `ALBUM_ID`），并同步 `detailData` 的 Album 查找键，动作面较大，建议先记为**已知简化** |
| P3-43 | `PlaylistScreen.kt:140-153`、`LibraryScreen.kt:272-283` | `PlaylistCard` 支持 `bitmap`/`artworkUri`，但**歌单墙与歌单 tab 的调用点均未传** → 歌单卡封面**恒为占位 ArtStyle**；而详情页 `DetailHero` 取首曲封面（`AppState.kt:1580`）→ 卡片是占位图、点进去是大封面，不一致 | 给歌单卡传首曲 `artworkUri`（与详情页同源） |
| P3-44 | `AppState.kt:215` | `var screen by mutableStateOf(GlassScreen.Player)` —— **初值是播放页**，不持久化、无启动改写。首装权限被拒的用户 settle 后看到的是**无 Chrome 的占位播放页**，需自己按返回才见「去授权」入口；未被 manifest `configChanges` 吞掉的 Activity 重建（密度/字号/语言）同样回播放页 | 若非有意，初值改 `GlassScreen.Library` |
| P3-45 | `GlassApp.kt:291` | `if (jumpedAllFiles && grantedNow && !grantedBefore)` —— 绑定了 `jumpedAllFiles`（**仅应用拉起系统页时才置位**）。用户自行去系统设置开「所有文件访问」后返回，`jumpedAllFiles == false` → 不触发重扫 → `.lrc` 不被拾取 | 去掉 `jumpedAllFiles &&`（`grantedNow && !grantedBefore` 已足够表达"刚授权"） |
| P3-46 | `GlassApp.kt:357` | `if (canAskAgain \|\| !audioDenialShown)` —— `audioDenialShown` 每进程重置，而 `shouldShowRequestPermissionRationale` 对「从未问过」与「不再询问」**都返回 false** → 新进程首次点「去授权」仍会 `launch` 即被拒、**无弹窗**，与 `GlassApp.kt:339-342` 注释声称的目标不符（round2 验收「点第二次跳详情页」已接受此行为，属**修复不完整**） | 仿 `allFilesPrompted` 持久化「已被拒过」标记 |
| P3-47 | `AppState.kt:88-96` + `SettingsScreen.kt:271-277`、`PlayerScreen.kt:717/732-737` | `SleepTimerOptions` 末项是 `0`（取消定时），无定时时 `activeMode` 也是 `0` → **「取消定时」被判为选中**（`emphasized = option == activeMode`），且副标题写「未开启」——从未设过定时的人打开面板就看到这行被高亮 | `emphasized = activeMode != 0 && option == activeMode`；`option == 0 && activeMode == 0` 时 subtitle 用 `null` |
| P3-48 | `SettingsScreen.kt:102-107`、`PlayerScreen.kt:711-716` | 定时倒计时的 `while(true) { delay(1000) }` 在**无定时时仍每秒跑**（写同值不触发重组，纯功耗） | 改 `while (state.effectiveSleepTimerMode() > 0)` |
| P3-49 | `GlassApp.kt:223` + `SettingsScreen.kt:95-98` | `loadRealLibrary` 在协程末尾**无条件** `onDone()`（成功/失败/空库三路都到）→ `SettingsScreen.kt:96-98` 的 `LaunchedEffect(state.library) { if (scanning) scanning = false }` 是**死代码**（且 List 用 equals 比较，重扫结果相同也不重启该 effect）。**残留风险**：若 `state.loadLibrary(songs)` 抛异常，`onDone()` 被跳过 → 按钮永久停在「扫描中…」 | `loadRealLibrary` 用 `try/finally { onDone() }` 包住 `state.loadLibrary`；删掉 96-98 的死代码 |
| P3-50 | `UpdateManager.kt:309-311` + `:347` | `updateProgress` 首行 `if (total <= 0) return`，而 `total` 来自 `conn.contentLengthLong`。经 gh-proxy/ghfast.top 转发时若丢 Content-Length（chunked）→ **进度条全程 0%**，文案「下载中 0%」直到突然跳到「已下载」 | `total <= 0` 时改用「已下载 X MB」字节文案 + 不定长进度 |
| P3-51 | `UpdateManager.kt:282-295` | `runCatching` 捕获 `Throwable`（含 `CancellationException`），而 `downloadTo` 是阻塞 IO 不受取消影响 → 下载中旋转屏幕/改字号（Activity 重建 → `rememberCoroutineScope` 取消）后，**剩余镜像仍会被逐个下完**（流量浪费），且 `app-update.tmp` 残留 | `catch (e: CancellationException) { tmp.delete(); throw e }`，`startUpdate` 开头清理陈旧 tmp |
| P3-52 | `UpdateManager.kt:53-54` 注释 vs `:324-327` 实现 | 注释称「静默检查最长 15s（三链 × 5s）」，实际每链 `connectTimeout=5s` + `readTimeout=5s` = 10s，**三链最长 30s**。期间设置行固定「正在检查…」且 `checkInFlight` 持有、无法取消 | 改注释，或给 `fetchManifest` 加整体 `withTimeoutOrNull(15_000)` |
| P3-53 | `gradle/libs.versions.toml` | ①`:40` `core-runtime = "0.0.0"` 是**不可解析**占位值（谁引用谁构建失败，目前无人引用=埋雷）；②`:37` `core = "1.17.0"` 但 APK 内实测 **1.16.0**；③`:120-122` `savedstate = "1.3.2"` 但实测 **1.3.3**；④`:70-72` `kotlinx-coroutines = "1.11.0"`（该版本不存在）；⑤`ksp`/`hilt` 插件声明 + 根 `build.gradle.kts:10-11` `apply false`，但**全仓无任何模块 apply**（死配置） | 删掉未引用条目（`core-runtime 0.0.0` 必删），core/savedstate 校正到实测值，或明确标注「仅供反查参考，不参与构建」 |
| P3-54 | `release.sh:10-11`、`:35`、`:80`；`.gitignore` | ①`GRADLE`/`JAVA_HOME` 写死 `C:/Users/a/.workbuddy-ai/...`（换机即失败）；②`:35` 用 `grep -oE 'versionCode = [0-9]+'` 取第一处，若注释里出现第二处 → 算术表达式语法错误；③`:80` 每版把 31MB APK 写进 git，且 **`apk/app-debug.apk`（38MB）被 git 跟踪**，`.gitignore` 未排除 `*.apk`；④无签名漂移自检 | gradle/JAVA_HOME 改可探测；`grep -c` 断言唯一；`.gitignore` 加 `apk/*.apk` + `git rm --cached apk/app-debug.apk`；补签名自检 |
| P3-55 | `res/values/themes.xml:3-8` | `windowBackground = @android:color/black` 恒黑。应用自管深浅色（默认 Dark），但用户切成浅色后杀进程重开 → **启动画面与首帧是纯黑**，随后 400ms 交叉渐变到浅色；`GlassApp.kt:421-433` 还要 `withFrameNanos` + `view.post` 连打三次才把状态栏图标改对（注释承认 ColorOS 会吃掉启动期设置） | 加 `values-night/themes.xml`，或在 `MainActivity.onCreate` 里按持久化的 `themeMode` 先 `setTheme()` 再 `enableEdgeToEdge()` |
| P3-56 | `ThemeColorPicker.kt:61-72` | 选中态在 3dp `textPrimary`（深色=白/浅色=近黑）外圈之上**又叠了 2dp 白圈** → 深色下只剩 0.5dp 白牙、选中感极弱；浅色下白圈压在浅色玻璃上几乎看不见。且 10 个色点的 `contentDescription` **全是「主题色」**，读屏无法区分 | 选中态只保留一层 3dp 强调描边 + 内缩白色对勾；`PresetSeeds` 改 `List<Pair<String, Color>>` 带色名 |
| P3-57 | `GlassTheme.kt:71-72` vs `:86-90` | `PresetSeeds` 首项 `#7F77DD` 注释为「靛紫（品牌色）」，但 `GlassPalettes.Purple` 是**手调值**（`seed2 #AFA9EC` / `deep #241C4A`）；按 `fromSeed` 实算 #7F77DD 得 `seed2 ≈ #9B94E8`、`deep ≈ #252242` → **色板第一个色 ≠ 品牌靛紫**。用户点它极光略有变化、副标题变「自定义颜色」，却无法再点回「品牌靛紫」 | 色板把「品牌靛紫」作为独立一档（命中 `followCover==false && customSeedArgb==null` 时高亮、点击即 `setCustomSeed(null)`） |
| P3-58 | `SettingsScreen.kt:434-435` | 更新面板 `ProgressTrack(progress = current.progress, onSeek = {})` —— `ProgressTrack` 无条件挂 `detectTapGestures` + `detectHorizontalDragGestures`，拖动会写 `dragProgress` 显示预览、松手调**空回调**后弹回 | 给 `ProgressTrack` 加 `enabled: Boolean = true`（false 时摘掉两个 `pointerInput`） |
| P3-59 | `SettingsScreen.kt:391-393`、`:125-232` | ①当前版本号**只在 `Status.Idle` 分支可见**，点过检查后永久消失（`UpdateManager.state` 不持久化，要重启进程才回 Idle）；②外观组**没有深/浅色开关**，只能回曲库页页头切换（`LibraryScreen.kt:123-131`） | 关于组加常驻「当前版本 vX」；外观组加「深色模式」`GlassToggle` |
| P3-60 | `Lists.kt:349-372` + `SettingsScreen.kt:127-130/321-325/289-292` | `SettingRow` 仅在 `onClick != null` 时挂 `clickable`；这三个开关行只传 `trailing` 不传 `onClick` → **点行文字/图标无反应**，只有右侧 42×25dp 的小开关可点（小于 48dp 无障碍下限） | 三个 `SettingRow` 同时传 `onClick = { toggle }`（外层 `clickable` 与内层 `GlassToggle` 不会双触发） |
| P3-61 | `res/xml/data_extraction_rules.xml:11-16` | 只排除 `domain="file"`，`sharedpref`/`database`/`external` 三个域**未排除**。当前安全（`allowBackup=false` + 无 SP/DB），但将来引入任何 SP/DB 会**静默进云备份**，与本文件「设备本地状态不外流」的意图相反 | 四个域各补一行 `<exclude domain="..." path="." />` |
| P3-62 | `SettingsStore.kt:137/173` vs `:227-286` | 写入了 `"v": 8` 但 `decode()`/`decodeJson()` **从不读 `"v"`** —— 兼容性完全靠字段级 `has()` 判断 → 旧版读新版备份会**静默降级**（未知字段丢弃、缺失字段走默认），且提示「备份已恢复」 | `decode` 里读 `v`，`v > VERSION` 时返回 null（提示「备份来自更新版本，请先升级应用」） |
| P3-63 | `PlayerScreen.kt:342-344`、`SearchScreen.kt:211` | 控制台/Chrome 让位是**瞬时跳变**：`sheetOpen` 是布尔，`alpha` 在 `graphicsLayer` 绘制期直接取值 → 面板 `show()` 同帧变 0，而 `GlassSheet` 上滑入场是 360ms。退场侧要等 `clearContent()` 才复位 → 点「更多/队列」时整块 28dp 玻璃控制台（约 180dp 高）**瞬间消失**，关闭时又「啪」地出现 | `val sheetAlpha by animateFloatAsState(if (sheetOpen) 0f else 1f, GlassMotion.tweenPage())` |
| P3-64 | `PlayerScreen.kt:411-414` + `Chrome.kt:76/89-96/111` | 拖动进度条时**已播时长文本不跟随** —— 拖动期只改 `ProgressTrack` 本地 `dragProgress`，`ElapsedTimeText` 只读 `state.progressSec` → 填充条跟着手指走、左侧时间纹丝不动，松手才跳变；读屏的 `progressBarRangeInfo`（`Chrome.kt:149`）也读旧值 | `ProgressTrack` 加 `onPreview: (Float?) -> Unit`，拖动期驱动时间文本 |
| P3-65 | `PlayerScreen.kt:486` + `:122` | 纯音乐/无歌词时底部提示仍写「左右滑动切换封面 / 歌词」，但 `lyrics == null` → `showLyrics` 恒 false、`modeT` 恒 0 → 滑动**毫无效果** | `val canSwitchMode = lyrics != null`，无歌词时隐藏该行或改文案 |
| P3-66 | `PlayerScreen.kt:158-161` | 无歌词时点封面会把**持久化偏好** `lyricEnabled` 静默改成 `true` —— `showLyrics` 已含 `lyrics != null` 条件，但写回的是纯偏好。用户在设置里关掉歌词，听纯音乐时误点封面 → 偏好被悄悄打开 | `if (lyrics != null) state.lyricEnabled = !showLyrics` |
| P3-67 | `PlayerScreen.kt:603/546-548/527/600` | 歌词「当前行」每帧重设居中目标 → 当前行会**缓慢漂移**（行高在 500ms 内从 24.65 长到 34.8sp，`lineCenters` 每帧变 → effect 每帧取消重启）；且 `fontWeight = if (p > 0.5f) Bold else Normal` 在 p 跨 0.5 时**字重瞬切**，字形宽度微跳 | 居中 effect 的 key 去掉 `lineCenters[current]`，改为 effect 内 `snapshotFlow{}.first{}`；字重改 `FontWeight(lerp(400f, 700f, p))` |
| P3-68 | `PlayerScreen.kt:274` + `:235-238` | `if (lyrics != null && modeT > 0f)` 在**组合期**读动画状态 `modeT` → 模式切换的 500ms 内整个 `BoxWithConstraints` 内容每帧重组（含 `ArtThumb` 的 `getOrDecode` 与全部歌词行） | 条件改为「只要 `lyrics != null` 就保持组合」，alpha 交给已有的 `graphicsLayer` lambda（`:281` 绘制期读） |
| P3-69 | `Sheet.kt:101-104` + `:237-238` + `:149-167` | 「歌曲操作」二次 `modal.show` 是**硬切无动画**：`show()` 只做 `content = ...; visible = true`（值未变），新组合首帧即 `visible=true` → `isIdle && targetState=true` → 不播入场；`snapshotFlow { modal.visible }` 因值未变不发射 → 旧内容直接丢弃不播退场。状态无残留（group key 不同），但视觉是硬切 | 给 `ModalState` 加 `contentKey`，在 `GlassSheet` 里用 `AnimatedContent` 交叉淡化 |
| P3-70 | `M3uPlaylistIO.kt:57-59` | **只按 UTF-8 解码**：`toString(Charsets.UTF_8)` 对非法序列不抛异常而是插入 U+FFFD → 国内 PC 播放器（foobar2000 默认 ANSI / 记事本"ANSI"另存）导出的 GBK 歌单 → 中文路径全变乱码 → `byPath[line]` 永远查不到 → **全部 unmatched**。用户只看到「未匹配到任何曲目」，无法得知是编码问题 | 解码后检测 U+FFFD，超阈值再按 GBK 重解 |
| P3-71 | `M3uPlaylistIO.kt:66/79-80` | 路径匹配**过严**：索引键是 Android 绝对路径，查找键是 M3U 行原样字符串。Windows 反斜杠、`/sdcard/` 符号链接写法、`file://` 前缀、大小写差异全部命中不了。且注释（`:53-54`）写「相对路径（忽略）」，实现是**计入 unmatched** —— UI 显示「N 首未匹配」，引导用户排查不存在的失败 | 建索引与查找都归一化（反斜杠→斜杠、去 `file://`、`/sdcard/`→`/storage/emulated/0/`）；相对路径单独归类为 ignored |
| P3-72 | `M3uPlaylistIO.kt:111-119` + `GlassApp.kt:476-479` | `displayNameOf` 返回**含扩展名的完整文件名**，而 `fallbackNameOf` 做了 `nameWithoutExtension` → 导入无 `#PLAYLIST:` 头的文件时，歌单卡片标题显示「夏日歌单.m3u8」 | `displayNameOf(...)?.substringBeforeLast('.')` |
| P3-73 | `M3uPlaylistIO.kt:101-108` | `suggestedFileName` 字符替换正确，但**无长度上限**：`DISPLAY_NAME` 多数 provider 上限 255 字节，中文 3 字节/字 → 超约 84 个汉字即 provider 异常或截断；`CON/PRN/AUX/NUL/COM1` 等 Windows 保留名会导出成功但拷到 PC 无法落盘 | `take(80)` + 保留名追加下划线 |
| P3-74 | `Lists.kt:189` 注释 vs `:253/266` | 注释承诺「`onRemoveClick` 优先级高于 `onMoreClick`」，**实现相反**（先判 `onMoreClick`）。当前所有调用方只传其一，属**潜伏**契约反转；将来某处同时传两者时，移除按钮消失、只剩 ⋯ | 交换两个 `else if` 分支顺序 |
| P3-75 | `Glass.kt:115-121` | 只给 `onLongClick` 时，条件用 `\|\|` 放行但 `onClick ?: {}` 塞了空实现 → **点一下毫无反馈、但事件被消费**（父级收不到）。目前无此调用方（`PlaylistCard` 恒有 `onClick`），属潜伏 | 拆开条件，或 KDoc 明令禁止该组合 |
| P3-76 | `Field.kt:80` | `keyboardOptions` 硬编码 `ImeAction.Search`，而 `PlaylistScreen.kt:99`（新建歌单）、`ActionsSheet.kt:340`（重命名）都把它当「提交」用 → 命名弹窗的软键盘回车键是**放大镜图标**，按下去却执行「创建/保存」 | 加 `imeAction: ImeAction = ImeAction.Search` 参数，命名场景传 `Done` |
| P3-77 | `Field.kt:70-85` | hint 画在输入框**下方**（`Box(CenterStart)` 里先 `Text` 后 `BasicTextField`）→ 空值 + 已聚焦时，闪烁光标绘制在 hint 第一个字符的**同一像素列**上，首字被光标竖线划穿（`autoFocus=true` 的搜索页首帧最明显） | hint 改用 `decorationBox`，或聚焦时右移 2-3dp |
| P3-78 | `Glass.kt:110-114` + `HazeGlass.kt:29` | **双重填充**：haze 的 `tints` 已用 `fill(level)` 给模糊背景上一层色，`background(glass.fill(level))` 再叠一次 → 带 blur 的 Md 面板等效白 **19%**，不带 blur 的 Md 面板白 **10%**。调用方 `blur` 与 `level` 恒相同，所以对比直接 | 有 blur 时不再叠 `background` |
| P3-79 | `AutoStartGuide.kt:24` vs `:44-72` vs `:94-98` | 品牌表覆盖 **10 个品牌**，但 `CANDIDATES` 只有 OPPO 系三套 → 小米/华为/荣耀/vivo 用户点进去必然落到**系统级电池优化列表**（与"自启动"无关），而 `open()` 返回 `true` → `GlassApp.kt:722-724` 的「未能打开系统设置」提示**永不出现** | 按品牌补候选（小米 `com.miui.securitycenter`、华为 `com.huawei.systemmanager` 等，并同步补 `<queries>`）；兜底页返回后回 UI 提示 |
| P3-80 | `AppState.kt:536` → `QueueStateStore.kt:42` | `queueStateWriter` 传入的 `specs` 是**过滤后**的（`toSpec()` 对 `mediaUri == null` 返回 null），而 `index` 是**未过滤** `queue` 的下标 → 索引偏移 = 被剔除条数。`loadQueueOnEngine`（`:1104-1111`）在同样场景下专门重算了索引，**这里漏了** | 写入前统一换算：`specs.size == queue.size` 时用原索引，否则按当前曲 id 重查 |

---

## 4. 已证伪的怀疑点（避免重复排查）

| # | 曾被提出的问题 | 反证 |
|---|---|---|
| V-1 | `ArtworkBitmapCache.sizeBytes` 非原子 → 竞态/OOM（P1） | **不成立**。该类注释（`ArtworkBitmapCache.kt:15-16`）明确"只在主线程（Composition / 绘制）调用"，唯一调用点 `Artwork.kt:125` 在组合期。单线程无竞态。且 `put()`（`ArtworkBitmapCache.kt:56-63`）已手动兜底驱逐循环，`removeEldestEntry` 只删一个的问题已被覆盖。 |
| V-2 | `byteSize()` 硬编码 ×4 假设 ARGB_8888 错误（P2） | **不成立**。`BitmapFactory.decodeFile` 默认输出 `ARGB_8888`（未传 `inPreferredConfig`），×4 正确。 |
| V-3 | `ArtThumb` 未 recycle → native 内存泄漏（P2） | **不成立**。Compose 的 `ImageBitmap` 由 GC 管理；手动 `recycle()` 反而会在后续绘制时抛 `IllegalStateException`。LRU 淘汰交给 GC 是正确做法。 |
| V-4 | `AuroraBackground` 的 `drawWithCache` 失效键缺 `palette` → 换主题不刷新（P2） | **不成立**。`drawWithCache` 的 block 内读取了 `LocalAuroraPalette.current`（`AuroraBackground.kt:45` 在组合期读，block 内使用），CompositionLocal 变化会让该 block 重新执行。这正是 `drawWithCache` 的设计语义。 |
| V-5 | `LyricRail` 的 `railOffset` 被用了两次（`stageY` 与 `graphicsLayer.translationY`）→ 双重位移 | **不成立**。`positionInParent()`（`PlayerScreen.kt:547`）返回**布局坐标**，不含 `graphicsLayer` 的绘制期平移；`stageY = railOffset + lineCenters[i]`（`PlayerScreen.kt:539`）算的正是"行在屏幕上的真实位置"，用于 `positionalFade` 判断羽化，是正确的坐标系换算。 |
| V-6 | 飞行 snapshot 用 root 坐标、舞台动画用本地坐标 → 落点错位 | **不成立**。两者用途不同：`coverRect`（`PlayerScreen.kt:265-270`）是舞台本地坐标，只喂 `offset{}`；`trackFlight`（`Flight.kt:360-362`）记录 `boundsInRoot()`，只喂飞行插值。互不交叉。 |
| V-7 | `GlassTheme` 三级动画级联导致切换拖尾（旧报告 P3-9） | **已修且确认**。`GlassTheme.kt:249-251` 现在是两层（dark/light 各自动画 + `themeFraction` 混合），第三层 `animatePalette(effectivePalette)` 已移除。 |

---

## 5. 修复优先级建议

### 第一批（半天内，全是不改架构的小改 —— 建议今天全部落地）
1. **P1-4** `MusicPlaybackService.kt:71` 加 `.setSessionActivity(PendingIntent)` —— **一行，修掉"通知点了没反应"**（最高优先，用户感知最直接）
2. **P1-2** `alignQueueWithEngine` 加 `if (ids.isEmpty() && queue.isNotEmpty()) return false` —— 一行，堵住数据销毁
3. **P1-5** `onDispose` 里先 `state.queueStateWriter = null` 再 `shutdown()` —— 一行，堵住崩溃
4. **P1-3** `release.sh` 加签名漂移自检（`apksigner verify --print-certs` 比对上一版）—— 几行 bash，挡住"换机断更"
5. **P2-25** `UpdateUiState` 加 `failStage`，验签失败不给重试按钮 —— 小改动，修掉死循环
6. **P2-1** `loadLibrary` 空库早退 —— 一行改动，修掉幽灵曲库回归
7. **P2-24** `loadLibrary(cached)` 加 `!state.realLibraryLoaded` 门控 —— 一行，修掉启动竞态
8. **P2-29** 扫描入口改用内存态 `state.scanFilterShort` —— 一行，修掉开关不生效
9. **P2-30** `toggleFavorite`/`insertQueueItems` 补占位曲守卫 —— 两行，堵住脏数据落盘
10. **P2-2** 4 处两列补位 —— 每处一行 `Spacer(Modifier.weight(1f))`
11. **P2-4** QueueSheet 空态 —— 移动一行 `SectionLabel`
12. **P2-5** 主题色当前色高亮 —— 改一行传参
13. **P2-8** 搜索历史空态门控 —— 改一行条件
14. **P2-17** `PlaylistDetailScreen` 补传 `dataVersion` —— 一行，堵住不可撤销的错位提交
15. **P2-13** `beginExit` 补 `entryShowCover = false` —— 一行，修掉"歌词永久进不去"
16. **P2-23** 新增 `PlayerFlight.abort()` 并在 `GlassApp.kt:760` 调用 —— 小改动，修掉"被弹回曲库"
17. **P2-31** 搜索框移出 LazyColumn —— 结构性小改，修掉"滚走丢焦点 + 滚回弹键盘"

### 第二批（一天，涉及交互语义决策）
8. **P2-22** 歌单墙两处改读 `resolvePlaylist`（一处改动两个调用点）—— 修掉"卡片 5 首、详情 4 首"
9. **P2-18** `excludedFolders` 提升为视图层统一过滤（改动集中在 `AppState`，注意 `groups()` 缓存键要加排除集指纹）
10. **P2-20** LRC 候选优先级调换（同目录 .lrc 优先，`files/lrc/` 作兜底）—— 一行换序，修掉"歌词永不更新"
11. **P2-21** M3U 路径归一化（反斜杠/盘符/`file://` + basename 兜底）—— 跨播放器迁移功能实际不可用的根因
12. **P2-3** 内建歌单加歌权限（建议选 A：把「＋」入口也限制到 `user-`）
13. **P2-6** `followCover` 与 `customSeedArgb` 的优先级（需产品确认：拨开关是否等于取消自定义）
14. **P2-7** tab 状态提升到 `AppState`（或 `rememberSaveable`）
15. **P2-9** `createPlaylist` 改调 `playlistTitleExists()` 统一口径
16. **P2-11** 迷你条空曲库门控
17. **P2-12** 飞行等待期 `t` 冻结（`PlayerFlightOverlay.kt:67` 一行 + `beginEntry` 复位）
18. **P2-15** 拖拽途经行偏移归零（需引入 `rowEpoch` 或每行 `Animatable`，中等改动）

### 第三批（需引入生命周期管理，单独一轮）
18. **P1-1** MediaController 断连重建 —— 需引入 `onDisconnected` + controller 重建，改动面较大（与 docs 里已记录的"断连不重建"是同一件事，本轮把它从 P2 提到 P1，因为已确认会导致假死）
19. **P2-14** 退出回来源屏 —— 需 `PlayerFlight` 记录 `entrySource`，涉及 5 处硬编码收敛
20. **P2-16** 等待期 1.8s 冻结 —— 需重新设计 `awaitRects` 的预算策略
21. **P2-19** 扫描取消通道 —— 需给 `load()` 加取消探针参数并改造 `GlassApp` 调用点

### 第四批（打磨）
22. P3 全部（34 条），可按文件分批顺手改

---

## 6. 本轮未覆盖的部分

| 项 | 原因 |
|---|---|
| 真机/模拟器验收 | 全程只读代码，所有"用户可见后果"均为静态推演。**强烈建议装机点一遍**：P2-2（两列奇数行）、P2-4（队列空态）、P2-5（色板不高亮）、P2-12/P2-13（飞行与歌词）、P2-15（拖拽跳一行）—— 这几条都是"看一眼就能确认"的，但静态推演与实机可能存在偏差（尤其 P2-2 的 `weight` 在 `Row` 内的实际行为） |
| `update.json` 的 `sha256` 与实际 APK 是否匹配 | 需要构建产物才能验证 |
| 大曲库（万首级）性能实测 | 需要真机 `dumpsys meminfo` |
| `onPlaybackResumption` 丢模式（shuffle/repeat/速度/EQ） | `docs` 已记录且明确"未修"，本轮确认现状属实（`QueueStateStore.kt:31-35` 的 `QueueState` 只有 `specs/index/positionSec`），不重复展开 |
| `CMD_INSERT_SHUFFLE_FIX` 静默丢歌 | 同上，`docs` 已记录。本轮确认：`MediaControllerEngine.kt:259-271` 的 `runCatching` 只 catch 异常、**不检查 `SessionResult.resultCode`**，而 `MusicPlaybackService.kt:162` 无条件返回 `RESULT_SUCCESS` |
| `ReorderableColumn` 对 `LazyColumn` 父级不成立 | 组件前提是「直接父级即 `verticalScroll` 滚动内容」。当前两处调用点（`Sheet.kt:494`、`PlaylistDetailScreen.kt:157`）都是 `verticalScroll` Column，**符合前提**；但若将来把详情页改成 `LazyColumn`，`positionInParent` 与 autoscroll 会立刻失效 |

---

*排查方式：10+ 个并行子智能体分域只读审查（状态层 / 导航宿主 / 播放引擎 / 媒体扫描 / 玻璃组件 / 交互组件 / 飞行与拖拽 / 曲库歌单屏 / 播放搜索屏 / 设置更新链），Lead 逐条复核取证。全程只读，未修改任何源码。*
