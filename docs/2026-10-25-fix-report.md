# 审计问题修复记录（基于 2026-10-25 复核结论）

> 基线：HEAD `852d9b7`（源码 = 审计基线 `2c95774`）
> 范围：仅修改源码与脚本，**未改版本号、未提交、未发版**
> 验证：`:app:assembleRelease` + `:app:assembleDebug` 均 BUILD SUCCESSFUL（clean 后全量构建 3m33s）

---

## 一、修复清单

### P1（发布阻塞）

| # | 问题 | 修复方式 | 文件 |
|---|---|---|---|
| 1 | **全库封面常驻 OOM**：`loadCache`/扫描把每条封面解码进 `DemoSong.coverBitmap` 随库常驻，1000 首≈244MB / 3000 首≈715MB / 5000 首≈1.19GB，且首帧前同步解 | 扫描与缓存**都不再解码位图**，只落 `artworkUri`；新增 `ArtworkBitmapCache`（LRU，总预算 24MB，单张降采样至长边 ≤256px），`ArtThumb` 在真正要画时才解码 | `MediaLibrary.kt`、新增 `ArtworkBitmapCache.kt`、`Artwork.kt` |
| 2 | **文件夹封面裸解码**：`decodeFile` 全尺寸无 `inSampleSize`，同目录每首重复解；3000² 图单张 34MB，N=15 → 515MB | 新增 `decodeSampledCover()`：两趟解码（先 bounds 算 `inSampleSize`，再真解），长边压到 256px；并按**目录**缓存缩略位图，同目录只解一次 | `MediaLibrary.kt` |
| 3 | **换图不覆写**：`persistCover`/`persistFolderCover` 见文件存在即跳过 → 内嵌封面或 cover.jpg 换图后，通知栏与重启后 in-app 永远旧图 | 两处改为**总是覆写** | `MediaLibrary.kt` |
| 4 | **B4 删图不回退 / 换图不更新**：reuse 分支只查缓存 PNG 存在，从不探源 `cover.jpg`，B4 两条验收路径都必失败 | 新增 `reusableFolderCover()`：用**磁盘 mtime 比较**判新鲜度（不用内存指纹表——它每次扫描清空，目录内第一首必然"无历史可比"）<br>· 源图不存在 → 删缓存 PNG + 回退无封面<br>· 缓存不存在 → 落全量分支重提取（自愈）<br>· 源图比缓存新 → 删缓存重提取 | `MediaLibrary.kt` |
| 5 | **清缓存后封面永久消失**：`externalCacheDir` 被清后 reuse 分支只置空、不重提取，指纹未变故永不恢复 | reuse 分支新增 `coverLost` 判定（缓存记录有封面但磁盘 PNG 缺失）→ **不 continue**，落全量提取分支自愈 | `MediaLibrary.kt` |
| 6 | **飞行动画中止后导航劫持**：`onRootSizeChanged` 只翻 phase 不 cancel 协程，残留协程尾部 `navigate(Library)` 把用户拽回曲库 | entry/exit **各持一个 Job** + `generation` 代际计数；`cancelJobs()` 在中止/新飞行开始时取消；协程内所有副作用节点（navigate/清状态）先校验代际，不符则静默退出 | `Flight.kt` |
| 7 | **更新包无签名校验**：`install()` 只检查文件存在；release 用公开 debug keystore 签名 → 任何人可伪造更新包覆盖安装，sha256 防不住（清单与 APK 同通道可同时篡改） | 新增 `verifyApkSignature()`：比对 `getPackageArchiveInfo` 与当前包的签名集合（hex 字符串集合，避免 `ByteArray.equals` 引用比较坑），**fail-closed**（读不到=失败）；校验失败删除包 + 明确中文原因 | `UpdateManager.kt` |

### P2

