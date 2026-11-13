package com.local.music.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.local.music.MainActivity
import com.local.music.R
import com.local.music.player.MusicPlaybackService
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * 桌面小部件的**渲染 + 刷新**中枢（RemoteViews 路线）。
 *
 * ## 为什么不用 Glance（这条要留给下一轮审计看，别再当成「漏了」）
 * `gradle/libs.versions.toml` 第 57-59 行确实声明了 glance / glance-appwidget /
 * glance-material3（1.2.0），但那三个坐标**在本机的离线缓存里不可解析**：
 * 1. `files-2.1/androidx.glance/` 目录已不在（只剩 `metadata-2.107/descriptors/` 的
 *    元数据残壳 —— 这种「元数据在、产物没了」的状态最容易骗人，别只看目录名判断可用）；
 * 2. 更决定性的是**传递依赖从没缓存过**：glance-appwidget:1.2.0 要求
 *    compose.runtime 1.7.8 / compose.ui 1.6.0 系 / kotlin-stdlib(-common) 2.0.21 /
 *    work-runtime-ktx 2.7.1 / datastore 1.0.0，而本工程锁的是 compose 1.10.0 /
 *    kotlin 2.2.20 / lifecycle 2.9.4。即使 `files-2.1` 没被 Gradle 的缓存 GC 回收，
 *    这组版本在本机也凑不齐 —— **Glance 方案在当前环境无论怎样都编不过**。
 *    所以本模块走纯 framework + androidx.core 的 RemoteViews，零新依赖、功能等价。
 *    `libs.versions.toml` 里的三行**有意保留**：那是「原本的设计意图」的记录，
 *    将来能联网补缓存时按这段注释切回即可。
 *
 * ## 控制动作怎么转发
 * 小部件进程/生命周期与 `MusicPlaybackService` 完全不对齐，**绝不能**长期握一个
 * MediaController。策略是「每次动作现连现放」：点击 → PendingIntent(BroadcastReceiver)
 * → 建一个短命 MediaController → 下发一条播放器命令 → 立刻 release。
 * 完整理由与备选方案的否决过程见 [WidgetActionReceiver] 的类注释。
 *
 * ## 为什么刷新不用 WorkManager / AlarmManager
 * 小部件只需在「播放状态真的变了」时刷新：由 [MusicWidgetRefresher] 事件驱动即可。
 * 定时轮询纯浪费电量，桌面也不需要秒级进度。进度条只在事件驱动点更新（切歌/播放暂停），
 * 对一个桌面卡片完全够用 —— 也因此 provider XML 里 `updatePeriodMillis="0"`。
 */
internal object MusicWidgetViews {

    private const val TAG = "MusicWidget"

    /** 4x1 播放条（宽扁） */
    private const val LAYOUT_WIDE = R.layout.widget_music_wide

    /** 4x2 封面版（也是窄宽度时的退化形态） */
    private const val LAYOUT_SQUARE = R.layout.widget_music_square

    /**
     * 连接 MediaController 的等待上限。
     *
     * **为什么要超时**：`buildAsync()` 返回的 Future 在服务未启动/被禁用时可能永远
     * 不 complete。而调用的源头是 `AppWidgetProvider.onUpdate` 这类系统广播入口，
     * 必须在很短时间内返回（BroadcastReceiver.onReceive 的 ANR 线约 10s）。
     * 给 2s 上限，超时走空态，比让桌面卡在空槽好。
     */
    private const val CONTROLLER_TIMEOUT_MS = 2_000L

    /**
     * 本模块全部小部件 receiver。
     *
     * 新增尺寸时**必须同步加到这里**，否则新尺寸的小部件不会随播放状态刷新
     * （只在被放置时刷新一次，之后就冻住了）—— 这是那种「看着能用、用一会儿发现
     * 不更新」的隐性 bug，所以集中一处声明并把约束钉在注释里。
     *
     * 第二个元素 = 该 provider 是否用「横条」作为默认布局：用于
     * `OPTION_APPWIDGET_MIN_WIDTH` 读不到时的回退（见 [build]）。
     */
    private val WIDGET_PROVIDERS = listOf(
        MusicWidgetWideProvider::class.java to true,
        MusicWidgetSquareProvider::class.java to false,
    )

