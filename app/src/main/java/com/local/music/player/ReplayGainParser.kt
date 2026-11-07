package com.local.music.player

import java.io.EOFException
import java.io.InputStream
import java.io.PushbackInputStream

/**
 * ReplayGain / R128 响度标签读取器（纯 JVM，无 Android 依赖 → 可单测）。
 *
 * ## 为什么不能只用 MediaMetadataRetriever
 * `MediaMetadataRetriever` 只暴露 `METADATA_KEY_*` 这套**标准** key（标题/艺术家/专辑…），
 * `REPLAYGAIN_TRACK_GAIN` **不在其中**——它不是 Android metadata key，而是写在各家容器
 * 自己的注释区里的**应用私有标签**：
 * - MP3/AAC：ID3v2 的 `TXXX` 帧（description = `REPLAYGAIN_TRACK_GAIN`）
 * - FLAC/OGG：Vorbis comment 的 `REPLAYGAIN_TRACK_GAIN=...` 键值
 * Android 没有任何 API 能把它读出来，所以这里必须自己解容器头。**这就是本次绕不开的成本。**
 *
 * ## 为什么自己解容器而不是引第三方依赖
 * 本机离线（无外网、Gradle 只能 `--offline`），而且这类库（如 jaudiotagger）动辄数 MB。
 * 我们只需要**一个键**，手解三种格式的头部（约 200 行）比引一个完整标签库更可控。
 *
 * ## 覆盖范围（有意为之的取舍）
 * - ✅ ID3v2.2 / v2.3 / v2.4（MP3、部分 AAC/M4A 的 ID3 前置标签）
 * - ✅ FLAC（含 Ogg-FLAC 之外的原生 FLAC，绝大多数无损曲库就是这种）
 * - ❌ Ogg Vorbis / Opus：注释藏在 OGG page 里，要先拼页再解 comment，成本翻倍且国内曲库罕见
 * - ❌ M4A/ALAC 的 `iTunNORM`：苹果私有格式，语义与 ReplayGain 不同（是归一化音量对）
 * - ❌ WAV 的 `rgan`/`R128` chunk：ape/wav 用户占比低
 * 以上不支持的一律返回 `null`（= 「这首没有可用的响度信息」），**绝不猜**。
 * 增量补 OGG/MP4 时只需在 [readTrackGainMb] 的分派里加分支，其余不用动。
 *
 * ## 线程约束
 * 本对象做**文件 IO**（读头部几十 KB 到几 MB）。**严禁在主线程调用**——
 * MusicPlaybackService 在切歌回调里把它丢到 IO 线程，见其 [applyLoudnessForCurrentTrack]。
 * 这是历史教训：EQ 曾经在主线程做音频会话探测，低端机上切歌卡顿被误判成「播放器卡」。
 */
object ReplayGainParser {

    /** ReplayGain 2.0 参考响度（89 dB SPL）。标签值 = 参考响度 − 曲目响度，正数 = 该曲偏轻。 */
    private const val MIN_GAIN_MB = -24_000 // -24 dB
    private const val MAX_GAIN_MB = 24_000 // +24 dB

    /** ID3 帧数据体上限：TXXX 正常只有几十字节；超过这个数说明不是我们要的帧或文件损坏 */
    private const val MAX_FRAME_BYTES = 64 * 1024

    /** Vorbis comment 块上限：正常几 KB；防御异常文件 */
    private const val MAX_VORBIS_COMMENT_BYTES = 1024 * 1024

    /**
     * 头部预读字节数。ID3v2 头 10 字节 / FLAC 的 `fLaC` + 首个块头不超过 10 字节，
     * 预读 10 字节足够分派，读完用 [PushbackInputStream] 退回，两条解析路径都能从头顺序读。
     */
    private const val HEADER_PROBE = 10

    /**
     * 读取曲目的 ReplayGain 曲目增益，单位**毫贝（mB）**（LoudnessEnhancer.setTargetGain 的原生单位）。
     *
     * 返回 `null` = 文件里没有该标签 / 容器不支持 / 文件读不了 → 调用方**不做任何补偿**。
     * 这一点是刻意的：响度归一的正确行为是「有标签才补偿」，没有标签就给个固定增益
     * 会把本来就录得响的歌推爆（削波），那比不归一更糟。
     */
    fun readTrackGainMb(input: InputStream): Int? {
        val stream = PushbackInputStream(input, HEADER_PROBE)
        return runCatching {
            val probe = ByteArray(HEADER_PROBE)
            val read = readAtMost(stream, probe, HEADER_PROBE)
            stream.unread(probe, 0, read)
            when {
                isId3(probe, read) -> readFromId3(stream)
                isFlac(probe, read) -> readFromFlac(stream)
                else -> null // M4A / WAV / OGG / APE …：本轮不支持，见类注释
            }
        }.getOrNull()
    }