| # | 问题 | 修复方式 | 文件 |
|---|---|---|---|
| 8 | **幽灵曲库**：扫描 0 首时不写缓存 → 旧缓存残留已删曲目，`librarySettled` 使纠正分支永不触发，只能清数据 | `loadRealLibrary` 按 `result.isSuccess` 分流：成功→无论空不空都写缓存（空库写空数组覆盖）；失败→不写（避免权限异常清掉好缓存） | `GlassApp.kt` |
| 9 | **冷启动绕过 B3 过滤**：`loadCache` 无过滤参数，且 `writeCache` 刻意补回被滤短音频 → 默认开过滤的库冷启动会先闪出通知音 | `loadCache` 加 `filterShort` 形参并走 `outputFiltered`；调用方传 `SettingsStore.readScanFilterShort(context)` 与扫描侧同源 | `MediaLibrary.kt`、`GlassApp.kt` |
| 10 | **睡眠定时播完档双侧不同步**：UI 归零不下发服务端 → `stopAfterCurrent` 成孤儿旗标，恢复播放后当前曲完播仍被无故暂停 | `onPlayingChanged` 归零时补 `engine?.setSleepTimer(0)` 撤销服务端旗标 | `AppState.kt` |
| 11 | **重命名绕过重名拦截**：新建查重、重命名不查 → 可造出两个同名歌单，后续同名合并落到意料之外的歌单 | UI 层：`duplicateName` 三态（空白/重名/正常）+ `commitRename` 守卫；数据层：`renamePlaylist` 内部也查 `playlistTitleExists`（排除"改成自己原名"无害情形） | `ActionsSheet.kt`、`AppState.kt` |
| 12 | **allowBackup 未关闭**（两轮都没执行，`git log -S` 证实从未写过） | manifest 加 `android:allowBackup="false"` + `android:dataExtractionRules`；新增 `data_extraction_rules.xml` 排除私有文件域 | `AndroidManifest.xml`、新增 `data_extraction_rules.xml` |

### P3

| # | 问题 | 修复方式 | 文件 |
|---|---|---|---|
| 13 | **动态歌单守卫不全**：两处硬编码 `Favorites \|\| Recent` 漏 `MostPlayed` → 加歌无效 + 删除空跑 | `DemoCatalog.dynamicIds` 集合集中登记；两处改 `in dynamicIds`，一处根除 | `DemoCatalog.kt`、`ActionsSheet.kt`、`PlaylistDetailScreen.kt` |
| 14 | **替身 alpha 依赖 t==0**：中止后 t 残留使等待期替身 alpha≈0 → 迷你条闪没 | `enteringWait` 时 alpha 直接取 1f；Exiting 侧给 `t<=0 → 1f` 下限，消除"替身与真身同时隐身"双隐闪 | `PlayerFlightOverlay.kt` |
| 15 | **.lrc 路径算错**：对整条路径 `substringBeforeLast('.')`，`/a.b/song`（无扩展名）→ 算成 `/a.lrc`，还可能错读同目录别的歌的歌词 | 改为 basename 剥扩展名后再拼同级路径 | `MediaLibrary.kt` |
| 16 | **release.sh 三处** | 见下 | `release.sh` |

---

## 二、release.sh 修复细节

| 问题 | 原行为 | 现行为 |
|---|---|---|
| push 失败留本地 commit | commit 已落地，重跑 `versionCode` 再 +1 → **跳号**，且半成品 APK/update.json 堆在历史 | 记录 `PRE_PUSH_HEAD`，push 失败即 `git reset --soft` 回滚 commit（再用 `git restore --staged` 清索引） |
| EXIT trap 砸手工改动 | `git checkout -- app/build.gradle.kts` 是**整文件回滚**，会静默丢弃开发者的其它手工改动 | 改为「先 `cp` 整份备份到 mktemp，失败时原样拷回」，只靠 `GRADLE_DIRTY` 标志控制是否还原；push 成功后置 0 不再还原 |
| 自检失败误报发版失败 | push 成功后 curl 第三方代理，非 2xx 就 `exit 1` → 发版成功却报失败，运维重跑踩跳号 | 改为**只告警不失败**（退出码保持 0），并说明"推送已成功，代理可能抖动/限流/CDN 未刷新" |
| curl 失败时 `CODE` 为空串 | 打印 "HTTP "（空），网络不通与 500 无法区分 | 打印 `${CODE:-无响应}` |

