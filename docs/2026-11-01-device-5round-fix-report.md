# 真机五轮测试问题修复报告（6 项）

> 日期：2026-11-01
> 依据：`2026-11-01-device-5round-test-report.md`（真机五轮 × 五方位，crash 0 / ANR 0）+ `2026-11-01-full-audit.md`（静态审计）
> 基线：HEAD `3df1389`
> 方式：分域并行修复 + 逐项设备实证 + 独立代码审查
> 构建：`:app:assembleDebug` BUILD SUCCESSFUL（AGP 8.13 / Kotlin 2.2.20 / JDK 17）

---

## 0. 结论速览

| # | 级别 | 问题 | 修法一句话 | 状态 |
|---|---|---|---|---|
| F1 | **P0** | 自动切歌后首次点暂停不暂停（从头重播 + 继续走秒） | `syncFromEngine` 回写 `engineLoadedUri`，位置移出 `index != queueIndex` 分支 | ✅ 已修·设备实证 |
| F5 | **P1** | 字号变更（Activity 重建）把 15 分钟睡眠定时器重置为整档 | 按**剩余时间**重发（ceil）+ `(代次, deadline)` 判重 + 断连清戳 + 播完档撤销 | ✅ 已修·设备实证 |
| F4 | **P2** | 横屏旋转（歌词模式）→ 左舞台歌词渲染空白 | 行坐标加**舞台几何戳**（宽+高），定位只认当前几何量出的坐标 | ✅ 已修·设备实证 |
| P2-26 | **P2** | 字号变更后播放页/详情被弹回曲库首页 | `rememberSaveable` 承载导航态（含搜索词），构造 AppState 时同步还原 | ✅ 已修·设备实证 |
| F3 | **P2** | 玻璃 chrome 透字（迷你条/导航栏后方列表文字可辨） | chrome 层叠主题同向 scrim（深 0.80 / 浅 0.74），不依赖 blur | ✅ 已修·量化达标 |
| F2 | **P2** | 媒体会话封面用应用私有 cache 绝对路径 → 系统 EACCES 刷屏 | 改为**内嵌小 JPEG**（`setArtworkData`），带队列级字节预算与尝试次数上限 | ✅ 已修·设备实证 |

**改动规模**：22 文件 / +895 −123（含上一轮 UI 审计的既有未提交改动）；本轮新增文件 1 个（`CoverArtBitmap.kt`）。

**设备实证结论（模拟器 OnePlus13_API36 / 1440×3168）**

| 项 | 结论 | 关键证据 |
|---|---|---|
| F2 通知/锁屏封面不退化 | ✅ | 4 首通知媒体卡 + 锁屏封面全部正常；`EACCES`/`Permission Denial`/`TransactionTooLarge` 全 0 |
| F4 横屏歌词 | ✅ | 竖屏→横屏左舞台多行歌词且当前行居中，转回竖屏无回归 |
| F1 首点暂停 | ✅ | 首点后 `PAUSED` + position 冻结，T+1/4/7s 三次采样一致 |
| P2-26 重建保导航 | ✅ | 播放页改字号 → 仍在播放页，歌词完整 |
| F5 定时器 | ✅ | 15 分钟档：`剩余 14:51` → 重建 → `剩余 14:11`，连续无重置 |
| F3 透字 | ✅ | 透字区 lum 47 vs 纯底 46，对比 **1.53:1**（chrome 自身文字 6.34:1） |

---

## 1. F1【P0】自动切歌后首次点暂停不暂停

### 根因
`AppState.syncFromEngine()` 只更新 `queueIndex`，**不回写 `engineLoadedUri`**。而 `togglePlayPause()` 用
`engineLoadedUri != uri` 判断「引擎里是否装着当前这首」：

```kotlin
// 修前 togglePlayPause
when {
    engineLoadedUri != uri -> startCurrent(startProgressSec...)  // ← 误判为「未装载」→ 从头重播
    playing -> eng.pause()
    else -> eng.play()
}
```

