package com.local.music.player

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import androidx.palette.graphics.Palette
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import com.local.music.core.ui.components.ArtStyle
import com.local.music.core.ui.components.DemoSong
import com.local.music.core.ui.components.LyricLine
import com.local.music.core.ui.theme.AuroraPalette
import com.local.music.core.ui.theme.GlassPalettes

/**
 * 真曲库装载：MediaStore 扫描 /sdcard/Music 下的音频 → DemoSong
 * （内嵌封面缩略图 + 封面取色派生 AuroraPalette + 同名 .lrc 歌词解析）。
 */
object MediaLibrary {

    private const val FALLBACK_ART_COUNT = 6
    private const val TAG = "MediaLibrary"

    /** 小写 hex 查表（[stableHash] 用）：避免 formatter 的 locale 依赖与每次调用的对象分配 */
    private val HEX_CHARS = "0123456789abcdef".toCharArray()

    /** 扫描互斥：并发重扫（如设置页连点 / 启动扫描与手动重扫撞车）只跑一次，重入直接返回当前结果 */
    private val scanning = java.util.concurrent.atomic.AtomicBoolean(false)
    private val scanLock = Object()
    @Volatile private var lastScan: ScanResult? = null

    /**
     * 全量/增量扫描。已有缓存时走增量：只对「新增 / 指纹变化」的条目做元数据提取，
     * 其余直接复用缓存项（大曲库从分钟级降到秒级）。
     * 指纹 = dataPath + DATE_MODIFIED + SIZE（同名同大小但被改写也能识别）。
     *
     * B3 两层分离：扫描与缓存始终**全量**（短音频也提取一次、照常记指纹），
     * 「短于 [MIN_TRACK_SEC]」的过滤只在返回组装阶段做——开过滤=输出滤掉但指纹
     * 留在缓存里；关过滤=直接从缓存出，零重解码（大曲库通知音不再每次冷启动白解码）。
     *
     * @param filterShort 过滤短音频（B3：默认 <30s 的通知音/录音碎片不进返回列表；调用方传设置开关）
     * @param isCancelled 取消通道（P2-19）：MediaStore 游标每轮循环开头检查，命中即 break
     *   （**break 而非抛异常**——finally 才能正常关 cursor、复位 scanning 标志）。
     *   中止时返回已组装的部分结果，且本次不做孤儿封面清扫（见 [loadInternal] 末尾）；
     *   调用方也不应据此写缓存。默认 `{ false }`：既有调用点不改也编译。
     */
    fun load(
        context: Context,
        filterShort: Boolean = true,
        isCancelled: () -> Boolean = { false },
    ): List<DemoSong> = loadWithResult(context, filterShort, isCancelled)?.songs.orEmpty()

    /**
     * 与 [load] 完全同一条路径，但**把本次扫描的指纹一并返回**（P3-32 防线的接线点）。
     *
     * 为什么必须有它：`writeCache` 的 `fingerprints` 参数注释一直说「GlassApp 拿到
     * load() 返回的 ScanResult 后可传 result.fingerprints」——而 [load] 的返回类型是
     * `List<DemoSong>`，**根本拿不到指纹**，于是那个防错位参数在生产链上永远传 null，
     * 只能回落到单例 `lastScan`。并发重扫（启动扫描 + 设置页连点）时，先完成的扫描会
     * 覆盖 `lastScan`，后执行的那次 `writeCache` 就可能拿**另一轮**的指纹配这一轮的
     * songs —— 指纹错位后下次增量永远 miss，退化成每次冷启动全量重扫。
     *
     * 返回值：
     * - `null` = 重入且拿不到可用结果（上一次扫描被 cancel 中止）→ 调用方**不应写缓存**；
     * - 非 null = 本次（或最近一次完整）扫描结果，`fingerprints` 与 `songs` **同源**。
     *
     * `songs` 已按 [filterShort] 做过输出层过滤；`aborted` 透传 `loadInternal` 的中止标记。
     */
    fun loadWithResult(
        context: Context,
        filterShort: Boolean = true,
        isCancelled: () -> Boolean = { false },
    ): ScanResult? {
        // 互斥：已有扫描在跑就等它，拿它的结果（避免两路并发写同一缓存文件）
        if (!scanning.compareAndSet(false, true)) {
            synchronized(scanLock) {
                while (scanning.get()) {
                    runCatching { scanLock.wait(200L) }
                }
            }
            // 重入方拿到的是上次全量结果，同样只做输出层过滤（按本次开关）
            //
            // **中止的半截结果不能当全量用**（本轮修复）：主扫描可能因 isCancelled
            // （Activity 重建 / 离开组合）提前 break，此时 lastScan.songs 是**半截**，
            // 而 aborted 标志只存在于 ScanResult 里、旧实现的重入路径完全看不见它。
            // 后果与 writeCache 的 P2-19 防线同型：重入方把半截曲库当成完整结果返回，
            // 调用方（loadRealLibrary）随后会把它写进缓存、覆盖好数据。
            // 这里返回 null 让调用方走「失败/无结果」分支（不写缓存），
            // 比返回半截列表安全 —— 反正紧接着的一次重扫会拿到完整结果。
            val last = lastScan ?: return null
            if (last.aborted) {
                Log.w(TAG, "reentrant load: in-flight scan aborted; returning empty (not half result)")
                return null
            }
            return last.copy(songs = outputFiltered(last.songs, filterShort))
        }
        try {
            val result = loadInternal(context, isCancelled)
            lastScan = result
            return result.copy(songs = outputFiltered(result.songs, filterShort))
        } finally {
            scanning.set(false)
            synchronized(scanLock) { scanLock.notifyAll() }
        }
    }

    /** B3 输出层过滤：短于 [MIN_TRACK_SEC] 的条目不进返回列表；指纹与缓存不受影响（见 [load]） */
    private fun outputFiltered(songs: List<DemoSong>, filterShort: Boolean): List<DemoSong> =
        if (filterShort) songs.filter { it.durationSec >= MIN_TRACK_SEC } else songs

    // 已删除：`fun lastScanResult(): ScanResult?`（本轮清理）。
    // 全仓零调用：writeCache 直接读私有字段 `lastScan`，没有任何外部消费者。
    // 保留它等于给「从单例里读跨次扫描状态」留一个后门 —— 正是 P3-32 那类
    // 「扫描 A 的指纹配到扫描 B 的 songs 上」错位的温床。需要指纹请用 load() 返回的
    // ScanResult（或 writeCache 的 fingerprints 显式参数）。

    /** 缓存指纹：扫描前先按 dataPath 建索引，命中且指纹一致则复用整条记录 */
    /**
     * 缓存条目。[hadCoverRecord]：缓存文件里**曾经记录过** artworkUri（哪怕此刻文件已不在）。
     *
     * 为什么必须单独存这个布尔，而不能看 [song].artworkUri：增量扫描的 coverLost 判据是
     * 「缓存说以前有封面，但磁盘 PNG 没了」→ 落回全量提取重新生成。而 song.artworkUri
     * 是**已被读缓存时解析过**的值（文件不在就变 null），于是 coverLost 永远不成立：
     * 会被误判成「这首歌本来就没封面」直接复用，随后 writeCache 把 null 写死进缓存 →
     * **封面永久丢失再也不会自愈**。
     * 真机复现过：系统清理 externalCacheDir（covers/ 在 /sdcard/Android/data 下，可被
     * 清理助手/系统回收）后重启，57 首封面全灭且缓存被写成 0 张，只剩手动删缓存重扫才恢复。
     */
    private data class CachedEntry(
        val song: DemoSong,
        val lastModifiedSec: Long,
        val size: Long,
        val hadCoverRecord: Boolean = false,
    )

    /**
     * 短音频阈值（B3）：小于该秒数视为通知音/录音碎片，默认过滤掉。
     *
     * **单一事实源**：直接引用 `AppState.MIN_TRACK_SEC`。输出层已收敛到
     * `AppState.visibleLibrary()`（P2-④），扫描侧这里只是同口径的兜底/直调路径——
     * 两处各留一份字面量会有「改了一处、另一处没改」的分叉风险（比如扫描侧滤 30s、
     * 输出层滤 20s，用户就会看到开关行为飘忽）。状态层不依赖 app 模块是单向的，
     * 反向引用常量没有循环依赖。
     */
    private const val MIN_TRACK_SEC = com.local.music.core.ui.state.AppState.MIN_TRACK_SEC

