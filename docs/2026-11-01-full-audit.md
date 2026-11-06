# 全项目漏洞 / Bug / 逻辑错误 / 显示错误 全面审计报告

> 基线：HEAD `3df1389`（`修复播放页退出动画飞向左上并闪现歌词页`），工作区干净
> 范围：`project/` 全部 Kotlin 源码 35 文件 / 13,513 行 + Manifest / 资源 / Gradle / release.sh / update.json
> 方式：6 个并行只读审计代理按域精读 + Lead 逐条 `file:line` 亲核取证。**全程只读，未修改任何源码**
> 日期：2026-11-01
> 编译校验：`:app:compileDebugKotlin` BUILD SUCCESSFUL（确认基线可构建，非编译期问题）

---

## 0. 结论摘要

| 级别 | 数量 | 含义 |
|---|---|---|
| **P0** | 3 | 安全失效 / 必现卡死 / 用户数据错误 |
| **P1** | 15 | 功能完全失效或用户明显可感知的错误 |
| **P2** | 30 | 边界错误、显示错误、性能与口径不一致 |
| **P3** | 41 | 打磨项、死代码、注释漂移 |
| **已证伪** | 6 | 子代理/历史报告的误报，附反证 |

**最该先修的三件事**

1. **P0-2 权限弹窗取消即永久卡死** —— 首装必现，一行 `null -> Unit` 之失，用户只能杀进程且会反复复现。
2. **P0-3 自动切歌后第一次点暂停会把歌从头重播** —— 后台连播场景高频命中，藏在两个文件的接缝处。
3. **P0-1 release 用公开 debug keystore 签名** —— 应用内更新的签名校验形同虚设，可被第三方伪造覆盖安装。

**一个值得注意的系统性问题**：本工程有大量「注释声称已修 / 实际未做到」的情况。本轮逐条核对历史修复声明，发现 **P3-15（modeT 组合期读）未真正修复**、**v1 迁移分支永远不可能命中**、**曲库页漏掉 P2-④ 与 P3-40 两处同款修复**、**3 处注释写的节流间隔与代码不符**。说明历史修复轮存在「改了一处、漏了同类另一处」的模式。

---

## 1. P0 —— 安全失效 / 必现卡死 / 数据错误

### P0-1 · release 用公开 debug keystore 签名，应用内更新的签名校验形同虚设

| 项 | 内容 |
|---|---|
| 位置 | `app/build.gradle.kts:29`（`signingConfig = signingConfigs.getByName("debug")`）；自认见 `UpdateManager.kt:206-213` |

**证据**

```kotlin
// app/build.gradle.kts:26-31
release {
    applicationIdSuffix = ".debug"
    signingConfig = signingConfigs.getByName("debug")   // ← 公开的 ~/.android/debug.keystore，口令 android
    isMinifyEnabled = false
}
```

`UpdateManager.verifyApkSignature()` 的判据是「待装包签名集合 == **当前包**签名集合」。而当前包本身就是公开 debug 签名 → 任何第三方都能签出同签名包，该判据恒通过。`update.json` 的 sha256 与 APK 走同一条通道（gh-proxy / raw），sha256 只能防传输损坏，防不住「清单 + APK 同时替换」。

**用户可见后果**：恶意构造的更新包可静默覆盖安装，读取曲库、通讯录权限范围内的一切。

**修法**：生成正式 keystore（不入库，走 `keystore.properties`）；`verifyApkSignature` 改为比对**预置的发布证书指纹常量**，而不是「与当前包一致」。

---

### P0-2 · 权限弹窗被取消 → 应用永久卡在「正在整理曲库…」

| 项 | 内容 |
|---|---|
| 位置 | `GlassApp.kt:402-418`（关键行 **417**：`null -> Unit`） |

**证据**

```kotlin
// GlassApp.kt:402-418
when (result[Manifest.permission.READ_MEDIA_AUDIO]) {
    true -> { audioGranted = true; audioDenied = false; loadRealLibrary(); maybeRequestAllFiles() }
    false -> { audioDenied = true; audioDenialShown = true; state.settleLibraryKeepingPending() }
    null -> Unit   // ← 注释说"本次只补申请了通知权限"，但用户按返回取消时也是 null
}
```

`ActivityResultContracts.RequestMultiplePermissions` 在用户**按返回键 / 点击弹窗外部**取消时回调**空 map**，`result[READ_MEDIA_AUDIO]` 为 `null`。于是既不 `settleLibraryKeepingPending()` 也不 `loadLibrary()` → `librarySettled` 永远为 false。

**触发条件**：首装 / 清数据后启动 → 系统音频权限弹窗 → 用户按返回键取消。

**用户可见后果**：应用**永久停在纯文字「正在整理曲库…」**——无 Chrome、无迷你条、无导航、无任何可点元素；此时 `BackHandler(enabled = false)`，按返回键直接退出应用。只能杀进程重启，重启后再次弹窗，**可复现死循环**。

**修法**：`null` 分支不能当 no-op。改为
`if (result[READ_MEDIA_AUDIO] == null) { audioDenied = true; state.settleLibraryKeepingPending() }`，
或启动 effect 末尾加超时兜底 `delay(10_000) { if (!state.librarySettled) state.settleLibraryKeepingPending() }`。

---

### P0-3 · 自动切歌后第一次点「暂停」，音乐不暂停反而从头重播

| 项 | 内容 |
|---|---|
| 位置 | `AppState.kt:531-548`（`syncFromEngine` 全文无 `engineLoadedUri` 赋值）↔ 消费点 `AppState.kt:1042` |

