package com.local.music.core.ui.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 应用内更新 UI 状态（**本轮从 `:feature:settings` 迁到 `:core:ui`**）。
 *
 * ## 为什么迁移：修正一个真实的依赖方向错误
 *
 * 迁移前它住在 `feature/settings/screen/UpdateState.kt`，但使用它的有**两方**：
 * - `:app` 的 `UpdateManager`（真正产生状态的一方：检查/下载/验签/安装）
 * - `:feature:settings` 的设置页（消费状态画 UI 的一方）
 *
 * 于是 `:app` 必须 `import com.local.music.feature.settings.screen.UpdateUiState`
 * ——**生产者反向依赖了消费者所在的 feature 模块**。这在架构上是错的：
 * update 的领域模型不属于「设置屏」，它属于领域层。
 *
 * 实际代价：`UpdateManager` 是纯逻辑类，却因为这一个 import 而与
 * 「设置页的包结构」绑死（改设置页包名会连带编译失败），且任何新模块想读更新状态
 * 都得依赖 `:feature:settings`。
 *
 * 迁移到 `:core:ui` 之后：`:app` 与 `:feature:settings` 都依赖 `:core:ui`（这本来就是
 * 它们的公共上游），依赖方向恢复正常。
 *
 * ## 为什么这个 holder 只有状态、没有「动作」
 *
 * 与 [AudioFxHolder] / [LyricsHolder] 不同，更新域的**动作**（检查、下载、验签、拉起安装器）
 * 全部依赖 Android framework（OkHttp 式网络、PackageManager、FileProvider、SAF），
 * 它们必须留在 `:app` 的 `UpdateManager` 里。
 *
 * 所以这里拆出的是「状态容器 + 状态迁移的合法性规则」，而不是整块逻辑：
 * `:core:ui` 不能依赖 Android 的安装/网络能力，硬搬会让 core 模块被 framework 污染。
 *
 * 换句话说：**更新域的 StateHolder 就是这个 data class 本身**——它是纯数据，
 * 天然可单测、可跨模块共享。这一点与音效/歌词域不同，也正是当初评估「更新域无可拆」
 * 的原因；本轮把它**归位**（迁到正确的模块），而不是再造一个空壳持有者。
 */
data class UpdateUiState(
    val status: Status = Status.Idle,
    val versionName: String? = null,
    val changelog: String? = null,
    val progress: Float = 0f,
    val message: String? = null,
    /** 失败阶段（P2-25）：Failed 态下区分失败环节，设置页按阶段给准确文案（而非一律「下载失败」） */
    val failStage: FailStage = FailStage.None,
    /** 下载不定长（P3-50）：经代理转发丢 Content-Length 时算不出百分比，UI 应显示转圈而非恒 0% */
    val indeterminate: Boolean = false,
) {
    enum class Status {
        Idle,            // 未检查（显示当前版本，点击开始检查）
        Checking,        // 检查中
        UpToDate,        // 已是最新
        Available,       // 发现新版本（点击打开更新面板）
        Downloading,     // 下载中（progress 0..1；indeterminate=true 时总长未知）
        ReadyToInstall,  // 已下载（点击拉起安装器；安装被取消后也停在此态）
        Failed,          // 失败（message 原因 + failStage 阶段，重试行为按阶段定）
    }

    /**
     * 失败阶段（P2-25）：过去所有 Failed 都被文案说成「下载失败」，检查失败也不例外；
     * 且验签失败若照常给「重新下载」重试，会拿同一份坏字节必然再失败（死循环）。
     * None = 未细分（检查失败等——检查失败时 versionName == null，旧文案分流仍然成立）。
     */
    enum class FailStage {
        None,      // 未细分（检查失败等：沿用 versionName == null → 检查失败 的旧分流）
        Check,     // 清单检查失败（无法连接更新源）
        Download,  // APK 下载失败（网络异常或下载中断）
        Verify,    // 签名校验失败（已删包并清 pendingInfo；**不给**「重新下载」重试入口）
        Install,   // 安装包已丢失（需要重新下载，pendingInfo 仍在可直接重下）
    }

    /**
     * 更新面板是否应保持打开（状态语义组）。
     *
     * 为什么要有个属性而不是让设置页自己枚举三个状态：这个「哪些状态算"正在进行中"」
     * 的知识属于领域（它由下载链路的阶段划分决定），UI 只需要问一句
     * 「还要不要显示面板」。原来它散在 `SettingsScreen` 顶部的局部 val 里，
     * 与 Status 的定义分居两个模块——加一个中间态时极易只改一处。
     */
    val keepsSheetOpen: Boolean
        get() = status == Status.Available ||
            status == Status.Downloading ||
            status == Status.ReadyToInstall

    /**
     * 失败后是否应给「重试」入口。
     *
     * 验签失败**不给**：同一份坏字节再下一次必然再失败（死循环），
     * 正确出路是去发布页手动下载（文案已按 `failStage` 分流）。
     */
    val offersRetry: Boolean
        get() = status == Status.Failed && failStage != FailStage.Verify
}

/**
 * 更新域持有者：把「一个可变状态 + 若干状态迁移」收在一处。
 *
 * 真正的网络/安装动作在 `:app` 的 `UpdateManager`（它持有一个本类实例并向 [state] 写）。
 * 本类只保证两件事：
 * 1. 读方拿到的是 Compose 可观察的 State（`by mutableStateOf`）；
 * 2. 状态的**写入**只有一个口（[commit]），便于将来加校验/日志而不用改调用点。
 *
 * 为什么不把检查/下载做成方法：那需要网络与 PackageManager，`:core:ui` 不能依赖它们。
 * 这条边界与本文件顶部说明的「更新域 StateHolder 就是那个 data class」一致。
 */
class UpdateHolder {

    var state by mutableStateOf(UpdateUiState())
        private set

    /** 唯一写入口（`UpdateManager` 的检查/下载/安装各阶段都经它提交） */
    fun commit(next: UpdateUiState) {
        state = next
    }
}
