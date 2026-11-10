# 单人只读代码审查报告（逻辑错误 / Bug / 死代码）

日期：2026-11-08 ｜ 方式：仅读代码，未修改任何文件，未运行构建
范围：`project/` 全部源码（80 个 `.kt` + 构建脚本 + Manifest/res XML + 测试），`tools/` 未纳入结论
覆盖度：`AppState.kt`(2622) `PlayerScreen.kt`(1572) `GlassApp.kt`(1447) `MediaLibrary.kt`(1184)
`MusicPlaybackService.kt`(882) `MediaControllerEngine.kt`(606) `SettingsStore.kt`(611)
`UpdateManager.kt`(473) `ShareActions.kt`(369) `SongRematch.kt`(322) `ReorderableList.kt`(310)
`Chrome/Sheet/Lists/ActionsSheet/Glass/Flight/Field/Artwork/SettingDropdown/WidgetState…` 全部读完。

标注约定：**[确认]** = 顺着控制流/数据流能静态断定；**[高]** = 强推断，但依赖平台行为需真机复核。

---

## ✅ 修复状态（2026-11-10 已完成）

本报告的 B1–B10 与 D1–D3 **已全部修复**，另在修复过程中发现并修掉了报告未收录的 **B11**。
验证：`:app:compileDebugKotlin` / `:core:ui:compileDebugKotlin` / `:app:assembleDebug` 全部
BUILD SUCCESSFUL；单测 **179 条通过、0 失败、0 错误**（core:ui 99 + app 80）。
逐条改动与理由见 [2026-11-10-code-review-fixes.md](2026-11-10-code-review-fixes.md)。

下表为原始报告内容（保留作为问题记录与回归基线，**描述的是修复前的代码**）：

---

## 一、逻辑错误与 Bug

### B1 [确认] `loadLibrary` 的「服务正在播」分支绕过换机 re-match —— P0-5B 功能在最常见的时序下失效

`AppState.kt:1429-1442` 在 `playingSong != null`（服务正在播曲库中的歌）时 `return`，
而 `rematchMissingRefs()` 在 `AppState.kt:1481`，位于该 `return` **之后**。

```
restore() → pendingTrackRefs / rematchPending = true
   ↓（服务先连上并在播）
loadLibrary(songs) → playingSong != null → return   ← rematchMissingRefs 永不执行
```

后果：换机/恢复备份后，只要媒体会话已在播（进程被杀后系统续播、通知栏恢复、蓝牙重连——
正是「换机后第一次打开」的典型形态），四级重绑定**整场会话都不跑**：
`favorites / userPlaylists / playCounts / queue` 里的旧路径 id 全部解析不到，
`resolvePlaylist` 的 `mapNotNull` 静默丢弃 → 歌单显示空，且**没有恢复横幅**。
这不是新缺陷，而是 P0-5B 声称要消灭的那句「我恢复了备份但歌单全空了，而且没有任何提示」
在这条时序下原样复现。`rematchPending` 一直为 true，用户除非手动重扫，否则永不收敛。

修法方向：`return` 前调 `rematchMissingRefs()`；或把 rematch 收敛到 `librarySettled` 的单点出口。

---

### B2 [确认] `MiniPlayerBar` 的「死按钮」修复漏了 2/3 调用点

`Chrome.kt:311-412` 明确写了 `hasSongs = false` 时不渲染播放/下一首，
理由是 `togglePlayPause / next` 内部有 `hasSongs` 守卫 → 点了完全没反应，是纯死按钮。
`GlassApp.kt:1173` 传了 `hasSongs = state.hasSongs`，但：

- `SearchScreen.kt:274 GlassChrome(...)` —— 未传，取默认 `true`
- `PlaylistDetailScreen.kt:249 GlassChrome(...)` —— 未传，取默认 `true`

后果：空曲库 / 权限被拒时，从搜索页或详情页看到的迷你条仍然带着两个可点但永久无反应的按钮，
正是该 KDoc 声称已修掉的形态。默认值 `true` 使「漏传」不报错、不崩溃，只静默回归——
建议把 `hasSongs` 改为无默认值的必填参数，让漏传在编译期暴露。

---

### B3 [确认] `MediaControllerEngine` 的连接守卫不一致，断连后是崩溃路径而不是降级

`checkConnected()`（`:384`）只挂在 `play/pause/seekTo/skipTo/skipToNext/skipToPrevious/
setPlaybackSpeed/positionSec` 上。**未守卫**的公开入口包括：

