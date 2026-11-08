# ColorOS 音乐 · 快修轮交付说明（★快修轮）

> 日期：2026-11-08 ｜ 基线：`7e4cd94`（v1.1.0 / versionCode 5）｜ 依据：`docs/2026-11-08-ux-motion-audit.md` §4 第 1、2 项
> 范围：`project/` 6 个文件，+226 / −31 行
> 验证基线：`:app:compileDebugKotlin` BUILD SUCCESSFUL ／ `:app:testDebugUnitTest` + `:core:ui:testDebugUnitTest` BUILD SUCCESSFUL（137 条单测 0 失败）
> **状态：未提交、未装机验证**。改动全部为 UI 层，无数据/快照/接口变更。

---

## 一、本轮做了什么（5 项）

| # | 审计编号 | 改动 | 文件 |
|---|---|---|---|
| 1 | 增-U1 | 「加入歌单」面板内联「新建歌单」，创建即加入当前曲 | `core/ui/.../ActionsSheet.kt` |
| 2 | 增-U1 | 新建歌单后**直达新歌单详情** | `feature/playlist/.../PlaylistScreen.kt` |
| 3 | 2.2-D | 进度条拖动时「已播时长」跟手预览 | `feature/player/.../PlayerScreen.kt` |
| 4 | 增-P1-A | 进度条/迷你条进度线**帧级平滑**（消除 10Hz 阶梯） | `core/ui/.../Chrome.kt` |
| 5 | 增-P2-A | 波形曲线补 easing + 暂停平滑收束（顺带修组合期读动画值） | `core/ui/.../Artwork.kt` |
| 6 | 增-P2-C | 设置页损坏横幅 expand/shrink 补动效强度乘数 | `feature/settings/.../SettingsScreen.kt` |

（表 6 行含拆分的 1 项，实为 5 个审计条目。）

---

## 二、逐项实现要点

### ① 「加入歌单」内联新建（增-U1，消灭 11+ 步断链）

**改动前**：面板只列已有歌单。加歌时想建新歌单要「退面板 → 切歌单页 → ＋ → 输名 → 创建 → 找到新卡 → 点开 → ＋ → 选歌 → 提交」，实测 **11+ 步**（QQ音乐在加入面板底部就有新建行，4-5 步）。

**改动后**：面板底部固定一行「新建歌单」→ 点击展开内联输入框 → 输入名 → 「创建并加入」→ **创建歌单 + 把当前曲加进去 + 关闭面板**，全程不离开当前上下文。

关键决策：
- **默认收起输入框**：不干扰「从已有歌单选一个」这条主路径（面板限高 420dp，多摆一个常驻输入框会挤压歌单列表）。
- **重名预检复用 `playlistTitleExists`**：与独立「新建歌单」面板同口径（含内建/动态歌单），重名时副标题提示且不可提交——绝不静默造第二张同名卡。
- **不引入新状态**：`createPlaylist`（含重名唯一化）与 `addToPlaylist`（含去重）都是既有单点方法。
- **状态自守**：`remember` 的草稿与展开态在 `visible=false` 时清空，与同文件 `AddSongsSheet` 的 reset 同一纪律（组件契约不寄托在调用方式上）。

### ② 新建歌单后直达详情（增-U1 续）

`PlaylistScreen` 两处创建成功分支（IME 确认 / 按钮点击）在 `createPlaylist` 后补 `openDetail(...)`。

**为什么**：旧实现只收面板，用户在歌单墙里要自己找到新卡再点开才有「＋添加歌曲」；空歌单尤其没有下一步提示。详情页顶栏 ＋ 就是唯一「加歌」入口，直达等于把下一步摆在用户面前。`openDetail` 会把 `detailReturn` 记为当前屏（歌单页），返回即回原位。

### ③ 进度预览接线（2.2-D）

`ProgressTrack` 的 `onPreview` 回调早已存在（Chrome.kt 的 P3-64 专为此加，文档写明「供已播时长跟随手指变化」），但**全仓没有任何调用点传过它**——拖动时轨道跟手、时间数字一动不动，松手才跳。

**接法**：PlayerScreen 持有 `mutableStateOf<Float?>(null)` 预览态，`onPreview = { previewFraction.value = it }`；`ElapsedTimeText` 改为接收该 State 引用并在**内部**读值（拖动中显示 `preview * durationSec`，否则显示真实 `progressSec`）。
**关键的为什么不直接传 Float**：拖动中该值每帧变，若在控制台作用域读它，整条控制台（模式栏/播放键/队列入口）每帧重组——正是本仓明令禁止的模式。传 State 引用后，重组域仍只有「一张文本」。

### ④ 进度帧级平滑（增-P1-A）

**问题**：`state.progressSec` 只由 tick 每 100ms 推进，进度轨与迷你条进度线绘制期直读该快照 → 长曲目下肉眼可见「一格一格跳」。Apple Music / QQ音乐是帧级平滑。

**实现**：新增 `rememberSmoothProgress(progressProvider)`，用 `Animatable` 承载「显示进度」，`snapshotFlow` 收到新目标后线性追赶（时长 = 心跳周期 100ms）。因为动画本身是帧级的，每帧落在两次心跳之间 → 阶梯被抹平为匀速。

