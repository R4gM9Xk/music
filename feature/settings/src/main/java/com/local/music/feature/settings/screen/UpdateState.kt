package com.local.music.feature.settings.screen

/** 应用内更新 UI 状态（纯 UI 层；网络/安装实现在 :app 的 UpdateManager） */
data class UpdateUiState(
    val status: Status = Status.Idle,
    val versionName: String? = null,
    val changelog: String? = null,
    val progress: Float = 0f,
    val message: String? = null,
) {
    enum class Status {
        Idle,            // 未检查（显示当前版本，点击开始检查）
        Checking,        // 检查中
        UpToDate,        // 已是最新
        Available,       // 发现新版本（点击打开更新面板）
        Downloading,     // 下载中（progress 0..1）
        ReadyToInstall,  // 已下载（点击拉起安装器；安装被取消后也停在此态）
        Failed,          // 失败（message 原因，点击重试）
    }
}
