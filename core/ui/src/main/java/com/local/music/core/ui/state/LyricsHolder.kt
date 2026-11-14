package com.local.music.core.ui.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.local.music.core.ui.components.LyricLine

/**
 * 歌词域状态持有者（本轮从 [AppState] 拆出）。
 *
 * ## 这个域里到底有什么「状态」
 *
 * 拆之前先看清边界——歌词域的真实构成只有三样：
 * 1. **[enabled]**：用户偏好（是否显示歌词），**唯一的可变状态**，进快照；
 * 2. **[indexOf]**：由「时间轴 + 播放进度」推导出的当前行，纯函数；
 * 3. **[visibleLyricsOf]**：由「队列是否为空 + 曲目是否真曲 + 有无 .lrc」推导，纯函数。
 *
 * 也就是说本类真正拥有的状态只有**一个布尔**，其余是两个**纯函数**。
 * 这看起来不值得单独成一个文件——但拆分它的价值不在「搬状态」，而在：
 *
 * - 把「当前行」的计算从 2900 行的 AppState 里拿出来，**变成可以直接单测的纯函数**
 *   （之前它藏在 getter 里，只能通过 AppState 的时序去间接验证）；
 * - 把「歌词该不该显示」的三条判据（空队列 / 占位曲 / 无词）集中到一处，
 *   避免播放页与将来的桌面歌词各写一遍（那正是本仓反复出现的「改一处漏一处」模式）；
 * - 与 [AudioFxHolder] 形成同类结构，让「域持有者」成为本仓可复制的模式。
 *
 * ## 边界约定（与 AudioFxHolder 一致）
 *
 * 本类**不持有** AppState 的任何东西：歌词文本从调用方传入，播放进度也从调用方传入。
 * 这样它没有任何对 AppState 的反向依赖，可独立测试。
 */
class LyricsHolder(
    /** 请求存盘（由 AppState 注入；内含节流与 hydrated 门控） */
    private val onChanged: () -> Unit = {},
) {

    /**
     * 是否启用歌词显示（快照 v1 起持久化）。
     *
     * 用户偏好：关掉后播放页常驻封面端，桌面歌词也不显示。
     * 默认 true——歌词是本地播放器的核心卖点之一，默认关闭会让新用户以为不支持。
     */
    var enabled by mutableStateOf(true)
        private set

    /** 切换歌词显示（UI 双通道：播放页手势 / 设置页开关，两者都走这里）。
     *
     *  命名用 `applyEnabled` 而不是 `setEnabled`：`enabled` 是 `var`，它的 JVM setter
     *  就叫 `setEnabled`，同名会撞（Platform declaration clash）。
     *  `apply*` 是本仓既有的命名惯例（见 `AppState.applyFollowCover`）。 */
    fun applyEnabled(value: Boolean) {
        if (value == enabled) return
        enabled = value
        onChanged()
    }

    /** 取反（供开关与手势调用） */
    fun toggle() = applyEnabled(!enabled)

    /** 恢复（[AppState.restore] 调用；不走 [onChanged]——恢复路径是纯回写） */
    fun restore(enabled: Boolean) {
        this.enabled = enabled
    }

    companion object {

        /**
         * 某一首歌该显示的歌词；不该显示时返回 null。
         *
         * 三条判据缺一不可，各自对应一种**用户能看到的**错误：
         * - `queueIsEmpty`：占位曲（没有队列）不该给词——否则空曲库时舞台会显示
         *   一首并不存在的歌的歌词；
         * - `hasMediaUri`：演示/占位曲目（mediaUri == null）没有真实音频，它的
         *   lyricLines 只会是残留数据；
         * - `lyrics` 本身为 null 才说明「这首歌确实没有词」→ 播放页显示「纯音乐，请欣赏」。
         *
         * 为什么把这三条收进 holder 而不是留在 AppState 的 getter 里：它们是**领域规则**
         * 而不是状态。将来桌面歌词、小部件、车机界要取「当前歌词」时，应该复用同一套判定，
         * 而不是各自再写一遍这三行 if（那种复制正是本仓历史缺陷的高发处）。
         */
        fun visibleLyricsOf(
            queueIsEmpty: Boolean,
            hasMediaUri: Boolean,
            lyrics: List<LyricLine>?,
        ): List<LyricLine>? = when {
            queueIsEmpty -> null
            !hasMediaUri -> null
            else -> lyrics
        }

        /**
         * 当前行下标 = 最后一条 `timeSec <= posSec` 的行；找不到时返回 0。
         *
         * ## 为什么用二分查找而不是 `indexOfLast`
         * 这个函数在播放中**每 100ms 被调用一次**（播放页的 `derivedStateOf` 由进度驱动）。
         * 线性扫描几十行是几微秒，单次可忽略；但它是「每次都得从头扫」的 O(n)，
         * 而 `timeSec` 天然**升序**（LRC 时间轴），二分把 n 次比较压到 log n。
         * 更重要的理由是**与桌面歌词控制器保持同一算法**：那里已经用二分
         * （`DesktopLyricsController.currentLineText`），两处算法一致才不会出现
         * 「App 内显示第 12 行、桌面歌词显示第 11 行」这类边界差一错误。
         *
         * ## 边界语义（必须与 indexOfLast 版逐条对齐）
         * - 空列表 → 0（调用方的 `coerceAtLeast(0)` 语义）；
         * - 进度在第一行之前 → 0（不是 -1：舞台永远高亮一行，不出现"无高亮"态）；
         * - 进度在末行之后 → 末行下标；
         * - 多行时间戳相同 → 取**最后一个**（与 `indexOfLast` 一致）。
         */
        fun indexOf(lines: List<LyricLine>, posSec: Float): Int {
            if (lines.isEmpty()) return 0
            var lo = 0
            var hi = lines.size - 1
            var idx = 0
            while (lo <= hi) {
                val mid = (lo + hi) / 2
                // `<=` 而不是 `<`：时间戳相同的行要取最后一个，与 indexOfLast 一致
                if (lines[mid].timeSec <= posSec) {
                    idx = mid
                    lo = mid + 1
                } else {
                    hi = mid - 1
                }
            }
            return idx
        }
    }
}