**已实测验证**（临时脚本，用后已删）：
- 场景 A：源图仍在且缓存较新 → 复用 ✔
- 场景 B：删掉 cover.jpg → 回退 + 删缓存 ✔
- 场景 C：换掉 cover.jpg → 重提取 + 删旧图 ✔
- 场景 D：清空 externalCacheDir → 自愈 ✔
- 场景 E：B4 正向（首次放图无缓存）→ 走提取 ✔
- release.sh：构建失败回滚版本号后，**手工改动完整保留** ✔

---

## 三、影响面与行为变化

### 视觉/交互变化（均为修复预期内）
1. **封面改为按需解码**：列表滚动时首次出现某封面会解一次 256px PNG（约 1~3ms），之后命中 LRU。**不再有首帧前全库解码的启动卡顿**。
2. **「最常播放」详情页的「＋添加歌曲」按钮消失、长按菜单的「删除歌单」消失** —— 与「收藏/最近播放」行为对齐（这两个动作对动态歌单本就无效）。
3. **重命名同名歌单会被拦下**，副标题显示"已有同名歌单"，保存按钮不再高亮。
4. **选「播完当前首」后手动暂停/来电，定时会真正取消**（不再出现"取消了却还是停了"）。

### 需要真机验收的点（代码层已无法再验证）
| 项 | 验收方法 |
|---|---|
| B4 文件夹封面 | ①放 cover.jpg 到某无内嵌封面的专辑目录 → 重扫应显示；②删掉后重扫应回退为占位；③换一张图重扫应更新 |
| 清缓存自愈 | 设置→应用→存储→清除缓存 → 重启 App → 封面应自动重取（不是永久消失） |
| OOM | 万首级曲库滚动列表 + 反复进播放页，观察 `adb shell dumpsys meminfo` 是否稳定（不再随曲目数线性增长） |
| 飞行中止 | 播放页点收起 → 500ms 内旋转/分屏 → 应停在库页且**不被弹回曲库**（旧版本会） |
| 更新签名 | 手动放一个不同签名的 APK 到 `files/update/app-update.apk` → 点安装应被拒绝并提示"签名校验失败" |
| allowBackup | `adb shell dumpsys backup | grep -A3 com.local.music` 应显示不允许备份 |
| 睡眠定时 | 设「播完当前首」→ 来电接听 → 挂断 → 面板应显示"未开启"，且本曲播完**不再**暂停 |

---

## 四、未修复项（本轮范围外，建议后续）

| 项 | 原因 |
|---|---|
| tick 全库派生泄漏（搜索页/详情页每 0.1s 全库扫） | 属性能优化，非发布阻塞；且改动面涉及 `derivedStateOf` 重构与 `progressFraction` 读取位置下移，风险高于收益，建议单独一轮 |
| `onPlaybackResumption` 丢模式（shuffle/循环/倍速/EQ） | 需扩展 `queue_state.json` schema + 服务端应用逻辑，属功能增强 |
| MediaController 断连不重建 | 需引入 controller 重建生命周期管理，改动面较大 |
| `CMD_INSERT_SHUFFLE_FIX` 静默丢歌 | 需客户端检查 `SessionResult` + 服务端错误回传，跨进程协议改动 |
| 对比度（8/10 预设不达 WCAG AA） | 属设计系统调整，需产品决策（改 seed 或改前景色策略） |
| F2 HSV 滑杆缺失 | 需新增控件 + 色相/饱和度交互设计 |
| apk/app-debug.apk（36.9MB，冻结于 v1.0.2）仍在 git | 需 `git rm` + 可能的历史清理，建议发版后单独处理 |

---

*本轮改动 17 个文件 + 3 个新增文件，共 +431 / -89 行。未改 `versionCode`/`versionName`，未 commit。*
