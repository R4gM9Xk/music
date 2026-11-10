# 代码审查修复记录（B1–B11 / D1–D3）

日期：2026-11-10 ～ 11 ｜ 输入：`2026-11-08-sole-readonly-code-review.md`
方式：逐项修复 + 编译/单测验证 ｜ 改动 23 个文件，+624 / −332 行

## 验证结果

| 检查 | 结果 |
|---|---|
| `:app:compileDebugKotlin` `:core:ui:compileDebugKotlin` | **BUILD SUCCESSFUL** |
| `:app:testDebugUnitTest` `:core:ui:testDebugUnitTest`（--rerun-tasks） | **179 通过 / 0 失败 / 0 错误**（core:ui 99 + app 80） |
| `:app:assembleDebug` | 资源合并、Kotlin、Manifest 合并全部通过；**仅** `validateSigningDebug` 失败 |
| Manifest 合并结果核对 | 两个小部件 receiver 均含 `APPWIDGET_UPDATE` + `APPWIDGET_OPTIONS_CHANGED` |
| `libs.versions.toml` 别名双向校验 | 每个保留别名都被构建脚本引用；无悬空引用 |

> `validateSigningDebug` 的失败是**环境限制**而非代码缺陷：
> `java.nio.file.AccessDeniedException: C:\Users\a\.android\debug.keystore.lock`。
> `C:\Users\a\.android` 在沙箱内只读（`tools/gbuild.ps1` 头部说明第 3 条已记录该约束），
> 与本次改动无关 —— `app/build.gradle.kts` 的签名配置一行未动（已用 `git diff --stat` 核对）。

---

## 一、逻辑错误 / Bug

### B1 `loadLibrary` 播放在播分支绕过换机 re-match ✅

**`AppState.kt`**：在 `playingSong != null` 分支的 `return` 前补上 `rematchMissingRefs()`。

修复前 `rematchMissingRefs()` 的调用点在函数末尾、位于该 `return` 之后，于是
「restore 后服务已在播」（= 换机后第一次打开 App 的最常见形态：进程被杀 → 系统续播 /
通知栏恢复 / 蓝牙重连）时整场会话都不做四级重绑定，歌单显示空且无横幅。
`rematchMissingRefs` 自带 `rematchPending` 门控，重复调用对已消费场景是安全空转
（非恢复路径直接 return，不会重复统计或重复弹横幅）。

### B2 `hasSongs` 死按钮只看住了 1/3 调用点 ✅

**`Chrome.kt`**：`MiniPlayerBar` 与 `GlassChrome` 的 `hasSongs` **去掉默认值改为必填**。
**`SearchScreen.kt` / `PlaylistDetailScreen.kt`**：补传 `hasSongs = state.hasSongs`。

选「改类型」而不是「补两处传参」，是因为默认值 `true` 让漏传既不报错也不崩溃，
只静默退化成死按钮 —— 这正是它此前只接了一条的原因。改成必填后任何新调用点漏传都是编译错误。

### B3 引擎断连后写路径直接崩 ✅

**`MediaControllerEngine.kt`**：新增 `connectedSilently()`（静默判定，不上报横幅），
并给全部一条原本裸调的入口加上守卫：

| 入口 | 守卫 | 理由 |
|---|---|---|
| `loadQueue` | `checkConnected()` | 由 `startCurrent` 在 UI 线程调，`setMediaItems/prepare/playWhenReady` 三连都会抛 |
| `addQueueItems` / `moveQueueItem` / `removeQueueItems` | `checkConnected()` | 队列编辑全部由 UI 线程触发 |
| `setRepeatMode` / `setShuffleMode` | 静默 | 意图状态，重连后 `syncEngineModes` 会重发 |
| `stopAndClear` | 静默 | 调用点几乎全在 teardown 路径，断连是预期状态 |
| `currentMediaId` / `mediaIds` / `hasNext` / `hasPrevious` / `isEnginePlaying` / `durationSec` | 静默 + 安全默认值 | 读侧 |
| `positionSec` | `checkConnected()` 且断连返回 `0f` | 心跳入口，负责上报 |