    /** 解析 `REPLAYGAIN_TRACK_GAIN` / `R128_TRACK_GAIN` 的**值**（各容器共用） */
    fun parseGainValue(raw: String?): Int? {
        val text = raw?.trim()?.removeSurrounding("\"")?.trim() ?: return null
        if (text.isEmpty()) return null
        // 绝大多数写作 "-7.23 dB" / "+1.5 dB" / "−7.23dB"（注意可能有 U+2212 真减号）
        val normalized = text.replace('\u2212', '-').replace('\uFF0D', '-')
        val match = GAIN_RE.find(normalized) ?: return null
        val db = match.groupValues[1].toFloatOrNull() ?: return null
        if (db.isNaN() || db.isInfinite()) return null
        // 必须**四舍五入**而不是 `(db*100).toInt()`：float 表示 7.29 时是 7.2899999618…，
        // 乘 100 得 728.999996，直接截断会变成 728 mB —— 每首歌都少 1 mB 看似无害，
        // 但这是「解析看起来对、实际不对」的典型，日后排查响度问题会怀疑到错误的地方。
        return kotlin.math.round(db * 100f).toInt().coerceIn(MIN_GAIN_MB, MAX_GAIN_MB)
    }

    /** `-7.23 dB` 里把数字抓出来；允许省略 dB 单位（有些 tagger 只写数字） */
    private val GAIN_RE = Regex("""^\s*([+-]?\d+(?:\.\d+)?)\s*(?:dB|db|DB)?""")

    // ── ID3v2 ─────────────────────────────────────────────

    private fun isId3(b: ByteArray, n: Int): Boolean =
        n >= 3 && b[0] == 'I'.code.toByte() && b[1] == 'D'.code.toByte() && b[2] == '3'.code.toByte()

    /**
     * ID3v2 帧遍历。
     *
     * 支持 2.2（3 字节帧 id + 3 字节长度）与 2.3/2.4（4+4+2）。**必须同时支持 2.3 与 2.4**：
     * 2.4 的帧长度是 syncsafe（每字节只用 7 位），而 2.3 是普通大端——两者按普通大端解会
     * 在长度 ≥ 0x80 的帧上算错，然后整个帧链错位，表现为「有的歌能读到、有的读不到」，
     * 极难排查。这里按 header 里的版本号分派。
     *
     * **只找 TXXX，其余帧整帧跳过**（skip 而不是 read）：曲库里大量 MP3 内嵌几 MB 的
     * APIC 封面帧，全读进内存等于切一首歌分配几 MB，GC 抖动会直接变成可感知的卡顿。
     */
    private fun readFromId3(input: InputStream): Int? {
        val header = ByteArray(10)
        if (readAtMost(input, header, 10) < 10) return null
        val major = header[3].toInt() and 0xFF
        val flags = header[5].toInt() and 0xFF
        if (major !in 2..4) return null
        // syncsafe：4 个字节，每字节只用低 7 位（最高位恒 0，避免与同步字节 0xFF 冲突）
        val tagSize = syncSafeInt(header, 6, 4)
        // 未同步（unsynchronisation）：把 0xFF 0x00 还原成 0xFF。全程处理成本高且极罕见
        // （只在老工具产出的 2.3 文件里出现），这里直接放弃而不是解错——
        // 解错会得到一串垃圾帧 id，然后误判成「没有标签」，与不支持的结论一致，无害。
        if (flags and 0x80 != 0) return null
        // 有扩展头：2.3 是 4 字节长度；2.4 是 syncsafe 4 字节长度。跳过它。
        var remaining = tagSize
        if (flags and 0x40 != 0) {
            val ext = ByteArray(if (major == 2) 6 else 4)
            if (readAtMost(input, ext, ext.size) < ext.size) return null
            val skip = if (major >= 4) syncSafeInt(ext, 0, 4) else (ext[0].toInt() and 0xFF shl 24 or
                (ext[1].toInt() and 0xFF shl 16) or (ext[2].toInt() and 0xFF shl 8) or
                (ext[3].toInt() and 0xFF))
            remaining -= ext.size + skip
        }
        var idBytes = if (major == 2) 3 else 4
        var headBytes = if (major == 2) 6 else 10
        while (remaining > headBytes) {
            val head = ByteArray(headBytes)
            if (readAtMost(input, head, headBytes) < headBytes) return null
            remaining -= headBytes
            val id = String(head, 0, idBytes, Charsets.ISO_8859_1)
            if (id.all { it == '\u0000' }) return null // 填充区：后面没有真帧了
            val frameSize = when (major) {
                2 -> (head[3].toInt() and 0xFF shl 16) or (head[4].toInt() and 0xFF shl 8) or
                    (head[5].toInt() and 0xFF)
                4 -> syncSafeInt(head, 4, 4)
                else -> (head[4].toInt() and 0xFF shl 24) or (head[5].toInt() and 0xFF shl 16) or
                    (head[6].toInt() and 0xFF shl 8) or (head[7].toInt() and 0xFF)
            }
            if (frameSize <= 0 || frameSize > remaining) return null // 越界：文件损坏
            remaining -= frameSize
            if (id == "TXXX") {
                if (frameSize > MAX_FRAME_BYTES) return null
                val body = ByteArray(frameSize)
                if (readAtMost(input, body, frameSize) < frameSize) return null
                parseTxxx(body)?.let { return it }
            } else {
                if (!skipFully(input, frameSize.toLong())) return null
            }
        }
        return null
    }

