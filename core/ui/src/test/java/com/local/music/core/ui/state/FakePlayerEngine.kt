package com.local.music.core.ui.state

import com.local.music.core.ui.state.AudioFxSettings

/**
 * 纯内存假播放引擎——AppState 单测的「另一半地基」。
 *
 * ## 为什么存在
 *
 * 04 工程报告 #6 的原话：「`AppState()` 无参构造 = 无法注入假引擎」→「零测试不是
 * 没时间写，是写不出来」。`clock` 参数解锁了时间维度（见 [AppStateClockTest]），
 * 本类解锁引擎维度：AppState 的队列编辑/随机/恢复链路全部经 [PlayerEngine] 下发，
 * 没有假引擎就只能靠真 MediaController（要走 binder + 服务，纯 JVM 单测跑不了）。
 *
 * ## 设计：记录而非模拟
 *
 * 不是把 ExoPlayer 的行为仿真出来（那是无底洞），而是**记录调用**让测试断言：
 * - `loadQueueCalls` / `skipToCalls` / `removedRanges` 等列表记下每次调用与参数；
 * - 状态字段（`playing` / `positionSec` / 队列 id 表）由测试手动设置，
 *   再通过 AppState 的回调（`onMediaItemChanged` / `onIsPlayingChanged`）回流，
 *   与真引擎的「命令下发 + 监听回流」双通道语义一致。
 *
 * 这正是「测试替身」的正确形态：断言的是 **AppState 对引擎说了什么**（交互测试），
 * 而不是引擎自己会算出什么（那是 Media3 的测试责任）。
 *
 * ## 已知边界
 *
 * 未实现真实播放推进：`positionSec()` 恒为测试设置的值。完播推进场景由测试显式调
 * `state.tick()` 或触发回调模拟，不依赖时间流逝——这保证了测试的确定性。
 */
class FakePlayerEngine : PlayerEngine {

    // ── 回调槽位（AppState.attachPlayer 会填充）─────────────
    override var onPlayingChanged: ((Boolean) -> Unit)? = null
    override var onFormatChanged: ((SongFormat?) -> Unit)? = null
    override var onMediaItemChanged: ((String?) -> Unit)? = null
    override var onAutoAdvanced: ((String?) -> Unit)? = null
    override var onError: ((message: String, recovered: Boolean) -> Unit)? = null

    // ── 可编程状态（测试直接赋值）──────────────────────────
    /** 假想的服务端队列（mediaId 列表）。loadQueue / addQueueItems 等会真实维护它。 */
    val queueIds = mutableListOf<String>()

    /** 当前索引（对 [queueIds]） */
    var currentIndex = 0

    var playing = false
    var positionSec = 0f
    var durationSec = 0f

    // ── 调用记录（测试断言用）──────────────────────────────
    val loadQueueCalls = mutableListOf<LoadCall>()
    val skipToCalls = mutableListOf<Int>()
    val skipNextCalls = mutableListOf<Int>()
    val skipPrevCalls = mutableListOf<Int>()
    val addItemsCalls = mutableListOf<AddCall>()
    val moveCalls = mutableListOf<Pair<Int, Int>>()
    val removedRanges = mutableListOf<Pair<Int, Int>>()
    val shuffleCalls = mutableListOf<Boolean>()
    val repeatCalls = mutableListOf<Pair<Boolean, Boolean>>()
    val stopClearCalls = mutableListOf<Int>()
    val playCalls = mutableListOf<Int>()
    val pauseCalls = mutableListOf<Int>()
    val seekCalls = mutableListOf<Float>()
    val speedCalls = mutableListOf<Float>()
    val sleepTimerCalls = mutableListOf<Int>()
    val audioFxCalls = mutableListOf<AudioFxSettings>()

    data class LoadCall(val ids: List<String>, val startIndex: Int, val startSec: Float, val autoPlay: Boolean)
    data class AddCall(val index: Int, val ids: List<String>, val pinLast: Boolean)

    // ── PlayerEngine 实现：维护假队列 + 记录 ────────────────

    override fun loadQueue(specs: List<SongSpec>, startIndex: Int, startSec: Float, autoPlay: Boolean) {
        queueIds.clear()
        queueIds.addAll(specs.map { it.id })
        currentIndex = startIndex.coerceIn(0, queueIds.lastIndex.coerceAtLeast(0))
        loadQueueCalls += LoadCall(queueIds.toList(), startIndex, startSec, autoPlay)
    }