**为什么分两档**：`stopAndClear`/读操作在 Activity 销毁、服务重启、切演示曲目时高频发生，
那时断连是预期状态，弹「播放服务连接已断开」纯属噪音还会和真错误抢横幅；
而写路径（装载队列/队列编辑）必须上报——否则会出现「界面在播、实际没声」的假象。

### B4 小部件尺寸变化不重绘（两处独立成因）✅

1. **`AndroidManifest.xml`**：两个 receiver 的 intent-filter 补 `APPWIDGET_OPTIONS_CHANGED`。
   旧注释断言「由回调分发自动送达、不需要单独声明」是错的——系统按 action 匹配 receiver
   才唤起 `onReceive`，再分派到 `onAppWidgetOptionsChanged`。同时重写了那段错误注释。
2. **`MusicWidgetViews.kt`**：`OPTION_APPWIDGET_MIN_WIDTH` 读不到（部分 ROM 首帧不写该键）
   时不再落 `0`→一律竖版，改为按 provider 身份回退（`WIDGET_PROVIDERS` 改成
   `provider to isWideDefault` 的配对表，`refreshAll` 用 `LinkedHashMap<Int, Boolean>` 合并 id）。
3. 同文件顺带修掉**一次点击两次 2s 阻塞**：`WidgetActionReceiver` 原先在 finally 里调
   `MusicWidgetViews.refreshAll(context)`，会**再建一个 MediaController**。现在
   `refreshAll(context, preConnected = controller)` 复用已握有的连接，连接次数恒为 1。

### B5 铃声选择器塞了类型错误的 extra ✅

**`ShareActions.kt`**：删除 `putExtra("android.intent.extra.ringtone.SHOW_SILENT_ITEM", title)`
（该 key 是 `EXTRA_RINGTONE_SHOW_SILENT` 的 boolean 别名，上一行已正确传过；塞 String 会被
AOSP 忽略、被按 `getBooleanExtra` 读的实现视作类型不匹配）。`startRingtonePicker` 的
`title` 形参随之变为无用，一并移除。删除原因写在原处注释里，防后人加回。

### B6 封面解码失败每帧重解 ✅

