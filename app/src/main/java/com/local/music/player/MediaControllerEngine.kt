package com.local.music.player

import android.content.Context
import android.util.Log
import android.os.Bundle
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.local.music.core.ui.state.AudioFxSettings
import com.local.music.core.ui.state.PlayerEngine
import com.local.music.core.ui.state.SongFormat
import com.local.music.core.ui.state.SongSpec

/**
 * 「坏文件自动跳过」的连续跳数上限。
 *
 * 为什么是 5：一首坏文件跳一次是修复体验，整个队列都坏还继续跳就是事故（无限循环：
 * 一首没播、界面疯狂切歌、统计被刷、电量被烧）。5 已经足够覆盖「专辑里夹了几首坏文件」
 * 这种常见情况，又能在「整段拷贝失败 / 整张卡掉线」时很快停手。
 */
private const val MAX_CONSECUTIVE_SKIPS = 5

/**
 * 引擎实现：MediaController 代理——真正的 ExoPlayer 活在 MusicPlaybackService 里，
 * 通知 / 锁屏 / 流体云 / 后台播放由 MediaSessionService 统一承担。
 * 整条队列经 setMediaItems 下发（稳定 mediaId），循环 / 随机用播放器原生模式；
 * 全部调用异步转发，播放状态经 Player.Listener 回流 AppState。
 */