    override fun setRepeatMode(repeatAll: Boolean, repeatOne: Boolean) {
        repeatCalls += repeatAll to repeatOne
    }

    override fun setShuffleMode(enabled: Boolean) {
        shuffleCalls += enabled
    }

    override fun setEqPreset(presetName: String) { /* EQ 预设不在本批测试面内，忽略 */ }

    override fun setAudioFx(settings: AudioFxSettings) {
        audioFxCalls += settings
    }

    override fun setSleepTimer(modeMinutes: Int) {
        sleepTimerCalls += modeMinutes
    }

    override fun setPlaybackSpeed(speed: Float) {
        speedCalls += speed
    }

    override fun play() {
        playing = true
        playCalls += playCalls.size
    }

    override fun pause() {
        playing = false
        pauseCalls += pauseCalls.size
    }

    override fun seekTo(sec: Float) {
        positionSec = sec
        seekCalls += sec
    }

    override fun skipTo(index: Int) {
        if (index in queueIds.indices) currentIndex = index
        skipToCalls += index
    }

    override fun skipToNext() {
        if (currentIndex < queueIds.lastIndex) currentIndex++
        skipNextCalls += skipNextCalls.size
    }

    override fun skipToPrevious() {
        if (currentIndex > 0) currentIndex--
        skipPrevCalls += skipPrevCalls.size
    }

    override fun hasNextMediaItem(): Boolean = currentIndex < queueIds.lastIndex
    override fun hasPreviousMediaItem(): Boolean = currentIndex > 0

    override fun addQueueItems(index: Int, specs: List<SongSpec>, pinLast: Boolean) {
        val ids = specs.map { it.id }
        addItemsCalls += AddCall(index, ids, pinLast)
        // 按真实 MediaController 语义维护假队列（UI 队列对齐测试依赖这个）
        val clamped = index.coerceIn(0, queueIds.size)
        queueIds.addAll(clamped, ids)
    }

    override fun moveQueueItem(fromIndex: Int, toIndex: Int) {
        moveCalls += fromIndex to toIndex
        if (fromIndex in queueIds.indices && toIndex in queueIds.indices) {
            queueIds.add(toIndex, queueIds.removeAt(fromIndex))
        }
    }

    override fun removeQueueItems(fromIndex: Int, toIndex: Int) {
        removedRanges += fromIndex to toIndex
        if (fromIndex in queueIds.indices) {
            val hi = toIndex.coerceAtMost(queueIds.lastIndex)
            for (i in hi downTo fromIndex) queueIds.removeAt(i)
        }
    }

    override fun mediaIds(): List<String> = queueIds.toList()
    override fun currentMediaId(): String? = queueIds.getOrNull(currentIndex)
    override fun isEnginePlaying(): Boolean = playing
    override fun positionSec(): Float = positionSec
    override fun durationSec(): Float = durationSec

    override fun stopAndClear() {
        queueIds.clear()
        currentIndex = 0
        playing = false
        stopClearCalls += stopClearCalls.size
    }

    override fun release() { /* 测试无需释放 */ }

    // ── 测试辅助：模拟引擎侧事件回流 ────────────────────────

    /** 模拟「引擎切到了 mediaId 这首歌」：AppState 经 onMediaItemChanged 收敛 UI 状态 */
    fun emitMediaItemChanged(mediaId: String?) {
        onMediaItemChanged?.invoke(mediaId)
    }

    /** 模拟「引擎自动完播切歌」：驱动播放次数统计（D1 只在 AUTO 时 +1） */
    fun emitAutoAdvanced(previousId: String?) {
        onAutoAdvanced?.invoke(previousId)
    }

    /** 模拟「引擎播放态变化」 */
    fun emitPlayingChanged(isPlaying: Boolean) {
        playing = isPlaying
        onPlayingChanged?.invoke(isPlaying)
    }

    /** 模拟引擎报错（recovered 见 [PlayerEngine.onError] 注释） */
    fun emitError(message: String, recovered: Boolean = false) {
        onError?.invoke(message, recovered)
    }
}