    /**
     * TXXX 帧体 = 编码字节 + 描述(以编码对应的终止符结尾) + 值(同样终止)。
     *
     * 编码：0=ISO-8859-1、1=UTF-16 带 BOM、2=UTF-16BE、3=UTF-8。
     * **UTF-16 的终止符是 2 字节**（0x00 0x00 按编码单元），按 1 字节切会把描述读成
     * "REPLAYGAIN_TRACK_GAIN\0R"，于是永远匹配不上——这是本解析器最容易写错的一处。
     *
     * 还有第二处同样隐蔽的坑：`Charsets.UTF_16` 在**没有 BOM 时默认按大端解码**。
     * BOM 位于帧体内、在描述串之前，而我们要切出来的描述串是「BOM 之后」那一段——
     * 直接 `String(body, descStart, len, Charsets.UTF_16)` 会因为切片里不含 BOM 而按 BE 解，
     * 得到一串乱码，key 永远匹配不上（真机表现：UTF-16 标签的歌全部不归一，而 ASCII 标签的正常）。
     * 因此这里先从 BOM 读出真实字节序，之后**显式用 UTF_16LE / UTF_16BE** 解码，
     * 不再依赖 `Charsets.UTF_16` 的 BOM 猜测。
     */
    private fun parseTxxx(body: ByteArray): Int? {
        if (body.isEmpty()) return null
        val encoding = body[0].toInt() and 0xFF
        // 判定实际字节序（仅 encoding=1 才有 BOM；2 固定 BE；0/3 与字节序无关）
        var littleEndian = false
        var hasBom = false
        if (encoding == 1 && body.size >= 3) {
            val b0 = body[1].toInt() and 0xFF
            val b1 = body[2].toInt() and 0xFF
            if (b0 == 0xFF && b1 == 0xFE) {
                littleEndian = true; hasBom = true
            } else if (b0 == 0xFE && b1 == 0xFF) {
                littleEndian = false; hasBom = true
            }
        }
        val charset = when (encoding) {
            0 -> Charsets.ISO_8859_1
            1 -> if (littleEndian) Charsets.UTF_16LE else Charsets.UTF_16BE
            2 -> Charsets.UTF_16BE
            else -> Charsets.UTF_8
        }
        val unitSize = if (encoding == 1 || encoding == 2) 2 else 1
        // BOM 属于帧头不属于描述串：跳过它（否则描述串会带一个 U+FEFF 前缀，key 匹配不上）
        val descStart = if (hasBom) 3 else 1
        var pos = descStart
        while (pos + unitSize <= body.size) {
            if (isTerminator(body, pos, unitSize)) break
            pos += unitSize
        }
        val desc = String(body, descStart, (pos - descStart).coerceAtLeast(0), charset).trim()
        val valueStart = (pos + unitSize).coerceAtMost(body.size)
        val valueEnd = findTerminatorOrEnd(body, valueStart, unitSize)
        val value = String(body, valueStart, (valueEnd - valueStart).coerceAtLeast(0), charset).trim()
        if (!isTrackGainKey(desc)) return null
        return parseGainValue(value)
    }

    private fun isTerminator(b: ByteArray, at: Int, unitSize: Int): Boolean =
        if (unitSize == 2) {
            b[at].toInt() == 0 && at + 1 < b.size && b[at + 1].toInt() == 0
        } else {
            b[at].toInt() == 0
        }

    private fun findTerminatorOrEnd(b: ByteArray, from: Int, unitSize: Int): Int {
        var i = from
        while (i + unitSize <= b.size) {
            if (isTerminator(b, i, unitSize)) return i
            i += unitSize
        }
        return b.size
    }

    // ── FLAC ──────────────────────────────────────────────

    private fun isFlac(b: ByteArray, n: Int): Boolean =
        n >= 4 && b[0] == 'f'.code.toByte() && b[1] == 'L'.code.toByte() &&
            b[2] == 'a'.code.toByte() && b[3] == 'C'.code.toByte()

