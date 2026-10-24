# 全量审计报告（2026-10-24）

6 个只读审计代理分片精读全部 32 个 Kotlin 文件（HEAD=v1.0.2/versionCode 3），每片带历史坑档案做「修复仍位验证」。结论先行：**历史修复零回归**（各片清单全 ✅），无 P0 安全洞，无明文 HTTP，权限最小；新发现 P1×6、P2×19、P3×20+。三处最要害发现已由主会话亲核源码属实（Sheet.kt:276 / GlassApp.kt:286 / AppState.kt:554）。

## P1（6 项）

| # | 问题 | 位置 | 置信度 |
|---|------|------|--------|
| 1 | **QueueSheet 空队列必崩**：`currentIndex.coerceIn(0, queue.lastIndex)` 在队列空时 `coerceIn(0,-1)` 抛 IAE；该行在 PlayerScreen 组合体无条件执行，无需打开面板。空曲库设备（权限拒/无音频）点迷你条进播放页即崩 | Sheet.kt:276（组合点 PlayerScreen.kt:438） | 确认（已亲核） |
| 2 | **曲库页按返回键直接退出应用**：`BackHandler(enabled = screen != Library)` 把曲库页返回拦截整体禁用，排序面板/单曲/集合菜单开着时按返回 → Activity finish | GlassApp.kt:286 | 确认（已亲核） |
| 3 | **重扫把 UI 队列偷换成整个曲库**：`loadLibrary` 的 playingSong 分支 `queue = songs`，违反同文件 attachPlayer「服务队列即权威」注释；恢复的歌单/搜索语境被整库覆盖，tick 每 5s 把整库队列写盘。同函数 else 分支无条件 `queueIndex=0; progressSec=0; resumePending=false`——控制器连接慢于重扫时恢复现场丢失（A 片 P2 并入同修） | AppState.kt:552-566 | 确认（已亲核） |
| 4 | **旋转后飞行落点错位**：manifest configChanges 吞掉旋转（不重建组合），`keepOnDispose=true` 的 7 个迷你条 rect 与冻结 snapshot 无任何尺寸变化失效机制 → 横屏收起时替身飞向竖屏旧坐标 | Flight.kt:196-199 + Chrome.kt keepOnDispose 各点 | 机制确认，表现待实测 |
| 5 | **缺 WAKE_LOCK 权限**：`setWakeMode(WAKE_MODE_LOCAL)` 静默失效（media3 仅打 warning 不持锁），息屏连播曲间可能断流，ColorOS 激进省电下更明显 | AndroidManifest.xml（对照 MusicPlaybackService.kt:47） | 确认 |
| 6 | **内建歌单计数恒「0 首」**：resolvePlaylist 非 user- 分支保留目录硬编码 meta；加歌后歌单墙/详情头/加入歌单选择行三处仍「0 首」而曲目列表已真实 | AppState.kt:1107-1112 + DemoCatalog.kt:74-79 | 确认 |

## P2（按主题）

### 恢复链路与状态
- **循环/随机模式完全未持久化**：快照无 shuffleOn/repeatMode 字段、mutator 无 scheduleSave、不在 settingsKey 指纹——三重漏存，重启必丢（AppState.kt:35-54/798-818）。
- **扫描空/失败时 pendingPlayback 永不清除**：扫到 0 首早退不清 pending，tick 演示分支被冻结 → 播放键假死（AppState.kt:592 + GlassApp.kt:140-147）。
- **系统设置手动授 READ_MEDIA_AUDIO 返回不触发装载**：ON_RESUME 只复查 MANAGE_EXTERNAL_STORAGE（GlassApp.kt:177-194）。
- **durationSec 兜底 1s 钳真实进度**：元数据双失败时 `coerceAtLeast(1)`，tick 把 4 分钟进度钳到 ≤1s，快照存错值（MediaLibrary.kt:77-78 + AppState tick）。改用引擎时长。

