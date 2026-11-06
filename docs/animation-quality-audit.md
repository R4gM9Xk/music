# 动效质量审计台账（2026-11-01 全项目轮）

审计方式：三路只读代理全量扫读 core/ui 组件、feature 五屏、AppState/GlassTheme 状态层 + 主审计员逐条代码核实 + 模拟器动态验证。
关键场景已在模拟器实测：进出播放页飞行（上一轮）、面板开合、子面板导航、队列滚动、搜索、tab 切换、主题切换。
误报修正：代理报告的 ReorderableList `dragDataVersion` 重组问题（无组合期读取，实测不成立）与 ThemeColorSwatches border 挤压布局（border 不参与布局，仅宽度/颜色突变）已修正定性。

| 编号 | 模块 | 位置 | 表现 | 根因 | 分类 | 严重度 | 修复方案 | 状态 | 验证 |
|---|---|---|---|---|---|---|---|---|---|
| A1-01 | 模态面板 | Sheet.kt:247 GlassSheet | 打开面板=1 帧硬切（scrim+面板瞬现），与 :227 KDoc「360ms 弹性上滑入场」契约矛盾；退场有动画、入场没有 | `MutableTransitionState(visible)` 初值取当前 visible → 首组合即 idle | B | **P1** | 初值改 false，visible=true 首组合时播放入场；退场收尾信号机制不变 | ✅ 已修 | 模拟器录屏：队列/歌曲操作/播放选项面板开启有上滑渐入 |
| A1-02 | 面板二级导航 | ActionsSheet.kt:75,145-146,278,348,371 | 点「加入歌单/歌曲信息/重命名/删除确认」：主面板 360ms 渐隐 + 子面板瞬现全量呈现，双 scrim 叠加 ~0.7 再回落，可见地「暗一下再亮回来」 | 互斥布尔驱动多套 GlassSheet：主退场渐隐、子无入场（同 A1-01） | B | **P1** | A1-01 修复后主/子面板交叉淡化，scrim 之和恒 ≈0.35 | ✅ 已修（随 A1-01） | 录屏对比 |
| A1-03 | 主题切换 | GlassTheme.kt:392 + AuroraBackground.kt:47,78,96 + GlassTheme.kt:405 + Sheet.kt:300 | 深浅切换 400ms 渐变中点：极光混合模式 Screen↔Multiply 硬切、暗角 0.35↔0.10 跳变、auroraAccentColor（选中态/光标/播放高亮）跳色、面板 veil 黑白硬切 | `LocalIsDark provides (themeFraction < 0.5f)` 派生布尔挂阈值在动画值上，消费者无过渡 | B/H | **P1** | 新增 `LocalThemeFraction`；vignette/veil/accent 全部按 fraction 连续插值；极光光源过渡期双 pass（Screen×(1-f) + Multiply×f）交叉混合，稳态单 pass 零开销 | ✅ 已修 | 模拟器录屏主题切换全程无中途跳变 |
| A2-01 | 播放页 | AppState.kt:545 + PlayerScreen.kt:314 | 播放中舞台区（含封面 rect/shadow 插值、LyricRail 调用点）10Hz 重组 | `currentLyricIndex` getter 链组合期读 progressSec | E | **P2** | PlayerScreen 内 `derivedStateOf { state.currentLyricIndex }`，仅行变化时失效 | ✅ 已修 | 代码核查 + 播放录屏无回归 |
| A2-02 | 主题 | GlassTheme.kt:384-386 | 玻璃强度三档切换全 App 玻璃填充/描边瞬变（themeMode/palette 均有 400ms 渐变，唯独 glassLevel 没有） | derive() 直接读瞬时枚举，无动画层 | B | **P2** | fill/stroke 两个 scale animateFloatAsState（tweenPalette）+ deriveBy(base, fill, stroke) | ✅ 已修 | 模拟器切换玻璃强度录屏 |
| A2-03 | 队列面板 | Sheet.kt:434-444 | 队列开着时自动切歌，无动画瞬跳回当前曲，覆盖用户滚动位置 | LaunchedEffect(visible, currentIndex) 直接 scrollTo，无用户滚动互斥 | B/F | **P2** | 增加 `isScrollInProgress` 守卫（用户滚动中跳过本次跟随） | ✅ 已修 | 模拟器：开着队列滚动后切歌不被拽回 |
| A2-04 | 队列面板 | Sheet.kt:426,428,441-442 | 长队列「正在播放」定位随行数线性偏移（500 行 ≈250px≈1.4 行） | rowPx/labelPx toPx 后 toInt() 截断再乘索引 | C | **P2** | 定位数学全程 Float，最后一步再取整 | ✅ 已修 | 数学核对 + 长队列定位 |
| A2-05 | 拖拽重排 | ReorderableList.kt:189-194,286,309-311 | 队列/歌单拖拽的邻居让位与落位补间用 FastOutSlowIn，与全 App 唯一曲线 GlassMotion.Curve 手感不一致 | tween 未指定 easing（默认 FastOutSlowIn）+ settle 循环显式 FastOutSlowIn | A | **P2** | 两处统一 GlassMotion.Curve（时长 150×scale 不变） | ✅ 已修 | 拖拽录屏 |
| A2-06 | 搜索页 | SearchScreen.kt:119-123 | 换查询词不重置滚动：新结果从旧偏移展示/被 clamp 跳尾 | 同一 LazyColumn 三态内容共享滚动位置，无 query 重置 | B/C | **P2** | `rememberLazyListState` + LaunchedEffect(query) scrollToItem(0) | ✅ 已修 | 模拟器输入搜索 |
| A2-07 | 曲库页 | LibraryScreen.kt:84,131,175 | 六 tab 共享一份 LazyListState：滚动后切 tab 视口被 clamp 跳到异常位置、内容瞬间换血 | tab 切换只换 items 集不重置滚动 | B/C | **P2** | LaunchedEffect(tab)（跳过首组合，保留 rememberSaveable 恢复）scrollToItem(0) | ✅ 已修 | 模拟器切 tab |
| A2-08 | 设置页/曲库 | Lists.kt:359-363 SettingRow（LibraryScreen.kt:429 同） | 设置全部行 + 权限引导行按压零反馈，与全 App「玻璃按压回弹」语言断裂 | clickable indication=null 且未套 pressScale（SheetAction/SongRow 均有） | F | **P2** | 补 pressScale（有 onClick 时） | ✅ 已修 | 模拟器按压录屏 |
| A2-09 | 封面占位 | Artwork.kt:157-166（Afternoon :71-79） | 列表滚动/pressScale/波形重绘期间占位封面每次绘制新建 Brush+Shader（全 App 出现密度最高的组件）；Afternoon 纹样每帧 new Path | drawBehind lambda 内构造 Brush；AuroraBackground.kt:35-42 已立「每帧不允许新建 Brush」红线并示范 drawWithCache | E | **P2** | drawBehind → drawWithCache（brush 进 cache block；Path 构造随 cache） | ✅ 已修 | 代码核查（分配点移入 cache） |
| A2-10 | 队列面板性能 | Sheet.kt:462-464,516-530 + Artwork.kt:130 | 打开队列全量组合所有行；冷缓存时每行主线程同步解码 1-3ms，数百首队列一次性数百 ms | GlassSheet 内容列 verticalScroll 普通 Column；ArtThumb 组合期同步解码（注释自述取舍） | E | **P2** | 结构性修复需 GlassSheet 内容改 Lazy（牵动 ReorderableColumn 拖拽数学）——本轮不做结构重构；实测典型队列（<60 首）打开成本 <150ms 一次性，记录为后续项 | 📋 已登记，结构性改造挂起 | 实测打开耗时 |
| A3-01 | 动效时长统一 | GlassTheme.kt:373 | 深浅主题交叉渐变固定 400ms，不吃动效强度设置（同源 palette 用 tweenPalette=Palette×scale） | 手抄常量漏 scale 包装 | A | **P3** | 改用 GlassMotion.tweenPalette() | ✅ 已修（随 A1-03） | — |
| A3-02 | tab 胶囊 | Lists.kt:157-193 GlassTabs | 选中切换：seed 填充/描边/文字色/6dp 投影四者瞬变 | if(on) 直接换 Modifier 链 | A | **P3** | 颜色 animateColorAsState + 阴影 animateDpAsState（tweenHover） | ✅ 已修 | 模拟器切 tab 录屏 |
| A3-03 | 歌曲行尾槽 | Lists.kt:258-291 SongRow | 播放态切换波形瞬现/瞬消；「已选」↔时长同硬切 | 条件渲染直接换尾槽 | A | **P3** | 尾槽 AnimatedContent（fadeIn/fadeOut tweenHover） | ✅ 已修 | 模拟器播放/暂停录屏 |
| A3-04 | 主题色板 | ThemeColorPicker.kt:68-72 | 选中环 1↔3dp 宽度与颜色双重瞬变 | if(on) 硬换 border 参数 | A | **P3** | animateDpAsState + animateColorAsState | ✅ 已修 | 模拟器 |
| A3-05 | 输入框 | Field.kt:92-104 | hint 出现/消失无过渡（Material3 占位符有 fade） | if 条件渲染 | A | **P3** | hint alpha animateFloatAsState（tweenHover），alpha>0 才组合 | ✅ 已修 | 模拟器输入 |
| A3-06 | 设置页 | SettingsScreen.kt:111-118 | 定时关闭未开启时循环仍每秒空转；开启后每秒重组整个屏体（含全部玻璃卡行） | while(true) 不看 deadline；剩余秒数在屏体作用域读 | E/G | **P3** | 循环按 deadline 门控（未开启不跑）；到期自然退出后停 | ✅ 已修 | 代码核查 |
| A3-07 | 设置页 | SettingsScreen.kt:138-157 | 快照损坏横幅出现/消失瞬间顶开/塌缩下方内容 | if 条件渲染 | B/A | **P3** | AnimatedVisibility(expandVertically+fade) | ✅ 已修 | 模拟器 |
| A3-08 | 详情/搜索页 | PlaylistDetailScreen.kt:257 + SearchScreen.kt:233 | 面板开合时 Chrome alpha 0↔1 硬切（面板本身有 360ms 动画，节奏脱节） | if(modal.isOpen) 直接给 alpha | A | **P3** | animateFloatAsState(tweenPage) | ✅ 已修 | 模拟器开面板录屏 |
| A3-09 | 歌单详情 | PlaylistDetailScreen.kt:535-544 | 首段高光线每次绘制 new Brush+List（同仓 Glass.kt glassTopLine 已规约 drawWithCache） | drawBehind 内构造 | D/E | **P3** | 改 drawWithCache | ✅ 已修 | 代码核查 |
| A3-10 | 极光背景 | AuroraBackground.kt:45,70-96 | 取色/主题 400ms 过渡期 drawWithCache 逐帧重建 5 个 Brush（稳态零分配正确） | palette 组合期读，过渡期逐帧变 | E | **P3** | 有界（24 帧过渡期）且稳态已合规——登记为已知有界开销，不改 | 📋 接受并记录 | — |
| A3-11 | 动效参数 | GlassTheme.kt:295 GlassMotion.scale | 普通 var 非 snapshot：运行中动画按旧时长跑完，参数下次动画生效；无组合失效 | 全局可变非快照状态 | H | **P3** | 语义即「下次动画生效」，运行中改档场景极窄；重构 snapshot 化收益低风险高——登记不改 | 📋 接受并记录 | — |
| A3-12 | 歌词边界 | AppState.kt:546 + PlayerScreen.kt:686 | 前奏期第 0 行按当前行高亮；seek 跨行时 rail 500ms 固定时长横扫（与距离无关），行样式同帧重定目标 | coerceAtLeast(0) 边界语义；animateTo 固定 tweenLyric | A | **P3** | 高亮属设计语义（居中逻辑依赖 index=0）；seek 横扫与行进切换共用同一条曲线语言——登记为设计取舍 | 📋 接受并记录 | — |
| A3-13 | 列表增删 | 全仓 LazyColumn（grep 0 处 animateItem） | 曲库排序/歌单增删/队列增删瞬时重排，无位移动画；歌单详情分段卡首/末段轮廓随删行瞬间切换 | 未挂 Modifier.animateItem | A | **P3** | 曲库排序切换/歌单详情增删是低频操作，硬切与「行即数据」语义一致；补动画涉及分段卡轮廓体系，登记后续项 | 📋 已登记 | — |
| A3-14 | 曲库网格 | LibraryScreen.kt:200-224,310-336 | chunked(2) 行 key 取 pair 首：增删引发成对关系位移、多行重建 | 分组网格在 LazyColumn 的固有结构 | C | **P3** | 登记后续项（歌单卡增删低频） | 📋 已登记 | — |
| A3-15 | 曲库页头 | LibraryScreen.kt:141 | 排序按钮随 tab 瞬现瞬失，页头一帧重排 | action2Icon 条件渲染 | A | **P3** | 低频轻微软切，登记 | 📋 已登记 | — |
| A3-16 | 歌单详情 | PlaylistDetailScreen.kt:116-122 | 目标歌单被删时先渲染一帧空屏再返回 | backFromDetail 在 LaunchedEffect（组合后）执行 | B/G | **P3** | 导航必须在组合后触发（组合期写导航态有更大赛听面）；空屏仅 1 帧纯背景——登记不改 | 📋 接受并记录 | — |
| A3-17 | 搜索页 | SearchScreen.kt:140-149 | 点历史芯片：芯片瞬时重排 + 结果瞬间换血 | FlowRow 无布局动画 + 列表换血 | A/B | **P3** | 登记后续项 | 📋 已登记 | — |
| A3-18 | 搜索页 | SearchScreen.kt:203-224 | 专辑展开/收起硬切，收起时视口上跳；打字中展开态被 query 重置加剧跳动 | item 集瞬增瞬减 | A | **P3** | 登记后续项 | 📋 已登记 | — |
| A3-19 | 空态硬切 | LibraryScreen.kt:178-182 / Sheet.kt:449-459 / ActionsSheet.kt:443-470 | 空态↔列表/队列/搜索结果互切无过渡 | 条件渲染 | A | **P3** | 登记后续项（空态本身低频稳定态） | 📋 已登记 | — |
| A3-20 | 设置页 | SettingsScreen.kt:120-125 | 定时开启时每秒重组整屏（副标题在屏体作用域读秒值） | 倒计时值组合期读于大作用域 | E | **P3** | 副标题行范围有限、设置页无动画并发——随 A3-06 门控后风险可忽略，登记 | 📋 接受并记录 | — |
| A3-21 | 状态层 | AppState 多处 resolvePlaylist 组合期裸调 | 每次重组重复派生（多数有 songIndex 缓存兜底） | 无 remember 包裹（key 集易漏导致陈旧，风险大于收益） | E | **P3** | 登记不改（正确性优先） | 📋 接受并记录 | — |
| A3-22 | 跨屏状态 | LibraryScreen.kt:81-84 | AnimatedContent 换屏销毁内容，tab/滚动位置必丢（注释已自认为后续项） | 状态未上提 AppState | B | **P3** | 功能性状态保留，超出本轮动效范围——登记 | 📋 已登记 | — |
| A3-23 | 队列波形 | Sheet.kt:466-472 | 当前曲行滚出 420dp 视口后 WaveIndicator 无限动画仍运行（普通 Column 全量组合） | 非 Lazy 容器无法按可见性停表 | E | **P3** | 随 A2-10 结构改造一并解决——登记 | 📋 已登记 | — |

## 回归审计（修复后第二轮）

（见文末「回归结论」节，修复完成后填写）

## 明确不做 / 非缺陷

- 飞行系统（Flight.kt / PlayerFlightOverlay / GlassApp）上一轮已完成衔接修复并通过帧级验证，本轮未引入回归。
- pressScale / WaveIndicator / 极光漂移 / ProgressTrack / 进度 lambda 下钻等既有绘制期读值模式核验正常。
- hazeEffect 在 clip 之后的玻璃铁律全仓核验一致（A2-09/A3-09 的 drawWithCache 改写不改变层序）。