`loadQueue(:400)`、`setRepeatMode`、`setShuffleMode`、`stopAndClear(:598)`、
`addQueueItems(:553)`、`moveQueueItem(:574)`、`removeQueueItems(:576)`、
`hasNextMediaItem/hasPreviousMediaItem`、`currentMediaId`、`mediaIds`、`durationSec`。

Media3 的 `Player` 在未连接时调用这些方法抛 `IllegalStateException`。
`AppState` 侧全部裸调，且都在 **UI 线程 / 组合回调**里：

- `startCurrent → loadQueueOnEngine → eng.loadQueue(...)`（`AppState.kt:1733`）
- `insertQueueItems / removeQueueItem / moveQueueItem / clearUpNext → engine!!.…`（`AppState.kt:1982-2013`）
- `togglePlayPause` 的 `engineLoadedUri != uri` 分支 → `startCurrent` → `loadQueue`

后果：服务进程被系统杀掉/用户强停而 Activity 还活着时，用户点「播放」「下一首播放」「清空接下来」
= 未捕获异常崩溃。`checkConnected` 的注释把「断连一个心跳内可见」当成既有保障，
但那句只覆盖 `positionSec`；写路径一条都没挡。`reportedDisconnected` 的节流也救不了——
它只在守卫里置位，未守卫的路径先抛。

---

### B4 [高] 小部件 `onAppWidgetOptionsChanged` 可能永远收不到

`AndroidManifest.xml:142-145` 断言：

> APPWIDGET_DELETED / APPWIDGET_OPTIONS_CHANGED 等由 AppWidgetProvider 的回调分发**自动**送达，
> 不需要单独声明；把它们也写进来反而绕过系统校验，属于无意义放大。

`AppWidgetProvider` 的 javadoc 对 `onAppWidgetOptionsChanged` 的表述是相反的：
provider 的 intent-filter 必须声明该 action，否则不会被调用。
若断言不成立，实际后果是：用户拖拽改变小部件尺寸后**不会重绘**，
`MusicWidgetViews.build` 里按 `OPTION_APPWIDGET_MIN_WIDTH` 选横条/封面版的逻辑
要等到下一次播放事件才生效（`MusicWidgetRefresher` 是事件驱动、`updatePeriodMillis="0"`），
观感即「把 4x2 拖窄了，还是竖版/还是横版没换」。
`onAppWidgetHostStartLayoutZoomChange`… 无。结论：需要真机验证这条注释；
验证前不要把「只声明 UPDATE」当成既成正确。

顺带两个静态可见的问题（与上面独立）：

- `MusicWidgetViews.kt:160`：`getInt(OPTION_APPWIDGET_MIN_WIDTH, 0)` ——
  系统未写该键时取 0 ⇒ `wide=false` ⇒ 一律退化成封面竖版。
  而 `music_widget_4x1.xml` 把 `minHeight` 也声明成 110dp/2 格，
  注释里「让『1 行高但够宽』也走横条」的意图（`:162`）实际上不可能出现：
  放置时 `targetCellHeight=2`，与 4x2 同尺寸，`updatePeriodMillis`/`maxResize*` 亦同，
  两个「独立小部件」在尺寸语义上完全无法区分，只靠 `initialLayout` 不同。
- `MusicWidgetViews.kt:104` + `WidgetActionReceiver.kt:107`：`refreshAll` 在主线程/广播线程
  同步 `future.get(2s)`，`onUpdate` 与点击回调各阻塞至多 2s。注释已自觉论证「安全」，
  但 `WidgetActionReceiver` 的 finally 里又调一次 `refreshAll` ⇒ **同一次点击最多两次 2s 阻塞**
  （第一次在 `forward` 的建连、第二次在 `refreshAll` 的再建连），
  而它自己刚说「连一次要 10-50ms，排队会让最后一次点击延迟几百 ms」。
  `MusicWidgetRefresher` 的 400ms 抑制窗口只保护服务侧路径，不保护这条 receiver 路径。

---

### B5 [确认] `ShareActions` 给铃声选择器塞了一个类型错误的 extra

`ShareActions.kt:278`：

```kotlin
putExtra("android.intent.extra.ringtone.SHOW_SILENT_ITEM", title)
```