**证据**（两个文件接缝处，单看任一文件都看不出）

```kotlin
// AppState.kt:531-548  syncFromEngine —— 只更新 queueIndex，不动 engineLoadedUri
private fun syncFromEngine(mediaId: String?) {
    ...
    if (index != queueIndex) {
        queueIndex = index
        progressSec = 0f
        liveFormat = null
        rememberPlayed(mediaId)
        scheduleSave()
    }
}

// AppState.kt:1042  togglePlayPause —— 以 uri 判"是否已装载"
when {
    engineLoadedUri != uri -> startCurrent(startPositionSec = progressSec...)  // ← 误判为未装载
    ...
}
```

`engineLoadedUri` 全仓只有 4 处赋值（`attachPlayer:509`、`prepareResume:959`、`startCurrent:1200`、`loadLibrary:906`），**`syncFromEngine` 不在其中**。

**触发条件**：服务端自然完播自动切到下一首（后台连播 / 锁屏最常发生），此时 `isPlaying` 仍 true 无 `onPlayingChanged` 回调；随后用户回 App 点暂停。

**用户可见后果**：
- 点暂停**不暂停**，当前曲从 0 秒重新开始播放，进度条跳回 0；必须再点一次才真暂停。**每次自动切歌后复现一次**。
- 万首队列时该分支在主线程重建 1 万个 `MediaItem`，明显卡顿。
- `effectiveDurationSec()`（`AppState.kt:421-429`）同样因 uri 不等而回退扫描时长 → 元数据缺失曲（扫描兜底 1s）的进度条卡在 1 秒、歌词停在首行。

**修法**：`syncFromEngine` 里补 `queue.getOrNull(index)?.let { engineLoadedUri = it.mediaUri }`；或把三处判据统一改成 `engine.currentMediaId() == currentSong.id`。

---

## 2. P1 —— 功能完全失效 / 明显可感知错误