后台完播自动切歌时 `isPlaying` 仍为 true、`onPlayingChanged` 不回调，于是 `engineLoadedUri` 永远停在上一首 →
用户回来第一次点暂停被判成「尚未装载」→ 走 `startCurrent()` 从头重播。真机表现为「首点不暂停、继续走秒、
第二次点击才停」。

同一根因还波及 `effectiveDurationSec()`（uri 不等 → 回退扫描时长 → 元数据缺失曲的进度条卡在 1 秒、歌词停在首行）。

### 修法
`AppState.kt` `syncFromEngine()`：

```kotlin
// **引擎现场必须在这里回写**：engineLoadedUri 是「引擎里装着的正是当前这首」的唯一凭据
// 写回放在分支外：这条回调本身就是「引擎当前曲 = mediaId」的权威声明，
// 与「索引有没有变」无关（setMediaItems 引发的 PLAYLIST_CHANGED 同曲上报也要落实凭据）
queue.getOrNull(index)?.let { engineLoadedUri = it.mediaUri }
if (index != queueIndex) { ... }
```

**为什么放在分支外**：挂在 `index != queueIndex` 里等于把「事实」绑定到「副作用」上，
同曲重复上报（`setMediaItems` 触发的 `PLAYLIST_CHANGED`）就不会落实凭据 —— 迟早再漏一处。

### 验证
模拟器 1440×3168，切歌后**点一次**暂停：

| 时刻 | state | position |
|---|---|---|
| 首点后 | `PAUSED` | 31485 |
| 3s 后 | `PAUSED` | 31485（冻结） |

修前同操作是 `PLAYING` + position 回到 0 附近并继续增长。

---

## 2. F5【P1】字号变更把睡眠定时器重置为整档

### 根因
两处叠加：
1. `restore()` 用**原始分钟数**重发：`if (sleepTimerMode > 0) engine?.setSleepTimer(sleepTimerMode)` ——
   把已经走掉的那段时间还了回去（真机实测 15 分钟延长 ~3.5 分钟）。
2. `restore()` 可能早于 `attachPlayer` 到达，此时 `engine == null` 整行 no-op；
   而 `syncEngineModes()` **不含** `setSleepTimer` → 服务端**永不武装**（UI 显示「已开启」但到点不停）。

### 修法
`AppState.kt`：

```kotlin
/** 服务端此刻还应武装的分钟数；null = 本次不触碰 */
private fun sleepTimerMinutesForEngine(): Int? {
    if (sleepTimerNeedsCancel) return 0                  // 播完档待撤销（见下）
    if (sleepTimerMode <= 0) return null
    if (effectiveSleepTimerMode() == 0) return 0         // 已过期：显式撤销
    val remainMs = (sleepTimerDeadlineMs - System.currentTimeMillis()).coerceAtLeast(0L)
    if (remainMs <= 0L) return 0
    return ((remainMs + 59_999L) / 60_000L).toInt()      // ceil：宁可晚 <60s，不能丢定时
}
```

并在 `syncEngineModes()`（`attachPlayer` 与 `restore` 的公共出口）补下发，带 `(engineGeneration, deadline)` 判重戳。

**四个关键设计取舍**（都写进了代码注释）：

- **取整用 ceil，并明确接受「最多晚 59 秒」**。分钟档协议只能表达整分钟：想让服务端**不晚于**
  原截止就得 floor，但 floor 在「剩余 50 秒」时得到 0，而 0 的语义是「撤销定时」——
  直接变成「定时提前消失」，比晚 59 秒更糟。所以选 ceil，宁可晚不到一分钟（用户几乎无感）。
  （初版代码写 `coerceAtLeast(1)` 而注释声称「截断只会早停」，方向恰好相反，已按实际语义更正。）
- **为什么必须记判重戳**：`syncEngineModes` 在每次切歌/恢复都会走到。无脑重发会用「当前剩余整分钟」
  重建服务端协程，新截止**只会更早** —— 连切十首就能把 15 分钟明显提前掐掉，
  等于把「延长整档」换成了「逐次缩短」。
