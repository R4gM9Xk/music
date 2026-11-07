package com.local.music.lyrics

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager

/**
 * 悬浮歌词的拖动 touch 侦听：按住整行拖动，位置写回 [WindowManager.LayoutParams]
 * 并即时 [WindowManager.updateViewLayout]。
 *
 * 为什么不用 ViewDragHelper / 自定义手势：悬浮窗场景里 View 不在 Compose 树内，
 * layout 由 WindowManager 直管，`updateViewLayout` 是唯一合法的移动通道——
 * 30 行的手写实现比引入框架更直白，也更容易看清「每次移动一次系统调用」的成本。
 *
 * **clickable 冲突说明**：歌词行本身不需要点击行为（点击展开回 App 是第二版的事），
 * 所以这里只消费拖动——否则用户每次想挪位置都会误触发跳转。
 *
 * 位置持久化经 [onParamsChanged] 回调交回调用方（控制器把它接到 [DesktopLyricsPrefs]）：
 * 侦听器自己不持有 Context/磁盘职责，职责单一。
 */
internal class DragTouchListener(
    private val wm: WindowManager,
    private val onParamsChanged: (WindowManager.LayoutParams) -> Unit,
) : View.OnTouchListener {

    private var downRawX = 0f
    private var downRawY = 0f
    private var startX = 0
    private var startY = 0

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouch(v: View, event: MotionEvent): Boolean {
        try {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    val lp = v.layoutParams as? WindowManager.LayoutParams ?: return false
                    startX = lp.x
                    startY = lp.y
                }
                MotionEvent.ACTION_MOVE -> {
                    val lp = v.layoutParams as? WindowManager.LayoutParams ?: return false
                    val dx = (event.rawX - downRawX).toInt()
                    val dy = (event.rawY - downRawY).toInt()
                    // 8px 阈值：区分「手抖」与「真想拖」，避免轻触就漂移
                    if (dx * dx + dy * dy >= TOUCH_SLOP_PX * TOUCH_SLOP_PX) {
                        lp.x = startX + dx
                        lp.y = startY + dy
                        runCatching { wm.updateViewLayout(v, lp) }
                            .onFailure { Log.w(TAG, "updateViewLayout failed", it) }
                        onParamsChanged(lp)
                    }
                }
            }
        } catch (t: Throwable) {
            // 触摸回调跑在 system_server 派发链上，抛异常会连带桌面一起炸——全部兜住
            Log.w(TAG, "touch handling failed", t)
        }
        return true // 全程消费：悬浮窗不响应其他手势，拖柄语义完整
    }

    private companion object {
        const val TAG = "DesktopLyric"
        const val TOUCH_SLOP_PX = 8
    }
}

/**
 * 拖动位置的持久化（应用私有目录的一个小 JSON）。
 *
 * 为什么不用 SharedPreferences / SettingsStore 快照：SettingsStore 是「整个设置状态」
 * 的载体（v1→v10 迁移链已被单测钉死、文件封板），塞一个 UI 细节进去要升版+三路迁移；
 * SharedPreferences 则要为两个 int 建一套键值管理。独立小文件坏了就回默认位置，
 * 不影响任何其他设置。
 */
internal object DesktopLyricsPrefs {

    private const val TAG = "DesktopLyric"

    fun loadPosition(context: Context): Pair<Int, Int>? = runCatching {
        val file = java.io.File(context.filesDir, "desktop_lyrics_pos.json")
        if (!file.isFile) return null
        val obj = org.json.JSONObject(file.readText())
        obj.getInt("x") to obj.getInt("y")
    }.getOrNull()

    fun savePosition(context: Context, x: Int, y: Int) {
        runCatching {
            val file = java.io.File(context.filesDir, "desktop_lyrics_pos.json")
            file.parentFile?.mkdirs()
            file.writeText(org.json.JSONObject().put("x", x).put("y", y).toString())
        }.onFailure { Log.w(TAG, "save lyrics position failed", it) }
    }
}
