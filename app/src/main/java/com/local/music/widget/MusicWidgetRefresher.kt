package com.local.music.widget

import android.content.Context
import android.util.Log

/**
 * 播放状态变化 → 小部件刷新 的**唯一入口**。
 *
 * ============================================================================
 * 为什么单独抽一个文件，而不是直接在 MusicPlaybackService 里调 MusicWidgetViews
 * ============================================================================
 * `MusicPlaybackService.kt` 由音频链路那条线独占（EQ / 响度归一 / BassBoost 那批
 * 改动都在里面）。小部件的刷新接进去只需要**一行**，但那条线不应该为了小部件
 * 去关心 RemoteViews / AppWidgetManager 的细节。
 *
 * 所以这里提供一个**幂等、永不抛异常、可被高频调用**的入口：
 * 调用方只要在播放状态变化的回调里调 [onPlaybackStateChanged] 就行，
 * 其余（重复抑制、异常兜底）在这一层处理，**线程切换与建连合并**在
 * [MusicWidgetViews.refreshAll] 里处理（它异步派发到专用后台线程，立即返回）。
 *
 * ```kotlin
 * // MusicPlaybackService 里（示意，实际由 Lead 决定接哪条回调）
 * player.addListener(object : Player.Listener {
 *     override fun onIsPlayingChanged(isPlaying: Boolean) {
 *         MusicWidgetRefresher.onPlaybackStateChanged(this@MusicPlaybackService)
 *     }
 *     override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
 *         MusicWidgetRefresher.onPlaybackStateChanged(this@MusicPlaybackService)
 *     }
 * })
 * ```
 *
 * ============================================================================
 * 为什么要有「重复抑制」
 * ============================================================================
 * `onIsPlayingChanged` 与 `onMediaItemTransition` 常常成对触发（切歌 → 新歌开始播
 * → 两个回调各来一次）。每次刷新都要建一次 MediaController（10-50ms + binder），
 * 连发两次纯属浪费，而且会让桌面小部件连着重绘两帧（能看出闪）。
 * 用「最小间隔」把同一批事件合并成一次刷新。
 *
 * 抑制窗口不能太长：用户在小部件上按暂停，如果因为抑制期没刷新，按钮图标会停在
 * 「暂停」而实际已经暂停 —— 看起来像卡住。[MIN_INTERVAL_MS] 取 400ms：
 * 足够合并掉同一批事件，又远短于用户对「点下去有反馈」的容忍上限。
 */
object MusicWidgetRefresher {

    private const val TAG = "WidgetRefresher"

    /** 两次刷新之间的最小间隔（见类注释的取舍） */
    private const val MIN_INTERVAL_MS = 400L

    /** 上次真正执行刷新的时间戳（uptimeMillis，不受系统时间调整影响） */
    private var lastRefreshAt = 0L

    /**
     * 播放状态变化时调用。任何线程都可以调，任何异常都不会抛出去。
     *
     * 之所以「不抛」是硬要求：调用点在 `Player.Listener` 回调里，而 media3 的
     * listener 回调抛异常会**直接杀掉播放服务进程**（表现为用户正在听歌时 App 闪退）。
     * 小部件刷新这种附属功能，绝不允许反过来搞崩主播放链路。
     */
    fun onPlaybackStateChanged(context: Context) {
        runCatching { refresh(context.applicationContext) }
            .onFailure { Log.w(TAG, "widget refresh swallowed", it) }
    }

    private fun refresh(app: Context) {
        val now = android.os.SystemClock.uptimeMillis()
        // 抑制窗口内的重复事件直接丢弃（见类注释）
        if (now - lastRefreshAt < MIN_INTERVAL_MS) return
        lastRefreshAt = now
        // 建连的互斥与合并在 refreshAll 内部（单线程执行器 + pending 标记）：
        // 这里再套一层 CAS 只会把「异步派发」误当成「同步执行」，反而丢掉合并语义。
        MusicWidgetViews.refreshAll(app)
    }
}