| # | 问题 | 位置 | 触发条件 | 用户可见后果 | 修法 |
|---|---|---|---|---|---|
| 1 | **播放服务断连后播放键彻底假死，无恢复路径**：`MediaController.Builder` 全程无 `.setListener`（全仓 `onDisconnected` 零命中）；`loadQueue()` 是引擎里**唯一**没有 `checkConnected()` 守卫的方法；`onError` 清 `engineLoadedUri` → `tick()` 首行 `if (!playing) return` → 心跳停 | `GlassApp.kt:473-493`、`MediaControllerEngine.kt:146-156`、`AppState.kt:492-497`、`AppState.kt:965-966` | 暂停态下系统回收播放服务（`onTaskRemoved` → `stopSelf()`） | media3 收到 `onDisconnected` 后**自动 `MediaController.release()`**，此后所有命令静默返回 -4。点播放/切歌/拖进度全部无反应，`startCurrent()` 仍把 `playing` 置 true → UI 显示在播、无声、无提示，只能杀进程 | ① Builder 加 `.setListener(onDisconnected = { 重建 controller 并 attachPlayer })`；② `loadQueue()` 首行加 `if (!checkConnected()) return`；③ `onError` 不清 `engineLoadedUri`，改置 `engineBroken` 标志保住心跳 |
| 2 | **「过滤文件夹 / 扫描过滤」对全部歌单不生效**：`resolvePlaylist` 走 `songById` → `songIndex()` 由**原始 `library`** 建索引，完全绕过 `visibleLibrary()` 输出层过滤 | `AppState.kt:1729`（`val lib = library`）、`1739-1744`、`1747-1779` | 排除某目录 / 开启短音频过滤后，进「我喜欢的音乐 / 最近播放 / 最常播放」或任一歌单详情 | 被排除目录里的歌、<30s 短音频**仍出现在歌单里且能播放**；曲库页已看不到但歌单里还在。正是 `AppState.kt:1586-1587` 注释自称"已根除"的那类"设置只在 1/6 界面生效"缺陷 | `songById` 增加 `visibleLibrary()` 口径（或建两套索引：全量给恢复链路、可见给展示） |
| 3 | **定时关闭在 Activity 重建后「延长整档」或「完全不武装」**：`restore()` 按 deadline 门控恢复了 mode，但重下发用**原始分钟数**而非剩余时间；且 restore 早于 `attachPlayer` 时 `engine == null` 为 no-op，而 `syncEngineModes` 不含 `setSleepTimer` | `AppState.kt:717-722`、`755-756`、`552-557` | 设 15 分钟定时 → 改系统字体大小（`fontScale` 不在 manifest `configChanges` 里）→ Activity 重建 | 两种错法：engine 已就绪 → 定时被**延长成再等 15 分钟**（UI 倒计时显示剩余几分钟，到 0 音乐继续放）；engine 未就绪 → 服务端**永不暂停**，UI 显示「已开启」但到点不停 | 重发用剩余时间 `((deadline-now)/60000).coerceAtLeast(1)`，到期下发 0；`syncEngineModes` 补一次 `setSleepTimer` |
| 4 | **横屏右栏写死 380dp**，左栏只有 `weight(1f)` 吃剩余宽度 | `PlayerScreen.kt:571-573` | 横屏窗口宽 ≤ ~460dp：手机横屏分屏（915×412 → 每侧 457dp）、自由窗口、折叠机外屏 | 457dp 时舞台只剩 77dp → 封面 77dp、歌词每行挤成 2~3 字竖排，**歌词端不可用**；窗口 ≤380dp 时 `.width(380.dp)` 被父约束 coerce 到整行宽，**左栏归零** → 封面/歌词/`modeSwipe` 全部消失 | 右栏改 `widthIn(min = 300.dp, max = 380.dp)` 或 `fillMaxWidth(0.42f)`；左栏保底 `widthIn(min = 200.dp)` |
| 5 | **横屏下 `stageBlock` 内的 `Modifier.weight(1f)` 被 Box 父节点静默忽略** | `PlayerScreen.kt:269`（weight）、调用点 `565-570`（父是 `Box` 不是 `Column`） | 横屏 + 无歌词（纯音乐 / 无 .lrc）；有歌词时被 `LyricRail(fillMaxSize)` 掩盖 | `weight` 是纯 parentData：落 Column 生效、落 Box 丢弃。`BoxWithConstraints` 退化为按内容尺寸 → 挂在其上的 `modeSwipe()` 手势区同步缩到封面大小，窄横屏下只剩 77×77dp 可滑，**舞台其余区域左右滑切换封面/歌词完全失效** | stageBlock 内改 `.fillMaxSize()`（尺寸由父决定），weight 只留在调用点；竖屏用 `Box(Modifier.weight(1f))` 外包承接权重 |
| 6 | **歌词当前行在组合期读 `progressSec`** → 整个舞台 10Hz 重组 | `PlayerScreen.kt:313`（`current = state.currentLyricIndex`），`AppState.kt:462-463` getter 内部读 `progressSec` | 任意播放中（进度在走） | 快照失效按「读」触发 → **整个舞台每 100ms 重组**：`BoxWithConstraints` 重新子组合 + 30~40 行歌词 + 封面 `ArtThumb`。与本文件 307-308 行"旧写法让整个舞台每帧重组"的自我约束自相矛盾，也与 `Chrome.kt` 的 10Hz 隔离体系不一致。表现为歌词滚动掉帧、长时间播放发热 | `val currentLyric by remember { derivedStateOf { state.currentLyricIndex } }` 后传给 `LyricRail(current = currentLyric)` |
| 7 | **曲库页 `items(songs, key = { it.id })` 未做 `distinctBy`** | `LibraryScreen.kt:182`；对照 `SearchScreen.kt:76`、`PlaylistDetailScreen.kt:190` 都做了 | 缓存/扫描产生重复 id（MediaStore 同文件多条记录、缓存被改坏） | LazyColumn 抛 `IllegalArgumentException: Key ... was already used` → **曲库页硬崩**。三处同类防护唯独曲库页漏掉 | 补 `remember(songs) { songs.distinctBy { it.id } }`，`items` 与 `playSong` 统一用去重序列 |
| 8 | **曲库页 `remember` key 漏 `excludedFolders`**（`mutableStateSetOf` 元素变化不换引用） | `LibraryScreen.kt:91`；`AppState.kt:360`、`1603-1617` | 设置页勾选/取消「过滤文件夹」→ 返回曲库页「歌曲」tab | **歌曲 tab 列表陈旧**（仍显示已排除目录的歌），而专辑/艺术家/文件夹/流派 tab 裸调 `groups()` 正常刷新 → 同一页面 6 个 tab 口径互相打脸 | key 补 `state.excludedFolders.toSet()`；`SearchScreen.kt:71-72`、`ActionsSheet.kt:429` 同改 |
| 9 | **权限被撤销后不降级**：ON_RESUME 只处理「授权 0→1」，且 `loadCache()` 读私有文件**不需要权限**，无条件 `loadLibrary` | `GlassApp.kt:372-393`、`338-347` | 用户在系统设置撤销 READ_MEDIA_AUDIO 后返回应用（或撤销后冷启动） | 曲库页/搜索页照常列出全部歌曲（来自缓存），点击任一首播放失败报错；空态与「去授权」入口都不出现，用户无从得知是权限问题 | 撤销分支加降级；`loadCache` 前先查权限，无权限不装载 |
| 10 | **`minSdk = 35`（Android 15）过窄** | `gradle/libs.versions.toml:13` | 任何 Android 14 及以下设备 | 侧载/商店均提示「不兼容」装不上；代码中 `Build.VERSION.SDK_INT >= P` 等兼容分支全成死代码 | 降到 26~29（工程未用 35 独有 API） |
| 11 | **release 变体带 `applicationIdSuffix = ".debug"`**，`defaultConfig` 的 `applicationId = "com.local.music"` 从未生效 | `app/build.gradle.kts:14` vs `:28` | 每次发版 | 正式包 ID 是 `com.local.music.debug`，被识别为调试包；将来切回正式 ID = 新沙盒，DataStore/缓存/收藏全丢 | 移除 release 的 `applicationIdSuffix` |
| 12 | **内嵌封面解码缺 bounds 无效保护**：`inJustDecodeBounds` 后未判 `outWidth<=0`，直接以 `sample=1` 全尺寸解码 | `MediaLibrary.kt:634-639` | 内嵌 3000×3000+ 扫描图且 bounds 解析失败（异常 JPEG / 非标准容器） | 单张解码 ≈36MB，**扫描线程 OOM 崩溃**。同文件 `decodeSampledCover:489` 有该保护，此处漏了 | `:635` 后补 `if (bounds.outWidth <= 0 \|\| bounds.outHeight <= 0) return null` |
| 13 | **`ArtworkBitmapCache` 无任何失效机制**：缓存键是 `file://…c<sha>.png`，而换图后 `persistCover` **覆写同一路径**（键不变）；`clear()` 定义了但全仓零调用 | `ArtworkBitmapCache.kt:67`、`MediaLibrary.kt:550-555` | 用 tag 编辑器替换内嵌封面，或换 `cover.jpg` 后重扫 | 列表/播放页/通知栏继续显示**旧封面**，必须杀进程重启才更新（`561-563` 的 mtime 盖戳还会让下次扫描跳过重写，进一步固化旧图） | 缓存键并入文件 mtime+size，或扫描 `sweepCovers` 后调 `ArtworkBitmapCache.clear()` |
| 14 | **`loadCache` 在 library 层预过滤短音频**，与"扫描恒全量、过滤只在 `visibleLibrary()` 输出层"的设计冲突 | `MediaLibrary.kt:878-882`；扫描侧 `GlassApp.kt:236` 传 `filterShort = false` | 冷启动读缓存时开关为 ON，随后后台扫描失败（权限被撤 / MediaStore 异常） | `library` 里根本没有短音频条目 → 用户**关掉「过滤短音频」开关不生效**，必须重扫。B3 声称的"两方向即时生效、零重扫"在该路径不成立 | `loadCache` 也改恒全量，过滤只留 `visibleLibrary()` 一处 |
| 15 | **`snapshot()` 在 `Dispatchers.IO` 线程被调用**，遍历 `SnapshotStateList/Map/Set`（`favorites.toSet()`、`playCounts.toMap()` 等） | `GlassApp.kt:586`（`withContext(Dispatchers.IO) { encodeForBackup(state.snapshot()) }`） | 设置页「导出备份」 | 跨线程读 Compose 快照状态无 `Snapshot.takeSnapshot()`：全局快照正被主线程改动时可抛 `IllegalStateException`/`ConcurrentModificationException`，或导出半截不一致的快照 | 先在主线程取 `val snap = state.snapshot()`，再把纯数据传进 IO 块编码 |