**`ArtworkBitmapCache.kt`**：新增 `failed` 负缓存（`LinkedHashMap` + `MAX_FAILED = 1024` 上限），
`getOrDecode` 命中失败记录直接返回 null；新增 `clearFailure(uri)` / `clearFailures()` /
`getOrDecode(uri, retryFailed)`。
**`AppState.loadLibrary`** 在曲库刷新时调 `ArtworkBitmapCache.clearFailures()`——
扫描到达意味着 covers/*.png 可能已重建，不清会让新封面被旧的失败记录永久挡住。

同时把 `removeEldestEntry` 的实际行为写进注释：它**恒不触发**（`put` 尾部的收紧循环
维持了 `sizeBytes ≤ MAX` 不变量），真正的驱逐在那段循环里 —— 避免下一个人以为改
`MAX_BYTES` 就够了。

### B7 静默更新检查被 Failed 态永久挡住 ✅

**`UpdateManager.kt`**：`checkSilently` 的放行集合从 `{Idle}` 扩到
`{Idle, Failed, UpToDate, Checking}`（仍排除 `Downloading`/`ReadyToInstall`，
那两态 `pendingInfo` 指向进行中/已完成的下载，覆盖会丢进度与安装入口）。
并在 `check()` 里补 `pendingInfo = null`（UpToDate / 检查失败时），让它的生命周期
严格等于「当前 state 是 Available」——旧实现只在 Available 赋值、任何分支都不清理。

### B8 面板退场信号会被无关子面板唤醒 ✅

**`Sheet.kt`**：引入 `SheetExitReporter`（`currentSeq` + `notify(seq)`）替代原来的
`() -> Unit`。`ModalState` 新增 `dismissSeq`（`requestDismiss` 且确实可见时 +1）与
`currentDismissSeq()`；`notifyExitAnimationFinished(seq)` 只在 `seq == dismissSeq` 时计入。
`GlassSheet` 在**进入退场那一刻**冻结代次、完成时带回。

一个 modal 生命周期内可以有多个 `GlassSheet`（`SongActionsSheet` / `CollectionActionsSheet`
各三个互斥子面板），子面板切换也会跑退场动画并上报——旧实现无法区分，宿主会被
「属于另一次切换」的信号提前唤醒、截断当前面板的退场末帧。

### B9 `restore()` 的歌单曲目重复 ✅

**`AppState.kt`**：`p.trackIds.distinct()` 后再对已有项去重。
旧写法 `filterNot { it in addedTracks[p.id]!! }` 只挡「目标列表里已存在的」，
挡不住「这一批自身含重复」（旧快照 / 手改备份 JSON / legacy v1 迁移），
后果是同一首歌在歌单里出现两次、移除一次还剩一次（看起来像「点了没反应」）。

### B10 一组次要问题 ✅

1. **`visibleLibrary` 指纹碰撞** → 改为 `VisibleLibraryKey(excluded: Set<String>, scanFilterShort)` 内容比对。
   旧算术指纹 `size*31 + hashCode*2 + flag` 碰撞时只要 library 引用没换就返回旧的过滤结果。
2. **`MediaLibrary.load` 重入拿到半截结果** → 重入路径检查 `lastScan.aborted`，中止时返回空表
   （让调用方走「失败/无结果」分支不写缓存），并打一条 warn。
3. **shuffle 插入修正命令失败被完全吞掉** → `runCatching` 结果落地为 `sent.isFailure` 并打日志，
   再退回原生插入（不改变行为，只补留痕）。
4. **`next()` 的两级兜底读起来像「可能没有候选」** → 改写成显式的 if/else 并写明不变量，
   判据与行为**完全保持原样**（`!manual && repeatMode == Off && unplayed.isEmpty()` → stopAtEnd）。
5. **`CollectionActionsSheet` 的导出项在 `onExportM3u == null` 时仍然渲染** → 把 null 判定提到
   渲染条件里（当前唯一调用点恒非空，这是防回归守卫）。
6. **桌面歌词首行被永久钉住** → `currentLineText` 的纯文本降级判据从
   `lines.first().timeSec <= 0f` 改为 `lines.size == 1`。前者会让**合法的 `[00:00.00]` 首行**
   被误判成纯文本降级，悬浮窗永远停在第一行。

### B11（报告未收录，修复过程中发现）扫描失败被当成「确认无曲库」✅

**`GlassApp.kt`**：`loadRealLibrary` 改为**只在 `result.isSuccess` 时** `state.loadLibrary(songs)`。

修复前的实际控制流：
```
扫描失败 → songs = emptyList() → state.loadLibrary(emptyList())
         → 走「空库终态」分支：library 置空 / librarySettled = true / abandonPendingPlayback()
         → 紧接的 `else if (!state.librarySettled) settleLibraryKeepingPending()` 永不执行
```
也就是说那段注释（302–307 行）精心论证的「扫描失败 ≠ 确认无曲库，必须保留 pending」
**从未生效** —— 一次 MediaStore 瞬时错误就永久丢掉用户的恢复队列。
这正是审查报告 B1 同一族问题的另一面（一个是 `return` 太早，一个是门控被自己踩掉）。

---

## 二、死代码

### D1 演示数据层 ✅

删除整层不可达的演示目录（`DemoAlbum` 类型、`albums`×5、`lyrics`、`searchSongs`、`album(id)`），
并把 `AppState` 里依赖它的三条分支收敛：

- `searchScope()` → 恒返回 `visibleLibrary()`（旧 `else DemoData.songs` 恒空）
- `searchAlbumGroups()` → 恒走 `albumGroups()`（旧 `else albumGroupsFrom(searchScope(), …)` 恒空）
- `DemoCatalog.kt` 重写为只保留**仍活着**的部分（三个动态歌单 id、`dynamicIds`、
  四个常驻歌单壳、`playlist(id)`、`songById`），并在文件头用一张表记录删了什么、为什么。

保留 `songById` 与 `DemoData.songs`：它们仍被 `AppState.songById` 的非真库兜底分支引用，
删掉会牵动封板的快照/恢复链；已在其 KDoc 里写明「对当前工程恒返回 null，演示曲库已不存在」。

### D2 无人调用的 API ✅

| 删除项 | 原状 |
|---|---|
| `SettingsStore.read()` | 被 `readResult()` 三态取代；二态签名正是 P1-② 数据丢失的根因 |
| `SettingsStore.readScanFilterShort()` | KDoc 称「MediaLibrary 扫描线程专用」，实际零调用（P2-④ 后扫描恒全量）。保留该 Preferences 键的写入并说明原因 |
| `MediaLibrary.lastScanResult()` | 零调用；`writeCache` 直接读私有字段，留 public 读取口是 P3-32 错位的温床 |
| `QueueStateStore.clear()` | 零调用；其 KDoc 举的场景实际走 `clearUpNext + scheduleSave` |
| `SystemActions.available` | 零调用，且与真实实现（逐项判 null）语义不一致 |
| `DemoSong.inLibrary` | 零读取，唯一写入点是 placeholder 传 false |
| `AppState.folderGroups(excluded)` 的形参 | 带 `@Suppress("UNUSED_PARAMETER")` 完全忽略；两调用点分别传排除集与 `emptySet()`。改为无参 `folderGroups()`，两处调用同步修正 |

`DemoSong.coverBitmap` / `LibraryGroup.coverBitmap` **未删**：注释已写明「恒为 null」，
但它是 `ArtThumb(bitmap=)` 优先级语义的一部分，删掉要连带改 8 个调用点与组件签名，
收益（少一个 null 管道）不抵改动面。已在 `DetailData.cover` 的 KDoc 里补上「恒为 null」的说明。

### D3 配置与副本 ✅

1. **`gradle/libs.versions.toml`**：129 条 → **24 条**，只保留被 `libs.…` 实际引用的坐标；
   删除 105 条零引用项（coil/okhttp/okio/room×3/hilt×5/dagger×2/navigation×4/fragment/
   work-runtime/material-icons/lifecycle 全套…）。文件头重写为「为什么它曾经是个坑」的说明。
   - **踩坑记录**：`[plugins]` 里的 `ksp` / `hilt` / `kotlin-serialization` 虽然无人 apply，
     但根 `build.gradle.kts` 用 `alias(...) apply false` **持有这些 alias**，删掉会导致
     `Unresolved reference: serialization / ksp / hilt` 脚本编译失败。已恢复三个插件与其
     `ksp`/`hilt` 版本号，并注明「保留但不要在任何模块 apply」。
2. **`backup-snapshot-2026-10-04/`**（1,934 文件 / 98.9 MB，Git 之外）：**未删除**
   （沙箱禁止写出工作区，且它是有价值的历史快照）。改为新增 **`.searchignore`** 把
   `backup-snapshot-*/`、各 build 目录、`.gradle-home/`、`.tmp*/`、`.zcode*/` 等一并排除，
   并在文件里写明「若确认不再需要，正确做法是移到工作区之外，而不是让它待在源码目录里」。

---

## 三、未修（有意保留）

| 项 | 原因 |
|---|---|
| `DemoSong.coverBitmap` / `LibraryGroup.coverBitmap` 全链路 | 删它要改 8 个调用点 + `ArtThumb` 签名，收益不抵改动面；已在 KDoc 标注真实语义 |
| `SongRematch.levelHits` / `fileNameOf` | production 无消费，但单测以 `hitsOf()` 断言分级行为，属测试契约 |
| `WidgetState`/`MusicWidgetViews` 的 `MIN_HEIGHT` 未参与布局选择 | 两个 provider 尺寸语义相同是既有产品决策（两张卡片只在预览/`initialLayout` 上不同），改成按高度分支属功能变更，不在本次范围 |
| `backup-snapshot-2026-10-04/` 本体 | 同上：需要移出工作区的权限，已用 `.searchignore` 缓解 |

---

## 四、建议的真机复核清单

以下三条依赖平台行为，静态审查**无法**定论，需装机验证：

1. **B4-1**：放两个小部件（4x1 / 4x2），拖拽改变尺寸 → 布局应立即切换
   （横条 ⇄ 封面版）。若仍不切换，说明该 ROM 的 `OPTION_APPWIDGET_MIN_WIDTH` 时序另有问题。
2. **B3**：播放中 `adb shell am force-stop` 杀掉播放服务（或开发者选项「不保留活动」）后，
   在 App 内点「播放」「下一首播放」「清空接下来」→ 应弹「播放服务连接已断开」横幅而**不崩**。
3. **B10-6**：放一个首行为 `[00:00.00]…` 的 .lrc，开桌面歌词 → 歌词应随进度翻行，
   不再永远停在第一句。
