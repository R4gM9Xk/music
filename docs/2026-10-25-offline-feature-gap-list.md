# 离线功能差距清单（对照成熟商用音乐 App 基线）

日期：2026-10-25。基线 = 九模块离线功能清单（本地曲库 / 播放引擎 / 音效 / 队列歌单 / 歌词 / 离线内容 / 持久化 / 系统集成 / 稳定性），逐项对照本工程实际代码。

**工作区现状（先读）**：2026-10-24 审计后已开修一批，**全部停留在工作区未提交**（10 文件修改，无新 commit）：批次 1 的 P1②③⑤⑥、批次 2 状态边角全部、批次 4 更新链全部已落；P1①④、批次 3（haze 结构）、批次 5（性能）未动。详见文末「审计修复进度」。另：MANAGE_EXTERNAL_STORAGE（.lrc 方案 A）已实现进 Manifest，旧记录中「待拍板」已过时。

图例：✅ 已实现（打磨过）｜🟡 有但浅/有已知问题｜❌ 缺失

## 1. 本地曲库

| 项 | 状态 | 说明 |
|---|---|---|
| 全目录扫描 + SAF 自定义目录 | ✅ | MediaStore 不限 Music/ + qqTreeUri 文档树合并入库 |
| 增量扫描 | ❌ | 每次全量；有 library_cache（v2）作冷启动缓存，但非增量机制 |
| 格式与参数 | ✅ | MediaExtractor 取 codec/采样率/位深/码率，真值进 UI |
| 元数据广度 | 🟡 | 标题/艺术家/专辑/时长/日期有；genre 只兜底「独立」不真扫，无年份/音轨号/碟号；乱码修复无 |
| 内嵌封面 + 取色 | ✅ | 封面落盘缓存（已治理：dataPath 稳定哈希命名 + 孤儿清扫）+ Palette 五色联动极光 |
| 文件夹 cover.jpg | ❌ | 只取内嵌 picture |
| 分组视图 | 🟡 | 歌曲/专辑/艺术家/文件夹四视图 + 中文拼音 Collator 排序；流派视图缺（数据就没扫） |
| 过滤规则 | 🟡 | 「过滤文件夹」排除目录有；短音频阈值、隐藏文件过滤无 |
| 大曲库性能 | ❌ | 审计批次 5 未修：歌曲 tab 是 Column+verticalScroll 非懒加载，`sortedSongs()` 每重组全库 Collator 重排，艺术家 tab 每行 O(n×m) 分组（LibraryScreen.kt） |

## 2. 播放引擎

| 项 | 状态 | 说明 |
|---|---|---|
| 服务端播放架构 | ✅ | ExoPlayer + MediaSessionService，服务队列权威，队列编辑不打断播放（多轮实机打磨） |
| 音频焦点 | ✅ | handleAudioFocus=true + setHandleAudioBecomingNoisy（拔耳机暂停） |
| 播放模式 | 🟡 | 顺序/单曲/列表循环/随机 + shuffle 插队序修正；无智能随机（本轮不重复）；循环/随机持久化刚补进快照 v4 |
| 状态恢复 | ✅ | 恢复链路完整度是本工程最强项：快照/pendingPlayback/服务权威/原子起始位置/hydrated 门控，多轮大修 |
| 错误处理 | ✅ | 播放错误横幅、断连心跳检测（刚修）、引擎时长兜底元数据缺失（刚修） |
| WAKE_LOCK | ✅ | 审计后刚补（Manifest + setWakeMode） |
| 定时关闭 | ❌ | 全工程无 SleepTimer，商用 App 标配缺失项 |
| 变速不变调 | ❌ | 无 PlaybackParameters |
| 淡入淡出 / gapless | ❌ | 无 crossfade / setPauseAtEndOfMediaItems |
| 响度归一（ReplayGain） | ❌ | 无 |
| 系统续播入口 | ❌ | onPlaybackResumption 未实现（P3） |

## 3. 音效

| 项 | 状态 | 说明 |
|---|---|---|
| 均衡器 | 🟡 | 5 频段自定义增益表 + 4 预设（Pop/Rock/Classic/Vocal），READY 后挂载、onConnect 声明、异步失败留痕（刚修）；预设硬编码，无用户自定义曲线 |
| 其他音效 | ❌ | 无低音增强/虚拟化/声道平衡 |

## 4. 队列与歌单

| 项 | 状态 | 说明 |
|---|---|---|
| 队列编辑 | ✅ | 插队/最后播放（shuffle 下 pinLast 显式意图，刚修）/拖拽重排/多选删/清空，全程不打断播放 |
| 用户歌单 | ✅ | 建/加/重排/移除/重命名 + 长按集合菜单 + 专辑/艺术家交叉导航 |
| 内建歌单 | ✅ | 可删可恢复，计数实时化（刚修 P1⑥） |
| 收藏 / 最近播放 | ✅ | favorites + playedIds 去重历史 |
| 最常播放 / 播放次数 | ❌ | 无 playCount 统计 |
| M3U 导入导出 | ❌ | 歌单无法跨应用/跨设备迁移 |
| 空队列崩溃 | 🟡 | 审计 P1① **未修**：QueueSheet `coerceIn(0,-1)` 必崩（Sheet.kt:276），空曲库设备点迷你条进播放页即触发 |
| 拖拽陈旧闭包 | 🟡 | 审计 P2 未修：pointerInput 键不含数据版本，长拖中自动切歌用陈旧索引提交 |