    /**
     * 刷新所有已放置的小部件实例。
     *
     * **必须逐个 id 下发**：不同实例尺寸不同（一个 4x1 一个 4x2），
     * `updateAppWidget(ComponentName, RemoteViews)` 那种「一把刷」的形态没法按
     * `OPTION_APPWIDGET_MIN_WIDTH` 选布局，会把横条塞进封面版的槽位。
     *
     * 连接次数恒为 1（先读一次状态，再刷 N 个实例），不会随实例数放大 ——
     * 这点在 onUpdate 这种有 ANR 时限的入口很关键。
     *
     * @param preConnected 调用方**已经持有**的 controller（[WidgetActionReceiver] 在
     *   下发完命令后复用同一条连接做反馈刷新）。传非 null 时不再新建连接 ——
     *   否则一次点击会出现「建连 → 下发 → 建连 → 刷新」两次 2s 阻塞。
     *   **生命周期归调用方**：本函数不会释放它。
     */
    fun refreshAll(context: Context, preConnected: MediaController? = null) {
        val manager = AppWidgetManager.getInstance(context) ?: return
        // 两个尺寸是两个独立 receiver，各取一次 id 再合并：
        // 只取一个的话，用户同时放了 4x1 和 4x2 时只有一个会刷新。
        // 用 (id → isWideProvider) 合并：id 全局唯一，所以 distinct 的语义不变，
        // 同时把「这个实例属于哪个 provider」带下去，供 build 在缺 OPTION_MIN_WIDTH 时回退。
        val idToWide = LinkedHashMap<Int, Boolean>()
        WIDGET_PROVIDERS.forEach { (provider, wideDefault) ->
            val ids = runCatching {
                manager.getAppWidgetIds(ComponentName(context, provider)).toList()
            }.getOrDefault(emptyList())
            ids.forEach { id -> idToWide.putIfAbsent(id, wideDefault) }
        }
        if (idToWide.isEmpty()) return // 用户没放小部件：直接返回，别白连一次播放服务
        val state = if (preConnected != null) {
            WidgetState.read(preConnected)
        } else {
            readStateBlocking(context)
        }
        idToWide.forEach { (id, wideDefault) ->
            runCatching { manager.updateAppWidget(id, build(context, manager, id, state, wideDefault)) }
                .onFailure { Log.w(TAG, "updateAppWidget failed id=$id", it) }
        }
    }