### 播放服务边角
- **attachPlayer 两条分支违背「不打断播放」**：正在播的歌不在缓存里时 `stopAndClear()` 掐断；loadLibrary 到达且 playingSong==null 时重发队列但没置 playing=false → UI 假显示播放中（AppState.kt:340、563-569）。
- **shuffle pinLast 队尾误判**：`index >= mediaItemCount` 用位置猜意图，当前曲在队尾时「下一首播放」被当「最后播放」（MediaControllerEngine.kt:166）。改显式传意图。
- **封面缓存无限增长 + 键用会漂移的 MediaStore _id**：删歌/重扫产生孤儿 png，无任何清理（MediaLibrary.kt:100、120-127）。改稳定 id 命名 + 扫后清扫。
- **MediaController 无 onDisconnected 监听/重连**：服务 stopSelf 后引擎调用全部静默 no-op，UI 假死无提示（MediaControllerEngine.kt）。

### 玻璃/haze 结构（铁律①系统性绕开）
- **全部 Sheet 效果层位于 app 级 hazeSource 子树内**：Player/Playlist/Detail/Settings/Library 的 sheet 都消费 app 级状态（唯 Search 合规在 provider 内）——正是档案记录的串台结构，常态被 haze 1.6 容忍，与重叠期叠加风险最高。统一提升 sheet 宿主到 GlassApp 根层可一并解决下两条。
- **Detail/Search 自绘 Chrome 面板打开时不让位**：不读 LocalModalState，面板玻璃与迷你条/导航 hazeEffect 重叠（PlaylistDetailScreen.kt:222-243、SearchScreen.kt:177-198）。
- **ModalState 在 visible 翻转瞬间注销**（注释称退场动画后）：关闭面板时 Chrome/控制台提前 360ms 复位，与退场 sheet 重叠（Sheet.kt:104-110）。
- **Playlist/Settings 屏自有 hazeSource 零消费者**：每帧整屏录制纯开销（PlaylistScreen.kt:68-75、SettingsScreen.kt:78-85）。
- **浅色光标 seed3 近不可见**：Field.kt:79 应改 `auroraAccentColor()`（SongRow 同类修复的漏网处）。

### 飞行/播放页
- **飞行期透明层全部可交互**：graphicsLayer alpha 不挡触摸，双击迷你条可误触透明控制台（PlayerScreen.kt:306-308 等）。
- **飞行中切歌重快照捕捉 mid-animation 封面 rect**：entryShowCover 清除触发 modeT 500ms 动画与起飞重快照竞态（Flight.kt:170）。

### 性能
- **曲库页整库非懒加载 + 每重组全库 Collator 重排**：歌曲 tab Column+verticalScroll 一次性组合；`sortedSongs()` 在组合体内每次重排；艺术家 tab 每行调 albumGroups() O(n×m)（LibraryScreen.kt:78/104/140）。改 LazyColumn+key+remember 缓存。
- **队列拖拽陈旧闭包**：pointerInput 键不含数据版本，长拖中自动切歌后用陈旧 safeIndex 提交（Sheet.kt:334 + ReorderableList.kt:76）。

### 更新链（安全加固）
- **分发 debug 构建**：release.sh 产 assembleDebug，用户装的都是 debuggable=true + 无混淆包（release.sh:22）。加 release 变体（debug 签名侧载可装）。
- **sha256 fail-open**：清单缺 sha256 字段时静默跳过校验照装；gh-proxy 等第三方代理可裁剪 hash 绕过（UpdateManager.kt:168）。改 fail-closed。
- **startUpdate/check 无并发防护**：双击并发双协程写同一 tmp / 并发多链检查（UpdateManager.kt:40-55、75-77）。
- **38MB APK 逐版入 git**：每发一版 +37MB 永久历史，v1.0.1/1.0.2 已 77MB（release.sh:42-46）。设计代价，建议知悉或改 Releases 附件。
- **install() 文件缺失静默 return**：状态机卡 ReadyToInstall 无提示（UpdateManager.kt:100）。