---

## 3. P2 —— 边界错误 / 显示错误 / 性能与口径不一致

### 3.1 播放引擎与后台服务

| # | 问题 | 位置 | 后果 |
|---|---|---|---|
| 1 | **队列被清空后 `queue_state.json` 永不清理 → 幽灵续播**：`shouldWriteQueueState` 对空队列直接 `return false`，旧现场文件原地保留；`QueueStateStore.clear()` 全仓零调用点 | `AppState.kt:603-611`、`883-892`、`QueueStateStore.kt:121-125` | 删光音乐 → 进程被杀 → 从通知栏/蓝牙点播放，系统把**已删除的曲目**整队恢复出来，全部报「文件已丢失」 |
| 2 | **M3U 导入把 N 次全量快照 + N 次队列 JSON 写盘压在主线程**：导入回调 `forEach { addToPlaylist }` 无分批节流，每次 `addToPlaylist` 都 `scheduleSave()`；暂停态下 `shouldWriteQueueState` 恒 true | `GlassApp.kt:554-572`、`AppState.kt:1389-1403`、`609` | 暂停态导入几百/上千行 M3U（跨播放器迁移典型场景）→ UI 长时间卡顿，后台堆 N 个整份 JSON 写盘 |
| 3 | **shuffle 下 `addQueueItems` 的自定义命令失败完全不可见**：EQ/定时两条同类命令都读 future 结果并 `Log.w`，唯独插入修正不读；media3 在 Binder 侧把 `RemoteException`（含 `TransactionTooLargeException`）吞成 `SessionResult(-100)`，`runCatching{}.onFailure{}` 兜底永不触发 | `MediaControllerEngine.kt:257-275` vs `171-192`/`194-216`；`MusicPlaybackService.kt:164-178` | 大集合（整库「下一首播放全部」，Bundle 有 1MB 上限）插入被丢弃但 UI 队列已 `addAll` → 队列面板列出「下一首播放」的歌，实际播到的是别的歌，且无任何日志 |
| 4 | **均衡器不监听音频会话变化**：`ensureEqualizer` 只在 `STATE_READY` 与 EQ 命令里触发；media3 1.11.1 有 `onAudioSessionIdChanged(int)` 但未实现 | `MusicPlaybackService.kt:185-197`、`297-305` | 播放中连接/断开蓝牙或有线耳机（audioSessionId 变化）且播放器未离开 READY → 已选 Pop/Rock 预设**听感上突然失效**，用户认为「EQ 时好时坏」 |
| 5 | **`MediaControllerEngine` 队列编辑与查询接口无 `checkConnected()` 保护**，而 play/pause/seek 都有 | `MediaControllerEngine.kt:277-286` vs `223-233` | 断连后 `controller.addMediaItems/moveMediaItem/mediaItemCount` 在已释放 controller 上抛 `IllegalStateException`，**崩在 UI 线程**（`alignQueueWithEngine` 的 `eng.mediaIds()` 也在其中） |
| 6 | **定时关闭恢复归零时不向服务端下发 0** | `AppState.kt:717-722` ↔ `756` | 先设「播完当前首」，再从备份恢复 sleepTimerMode=0 的快照 → 面板显示「未开启定时」，当前曲自然播完却被无故暂停 |

### 3.2 状态机与持久化

