package com.local.music

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import com.local.music.crash.CrashHandler
import com.local.music.ui.GlassApp

/**
 * 唯一 Activity：液态玻璃 UI 宿主。
 *
 * ## A-4（本轮）：从媒体通知/锁屏/流体云点回来时直接进播放页
 *
 * 用户从通知栏点媒体卡片，意图很明确——「我要看当前这首」。而改动前的行为是恢复
 * 上次离开时的页面：如果他退出前停在歌单详情或设置，点回来会落到那里，还得再点
 * 迷你条才到播放页。主流音乐 App（QQ/网易/Spotify）在这条路径上都直接进播放页。
 *
 * ## 为什么用 `MutableState` 中转，而不是在 onCreate/onNewIntent 里直接改 AppState
 *
 * `AppState` 由 `GlassApp()` 内部的 `remember` 持有，Activity 的回调跑在它之外：
 * - `onCreate` 里 `setContent` 之后立刻拿不到 AppState（组合还没执行）；
 * - `onNewIntent` 更晚，但此时 Activity 已重建/复用，直接抓状态会撞上「组合正在
 *   重组的中间态」，写入可能被随后的一次 restore 覆盖。
 *
 * 所以这里只**记录来意**（一个布尔事件），由 GlassApp 在准备就绪后消费——与
 * `ioMessage`、`scanResultToast` 的「事件由 app 层产生、组合层消费」模式一致。
 */
class MainActivity : ComponentActivity() {

    /**
     * 「本次回到前台是用户点了媒体通知」的一次性事件。
     *
     * 刻意不做成 `rememberSaveable`：旋转屏幕/配置变更后不该重新触发一次跳转
     * （用户已经看到播放页了，再跳一次等于打断他当前的浏览）。
     */
    private val openPlayerFromNotification = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        // 崩溃采集必须在任何 UI 之前装好：越早装，能捕获的启动期崩溃越多
        CrashHandler.install(this)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge() // 沉浸式，极光铺满全屏
        // 冷启动路径：进程被杀后从通知栏点回来，Intent 在这里到达。
        // savedInstanceState != null 时不消费——那是配置变更/系统回收重建，
        // 用户的页面上下文本来就该保留，不该被跳转打断。
        if (savedInstanceState == null) {
            openPlayerFromNotification.value = isFromMediaNotification(intent)
        }
        setContent { GlassApp(openPlayerFromNotification = openPlayerFromNotification) }
    }

    /**
     * 热启动路径：Activity 已在栈中，点通知走这里（launchMode 为默认 standard，
     * 但 PendingIntent 带了 SINGLE_TOP，所以是复用而非新建）。
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // 必须 setIntent：否则后续 getIntent() 读到的仍是旧 Intent
        setIntent(intent)
        if (isFromMediaNotification(intent)) openPlayerFromNotification.value = true
    }

    /**
     * 判据：Intent 是否由本应用的**媒体会话**拉起。
     *
     * `MusicPlaybackService` 的 `setSessionActivity` 传入的就是
     * `ACTION_MAIN + CATEGORY_APP_MUSIC`（见该处 KDoc），所以这个组合既能区分
     * 「从桌面图标启动」（只有 LAUNCHER，无 APP_MUSIC）也能区分「被其他应用以
     * 音乐身份拉起」（*有* APP_MUSIC，但那种场景下用户同样是想听歌，跳播放页是对的）。
     *
     * 为什么不按 action 细分（例如自定义一个 `ACTION_OPEN_PLAYER`）：media3 的
     * `setSessionActivity` 接收任意 Intent，自定义 action 也能工作，但会让「系统识别
     * 音乐应用」那条 manifest intent-filter 与运行时 PendingIntent 的形态分叉
     * （manifest 必须是 MAIN/APP_MUSIC 才会被游戏助手/车机识别）。保持同构更安全。
     */
    private fun isFromMediaNotification(intent: Intent?): Boolean {
        if (intent == null) return false
        if (intent.action != Intent.ACTION_MAIN) return false
        return intent.categories?.contains(Intent.CATEGORY_APP_MUSIC) == true
    }
}