## P3（打磨清单）

播放：onPlaybackResumption 未实现（进程死后系统入口无法续播）；loadMetadata 无超时（损坏文件拖慢整轮扫描）；双扫无互斥（writeCache 非原子并发写）；EQ sendCustomCommand 异步失败静默（历史 EQ 失效通道无兜底）；onDestroy 先 player 后 session 释放顺序；exported 服务对未知 customAction 返回 SUCCESS。

状态：SettingsStore 每次 Activity 重建泄漏旧 scope（白名单外配置变更才触发）；服务连接失败后演示假播放无重连；演示搜索历史预置 4 条假词（真曲库下全空结果）；prev() 演示分支缺 scheduleSave；legacy v1 迁移按逗号拆 id（路径含逗号错拆，仅一次性迁移）；内建歌单 Recent 卡片计数与详情不一致（未过滤失效 id）。

UI：歌单无重名校验；GlassTabs 缺 Role.Tab/selected 语义；数字开头标题非自然序（1,10,100,2）；搜索无关键词高亮；队列段头 34dp 近似常量误差 ~8dp + 大 fontScale 撑破 66dp 行高；深浅切换三处硬切（Sheet 内衬/极光混合模式/themeFraction 未乘动效强度）；GlassTheme 三重动画叠加取色迟滞翻倍；GlassToggle 白滑块浅色对比不足；GlassTextField 无焦点态；播放键/替身顶线硬编码 White 50% 未用 token。

工程：死代码若干（DemoCatalog.searchSongs 族、AppState.releasePlayer、FluidShowcaseScreen 含 20 处硬编码白、Flight.kt 不可达语句）；release.sh 三处（changelog 未转义进 JSON 可断全量更新/构建失败不回滚跳号/自检 curl 非 200 仍成功）；下载不可取消不复用已验证包；Failed 文案与重试行为错位；APK URL 未强制 https；allowBackup 未显式关闭；Log.d 残留（MediaLibrary.kt:33/52/111）；local.properties 已正确忽略。

## 修复仍位验证（历史坑零回归）

- A 片 8 项全 ✅：hydrated 门控（首装 markHydrated）、pendingPlayback 窗口期回写、曲库缓存先行+librarySettled 门面、稳定 id "f路径"、服务队列权威、SettingsStore org.json/flush/防抖、decode 兼容、恢复现场链。
- B 片 9 项全 ✅：onConnect 自定义命令声明（EQ+队列命令都在）、setMediaItems 原子起始位置、EQ READY 挂载+重放、CMD_INSERT_SHUFFLE_FIX 实现、removeMediaItems 双参、队列编辑不打断播放、封面落盘+Serializable、MediaExtractor 正确键、缓存 v2。
- C 片 8 项中 7 ✅ 1 ⚠️：rect 注册表清理/keepOnDispose、双影像交叉淡出、enteringWait mini 替身+几何闸门、中止路径原子清理、entryShowCover 语义、歌词 NPE 防护（已随重构消除）、歌词 rail Layout——唯 GlassApp 自身 onOpenPlayer 缺忙时兜底（Detail/Search 有）。
- D 片：clip→haze 顺序全工程 7 处全合规 ✅；浅色 token 体系/极光几何/GlassMotion 全 ✅；「效果层=源兄弟」铁律在 Chrome 层 ✅ 但 sheet 层系统性绕开（见 P2）。
- E 片 7 项全 ✅：sha256 时序、versionCode 整数比对、三链 HTTPS+5s、半包清理、FileProvider 窄范围、Manifest 权限最小、release.sh 无半提交。安全面总评：无 P0、无明文 HTTP、无 analytics；短板集中在 debug 分发与 sha256 fail-open。
- F 片 8 项全 ✅：四组件唯一实现红线、ReorderableList 唯一实现+冲突修复、排序 Collator+兜底、SongRow 全项、ProgressTrack、搜索全项、a11y 主体、队列面板定位。