该 key 的语义是 boolean（`EXTRA_RINGTONE_SHOW_SILENT` 的字符串别名），
上一行已经用官方常量正确地 `putExtra(EXTRA_RINGTONE_SHOW_SILENT, false)`。
这行把一个歌名 String 塞进 boolean 键：在 AOSP 里被忽略，
而在「按 `getBooleanExtra` 读它」的 ROM 上会拿到 Bundle 类型不匹配
（`getBooleanExtra` 对 String 值返回默认值，静默；部分 ROM 直接抛）。
`title` 在这里也毫无用处——同一段已经把 uri 作为 EXISTING 项塞进去了。
应删除。同类：`:277` 把**输出**键 `EXTRA_RINGTONE_PICKED_URI` 当输入写，
注释自认是 ROM 兜底，属于已知取舍，保留但应记为可疑。

---

### B6 [确认] `ArtworkBitmapCache`：失败/缺失封面每帧重解（负缓存缺失）

`getOrDecode`（`:43`）只在**成功**时入缓存；`decodeSampled` 返回 null 就每次重跑
`BitmapFactory.decodeFile` 两趟（bounds + 真解）。
`MediaLibrary` 的迁移链里 `coverLost/reusableFolderCover` 会主动 `cache.delete()`
（`:513`、`:520`），`sweepCovers` 也会删孤儿 PNG —— 于是存在一个确定会到达的状态：
缓存 JSON 里 `artworkUri` 仍指向已被删除的 `covers/*.png`（`readCacheEntries` 的
legacy 回落把「文件不在」折算成 `artworkUri = null` 只在 dataPath 为空时…实际 `cover` 为 null 时
`artworkUri = null`，好）；但**增量扫描 miss 前**的这一段，以及任何 `file://` 指向
刚被清理助手删掉的文件时，列表里每个可见行的每次重组/重绘都会发一次失败的两趟解码。
曲库页滚动时该行会被反复组合 ⇒ 主线程 IO。建议：`getOrDecode` 用 null 占位（或单独 miss 集）
做负缓存，配合 `MediaLibrary` 的 coverLost 事件失效。

同类（次要）：`removeEldestEntry`（`:27`）恒返回 `sizeBytes > MAX_BYTES`，
而 `put` 里的手动收紧循环把不变量维持为 `sizeBytes ≤ MAX`，
所以这个 override 实际**永不触发**（进入 put 时不可能 >MAX）。
驱逐全部由手动循环完成，行为仍正确，但这段 override 是「看起来在做的事其实没做」的死逻辑，
容易在下次改动时被误信。

---

### B7 [确认] `UpdateManager` 的 `pendingInfo` 不清理 + 静默检查被 Failed 态挡住

- `check()`（`:80-93`）：`UpToDate` / `Failed` 分支不重置 `pendingInfo`。
  于是「检查到 v9 → 用户没更新 → 服务端回滚到 v8 → 再检查得到 UpToDate」时
  `pendingInfo` 仍指向 v9 的 URL 列表；`startUpdate()` 只从 Available/Downloading/
  ReadyToInstall 进入，暂时拿不到它——但 UI 文案 `SettingRow` 的 `Failed` 分支
  用 `versionName != null` 判「进过下载链路」（`SettingsScreen.kt:714`），
  而 `versionName` 来自 `state`（每次 check 覆盖）✓。真正的风险是
  **`ReadyToInstall` → 用户重装旧包 → 状态仍 ReadyToInstall**：`install()` 会重发同一个
  apkFile。这条已被 `apkFile.isFile` + 签名校验兜住，可接受。结论：`pendingInfo` 的生命周期
  依赖「只有 Available 才赋值」这一条不成文约束，没有任何注释钉住它，改动时极易破。
- `checkSilently()`（`:110-119`）：条件 `state.status == Idle`。
  手动检查失败后（`Failed`），此后每次冷启动的静默检查都**静默跳过**，
  设置页「发现新版本」那条点亮永远不会再出现，直到用户手动点一次「检查更新」。
  用户感知 = 「这个 App 不再提示更新了」。

---

### B8 [确认] `ModalSheetHost` 的退场信号在多 GlassSheet 面板里有伪触发窗口