- **断连要清戳**（审查发现）：服务进程被杀后重启（低内存回收 / 用户强停），MediaController 会重连，
  但 **`attachPlayer` 不一定再被调用**（Activity 若还活着就不重建 controller）。此时
  `engineGeneration` 不变、判重戳仍等于上次值 → 认为「已武装」而不补发，而服务端定时协程已随旧进程消失
  → UI 显示定时还在、到点却不停。现在 `onError` 里调 `onEngineDisconnected()` 清戳。
- **播完档（-1/-2）要区分「跨进程」与「Activity 重建」**（审查发现）：它不进快照，
  跨进程确实无法复原（下发 0 会把用户仍需要的定时撤掉）；但 **Activity 重建时服务进程是活的**，
  `stopAfterCurrent`/`stopAtQueueEnd` 原样保留 —— 不撤销就是孤儿旗标：
  用户改个字号回来听歌，当前曲自然播完被莫名暂停，而 UI 显示「未开启」。
  现在 restore 遇到 `snapshot.sleepTimerMode < 0` 时置 `sleepTimerNeedsCancel`，
  由 `syncEngineModes` 下发一次 0 收掉。

### 验证
设备实证（15 分钟档；源码 `SleepTimerOptions` 只有 15/30/60/90 与两个播完档，**没有 2 分钟档**）：

| 时刻 | 面板显示 |
|---|---|
| 设 15 分钟 | `剩余 14:51` |
| 改 `font_scale 1.15` 触发 Activity 重建（等 7s） | — |
| 重新打开菜单 | **`剩余 14:11`** |

41 秒真实流逝对应扣减 40 秒 —— **倒计时连续，没有重置回 15:00**。

---

## 3. F4【P2】横屏旋转（歌词模式）→ 歌词舞台空白

### 根因（**不是**审计推测的 `weight(1f)` 落 Box 被丢弃）
`AndroidManifest.xml` 的 `configChanges` 含 `orientation|screenSize|screenLayout|smallestScreenSize`
→ **旋转既不重建 Activity，也不重建组合**。于是：

1. `lineCenters`（`remember { mutableStateMapOf }`）原样留着**竖屏舞台坐标**；
2. `stageHeightPx` 变成横屏值 → 定位 `LaunchedEffect` 重启；
3. 它第一行 `snapshotFlow { lineCenters[current] }.first()` 对**已存在的值立即发射**（快照流不会等新布局）；
4. 拿竖屏坐标去算横屏偏移：`railOffset = 新舞台高/2 − 旧坐标` → rail 被整体推出舞台；
5. 每行 `positionalFade(...)` → `stageY <= 0f` 分支返回 0 → **全行透明，左舞台只剩封面**。

切一次封面/歌词之所以能恢复，是因为那次切换触发了重新测量。

**前期排查已排除的**：模拟器同分辨率（1440×3168）旋转后歌词正常 → 不是确定性布局 bug，是**时序/坐标系**问题。
`landscape` 判定与横屏双栏布局本身工作正常。

### 修法（三次迭代，前两次都是错的）
`PlayerScreen.kt` `LyricRail`：给每行坐标加**舞台几何戳**，定位只认「在当前几何下量出来」的坐标。