## 建议修复批次（v1.0.3 候选）

1. **崩溃+P1 快赢**：Sheet.kt coerceIn 防空、BackHandler 加 modalState.isOpen 条件、loadLibrary 按服务队列权威重构（一处收敛 #3 与 resumePending 重置）、WAKE_LOCK 一行、内建歌单 meta 实时化、飞行旋转失效（根尺寸变化 flight.cancel + 清 rects）。
2. **状态边角**：循环/随机入快照、pendingPlayback 终态清理、ON_RESUME 复查音频权限、attachPlayer 不打断两分支、pinLast 显式意图、封面缓存治理、durationSec 用引擎时长。
3. **haze 结构统一**：sheet 宿主提升到 GlassApp 根层（一并解决铁律①违规、Detail/Search 让位、ModalState 注销时序），删零消费者 source，光标 auroraAccentColor。
4. **更新链加固**：release 变体切换、sha256 fail-closed + 下载通过条件收紧、check/startUpdate 防抖、release.sh 三修（jq 生成 JSON/失败回滚/curl 判定）。
5. **性能与打磨**：曲库 LazyColumn+key+排序缓存+分组预计算、拖拽闭包 rememberUpdatedState、P3 清单按需挑选。

每批次完成后按既有流程装机验收（模拟器 OnePlus13_API36 + 真机 PJZ110）。

---

# 修复实施记录（2026-10-25）

按上述批次用 6 个修复代理分两波实施（Wave 1：状态层 S / 播放链 P / 更新链 U；Wave 2：haze 层 H / 飞行系统 F / 性能 R），文件所有权互斥并行，波间编译验证。**已修 26 项：P1 全部 6 项 + P2 15 项 + P3 5 项**。debug+release 双变体 BUILD SUCCESSFUL。

## 主会话补修（代理波次的遗漏/集成错误）
- **P1#1 空队列崩溃**：分波时遗漏未派发，装机首日实测坐实（权限拒→冷启动即崩，比审计描述更严重——冷启动默认屏就是 Player）。修法 `Sheet.kt`：`if (queue.isEmpty()) 0 else currentIndex.coerceIn(0, queue.lastIndex)`。
- Wave 2 三处集成错误：Sheet.kt 缺 `mutableStateOf` import；Flight.kt awaitRects 删「不可达语句」实为 lambda 类型推断锚，重写为 `while (stable == null)` 带尾表达式结构；PlaylistScreen `?.tracks.size` 空安全笔误。

