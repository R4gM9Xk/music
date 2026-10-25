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
     */
    fun load(context: Context, filterShort: Boolean = true): List<DemoSong> {
        // 互斥：已有扫描在跑就等它，拿它的结果（避免两路并发写同一缓存文件）
        if (!scanning.compareAndSet(false, true)) {
            synchronized(scanLock) {
                while (scanning.get()) {
                    runCatching { scanLock.wait(200L) }
                }
            }
            // 重入方拿到的是上次全量结果，同样只做输出层过滤（按本次开关）
            return lastScan?.songs?.let { outputFiltered(it, filterShort) } ?: emptyList()
        }
        try {
            val result = loadInternal(context)
            lastScan = result
            return outputFiltered(result.songs, filterShort)
        } finally {
            scanning.set(false)
            synchronized(scanLock) { scanLock.notifyAll() }
        }
    }

    /** B3 输出层过滤：短于 [MIN_TRACK_SEC] 的条目不进返回列表；指纹与缓存不受影响（见 [load]） */
    private fun outputFiltered(songs: List<DemoSong>, filterShort: Boolean): List<DemoSong> =
        if (filterShort) songs.filter { it.durationSec >= MIN_TRACK_SEC } else songs

    /** 最近一次扫描结果（含指纹）：writeCache 需要它写回指纹表 */
    fun lastScanResult(): ScanResult? = lastScan

    /** 缓存指纹：扫描前先按 dataPath 建索引，命中且指纹一致则复用整条记录 */
    private data class CachedEntry(val song: DemoSong, val lastModifiedSec: Long, val size: Long)

    /** 短音频阈值（B3）：小于该秒数视为通知音/录音碎片，默认过滤掉 */
    private const val MIN_TRACK_SEC = 30

    private fun loadInternal(context: Context): ScanResult {
        // 先读旧缓存建指纹索引（v3 才有 lastModified/size；旧缓存缺省 0 视为不命中 → 该条重扫）。
        // light 模式：只读 JSON 字段，不解码封面、不读歌词（那是命中后才需要补的）
        val cachedByPath: Map<String, CachedEntry> = runCatching {
            readCacheEntries(context, light = true).associateBy { it.song.id.removePrefix("f") }
        }.getOrDefault(emptyMap())

        val songs = mutableListOf<DemoSong>()
        val coverDataPaths = mutableListOf<String>() // 本次扫描落盘了封面的歌曲（收尾清扫孤儿封面时保留）
        val reused = mutableListOf<CachedEntry>()    // 复用缓存项：写回时保留其指纹
        val freshFingerprints = mutableMapOf<String, Pair<Long, Long>>() // 新提取条目的指纹
        folderCoverCache.clear() // 每次扫描重查文件夹封面（用户可能增删 cover.jpg）
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

            while (cursor.moveToNext()) {
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
                    val folderCover = if (hasOwnCover) null else folderCoverCacheFile(context, dataPath)
                    val restoredCoverFile = if (hasOwnCover) coverFile else folderCover
                    if (restoredCoverFile != null) coverDataPaths += dataPath
                    songs += c.copy(
                        // 播放地址用当次 MediaStore _id 重算（_id 可能漂移，路径才是稳定键）
                        mediaUri = ContentUris.withAppendedId(
                            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id,
                        ).toString(),
                        dateAddedSec = cursor.getLong(colDateAdded),
                        // light 模式读缓存时跳过的重资源，命中后再补：封面位图按落盘文件解码
                        // （不重新解码音频内嵌图，仍是增量语义）、歌词读同名 .lrc
                        coverBitmap = restoredCoverFile?.let {
                            android.graphics.BitmapFactory.decodeFile(it.absolutePath)?.asImageBitmap()
                        },
                        artworkUri = restoredCoverFile?.let { Uri.fromFile(it).toString() },
                        lyricLines = readLrc(context, dataPath),
                    )
                    reused += cached
                    continue
                }

                val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
                val meta = loadMetadata(context, uri)
                val coverBitmap = meta.cover
                val retrieverDurationMs = meta.durationMs
                val format = meta.format
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

                val persistedUri = coverBitmap?.let { persistCover(context, dataPath, it) }
                if (persistedUri != null) coverDataPaths += dataPath
                // 无内嵌封面时回退同目录 cover.jpg/folder.jpg/albumart.jpg（整专辑图常见形态）：
                // 按目录缓存一次，同目录其余无封面歌曲共享（不重复读盘）。
                // 兜底图也落盘到 covers/（键 = 目录路径哈希），这样用户删掉原图后缓存仍可用，
                // 且 artworkUri 与内嵌封面同一套寻址方式（通知栏/锁屏可直接取）。
                val effectiveCover = coverBitmap ?: folderCoverFor(context, dataPath)?.let { file ->
                    android.graphics.BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap()
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
                    year = meta.tags.year,
                    trackNo = meta.tags.trackNo,
                    mediaUri = uri.toString(),
                    coverBitmap = effectiveCover,
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
        sweepCovers(context, coverDataPaths) // 全量扫描收尾：清掉孤儿封面（含旧 song<id>.png 命名，天然迁移）
        // 指纹表随结果一并返回，writeCache 写回时使用（新扫条目用本次 cursor 值，
        // 复用条目沿用缓存指纹，保证下次扫描仍能命中）
        return ScanResult(songs, reused.associate { it.song.id.removePrefix("f") to (it.lastModifiedSec to it.size) } + freshFingerprints)
    }

    /** 扫描结果 + 各条目指纹（dataPath → lastModified/size），writeCache 依此写回 */
    data class ScanResult(
        val songs: List<DemoSong>,
        val fingerprints: Map<String, Pair<Long, Long>>,
    )

    /**
     * 封面缓存文件（单一事实源）：covers 目录下按 dataPath 稳定哈希命名——
     * 旧键 song<MediaStore _id> 会随重扫/数据库重建漂移且从不清理（无限增长）；
     * persistCover 落盘与 loadCache 读缓存共用此命名，全量扫描收尾按它清扫孤儿。
     */
    fun coverFileFor(context: Context, dataPath: String): java.io.File =
        java.io.File(
            java.io.File(context.externalCacheDir, "covers"),
            "c" + Integer.toHexString(dataPath.hashCode()) + ".png",
        )

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

    /** 文件夹封面落盘文件名：以「目录」而非单曲为键（同目录共享一份，清扫时按目录保留） */
    private fun folderCoverKey(dataPath: String): String = "d" + Integer.toHexString(
        dataPath.substringBeforeLast('/', "").hashCode(),
    ) + ".png"

    /** 已落盘的文件夹兜底封面文件（存在且非空才返回） */
    private fun folderCoverCacheFile(context: Context, dataPath: String): java.io.File? {
        val file = java.io.File(java.io.File(context.externalCacheDir, "covers"), folderCoverKey(dataPath))
        return file.takeIf { it.isFile && it.length() > 0L }
    }

    /** 文件夹封面落盘（键 = 目录哈希）：与内嵌封面同目录、同寻址方式 */
    private fun persistFolderCover(
        context: Context,
        dataPath: String,
        bitmap: androidx.compose.ui.graphics.ImageBitmap,
    ): String? = try {
        val file = java.io.File(java.io.File(context.externalCacheDir, "covers"), folderCoverKey(dataPath))
        file.parentFile?.mkdirs()
        if (!file.exists() || file.length() == 0L) {
            java.io.FileOutputStream(file).use { out ->
                bitmap.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 90, out)
            }
        }
        Uri.fromFile(file).toString()
    } catch (e: Exception) {
        null
    }

    /** 封面落盘为 PNG（cache/covers/）：媒体通知 / 锁屏 / 流体云需要可寻址的 artworkUri；失败返回 null（空串也是无效 uri） */
    private fun persistCover(context: Context, dataPath: String, bitmap: androidx.compose.ui.graphics.ImageBitmap): String? {
        return try {
            val file = coverFileFor(context, dataPath)
            file.parentFile?.mkdirs()
            if (!file.exists() || file.length() == 0L) {
                java.io.FileOutputStream(file).use { out ->
                    bitmap.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 90, out)
                }
            }
            Uri.fromFile(file).toString()
        } catch (e: Exception) {
            null
        }
    }

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
    )

    private val mimeToCodec = mapOf(
        "audio/flac" to "FLAC", "audio/x-flac" to "FLAC",
        "audio/mpeg" to "MP3", "audio/mp4a-latm" to "AAC", "audio/aac" to "AAC",
        "audio/x-ms-wma" to "WMA", "audio/ape" to "APE", "audio/x-ape" to "APE",
        "audio/wav" to "WAV", "audio/x-wav" to "WAV", "audio/alac" to "ALAC",
        "audio/ogg" to "OGG", "audio/opus" to "OPUS", "audio/amr" to "AMR",
        "audio/midi" to "MIDI",
    )

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
                trackNo = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)
                    ?.let { parseTrackNo(it) } ?: 0,
            )
        } catch (e: Exception) {
            // 标签读取失败不影响封面/时长/格式：各自已有降级
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

    /** 音轨号：标签常见 "3" / "3/12" / "03"，取斜杠前的整数 */
    private fun parseTrackNo(raw: String): Int =
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
     * 同名 .lrc 歌词：优先读应用专属外部目录 files/lrc/<音频文件名>.lrc
     * （adb 可直推、免任何权限；scoped storage 下跨应用读 .lrc 会被拦），
     * 回退音频同目录同名 .lrc（部分 ROM 可读）。都取不到则回退演示歌词。
     */
    private fun readLrc(context: Context, audioPath: String): List<LyricLine>? {
        val fileName = audioPath.substringAfterLast('/').substringBeforeLast('.')
        val candidates = listOfNotNull(
            context.getExternalFilesDir(null)?.let { java.io.File(it, "lrc/$fileName.lrc") },
            java.io.File(audioPath.substringBeforeLast('.') + ".lrc"),
        )
        // 逐候选"读取+解析"，一个候选存在但损坏/为空时继续尝试下一个
        return candidates.asSequence()
            .filter { it.isFile }
            .firstNotNullOfOrNull { file ->
                runCatching { parseLrc(file.readText()).ifEmpty { null } }.getOrNull()
            }
    }

    private val lrcRegex = Regex("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?\\]")
    private val lrcOffsetRegex = Regex("\\[offset:\\s*([+-]?\\d+)\\s*\\]", RegexOption.IGNORE_CASE)
    private val lrcEnhancedRegex = Regex("<\\d{1,3}:\\d{1,2}(?:[.:]\\d{1,3})?>") // 增强歌词行内时间戳

    /** 标准 LRC：[mm:ss.xx]（可多时间戳）；忽略 [ti:][ar:][offset:] 等标签行，offset 参与全局偏移，剥离增强标签 */
    fun parseLrc(text: String): List<LyricLine> {
        var offsetMs = 0
        val out = mutableListOf<LyricLine>()
        text.lineSequence().forEach { rawLine ->
            val line = rawLine.trim().removePrefix("﻿") // UTF-8 BOM
            val stamps = lrcRegex.findAll(line).toList()
            if (stamps.isEmpty()) {
                lrcOffsetRegex.find(line)?.let { offsetMs = it.groupValues[1].toIntOrNull() ?: 0 }
                return@forEach
            }
            val content = line.substring(stamps.last().range.last + 1)
                .replace(lrcEnhancedRegex, "")
                .trim()
            if (content.isEmpty()) return@forEach
            stamps.forEach { match ->
                val (mm, ss, frac) = match.destructured
                // offset 取负：主流 LRC 约定正 offset 让歌词提前显示
                val sec = mm.toInt() * 60 + ss.toInt() + (frac.padEnd(3, '0').take(3).toInt() / 1000f) -
                    offsetMs / 1000f
                out += LyricLine(content, sec)
            }
        }
        return out.sortedBy { it.timeSec }.distinct()
    }

    /** 封面取色：Palette 主色 → HSV 派生整套 AuroraPalette */
    fun paletteFromBitmap(bitmap: androidx.compose.ui.graphics.ImageBitmap): AuroraPalette {
        val swatch = Palette.from(bitmap.asAndroidBitmap()).maximumColorCount(24).generate().let { p ->
            p.vibrantSwatch ?: p.mutedSwatch ?: p.dominantSwatch
        } ?: return GlassPalettes.Purple
        val rgb = swatch.rgb
        val hsv = FloatArray(3)
        android.graphics.Color.RGBToHSV(
            android.graphics.Color.red(rgb), android.graphics.Color.green(rgb), android.graphics.Color.blue(rgb), hsv,
        )
        fun color(sMul: Float, vMul: Float): androidx.compose.ui.graphics.Color {
            val s = (hsv[1] * sMul).coerceIn(0f, 1f)
            val v = (hsv[2] * vMul).coerceIn(0f, 1f)
            return androidx.compose.ui.graphics.Color(android.graphics.Color.HSVToColor(floatArrayOf(hsv[0], s, v)))
        }
        return AuroraPalette(
            seed = color(1f, 1f),
            seed2 = color(0.7f, 1.25f),
            seed3 = color(0.45f, 1.5f),
            deep = color(0.85f, 0.38f),
            deep2 = color(0.8f, 0.13f),
        )
    }

    /** 无封面歌曲的占位风格：按序循环 6 种演示封面 */
    private fun fallbackArt(index: Int): ArtStyle =
        ArtStyle.entries[index % FALLBACK_ART_COUNT]

    // ── 曲库缓存：启动即时真实化 ──────────────────────────
    // （真机 56 首全量重扫 = 每首 MediaMetadataRetriever + MediaExtractor，十几秒级；
    //   缓存让冷启动 <1s 呈现恢复场景，随后的全量重扫在后台对账并重写缓存）

    private const val CACHE_FILE = "library_cache.json"
    private const val CACHE_VERSION = 3

    /** 读曲库缓存：无/损坏/版本不符返回 null（自愈：随后的全量重扫会重写） */
    fun loadCache(context: Context): List<DemoSong>? = runCatching {
        readCacheEntries(context).takeIf { it.isNotEmpty() }?.map { it.song }
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
            // 无 dataPath 的条目沿用缓存里的 artworkUri
            val artworkUri = if (dataPath.isEmpty()) {
                o.optString("artworkUri").ifEmpty { null }
            } else {
                coverFileFor(context, dataPath)
                    .takeIf { it.isFile && it.length() > 0L }?.let { Uri.fromFile(it).toString() }
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
                mediaUri = o.optString("mediaUri").ifEmpty { null },
                coverBitmap = if (light) null else artworkUri?.let { uri ->
                    android.graphics.BitmapFactory.decodeFile(uri.removePrefix("file://"))?.asImageBitmap()
                },
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
            )
        }
        entries
    }.getOrDefault(emptyList())

    /** 扫描成功后写缓存（IO 线程调用）；失败静默（下次重扫再写）。
     *  指纹取自 [lastScanResult]：新扫条目用本次 cursor 值、复用条目沿用旧指纹，
     *  保证下一次增量扫描仍能命中（否则退化成每次全量重扫）。
     *  B3 缓存全量：调用方（GlassApp）传入的是 load() **输出层过滤后**的列表，
     *  被滤掉的短音频须从本次全量扫描结果（lastScan.songs）补回再落盘——
     *  否则缓存文件缺了它们的条目与指纹，关掉过滤后的下一次扫描只能对短音频
     *  重新跑 MediaExtractor/MediaMetadataRetriever（正是两层分离要消除的浪费）。 */
    fun writeCache(context: Context, songs: List<DemoSong>) {
        val scan = lastScan
        val fingerprints = scan?.fingerprints.orEmpty()
        val presentIds = songs.asSequence().map { it.id }.toHashSet()
        val allSongs = if (scan != null) songs + scan.songs.filter { it.id !in presentIds } else songs
        runCatching {
            val arr = org.json.JSONArray()
            allSongs.forEach { s ->
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
                    put("mediaUri", s.mediaUri ?: "")
                    put("dataPath", s.id.removePrefix("f")) // 稳定 id = "f" + 文件绝对路径
                    put("artworkUri", s.artworkUri ?: "")
                    put("codec", s.codec)
                    put("sampleRateHz", s.sampleRateHz)
                    put("bitDepth", s.bitDepth)
                    put("bitrateKbps", s.bitrateKbps)
                    put("dateAdded", s.dateAddedSec)
                    // 增量扫描指纹（v3）：缺省 0 → 下次视为不命中，该条重扫（自愈）
                    val fp = fingerprints[s.id.removePrefix("f")]
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
        }
    }
}