`Sheet.kt:169-186` 用 `exitAnimationId` 作为「真的退场完了」的凭据来 `clearContent()`；
`GlassSheet` 侧 `:295-301` 的条件是 `hasBeenVisible && !targetState`。
`SongActionsSheet`/`CollectionActionsSheet` 在**同一个 modal 生命周期内**渲染多个
互斥 `GlassSheet`（`:84,196,197` / `:425,516,539`）。切换子面板时旧子面板的退场动画结束
也会 `onExitFinished()` → `exitAnimationId++`。若用户正好处在一次 dismiss 的等待窗口里
（`visible` 刚翻 false、`collectLatest` 正在 `first { it != startId }`），
这个来自别的 sheet 的 bump 会让宿主**提前** `clearContent()`，
截断当前正在退场的那一块的最后几帧——正是该机制注释自称要避免的事
（「按 tweenPage delay 不是同一时间源」）。信号需要带「哪一个 sheet」的身份，
或让子面板切换不共用同一个 exit 计数。

---

### B9 [确认] `restore()` 内联展开用户歌单时，同批 `trackIds` 的自重叠加未被剥掉

`AppState.kt:1263-1267`：

```kotlin
snapshot.userPlaylists.forEach { p ->
    if (p.trackIds.isNotEmpty()) {
        addedTracks.getOrPut(p.id) { mutableStateListOf() }
            .addAll(p.trackIds.filterNot { it in addedTracks[p.id]!! })
    }
}
```

`addedTracks` 先被 `:1257-1260` 用 `snapshot.addedTracks` 填过一遍——同一份数据在 v10 编码里
**被写两次**（`encode` 的 `addedTracks` 与 `userPlaylists[].tracks` 都来自
`snapshot()` 里的 `addedTracks[it.id]`，`AppState.kt:1141-1144`）。
上面这段的 `filterNot { in addedTracks[p.id] }` 只挡住「已存在的」，
挡不住 `p.trackIds` **自身含重复**（旧版本快照、手工编辑的备份 JSON、legacy v1 迁移路径）
⇒ 恢复后同一 id 在歌单叠加层里出现两次：`meta` 计数与 `visibleLibrary` 之外的展示不符，
`isSongInPlaylist` 仍返回 true，但用户「移除」一次还剩一次，点了没反应。
去重应当用 `distinct()` 而非对着目标列表判存在。

---

### B10 [确认] 次要但真实的一组

1. `AppState.kt:2264` 可见曲库缓存指纹
   `excluded.size*31 + excluded.hashCode()*2 + (scanFilterShort?1:0)`：
   指纹碰撞（不同排除集算出同值）且 `library` 引用未换时，返回**上一次的过滤结果**。
   概率低但后果是「设置里改了排除，曲库没变」这类难以复现的抱怨；
   排除集本身通常 <10 项，直接存 `excluded.toSet()` 比对更省心。
2. `MediaLibrary.kt:62` 并发 `load` 的重入方在 `finally` 之前 `wait(200)` 轮询拿 `lastScan`；
   若主扫描被 `isCancelled` 中止（`aborted=true`），重入方拿到的是**半截结果**且
   无法察觉（`load` 的返回值只有 `List<DemoSong>`，`aborted` 被吞在 `ScanResult` 里，
   重入路径绕过了它）。与 `writeCache` 的 P2-19 防线同一条风险，只是换了入口。
3. `MediaControllerEngine.kt:567` `runCatching{sendCustomCommand}.onFailure{ addMediaItems… }`
   的兜底会**静默丢掉 shuffle 序修正**（「下一首播放」变成随机放置），无任何日志。
4. `AppState.kt:1614` `next()` 演示分支 `queue.indices.filter { … }` 之后
   `(0..queue.lastIndex).filter { it != queueIndex }.random()` —— 已守卫 `queue.size > 1`，
   不会抛，但 `randomOrNull() ?: random()` 的两级兜底读起来像「可能有空集」，实际两支都非空。
5. `PlaylistDetailScreen.kt:53-56` `if (onExportM3u != null) onExportM3u(state.detail.id, …)`
   在 `onExportM3u == null` 时**什么都不做**（无 else），而调用点
   `GlassApp.kt:1037` 恒传非空 ⇒ 现在无害；但「菜单项显示着，点了没反应」的形态已经被
   `SystemActions.kt` 自己列为红线（「点了没反应比没有这项更伤」）。同一文件应加守卫或去参。
6. `DesktopLyricsController.kt:97` `positionMsProvider() / 1000f` 与 `lines[i].timeSec` 比较 ✓；
   但 `:271`「首行时间 ≤ 0 就当纯音乐降级恒显示首行」会把**合法的第 1 行（time 0.00）**
   永久钉住：带 `[00:00.00]` 起头的正常歌词文件，悬浮窗永远不随进度换行。
   判据应是 `lines.size == 1`（与 `parseLrcOrPlainText` 的降级形态一致），不是 `timeSec <= 0f`。