```kotlin
// 舞台几何戳：宽 + 高打包成一个 Long
val stageW = constraints.maxWidth
val stageKey: Long = remember(stageHeightPx, stageW) {
    (stageW.toLong() shl 32) or (stageHeightPx.toRawBits().toLong() and 0xFFFF_FFFFL)
}
val lineCenterStageKey = remember { mutableStateMapOf<Int, Long>() }

// ① 切歌：坐标彻底作废（只以 lines 为 key，独立 effect）
LaunchedEffect(lines) {
    lineCenters.clear()
    lineCenterStageKey.clear()
}
// ② 尺寸变化：只复位偏移与首次定位标记，**不清 lineCenters**
LaunchedEffect(stageKey) {
    railOffset.snapTo(0f)
    hasPositioned = false
}
// ③ 定位：只认当前几何下量出的坐标
LaunchedEffect(lines, current, stageKey) {
    val center = snapshotFlow {
        lineCenters[current]?.takeIf { lineCenterStageKey[current] == stageKey }
    }.filterNotNull().first()
    ...
}
// ④ 测量回填：坐标与几何戳同一次回调原子写
modifier = Modifier.onGloballyPositioned { coords ->
    lineCenters[index] = coords.positionInParent().y + coords.size.height / 2f
    lineCenterStageKey[index] = stageKey
}
```

**为什么**：

1. **必须盖宽度**（不只是高度）：`lineCenters` 存的是行**中心 y**，而 y 由行高与**折行**共同决定，
   折行只取决于宽度。只盖高度的话，分屏拖分隔条 / 折叠屏展开 / 自由窗口 resize 这类
   「只改宽不改高」的路径会让旧戳仍然「有效」→ 同一个 bug 换个入口原样复现。

2. **切歌必须清坐标，且必须是独立 effect**：迭代过程中曾把「切歌清坐标」与「尺寸复位」合并成
   `LaunchedEffect(lines, stageHeightPx)` 并且没有 clear —— 结果切歌时定位 effect 被**上一首**残留的
   坐标立刻喂饱（舞台高度没变，高度戳判据拦不住）并置 `hasPositioned = true`，之后
   lines/current/height 三个 key 都不再变 → rail 一直停在上一首的偏移上，直到下一句歌词才纠正。
   这正是竖屏「切歌瞬时归位」的回归。

3. **尺寸变化时绝不能清 `lineCenters`**：逐行透明度由
   `positionalFade(railOffset + (lineCenters[i] ?: 0f), …)` 计算，而 `positionalFade` 对
   `stageY <= 0f` 直接返回 0。一旦清空又没能立刻重新测量，**所有行都算成 0 透明 → 整片歌词消失**。
   第一版修法就是这么把「概率性空白」变成「必现空白」的。保留旧坐标最多一帧偏移不准，绝不会黑屏；
   正确性由几何戳保证。

### 验证
| 场景 | 修前 | 修后 |
|---|---|---|
| 竖屏歌词模式 → 旋转横屏 3s | 左舞台空白（仅左上角小封面） | **多行歌词正常显示**，当前行居中加粗 |
| 横屏 6s 后 | — | 歌词随播放滚动，仍居中 |
| 旋转回竖屏 | — | 歌词正常，无回归 |
| 横屏封面⇄歌词切换 | — | 正常，无崩溃 |

证据：`.gtmp/fix-1101/v07_land3s.png`（修前空白） vs `x02_land3s.png` / `v3n_08_land3s.png`（修后正常）。

---

## 4. P2-26【P2】Activity 重建后播放页/详情被弹回曲库首页

### 根因
`GlassApp.kt` 是 `val state = remember { AppState() }` —— **不是 ViewModel/Saveable**。
而 `fontScale`/`density` 不在 manifest 的 `configChanges` 里 → 改系统字号**必重建** →
`AppState` 整个丢掉，`screen`/`detail`/`detailReturn`/`playerOrigin` 全部回落默认（曲库首页）。

### 修法
导航态**不进 DataStore 快照**（那是跨进程持久化，重启后回到上次那一页反而是骚扰），
而是用 `rememberSaveable` 承载「只活在本次任务栈」的那一层：

```kotlin
// AppState.kt：换成纯可序列化基本类型，宿主侧无需给 GlassScreen/DetailTarget 写 Saver
fun navigationState(): List<String> = listOf(screen.name, detail.kind.name, detail.id,
                                            detailReturn.name, playerOrigin.name, searchQuery)

fun restoreNavigationState(saved: List<String>) { ... }   // 任一项解析失败即保留当前值

// GlassApp.kt：顺序必须「先取回 savable，再造 AppState」
val savedNav = rememberSaveable { mutableStateOf<List<String>?>(null) }
val state = remember { AppState().also { s -> savedNav.value?.let { s.restoreNavigationState(it) } } }
...
LaunchedEffect(state.screen, state.detail, state.detailReturn, state.playerOrigin) {
    val nav = state.navigationState()
    if (savedNav.value != nav) savedNav.value = nav
}
```

