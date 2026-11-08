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
                // **lastText 只在文字真的写进 View 之后才更新**（见下方 endAction）。
                //
                // 这是一个真实踩过的坑：最初把 `lastText = text` 放在动画启动**之前**，
                // 结果 fast-forward 一行接着一行（快歌 / 用户拖进度条）时——
                //   ① tick 启动淡出动画（alpha→0，90ms 后 endAction 里 setText）
                //   ② 90ms 未到，下一次 tick 又把新 text 判为「变了」，调用 animate().cancel()
                //   ③ cancel 会**丢弃尚未执行的 endAction** → setText 从未发生
                //   ④ 而 lastText 早已等于这一句 → 后续 tick 全部判定「无变化」而跳过
                // 最终表现：悬浮窗**永久停在上上句，而且 alpha 卡在 0（整窗看不见）**。
                // 只有重启 App 或重新 show 才能恢复——是最难排查的那类「偶发但不自愈」。
                //
                // 正确做法是让 lastText 成为「**已经渲染**的那句」而不是「正在尝试渲染的那句」：
                // 动画被打断时 lastText 仍是旧值，下一次 tick 自然重试，最坏情况只是
                // 少一次淡入过渡，绝不会丢内容或卡透明度。
                if (text != lastText) {
                    val sameView = { lyricView === view }
                    // ── 行切换过渡（视觉审计 F1 / 增-P3-4）──
                    // 改前 setText 硬切：桌面上逐行歌词「啪」地换字，与 App 内播放页
                    // 歌词的 500ms 柔和滚动完全两种语言。悬浮窗里做不到 Compose 的
                    // AnimatedContent，但「先淡出 → 换字 → 淡入」的成本几乎为零。
                    //
                    // 为什么不对称（90ms 出 / 120ms 入）：淡出要**快**，否则旧行还在屏幕上
                    // 赖着不走、与新行的语义冲突；淡入可以稍缓，用户刚看清新行时不会觉得跳。
                    //
                    // 为什么用 ViewPropertyAnimator 而不是 ValueAnimator：前者自带
                    // 硬件层优化与生命周期取消，且这里只要 alpha 一条曲线。
                    view.animate().cancel() // 不叠加两条动画：后者的 endAction 会与前者抢 setText
                    view.animate().alpha(0f).setDuration(90).withEndAction {
                        // 快速切歌/关窗时 lyricView 已被替换或置空：此时 setText 会打到
                        // 已移除的视图上（无害但无意义），且不该再更新 lastText——保持旧值
                        // 让下一次 tick 在新视图上重试
                        if (sameView()) {
                            view.text = text
                            lastText = text // ← 唯一更新点：文字真的上屏了才认账
                            view.animate().alpha(1f).setDuration(120).start()
                        }
                    }.start()
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
                // ── 视觉一致性（视觉审计 F1）──
                // 改前是裸 TextView：直角 + 0x66000000 纯黑底 + px 单位的 padding。
                // 它悬浮在**桌面**上，用户每看一眼都在把它和 App 内的玻璃做对照，
                // 直角黑块与「液态玻璃」的语言完全断裂。
                //
                // 现在：圆角胶囊（14dp，对齐 R.Card14 的视觉重量；悬浮小条不宜用 28dp 大圆角）
                // + 与 chromeScrim 同族的深底（#1A1A24，App 内迷你条/导航栏用的就是它）
                // + 细描边制造「玻璃边缘」的暗示（桌面壁纸不可控，必须给边缘定义，
                //   否则浅色壁纸下这个深块会变成一块说不清来路的墨迹）。
                //
                // 注意：悬浮窗**不能用 Compose**——它跑在服务进程里、没有 ComposeView 宿主，
                // 且每次显示都重建 View 树，用 ComposeView 需要额外搭 Recomposer 与生命周期，
                // 成本远超一个 GradientDrawable。这里是「用平台 API 模仿应用语言」，
                // 不追求 realtime blur（WindowManager 悬浮窗做背景模糊需要 RenderEffect +
                // 逐帧采样壁纸，权限与功耗都不可接受）。
                val density = appContext.resources.displayMetrics.density
                fun dp(v: Int) = (v * density).toInt()
                val bg = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                    cornerRadius = dp(14).toFloat()
                    setColor(0xE61A1A24.toInt()) // #1A1A24 @ 90%：比 App 内 chromeScrim 略实，桌面壁纸不可控
                    setStroke(dp(1), 0x33FFFFFF) // 白 20% 细描边：给深块一个「玻璃边缘」
                }
                background = bg
                setPadding(dp(18), dp(10), dp(18), dp(10))
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