    /**
     * FLAC 元数据块遍历：`fLaC` 之后是一串 block，每块 1 字节（最后块标志 + 类型）+ 3 字节长度（大端）。
     * 类型 4 = VORBIS_COMMENT，就是 ReplayGain 的家。找到即停，其余整块跳过。
     */
    private fun readFromFlac(input: InputStream): Int? {
        val magic = ByteArray(4)
        if (readAtMost(input, magic, 4) < 4) return null
        var guard = 0
        while (guard++ < 128) { // 防御：异常文件靠块链绕圈
            val head = ByteArray(4)
            if (readAtMost(input, head, 4) < 4) return null
            val last = (head[0].toInt() and 0x80) != 0
            val type = head[0].toInt() and 0x7F
            val length = (head[1].toInt() and 0xFF shl 16) or
                (head[2].toInt() and 0xFF shl 8) or (head[3].toInt() and 0xFF)
            if (type == 4) {
                if (length > MAX_VORBIS_COMMENT_BYTES) return null
                val block = ByteArray(length)
                if (readAtMost(input, block, length) < length) return null
                return parseVorbisComment(block)
            }
            if (last) return null
            if (!skipFully(input, length.toLong())) return null
        }
        return null
    }

    /**
     * Vorbis comment 块：vendor_length(4 LE) + vendor + comment_count(4 LE) + N × (len(4 LE) + "KEY=value")。
     * 键名按规范**大小写不敏感**，所以统一转大写比较。
     */
    private fun parseVorbisComment(block: ByteArray): Int? {
        var pos = 0
        if (block.size < 8) return null
        val vendorLen = leInt(block, pos); pos += 4
        if (vendorLen < 0 || pos + vendorLen > block.size) return null
        pos += vendorLen
        if (pos + 4 > block.size) return null
        val count = leInt(block, pos); pos += 4
        if (count < 0) return null
        var i = 0
        while (i < count && pos + 4 <= block.size) {
            val len = leInt(block, pos); pos += 4
            if (len < 0 || pos + len > block.size) return null
            val entry = String(block, pos, len, Charsets.UTF_8)
            pos += len
            val eq = entry.indexOf('=')
            if (eq > 0) {
                val key = entry.substring(0, eq).trim().uppercase()
                if (isTrackGainKey(key)) return parseGainValue(entry.substring(eq + 1))
            }
            i++
        }
        return null
    }

    private fun leInt(b: ByteArray, at: Int): Int =
        (b[at].toInt() and 0xFF) or (b[at + 1].toInt() and 0xFF shl 8) or
            (b[at + 2].toInt() and 0xFF shl 16) or (b[at + 3].toInt() and 0xFF shl 24)

    // ── 共用小工具 ────────────────────────────────────────

    /**
     * 判定是不是「曲目响度」键。**排除 ALBUM_GAIN**：专辑增益是给整张专辑用的，
     * 单曲乱序播放时用专辑增益会让个别曲目响度不对（专辑增益假设你按顺序听整张）。
     * 同时接受 R128 的 `R128_TRACK_GAIN`：它单位是 Q7.8 定点（不是 dB），语义完全不同，
     * 这里**不换算**（换算要改单位体系），直接忽略，避免把 256 的倍率关系算错。
     */
    private fun isTrackGainKey(key: String): Boolean {
        val k = key.trim().uppercase()
        return k == "REPLAYGAIN_TRACK_GAIN"
    }

    private fun syncSafeInt(b: ByteArray, at: Int, len: Int): Int {
        var v = 0
        for (i in 0 until len) v = (v shl 7) or (b[at + i].toInt() and 0x7F)
        return v
    }

    /** 读满 n 字节（允许短读，返实际字节数）；EOF 不抛，返回已读数量 */
    private fun readAtMost(input: InputStream, buf: ByteArray, n: Int): Int {
        var total = 0
        while (total < n) {
            val r = try {
                input.read(buf, total, n - total)
            } catch (e: EOFException) {
                -1
            }
            if (r <= 0) break
            total += r
        }
        return total
    }

    /** 跳过 n 字节；**必须循环 skip**：InputStream.skip 允许短跳（网络/管道流），单次 skip 会错位 */
    private fun skipFully(input: InputStream, n: Long): Boolean {
        var left = n
        var guard = 0
        val scratch = ByteArray(8 * 1024)
        while (left > 0) {
            val skipped = input.skip(left)
            if (skipped > 0) {
                left -= skipped
                continue
            }
            // skip 返回 0：该流不支持定位（如某些 ContentResolver 包装流），退化成读丢弃
            val r = input.read(scratch, 0, minOf(left, scratch.size.toLong()).toInt())
            if (r <= 0) return false
            left -= r
            if (guard++ > 1_000_000) return false // 防御死循环
        }
        return true
    }
}