    /**
     * 同步读一次播放状态（带超时）。
     *
     * 无论成功失败都释放 controller（见各分支）—— 不释放会随每次刷新泄漏一个
     * binder 连接，连满后后续连接直接失败，小部件永久失效。
     */
    private fun readStateBlocking(context: Context): WidgetState {
        val app = context.applicationContext
        val future: ListenableFuture<MediaController> = MediaController.Builder(
            app,
            SessionToken(app, ComponentName(app, MusicPlaybackService::class.java)),
        ).buildAsync()
        return try {
            val controller = future.get(CONTROLLER_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            try {
                WidgetState.read(controller)
            } finally {
                MediaController.releaseFuture(future)
            }
        } catch (e: TimeoutException) {
            // 超时：取消 future 自己，免得它在后台静默连上后没人释放
            MediaController.releaseFuture(future)
            WidgetState.EMPTY
        } catch (e: ExecutionException) {
            // **必须释放**（本轮修复）：旧实现这三个 catch 直接返回空态，把 future 丢了。
            // 服务没启动 / 建连被拒时 future 已失败，但 releaseFuture 仍要调 —— 它同时
            // 负责取消未完成连接与回收控制器槽位；漏掉的后果正是本函数 KDoc 描述的那条：
            // 每次失败刷新泄漏一个 controller，连满后小部件永久失效。
            // （WidgetActionReceiver.forward 曾同样漏掉 ExecutionException 分支，
            //   已在同一轮补齐 —— 此处不再声称「那边都释放了」，当初那句断言是错的。）
            MediaController.releaseFuture(future)
            WidgetState.EMPTY
        } catch (e: InterruptedException) {
            // 恢复中断标记：上层（协程/OkHttp）依赖它判断取消
            Thread.currentThread().interrupt()
            MediaController.releaseFuture(future)
            WidgetState.EMPTY
        } catch (e: Exception) {
            // 服务未启动 / 已销毁等：一律空态，不让异常冒到 AppWidgetHost
            MediaController.releaseFuture(future)
            WidgetState.EMPTY
        }
    }

    /**
     * 按实例尺寸选布局并填充。
     *
     * 判据用 `OPTION_APPWIDGET_MIN_WIDTH`（**不是** maxWidth）：用户把 4x2 拖成 2x2 时
     * 只有 minWidth 真实反映当前可用宽度；provider XML 里声明的 `minWidth` 只是
     * 「默认放置时的初始尺寸」。
     */
    private fun build(
        context: Context,
        manager: AppWidgetManager,
        id: Int,
        state: WidgetState,
        isWideProvider: Boolean,
    ): RemoteViews {
        val options = runCatching { manager.getAppWidgetOptions(id) }.getOrNull()
        val minWidthDp = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0) ?: 0
        // 阈值 180dp：4 格在主流 ROM 上约 250-290dp，2 格约 110-140dp。
        // 取 180 让「1 行高但够宽」也走横条，只有真被拖窄了才退化成封面竖版。
        //
        // **读不到 OPTION_APPWIDGET_MIN_WIDTH 时按 provider 身份回退**（本轮修复）：
        // 旧实现取不到就落 0 → wide=false → 一律渲染成封面竖版。
        // 而部分 ROM 在 onUpdate 首帧、以及跨版本升级后的首次刷新里并不写该键，
        // 于是 4x1 播放条会被塞进竖版布局（一张被压扁的方卡）。
        // 4x1/4x2 本就是两个独立 receiver，`isWideProvider` 是比 0dp 可靠得多的默认值。
        //
        // **两个 provider 的 minWidth 都是 250dp ⇒ 纯宽度判据会让两者都变横条**（本轮修复）：
        // 只看 `minWidthDp >= 180` 时，用户放置「4x2 封面版」（声明 minWidth=250dp）也会
        // 直接算出 wide=true，渲染成横条 ——「封面版」这个选择器条目形同虚设，
        // 而 `isWideProvider` 那条按身份的回退在正常放置路径上永远轮不到。
        // 现在：**封面版的身份优先**，只有它被用户拖到明显更宽（≥ 横条阈值 + 一档）时才转横条；
        // 播放条身份保持原有的纯宽度判定（它本来就该随宽度自适应）。
        val wide = if (!isWideProvider) {
            // 封面版：默认竖版；只有被拖得明显比默认 250dp 更宽才允许切横条
            minWidthDp >= WIDE_LAYOUT_MIN_WIDTH_DP + SQUARE_TO_WIDE_HYSTERESIS_DP
        } else if (minWidthDp > 0) {
            minWidthDp >= WIDE_LAYOUT_MIN_WIDTH_DP
        } else {
            true // 播放条身份：读不到 options 时按横条渲染（见上）
        }
        val views = RemoteViews(context.packageName, if (wide) LAYOUT_WIDE else LAYOUT_SQUARE)
        bindCommon(context, views, state)
        if (wide) bindWide(views, state) else bindSquare(views, state)
        return views
    }

