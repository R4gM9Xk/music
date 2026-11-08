# 游戏助手接入（音乐卡片可见性）· 2026-11-08

> 基线：`faacbc3` ｜ 范围：2 个文件（`AndroidManifest.xml`、`MediaControllerEngine.kt`）
> 验证：`:app:assembleDebug --rerun-tasks` BUILD SUCCESSFUL ／ APK 内 manifest 逐项核对

---

## 一、先把技术边界说清楚（这决定了能做什么）

用户要求「接入各品牌手机的游戏助手」。需要先纠正一个常见预期：

**各品牌游戏助手都没有对第三方 App 开放的接入 SDK。** ColorOS 游戏助手、MIUI 游戏加速、
vivo 游戏魔盒、华为应用助手都是**厂商私有系统应用**，没有公开 API 让普通 App
「注册进游戏助手」。网上流传的各种「游戏模式 API」基本上都是这三种东西之一：
厂商内部接口（需要系统签名）、已废弃的旧 API、或者干脆是营销话术。

**所以「接入」唯一可行的技术路径是：把本应用做成一个「标准、完整的音乐应用」，
让游戏助手在它自己的那套识别逻辑里被正确识别和读取。** 游戏助手显示音乐卡片的方式
是去查系统的 `MediaSessionManager` / 音乐应用列表，然后拉取会话元数据渲染。
我们能做的是把这条路铺好，而不是去对接一个不存在的 SDK。

本轮据此补齐了三个**真实存在、可验证**的缺口。

---

## 二、三个缺口与修复

### ① 应用没有声明「音乐应用身份」★ 最关键

**缺口**：`AndroidManifest.xml` 的 `MainActivity` 只声明了 `CATEGORY_LAUNCHER`，
**没有 `CATEGORY_APP_MUSIC`**。

**为什么这是关键**：
- `CATEGORY_APP_MUSIC` 是系统识别「这是一个音乐 App」的**唯一标准方式**；
- 各厂商游戏助手列「游戏内可用音乐」时，候选来源之一就是按这个 category 查
  `PackageManager` 得到的音乐应用集合；
- 车机、手表、系统的「音乐」快捷入口同样按这个类别筛选。

**为什么之前没写**（诚实记录）：旧实现**只在 `sessionActivity` 的运行时 Intent 上**
加了 `CATEGORY_APP_MUSIC`（见 `MusicPlaybackService`）。那是 Intent 的 category，
**与 Manifest 里的组件 category 声明是两回事**——`PackageManager` 查询"有哪些音乐应用"
读的是后者；前者只影响那一次跳转的解析。所以这个 category 一直存在于代码里，
却从未让系统知道本应用属于音乐类。**这是一个典型的「看起来做了、实际没生效」。**

**修法**：新增**独立的** intent-filter。不能与 LAUNCHER 合并——合并后
`PackageManager` 会把它当成「启动器入口的附加分类」，而非独立的音乐应用声明。

### ② 媒体按钮接收器缺失

**缺口**：没有声明任何 `MEDIA_BUTTON` receiver。

**为什么影响游戏场景**：media3 的 `MediaSessionService` **不会**自动注册
MEDIA_BUTTON receiver。没有它时，系统把媒体键事件发到 `AudioManager` 后，
若本应用会话不是「最近活跃」，事件会落到 null 而丢失。
表现就是**「戴着蓝牙耳机打游戏，耳机上的播放/暂停键没反应」**——
游戏期间本应用一定不是最近活跃的，所以这个场景几乎必然复现。

**修法**：用 media3 官方的 `MediaButtonReceiver`。不自己处理
`ACTION_MEDIA_BUTTON` 的 DOWN/UP 配对与长按去抖——手写那段极易在长按时连发多次 next。

### ③ 队列元数据缺时长

**缺口**：`SongSpec.durationMs` 在扫描期就有，但 `toMediaItem()` **从未写进 metadata**。