| # | 问题 | 位置 | 后果 |
|---|---|---|---|
| 7 | **`alignQueueWithEngine()` 返回 false 时仍修改 UI 队列**，不改引擎队列 | `AppState.kt:1336-1342`、`1345-1356`、`1359-1364` | 播放中文件被外部删除 → 用户删掉的队列项，下一次 `syncFromEngine` **又整条回来**，删除/重排静默失效 |
| 8 | **快照损坏时 `hydrated` 保持 false → 连独立的 queue_state.json 也停写** | `AppState.kt:566-567`（早退）→ `591`（writer 在其后） | 快照损坏期间进程被杀，通知栏/蓝牙续播现场也丢失（本应互不影响的 C4 链路被一并冻住） |
| 9 | **`playCounts` 无上限增长**：只增不删（失效 id 永不清理），每次 `scheduleSave` 整份 `toMap()` 复制并序列化 | `AppState.kt:1820-1825`、`686`；`SettingsStore.kt:410-413` | 长期使用 + 频繁删歌 → 快照 JSON 单调膨胀，20s 一次的整份写盘变大 |
| 10 | **`queueSource` 在 `loadLibrary` 换队列时不清空** | `AppState.kt:938`、`917` | 从歌单「深夜巡航」播放后重扫 → 队列已变成整库，播放页仍显示「来自『深夜巡航』」 |
| 11 | **`bumpPlayCount` 不触发存盘**，靠 20s tick 兜底；单曲循环分支尤其确定不存 | `AppState.kt:1820-1825`、`1152-1155` | 计数 +1 后 20s 内进程被杀则丢失该次计数 |
| 12 | **专辑/艺术家/流派/文件夹按显示名字符串 `groupBy`，无去重键** | `AppState.kt:1656-1666` | 两个不同艺术家各有同名专辑（如两张「Greatest Hits」）→ 被合并成一张卡、详情页曲目混杂；`DetailTarget(Album, "Greatest Hits")` 无法区分 |

### 3.3 界面与导航

| # | 问题 | 位置 | 后果 |
|---|---|---|---|
| 13 | **「导出 M3U 歌单」死入口**：菜单项只判 `tracks.isNotEmpty()`，不判 `onExportM3u != null`；歌单墙/曲库歌单 tab 的调用点未传该回调 | `ActionsSheet.kt:321-332`；`PlaylistScreen.kt:74-88`、`LibraryScreen.kt:111-124`（`GlassApp.kt:755` 只给详情页接线） | 长按歌单卡打开集合面板 → 出现「导出 M3U 歌单」→ 点击后**面板关闭、什么都没发生**（无文件、无提示） |
| 14 | **`AddSongsSheet` 的 LazyColumn key 未去重**，与同仓已修的 P3-40 口径不一致 | `ActionsSheet.kt:451-452` | 曲库出现重复 id 时打开「添加歌曲」面板 → `Key was already used` **直接崩溃** |
| 15 | **`GlassMotion.scale` 是普通 `var` 不是 Compose State**，只由 `LaunchedEffect` 赋值；而 `tweenPage/tweenPress/tweenHover` 在组合期读取它生成 spec | `GlassTheme.kt:172`、`181-186`；`GlassApp.kt:503-505`；`ReorderableList.kt:192`、`311` | ① 切换「动效强度」后**当前已组合的动画不生效**；② **启动首帧恒按 ×1.0**（LaunchedEffect 在组合后跑），持久化档位首帧无效 |
| 16 | **`deletePlaylist` 没有 `dynamicIds` 守卫**，且与 UI 判定口径不同源（UI 用 `dynamicIds`，状态层用 `startsWith("user-")`） | `AppState.kt:1458-1466` vs `ActionsSheet.kt:335-337` | 绕过 UI 的调用路径下动态歌单删除静默无操作但 `scheduleSave()` 照跑；两套可写判定并存是"漏改一处"的温床 |
| 17 | **6 个 tab 共用一个 `LazyListState`**，切 tab 不重置也不保存分 tab 滚动位置 | `LibraryScreen.kt:83`、`130`、`174-338` | 歌曲 tab 滚到中部 → 切「专辑」（项数少）→ 切回：**位置丢失**，长曲库下每次切 tab 都要重新找位置 |
| 18 | **纯音乐提示文案用 `offset` 从封面底边 +20dp 定位，无底部边界钳制** | `PlayerScreen.kt:357-368` | 横屏（720×360：`coverRect.bottom≈253.5`，文字 top≈273.5dp，舞台高 284dp）→ **下缘溢出舞台约 8dp**，压在屏幕底边/导航条；竖屏小高度机型同样溢出并压到控制台顶部 |
| 19 | **空曲库仍可进入播放页**，且文案/数值自相矛盾 | `PlayerScreen.kt:544-548`、`909-916`；`GlassApp.kt:860-875`（迷你条 onClick 无 `hasSongs` 门控） | 无权限/空库时点悬浮迷你条 → 播放页显示「暂无歌曲」+ 占位封面 + 底部提示「**纯音乐，请欣赏**」（语义错误）+ 总时长「00:01」，收藏/队列/随机/循环点了全无反应 |
| 20 | **`modeT` 仍在组合期参与几何计算**，与 307-308 行注释「淡入淡出交给 graphicsLayer 绘制期读」不符 | `PlayerScreen.kt:299-305`、`305`、`344` | 每次点封面/左右滑切换 → **500ms 内整个舞台每帧重组**。P3-15 只去掉了条件读，矩形/圆角/阴影三个组合期读点没下沉，等于该问题未真正修复 |
| 21 | **搜索无防抖**，每次击键同步在组合期跑两遍全库扫描；`searchAlbumGroups` 还有 `g.songs.any { ... }` 对每组全遍历 | `SearchScreen.kt:98`、`71-72`；`AppState.kt:1689-1695`、`1702-1712` | 大曲库连续输入 → 主线程每键 O(n)+O(g×m) `String.contains`，低端机输入掉帧、键盘回显滞后 |
| 22 | **歌曲 tab 空态文案不区分「真没歌」/「全被排除」/「全被短音频过滤」** | `LibraryScreen.kt:435-444` | 用户排除唯一音乐目录 → 显示「曲库里还没有歌曲 · 去设置页扫描音乐」→ 按提示重扫**仍然空**（根因是排除/过滤），反复无效操作 |
| 23 | **`albumCountByArtist` 的 remember key 只有 `state.library`**，漏 `scanFilterShort` 与排除集 | `LibraryScreen.kt:96` | 艺术家行 meta 出现「12 首 · 0 张专辑」这类自相矛盾计数 |
| 24 | **`AddToPlaylistSheet` 计数口径与歌单墙不一致**：内建走 `visibleBuiltInPlaylists()`（已 resolve），用户歌单直接用 `userPlaylists`（含失效 id） | `ActionsSheet.kt:157` vs `PlaylistScreen.kt:140-141` | 同一歌单在「加入歌单」面板显示「5 首」，歌单墙显示「4 首」 |
| 25 | **动态歌单仍有 3 处独立枚举**，`DemoCatalog.kt:60` 的 KDoc 承诺（"新增时把 id 加进这里即可"）不成立 | `DemoCatalog.kt:48-62`；`AppState.kt:1446`、`1749-1771` | 将来新增第 4 个动态歌单只改 `dynamicIds` → 能被新建/重命名成同名，且 `resolvePlaylist` 返回 null → 详情页直接 `backFromDetail()` 弹回，卡点"点进去闪回" |
| 26 | **`AppState` 是 `remember {}`，非 ViewModel/Saveable**；`screen`/`detail`/`searchQuery` 都不进快照 | `GlassApp.kt:121`、`AppState.kt:219`、`MainActivity.kt:13` | 字号/显示大小变更（`fontScale`/`density` 不在 `configChanges`）→ Activity 重建 → 用户从播放页/详情页被弹回曲库首页，tab、搜索词、详情目标全丢 |
| 27 | **扫描被取消时半截结果仍替换内存曲库** | `GlassApp.kt:245-246`；守卫仅覆盖缓存 `MediaLibrary.kt:982` | 扫描中途 Activity 重建 → 曲库暂时只剩前半部分，歌单/队列里后半部分"消失"，直到下次重扫 |
| 28 | **`readCacheEntries` 混用严格 `getString`/`getInt` 与宽松 `optXxx`**；单条记录缺字段即抛 `JSONException`，被外层 `runCatching` 吞成空列表 | `MediaLibrary.kt:917-921`、`892-957` | 一条坏记录废掉**整份**缓存 → 冷启动退化为全量重扫（十几秒），且无日志 |
| 29 | **内嵌封面 `inSampleSize` 只按宽度算，不判高度** | `MediaLibrary.kt:637` | 竖版封面（500×3000）降采样后仍 250×1500 ≈1.5MB/张（`decodeSampledCover:491-492` 与 `ArtworkBitmapCache.kt:80-82` 都是宽**或**高双条件） |
| 30 | **`MusicPlaybackService` 声明 `exported="true"` 且 `onConnect` 无条件 accept**，无包名白名单 | `AndroidManifest.xml:70`；`MusicPlaybackService.kt` `onConnect` | 任意第三方应用 `MediaController.Builder` 连接即可读取当前播放曲目元数据、任意控制播放/切歌 |

