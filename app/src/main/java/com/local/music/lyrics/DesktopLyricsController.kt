package com.local.music.lyrics

import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import com.local.music.core.ui.components.LyricLine

/**
 * 桌面歌词悬浮窗（第一版）——审计 P0「桌面小部件 + 桌面歌词双双为零」的后一半。
 *
 * ## 实现路径：服务侧驱动的实例控制器
 *
 * 悬浮窗跟着「播放服务」走而不是跟着「Activity」走，理由：
 * - 桌面歌词的使用场景就是**离开 App 之后**（刷别的应用、锁屏前瞄一眼），
 *   Activity 一退场景就没了，Activity 驱动的悬浮窗等于没做；
 * - 服务进程存活时 [positionMsProvider]（读 `player.currentPosition`）始终可读，
 *   是唯一稳定的进度权威；
 * - 开关状态由设置页持久化并经 `CMD_SET_DESKTOP_LYRICS` 下发（服务重建后由
 *   GlassApp 重连时重放），控制器本身不持状态、只执行命令。
 *
 * ## 数据源（构造注入，见 MusicPlaybackService.onCreate）
 *
 * - [positionMsProvider]：播放进度（毫秒）。暂停时 currentPosition 保持值，恒可用。
 * - [currentTrackPathProvider]：当前曲目的**文件绝对路径**（mediaId = "f"+绝对路径，
 *   removePrefix 解出）。切歌时用它找同名 .lrc。演示曲目/非 f 前缀 id 返回 null → 暂无歌词。
 *
 * ## 关键工程约束（每一条都是真实事故形态，不是清单式防御）
 *
 * 1. **所有 WindowManager 操作 runCatching 兜底**：部分 ROM 会拒绝 addView /
 *    removeView（权限被回收的瞬间、多窗口模式），主播放服务绝不能因歌词挂掉。
 * 2. **[release] 必须移除视图**：服务被系统回收时若不移除，悬浮窗会变成「孤儿窗」
 *    ——进程死了但 Window 还在，桌面留一行删不掉的歌词。服务 onDestroy 首行调用。
 * 3. **TYPE_APPLICATION_OVERLAY + FLAG_NOT_FOCUSABLE 成对出现**：不设 NOT_FOCUSABLE
 *    悬浮窗抢焦点，用户在别的应用打字时输入法被顶掉——悬浮窗功能最经典的差评来源。
 * 4. **权限先行**：`Settings.canDrawOverlays()` 为 false 时 [show] 返回 false，
 *    由设置页负责引导授权；运行中权限被撤销 → 下一次操作抛异常 → runCatching
 *    兜住并自动摘窗降级。
 *
 * ## 刷新策略
 *
 * 250ms 轮询进度 → 找当前行 → 文本变了才 setText。不做逐字（QRC）渲染——
 * 第一版目标是「能用、稳」，逐字是锦上添花。
 *
 * ## 无歌词的策略
 *
 * 当前曲无可用歌词行 → 显示「♪ 暂无歌词」而不是隐藏窗口：隐藏会让用户分不清
 * 「开关坏了」还是「这歌没词」，显式占位是更诚实的表达。
 */
class DesktopLyricsController(private val appContext: Context) {

    /**
     * 播放进度（毫秒）。**构造后由服务端注入**（见 MusicPlaybackService.onCreate 的
     * `apply { positionMsProvider = { player.currentPosition } }`）。
     *
     * 为什么用可写属性而不是构造参数：服务端 `player` 在 onCreate 里创建，而控制器
     * 也要在 onCreate 里创建——两者互相引用时构造参数只能靠 lateinit 风格的二次赋值。
     * 用 `var` + 默认空实现（返回 0）比 lateinit 更安全：万一忘了注入，轮询拿到 0
     * 只会停在首行，不会抛 UninitializedPropertyAccessException 打断播放链路。
     */
    var positionMsProvider: () -> Long = { 0L }

    /**
     * 当前曲目的文件绝对路径（mediaId = "f"+绝对路径，removePrefix 解出）。
     * 同 [positionMsProvider]，由服务端注入；默认 null = 暂无歌词。
     */
    var currentTrackPathProvider: () -> String? = { null }

    companion object {
        private const val TAG = "DesktopLyric"
        private const val REFRESH_INTERVAL_MS = 250L
    }

    private var windowManager: WindowManager? = null
    private var lyricView: TextView? = null

    /** 当前歌词行集。切歌由 [onTrackChanged] 重读；显示期间由轮询使用。 */
    private var lines: List<LyricLine> = emptyList()

    /** 上一次渲染文本：相同则跳过 setText（省掉无谓的 relayout） */
    private var lastText: String? = null