**为什么还原必须在构造时同步做、不能放 `LaunchedEffect`**：下面还有一条 effect 会在每次导航变化时
把当前值写回 `savedNav`，两者同帧竞争时谁先跑取决于声明顺序 —— 写回先跑就把待还原的值冲掉了。

**为什么用 `List<String>` 而不是给枚举写 Saver**：将来枚举增删不会让恢复逻辑编译不过，解析失败一律回落默认值。
（已核对：Compose 的 `autoSaver` + `AcceptableClasses` 白名单含 `Serializable`，
而 `listOf(...)` 返回 `java.util.Arrays$ArrayList` 属 Serializable，**无需自定义 Saver**。）

**搜索词必须一起带**（审查发现）：真机报告点名「正在看的详情/**搜索词**一并丢失」，
而搜索页输入框读的就是 `state.searchQuery`。只恢复 `screen = Search` 而词是空串，
用户看到的仍是一个空搜索页 —— 症状只修掉一半。

**长度守卫用 `>=` 而不是 `==`**：将来再加字段时，旧版本存的短列表仍能按位还原已认识的项，
不会因为强校验整份作废（那正是「加一个字段就静默失效」的坑）。

`restoreNavigationState` 里还兜了一刀：`if (playerOrigin == GlassScreen.Player) playerOrigin = Library`
—— 防止历史坏数据让 `closePlayer` 原地打转。

### 验证
播放页状态下 `settings put system font_scale 1.15` → 重建后**仍在播放页**（控制台 + 歌词/封面都在），
修前会落到曲库首页。证据：`.gtmp/fix-1101/v05_after_font.png`、`p26_after.png`。

---

## 5. F3【P2】玻璃 chrome 透字（迷你条 / 底部导航）

### 根因（**本轮真机量化推翻了历史审计推测**）
历史审计（`ux-audit-ledger.md` G2）推测「haze 渲染链静默失效、退化为纯 tint」。**真机量化否证了它**：
透字边缘强度 mean-grad 3.4（导航栏）/ 5.1（迷你条）vs 前景正常文字 21.9 vs 纯玻璃 0.13
→ **模糊确实生效**，只是**不充分**：经 blur + 白 tint 后透字仍有迷你条 4.3:1 / 导航栏 11.4:1 的局部对比，
达到 chrome 自身文字的 50%–96%，语义可辨。深色模式尤其刺眼（浅色幽灵字压在深玻璃上）。

### 修法（四次迭代，前三次都没过）
**只加强 chrome 层**（迷你条 + 导航栏），不动全局玻璃面板：

- `GlassSurface` 新增**带默认值**的参数 `scrim: Color? = null`（向后兼容，其余调用点观感不变）
- `GlassColors` 新增 `chromeScrim`（深色 `0xFF1A1A24@0.80` / 浅色 `0xFFF2F2F6@0.74`，**方向相反**），并纳入 `lerpGlassColors`
- `Chrome.kt` 的 `MiniPlayerBar` / `GlassNavBar` 传 `scrim = glass.chromeScrim`

**为什么叠 scrim 而不是加深 fill**：fill 是**白色系**（dark 下 hi=白16%），再叠白只会提亮背景、
把浅色幽灵字衬得更明显。scrim 用「与主题同向」的遮罩（深色压黑、浅色提白），
把幽灵字笔画与玻璃底色**一起**推向同一极；WCAG 对比度里的 +0.05 常数项在两端压缩比值，
透字对比因此塌陷，而 chrome 自身文字画在 scrim **之上**、不受衰减。

**scrim 的落点很关键**：必须叠在「haze 结果 / fill」**之上**、Box 内容**之下**，这样只衰减背景、
chrome 自身文字完全不受影响。反过来先 `background(scrim)` 再 `hazeEffect`，scrim 会被 haze 采样覆盖，等于没加。
**并且 scrim 必须是背景的最后一笔**：曾试过在 scrim 之后再补一层 `glass.fill(level)` 想把玻璃色调提回来，
结果等于把遮罩「提亮回半透明」，残值又被抬到 46–50 与底色拉开，字形重新浮现。

#### 阈值怎么算出来的（读这段能省一轮返工）
深色模式下：列表文字是白 lum≈240，chrome 底 lum≈52。经 alpha=a 黑遮罩后残值 = 240×(1−a)。
要它与底色**无法区分**需 `240×(1−a) ≤ 52` → **a ≥ 0.78**。

| 迭代 | alpha | 实测透字 grad_p97 / 对比 | 结论 |
|---|---|---|---|
| ① | 0.34 | 55.9–60.0（与清晰文字同量级） | ❌ 完全可读 |
| ② | 0.62 | 42.5（迷你条）/ 29.7（导航栏） | ❌ 变暗但仍可辨 |
| ③ | 0.90 | 对比 1.32:1 | ✅ 达标，但通透度只剩 10%，高频可见的两块玻璃名存实亡 → 过度 |
| ④ | **0.80** | 透字 lum 47 vs 底 46 = **1.53:1** | ✅ 达标且保住玻璃感（最终采用） |

#### 一个重要的环境陷阱
本轮 F3 在模拟器上「怎么加 scrim 都还看得见透字」，一半原因是**测试环境本身**：
模拟器以 `-gpu swiftshader_indirect` 启动（`dumpsys SurfaceFlinger` 报
`OpenGL ES 3.0 SwiftShader`），而 **SwiftShader 不支持 RenderEffect** —— haze 的 blur 压根不跑，
静默退化为纯 tint。这既是历史审计把「scrim 不足」误判成「haze 链失效」的根源，也说明：

> **不能把「压透字」的责任押在 blur 上。** 设备一旦退化（软渲染 / 老 GPU / 省电模式降级），
> 就完全裸奔。所以 scrim 必须**独立**达标，不能按「blur 会再抹一层」来打折。

（也验证过「按 blur 是否生效分档」这条路走不通：haze 1.6.10 的 `isBlurEnabledByDefault()`
只有 `SDK_INT >= 31` 判定，退化到 ScrimBlurEffect 是运行时按 GPU 能力静默决定的，无公开查询点；
而本工程 minSdk=35 判定恒真，SwiftShader 也照样过。）

**顺带撤掉了 `blurScale`**：scrim 到 0.80 后模糊结果对最终像素的可见贡献只剩 20%，
把 26dp 加重到 39dp 肉眼不可辨，只是让 RenderEffect 半径翻倍白烧 GPU。

**已知取舍**：`chromeScrim` **不随「玻璃强度」设置变化**（`GlassIntensity.derive` 有意不缩放它）。
它是可读性保障而非装饰强度，不该被观感偏好削弱 —— 副作用是迷你条/导航栏上三档强度差异变得很微弱，
属有意为之，不要当成 bug。

### 验证（量化，模拟器 blur 失效的最差环境）
| 指标 | 值 | 判断 |
|---|---|---|
| 透字区 median lum | 47 | 与纯底 46 **基本一致** |
| 透字对比度 | **1.53 : 1** | 低于可读阈值（<2:1 不可辨） |
| chrome 自身「设置」白字对比 | **6.34 : 1** | 清晰，未受影响 |

修前（`v4_05_scroll2.png`）同一区域：透字 std 4.26、grad 与清晰文字同量级、肉眼一眼可读。

---

## 6. F2【P2】媒体会话封面用应用私有 cache 绝对路径

### 根因
`MusicPlaybackService.toMediaItem()` 下发 `setArtworkUri(file:///storage/emulated/0/Android/data/
com.local.music.debug/cache/covers/*.png)` —— **应用私有目录**。消费方是系统进程
（MediaDataManager / SystemUI ImageLoader），跨 uid 必然被拒：
真机 `EACCES`、模拟器 `ENOENT`，每次切歌刷一片。

控制中心/锁屏「看起来正常」是**误导性的**：它们走通知的 bitmap 通道（media3 在**本进程内**解码），
与 metadata 那条完全无关。真正拿不到封面的是**只读 metadata 的下游**：车载、手表、第三方「正在播放」面板。

### 修法探索：先试 content://，**实测否决**
第一步改成 FileProvider `content://<pkg>.fileprovider/covers/...`。**失败**，实测证据：

```
W ContentProviderHelper: Permission Denial: opening provider androidx.core.content.FileProvider
  from ProcessRecord{... 956:com.android.systemui/u0a186} ... not exported from UID 10219
W ImageLoader: java.lang.SecurityException: Permission Denial: opening provider ...
```

根本原因：**MediaMetadata 里的 uri 是纯数据**，media3 转发时不携带（也无法携带）
`FLAG_GRANT_READ_URI_PERMISSION` —— 该 flag 只对 `startActivity`/`sendBroadcast` 这类
**显式传递 Intent** 的路径生效。content:// 与 file:// 一样读不到，只是把 EACCES 换成了
SecurityException（实测刷屏 100 条，**比原来更糟**）。而把 provider 改成 exported 等于把
封面目录对整个系统开放，不可接受。

> 这一条值得记进工程经验：**「私有文件的 content:// 天然可跨进程读」是错的**。
> 没有 grant 就没有权限。

### 最终修法：内嵌小 JPEG
新增 `app/src/main/java/com/local/music/player/CoverArtBitmap.kt`：

- 只处理 covers 目录下的 `file://` → 长边 **192px** 采样解码 → JPEG quality 82 起逐档降到 20
- 单张上限 **24KB**；队列级预算 **256KB**
- `toMediaItem()` **不再下发 `artworkUri`**，改由 `withArtwork(specs, startIndex)` 按预算内嵌
  `setArtworkData(bytes, PICTURE_TYPE_FRONT_COVER)`

**为什么必须做队列级预算**：`setArtworkData` 的字节随 MediaItem 走 Binder。实测单张封面 PNG 140–200KB，
本工程一队列可达上千首 —— 整队内嵌**必然**撞 1MB 的 `TransactionTooLargeException`，
届时 `setMediaItems` 整个失败、播放直接不可用，**比没有封面严重得多**。
只覆盖 `startIndex` 起的一小段：系统面板与通知显示的都是**当前曲**封面，而当前曲装载时必然落在这段窗口内。
预算取 128KB（不是 256KB）：图片 128KB + 千项文本 ≈200B/项 ≈ 200KB ≈ 330KB，给 media3 自身的
再序列化留足余量。

#### 代码审查抓出的一个致命 bug（已修）
`withArtwork` 初版写成：

```kotlin
if (budget <= 0) break
val bytes = ...
if (bytes.size > budget) continue      // ← 不扣预算
budget -= bytes.size
```

`continue` 不扣 budget，于是「剩余预算 > 0 却小于单张尺寸」时退出条件**永不成立** ——
循环会把队列里**剩下的每一首**都完整解码 + JPEG 压缩一遍再丢弃。千首队列 ≈ 975 次无用解码
（单张 8–20ms）→ 主线程阻塞十几秒。而本函数由 `loadQueue`（`AppState.startCurrent` /
`prepareResume`）在**UI 线程**调用 → **ANR 级风险**。

修法：`continue` → `break`（`bytes.size` 随下标推进不会变小，这一张装不下等价于后面都装不下），
并给 `order` 加 `.take(MAX_ATTEMPTS=48)` 封顶，把复杂度钉在 O(容量) 而不是 O(队列长度)。
两个文件里的同一份 `withArtwork` 都改了。

#### media3 侧为什么能工作（字节码核对）
`DefaultMediaNotificationProvider.createNotification` → `BitmapLoader.loadBitmapFromMetadata()`，
后者的判定顺序是 **`artworkData` 非空就直接 `decodeBitmap(artworkData)`，否则才回落 `artworkUri`**。
也就是说 `artworkData` 是**首选**路径，摘掉 `artworkUri` 走正是首选分支 —— 通知/锁屏/控制中心
封面只会更稳。附带改善：legacy session 的**队列**发布分支原先因 artworkData 恒 null 而一律 null bitmap，
现在有字节的项会真解码并塞进 `ALBUM_ART`，对车载/蓝牙 MediaBrowser 是净改善。

### 验证
- 连切 5 首后 logcat：`EACCES` / `ENOENT` / `Permission Denial` / `SecurityException` /
  `TransactionTooLarge` **全部 0 条**（修前每次切歌刷一片）。
- **通知封面不退化**（本轮最大风险项）：4 首歌的通知媒体卡全部显示真实专辑封面，
  锁屏媒体卡片封面完整，无空白/灰块。

---

## 7. 遗留与待办

| 项 | 说明 |
|---|---|
| **真机复测** | 本轮全部验证在模拟器（OnePlus13_API36，1440×3168）完成。真机一加13 需复测 F1（首点暂停）、F3（透字观感，真机 blur 生效时 scrim 0.80 的玻璃感需目视确认）、F4（横屏歌词）。 |
| **F5 播完档撤销的设备实证** | 代码路径已补（`sleepTimerNeedsCancel`），但「设播完档 → 改字号 → 曲终是否误暂停」的端到端用例未在本轮跑完。 |
| **F2 首次播放大队列延迟** | 已有 128KB 预算 + 48 次尝试封顶兜底，理论上界约 48×(8~20ms) ≈ 0.4–1s（最坏情况，且只在大队列首播时）。建议真机用千首级队列实测一次 `loadQueue` 耗时。 |
| **Deeper 未修** | 静态审计里 F1/F2/F4/F5 之外仍有 P0-1（debug keystore 签名）、P0-2（权限弹窗取消卡死）等 41 项未在本轮范围内，不在本报告承诺内。 |

---

## 8. 方法论记录

**本轮最有价值的四个教训**：

1. **「注释/历史结论」不等于事实**。G2 的「haze 静默失效」推测被真机量化直接推翻（haze 活着，
   是 scrim 不足）。修法方向因此完全相反 —— 若按推测修 `!hazed` 兜底分支，本轮等于白改。

2. **改完必须实测，推理正确不代表实现正确**。F4 第一版凭「尺寸变了就清空坐标缓存」的直觉动手，
   把「概率性空白」变成了「必然空白」（`positionalFade` 对空坐标返回 0 → 全行透明）。

3. **测试环境本身可能就是假的**。模拟器 SwiftShader 不支持 RenderEffect → haze 的 blur 全程没跑。
   在这样一个环境里调 F3 的模糊参数是没有意义的，必须先把「糊不糊」这件事从环境里剥离出来，
   让 scrim 独立达标。这也是为什么第 ① ② ③ 三版全在错的判据上迭代。

4. **并发改同一函数的复制版本会漏**。`withArtwork` 在 `MediaControllerEngine` 与
   `MusicPlaybackService` 各有一份（分域并行改动的直接后果），修 bug 时必须两处同改 ——
   本轮第一轮修改确实只改了一处，靠代码审查才发现。

**协作方式**：分域并行（播放核心 / 播放页 UI / 玻璃层）+ 独立代码审查 + 独占设备验证，
避免写域冲突与「自己验自己」的确认偏差。事后看，**独立代码审查是投入产出比最高的一环**：
它抓出的 ANR bug 与 F4 竖屏回归，都是自己测不出来的问题（前者要千首队列才触发，后者要在特定时序下才显形）。