三个必须的边界处理：
- **大跨度直接对齐**（>5% 行程：seek/切歌/拖动提交）：若照常「追」，用户 seek 到副歌会看到进度条从头爬过去，比阶梯更糟。
- **`collectLatest` 而非 `collect`**：新目标到达即取消未跑完的 `animateTo`，从当前值重新追——保证「永远追最新值」，不会出现动画比心跳慢而逐个积压的时序漂移。
- **`rememberUpdatedState` + `LaunchedEffect(Unit)`**：调用方通常写 `{ state.progressFraction }` 这种每次重组新建的 lambda，若当 key 会每次重组都重启协程。以 `Unit` 为 key、内部读最新值。

**性能**：值只在绘制期读（`onDrawBehind` 内 `smoothProgress.value`），**重组频率与改动前完全一致**；代价仅一条播放中活跃的单值动画。主进度轨与迷你条各持一份实例（独立组合节点，共享会互相打断动画）。
**读屏不受影响**：`ProgressSemantics` 仍读真实 `progressProvider()`——无障碍不需要帧级平滑。

### ⑤ 波形两处漏网（增-P2-A）

原实现三个问题一次重写解决：
1. **曲线漏网**：`tween(550, delay)` 未传 easing → 落 Compose 默认 `FastOutSlowInEasing`，是全仓唯一违反「统一曲线」纪律的点。现补 `GlassMotion.Curve`。
2. **暂停硬跳**：`playing=false` 时直接取常量 `mutableStateOf(min)`，四条竖条从各自任意高度瞬跳到最低。现在同一 `Animatable` 收束（`tweenHover`），暂停时自然「落下去」。
3. **组合期读动画值**（附带发现）：旧写法 `val h by ...` 在组合期解构 State，与文件内「零重组」的宣称相悖——无限动画下等于 60fps 重组本行子树。现在把 `Animatable` 实例交给 `graphicsLayer` 绘制期读。

**为什么不能用 `animateFloatAsState` 追无限动画的值**：那会让它每帧追一个每帧都变的目标，起伏被平滑成滞后，等于把波形抹平——必须由协程直接驱动（`while(true)` 往返 `animateTo`）。

### ⑥ 设置页横幅时长（增-P2-C）

`expandVertically`/`shrinkVertically` 原用裸 `tween(GlassMotion.Page, Curve)`——**没乘动效强度乘数**，而同一次转场的 `fadeIn/Out` 用的是 `tweenPage()`（按档缩放）。「增强」档下 expand 360ms 固定 / fade 504ms，两者不同步收束，观感是「方框先动、内容抢跑」。统一为 `GlassMotion.tweenPage()`。

---

## 三、验证证据

```
:app:compileDebugKotlin            BUILD SUCCESSFUL
:app:testDebugUnitTest             BUILD SUCCESSFUL
:core:ui:testDebugUnitTest         BUILD SUCCESSFUL   （137 条，0 失败）
```

**构建环境记录（踩坑）**：
- 本机 `GRADLE_USER_HOME` 必须指向 `C:/Users/a/.gradle`（默认缓存，含 compose 编译器插件）。
- 首次尝试误指向工作区 `.ghome`（空缓存）→ `plugin classpath entry points to a non-existent location: kotlin-compose-compiler-plugin-embeddable-2.2.20.jar`。
- 必须用 `gradle.bat` + `$env:JAVA_HOME=...jdk-17.0.20.1+1`；PowerShell 里 `gradle`（无 .bat）接管道会报 `Cannot run a document in the middle of a pipeline`。
- 沙箱只读模式下 Gradle 原生服务初始化会失败（`Failed to load native library 'native-platform.dll'`），需可写环境。

---

## 四、未做 / 需真机验证

**本轮刻意未做**（属后续轮次）：
- 手势四件套（面板拖拽关闭 / 播放页下滑收起 / 迷你条上滑展开 / 歌词⇄封面跟手）——报告 §4 第 3 项，工程量最大。
- 反馈层（操作轻提示 + 撤销 + 触觉反馈）——§4 第 4 项。
- 歌词时间轴重构（连续驱动 + 拖拽浏览）——§4 第 5 项。
- spring 化、跨屏状态保持、队列 Lazy 化。

**必须真机验证的三处**（UI 行为，编译通过不等于体感正确）：
1. **进度平滑**：拖动进度条是否仍跟手（拖动期走 `dragProgress` 分支，理论上不受平滑影响，但需确认松手瞬间无回跳）；seek 大跨度是否瞬时对齐而非「爬过去」。
2. **波形**：暂停时四条竖条是否平滑落到最低而非硬跳；播放恢复时是否顺滑起振；四条错峰相位是否与原观感一致。
3. **加入歌单内联新建**：键盘弹起时面板是否被 `imePadding` 正确顶起；创建后是否确实出现在歌单墙且含当前曲；重名时是否拒绝提交。

**回归风险点**：
- `rememberSmoothProgress` 在**空曲库**（`hasSongs=false`）时迷你条进度线仍走 `if (hasSongs)` 分支置 0，未受平滑影响——已核对。
- 演示模式（无 `mediaUri`）下 `progressFraction` 由 tick 模拟推进，平滑同样生效，无副作用。

---

## 五、下一步建议

按 `docs/2026-11-08-ux-motion-audit.md` §4：
- **先装机验证本轮 5 项**（尤其上述三处），再决定是否提交。
- 若本轮体感通过，下一轮建议上 **§4 第 3 项「手势四件套」**——这是与 Apple Music 手感差距的最大单项，且需要一次性的「手势驱动动画值」抽象，越早做越省返工。
- 「点歌语义统一」（曲库点歌是否进播放页）、「返回键是否清搜索词」、「迷你条进入是否记忆歌词模式」三项需产品拍板，建议在动手前定。