    /** 歌词读的是哪条路径（轮询发现路径被外部改变时自动重读，如跨设备续播） */
    private var loadedPath: String? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    private val tick = object : Runnable {
        override fun run() {
            val view = lyricView
            if (view != null) {
                val posSec = positionMsProvider() / 1000f
                val text = currentLineText(posSec)
                if (text != lastText) {
                    lastText = text
                    view.text = text
                }
                // 路径被外部改变（引擎重建等）：重读一次，保证歌词跟得上曲目
                val path = currentTrackPathProvider()
                if (path != loadedPath) reloadLyrics(path)
            }
            mainHandler.postDelayed(this, REFRESH_INTERVAL_MS)
        }
    }

    /**
     * 显示悬浮窗。已在显示时幂等（只重读歌词）。无悬浮窗权限返回 false
     * （调用方据此引导授权），任何异常不外抛。
     */
    fun show(): Boolean {
        if (lyricView != null) {
            reloadLyrics(currentTrackPathProvider())
            return true
        }
        if (!Settings.canDrawOverlays(appContext)) return false
        return runCatching {
            val wm = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val view = TextView(appContext).apply {
                text = "♪ 暂无歌词"
                textSize = 16f
                setTextColor(0xFFFFFFFF.toInt())
                setBackgroundColor(0x66000000) // 半透明黑底：任意壁纸可读、可拖开
                setPadding(24, 12, 24, 12)
                // 拖动 + 位置持久化：松手/拖动中把 x/y 写回应用私有目录的小 JSON
                //（DesktopLyricsPrefs），下次 show 恢复到用户拖后的位置（见 restorePosition）
                setOnTouchListener(
                    DragTouchListener(wm) { lp ->
                        DesktopLyricsPrefs.savePosition(appContext, lp.x, lp.y)
                    },
                )
            }
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                // 悬浮窗唯一合法类型（API 26+）；配合 manifest 的 SYSTEM_ALERT_WINDOW
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                // NOT_FOCUSABLE 必须有：否则抢焦点顶掉输入法；NOT_TOUCH_MODAL 让窗外触摸穿透
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                // 默认悬在屏幕上部：显眼且不太挡内容，用户可拖到任意位置；
                // 用户拖过后 restorePosition 会覆盖为持久化值
                x = 60
                y = 240
            }
            DesktopLyricsPrefs.loadPosition(appContext)?.let { (x, y) ->
                params.x = x
                params.y = y
            }
            wm.addView(view, params)
            windowManager = wm
            lyricView = view
            reloadLyrics(currentTrackPathProvider())
            mainHandler.removeCallbacks(tick)
            mainHandler.postDelayed(tick, REFRESH_INTERVAL_MS)
            true
        }.getOrElse {
            Log.w(TAG, "addView failed（权限被拒 / ROM 限制）", it)
            lyricView = null // 复位半初始化状态：否则 hide() 误以为还在显示
            windowManager = null
            false
        }
    }

    /** 隐藏并清理。幂等。 */
    fun hide() {
        mainHandler.removeCallbacks(tick)
        runCatching {
            lyricView?.let { v -> windowManager?.removeView(v) }
        }.onFailure { Log.w(TAG, "removeView failed", it) }
        lyricView = null
        windowManager = null
        lastText = null
    }

    /**
     * 服务销毁：摘窗 + 停轮询 + 断数据源引用。与 [hide] 的区别是语义（销毁 vs 用户关闭），
     * 行为一致；分开命名是为了服务 onDestroy 处的调用点可读。
     */
    fun release() = hide()

    /** 切歌回调（服务端 onMediaItemTransition）。未在显示时只记路径，show 时会加载。 */
    fun onTrackChanged(dataPath: String?) {
        if (lyricView == null) {
            loadedPath = null // 未显示时不缓存旧歌词：下次 show 按最新曲目加载
            return
        }
        reloadLyrics(dataPath)
    }

    private fun reloadLyrics(dataPath: String?) {
        loadedPath = dataPath
        // DesktopLyricsLoader = MediaLibrary.readLrc（private、文件封板）的平移实现：
        // 同一套候选路径（sibling → files/lrc）+ 同一个 public 解析器 MediaLibrary.parseLrc，
        // 保证悬浮窗与播放页 LyricRail 的行切分完全一致。读盘+解析跑在本轮询线程（主线程）
        // 会有数十 ms 开销——仅当路径变化时发生，且单文件 ≤512KB（超限视同无歌词），
        // 不会构成持续负担；第一版不为它引入异步状态机。
        lines = if (dataPath.isNullOrBlank()) emptyList() else runCatching {
            DesktopLyricsLoader.load(appContext, dataPath)
        }.getOrNull() ?: emptyList()
        lastText = null // 强制下一帧重渲染
    }

    /** 二分找「时间 ≤ pos 的最后一行」；纯文本降级（无有效时间戳）恒显示首行 */
    private fun currentLineText(posSec: Float): String {
        if (lines.isEmpty()) return "♪ 暂无歌词"
        if (lines.first().timeSec <= 0f) return lines.first().text
        var lo = 0
        var hi = lines.size - 1
        var idx = 0
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            if (lines[mid].timeSec <= posSec) {
                idx = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return lines[idx].text
    }
}