**为什么影响游戏助手**：系统从 `MediaItem` 的 metadata 里**不读**时长；ExoPlayer 要在
真正 prepare 完当前曲之后才填 `player.duration`。而各厂商游戏助手 / 车机 / 蓝牙设备
的音乐卡片，绝大多数是**按队列元数据一次性拉取列表**（封面/歌名/时长），
拉取那一刻队尾那些还没 prepare 的曲目时长全是 UNKNOWN。

表现：游戏助手的进度条只在当前曲上正常，列表里其它曲显示 `--:--`，
有的面板**干脆因为拿不到时长而不渲染进度条**。

**修法**：`if (durationMs > 0L) setDurationMs(durationMs)`。
- 用 `> 0` 判断而非无条件写：0/负数 = 扫描时取不到（未知），此时**不写**保持 UNKNOWN
  语义。写 0 会被系统当成「零长度音频」，比未知更糟（进度条直接满格）；
- 时长用 Long 毫秒，与 media3 的 `METADATA_KEY_DURATION` 口径一致。

---

## 三、已有基础（本轮复用的部分，无需改动）

这些在之前的轮次已经做对了，游戏助手能正常读取：

| 能力 | 状态 | 说明 |
|---|---|---|
| `MediaSession` + `MediaSessionService` | ✅ | 会话基础 |
| `setSessionActivity` | ✅ | 游戏助手点卡片能拉起 App（漏了它就点不开，既有 KDoc 记录过） |
| 封面内嵌 JPEG | ✅ | 系统进程读不到私有 cache 的 file://，内嵌字节是唯一能到达的形式 |
| title / artist / albumTitle | ✅ | |
| 前台服务 + 媒体通知 | ✅ | |
| 队列下发给服务端播放器 | ✅ | 游戏助手能读到完整队列 |
| 音频焦点策略 | ✅ | `handleAudioFocus = true`（游戏抢焦点时暂停、结束后恢复） |

---

## 四、验证

```
:app:assembleDebug --rerun-tasks  → BUILD SUCCESSFUL
```

APK 内 manifest 逐项核对（`aapt2 dump xmltree`）：
```
android.intent.category.APP_MUSIC              ✅
android.intent.action.MEDIA_BUTTON             ✅
androidx.media3.session.MediaButtonReceiver    ✅
androidx.media3.session.MediaSessionService    ✅
```

---

## 五、必须真机验证的项（本轮手机未连接）

1. **游戏助手音乐卡片是否出现**——这依赖 ColorOS 游戏助手自己的识别策略。
   `CATEGORY_APP_MUSIC` 是必要条件，但**不保证充分**（厂商可能还有自己的白名单/竞品判断）。
   验证方式：玩游戏时呼出游戏助手，看音乐卡片是否出现本应用。
2. **卡片元数据是否完整**（封面/歌名/歌手/时长/上一首下一首）。
3. **蓝牙耳机媒体键**在游戏中是否响应。
4. **点卡片能否拉起 App**。

若 ① 仍不出现，说明 ColorOS 走的是别的识别路径（如仅认自家音乐 App 或已安装包白名单），
那属于**厂商侧限制，无 App 侧解法**——这一点我如实标注，不承诺能绕开。

---

## 六、关于其他四个选项的说明（用户未选，但记录清楚）

- **B 音频焦点 duck**：现在游戏抢焦点是「暂停」，改成「压低音量」需要改
  `handleAudioFocus` 手写焦点处理。用户未选。
- **C 歌词悬浮窗游戏内不被遮挡**：`TYPE_APPLICATION_OVERLAY` 已经能置顶，
  但部分 ROM 在全屏游戏中会强制隐藏第三方悬浮窗，属系统行为。用户未选。
- **D 游戏保活**：可复用 `AutoStartGuide` 的多品牌候选 Intent 思路。用户未选。
- **E 游戏场景自动切换播放模式**：需要前台应用检测（`UsageStatsManager`，需用户授权）。
  用户未选。