## 5. 歌词

| 项 | 状态 | 说明 |
|---|---|---|
| LRC 加载 | ✅ | 同名 .lrc（MANAGE_EXTERNAL_STORAGE 全目录可达）+ 应用专属 files/lrc/ 兜底；offset 负号约定正确 |
| 展示 | ✅ | 逐行滚动、行点击 seek、位置衰减整行淡出、空态「纯音乐，请享受」、左右滑切封面模式 |
| 内嵌歌词 | ❌ | USLT/LYRICS 标签从未实现 |
| 逐字歌词 | ❌ | 无 QRC/KRC 解析 |
| 翻译/音译行 | ❌ | 无 |
| 桌面歌词 | ❌ | 无（国产 ROM 高频需求） |

## 6. 离线内容管理

纯本地应用，无在线曲库可缓存，此模块基本不适用。自有等价物：应用内更新链已加固（sha256 fail-closed、PK 魔数 + 1MB 下限、并发防护、release 变体、release.sh 三修——批次 4 刚落，未提交未发版）。

## 7. 数据持久化

| 项 | 状态 | 说明 |
|---|---|---|
| 快照持久化 | ✅ | DataStore + org.json v4：队列现场/歌单/收藏/排序/循环随机/隐藏歌单/EQ/主题/玻璃与动效档位；防抖 + flush + hydrated 门控 + v1 起全链迁移 |
| 曲库缓存 | ✅ | library_cache v2（含 dateAdded），启动先渲染再补扫 |
| 备份/恢复/导出 | ❌ | 换机只能手工拷 datastore 文件；歌单无用户可见导出 |

## 8. 系统集成

| 项 | 状态 | 说明 |
|---|---|---|
| 前台服务 + 媒体通知 | ✅ | 封面/进度/控制，ColorOS 锁屏岛实测 |
| MediaSession / 锁屏控制 | ✅ | 标准路径完整 |
| 耳机/蓝牙 | 🟡 | 标准线控 + 断连暂停有；无线控按键自定义映射 |
| 桌面小部件 | ❌ | feature/widget 只剩展示页源码不参与构建，全工程无 Glance/AppWidgetProvider |
| Android Auto | ❌ | 无 automotive 配置 |
| 自启动引导 | ❌ | 无 ColorOS 自启动管理引导页 |

## 9. 稳定性与体验

| 项 | 状态 | 说明 |
|---|---|---|
| 启动门面 + 空态 | ✅ | librarySettled 门面、占位曲防空队列越界、权限拒绝有引导；系统设置手动授权返回自动装载（刚修） |
| 深浅色 / 取色 / 个性化 | ✅ | 三态主题 + 封面取色极光 + 玻璃三档 + 动效三档，全部持久化 |
| 无障碍 | 🟡 | 主体有（Tab/Switch/进度语义）；GlassTabs 缺 Role.Tab/selected（P3） |
| 旋转适配 | 🟡 | 审计 P1④ **未修**：旋转后飞行替身用陈旧 rect/snapshot，横屏收起落点错位 |
| 多语言 | ❌ | 无 strings.xml，文案全部硬编码中文写在 Composable 里——商用成熟度的显性门槛 |
| haze 结构 | 🟡 | 审计批次 3 未修：全部 Sheet 效果层位于 app 级源子树内（铁律①系统性绕开），重叠期串台风险 |

## 审计修复进度（截至本文档）

已落工作区（**未提交、未见装机验收记录**）：
- 批次 1 的 4/6：P1② 返回键（GlassApp BackHandler + modalState.isOpen）、P1③ 重扫偷换队列（loadLibrary 服务权威重构）、P1⑤ WAKE_LOCK、P1⑥ 内建歌单计数
- 批次 2 全部：循环/随机入快照 v4、pendingPlayback 终态清理、ON_RESUME 音频权限复查、attachPlayer 不打断两分支、pinLast 显式意图、封面缓存治理、durationSec 引擎时长、断连心跳、EQ 异步留痕、onDestroy 释放顺序、未知命令返回错误
- 批次 4 全部：release 变体、sha256 fail-closed、https 强制、PK 魔数 + 1MB 下限、check/startUpdate 防抖、install 缺包提示、Failed 文案与重试分流、release.sh 三修（JSON 转义/失败回滚/自检 2xx 判定）
- 零散 P3：onOpenPlayer 忙时兜底、演示搜索历史清空、prev() 漏存

未动：P1①（Sheet.kt 空队列崩溃）、P1④（旋转飞行）、批次 3（haze sheet 结构统一）、批次 5（曲库懒加载/排序缓存/拖拽闭包）、其余 P3。

## 补齐优先级建议

1. **先收尾已修批次**：装机验收（模拟器 + PJZ110）后提交——10 文件改动只存在于工作区，是当前最大风险。
2. **P1① 一行级快赢** + 批次 5 性能（LazyColumn + 排序缓存，大曲库刚需）。
3. **深度音频组**按感知价值排序：定时关闭（实现量最小、用户感知最高）→ 淡入淡出 → 变速不变调。智能随机/响度归一可后置。
4. **strings.xml 抽取 + 多语言**：商用门槛，越晚抽越贵。
5. **M3U 导入导出 + 快照备份**：用户资产迁移。
6. **桌面小部件**：国产 ROM 高频诉求，Glance 实现量中等。