    private fun loadInternal(context: Context, isCancelled: () -> Boolean = { false }): ScanResult {
        // 先读旧缓存建指纹索引（v3 才有 lastModified/size；旧缓存缺省 0 视为不命中 → 该条重扫）。
        // light 模式：只读 JSON 字段，不解码封面、不读歌词（那是命中后才需要补的）
        val cachedByPath: Map<String, CachedEntry> = runCatching {
            readCacheEntries(context, light = true).associateBy { it.song.id.removePrefix("f") }
        }.getOrDefault(emptyMap())

        val songs = mutableListOf<DemoSong>()
        val coverDataPaths = mutableListOf<String>() // 本次扫描落盘了封面的歌曲（收尾清扫孤儿封面时保留）
        val reused = mutableListOf<CachedEntry>()    // 复用缓存项：写回时保留其指纹
        val freshFingerprints = mutableMapOf<String, Pair<Long, Long>>() // 新提取条目的指纹
        // P2-19：显式中止标记——用「循环里确实 break 了」而非「此刻 isCancelled()」判定，
        // 避免扫描恰好自然跑完后调用方才置取消，把完整扫描误判成中止而漏掉 sweep
        var aborted = false
        folderCoverCache.clear() // 每次扫描重查文件夹封面（用户可能增删 cover.jpg）
        folderCoverBitmaps.clear() // 缩略位图只服务单次扫描，不跨扫描缓存（Palette 取色后即可释放）
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.RELATIVE_PATH,
            MediaStore.Audio.Media.DATE_ADDED,
            MediaStore.Audio.Media.DATE_MODIFIED,
            MediaStore.Audio.Media.SIZE,
            // ── 专辑模型（P0：同名不同艺术家的专辑被合成一张 / 专辑内曲序是字母序）──
            // 三列都在 MediaStore.MediaColumns 上声明（Audio.Media 继承自它），minSdk 35 恒可用：
            // ALBUM_ARTIST 自 API 30 起、DISC_NUMBER / YEAR 更早，无需 Build.VERSION 分支。
            // 但**取值**不可假设非空：MediaProvider 对不同容器/标签的填充率差异很大
            // （尤其 DISC_NUMBER，很多 MP3 根本不带 TPOS），一律按「空/NULL = 未知」处理，
            // 且用 getColumnIndex（非 OrThrow）+ 负索引守卫，个别 OEM provider 漏列时不至于整次扫描崩掉。
            MediaStore.Audio.Media.ALBUM_ARTIST,
            MediaStore.Audio.Media.DISC_NUMBER,
            MediaStore.Audio.Media.YEAR,
        )
        // 不带 selection：is_music/duration 在部分扫描路径下不可靠（adb 推送扫成 NULL），
        // 全量拉回外部音频后按 RELATIVE_PATH 分组——不限定 Music/，Download/QQ 等任意
        // 公共目录的音频都收；不想要的目录用设置页「过滤文件夹」排除
        context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection, null, null,
            "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC",
        )?.use { cursor ->
            val colId = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val colTitle = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val colArtist = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val colAlbum = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val colDuration = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val colData = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
            val colPath = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.RELATIVE_PATH)
            val colDateAdded = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
            val colDateModified = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_MODIFIED)
            val colSize = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
            // 专辑模型三列用 getColumnIndex（**非** OrThrow）：上面 projection 已显式请求，
            // 正常 provider 一定有；个别 ROM 的 MediaProvider 对 DISC_NUMBER 之类漏列时
            // 返回 -1，取值走「未知」默认值即可，不该让整次扫描抛异常失败
            val colAlbumArtist = cursor.getColumnIndex(MediaStore.Audio.Media.ALBUM_ARTIST)
            val colDiscNo = cursor.getColumnIndex(MediaStore.Audio.Media.DISC_NUMBER)
            val colYear = cursor.getColumnIndex(MediaStore.Audio.Media.YEAR)
            /** 负索引（该 provider 没有此列）安全取串：列缺失 = 未知，统一空串 */
            fun str(col: Int): String? = if (col >= 0) cursor.getString(col) else null
            fun num(col: Int): Int = if (col >= 0) cursor.getInt(col) else 0
            /**
             * 「可能是 "1" / "1/2" 形态的整数列」安全取值。
             *
             * **为什么不直接用 [num]**（本轮修复）：`MediaStore.Audio.Media.DISC_NUMBER`
             * 是 **TEXT** 列，MediaProvider 原样透传标签里的 TPOS（"1" 或 "1/2" 都可能）。
             * `cursor.getInt()` 对 "1/2" 会抛 NumberFormatException —— 而它在
             * `loadInternal` 的游标循环里，异常会一路冒到 `load()` 外层被吞成「扫描失败」，
             * 表现为**整库突然变空**；即便某些实现不抛而返回 0，多碟专辑的碟号也恒为 0，
             * `sortAlbumTracks` 的碟号优先排序随之失效。
             * 这里先按字符串取，再用与音轨号同一套「取斜杠前的整数」口径解析。
             */
            fun discNum(col: Int): Int =
                if (col >= 0) cursor.getString(col)?.let { parseLeadingInt(it) } ?: 0 else 0

            while (cursor.moveToNext()) {
                // P2-19 取消检查：命中即 break（不抛异常），保证下面的 use{} 正常关 cursor。
                // 中止时返回已组装的部分结果——lastScan 仍被赋值（见 load），但 aborted=true
                // 会让本次跳过 sweepCovers（孤儿清扫基于"全库"命名集，半截结果会把未扫到
                // 的歌的封面误当孤儿删掉）；调用方 GlassApp 拿到 partial 结果后也不写缓存
                if (isCancelled()) {
                    aborted = true
                    break
                }
                val relativePath = cursor.getString(colPath) ?: continue
                val id = cursor.getLong(colId)
                val dataPath = cursor.getString(colData) ?: continue
                val lastModified = cursor.getLong(colDateModified)
                val size = cursor.getLong(colSize)
                val fileName = dataPath.substringAfterLast('/')
                val title = cursor.getString(colTitle)?.takeIf { it.isNotBlank() }
                    ?: fileName.substringBeforeLast('.')
                val artist = cursor.getString(colArtist)?.takeIf { it.isNotBlank() && it != "<unknown>" }
                    ?: "未知艺术家"
                val album = cursor.getString(colAlbum)?.takeIf { it.isNotBlank() && it != "<unknown>" }
                    ?: "未知专辑"
                val cursorDurationMs = cursor.getLong(colDuration)

                // 增量命中：指纹（修改时间 + 大小）与缓存一致 → 直接复用整条记录（跳过
                // MediaMetadataRetriever + MediaExtractor 两次解码，这是大曲库慢的主因）
                val cached = cachedByPath[dataPath]
                if (cached != null && lastModified > 0L && size > 0L &&
                    cached.lastModifiedSec == lastModified && cached.size == size
                ) {
                    val c = cached.song
                    // B3：短音频不再在此拦截——指纹/缓存是全量语义，过滤统一移到
                    // load() 的输出组装层（若在此 continue，短音频进不了 reused →
                    // 指纹写回丢失，关掉过滤后每次扫描都要重新解码它）
                    // 封面还原：优先单曲内嵌封面缓存，其次文件夹兜底封面缓存（d<hash>.png）。
                    // 两者都要计入保留集，否则收尾清扫会误删
                    val coverFile = coverFileFor(context, dataPath)
                    val hasOwnCover = coverFile.isFile && coverFile.length() > 0L
                    // 目录兜底封面必须**校验源 cover.jpg 仍在且未变**：否则用户删图后仍显示旧图
                    // （B4「删掉后重扫应回退」），换图后也永远拿旧图
                    val folderCover = if (hasOwnCover) null else reusableFolderCover(context, dataPath)
                    val restoredCoverFile = if (hasOwnCover) coverFile else folderCover
                    // 两种「缓存与磁盘不一致」都要落回全量提取，不能就此复用旧记录：
                    // A. 缓存说**有**封面，但磁盘 PNG 不见了（系统/清理助手清 externalCacheDir，
                    //    或源 cover.jpg 被删）→ 否则全库封面永久消失
                    // B. 缓存说**没**封面（artworkUri == null），但现在探到了可用的封面文件
                    //    （用户刚往目录里放了 cover.jpg）→ 否则「放图后重扫」永远看不到图
                    //
                    // **A 必须用 c.hadCoverRecord 而不是 c.artworkUri != null**：后者是
                    // readCacheEntries 解析过文件存在性的结果，封面目录被清空后它已是 null，
                    // 判据永远不成立 → 误判「本来就没封面」→ 复用 + writeCache 把 null 写死
                    // → 封面永久丢失（真机实测：57 首全灭，只能删缓存重扫才恢复）。
                    val coverLost = cached.hadCoverRecord && restoredCoverFile == null
                    val coverGained = !cached.hadCoverRecord && restoredCoverFile != null
                    if (!coverLost && !coverGained) {
                        if (restoredCoverFile != null) coverDataPaths += dataPath
                        songs += c.copy(
                            // 播放地址用当次 MediaStore _id 重算（_id 可能漂移，路径才是稳定键）
                            mediaUri = ContentUris.withAppendedId(
                                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id,
                            ).toString(),
                            dateAddedSec = cursor.getLong(colDateAdded),
                            // ── 专辑模型三列从**本次 cursor** 覆盖，而不是沿用旧记录 ──
                            // 这是本改造最容易漏的一处：缓存命中走的是 `c.copy(...)`，
                            // 旧记录里 albumArtist/discNo/year 要么是空（上一版缓存没这几个键），
                            // 要么是上一轮扫描时的值。若不在复用路径覆盖，用户升级后
                            // **只要缓存还命中，这三列就永远是空的**，看起来就像「改了没生效」，
                            // 而且只有删缓存重扫才会好——正是最难排查的那类 bug。
                            // 这三列取自 cursor（纯 SQL 列，无解码成本），复用路径覆盖它们
                            // 不会破坏「增量扫描跳过解码」这个初衷。
                            albumArtist = str(colAlbumArtist)
                                ?.trim()
                                ?.takeIf { it.isNotEmpty() && it != "<unknown>" }
                                .orEmpty(),
                            discNo = discNum(colDiscNo),
                            year = num(colYear).takeIf { it in 1..9999 } ?: c.year,
                            // 能否播放沿用旧记录：它取决于 codec，而 codec 不在 cursor 里，
                            // 复用路径拿不到 MIME（拿它就要重跑 MediaExtractor，等于放弃增量）。
                            // 这是可接受的：格式不会因为换了个扫描轮次就变，除非用户换了文件，
                            // 而换文件会改变 mtime/size → 指纹不命中 → 走全量提取，届时自然重算
                            playable = c.playable,
                            // 不再解码位图（全库常驻数百 MB 的根因），只留 artworkUri：
                            // 由 ArtThumb 在真正要画时经 ArtworkBitmapCache 按需解码（LRU 24MB）
                            coverBitmap = null,
                            artworkUri = restoredCoverFile?.let { Uri.fromFile(it).toString() },
                            lyricLines = readLrc(context, dataPath),
                        )
                        reused += cached
                        continue
                    }
                    // coverLost / coverGained：不 continue，继续走下面的全量提取
                }

                val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
                val meta = loadMetadata(context, uri)
                val coverBitmap = meta.cover
                val retrieverDurationMs = meta.durationMs
                val format = meta.format
                // ── 专辑模型三列（审计 02 报告最致命缺口②）──
                // 取值时机放在这里（而不是文件开头的 cursor 段）是因为它紧邻 DemoSong 构造，
                // 读者能一眼看到「列索引 → 归一 → 落字段」这条链，不用来回跳。
                // 归一口径：空串 / "<unknown>" 都算「标签缺失」→ 空串，
                // 由 AppState.albumGroupKey 回退到 artist；不在这里直接填 artist，
                // 是因为「缺失」与「就等于曲目艺术家」在分组语义上必须可区分（合辑场景）。
                val albumArtist = str(colAlbumArtist)
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() && it != "<unknown>" }
                    .orEmpty()
                // 碟号：MediaStore 的 DISC_NUMBER 是 **TEXT** 列（可能为 "1" 或 "1/2"），
                // 必须走 discNum 的字符串解析 —— 用 getInt 对 "1/2" 会抛 NumberFormatException
                // 让整次扫描失败，或静默退化成 0（多碟专辑排序失效）。取不出来按 0 = 未知
                val discNo = discNum(colDiscNo)
                // 年份：优先用 MediaStore 的 YEAR 列，取不到（0）再回退标签里的年份。
                // 两条路都有值但不同时以 MediaStore 为准——它是文件系统侧的权威声明
                val year = num(colYear).takeIf { it in 1..9999 } ?: meta.tags.year
                val durationMs = if (cursorDurationMs > 1000) cursorDurationMs else (retrieverDurationMs ?: 0L)
                val durationSec = (durationMs / 1000).toInt().coerceAtLeast(1)

                // 分组键 = Music/ 之后的完整相对目录（Music/Pop/ → "Pop"，顶层文件 → "Music"），
                // 子目录不再被顶层名吞并
                val dir = relativePath.trimStart('/').trimEnd('/')
                val folder = when {
                    dir.startsWith("Music/", ignoreCase = true) -> dir.substring(6).ifEmpty { "Music" }
                    else -> dir.ifEmpty { "Music" }
                }

                // B3：短音频（通知音/录音碎片）不再在此 continue——照常入库+记指纹，
                // 只提取这一次（时长必须在元数据提取之后才有：cursor 时长常为 NULL，
                // retriever 才可靠）；是否展示给用户由 load() 输出层按 filterShort
                // 决定，开关切换零重解码

                val persistedUri = coverBitmap?.let { persistCover(context, dataPath, it, lastModified) }
                if (persistedUri != null) coverDataPaths += dataPath
                // 无内嵌封面时回退同目录 cover.jpg/folder.jpg/albumart.jpg（整专辑图常见形态）：
                // 按目录缓存一次，同目录其余无封面歌曲共享（不重复读盘）。
                // 兜底图也落盘到 covers/（键 = 目录路径哈希），这样用户删掉原图后缓存仍可用，
                // 且 artworkUri 与内嵌封面同一套寻址方式（通知栏/锁屏可直接取）。
                // **降采样**：目录封面常是 3000×3000 级扫描图，裸 decodeFile 单张约 34MB，
                // 同目录 N 首各解一次直接打穿堆 → 走 decodeSampledCover（长边 ≤256px + 目录去重）
                val effectiveCover = coverBitmap ?: folderCoverFor(context, dataPath)?.let { file ->
                    decodeSampledCover(file, dataPath)
                }
                val effectiveArtworkUri = persistedUri
                    ?: effectiveCover?.let { bmp -> persistFolderCover(context, dataPath, bmp) }
                // 文件夹兜底封面也计入保留集（否则收尾清扫会把它当孤儿删掉）
                if (persistedUri == null && effectiveArtworkUri != null) coverDataPaths += dataPath
                songs += DemoSong(
                    // 稳定 id = 文件绝对路径：MediaStore _id 会在重扫/数据库重建后漂移，
                    // 快照里的队列/收藏用它对位会错位甚至丢失；路径跨扫描稳定
                    id = "f$dataPath",
                    title = title,
                    artist = artist,
                    album = album,
                    durationSec = durationSec,
                    art = fallbackArt(songs.size),
                    folder = folder,
                    genre = meta.tags.genre,
                    year = year,
                    trackNo = meta.tags.trackNo,
                    albumArtist = albumArtist,
                    discNo = discNo,
                    // 能否播放：MIME 判得出来就用 MIME 的结论；MIME 都没取到（MediaExtractor
                    // 打不开该容器）时退回扩展名判定。注意 `format?.playable ?: isPlayablePath(...)`
                    // 的短路顺序——不能用 `?: true`，那等于把「没取到 MIME」一律乐观判可播，
                    // 而 .ape 这类文件的典型特征恰恰就是 MediaExtractor 也解不开
                    playable = format?.playable ?: isPlayablePath(dataPath),
                    mediaUri = uri.toString(),
                    // 位图不留驻（同上：改为按需解码）；palette 仍取一次缩略图算色
                    coverBitmap = null,
                    artworkUri = effectiveArtworkUri,
                    lyricLines = readLrc(context, dataPath),
                    codec = format?.codec ?: dataPath.substringAfterLast('.', "").uppercase(),
                    sampleRateHz = format?.sampleRateHz ?: 0,
                    bitDepth = format?.bitDepth ?: 0,
                    bitrateKbps = format?.bitrateKbps ?: 0,
                    palette = effectiveCover?.let { paletteFromBitmap(it) },
                    dateAddedSec = cursor.getLong(colDateAdded),
                )
                // 新提取条目的指纹（复用项不走这里，指纹沿用缓存）
                freshFingerprints[dataPath] = lastModified to size
            }
        }
        // P2-19：中途取消（半截结果）时**不 sweep**——sweepCovers 的保留集来自本次扫描，
        // 半截结果意味着大量未扫到的歌不在保留集里，跑了就是把好封面当孤儿批量误删。
        // 写缓存同理由调用方规避（GlassApp 拿 partial 结果不落盘）
        if (!aborted) {
            sweepCovers(context, coverDataPaths) // 全量扫描收尾：清掉孤儿封面（含旧 song<id>.png 命名，天然迁移）
        }
        // 指纹表随结果一并返回，writeCache 写回时使用（新扫条目用本次 cursor 值，
        // 复用条目沿用缓存指纹，保证下次扫描仍能命中）
        return ScanResult(
            songs,
            reused.associate { it.song.id.removePrefix("f") to (it.lastModifiedSec to it.size) } + freshFingerprints,
            aborted,
        )
    }

    /**
     * 扫描结果 + 各条目指纹（dataPath → lastModified/size），writeCache 依此写回。
     * [aborted]（P2-19）：true = 扫描被取消通道中止的半截结果——调用方据此**不应写缓存**
     * （writeCache 默认路径已内置防线，显式传指纹的调用方自行检查此标志）。
     */
    data class ScanResult(
        val songs: List<DemoSong>,
        val fingerprints: Map<String, Pair<Long, Long>>,
        val aborted: Boolean = false,
    )

    /**
     * 封面缓存文件（单一事实源）：covers 目录下按 dataPath 稳定摘要命名——
     * 旧键 song<MediaStore _id> 会随重扫/数据库重建漂移且从不清理（无限增长）；
     * persistCover 落盘与 loadCache 读缓存共用此命名，全量扫描收尾按它清扫孤儿。
     *
     * 命名键从 32 位 String.hashCode 换成 [stableHash]（SHA-256 前 8 字节）的原因：
     * hashCode 只有 32 位，而来源是**文件绝对路径**——1 万首约 1% 概率出现两条不同路径
     * 碰撞成同一个 "c"+hash（生日问题，n²/2^33）。碰撞不是「少一张图」，而是**串图**：
     * 后落盘者覆写前一文件，两首歌永久共用同一张 PNG（缓存里 artworkUri 指向同一路径，
     * 谁也不会自愈），且冲突对是确定的、用户永远修不好。
     *
     * 迁移链（旧命名 → 新命名，全部靠现有分支自愈，无需版本号/一次性迁移代码）：
     * 1. 读缓存：[readCacheEntries] 优先按新名找文件；找不到时**回落到缓存里记录的旧
     *    artworkUri**（旧名 PNG 此时通常还在）。这一步不只是「冷启动别变灰」的观感问题——
     *    它同时是第 2 步的**触发条件**：回落让 `c.artworkUri != null`，而新名文件不存在，
     *    于是 coverLost 成立。若此处不回落，缓存条目会以 artworkUri=null 被判定为
     *    「本来就没封面」（coverLost/coverGained 都不成立）直接复用，随后 writeCache 把
     *    空 uri 写回缓存——封面就**永久**丢了，再也不会自愈。
     * 2. 增量命中判断：旧文件在新名下不存在 → restoredCoverFile == null 且缓存记录有封面
     *    → coverLost 成立 → 该条落回全量提取分支，重新取内嵌/目录封面并按**新名**落盘。
     * 3. 收尾清扫：[sweepCovers] 的保留集只含新名，旧名 PNG 一律当孤儿删除（不占空间）。
     * 4. 下一次 writeCache 把 artworkUri 写成新名，之后彻底稳定。
     * 代价：升级后第一次全量扫描要为「有封面」的歌各重提取一次（与一次冷装扫描同量级）；
     * 之后的增量扫描照旧命中缓存，不再重解码——一次性迁移，换来缓存键不再碰撞。
     * 注意 directory 兜底封面（d 前缀）同理：reusableFolderCover 也只认新名，
     * 旧名目录图会被判为「缓存不存在」→ 落全量分支重提取 → 同样经 sweepCovers 清掉。
     */
    fun coverFileFor(context: Context, dataPath: String): java.io.File =
        java.io.File(
            java.io.File(context.externalCacheDir, "covers"),
            "c" + stableHash(dataPath) + ".png",
        )

    /**
     * 封面键迁移的兼容读取（见 [coverFileFor] 迁移链第 1 步）：按缓存里记录的 artworkUri
     * 取回旧命名文件。只接受 `file://` 且确实存在于盘上的路径——记录可能是空串、
     * 指向已被清掉的 externalCacheDir，或是历史遗留的异常 uri；这些一律当「无封面」。
     */
    private fun legacyArtworkFile(recorded: String): java.io.File? {
        if (recorded.isEmpty()) return null
        val path = runCatching { Uri.parse(recorded) }.getOrNull()
            ?.takeIf { it.scheme == "file" }?.path ?: return null
        return java.io.File(path).takeIf { it.isFile && it.length() > 0L }
    }

    /**
     * 稳定摘要（16 位小写 hex = SHA-256 的前 8 字节）：封面缓存文件名的键。
     *
     * 为什么不用 String.hashCode：32 位、且来源是**文件绝对路径**（一万首 ≈ 1% 生日碰撞），
     * 碰撞会导致两首歌的封面文件同名 → 后写者覆写前者 → 两首歌永久显示同一张图且无法自愈。
     * SHA-256 前 8 字节 = 64 位，碰撞概率降到可忽略；截断到 8 字节是为了文件名短、目录列表快。
     *
     * 不用 `"%02x".format()`：那走默认 locale（阿拉伯语/波斯语区域会输出非 ASCII 数字字形，
     * 名字里出现非法字符）。手写查表既不依赖 locale，也没有 formatter 的分配开销。
     * 注意：本函数结果**参与文件名**，改动它等于让所有缓存失效——不要再改算法。
     */
    private fun stableHash(s: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(s.toByteArray(Charsets.UTF_8))
        val out = StringBuilder(16)
        for (i in 0 until 8) {
            val b = digest[i].toInt() and 0xFF
            out.append(HEX_CHARS[b ushr 4]).append(HEX_CHARS[b and 0x0F])
        }
        return out.toString()
    }

    /**
     * 文件夹封面兜底：同目录下 cover.jpg / folder.jpg / albumart.jpg（常见整专辑图形态）。
     * 整次扫描内按目录缓存（同目录几十首歌只查一次盘）；无则 null。
     * 依赖 MANAGE_EXTERNAL_STORAGE（已具备）才能直读任意公共目录。
     * 注：缓存以目录为键、文件为值，**每次扫描开始时清空**——用户可能新放/删除 cover.jpg，
     * 跨扫描复用会拿到陈旧值（例如删了图仍显示旧封面）。
     */
    private val folderCoverCache = mutableMapOf<String, java.io.File?>()

    private fun folderCoverFileFor(context: Context, dataPath: String): java.io.File? {
        val dir = dataPath.substringBeforeLast('/', "")
        if (dir.isEmpty()) return null
        return folderCoverCache.getOrPut(dir) {
            FOLDER_COVER_NAMES.asSequence()
                .map { java.io.File(dir, it) }
                .firstOrNull { it.isFile && it.length() > 0L }
        }
    }

    /** 同 [folderCoverFileFor]；语义化别名供扫描主体表达「取该目录的兜底封面文件」 */
    private fun folderCoverFor(context: Context, dataPath: String): java.io.File? =
        folderCoverFileFor(context, dataPath)

    private val FOLDER_COVER_NAMES = listOf("cover.jpg", "folder.jpg", "albumart.jpg", "Cover.jpg", "Folder.jpg")

    /** 文件夹封面落盘文件名：以「目录」而非单曲为键（同目录共享一份，清扫时按目录保留）；
     *  键同样走 [stableHash]（目录路径而非单曲路径）——理由见 [coverFileFor] 的迁移链说明。
     *  默认值 "" 必须保留：无目录分隔符的 dataPath 归到「空目录」键，与旧命名一一对应 */
    private fun folderCoverKey(dataPath: String): String = "d" + stableHash(
        dataPath.substringBeforeLast('/', ""),
    ) + ".png"

    /** 已落盘的文件夹兜底封面文件（存在且非空才返回） */
    private fun folderCoverCacheFile(context: Context, dataPath: String): java.io.File? {
        val file = java.io.File(java.io.File(context.externalCacheDir, "covers"), folderCoverKey(dataPath))
        return file.takeIf { it.isFile && it.length() > 0L }
    }

    /**
     * 增量复用分支专用：取「仍然有效」的目录兜底封面缓存。
     *
     * 旧实现（只查 d&lt;hash&gt;.png 是否存在）在三处会给出错结果：
     * 1. 用户**删掉** cover.jpg → 缓存 PNG 还在，永远显示旧图（B4「删掉后重扫应回退」必失败）
     * 2. 用户**换掉** cover.jpg → persistFolderCover 见文件存在即跳过，永远旧图
     * 3. 系统清 externalCacheDir → PNG 没了，旧实现只把封面置空，不触发重提取（封面永久消失）
     *
     * 判新鲜度用**磁盘 mtime 比较**而不是内存里的指纹表——指纹表每次扫描都会清空，
     * 目录内第一首歌必然「无历史可比」，换图后首扫仍会误用旧 PNG；mtime 是跨进程持久的。
     * 判定：源图不存在 → 删缓存回退；缓存不存在 → 落全量分支重提取；
     * 源图比缓存新 → 视为换图，删缓存重提取；否则缓存仍有效。
     */
    private fun reusableFolderCover(context: Context, dataPath: String): java.io.File? {
        val dir = dataPath.substringBeforeLast('/', "")
        if (dir.isEmpty()) return null
        val source = folderCoverFileFor(context, dataPath)
        val cache = folderCoverCacheFile(context, dataPath)
        if (source == null) {
            // 源图不在了：清掉陈旧缓存 PNG，让「删图后重扫」真正回退到无封面
            cache?.delete()
            return null
        }
        if (!source.isFile || source.length() <= 0L) return null
        if (cache == null) return null // 缓存被系统清理：落全量分支重新提取（自愈）
        if (source.lastModified() > cache.lastModified()) {
            // 源图比缓存新 → 用户换过封面，删旧图重提取
            cache.delete()
            return null
        }
        return cache
    }

    /** 降采样解码缓存：dataPath（目录级去重）→ 缩略位图，避免同目录每首歌重复解一张大图 */
    private val folderCoverBitmaps = mutableMapOf<String, androidx.compose.ui.graphics.ImageBitmap?>()

    /** 长边上限：全库缩略图最大显示约 80dp（≈240px @3x），256 留余量 */
    private const val COVER_MAX_EDGE_PX = 256

    /**
     * 降采样解码目录封面：长边压到 [COVER_MAX_EDGE_PX]，并按**目录**去重。
     *
     * 旧实现是 `BitmapFactory.decodeFile(path)` 裸解：3000×3000 扫描封面单张 ≈ 34MB，
     * 同目录 N 首无内嵌封面的歌各解一次（folderCoverCache 只缓存 File 不缓存 Bitmap），
     * N=15 即 515MB，直接打穿默认堆（Manifest 无 largeHeap）——这是项目里最容易 OOM 的点。
     */
    private fun decodeSampledCover(
        file: java.io.File,
        dataPath: String,
    ): androidx.compose.ui.graphics.ImageBitmap? {
        val key = dataPath.substringBeforeLast('/', "")
        folderCoverBitmaps[key]?.let { return it }
        val decoded = runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= COVER_MAX_EDGE_PX ||
                bounds.outHeight / (sample * 2) >= COVER_MAX_EDGE_PX
            ) {
                sample *= 2
            }
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            BitmapFactory.decodeFile(file.absolutePath, opts)?.asImageBitmap()
        }.getOrNull()
        folderCoverBitmaps[key] = decoded
        return decoded
    }

    /** 文件夹封面落盘（键 = 目录哈希）：与内嵌封面同目录、同寻址方式 */
    private fun persistFolderCover(
        context: Context,
        dataPath: String,
        bitmap: androidx.compose.ui.graphics.ImageBitmap,
    ): String? = try {
        val file = java.io.File(java.io.File(context.externalCacheDir, "covers"), folderCoverKey(dataPath))
        file.parentFile?.mkdirs()
        // **总是覆写**：旧实现「已存在即跳过」会让换 cover.jpg 后永远显示旧图。
        // 调用方（reusableFolderCover）已先用源指纹判定新鲜度，能走到这里就是需要重写。
        java.io.FileOutputStream(file).use { out ->
            bitmap.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 90, out)
        }
        Uri.fromFile(file).toString()
    } catch (e: Exception) {
        // 落盘失败只影响「通知栏/锁屏封面 + 缓存复用」，不该中断整次扫描（降级为无封面）；
        // 但必须留痕：磁盘满 / externalCacheDir 不可写时，用户看到的正是「封面时有时无」这种
        // 找不到原因的怪状，没日志就只能靠猜
        Log.e(TAG, "persistFolderCover failed: ${folderCoverKey(dataPath)}", e)
        null
    }

    /**
     * 封面落盘为 PNG（cache/covers/）：媒体通知 / 锁屏 / 流体云需要可寻址的 artworkUri；失败返回 null（空串也是无效 uri）。
     *
     * P3-31 写放大治理：能证明「源音频没变」时跳过覆写——扫描走到这里通常意味着
     * 元数据被重新解码了，但重新解码 ≠ 封面变了（增量缓存 miss 的原因多是 mtime/size
     * 之外的指纹细节）。判定用源音频 DATE_MODIFIED（MediaStore 秒级）与 PNG 落盘
     * mtime（毫秒→秒）比对：一致即认为封面与上次相同，直接返回现有文件。
     * 源 mtime 不可信（≤0）或 PNG 不存在/为空时照旧覆写——**正确性优先于写放大**：
     * 宁可多写一次，不冒「内嵌封面换图后通知栏永远旧图」的险（那是当初改成总是覆写的原因）。
     */
    private fun persistCover(
        context: Context,
        dataPath: String,
        bitmap: androidx.compose.ui.graphics.ImageBitmap,
        sourceLastModifiedSec: Long = 0L,
    ): String? {
        return try {
            val file = coverFileFor(context, dataPath)
            // 差异跳过：PNG 在、非空、且与源音频 mtime 同秒 → 封面没变，不重写
            if (sourceLastModifiedSec > 0L && file.isFile && file.length() > 0L &&
                file.lastModified() / 1000L == sourceLastModifiedSec
            ) {
                return Uri.fromFile(file).toString()
            }
            file.parentFile?.mkdirs()
            // **总是覆写**：旧实现「已存在即跳过」→ 内嵌封面换图后通知栏和重启后的
            // in-app 都永远显示旧图（缓存键稳定哈希后不再自愈）。扫描走到这里说明本次
            // 确实重新提取了内嵌封面，写盘就该以本次结果为准（能证明没变时上面已提前返回）。
            java.io.FileOutputStream(file).use { out ->
                bitmap.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 90, out)
            }
            // P3-31 盖戳：上面跳过分支比的是 PNG 的 mtime，而刚写完的 PNG mtime = 写入时刻，
            // 与源音频 mtime 天然不等——不把 PNG mtime 显式对齐到源 mtime，跳过分支永远
            // 不命中，「差异跳过」形同虚设。盖戳后比对语义变为「这张 PNG 属于 mtime=X 的
            // 那一版音频」：下次扫到同一版本（X 未变）即跳过，换图/改文件（mtime 变）则照常覆写。
            // setLastModified 失败（个别 FS 不支持）只退化为每次多写一次，无正确性影响，静默即可
            if (sourceLastModifiedSec > 0L) {
                runCatching { file.setLastModified(sourceLastModifiedSec * 1000L) }
            }
            Uri.fromFile(file).toString()
        } catch (e: Exception) {
            // 同 persistFolderCover：降级为无封面，但留痕（失败原因几乎总是磁盘/权限）
            Log.e(TAG, "persistCover failed: ${coverNameOrDash(context, dataPath)}", e)
            null
        }
    }

    /** persistCover 的日志辅助：文件名算不出来也不能抛（日志路径不允许二次失败） */
    private fun coverNameOrDash(context: Context, dataPath: String): String =
        runCatching { coverFileFor(context, dataPath).name }.getOrDefault("-")

    /** 清扫 covers 目录孤儿：删除不在本次曲库命名集内的文件；externalCacheDir 为 null 等异常静默跳过（与 persistCover 防御一致） */
    private fun sweepCovers(context: Context, keepDataPaths: List<String>) {
        val covers = context.externalCacheDir?.let { java.io.File(it, "covers") } ?: return
        // 保留集 = 单曲内嵌封面文件名 + 文件夹兜底封面文件名（d<hash>，按目录去重）
        val keep = buildSet {
            keepDataPaths.forEach { add(coverFileFor(context, it).name) }
            keepDataPaths.map { folderCoverKey(it) }.forEach { add(it) }
        }
        runCatching {
            covers.listFiles()?.forEach { file ->
                if (file.isFile && file.name !in keep) file.delete()
            }
        }
    }

    /** 扫描期提取的音频格式参数（MediaExtractor 权威值，取不到为 0/null） */
    data class AudioFileInfo(
        val codec: String,
        val sampleRateHz: Int,
        val bitDepth: Int,
        val bitrateKbps: Int,
        /** 本机引擎能否播放该容器的 MIME（判定口径见 [isPlayableMime]）；null = 没取到 MIME */
        val playable: Boolean? = null,
    )

    private val mimeToCodec = mapOf(
        "audio/flac" to "FLAC", "audio/x-flac" to "FLAC",
        "audio/mpeg" to "MP3", "audio/mp4a-latm" to "AAC", "audio/aac" to "AAC",
        "audio/x-ms-wma" to "WMA", "audio/ape" to "APE", "audio/x-ape" to "APE",
        "audio/wav" to "WAV", "audio/x-wav" to "WAV", "audio/alac" to "ALAC",
        "audio/ogg" to "OGG", "audio/opus" to "OPUS", "audio/amr" to "AMR",
        "audio/midi" to "MIDI",
    )

    /**
     * 本机播放引擎**明确解不了**的容器 MIME（黑名单，口径见 [isPlayableMime]）。
     *
     * 与 [mimeToCodec] 的分工是这件事的关键：
     * - [mimeToCodec] 回答「这是什么格式」（**展示用**，含 APE/WMA 这类引擎解不了的）
     * - 本集合回答「本机明确不能播」（**能力用**）
     *
     * ## 为什么第一版用白名单、真机回归后改成了黑名单（决策变更记录）
     *
     * 第一版的理由是「白名单漏一个只会多标一次不支持，代价小」——**这个推理漏了
     * 一个真实的设备事实**：Android 平台 MediaExtractor 对 FLAC 常报 `audio/raw`
     * （PCM 解封装输出而非 `audio/flac`），WAV 也常报 `audio/raw`。白名单没有
     * `audio/raw` → **整个无损曲库被误标为「当前设备不支持播放」**。真机（API 36
     * 模拟器）实测：全部 FLAC/WAV 条目 playable=false，而这些歌实际都能正常播放
     * （dumpsys media_session 显示 PLAYING）。信息面板的错误标注比「不标注」更糟
     * ——那正是审计 14a 批评的「说了做不到」的镜像：「做得到却说做不到」。
     *
     * 黑名单的取舍反过来了：冷门格式漏标一次「能播」→ 用户点播放得到引擎的错误
     * 横幅（引擎自己会说「音频格式不支持播放」，还有坏文件自动跳过兜底），代价是
     * 一次点击；而白名单错杀是**整类格式永久带错误角标**，代价是每天看一眼。
     * 引擎侧已有错误处理与跳过链路兜底，黑名单的失败模式是可控的。
     */
    private val unsupportedMime = setOf(
        // Media3 开箱（无 FFmpeg 扩展）确定解不了的容器/编码。
        // APE(Wavpack 系 Monkey's Audio)、WMA、WavPack、DSD、TAK、TTA、MPC
        // 均不在 AOSP MediaExtractor 的解析能力内。
        "audio/ape", "audio/x-ape", "audio/x-ms-wma", "audio/wavpack", "audio/x-wavpack",
        "audio/dsf", "audio/dff", "audio/x-dsd", "audio/tak", "audio/tta", "audio/musepack",
        "audio/mpc", "audio/aiff", "audio/x-aiff", "audio/x-ape-file",
    )

    /** 引擎明确**不支持**的扩展名（无 MIME 时的兜底判据，也是给 UI 的明确说法来源） */
    private val unsupportedExtensions = setOf(
        "APE", "WMA", "WV", "DSF", "DFF", "TAK", "TTA", "MPC", "AIFF", "AIFFC",
    )

    /**
     * 判定该 MIME 对应的格式在本机能否播放。
     *
     * **为什么真机回归后从白名单改为黑名单**：见 [unsupportedMime] 的注释。
     * 一句话总结——白名单在真实设备上会把 FLAC/WAV 的 `audio/raw` MIME 错杀，
     * 把「能播的整类格式」标成「不支持」；黑名单的失败模式（冷门格式漏标）由
     * 引擎侧错误横幅 + 坏文件自动跳过兜底，代价小一个数量级。
     */
    private fun isPlayableMime(mime: String): Boolean = mime.lowercase() !in unsupportedMime

    /**
     * 无 MIME（MediaExtractor 打不开该容器）时按扩展名兜底判定能否播放。
     *
     * 走这条路说明 MediaExtractor 已经解不开这个文件了 —— 但**不能据此直接判不支持**：
     * MediaExtractor 对某些能播的容器（个别 WAV/AIFF 变体）也会失败，那种情况下
     * 平台解码器仍可能正常播放。所以这里只对「扩展名明确属于已知不支持集合」的判 false，
     * 余下一律 true（乐观），避免把能播的文件误标成不能播。
     */
    private fun isPlayablePath(dataPath: String): Boolean {
        val ext = dataPath.substringAfterLast('.', "").uppercase()
        return ext !in unsupportedExtensions
    }

    /** MIME → 编码名（播放校准与扫描共用） */
    fun codecFromMime(mime: String): String =
        mimeToCodec[mime] ?: mime.substringAfter('/').uppercase().ifEmpty { "音频" }

    private fun pcmEncodingToBitDepth(encoding: Int): Int = when (encoding) {
        android.media.AudioFormat.ENCODING_PCM_8BIT -> 8
        android.media.AudioFormat.ENCODING_PCM_16BIT -> 16
        android.media.AudioFormat.ENCODING_PCM_24BIT_PACKED -> 24
        android.media.AudioFormat.ENCODING_PCM_32BIT, android.media.AudioFormat.ENCODING_PCM_FLOAT -> 32
        else -> 0
    }

    /** 扫描期从标签取到的元数据（取不到为 null/0） */
    data class TagInfo(val genre: String, val year: Int, val trackNo: Int)

    /** 内嵌封面缩略图 + 时长兜底 + 音频格式参数（MediaStore duration 常为 NULL，一次打开全取） */
    private fun loadMetadata(
        context: Context,
        uri: Uri,
    ): MetadataResult {
        val retriever = MediaMetadataRetriever()
        var coverAndDuration: Pair<androidx.compose.ui.graphics.ImageBitmap?, Long?> = null to null
        var tags = TagInfo("", 0, 0)
        try {
            retriever.setDataSource(context, uri)
            coverAndDuration = retriever.embeddedPicture?.let { bytes ->
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= 160) sample *= 2
                val opts = BitmapFactory.Options().apply { inSampleSize = sample }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)?.asImageBitmap()
            } to retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            // 流派/年份/音轨号：MediaMetadataRetriever 通吃 MediaStore 与 SAF 两通道
            tags = TagInfo(
                genre = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE)
                    ?.trim()?.let { normalizeGenre(it) }.orEmpty(),
                year = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_YEAR)
                    ?.trim()?.toIntOrNull()?.takeIf { it in 1..9999 } ?: 0,
                // 音轨号：标签常见 "3" / "3/12" / "03"，与碟号共用同一个解析口径
                trackNo = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)
                    ?.let { parseLeadingInt(it) } ?: 0,
            )
        } catch (e: Exception) {
            // 标签读取失败不影响封面/时长/格式：各自已有降级。留痕是为了区分
            // 「文件本来就没标签」与「这台的 MediaMetadataRetriever 打不开该容器」
            Log.w(TAG, "tag read failed: $uri", e)
        } finally {
            try { retriever.release() } catch (e: Exception) {}
        }
        val bitmap = coverAndDuration

        val format = try {
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(context, uri, null)
                val trackFormat = (0 until extractor.trackCount)
                    .map { extractor.getTrackFormat(it) }
                    .firstOrNull { it.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                trackFormat?.let { f ->
                    fun intOf(key: String): Int = runCatching { f.getInteger(key) }.getOrDefault(0)
                    val mime = f.getString(MediaFormat.KEY_MIME).orEmpty()
                    AudioFileInfo(
                        codec = mimeToCodec[mime]
                            ?: mime.substringAfter('/').uppercase().ifEmpty { "音频" },
                        sampleRateHz = intOf(MediaFormat.KEY_SAMPLE_RATE),
                        // 能否播放按 **MIME** 判定（不是 codec 名）：codec 名是展示用的，
                        // 而「引擎解不解得开」是 Media3 按 sampleMimeType 路由 renderer 的，
                        // 所以判据必须和被判定对象同源，否则会出现「显示 APE 被判成可播」这类错位
                        playable = isPlayableMime(mime),
                        bitDepth = if (intOf(MediaFormat.KEY_PCM_ENCODING) != 0) {
                            pcmEncodingToBitDepth(intOf(MediaFormat.KEY_PCM_ENCODING))
                        } else {
                            intOf("bits-per-sample") // 部分解码器以自定义键携带源位深
                        },
                        bitrateKbps = intOf(MediaFormat.KEY_BIT_RATE).let { if (it > 0) it / 1000 else 0 },
                    )
                }
            } finally {
                runCatching { extractor.release() } // 损坏文件抛异常也不能泄漏 native 资源
            }
        } catch (e: Exception) {
            // 格式提取失败（损坏文件 / 不支持的容器）：降级为「参数未知」（codec 回退扩展名、
            // 其余 0），不影响入库。但这是用户「采样率/位深不显示」的唯一线索，按 warn 留痕——
            // 只对真正取不到格式的文件报一条，正常曲库为 0，量级可控
            Log.w(TAG, "format extract failed: $uri", e)
            null
        }

        return MetadataResult(bitmap.first, bitmap.second, format, tags)
    }

    /** loadMetadata 一次打开取回的全部结果（封面 / 时长兜底 / 格式 / 标签） */
    data class MetadataResult(
        val cover: androidx.compose.ui.graphics.ImageBitmap?,
        val durationMs: Long?,
        val format: AudioFileInfo?,
        val tags: TagInfo,
    )

    /**
     * 流派归一：ID3v1 数字流派（"(17)" / "17"）映射为名称，其余原样保留。
     * 部分标签同时带数字与名称（如 "(17)Rock"），优先取括号后的名称文本。
     */
    private fun normalizeGenre(raw: String): String {
        val text = raw.trim()
        if (text.isEmpty()) return ""
        // "(17)Rock" / "(17)" / "17" 三种形态
        val m = Regex("^\\((\\d{1,3})\\)(.*)$").find(text)
        if (m != null) {
            val name = m.groupValues[2].trim()
            if (name.isNotEmpty()) return name
            val n = m.groupValues[1].toIntOrNull() ?: return ""
            return ID3_GENRES.getOrNull(n) ?: ""
        }
        val pure = text.toIntOrNull()
        if (pure != null) return ID3_GENRES.getOrNull(pure) ?: ""
        return text
    }

    /**
     * 「可能带 "/总数" 后缀的整数文本」归一：取斜杠前的整数，非正数或解析失败一律 0（= 未知）。
     *
     * 覆盖两条完全不同的来路，所以抽成一个口径：
     * - 音轨号来自 MediaMetadataRetriever 的 `METADATA_KEY_CD_TRACK_NUMBER`（"3" / "3/12"）；
     * - 碟号来自 **cursor 的 TEXT 列** `DISC_NUMBER`（同样是 "1" / "1/2"）。
     * 两处输入形态一致、语义一致（0 = 未知，见 DemoSong.discNo 的「0 排末尾」约定），
     * 各写一份只会在调整规则时改一处漏一处。
     */
    private fun parseLeadingInt(raw: String): Int =
        raw.trim().substringBefore('/').trim().toIntOrNull()?.takeIf { it > 0 } ?: 0

    /** 标准 ID3v1 流派表（0..79；后续 Winamp 扩展略去——超出范围回退空串，UI 归一「未知流派」） */
    private val ID3_GENRES = listOf(
        "Blues", "Classic Rock", "Country", "Dance", "Disco", "Funk", "Grunge", "Hip-Hop",
        "Jazz", "Metal", "New Age", "Oldies", "Other", "Pop", "R&B", "Rap",
        "Reggae", "Rock", "Techno", "Industrial", "Alternative", "Ska", "Death Metal", "Pranks",
        "Soundtrack", "Euro-Techno", "Ambient", "Trip-Hop", "Vocal", "Jazz+Funk", "Fusion", "Trance",
        "Classical", "Instrumental", "Acid", "House", "Game", "Sound Clip", "Gospel", "Noise",
        "AlternRock", "Bass", "Soul", "Punk", "Space", "Meditative", "Instrumental Pop", "Instrumental Rock",
        "Ethnic", "Gothic", "Darkwave", "Techno-Industrial", "Electronic", "Pop-Folk", "Eurodance", "Dream",
        "Southern Rock", "Comedy", "Cult", "Gangsta", "Top 40", "Christian Rap", "Pop/Funk", "Jungle",
        "Native American", "Cabaret", "New Wave", "Psychadelic", "Rave", "Showtunes", "Trailer", "Lo-Fi",
        "Tribal", "Acid Punk", "Acid Jazz", "Polka", "Retro", "Musical", "Rock & Roll", "Hard Rock",
    )

    /**
     * 同名 .lrc 歌词（P2-20）：音频同目录 sibling **优先**，回退应用专属外部目录
     * files/lrc/<音频文件名>.lrc（scoped storage 下跨应用读 .lrc 会被拦，
     * adb 可直推该目录、免任何权限，作兜底）。
     * 返回 null = 两个候选都不存在；候选存在但无有效时间戳行时降级为纯文本
     * 单行歌词（见 [parseLrcOrPlainText]），不再与「无文件」混同。
     *
     * 桌面歌词（P0-8）**刻意不复用本方法**：本文件已封板（历史任务锁定，改可见性也是改），
     * 悬浮窗侧的 [com.local.music.lyrics.DesktopLyricsLoader] 平移了同一套候选路径逻辑，
     * 并复用 public 的 [parseLrc] 保证两处解析行为逐比特一致——见其类注释的「同步清单」。
     */
    private fun readLrc(context: Context, audioPath: String): List<LyricLine>? {
        // 注意：文件名取 basename 后再剥扩展名——不能对**整条路径**用 substringBeforeLast('.')，
        // 否则「无扩展名文件名 + 含点目录」会算到目录的那个点上：
        //   /a.b/song（无扩展名）→ 整路径法得 /a.lrc（错），basename 法得 /a.b/song.lrc（对）。
        // 更糟的是错误路径可能命中同目录另一首歌的歌词，变成错误关联而非缺失。
        val fileName = audioPath.substringAfterLast('/').substringBeforeLast('.')
        val sibling = audioPath.substringBeforeLast('/') + "/" + fileName + ".lrc"
        // P2-20：同目录 sibling 优先，files/lrc 兜底（顺序反了的话，私有目录里的旧文件
        // 会永远压过音频同目录的正确歌词——用户删/改同目录 lrc 后重扫无效）。
        // files/lrc 存在的理由：scoped storage 下跨应用读 .lrc 会被拦，adb 推歌词到
        // 应用专属目录免权限，作为同目录读不到时的兜底通道
        val candidates = listOfNotNull(
            java.io.File(sibling),
            context.getExternalFilesDir(null)?.let { java.io.File(it, "lrc/$fileName.lrc") },
        )
        // 逐候选"读取+解析"，一个候选存在但损坏/为空时继续尝试下一个。
        // P3-30：先看大小再读——超大 .lrc（几百 MB 的误下载/二进制同名文件）readText
        // 会整读进堆并逐行跑正则，卡死扫描线程；超限视同该候选不存在
        return candidates.asSequence()
            .filter { it.isFile }
            .filter { it.length() <= LRC_MAX_BYTES }
            .firstNotNullOfOrNull { file ->
                runCatching { parseLrcOrPlainText(file).ifEmpty { null } }.getOrNull()
            }
    }

    /** P3-30：.lrc 读取上限（512 KiB）——正常歌词远小于此，超限的基本是误命中的大文件 */
    private const val LRC_MAX_BYTES = 512 * 1024

    /**
     * P3-29：读 .lrc 的三态封装（文件不存在 → null；有时间戳 → 解析结果；
     * 文件在但**无任何有效时间戳行** → 纯文本降级）。
     *
     * 为什么需要第三态：旧链路 parseLrc 丢弃无时间戳行 → 空列表 → readLrc 把
     * 「有文件但解析为空」与「无文件」混同返回 null → UI 拿别的歌的演示歌词兜底。
     * 纯文本 .lrc（简谱附词/漏写时间戳）至少应该显示这首歌自己的文字。
     * 文本截断到 2000 字符：整文件作为单行歌词渲染会撑爆布局/纹理。
     */
    private fun parseLrcOrPlainText(file: java.io.File): List<LyricLine> {
        val text = file.readText()
        val parsed = parseLrc(text)
        if (parsed.isNotEmpty()) return parsed
        val plain = text.trim()
        if (plain.isEmpty()) return emptyList()
        return listOf(LyricLine(plain.take(2000), 0f))
    }

    private val lrcRegex = Regex("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?\\]")
    private val lrcOffsetRegex = Regex("\\[offset:\\s*([+-]?\\d+)\\s*\\]", RegexOption.IGNORE_CASE)
    private val lrcEnhancedRegex = Regex("<\\d{1,3}:\\d{1,2}(?:[.:]\\d{1,3})?>") // 增强歌词行内时间戳

    /**
     * 标准 LRC：[mm:ss.xx]（可多时间戳）；忽略 [ti:][ar:] 等标签行；剥离增强标签。
     *
     * P3-28 两趟解析：[offset:] 是**文件级**标签，旧实现逐行即时扣减有两个 bug——
     * ① 写在文件尾的 [offset:] 对前面已解析的行不生效（不回溯）；
     * ② 某行同时含时间戳与 [offset:] 时，时间戳分支先命中，offset 被静默忽略。
     * 修法：第一趟在**全文**级别取最后一次出现的 [offset:]（同一文件写多个时以最后
     * 一个为准），第二趟逐行只解析时间戳，最后统一减去 offset。
     */
    fun parseLrc(text: String): List<LyricLine> {
        // 第一趟：全文扫 offset（lastOrNull：约定以文件中最后一次声明为准）
        val offsetMs = lrcOffsetRegex.findAll(text).lastOrNull()
            ?.groupValues[1]?.toIntOrNull() ?: 0
        val out = mutableListOf<LyricLine>()
        text.lineSequence().forEach { rawLine ->
            val line = rawLine.trim().removePrefix("﻿") // UTF-8 BOM
            val stamps = lrcRegex.findAll(line).toList()
            if (stamps.isEmpty()) return@forEach // 标签行/纯文本行：时间戳解析阶段一律跳过（纯文本降级见 parseLrcOrPlainText）
            // P3-28 收尾：行内 [offset:] 文本也要从 content 剥离——第二趟 stamps-first 意味着
            // 「时间戳与 offset 同行」时 offset 文本会混进歌词展示内容；offset 本体已由第一趟
            // 全文扫描统一生效，这里只清理展示文本
            val content = line.substring(stamps.last().range.last + 1)
                .replace(lrcEnhancedRegex, "")
                .replace(lrcOffsetRegex, "")
                .trim()
            if (content.isEmpty()) return@forEach
            stamps.forEach { match ->
                val (mm, ss, frac) = match.destructured
                // offset 取负：主流 LRC 约定正 offset 让歌词提前显示（此处对全文统一扣减）
                val sec = mm.toInt() * 60 + ss.toInt() + (frac.padEnd(3, '0').take(3).toInt() / 1000f) -
                    offsetMs / 1000f
                out += LyricLine(content, sec)
            }
        }
        return out.sortedBy { it.timeSec }.distinct()
    }

    /**
     * 封面取色：从位图提**一个** seed 色，派生整套 AuroraPalette 一律走 [AuroraPalette.fromSeed]。
     *
     * 视觉审计 A3 改造前这里有第三套公式（seed2 = s×0.7/v×1.25、seed3 = s×0.45/v×1.5、
     * deep = s×0.85/v×0.38、deep2 = s×0.8/v×0.13），与 fromSeed 的系数完全不同：
     * 同一个色相「手选」和「封面恰好取到」会得到两种深浅，切歌时氛围在两套公式间跳，
     * 取色 400ms 交叉渐变掩盖不了这种色相/明度偏移。现在只剩这一个派生源。
     *
     * 本函数因此只负责采样的**前两步**：
     * 1. 用 Palette API 挑 swatch（vibrant → muted → dominant 依次降级）；
     * 2. 把 swatch 的代表色 `swatch.rgb` 原样当作 seed 交给 fromSeed。
     * 不再做「中性化/防脏色」的手工 HSV 缩放——那部分逻辑已经上移到 fromSeed 的彩度门控里
     * （中性 seed 不注入假饱和，纯灰封面得到纯灰极光底），在这里重复一遍只会再次分叉两套公式。
     *
     * **线程与性能**：保持纯 CPU、无位图拷贝（`asAndroidBitmap()` 只是把 Compose 的
     * [androidx.compose.ui.graphics.ImageBitmap] 包一层 Android 视图，不复制像素）、无 IO；
     * Palette 量化本就要遍历缩略图，与改造前同一量级（改造只减少了一次 HSV 派生，
     * 不再额外分配 float 数组）。调用方在解码/扫描线程调它，fromSeed 是纯函数，
     * 不需要主线程，也没有 @Composable/Context 依赖。
     */
    fun paletteFromBitmap(bitmap: androidx.compose.ui.graphics.ImageBitmap): AuroraPalette {
        val swatch = Palette.from(bitmap.asAndroidBitmap()).maximumColorCount(24).generate().let { p ->
            p.vibrantSwatch ?: p.mutedSwatch ?: p.dominantSwatch
        } ?: return GlassPalettes.Purple
        // 无封面/取色失败时回落品牌靛紫（与 fromSeed 无关，是「没有 seed」的兜底语义）
        return AuroraPalette.fromSeed(Color(swatch.rgb))
    }

    /** 无封面歌曲的占位风格：按序循环 6 种演示封面 */
    private fun fallbackArt(index: Int): ArtStyle =
        ArtStyle.entries[index % FALLBACK_ART_COUNT]

    // ── 曲库缓存：启动即时真实化 ──────────────────────────
    // （真机 56 首全量重扫 = 每首 MediaMetadataRetriever + MediaExtractor，十几秒级；
    //   缓存让冷启动 <1s 呈现恢复场景，随后的全量重扫在后台对账并重写缓存）

    private const val CACHE_FILE = "library_cache.json"
    private const val CACHE_VERSION = 3

    /**
     * 读曲库缓存：无/损坏/版本不符返回 null（自愈：随后的全量重扫会重写）。
     *
     * [filterShort]：与 [load] 同一套输出层过滤语义（B3）。冷启动读缓存时若不过滤，
     * 默认开着过滤的库在重扫完成前会把短音频（通知音/录音碎片）先放出来闪一下——
     * 缓存刻意全量保存（见 [writeCache]），所以过滤必须在**输出**这一层补。
     */
    fun loadCache(context: Context, filterShort: Boolean = true): List<DemoSong>? = runCatching {
        readCacheEntries(context).takeIf { it.isNotEmpty() }?.let { entries ->
            outputFiltered(entries.map { it.song }, filterShort)
        }
    }.getOrNull()

    /**
     * 读缓存条目（含指纹）：增量扫描据此判断哪些条目可复用。
     * 无/损坏/版本不符返回空列表（自愈：随后扫描全量重写）。
     *
     * [light] = true 时不解码封面位图、不读 .lrc——增量扫描只需要指纹与曲目字段，
     * 复用条目的封面/歌词在真正命中后再按需补齐（见 [loadInternal] 的复用分支），
     * 否则「读一遍缓存」的 I/O 就吃掉增量扫描的全部收益。
     */
    private fun readCacheEntries(context: Context, light: Boolean = false): List<CachedEntry> = runCatching {
        val file = java.io.File(context.filesDir, CACHE_FILE)
        if (!file.isFile) return@runCatching emptyList()
        val root = org.json.JSONObject(file.readText())
        if (root.optInt("v") != CACHE_VERSION) return@runCatching emptyList()
        val arr = root.optJSONArray("songs") ?: return@runCatching emptyList()
        val entries = mutableListOf<CachedEntry>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val dataPath = o.optString("dataPath")
            // 封面统一按 dataPath 重导出（缓存存的就是 dataPath，不依赖落盘时的旧文件名）；
            // 无 dataPath 的条目沿用缓存里的 artworkUri。
            // 迁移兼容（见 [coverFileFor] 的迁移链第 1 步）：新命名文件不存在时，回落到缓存里
            // 记录的 artworkUri——那多半是旧命名 c<hash>/d<hash>.png 且此刻还在盘上，
            // 冷启动（此处）因此不会因为换了键而首屏全灰。是否**真的**要重提取由增量扫描
            // 的 coverLost 判断（它只看新命名文件），所以回落到旧文件不会掩盖迁移。
            val rawArtworkUri = o.optString("artworkUri")
            val artworkUri = if (dataPath.isEmpty()) {
                rawArtworkUri.ifEmpty { null }
            } else {
                val cover = coverFileFor(context, dataPath).takeIf { it.isFile && it.length() > 0L }
                    ?: legacyArtworkFile(rawArtworkUri)
                cover?.let { Uri.fromFile(it).toString() }
            }
            val song = DemoSong(
                id = o.getString("id"),
                title = o.getString("title"),
                artist = o.getString("artist"),
                album = o.getString("album"),
                durationSec = o.getInt("durationSec"),
                art = ArtStyle.entries.getOrElse(o.optInt("art", 0)) { ArtStyle.Night },
                folder = o.optString("folder", "Music"),
                // 空串 = 未知流派（v3 起语义；v2 缓存里该字段缺省也走空串，与 DemoSong 默认一致）
                genre = o.optString("genre", ""),
                year = o.optInt("year", 0),
                trackNo = o.optInt("trackNo", 0),
                // 旧缓存（v3 之前）没有这三个键：opt* 默认值 = 空串/0/true，
                // 正好落到「标签缺失 + 可播放」的安全默认，与 DemoSong 字段默认值一致。
                // playable 默认 **true** 而不是 false：老缓存条目大多是真能播的常规格式，
                // 默认 false 会把升级后首屏所有歌全标成「不支持」，那才是事故
                albumArtist = o.optString("albumArtist", ""),
                discNo = o.optInt("discNo", 0),
                playable = o.optBoolean("playable", true),
                mediaUri = o.optString("mediaUri").ifEmpty { null },
                coverBitmap = null, // 按需解码（ArtworkBitmapCache）：全库常驻数百 MB 的根因
                artworkUri = artworkUri,
                lyricLines = if (light || dataPath.isEmpty()) null else readLrc(context, dataPath),
                codec = o.optString("codec"),
                sampleRateHz = o.optInt("sampleRateHz"),
                bitDepth = o.optInt("bitDepth"),
                bitrateKbps = o.optInt("bitrateKbps"),
                palette = o.optJSONArray("palette")?.takeIf { it.length() == 5 }?.let { p ->
                    AuroraPalette(
                        seed = Color(p.optInt(0)),
                        seed2 = Color(p.optInt(1)),
                        seed3 = Color(p.optInt(2)),
                        deep = Color(p.optInt(3)),
                        deep2 = Color(p.optInt(4)),
                    )
                },
                dateAddedSec = o.optLong("dateAdded"),
            )
            entries += CachedEntry(
                song = song,
                lastModifiedSec = o.optLong("lastModified", 0L),
                size = o.optLong("size", 0L),
                // 用**原始 JSON 字段**判定「曾经记过封面」，不用解析后的 artworkUri：
                // 后者在封面文件被清理后已成 null，会把「曾有过」误判成「从来没有」
                hadCoverRecord = rawArtworkUri.isNotEmpty(),
            )
        }
        entries
    }.getOrDefault(emptyList())

    /** 扫描成功后写缓存（IO 线程调用）；失败静默（下次重扫再写）。
     *  指纹取自 [fingerprints] 参数或单例里的 `lastScan`（内部字段，无公开读取入口）：
     *  新扫条目用本次 cursor 值、
     *  复用条目沿用旧指纹，保证下一次增量扫描仍能命中（否则退化成每次全量重扫）。
     *  P3-32：[fingerprints] 显式传入时可与 songs **同源**——旧实现固定读单例 lastScan，
     *  两次扫描交错（扫描 A 刚写完 lastScan，扫描 B 才调 writeCache）会把 A 的指纹
     *  配到 B 的 songs 上，错位后下次增量永远 miss。默认 null 保持旧行为，既有调用点不改也编译；
     *  GlassApp 拿到 load() 返回的 ScanResult 后可传 result.fingerprints。
     *  B3 缓存全量：调用方（GlassApp）传入的是 load() **输出层过滤后**的列表，
     *  被滤掉的短音频须从本次全量扫描结果（scan.songs）补回再落盘——
     *  否则缓存文件缺了它们的条目与指纹，关掉过滤后的下一次扫描只能对短音频
     *  重新跑 MediaExtractor/MediaMetadataRetriever（正是两层分离要消除的浪费）。 */
    fun writeCache(
        context: Context,
        songs: List<DemoSong>,
        fingerprints: Map<String, Pair<Long, Long>>? = null,
    ) {
        val scan = lastScan
        // P2-19 防线：扫描被取消通道中止时 break 而非抛异常，调用方的 runCatching
        // 分流不出「中止」与「成功」，会把半截 songs 当成功写进缓存（覆盖掉好缓存，
        // 且半截 songs 配全量指纹会让下次增量把未扫到的歌判为「指纹一致」永久复用旧记录）。
        // 默认路径（未显式传 fingerprints）在此拦截；显式传指纹视为「同源调用方已自查
        // ScanResult.aborted」，不再代拦。lastScan 为 null（从未扫过）时不拦——songs 本身
        // 就是调用方现成数据，无「半截」可言
        if (fingerprints == null && scan?.aborted == true) return
        val fps = fingerprints ?: scan?.fingerprints.orEmpty()
        val presentIds = songs.asSequence().map { it.id }.toHashSet()
        val allSongs = if (scan != null) songs + scan.songs.filter { it.id !in presentIds } else songs
        runCatching {
            val arr = org.json.JSONArray()
            allSongs.forEach { s ->
                // P3-33：id 体系是 "f" + 绝对路径；非 f 前缀（如演示/导入的合成条目）
                // removePrefix 后得空串 dataPath，写进缓存会变成无路径幽灵条目，
                // 还可能覆盖读侧对 dataPath 的判空分支——直接不入缓存
                if (!s.id.startsWith("f")) return@forEach
                arr.put(org.json.JSONObject().apply {
                    put("id", s.id)
                    put("title", s.title)
                    put("artist", s.artist)
                    put("album", s.album)
                    put("durationSec", s.durationSec)
                    put("art", s.art.ordinal)
                    put("folder", s.folder)
                    put("genre", s.genre)
                    put("year", s.year)
                    put("trackNo", s.trackNo)
                    // ── 专辑模型三列（v3 缓存不带这些键 → 读侧 opt* 带默认值兜底）──
                    // 不升 CACHE_VERSION 是刻意的：升版会让**所有**老用户首次启动强制全量重扫
                    // （万首库分钟级），代价远大于收益。这三个键都是「缺省即可用」的纯增量字段，
                    // 读侧默认值 + 下一次增量扫描的复用路径覆盖（见 load() 里 c.copy 那段的注释）
                    // 已经能让老缓存平滑收敛到新值，不需要整库作废。
                    put("albumArtist", s.albumArtist)
                    put("discNo", s.discNo)
                    put("playable", s.playable)
                    put("mediaUri", s.mediaUri ?: "")
                    put("dataPath", s.id.removePrefix("f")) // 稳定 id = "f" + 文件绝对路径
                    put("artworkUri", s.artworkUri ?: "")
                    put("codec", s.codec)
                    put("sampleRateHz", s.sampleRateHz)
                    put("bitDepth", s.bitDepth)
                    put("bitrateKbps", s.bitrateKbps)
                    put("dateAdded", s.dateAddedSec)
                    // 增量扫描指纹（v3）：缺省 0 → 下次视为不命中，该条重扫（自愈）
                    val fp = fps[s.id.removePrefix("f")]
                    put("lastModified", fp?.first ?: 0L)
                    put("size", fp?.second ?: 0L)
                    s.palette?.let { p ->
                        put("palette", org.json.JSONArray().apply {
                            put(p.seed.toArgb())
                            put(p.seed2.toArgb())
                            put(p.seed3.toArgb())
                            put(p.deep.toArgb())
                            put(p.deep2.toArgb())
                        })
                    }
                })
            }
            java.io.File(context.filesDir, CACHE_FILE).writeText(
                org.json.JSONObject().put("v", CACHE_VERSION).put("songs", arr).toString(),
            )
        }.onFailure {
            // 写缓存失败不阻断本次扫描（本进程内曲库照常可用，下次重扫再写）。
            // 但这是「每次冷启动都要重扫十几秒」的直接原因（磁盘满 / filesDir 不可写），
            // 静默吞掉会让性能问题完全无法归因，故留痕
            Log.e(TAG, "writeCache failed (${songs.size} songs)", it)
        }
    }
}