---

## 二、死代码清单

### D1 演示数据层：整层已不可达，但仍在被「当作活路径」引用

`DemoData.songs`（`Artwork.kt:365`）是 `get() = emptyList()`。于是：

| 位置 | 符号 | 状态 |
|---|---|---|
| `DemoCatalog.kt:75-86` | `albums`（5 个 `DemoAlbum`，tracks 恒空） | 全仓零引用 |
| `DemoCatalog.kt:11-20` | `DemoAlbum` 类型 | 仅被上面使用 |
| `DemoCatalog.kt:97` | `album(id)` | 零调用 |
| `DemoCatalog.kt:101-107` | `searchSongs` | 零调用（`AppState.searchSongs` 是另一份实现） |
| `DemoCatalog.kt:65-73` | `lyrics` | 零调用（只在注释里被提到） |
| `DemoCatalog.kt:96` | `songById` | 被 `AppState:2506` 调用，但对恒等空列表 ⇒ 恒 null |
| `AppState.kt:2440` | `searchScope()` 的 `DemoData.songs` 分支 | 恒空 ⇒ 演示模式下搜索恒无结果 |
| `AppState.kt:2462-2465` | `searchAlbumGroups` 的 `else`（非 realLibraryLoaded）分支 | 对空列表分组 ⇒ 恒空，且注释还在论证「演示分支也必须走 albumGroupsFrom」 |

这层的代价不是体积，是**误导**：`AppState` 里有两条完整的演示/真库分支、
`LibraryOrigin.Demo/Live`、`Artwork.kt:141` 的占位色分叉，
全部建立在「恒空」的数据源上。要么承认演示模式已死并删掉分支，
要么把 `DemoData.songs` 恢复成真的数据——现在这种「保留形状、清空内容」是审查与改动最容易踩的形态。

### D2 无人调用的 public API

- `SettingsStore.read()`（`:194`）—— 被 `readResult()` 取代；仅存于注释「旧写法」。
- `SettingsStore.readScanFilterShort()`（`:352`）—— **KDoc 声称是
  「MediaLibrary.load 扫描线程专用」，而实际零调用**：`GlassApp.kt:275-279` 现在恒传
  `filterShort = false`，过滤已下沉到 `visibleLibrary()`（P2-④）。注释与代码相反，是主动的误导。
- `MediaLibrary.lastScanResult()`（`:86`）—— 零调用（`writeCache` 直接读 `lastScan` 字段）。
- `QueueStateStore.clear()`（`:122`）—— 零调用；注释举的场景（「用户清空队列」）
  实际上 `clearUpNext` 走的是引擎+快照，没人清这个文件。
- `SystemActions.available`（`SystemActions.kt:44-45`）—— 零调用，
  而它的 KDoc 与 `ActionsSheet.kt:80` 的注释都在说「面板据此不渲染分享分组」。
  真实实现是逐项判 null（`:173,184`），`available` 是那条设计没落地的残留。
- `SongRematch.fileNameOf`（`:253`）—— 声明为 public（`fun`，非 private），外部只有 `byName`
  索引在文件内使用。`Result.levelHits`/`hitsOf` 只服务单测，production 无日志消费
  （KDoc 说「便于日志排查」，其实没有任何一处打这个日志）。
- `AppState.folderGroups(excluded)`（`:2434-2435`）—— `@Suppress("UNUSED_PARAMETER")`
  且**完全忽略形参**，恒返回 `groups().folder`。两个调用点（`LibraryScreen.kt:303`、
  `AppState.kt:2571`）分别传 `state.excludedFolders` 与 `emptySet()`，
  签名在撒谎；注释承认「保留形参是为了不破坏既有调用点签名」，但代价是
  读代码的人会以为排除在这一层生效。