class MediaControllerEngine(
    private val controller: MediaController,
    /** 封面解码用的 Context（`CoverArtBitmap.smallJpeg` 需读 externalCacheDir 下的封面文件）。
     *  只在构造 MediaItem 时用，传 applicationContext 即可，不持有 Activity。 */
    private val context: Context,
) : PlayerEngine {

    override var onPlayingChanged: ((Boolean) -> Unit)? = null
    override var onFormatChanged: ((SongFormat?) -> Unit)? = null
    override var onMediaItemChanged: ((String?) -> Unit)? = null
    override var onAutoAdvanced: ((String?) -> Unit)? = null

    /** 上一曲 mediaId：用于在自动切歌时报告「哪首播完了」（D1 完播计数）。
     *  构造时用服务端当前曲初始化——attach 后第一次自动切歌 previous 不再是 null
     *  （服务在播重连/每次冷启动场景不再丢一次计数）；服务空闲时初值为 null 亦无碍
     *  （首次 setMediaItems 走 PLAYLIST_CHANGED，不计数） */
    private var lastMediaId: String? = controller.currentMediaItem?.mediaId
    /** 播放错误上报：`recovered = true` 表示引擎**已自行恢复**（坏文件被自动跳过、队列继续往下播）。
     *  宿主据此只弹提示、不动播放态——详见 [PlayerEngine.onError] 的 KDoc。 */
    override var onError: ((String, Boolean) -> Unit)? = null

    /** 断连已上报标记（重连后清位，避免每个调用都重复刷错误横幅） */
    private var reportedDisconnected = false

    /** 连续自动跳过计数（坏文件跳过用，见 [onPlayerError]）。
     *
     *  **为什么不用 AtomicInteger / 不加锁**：Player.Listener 的全部回调（onPlayerError /
     *  onMediaItemTransition / onIsPlayingChanged）都由 MediaController 的 application looper
     *  串行派发，两条错误回调不可能真正并发进入本方法。这里是单线程状态，加锁只会制造
     *  「看起来线程安全」的假象。
     *
     *  **但「串行」不等于「不重入」**：skipToNextMediaItem 是走 binder 的异步命令，
     *  下一首若同样损坏，第二条 onPlayerError 会紧接着回来，中间不会有任何用户动作。
     *  所以「读计数 → 判上限 → 自增 → 跳」这一串必须在同一次回调内原子完成，
     *  否则计数就会被读成旧值，上限形同虚设。 */
    private var consecutiveSkips = 0

    /** 「这一次切歌是我们为了跳过坏文件自己发起的」标记（只影响计数重置，见 [onPlayerError]）。
     *
     *  **为什么必须记这一笔**：跳过动作本身也会走 onMediaItemTransition，reason 同样是 SEEK——
     *  与用户手动点「下一首」在监听器里**完全无法区分**。若对 SEEK 无条件清零，
     *  那么每跳一首就把计数归零，「连续跳过上限」将永远达不到，
     *  这正是「整队坏文件 → 无限切歌」事故的复现路径。所以跳过前先立旗，
     *  由紧接着的那次 transition 消费掉（不重置），其余 SEEK 才视为用户操作。
     *
     *  旗标残留的兜底：跳过命令若因边界原因没产生 transition（例如服务端队列刚好被替换），
     *  旗标会一直留到下一次被我们发起的 transition 或 onIsPlayingChanged(true) 消费掉，
     *  最坏情况是多吞掉**一次**「用户切歌」的重置，不会让上限永久失效（上限本身还有
     *  loadQueue 换队列重置 + 队列边界兜着）。 */
    private var autoSkipInFlight = false

    init {
        controller.addListener(
            object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    // 真的出声了 → 说明当前这首是可播的，「连续跳过」这条链就此断开。
                    // 注意判据用「真正 isPlaying」而不是「装载成功」：坏文件也能走到准备完成
                    // （容器头正常、中途才解不出来），只有实际播放才是可信的健康信号。
                    if (isPlaying) {
                        consecutiveSkips = 0
                        autoSkipInFlight = false
                    }
                    onPlayingChanged?.invoke(isPlaying)
                }

                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    val previous = lastMediaId
                    lastMediaId = mediaItem?.mediaId
                    // 用户主动切歌（见下）/ 自然完播 → 播放链是健康的，「连续跳过」就此断开。
                    // - SEEK：下一首 / 上一首 / 点列表 / 通知栏 / 耳机线控发起的切歌都归这一类，
                    //   用户是在操作，不是在等我们自愈。若不在这里清零，用户手动切走五首之后，
                    //   第一次碰到坏文件就会直接放弃跳过——计数被不相关的操作吃掉，是最典型的
                    //   那种「偶发、难复现」的 bug。在 transition 里清零而不是在 skipTo 入口清零，
                    //   是为了同时覆盖系统媒体面板等不经过本类的入口。
                    // - AUTO：前一首**自然播完**（含 REPEAT_MODE_ALL 绕回队首）才可能走到这里，
                    //   说明播放器确实能出声。
                    // **但跳过坏文件引发的 SEEK 必须先排除**：那是我们自己的动作，不能算「用户切歌」，
                    // 否则上限每次都被自己清零，等于没有。标志位由跳过处立、在这里消费。
                    if (autoSkipInFlight) {
                        autoSkipInFlight = false
                    } else if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK ||
                        reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO
                    ) {
                        consecutiveSkips = 0
                    }
                    // 完播计数（D1）：只有 AUTO 才算「播完上一首」——
                    // 手动切歌(SEEK)/随机跳转/列表重建都不计，与「播放次数」的用户预期一致
                    if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                        onAutoAdvanced?.invoke(previous)
                    }
                    onMediaItemChanged?.invoke(mediaItem?.mediaId)
                }

                /**
                 * 坏文件自动跳过（13b）。
                 *
                 * 动机：用户曲库里有从网盘 / 老旧硬盘 / 各种转码工具拷来的文件，坏文件是常态
                 * 而非例外。旧实现只把错误上报成一句文案就收手，于是**队列里混进一个坏文件，
                 * 整条队列就停在那一首**，用户必须手动点下一首——单个偶发问题被放大成「播放中断」，
                 * 是本地播放器最经典的用户投诉源。
                 *
                 * 三条设计红线：
                 * 1. **不是所有错误都跳**。只跳过「这一个是坏文件」的证据，见 [isSkippable]；
                 *    设备 / 会话层面的问题（AUDIO_TRACK_INIT_FAILED 之类）跳了也没用，
                 *    而且会变成「疯狂跳歌」。
                 * 2. **跳了也必须上报文案**。歌莫名消失比停住更让人困惑，用户有权知道发生了什么
                 *    ——所以这里上报先于跳过执行。
                 * 3. **上限 + 队列边界双保险**，见下面两处注释。
                 */
                override fun onPlayerError(error: PlaybackException) {
                    val skippable = isSkippable(error.errorCode)
                    // 队列只剩一首 / 已在队尾 ⇒ 无处可跳。
                    // **这一判定同时是死循环的最后一道闸**：REPEAT_MODE_ALL 下队尾的坏文件
                    // 本来会触发「跳回队首 → 又是坏文件 → 再跳」，被这里直接截断。
                    // 先判队列再判计数是有意的：队列只有一首时根本不该动，与计数多少无关。
                    val canSkip = skippable && controller.hasNextMediaItem()
                    // 连续跳满上限：基本可以断定不是「个别坏文件」，而是整段队列都不可用
                    // （整张存储卡掉线 / 拷贝中断 / 存储权限被回收）。再跳下去就是无限切歌：
                    // 一首没播成、界面抽风、播放次数被刷歪，不如停在这里把选择权交还用户。
                    val limitReached = canSkip && consecutiveSkips >= MAX_CONSECUTIVE_SKIPS

                    // **先算结论再措辞**：文案与真实行为必须一致。说「已跳过」却因为已是最后一首
                    // 而没跳，等于播放器在对用户撒谎；说「文件丢失」却悄悄换了首歌，用户则会
                    // 困惑「歌怎么自己变了」而找不到那句提示。所以每种情形都有独立措辞。
                    //
                    // 第二个参数 recovered（= 引擎已自行恢复，宿主据此只弹提示、不动播放态）：
                    // 判定口径必须与「到底跳没跳」严格一致——只有真的把跳过动作发出去了，
                    // 才敢说 recovered=true。否则宿主会以为「音频还在放」而不把界面打成暂停，
                    // 但实际播放已经停在坏文件上，UI 就会显示成「正在播放一首其实没响的歌」，
                    // 这比闪一下暂停糟糕得多。所以 recovered 与下面的 `if (!canSkip || limitReached)
                    // return` 用的是同一组条件，且顺序上有意先上报后跳（见下方注释）。
                    val recovered = canSkip && !limitReached
                    onError?.invoke(
                        when {
                            !skippable -> "播放出错（${error.errorCodeName}）"
                            limitReached -> "连续跳过 $MAX_CONSECUTIVE_SKIPS 首仍无法播放，已暂停自动跳过"
                            // 可跳过却跳不了 ⇒ 已经在队尾（含队列只剩一首），没有下一首可跳。
                            // 措辞必须如实说明「没跳」，不能让用户以为播放器已经换歌了。
                            !canSkip -> if (isIoError(error.errorCode)) {
                                "音频文件已丢失或不可读"
                            } else {
                                "音频格式不支持播放"
                            }

                            isIoError(error.errorCode) -> "音频文件已丢失或不可读，已跳过"
                            else -> "音频格式不支持播放，已跳过"
                        },
                        recovered,
                    )

                    if (!canSkip || limitReached) return
                    consecutiveSkips++
                    // 立旗：紧接着的那次 SEEK transition 是我们自己造成的，不得重置计数。
                    // 旗标在 onMediaItemTransition 里消费（见 autoSkipInFlight 注释）。
                    autoSkipInFlight = true
                    // 自增与判定在同一次回调内完成：下一条错误的回调一定会看到新值，
                    // 不会因为 binder 往返的时间差把上限绕过去（见 [consecutiveSkips] 注释）。
                    // 用 controller 原生方法而不是自己的 skipToNext()：后者会先 checkConnected()
                    // 并可能再报一次「连接已断开」，与已上报的错误文案叠在一起刷屏。
                    // 方法名说明：任务书里写的 skipToNextMediaItem 是 ExoPlayer 的**内部**叫法，
                    // Player/MediaController 暴露的公开 API 是 seekToNextMediaItem()（本类
                    // 既有的 skipToNext() 用的也是它）——语义一致：忽略 REPEAT_MODE_ONE 跳到
                    // 下一首并回到开头。前面已用 hasNextMediaItem() 保证调用时确实有下一首。
                    //
                    // **错误态必须先 prepare（真机回归发现的缺陷）**：播放错误发生后服务端
                    // ExoPlayer 进入 STATE_IDLE，而 **IDLE 态下 seekToNextMediaItem 只改索引
                    // 不触发装载**——命令发出去了、横幅也如实说「已跳过」，但下一首永远
                    // 不会真正开始加载，播放停死在坏文件上（模拟器实测：active item 不动、
                    // state=ERROR 常驻）。IDLE 下必须显式 prepare()/play() 才会继续。
                    // 这也解释了为什么横幅与行为不一致：recovered 判定基于「命令已下发」，
                    // 而命令在错误态下是半生效的。先 prepare 再 seek，play 恢复播放意图。
                    runCatching { controller.prepare() }
                    controller.seekToNextMediaItem()
                    controller.play()
                }

                override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                    onFormatChanged?.invoke(readAudioFormat())
                }
            },
        )
    }

    /** 当前选中音轨的实时参数（解码输出 PCM 编码即位深来源） */
    private fun readAudioFormat(): SongFormat? {
        val format = controller.currentTracks.groups
            .firstOrNull { it.isSelected && it.type == C.TRACK_TYPE_AUDIO }
            ?.getTrackFormat(0) ?: return null
        val mime = format.sampleMimeType ?: return null
        val bitDepth = if (format.pcmEncoding != androidx.media3.common.Format.NO_VALUE) {
            when (format.pcmEncoding) {
                android.media.AudioFormat.ENCODING_PCM_8BIT -> 8
                android.media.AudioFormat.ENCODING_PCM_16BIT -> 16
                android.media.AudioFormat.ENCODING_PCM_24BIT_PACKED -> 24
                android.media.AudioFormat.ENCODING_PCM_32BIT,
                android.media.AudioFormat.ENCODING_PCM_FLOAT,
                -> 32
                else -> 0
            }
        } else {
            0
        }
        return SongFormat(
            codec = MediaLibrary.codecFromMime(mime),
            sampleRateHz = format.sampleRate.takeIf { it > 0 } ?: 0,
            bitDepth = bitDepth,
            bitrateKbps = format.bitrate.takeIf { it > 0 }?.div(1000) ?: 0,
        )
    }

    /**
     * 这个错误码是否值得「跳过当前曲、继续放下一首」。
     *
     * **为什么不能一律跳过**：onPlayerError 的入参只有错误码，没有「责任归属于哪个文件」的
     * 信息。会话 / 设备层面的故障（音频轨道初始化失败、音频处理初始化失败、安全策略失败、
     * 运行时异常）与具体音频文件无关，跳过去下一首同样会失败；而一旦这样做，每首都会
     * 触发一次错误 → 跳歌 → 再错误，配合上限就是「连着跳五首然后停住」，
     * 用户体验比原地报错更莫名其妙。
     *
     * 因此白名单只收两类真正能归咎于「这一个文件」的错误：
     * - **IO / 容器解析**：文件不存在、无权限、读越界、容器头损坏或格式不认识
     *   —— 换一首就没事，属于典型的坏文件 / 已删除文件 / SD 卡掉线。
     * - **解码失败 / 格式不支持**：容器头读得出来但解不出样本，或本机没有对应解码器
     *   （例如 APE、DSD 这类 Media3 开箱不支持的格式）—— 同样是这一首的问题。
     *
     * 特意**不**放在白名单里的几个近亲，理由如下：
     * - ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES：确实也是「解不了」，但它的成因常是
     *   「设备能力不足」而不是文件本身不可播（同一乐曲换台机器就能放）。它与
     *   DECODING_FORMAT_UNSUPPORTED 一样落进「格式不支持」文案，只是不触发自动跳过，
     *   避免在低端机上把整个高解析曲库一路跳完。
     * - ERROR_CODE_BEHIND_LIVE_WINDOW / ERROR_CODE_TIMEOUT：直播窗口与网络超时是瞬时的，
     *   本地文件播放基本不出现；真要出现也该停下让用户看见，而不是悄悄换首歌。
     */
    private fun isSkippable(errorCode: Int): Boolean = when (errorCode) {
        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
        PlaybackException.ERROR_CODE_IO_NO_PERMISSION,
        PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
        PlaybackException.ERROR_CODE_DECODING_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        -> true

        else -> false
    }

    /** IO 类（文件不存在 / 无权限 / 读越界）——文案分组用，与 [isSkippable] 的白名单分层 */
    private fun isIoError(errorCode: Int): Boolean = when (errorCode) {
        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
        PlaybackException.ERROR_CODE_IO_NO_PERMISSION,
        PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE,
        -> true

        else -> false
    }

    private fun SongSpec.toMediaItem(): MediaItem =
        MediaItem.Builder()
            .setMediaId(id)
            .setUri(uri)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setArtist(artist)
                    .setAlbumTitle(album)
                    // **刻意不下发 artworkUri**（F2）：它指向应用私有 cache 的 file:// 路径，
                    // 系统进程读不到（真机 EACCES / 模拟器 ENOENT，每次切歌刷屏）。
                    // 封面改由 [withArtwork] 内嵌小 JPEG，见 CoverArtBitmap 类注释。
                    .build(),
            )
            .build()

    /**
     * 给队首到当前曲附近的一小段内嵌封面（F2）。
     *
     * 为什么内嵌而不是给 uri：私有 cache 的 file:// 系统进程读不到（EACCES），
     * FileProvider content:// 也读不到（metadata 是纯数据，带不了 grant flag，实测
     * SecurityException）。内嵌字节是唯一能真正到达系统进程的形式。
     *
     * 为什么按预算裁剪：单张压到 ~5–15KB，但千首队列 × 10KB 仍会撞 Binder 的 1MB
     * 事务上限 —— 届时 setMediaItems 整个失败、播放直接不可用，比没有封面严重得多。
     * 只给 [startIndex] 起的一小段：系统面板与通知显示的都是**当前曲**封面，
     * 而当前曲在装载时必然落在这段窗口内。
     *
     * 从 startIndex 起而不是 0：从歌单/搜索结果进播放时当前曲可能在队列很靠后，
     * 只从 0 开始会漏掉真正要显示的那首。
     */
    private fun List<MediaItem>.withArtwork(specs: List<SongSpec>, startIndex: Int): List<MediaItem> {
        var budget = CoverArtBitmap.BUDGET_BYTES
        val art = HashMap<String, ByteArray>()
        // 优先级：当前曲 → 其后 → 其前（面板先显示当前曲，随后是「下一首」）。
        // **必须封顶尝试次数**：预算只有 256KB（能装几十首），而队列可达上千首。
        // 见下面 `if (bytes.size > budget) break` 的注释 —— 两者合起来才保证 O(容量) 而不是 O(队列)。
        val order = ((startIndex until specs.size) + (0 until startIndex))
            .take(CoverArtBitmap.MAX_ATTEMPTS)
        for (i in order) {
            val spec = specs.getOrNull(i) ?: continue
            if (art.containsKey(spec.id)) continue
            val bytes = CoverArtBitmap.smallJpeg(context, spec.artworkUri) ?: continue
            // **装不下就整轮停手，不能 continue**：这是一个曾经踩过的性能坑 ——
            // 写成 continue 时，只要剩余预算还大于 0 却小于单张尺寸，`budget <= 0` 的
            // 退出条件就永远不成立，循环会把队列里**剩下的每一首**都完整解码 + JPEG 压缩
            // 一遍再丢弃。千首队列 = 近千次无用解码（单张 8~20ms）→ 主线程阻塞十几秒，
            // 而本函数由 loadQueue（AppState.startCurrent / prepareResume）在 UI 线程调用
            // → ANR。bytes.size 随下标的推进不会再变小（都走同一套压缩档位），
            // 所以「这一张装不下」等价于「后面同样装不下」，直接 break 无损。
            if (bytes.size > budget) break
            budget -= bytes.size
            art[spec.id] = bytes
        }
        if (art.isEmpty()) return this
        return map { item ->
            val bytes = art[item.mediaId] ?: return@map item
            item.buildUpon()
                .setMediaMetadata(
                    item.mediaMetadata.buildUpon()
                        .setArtworkData(bytes, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
                        .build(),
                )
                .build()
        }
    }

    /**
     * 断连检测：MediaController.Listener 只能在 Builder 注册，而引擎拿到的是成品 controller，
     * 无处挂 onDisconnected——改用轻量心跳：play/pause/seek/skip/positionSec 等入口检查 isConnected，
     * 断连即上报并置位（重连后清位）；tick 周期调 positionSec，断连一个心跳内可见，
     * UI 走既有错误横幅而非假死。返回 false = 已断连（调用方 no-op 返回）。
     */
    private fun checkConnected(): Boolean {
        if (controller.isConnected) {
            reportedDisconnected = false
            return true
        }
        if (!reportedDisconnected) {
            reportedDisconnected = true
            // recovered = false：断连是**真错误**，没有任何自愈动作兜住它。
            // 必须让宿主照旧清播放态（playing=false / engineLoadedUri=null / 清定时武装戳）——
            // 传 true 会让界面继续显示「正在播放」，而实际声音早已断掉，比直接报错更误导。
            onError?.invoke("播放服务连接已断开", false)
        }
        return false
    }

    /** 整条队列下发给服务端播放器：起始位置随 setMediaItems 原子下发（独立 seekTo 会在装载窗口被丢弃） */
    override fun loadQueue(specs: List<SongSpec>, startIndex: Int, startSec: Float, autoPlay: Boolean) {
        // 换了一整条队列 = 用户换了播放语境（换歌单 / 换搜索结果），上一次「连续跳过」的记录
        // 不该跨语境累计：否则在某张坏歌单里跳过 4 首后切到正常歌单，只需再碰 1 首坏文件
        // 就直接放弃——上限被别处的坏文件消耗掉了。此处重置，同时把可能残留的跳过旗标清掉。
        consecutiveSkips = 0
        autoSkipInFlight = false
        val idx = startIndex.coerceIn(0, (specs.size - 1).coerceAtLeast(0))
        val items = specs.map { it.toMediaItem() }.withArtwork(specs, idx)
        val startPositionMs = if (startSec > 0f) (startSec * 1000).toLong() else C.TIME_UNSET
        controller.setMediaItems(
            items,
            /* startIndex = */ idx.coerceIn(0, (items.size - 1).coerceAtLeast(0)),
            /* startPositionMs = */ startPositionMs,
        )
        controller.prepare()
        controller.playWhenReady = autoPlay
    }

    override fun setRepeatMode(repeatAll: Boolean, repeatOne: Boolean) {
        controller.repeatMode = when {
            repeatOne -> Player.REPEAT_MODE_ONE
            repeatAll -> Player.REPEAT_MODE_ALL
            else -> Player.REPEAT_MODE_OFF
        }
    }

    override fun setShuffleMode(enabled: Boolean) {
        controller.shuffleModeEnabled = enabled
    }

    /** 均衡器预设：经自定义命令下发服务端（android.media.audiofx.Equalizer） */
    override fun setEqPreset(presetName: String) {
        runCatching {
            val future = controller.sendCustomCommand(
                SessionCommand(MusicPlaybackService.CMD_SET_EQ, Bundle.EMPTY),
                Bundle().apply { putString(MusicPlaybackService.KEY_PRESET, presetName) },
            )
            // 异步结果留痕：EQ 曾静默失效（历史坑），失败只打日志供诊断、不打扰用户
            future.addListener(
                {
                    val result = runCatching { future.get() }.getOrNull()
                    if (result == null || result.resultCode != SessionResult.RESULT_SUCCESS) {
                        android.util.Log.w(
                            "MediaControllerEngine",
                            "自定义命令下发失败 cmd=${MusicPlaybackService.CMD_SET_EQ} " +
                                "resultCode=${result?.resultCode}",
                        )
                    }
                },
                Runnable::run, // 直接执行器：仅记日志，无需线程切换
            )
        }
    }

    /**
     * 音效链（响度归一 / 低音增强 / 虚拟环绕）：经自定义命令整组下发服务端。
     *
     * 一次带全 4 个参数的理由见 MusicPlaybackService.CMD_SET_AUDIO_FX 的注释
     * （分开下发会在 binder 异步下出现半套状态）。与 EQ 同款留痕：
     * onConnect 漏声明命令曾让整个 EQ 静默失效，这类故障在真机上完全无感，
     * 只能靠日志事后定位，所以每个命令都必须记录失败结果。
     */
    override fun setAudioFx(settings: AudioFxSettings) {
        runCatching {
            val future = controller.sendCustomCommand(
                SessionCommand(MusicPlaybackService.CMD_SET_AUDIO_FX, Bundle.EMPTY),
                Bundle().apply {
                    putBoolean(MusicPlaybackService.KEY_FX_LOUDNESS, settings.loudnessNorm)
                    putBoolean(MusicPlaybackService.KEY_FX_BASS, settings.bassBoost)
                    putInt(MusicPlaybackService.KEY_FX_BASS_LEVEL, settings.bassLevel)
                    putBoolean(MusicPlaybackService.KEY_FX_VIRTUAL, settings.virtualizer)
                    putInt(MusicPlaybackService.KEY_FX_VIRTUAL_LEVEL, settings.virtualLevel)
                },
            )
            future.addListener(
                {
                    val result = runCatching { future.get() }.getOrNull()
                    if (result == null || result.resultCode != SessionResult.RESULT_SUCCESS) {
                        android.util.Log.w(
                            "MediaControllerEngine",
                            "自定义命令下发失败 cmd=${MusicPlaybackService.CMD_SET_AUDIO_FX} " +
                                "resultCode=${result?.resultCode}",
                        )
                    }
                },
                Runnable::run,
            )
        }
    }

    /** 定时关闭：经自定义命令下发服务端（分钟档协程/播完档播放器监听都在服务端承担） */
    override fun setSleepTimer(modeMinutes: Int) {
        runCatching {
            val future = controller.sendCustomCommand(
                SessionCommand(MusicPlaybackService.CMD_SET_SLEEP_TIMER, Bundle.EMPTY),
                Bundle().apply { putInt(MusicPlaybackService.KEY_SLEEP_MODE, modeMinutes) },
            )
            // 异步结果留痕：与 EQ 同型（onConnect 漏声明曾静默失效），失败只打日志供诊断
            future.addListener(
                {
                    val result = runCatching { future.get() }.getOrNull()
                    if (result == null || result.resultCode != SessionResult.RESULT_SUCCESS) {
                        android.util.Log.w(
                            "MediaControllerEngine",
                            "自定义命令下发失败 cmd=${MusicPlaybackService.CMD_SET_SLEEP_TIMER} " +
                                "resultCode=${result?.resultCode}",
                        )
                    }
                },
                Runnable::run,
            )
        }
    }

    /** 播放速度：MediaController 原生 setPlaybackSpeed（服务端 ExoPlayer Sonic 变速不变调，无服务端改动） */
    override fun setPlaybackSpeed(speed: Float) {
        if (checkConnected()) controller.setPlaybackSpeed(speed)
    }

    override fun play() {
        if (checkConnected()) controller.play()
    }

    override fun pause() {
        if (checkConnected()) controller.pause()
    }

    override fun seekTo(sec: Float) {
        if (checkConnected()) controller.seekTo((sec * 1000).toLong())
    }

    override fun skipTo(index: Int) {
        if (checkConnected()) controller.seekToDefaultPosition(index)
    }

    override fun skipToNext() {
        if (checkConnected()) controller.seekToNextMediaItem()
    }

    override fun skipToPrevious() {
        if (checkConnected()) controller.seekToPreviousMediaItem()
    }

    override fun hasNextMediaItem(): Boolean = controller.hasNextMediaItem()

    override fun hasPreviousMediaItem(): Boolean = controller.hasPreviousMediaItem()

    // 增量队列编辑：MediaController 原生 API 即可原地改队列，当前曲不受影响；
    // move 语义（toIndex 以移除后的列表坐标计）与 AppState 侧 Kotlin removeAt/add 一致。
    // shuffle 开启时插入走服务端命令（DefaultShuffleOrder 会把新项随机放置，
    // 服务端重建播放序保证「下一首播放 = 紧跟当前曲、最后播放 = 队尾」；
    // pinLast 由调用方显式传入意图——不再用 index >= mediaItemCount 猜位置，
    // 当前曲在队尾时「下一首播放」曾被误判成「最后播放」）
    override fun addQueueItems(index: Int, specs: List<SongSpec>, pinLast: Boolean) {
        if (controller.shuffleModeEnabled && specs.isNotEmpty()) {
            runCatching {
                controller.sendCustomCommand(
                    SessionCommand(MusicPlaybackService.CMD_INSERT_SHUFFLE_FIX, Bundle.EMPTY),
                    Bundle().apply {
                        putInt(MusicPlaybackService.KEY_INDEX, index)
                        putSerializable(MusicPlaybackService.KEY_SPECS, ArrayList(specs))
                        putString(
                            MusicPlaybackService.KEY_PIN_MODE,
                            if (pinLast) MusicPlaybackService.PIN_LAST else MusicPlaybackService.PIN_NEXT,
                        )
                    },
                )
            }.onFailure { controller.addMediaItems(index, specs.map { it.toMediaItem() }.withArtwork(specs, 0)) }
        } else {
            // 追加插入：新项就是「下一首播放」的目标，自然可以带封面；预算取当前曲之后一段
            controller.addMediaItems(index, specs.map { it.toMediaItem() }.withArtwork(specs, 0))
        }
    }

    override fun moveQueueItem(fromIndex: Int, toIndex: Int) = controller.moveMediaItem(fromIndex, toIndex)

    override fun removeQueueItems(fromIndex: Int, toIndex: Int) {
        controller.removeMediaItems(fromIndex, toIndex)
    }

    override fun currentMediaId(): String? = controller.currentMediaItem?.mediaId

    override fun mediaIds(): List<String> =
        (0 until controller.mediaItemCount).map { controller.getMediaItemAt(it).mediaId }

    override fun isEnginePlaying(): Boolean = controller.isPlaying

    override fun positionSec(): Float {
        checkConnected() // 心跳入口：断连在此上报；返回值仍取 controller 缓存的当前进度
        return (controller.currentPosition.coerceAtLeast(0)) / 1000f
    }

    override fun durationSec(): Float {
        val d = controller.duration
        return if (d > 0) d / 1000f else 0f
    }

    /** 停止并清空装载（切到演示曲目 / 服务残留失效曲目时用） */
    override fun stopAndClear() {
        controller.pause()
        controller.clearMediaItems()
    }

    override fun release() {
        controller.release()
    }
}