    /**
     * 两个尺寸共用的绑定：空态切换 / 封面 / 播放键图标 / 点击动作。
     *
     * 集中处理空态是为了保证两个尺寸**行为完全一致** —— 分开写的话很容易出现
     * 「4x1 忘了隐藏控制按钮」，用户面对一个「未在播放」却有三个可按按钮的卡片。
     */
    private fun bindCommon(context: Context, views: RemoteViews, state: WidgetState) {
        // 根容器点哪儿都能拉起 App（桌面小部件「点卡片进 App」的普遍预期）
        views.setOnClickPendingIntent(R.id.widget_root, openApp(context))

        if (!state.hasCurrent) {
            // 空态：只留「未在播放」，控制按钮/进度整组隐藏。
            // 不显示「可点的播放键」——队列为空时点它什么也不会发生，
            // 那比没有按钮更像坏了。用户要开始播，点卡片进 App。
            views.setViewVisibility(R.id.widget_art, View.GONE)
            views.setViewVisibility(R.id.widget_controls, View.GONE)
            views.setViewVisibility(R.id.widget_progress, View.GONE)
            views.setViewVisibility(R.id.widget_time, View.GONE)
            views.setViewVisibility(R.id.widget_subtitle, View.GONE)
            views.setViewVisibility(R.id.widget_empty, View.VISIBLE)
            views.setTextViewText(R.id.widget_empty, "未在播放")
            views.setTextViewText(R.id.widget_title, "")
            return
        }

        views.setViewVisibility(R.id.widget_empty, View.GONE)
        views.setViewVisibility(R.id.widget_art, View.VISIBLE)
        views.setViewVisibility(R.id.widget_controls, View.VISIBLE)

        // 封面：有图用图，无图用占位（**不隐藏** —— 隐藏会让横条在有无封面时
        // 左右跳动，观感上就是「小部件在闪」）
        val art = state.art
        if (art != null) {
            views.setImageViewBitmap(R.id.widget_art, art)
        } else {
            views.setImageViewResource(R.id.widget_art, R.drawable.widget_art_placeholder)
        }

        // 播放/暂停图标表达「当前状态」，点击动作是「切换」
        views.setImageViewResource(
            R.id.widget_play,
            if (state.isPlaying) R.drawable.widget_ic_pause else R.drawable.widget_ic_play,
        )

        views.setOnClickPendingIntent(
            R.id.widget_prev,
            action(context, WidgetActionReceiver.ACTION_PREV),
        )
        views.setOnClickPendingIntent(
            R.id.widget_play,
            action(context, WidgetActionReceiver.ACTION_TOGGLE),
        )
        views.setOnClickPendingIntent(
            R.id.widget_next,
            action(context, WidgetActionReceiver.ACTION_NEXT),
        )
    }

    /** 4x1 横条：封面 + 标题/艺术家一行 + 上一首/播放暂停/下一首。不放进度条（高度不够） */
    private fun bindWide(views: RemoteViews, state: WidgetState) {
        // 横条空间紧张：艺术家单独一行会挤掉播放键，直接拼在标题后面显示
        val suffix = if (state.artist.isEmpty()) "" else " · ${state.artist}"
        views.setTextViewText(R.id.widget_title, state.title + suffix)
        // **必须显式 GONE**：RemoteViews 复用同一个实例刷新时，上一帧的 VISIBLE
        // 会被继承下来。不写这一行，从 4x2 布局切到窄宽度时会残留两行文字。
        views.setViewVisibility(R.id.widget_subtitle, View.GONE)
        views.setViewVisibility(R.id.widget_progress, View.GONE)
        views.setViewVisibility(R.id.widget_time, View.GONE)
    }