---

## 4. P3 —— 打磨项（摘要）

| 域 | 条目 |
|---|---|
| **构建/发版** | `release.sh:51-52` 的 `sed` 改写版本号无行锚定（将来加注释/flavor 会误伤）；push 失败回滚只还原 `build.gradle.kts`，`update.json` 与 `apk/` 改动留在工作区；`Bitmap.CompressFormat.PNG` 配 `quality=90`（PNG 无损，quality 被忽略 → 256² 约 100~200KB/张）；`isMinifyEnabled = false`（APK 31MB，无混淆无裁剪）；`attributionTags="media_playback"` 已声明但全仓无 `setAttributionTag` 调用 |
| **状态/持久化** | `SettingsStore.kt:502-504` **v1 迁移分支永远不可能命中**（分隔符写成字面量 `"\\u001F"` 6 字符而非 `"\u001F"` 0x1F）→ v1 用户数据被判 Corrupt；`visibleLibraryCache` 指纹 `size*31 + hashCode*2` 存在碰撞可能；`readScanFilterShort()` 死代码；`VERSION = 9` 但类注释仍写 v6；`createPlaylist` 用 `System.currentTimeMillis()` 做 id（同毫秒撞 id → 歌单墙 LazyColumn key 重复崩溃）；`playSong` 用 data class 值相等 `indexOf`，队列允许重复项时点第 2 个副本实际播第 1 个；定时关闭过期后 mode 不归零；`markHydrated`/`markLibrarySettled` 不 `scheduleSave` 而 `settleLibraryKeepingPending` 会；`renamePlaylist` 把「仅改大小写」判为重名而静默拒绝；`loadLibrary` 非空分支全程不 `scheduleSave` |
| **播放页** | 顶栏来源文案无 `maxLines`/`overflow`（长歌单名换行撑高顶栏，横屏抢走舞台高度）；进度轨端点圆点被 `coerceIn` 内缩 6dp（0% 时圆点不在起点）；横屏右栏无滚动（极矮窗口控制键被裁掉且无法滚到） |
| **歌单/面板** | 集合面板 4 个播放入口在 tracks 为空时仍可点（点了面板关闭、队列没变）；`AddToPlaylistSheet` 无空态；`contentHeightPx` 死代码；歌单详情 `ReorderableColumn` 未开 autoscroll（长歌单拖不到远处槽位）；歌单墙用 `Column+verticalScroll` 全量组合（几十个歌单时首帧卡顿）；`QueueSheet` 当前曲定位 `labelPx = 34.dp` 是估算值（实际 ≈29-31dp） |
| **设置页** | 定时倒计时 `LaunchedEffect` 无定时时也每秒空转并写 state（1Hz 无意义重组）；「检查更新」在 `Failed && failStage == Verify` 时点击完全无反应 |
| **曲库/扫描** | 占位封面 `fallbackArt(songs.size)` 用"已加入数量"当索引 → 重扫后同曲 `art` 可能变化（占位色跳变）；`MIN_TRACK_SEC` 注释与实现不符；增量指纹盲区（`DATE_MODIFIED` 秒级 + `SIZE`，tag 工具保留 mtime 且大小不变则指纹不变） |
| **更新** | `install()` 内 `startActivity` 无 try/catch（对比 `GlassApp.kt:194` 有 `runCatching`）→ 无包安装器时崩到桌面；`ArtworkBitmapCache.removeEldestEntry` 判据滞后（`sizeBytes` 在 `put()` 之后累加，比较的是旧值 → 实为无效代码） |
| **歌词** | LRC 读取 `readText()` 默认 UTF-8 无 GBK 兜底（国内千千静听/旧 foobar 导出存量极大）→ 整篇乱码且无提示。同项目 M3U 已修同类问题 |
| **M3U** | 导入结果提示丢弃 `ignored`（相对路径行），只汇总 `unmatched` → 用户以为全导入了；`insertWithShuffleFix` 提前 return 时服务仍回 `RESULT_SUCCESS` |
| **接口/注释** | `PlayerEngine.release()` 是死接口（全仓无调用点），`AppState.engine` 也永不置空；3 处注释与代码不符（「每 5s」实际 20s、「不持久化」实际已持久化）；`SearchScreen` 搜索历史去重大小写敏感；`backFromDetail()` 里 `if (detailReturn == Detail)` 是不可达死分支 |