## 各代理修复落点（摘要）
- **S（AppState/GlassApp/Manifest/SettingsStore）**：曲库页 BackHandler 补 `|| modalState.isOpen`；loadLibrary 重扫按 `eng.mediaIds()` 重建队列+三路分支（pending/rescan 保留语境/全新装载），不再整库偷换、不再无条件清 resumePending；WAKE_LOCK 权限；resolvePlaylist+visibleBuiltInPlaylists 计数实时化；快照 v4（VERSION 3→4）+shuffleOn/repeatMode 字段+mutator 补存+restore 末尾 syncEngineModes；markLibrarySettled 清 pendingPlayback；ON_RESUME 复查 READ_MEDIA_AUDIO（remember 记忆授权态）；attachPlayer 删 stopAndClear 分支；effectiveDurationSec() 引擎时长优先；GlassApp onOpenPlayer 忙时兜底；searchHistory 初始空。
- **P（Engine/Service/Library）**：addQueueItems 三参契约（pinLast 显式意图，bundle 键名不变）；checkConnected() 心跳断连检测（onError 横幅）；EQ sendCustomCommand 异步失败 Log.w 留痕；coverFileFor(dataPath 哈希命名)+sweepCovers 扫后清扫孤儿；onDestroy 先 session 后 player；未知 customAction 返回 RESULT_ERROR_BAD_VALUE；删高频 Log.d。
- **U（UpdateManager/SettingsScreen/release.sh/build.gradle.kts）**：release 变体（同 applicationIdSuffix .debug + debug 签名，侧载即更新）；sha256 fail-closed+下载通过条件收紧（≥1MB+PK 魔数）；check/startUpdate 并发防护；install() 文件缺失转 Failed；清单 URL 强制 https；release.sh 版本号正则校验+json_str 转义（python json.dumps 优先）+trap 回滚+curl 2xx 判定；Failed 文案区分检查/下载失败且重试行为对应。
- **H（Sheet/Field/Detail/Search/Playlist/Settings）**：Detail/Search 自绘 Chrome modalState.isOpen 时 alpha=0 让位（保留组合护 keepOnDispose 几何）；GlassSheet 注销延迟到退场动画结束（LaunchedEffect+delay tweenPage，中途重开防 openCount 泄漏）；QueueSheet onMove 经 rememberUpdatedState 读实时 safeIndex；光标改 auroraAccentColor()；Playlist/Settings 删零消费者 hazeSource；Recent 卡片计数与详情同源。
- **F（Flight/Overlay/GlassApp/PlayerScreen）**：onRootSizeChanged()（飞行中取消+清 rects，GlassApp 根层 onSizeChanged 触发）；飞行期根 Column 挂 PointerEventPass.Initial 全消费 pointerInput（Idle 零开销）；起飞前重快照复用 awaitRects 双帧判稳；删不可达语句/Overlay snap 容错/t 加注释。
- **R（LibraryScreen/AppState 排序/Lists）**：六个 tab 全部 LazyColumn+稳定 key（歌曲 items(key=id)、分组 item(key=前缀)；尾部避让 Spacer 保留为 item）；sortedSongs remember(library,songSort) 缓存+比较器缓存；艺术家 tab albumCountByArtist 预计算；GlassTabs 补 Role.Tab+selected 语义。

## 装机验收（模拟器 OnePlus13_API36，全新安装，截图 glass-v11/01~19）
- **P1#1** 拒绝权限冷启动不崩（实测曾崩→修后 crash 0）✅
- **P1#2** 曲库页面板开+返回键=关面板不退出，Chrome 退场后回归 ✅
- **P1#3** Pop 2 曲语境起播→设置页重扫 11 首→队列面板仍「2 首」语境完好 ✅
- **P1#6** 加入歌单→深夜巡航「1 首」实时计数，其余内建 0 首真实空态 ✅
- **P2 循环持久化**：切循环→force-stop→重启循环高亮保持、玻璃风铃@0:09 暂停恢复 ✅
- **P2 ON_RESUME 授权**：系统设置手动授权→返回自动装载 11 首+缓存生成 ✅
- **更新链**：检查更新真实联网三链→「已是最新版本」（versionCode 整数比对）✅
- 全程 logcat -b crash = 0；真播放（MediaSession PLAYING position 推进）、飞行进出、悬浮 Chrome 让位/回归均正常。

## 遗留（本轮未做/新发现）
- P3：queueSource（「来自『Pop』」语境标签）未入快照，重启后显示默认「本地音乐」（队列数据本身恢复正确）——新发现小瑕疵；
- P3 清单未修项：onPlaybackResumption、歌单重名校验、搜索高亮、自然数字排序、双扫互斥、SettingsStore scope 泄漏等（见上文 P3 列表）；
- sheet 宿主整体提升到 GlassApp 根层（结构性重构）留作后续；
- 38MB APK 逐版入 git 为设计代价未处理；release.sh 切换后旧 update.json/apk 链接在下次发版时整体重写；
- 旋转后首次收起走直切兜底（F 代理的「无旧坐标 vs 有动画」取舍，第二次进出恢复正常）。
- **未提交未推送**；发版（release.sh，将产出 release 变体 APK）待用户确认。
