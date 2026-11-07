package com.local.music.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log

/**
 * 桌面小部件的系统入口（`AppWidgetProvider`）。
 *
 * ============================================================================
 * 为什么有两个 Provider 子类（见 MusicWidgetWideProvider / MusicWidgetSquareProvider）
 * ============================================================================
 * 4x1 与 4x2 是两个**独立的小部件**（两个 appwidget-provider 声明、两个 receiver），
 * 这样用户在系统小部件选择器里能分别看到「播放条」和「封面版」两张卡片，
 * 各自带自己的预览图与默认尺寸 —— 这符合用户预期（而不是「一个卡片拖成两种样子」）。
 * 两者共享全部渲染逻辑（[MusicWidgetViews]），Provider 子类只负责「我是谁」。
 *
 * ============================================================================
 * 刷新时机
 * ============================================================================
 * 1. [onUpdate]：小部件被放置 / 系统周期性发 APPWIDGET_UPDATE（provider XML 里的
 *    updatePeriodMillis，本实现设为 0 = 不周期刷新，理由见两个 XML 的注释）。
 * 2. [onAppWidgetOptionsChanged]：用户拖动改变尺寸 → 需要重新选布局重绘。
 * 3. 播放状态变化时由服务侧调用 [refreshAll]（事件驱动，唯一的常规刷新来源）。
 *
 * **onUpdate 必须保证在系统限时内返回**：它跑在主线程，而 [MusicWidgetViews.refreshAll]
 * 内部最长阻塞 2s 等 controller。对单个 onUpdate 而言 2s 是安全的（系统给的是
 * 广播 10s 窗口），但**不能**再叠加（例如循环里对每个 id 各连一次）。
 * 所以 refreshAll 是「读一次状态，刷 N 个实例」，连接次数恒为 1。
 */
abstract class MusicWidgetProviderBase : AppWidgetProvider() {

    /** 本子类对应的 layout 选择策略由 MusicWidgetViews 统一处理，这里只标身份 */
    protected abstract val tag: String

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        // 直接全量刷新：refreshAll 内部自己会取全部 id 并按实例尺寸分别渲染，
        // 这里的 appWidgetIds 只是「这个 receiver 名下的实例」，两者等价。
        // 不复用入参是为了让 refreshAll 同时能给**另一个尺寸**的实例刷新（例如用户在
        // 同一个桌面放了 4x1 和 4x2 各一个，播放状态变化时两边都要更新）。
        runCatching { MusicWidgetViews.refreshAll(context) }
            .onFailure { Log.w(tag, "onUpdate failed", it) }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle,
    ) {
        // 尺寸变了 → 布局可能要换（横条 ↔ 竖版），必须重绘。
        // 走 refreshAll 而不是只刷这一个：实现简单，且实例数量本来就是个位数。
        runCatching { MusicWidgetViews.refreshAll(context) }
            .onFailure { Log.w(tag, "optionsChanged failed", it) }
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        // 不需要清理任何持久状态（小部件无状态，一切都是现读现渲染）。
        // 显式写这个空实现 + 注释，是为了让下一轮审计知道「不是漏了」。
    }

    override fun onReceive(context: Context, intent: Intent) {
        // **必须 try/catch**：AppWidgetProvider.onReceive 是系统广播入口，
        // 这里抛异常 = 进程崩溃。小部件后台被拉起时最容易撞上各种竞态
        // （系统正在删实例、播放服务正在销毁），一律吞掉并降级为「这次没刷新」。
        try {
            super.onReceive(context, intent)
        } catch (t: Throwable) {
            Log.w(tag, "onReceive swallowed", t)
        }
    }
}

/** 4x1 播放条的小部件入口 */
class MusicWidgetWideProvider : MusicWidgetProviderBase() {
    override val tag = "MusicWidget4x1"
}

/** 4x2 封面版的小部件入口 */
class MusicWidgetSquareProvider : MusicWidgetProviderBase() {
    override val tag = "MusicWidget4x2"
}
