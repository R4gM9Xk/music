package com.local.music.widget

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.local.music.player.MusicPlaybackService
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * 小部件的控制动作转发通道。
 *
 * ============================================================================
 * 一、为什么是 BroadcastReceiver（以及为什么不是另外几条路）
 * ============================================================================
 * 小部件的点击最终要落到 `MusicPlaybackService` 里的 ExoPlayer 上，而我们能拿到的
 * 唯一正规通道是 `MediaController`。问题在于**生命周期完全不匹配**：
 * 桌面小部件常驻在 AppWidgetHost（桌面进程）里，可以几个月不刷新；而
 * MediaController 是一个持有 binder 连接、注册了 Player.Listener 的对象。
 *
 * 三个候选方案与取舍：
 *
 * **A. 在小部件里长期持有一个 MediaController（否决）**
 *    最直觉，但错得最厉害：
 *    - MediaController 必须在**有 Looper 的线程**上创建并长期存活，而小部件没有
 *      属于自己的"活着"的进程上下文（进程随时被回收）；
 *    - 它内部注册的 listener 会持续收到播放事件 → 每次切歌都唤醒我们的进程做一次
 *      无人使用的刷新，纯耗电；
 *    - 系统对同时存活的 controller 数量有限制（分给每个 session 的 controller 槽位），
 *      长持一个会让 App 自己的 controller（GlassApp 里那个）以及车机/手表的连接更容易被挤掉。
 *
 * **B. 发 KeyEvent.ACTION_MEDIA_BUTTON 给当前会话（否决）**
 *    看似最省事（`AudioManager.dispatchMediaKeyEvent`），但：
 *    - 它走的是「媒体按钮」全局路由，会在**所有**媒体 App 之间竞争
 *      （AudioManager 会把事件发给最近活跃的 MEDIA_BUTTON receiver），
 *      用户刚用别的 App 听了播客，我们小部件的「下一首」可能就把播客切了 —— 这是完完全全
 *      的用户可见 bug；
 *    - `KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE` 无法表达「上一首」的边界语义，
 *      也不走 session 的 command 权限校验，媒体3 侧还会打 deprecation 警告。
 *
 * **C. PendingIntent → 短命 MediaController（本实现，采用）**
 *    每次点击：建 controller → 下发 1 条命令 → 立刻 release。
 *    代价是每次点击多一次约 10-50ms 的 binder 建连（用户完全无感），
 *    换来的是：没有常驻对象、没有后台唤醒、不会与 App 自己的 controller 抢槽位、
 *    且**命令严格路由到我们自己的 MusicPlaybackService**（不像方案 B 会打错 App）。
 *
 * ============================================================================
 * 二、为什么用 BroadcastReceiver 而不是 Service 或 ActionCallback
 * ============================================================================
 * - **Service**：Android 8+ 后台启动服务受限（BackgroundServiceStartNotAllowedException），
 *   而小部件点击发生时 App 完全可能在后台 —— 用 startService 会被系统直接拒绝，
 *   从桌面点「播放」没有任何反应，且只在真机上复现、模拟器不报错。这是最坑的一条。
 *   （`startForegroundService` 也不行：我们不是要起前台服务，5 秒内必须 startForeground，
 *   否则 ANR 崩溃。）
 * - **Activity trampoline**（Glance 内部就是这么干的）：会闪一下屏幕，桌面场景观感很差。
 * - **BroadcastReceiver**：清单里静态注册（`exported="false"`）→ 系统拉起进程 →
 *   onReceive 里同步做完（约几十 ms）→ 返回。它不需要任何后台执行配额，
 *   也不受上面两条限制。**注意 onReceive 跑在主线程**，所以 [forward] 里所有
 *   连接等待都放在了一个独立线程上（见下）。
 *
 * ============================================================================
 * 三、为什么用 goAsync + 独立线程
 * ============================================================================
 * `MediaController.Builder(...).buildAsync()` 返回的 Future 在主线程上 get() 会阻塞
 * looper；而 onReceive 的 ANR 线是 10s，正常情况 2s 内会完成，看似安全 —— 但
 * **服务恰好正在启动**时（用户刚点开 App）可能拖到 3-5s，加上系统低端机的
 * binder 抖动，赌不起。所以：`goAsync()` 拿到 PendingResult，把整个连接过程丢到
 * 后台线程，做完 `pendingResult.finish()`。这样即便超时也不会 ANR。
 */
class WidgetActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        // 只处理我们自己的三个动作。清单里 exported=false 已经挡住了外部调用，
        // 这里再判一次是纵深防御（万一哪天有人把它改成 exported）。
        if (action != ACTION_PREV && action != ACTION_NEXT && action != ACTION_TOGGLE) return
        if (forwarding) {
            // 用户手快连点：上一条命令还在建连。直接丢弃这一次，
            // 而不是排队 —— 播放控制里「连点三下下一首」的语义本来就是切三首，
            // 但我们连一次要 10-50ms，排队反而会让最后一次点击延迟到几百 ms 后生效，
            // 体感是「点了没反应」。
            Log.d(TAG, "drop action=$action (previous still forwarding)")
            return
        }
        val pending = goAsync()
        forwarding = true
        Thread({
            try {
                forward(context.applicationContext, action)
            } catch (t: Throwable) {
                // 绝不能让它冒到系统：BroadcastReceiver 里抛未捕获异常 = 进程崩溃弹窗，
                // 而用户只是点了一下桌面小部件。
                Log.w(TAG, "forward failed action=$action", t)
            } finally {
                forwarding = false
                pending.finish()
            }
        }, "widget-action").start()
    }

    /**
     * 真正建立连接并下发命令。
     *
     * 全部命令走 `MediaController` 的**高层播放器接口**（play/pause/seekToNext…），
     * 不用 `sendCustomCommand` —— 后者需要在服务侧 `onConnect` 里显式声明
     * （见 MusicPlaybackService 的 CMD_SET_EQ 那套），为三个标准动作付出那个成本
     * 没有必要，而且标准接口天然受 SessionCommand/PlayerCommand 的权限校验保护。
     *
     * ## 为什么反馈刷新复用**本次连接**（本轮修复）
     *
     * 旧实现在 finally 里额外调一次 `MusicWidgetViews.refreshAll(context)`，那会
     * **再建一个 MediaController 并再阻塞最多 2s**。于是一次点击最多两次 2s 阻塞，
     * 与这个类自己写在头顶的「连一次 10-50ms、不要排队」的取舍直接矛盾。
     * 现在在 release 之前用**同一个 controller** 读一次状态再刷，
     * 整条路径的连接次数恒为 1。
     */
    private fun forward(context: Context, action: String) {
        val future: ListenableFuture<MediaController> = MediaController.Builder(
            context,
            SessionToken(context, ComponentName(context, MusicPlaybackService::class.java)),
        ).buildAsync()
        try {
            val controller = future.get(CONTROLLER_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            try {
                when (action) {
                    // **TOGGLE 的判据用 isPlaying 而不是 playbackState**：
                    // 暂停状态下 seekTo 到一半（STATE_BUFFERING）时 playbackState 不是
                    // STATE_READY，若按 state 判断会把「播放中」误判成「非播放」而重新
                    // play()，表现为「点暂停没反应」。isPlaying 直接反映用户语义。
                    ACTION_TOGGLE ->
                        if (controller.isPlaying) controller.pause() else controller.play()

                    // 队列边界：没有上一首/下一首时**不要**发命令。
                    // MediaController 的 seekToPreviousMediaItem 在无上一条时会静默无操作，
                    // 但某些 ROM 的实现会退回 seekTo(0)，表现为「点了上一首回到开头」。
                    // 明判一下，行为在所有 ROM 上一致：到底了就是不动。
                    ACTION_PREV ->
                        if (controller.hasPreviousMediaItem()) controller.seekToPreviousMediaItem()

                    ACTION_NEXT ->
                        if (controller.hasNextMediaItem()) controller.seekToNextMediaItem()
                }
                // 「点了有反馈」：复用**本次已有**的 controller 读一次状态就地刷新。
                // 命令是单向 binder，此刻播放器多半还没切完，所以这次刷新只是让按钮看起来
                // 被按下过；真正保证界面正确性的是服务侧 MusicWidgetRefresher 的事件驱动刷新
                // （onMediaItemTransition / onIsPlayingChanged）。这里不建第二个连接。
                runCatching {
                    MusicWidgetViews.refreshAll(context, preConnected = controller)
                }.onFailure { Log.w(TAG, "post-action refresh failed action=$action", it) }
            } finally {
                // releaseFuture 而不是 controller.release()：前者会取消尚未完成的连接，
                // 对两者都安全。**必须无条件释放**，否则每点一次泄漏一个 binder 连接。
                MediaController.releaseFuture(future)
            }
        } catch (e: TimeoutException) {
            MediaController.releaseFuture(future)
            Log.w(TAG, "controller timeout for action=$action")
        } catch (e: ExecutionException) {
            // 服务没启动/被disable：静默。用户点桌面按钮不该看到任何错误提示。
            // **但必须释放 future**（本轮修复）：这是最常见的失败分支，不释放等于
            // 每次点击泄漏一个 controller 槽位，连满后小部件永久失效。
            // 注意这行以前是漏的，而 MusicWidgetViews 的注释曾断言「这里每个 catch 都释放了」——
            // 那句断言是错的，同一轮修复只改了 MusicWidgetViews 那一处。
            MediaController.releaseFuture(future)
            Log.w(TAG, "controller build failed for action=$action", e)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            MediaController.releaseFuture(future)
        } catch (e: Exception) {
            // 兜底（本轮新增）：SecurityException / IllegalArgumentException 等运行时异常
            // 不该冒到 Thread 的 catch(Throwable) —— 那里虽然不会崩进程，但 future 同样会漏。
            MediaController.releaseFuture(future)
            Log.w(TAG, "controller build threw for action=$action", e)
        }
    }

    companion object {
        private const val TAG = "WidgetAction"

        /**
         * 三个动作的 action 名。
         *
         * **必须带应用包名前缀**：虽然这里是显式广播（不会被外部收到），但 action 名
         * 在 PendingIntent 的 filterEquals 里参与身份判定，带包名可避免将来若改成
         * 隐式广播时与其他应用撞名。
         */
        const val ACTION_PREV = "com.local.music.widget.action.PREV"
        const val ACTION_NEXT = "com.local.music.widget.action.NEXT"
        const val ACTION_TOGGLE = "com.local.music.widget.action.TOGGLE"

        /**
         * 建连超时。
         *
         * 2s：正常情况下 20-80ms 完成；服务正在冷启动时可能到 1s+。超过 2s 基本可以
         * 断定连不上（服务被禁用 / 系统限制），再等只会让用户觉得「点了卡住」。
         */
        private const val CONTROLLER_TIMEOUT_MS = 2_000L

        /**
         * 「有命令正在转发」标记。
         *
         * **为什么是 @Volatile 的静态字段而不是别的**：BroadcastReceiver 是每次
         * 点击**新建实例**的（系统实例化，不是单例），所以实例字段无法跨点击共享；
         * 而两次点击可能落在不同线程上（每次 goAsync 起一个 Thread），
         * 所以必须 volatile 保证可见性。
         *
         * 不追求强一致性：这里只是「丢掉多余的连点」的优化，
         * 最坏情况是两个线程同时建连（各下发一条命令），不会出错。
         */
        @Volatile
        private var forwarding = false
    }
}