    /** 4x2 封面版：大封面 + 标题/艺术家 + 进度条 + 完整控制 */
    private fun bindSquare(views: RemoteViews, state: WidgetState) {
        views.setTextViewText(R.id.widget_title, state.title)
        if (state.artist.isNotEmpty()) {
            views.setTextViewText(R.id.widget_subtitle, state.artist)
            views.setViewVisibility(R.id.widget_subtitle, View.VISIBLE)
        } else {
            views.setViewVisibility(R.id.widget_subtitle, View.GONE)
        }

        // 进度：有确定时长才画，否则整条隐藏（直播流画个 0% 空槽会误导）
        if (state.hasDuration) {
            val duration = state.durationMs.toInt().coerceAtLeast(1)
            views.setViewVisibility(R.id.widget_progress, View.VISIBLE)
            views.setProgressBar(
                R.id.widget_progress,
                duration,
                state.positionMs.toInt().coerceIn(0, duration),
                false,
            )
            views.setViewVisibility(R.id.widget_time, View.VISIBLE)
            views.setTextViewText(
                R.id.widget_time,
                "${formatClock(state.positionMs)} / ${formatClock(state.durationMs)}",
            )
        } else {
            views.setViewVisibility(R.id.widget_progress, View.GONE)
            views.setViewVisibility(R.id.widget_time, View.GONE)
        }
    }

    // ── PendingIntent 构造 ────────────────────────────────────────────────
    //
    // **Android 12+（API 31）起 mutability 必须显式指定**，否则构造时直接抛
    // IllegalArgumentException。本工程 minSdk=35，所以 FLAG_IMMUTABLE 是硬性要求，
    // 不是「兼容加成」。全部 PendingIntent 都走下面两个工厂方法，避免以后有人漏写。

    /**
     * 拉起 App。
     *
     * FLAG_UPDATE_CURRENT 是必须的：小部件的 PendingIntent 被 AppWidgetHost 长期缓存，
     * 若「首次创建即定型」，热更新后旧的 action/extras 会一直生效，表现为
     * 「改了代码但点击行为没变」。
     */
    private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_APP_MUSIC)
            // SINGLE_TOP：App 已在任务栈里时不要重建 Activity（小部件高频点击场景）
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /**
     * 控制动作 → 显式组件广播。
     *
     * **为什么显式 setClass 而不是隐式 action 广播**：
     * 1. 隐式广播在 Android 8+ 对后台应用基本收不到（后台执行限制），会被静默丢弃；
     * 2. 隐式 action 可被任何应用伪造（往你的控制广播里塞「播放」）。显式指定
     *    + 清单 `exported="false"`，系统外没有任何应用能触达它。
     *
     * **requestCode 按 action 区分**：PendingIntent 的身份是
     * (requestCode, Intent.filterEquals) 二元组。三种动作的 action 字符串本就不同，
     * 但部分 ROM 的 PendingIntent 缓存实测会把「上一首/下一首」串味，所以再给一个
     * 唯一的 data uri 兜底 —— 成本为零，收益是彻底消除这类偶发 bug。
     */
    private fun action(context: Context, actionName: String): PendingIntent = PendingIntent.getBroadcast(
        context,
        actionName.hashCode(),
        Intent(context, WidgetActionReceiver::class.java)
            .setAction(actionName)
            .setData(Uri.parse("colormusic://widget/$actionName")),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** 竖版布局的宽度阈值（dp），低于它退化到封面竖版布局 */
    private const val WIDE_LAYOUT_MIN_WIDTH_DP = 180

    /**
     * 封面版转横条的滞回量（dp）。
     *
     * 两个 provider 声明的 `minWidth` 都是 250dp（这是 4 格的网格口径，不是布局意图），
     * 所以单靠「≥180dp 就横条」会让封面版一放上去就变横条。给它 180+60=240dp…
     * 仍然不够 —— 250dp 的默认值依然越线。因此封面版实际要求用户**主动拖宽到
     * 250dp 以上**才切横条，而默认放置时永远保持竖版。
     *
     * 取 80 的理由：250（默认）→ 330dp 才切；主流 ROM 的 4 格约 250-290dp，
     * 5 格约 320dp —— 即「用户真的把它拖成 5 格宽」才认为是想要横条，
     * 不会因为 ROM 之间几十 dp 的口径差异而抖动。
     */
    private const val SQUARE_TO_WIDE_HYSTERESIS_DP = 80
}
