package com.local.music.feature.settings.screen

/** 应用内更新 UI 状态（纯 UI 层；网络/安装实现在 :app 的 UpdateManager） */
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
}