- `DemoSong.inLibrary`（`Artwork.kt:328`）—— 只在 `placeholder` 上写 false，**全仓零读取**。
- `DemoSong.coverBitmap`（`Artwork.kt:330`）+ `LibraryGroup.coverBitmap`（`AppState.kt:256`）——
  扫描、缓存、placeholder 三条路径全部写 null，注释两处自认「恒为 null」，
  但仍作为 `bitmap =` 实参穿过 8 个调用点
  （Lists:419、Chrome:379、Artwork:135、SearchScreen:325、LibraryScreen:262/288/316/345、
  PlayerScreen:480、PlayerFlightOverlay:286）与 `ArtThumb(bitmap=)` 形参。
  纯管道死重量，且掩盖了「真正的封面来源只有 artworkUri」这一件事实。

### D3 死配置与死文件

- `gradle/libs.versions.toml`：137 行里绝大多数坐标**没有任何模块引用**
  （coil×2、okhttp、okio、room×3、hilt×5、dagger×2、navigation×4、media、fragment、
  glide… 以及 `[plugins]` 的 `ksp`/`hilt`/`kotlin-serialization` 三个从未被 apply）。
  文件头写明「由 APK 内 META-INF 反查生成」，因此它是**清单**而不是依赖表——
  但它的形状（`libs.hilt.compiler`、`libs.androidx.room.compiler` 这类别名）
  与真正的 `implementation(libs.…)` 语法完全同构，是「照着写就能引」的诱饵。
  `glance`×3 有专门注释说明有意保留（`MusicWidgetViews.kt:25-37`），其余 80+ 条没有任何解释。
  建议：分成 `[catalog]`（生成清单，不参与构建）与 `[libraries]`（实际在用），或直接裁到在用的 10 条。
- `backup-snapshot-2026-10-04/`：整棵工程副本（含 `core/*/build/` 的 aar/intermediates 产物）。
  这是 `glob **/*` 返回 53,279 条里绝大部分的来源——任何后续的代码搜索/审查/索引都会被它污染。
  `settings.gradle.kts:4-6` 刚刚以「纯粹误导索引与代码审查」为理由删过同类空骨架，
  这条快照是同一个理由的更强版本。
- 工作区根下的 `.capture/ .device-backup/ .ghome/ .ghome-work/ .gradle-home/ .gtmp/
  .tmp/ .tmp-capture/ .zcode/ .zcode-probe/` 与 `ui-preview.html`、`tools/gen_openpencil_ui.py`
  混在同一个目录里，与构建产物无隔离约定（`.gitignore` 只覆盖 `project/.git` 那一侧）。

---

## 三、静态可见但不属于 bug 的取舍（供后续判断，不要当缺陷改）

- `AppState` 的 `sleepTimerMinutesForEngine()` 用 ceil + 注释已纠正旧注释的错误方向 ✓
- `insertWithShuffleFix` 用 `System.nanoTime()` 做 shuffle seed ⇒ 单测不可复现（有意，非缺陷）
- `MediaLibrary` 的 coverLost 用 `hadCoverRecord` 而非解析后的 `artworkUri` ✓（真机踩过）
- `persistCover` 的 mtime 盖戳做「差异跳过」✓
- `MusicPlaybackService.onMediaItemTransition` 判 `reason == AUTO` 才暂停 ✓
- `WidgetActionReceiver.forwarding` 的 `@Volatile` 弱一致 ✓（注释已声明不追求强一致）
- `ReorderableList` 的 `commitGen/settleGen` 双代际 ✓；分母趋 0 的 NaN 风险已在 `start<-1||>1` 处避开
- `SettingsStore` 的 DataStore 实例重建（先 cancel scope）✓
- `CrashHandler.install` 的 `previous is Handler` 幂等守卫 ✓
- `AuroraPalette.fromSeed` 的彩度门控 `g` ✓（是修 bug，不是调参）

---

## 四、建议处理顺序

1. **B1**（换机 re-match 在续播时序下整场失效）——功能级回归，优先级最高。
2. **B3**（断连后写路径崩溃）——唯一确定的 crash 路径。
3. **B2**（死按钮回归）——把 `hasSongs` 改成必填即可一次性根治三处调用点。
4. **B4/B5/B6/B10.6**（小部件 resize 假设、铃声 extra、负缓存、桌面歌词首行钉死）。
5. **D1 + D2**（死代码）——`DemoData/DemoCatalog` 那一层的删除会同时消掉
   `LibraryOrigin`、`searchScope` 演示分支、`songById` 兜底，收益最大；
   注释与代码矛盾的 `readScanFilterShort`/`available`/`folderGroups` 三处优先删或改真。
6. **D3**（`backup-snapshot-*` 出仓 + `libs.versions.toml` 分区）——不改行为，只让人能读。
