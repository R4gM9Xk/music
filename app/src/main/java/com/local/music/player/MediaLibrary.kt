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
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
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

    fun load(context: Context): List<DemoSong> {
        val songs = mutableListOf<DemoSong>()
        android.util.Log.d(TAG, "scan start")
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.RELATIVE_PATH,
        )
        // 不带 selection：is_music/duration 在部分扫描路径下不可靠（adb 推送扫成 NULL），
        // 全量拉回后按 RELATIVE_PATH 在代码内过滤
        context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection, null, null,
            "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC",
        )?.use { cursor ->
            android.util.Log.d(TAG, "query rows=${cursor.count}")
            val colId = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val colTitle = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val colArtist = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val colAlbum = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val colDuration = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val colData = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
            val colPath = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.RELATIVE_PATH)

            while (cursor.moveToNext()) {
                val relativePath = cursor.getString(colPath) ?: continue
                if (!relativePath.trimStart('/').startsWith("Music/", ignoreCase = true)) continue
                android.util.Log.d(TAG, "hit: $relativePath ${cursor.getString(colTitle)}")

                val id = cursor.getLong(colId)
                val dataPath = cursor.getString(colData) ?: continue
                val fileName = dataPath.substringAfterLast('/')
                val title = cursor.getString(colTitle)?.takeIf { it.isNotBlank() }
                    ?: fileName.substringBeforeLast('.')
                val artist = cursor.getString(colArtist)?.takeIf { it.isNotBlank() && it != "<unknown>" }
                    ?: "未知艺术家"
                val album = cursor.getString(colAlbum)?.takeIf { it.isNotBlank() && it != "<unknown>" }
                    ?: "未知专辑"
                val cursorDurationMs = cursor.getLong(colDuration)

                val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
                val (coverBitmap, retrieverDurationMs, format) = loadMetadata(context, uri)
                val durationMs = if (cursorDurationMs > 1000) cursorDurationMs else (retrieverDurationMs ?: 0L)
                val durationSec = (durationMs / 1000).toInt().coerceAtLeast(1)

                // 分组键 = Music/ 之后的完整相对目录（Music/Pop/ → "Pop"，顶层文件 → "Music"），
                // 子目录不再被顶层名吞并
                val dir = relativePath.trimStart('/').trimEnd('/')
                val folder = when {
                    dir.startsWith("Music/", ignoreCase = true) -> dir.substring(6).ifEmpty { "Music" }
                    else -> dir.ifEmpty { "Music" }
                }

                songs += DemoSong(
                    id = "m$id",
                    title = title,
                    artist = artist,
                    album = album,
                    durationSec = durationSec,
                    art = fallbackArt(songs.size),
                    folder = folder,
                    mediaUri = uri.toString(),
                    coverBitmap = coverBitmap,
                    artworkUri = coverBitmap?.let { persistCover(context, "song$id", it) },
                    lyricLines = readLrc(context, dataPath),
                    codec = format?.codec ?: dataPath.substringAfterLast('.', "").uppercase(),
                    sampleRateHz = format?.sampleRateHz ?: 0,
                    bitDepth = format?.bitDepth ?: 0,
                    bitrateKbps = format?.bitrateKbps ?: 0,
                    palette = coverBitmap?.let { paletteFromBitmap(it) },
                )
            }
        }
        android.util.Log.d(TAG, "scan done, loaded=${songs.size}")
        return songs
    }

    private const val TAG = "MediaLibrary"

    /** 封面落盘为 PNG（cache/covers/）：媒体通知 / 锁屏 / 流体云需要可寻址的 artworkUri */
    private fun persistCover(context: Context, name: String, bitmap: androidx.compose.ui.graphics.ImageBitmap): String {
        return try {
            val dir = java.io.File(context.externalCacheDir, "covers").apply { mkdirs() }
            val file = java.io.File(dir, "$name.png")
            if (!file.exists()) {
                java.io.FileOutputStream(file).use { out ->
                    bitmap.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 90, out)
                }
            }
            Uri.fromFile(file).toString()
        } catch (e: Exception) {
            ""
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

    /** 内嵌封面缩略图 + 时长兜底 + 音频格式参数（MediaStore duration 常为 NULL，一次打开全取） */
    private fun loadMetadata(
        context: Context,
        uri: Uri,
    ): Triple<androidx.compose.ui.graphics.ImageBitmap?, Long?, AudioFileInfo?> {
        val retriever = MediaMetadataRetriever()
        val bitmap = try {
            retriever.setDataSource(context, uri)
            retriever.embeddedPicture?.let { bytes ->
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= 160) sample *= 2
                val opts = BitmapFactory.Options().apply { inSampleSize = sample }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)?.asImageBitmap()
            } to retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        } catch (e: Exception) {
            null to null
        } finally {
            try { retriever.release() } catch (e: Exception) {}
        }

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

        return Triple(bitmap.first, bitmap.second, format)
    }

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
                val sec = mm.toInt() * 60 + ss.toInt() + (frac.padEnd(3, '0').take(3).toInt() / 1000f) +
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
}