---

## 5. 已证伪（避免误修）

| # | 曾被主张的问题 | 反证 |
|---|---|---|
| 1 | **历史 P1-2「队列编辑销毁播放现场」** | 前提是「断连时 `mediaIds()` 返回空 → `0 == 0` 放行 → `queue = emptyList()`」。但 javap 核对 media3 1.11.1：`getMediaItemCount()` → `getCurrentTimeline()` → `playerInfo.timeline`，而 `playerInfo` 只在构造和 `onConnected` 赋值；断连走 `MediaController.release()` 只清 `iSession`、**不动 `playerInfo`** → 返回陈旧队列而非空表。加之 `onError` 已把 `engineLoadedUri` 置 null → `engineDriving()` 为 false，编辑根本进不去 `alignQueueWithEngine()`。**该条在 1.11.1 上不成立** |
| 2 | **`Modifier.weight(1f)` 抽成独立 lambda 后作用域失效（编译层面）** | 能编译：lambda 词法捕获外层 `ColumnScope` 隐式接收者，`weight()` 返回 `LayoutWeightElement`（纯 parentData，不绑定 scope 实例）。**运行期**才在 Box 父节点下被丢弃 → 见 P1-5，属运行期而非编译期问题 |
| 3 | **alpha 相乘链可能出负数或 >1** | `flight.softAlpha()`/`playerSharedAlpha()`/`miniSharedAlpha()`/`navAlpha()` 全部 `coerceIn(0f,1f)`；`sheetAlpha` 来自 `animateFloatAsState(0f..1f)`；相乘 ∈ [0,1] ✅ |
| 4 | **面板 `onDismiss` 可能被调用两次 / 重复打开出问题** | `ModalState.requestDismiss()` 幂等（只写 `visible=false`）；scrim 与动作行各一次；面板体内层 `clickable {}` 已吃掉透传；返回键在 GlassApp 统一先关面板 ✅ |
| 5 | **`update.json` 与实际 APK 不一致 / FileProvider authority 不匹配** | 实测 `sha256(apk/app-release.apk)` = `86fd9095…afc9`，与 `update.json:8` 逐字节一致；`${context.packageName}.fileprovider`（`UpdateManager.kt:197`）= manifest `${applicationId}.fileprovider`，`file_paths.xml:3` 与 `File(filesDir, "update/app-update.apk")` 对应 ✅ |
| 6 | **全库预解码位图常驻 OOM / `MediaExtractor`、`MediaMetadataRetriever` 未释放 / 32 位 hashCode 串图** | 已改为按需解码 + 24MB LRU（`MediaLibrary.kt:228`/`289`/`929`、`ArtworkBitmapCache.kt:21`）；两者都在 `finally` 中 release 且覆盖 `setDataSource` 抛异常路径（`654-656`/`681-683`）；已改 SHA-256 前 8 字节（`385-394`）✅ |

---

## 6. 历史修复复核（逐条验证"注释声称已修"）

### 已确认修好 ✅

空队列守卫（`tick`/`next`/`prev`/`seek`/`toggle`/`startCurrent` 六处）｜placeholder 脏数据守卫（计数/收藏/入队四处）｜`markLibrarySettled` 与 `settleLibraryKeepingPending` 语义分离｜空库真的清空 library｜P2-④ 过滤下沉到 `visibleLibrary()`｜`songById` O(1) 索引化｜详情页 `derivedStateOf` 消除 10Hz 全库派生｜sleepTimer v9 持久化｜DataStore 损坏「删文件+重建」双死锁｜快照三态（Absent/Ok/Corrupt）｜`playCounts` 三态｜Flight 状态机代际守卫与双 Job 取消｜`entryShowCover` 延迟清理｜Chrome 的 10Hz 隔离体系｜动态歌单 `dynamicIds` 判定层统一｜内建歌单计数实时化｜重命名查重｜删除两步确认｜拖拽 `dataVersion` 双闸｜面板退场 `MutableTransitionState.isIdle`｜`sessionActivity` 已设｜`queueStateWriter` 先解绑再 shutdown｜封面键迁移链与 `hadCoverRecord` 原始字段判定｜孤儿封面清理

### 只修了一半 / 未真正修复 ❌

| 声称 | 实际 |
|---|---|
| P3-15「modeT 组合期读已消除」 | **未真正消除**：条件读去掉了，但 `PlayerScreen.kt:299-305`/`305`/`344` 三处几何仍组合期读 → 见 P2-20 |
| P2-④「`scanFilterShort` 必须进 remember key」 | 歌曲 tab / 搜索 / 添加歌曲三处都进了，但**都漏了 `excludedFolders`** → 见 P1-8 |
| P3-40「LazyColumn key 重复需 `distinctBy`」 | 搜索页、详情页已做；**曲库页与添加歌曲面板漏了** → 见 P1-7 / P2-14 |
| P2-26「定时关闭重建后不失效」 | 持久化+deadline 门控已做，但重武装用整档分钟数 → 变成「延长」或「不武装」→ 见 P1-3 |
| P1-2「队列编辑销毁播放现场」 | 历史建议的一行空集守卫**未落地**（`AppState.kt:1299-1308` 仍无），只是触发链被 media3 行为改变（见已证伪 #1） |
| P1-1「断连后播放键失效」 | **仍存在**，且成因链更彻底（media3 自动 `release()`）→ 见 P1-1 |
| v1 旧格式迁移 | **永远不可能命中**（分隔符写成字面量反斜杠串）→ 见 P3 |

---

## 7. 建议修复顺序

**第一批（发版阻塞，改动都很小）**
1. P0-2 权限取消分支（1 行 + 可选超时兜底）
2. P0-3 `syncFromEngine` 补 `engineLoadedUri`（1 行）
3. P1-1 断连三件套（Builder listener + `loadQueue` 守卫 + `onError` 不清 uri）
4. P1-11 移除 release 的 `applicationIdSuffix`
5. P1-10 `minSdk` 降到 29
6. P0-1 正式 keystore + 预置证书指纹校验

**第二批（用户直接可感知）**

P1-2 歌单过滤口径（改 `songById`）｜P1-6 歌词 `derivedStateOf`（1 行）｜P1-7 曲库页 `distinctBy`（1 行）｜P1-8 remember key 补排除集（3 处）｜P1-3 定时关闭重发剩余时间｜P1-4/P1-5 横屏双栏两处硬伤（建议一起改）｜P1-12 封面 bounds 守卫（1 行）｜P1-13 封面缓存失效｜P1-15 备份导出移到主线程取快照

**第三批**：P2 全部 + P3 中涉及崩溃/数据的一批（v1 迁移分隔符、`createPlaylist` 撞 id、`JSONException` 吞整份缓存、`startActivity` 无 try-catch）

---

## 8. 方法论说明与存疑

- 本轮 6 个代理各自独立精读所辖文件，**未参考彼此结论**；Lead 对每条 P0/P1 亲核源码后写入本报告。
- 引擎域代理对 media3 1.11.1 的 jar 做了 `javap` 反编译核对库行为，而非按注释/文档推演——这直接推翻了两条历史结论（见已证伪 #1）。
- **存疑未入表的项**（需真机或产品确认）：
  1. P0-2 的 Android 侧前提：`RequestMultiplePermissions` 取消时回调 `emptyMap`。若某些 ROM 回调 `false`，该项降为 P2（只少一次 settle，不卡死）。**建议真机复现一次**。
  2. P1「曲库页点歌不跳播放页」是否产品有意（`LibraryScreen.kt:189` 不跳，而搜索页/详情页/歌单墙三处都跳）。若有意则应反过来统一那三处。
  3. 横屏控制台失去背景真模糊：`playerHaze` 采样源只盖左栏（`PlayerScreen.kt:565-570`），控制台在右栏、几何上位于采样节点 bounds 之外。haze 1.6 越界取空 → 竖屏那层「采样封面色的真毛玻璃」在横屏退化为平板玻璃。需真机对比质感。
  4. 横屏无刘海/挖孔避让：全工程 `displayCutout`/`safeDrawing`/`WindowInsets` grep 零命中，只有 `statusBarsPadding()`+`navigationBarsPadding()`。
  5. 扫描层完全没有隐藏文件/排除目录过滤（`excludedFolders` 只在输出层生效）；需确认是分层设计还是遗漏。
  6. 大 Bundle 触发 `TransactionTooLargeException` 的确切条目阈值（粗估 ~3000 首量级），需真机日志。
  7. 通知栏小图标：全仓无 `setMediaNotificationProvider`/`setSmallIcon`，media3 默认用库自带图标，是否为白色方块需真机看。
  8. `AutoStartGuide` 的候选组件 `com.android.settings/...InstalledAppDetails` 在 Android 12+ 可能恒 resolve 不到。
