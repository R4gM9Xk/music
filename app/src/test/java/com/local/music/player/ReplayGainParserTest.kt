package com.local.music.player

import java.io.ByteArrayOutputStream
import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * ReplayGain 标签解析单测。
 *
 * 为什么必须测：这是本次唯一「手写二进制解析」的新代码，而它的失败模式**完全不可见**——
 * 解析错了不会崩溃、不会报错，只会「某些歌不归一、某些歌增益离谱」。真机上根本没法验证，
 * 只能靠构造字节流把所有分支钉死。
 *
 * 覆盖策略：每个容器格式用**真实字节布局**构造（含 syncsafe 长度、UTF-16 BOM 终止符、
 * 大端/小端混用这些最容易写错的点），而不是简单地「塞一个字符串进去」。
 */
class ReplayGainParserTest {

    // ── 值解析 ─────────────────────────────────────────────

    @Test
    fun `标准 dB 写法解析为毫贝`() {
        // -7.23 dB → -723 mB。这是 ReplayGain 最常见的形态
        assertEquals(-723, ReplayGainParser.parseGainValue("-7.23 dB"))
    }

    @Test
    fun `正增益与省略单位都能解析`() {
        assertEquals(150, ReplayGainParser.parseGainValue("+1.50 dB"))
        assertEquals(-300, ReplayGainParser.parseGainValue("-3.0")) // 有些 tagger 不带 dB
    }

    @Test
    fun `真减号 U+2212 也要能解析`() {
        // 注意：这是「看起来像减号但不是 ASCII 减号」的字符，硬编码 '-' 匹配会漏掉这类文件
        val text = "\u22127.00 dB"
        assertEquals(-700, ReplayGainParser.parseGainValue(text))
    }

    @Test
    fun `两侧空白与引号被清理`() {
        assertEquals(-500, ReplayGainParser.parseGainValue("  \"-5.00 dB\"  "))
    }

    @Test
    fun `无法解析的内容返回 null 而不是零`() {
        // 关键区分：0 = 「标签说不用调整」，null = 「读不到」。混成 0 会让没有标签的歌被误当成都已归一
        assertNull(ReplayGainParser.parseGainValue(null))
        assertNull(ReplayGainParser.parseGainValue(""))
        assertNull(ReplayGainParser.parseGainValue("not a number"))
        assertNull(ReplayGainParser.parseGainValue("dB"))
    }

    @Test
    fun `超出合理范围的增益被钳制`() {
        // 防的是脏标签（有人把 1000 写进 gain）把用户耳朵炸了。
        // 阈值是 ±24000 mB = ±240 dB。注意换算：mB = dB × 100，
        // 所以 ±300 dB = ±30000 mB 才真正越界（±100 dB 只到 10000 mB，仍在合法区内）。
        assertEquals(24_000, ReplayGainParser.parseGainValue("+300 dB"))
        assertEquals(-24_000, ReplayGainParser.parseGainValue("-300 dB"))
    }

    @Test
    fun `边界值本身不被钳制`() {
        // -240 dB 恰好等于下限：应原样得到 **-24000** mB（负号不能丢），不能被钳成别的值。
        // （顺带钉住单位换算：24 dB == 2400 mB，这是最容易被 mB/dB 混淆搞错的一处。）
        assertEquals(-24_000, ReplayGainParser.parseGainValue("-240 dB"))
        assertEquals(24_000, ReplayGainParser.parseGainValue("+240 dB"))
        assertEquals(2_400, ReplayGainParser.parseGainValue("+24.00 dB"))
    }

    @Test
    fun `NaN 与无穷不产生增益`() {
        assertNull(ReplayGainParser.parseGainValue("NaN dB"))
        assertNull(ReplayGainParser.parseGainValue("Infinity"))
    }

    // ── ID3v2.3 ───────────────────────────────────────────

    @Test
    fun `ID3v2_3 的 TXXX 能读出曲目增益`() {
        val mp3 = id3Tag(
            major = 3,
            frames = listOf(txxx("REPLAYGAIN_TRACK_GAIN", "-6.50 dB", utf16 = false)),
        )
        assertEquals(-650, ReplayGainParser.readTrackGainMb(ByteArrayInputStream(mp3)))
    }

    @Test
    fun `ID3v2_4 的 syncsafe 帧长度不会让帧链错位`() {
        // 这条是回归重点：2.4 的帧长度是 syncsafe（每字节 7 位），若按普通大端解，
        // 长度 >= 0x80 的帧会算错，后续帧全部错位 → 「有的歌能读、有的读不到」。
        // 这里故意在前置帧塞一个 >= 128 字节的填充帧，强制触发大长度分支。
        val bigPadding = ByteArray(300) { 'x'.code.toByte() }
        val mp3 = id3Tag(
            major = 4,
            frames = listOf(
                // TIT2 标题帧，长度 300（>= 0x80），会走 syncsafe 分支。
                // **必须传 major=4**：300 的 syncsafe 编码是 00 00 02 2C，普通大端是 00 00 01 2C，
                // 两者不同 —— 正好能测出「按错的方式解长度」这个 bug
                frame("TIT2", byteArrayOf(0) + bigPadding, major = 4),
                txxx("REPLAYGAIN_TRACK_GAIN", "-4.00 dB", utf16 = false, major = 4),
            ),
        )
        assertEquals(-400, ReplayGainParser.readTrackGainMb(ByteArrayInputStream(mp3)))
    }

    @Test
    fun `ID3v2_3 的大帧按普通大端解析`() {
        // 与上一条对照：2.3 的长度不是 syncsafe，同样 300 字节的帧必须照样跳过
        val bigPadding = ByteArray(300) { 'y'.code.toByte() }
        val mp3 = id3Tag(
            major = 3,
            frames = listOf(
                frame("TPE1", byteArrayOf(0) + bigPadding),
                txxx("REPLAYGAIN_TRACK_GAIN", "-2.25 dB", utf16 = false),
            ),
        )
        assertEquals(-225, ReplayGainParser.readTrackGainMb(ByteArrayInputStream(mp3)))
    }

    @Test
    fun `ID3 的 UTF16 描述串用两字节终止符正确切分`() {
        // 最容易写错的一处：UTF-16 的 \0 是 2 字节。按 1 字节切会把描述读成
        // "REPLAYGAIN_TRACK_GAIN\0R"，于是永远匹配不上 key（表面现象就是「这张专辑不归一」）
        val mp3 = id3Tag(
            major = 3,
            frames = listOf(txxx("REPLAYGAIN_TRACK_GAIN", "-8.00 dB", utf16 = true)),
        )
        assertEquals(-800, ReplayGainParser.readTrackGainMb(ByteArrayInputStream(mp3)))
    }

    @Test
    fun `专辑增益不被当成曲目增益`() {
        // ALBUM_GAIN 是给「按顺序听整张专辑」用的，乱序播放套它会响度不对，必须忽略
        val mp3 = id3Tag(
            major = 3,
            frames = listOf(txxx("REPLAYGAIN_ALBUM_GAIN", "-9.00 dB", utf16 = false)),
        )
        assertNull(ReplayGainParser.readTrackGainMb(ByteArrayInputStream(mp3)))
    }

    @Test
    fun `没有 ReplayGain 帧的 ID3 返回 null`() {
        val mp3 = id3Tag(
            major = 3,
            frames = listOf(frame("TIT2", byteArrayOf(0, 'H'.code.toByte(), 'i'.code.toByte()))),
        )
        assertNull(ReplayGainParser.readTrackGainMb(ByteArrayInputStream(mp3)))
    }

    // ── FLAC ──────────────────────────────────────────────

    @Test
    fun `FLAC 的 Vorbis comment 能读出曲目增益`() {
        val flac = flacFile(
            vorbisComments = listOf(
                "TITLE=某首歌",
                "REPLAYGAIN_TRACK_GAIN=-7.50 dB",
            ),
        )
        assertEquals(-750, ReplayGainParser.readTrackGainMb(ByteArrayInputStream(flac)))
    }

    @Test
    fun `FLAC 的键名大小写不敏感`() {
        // Vorbis comment 规范要求键名大小写不敏感，实际文件里三种写法都见过
        val flac = flacFile(vorbisComments = listOf("replaygain_track_gain=-3.50 dB"))
        assertEquals(-350, ReplayGainParser.readTrackGainMb(ByteArrayInputStream(flac)))
    }

    @Test
    fun `FLAC 先跳过 STREAMINFO 再找 VORBIS_COMMENT`() {
        // 真实 FLAC 的第一个块永远是 STREAMINFO（类型 0），必须能跳过它继续找
        val flac = flacFile(
            vorbisComments = listOf("REPLAYGAIN_TRACK_GAIN=+2.00 dB"),
            leadingStreamInfo = true,
        )
        assertEquals(200, ReplayGainParser.readTrackGainMb(ByteArrayInputStream(flac)))
    }

    @Test
    fun `FLAC 只有专辑增益时返回 null`() {
        val flac = flacFile(vorbisComments = listOf("REPLAYGAIN_ALBUM_GAIN=-5.00 dB"))
        assertNull(ReplayGainParser.readTrackGainMb(ByteArrayInputStream(flac)))
    }

    @Test
    fun `FLAC 没有 Vorbis comment 块返回 null`() {
        val flac = flacFile(vorbisComments = null)
        assertNull(ReplayGainParser.readTrackGainMb(ByteArrayInputStream(flac)))
    }

    // ── 不支持 / 损坏的输入 ─────────────────────────────────

    @Test
    fun `不认识的容器返回 null 而不是抛异常`() {
        // M4A / WAV / OGG / APE：本轮不支持，必须安静地返回 null
        val m4a = ByteArray(64).also {
            it[0] = 0x00; it[1] = 0x00; it[2] = 0x00; it[3] = 0x20
            "ftypM4A ".forEachIndexed { i, c -> it[4 + i] = c.code.toByte() }
        }
        assertNull(ReplayGainParser.readTrackGainMb(ByteArrayInputStream(m4a)))
    }

    @Test
    fun `空输入与超短输入不崩溃`() {
        assertNull(ReplayGainParser.readTrackGainMb(ByteArrayInputStream(ByteArray(0))))
        assertNull(ReplayGainParser.readTrackGainMb(ByteArrayInputStream(byteArrayOf(0x49))))
        assertNull(ReplayGainParser.readTrackGainMb(ByteArrayInputStream("ID3".toByteArray())))
    }

    @Test
    fun `帧长度越界的损坏文件返回 null 而不是死循环或越界读`() {
        // 构造一个声称有巨大帧的 ID3 头：解析器必须识别出「越界 = 损坏」并停手
        val head = ByteArray(10)
        head[0] = 'I'.code.toByte(); head[1] = 'D'.code.toByte(); head[2] = '3'.code.toByte()
        head[3] = 3; head[4] = 0; head[5] = 0
        // tagSize = syncsafe(0x00,0x00,0x7F,0x7F) 很大
        head[6] = 0; head[7] = 0; head[8] = 0x7F; head[9] = 0x7F
        val frame = ByteArray(10)
        "TXXX".forEachIndexed { i, c -> frame[i] = c.code.toByte() }
        // 帧长度声称 0xFFFFFF（远超实际剩余数据）
        frame[4] = 0xFF.toByte(); frame[5] = 0xFF.toByte()
        frame[6] = 0xFF.toByte(); frame[7] = 0xFF.toByte()
        val bogus = head + frame
        assertNull(ReplayGainParser.readTrackGainMb(ByteArrayInputStream(bogus)))
    }

    @Test
    fun `被截断的 TXXX 帧数据不崩溃`() {
        val mp3 = id3Tag(
            major = 3,
            frames = listOf(txxx("REPLAYGAIN_TRACK_GAIN", "-1.00 dB", utf16 = false)),
        )
        // 砍掉最后 3 字节：帧声明长度与实际数据不符
        val truncated = mp3.copyOfRange(0, mp3.size - 3)
        // 结果是 null 还是解析成功都可以接受，关键是不抛异常
        runCatching { ReplayGainParser.readTrackGainMb(ByteArrayInputStream(truncated)) }
            .let { assert(it.isSuccess) { "截断输入不应抛异常" } }
    }

    // ── 构造器（按真实字节布局拼文件） ──────────────────────

    /** ID3v2 tag：10 字节头 + 若干帧 + 结束（无 padding，解析器不应依赖 padding） */
    private fun id3Tag(major: Byte, frames: List<ByteArray>): ByteArray {
        // 注意：frames 必须由调用方用**相同的 major** 构造（v2.4 的帧长度是 syncsafe，
        // v2.3 是普通大端）。写错会让「帧链错位」这个 bug 无法被测出来。
        val body = frames.fold(ByteArray(0)) { acc, f -> acc + f }
        val header = ByteArray(10)
        header[0] = 'I'.code.toByte()
        header[1] = 'D'.code.toByte()
        header[2] = '3'.code.toByte()
        header[3] = major
        header[4] = 0 // revision
        header[5] = 0 // flags：无 unsync / 无扩展头
        writeSyncSafe(header, 6, body.size)
        return header + body
    }

    /** 单帧：id + 长度 + flags + body。v2.3 用普通大端，v2.4 用 syncsafe */
    private fun frame(id: String, body: ByteArray, major: Byte = 3): ByteArray {
        val head = ByteArray(10)
        id.forEachIndexed { i, c -> head[i] = c.code.toByte() }
        if (major >= 4) writeSyncSafe(head, 4, body.size) else writeBe32(head, 4, body.size)
        head[8] = 0; head[9] = 0 // flags
        return head + body
    }

    /**
     * TXXX 帧体：编码字节 + 描述 + 终止符 + 值 + 终止符。
     * utf16=true 时按带 BOM 的 UTF-16LE 写（这是真实 tagger 最常用的形态）。
     */
    private fun txxx(desc: String, value: String, utf16: Boolean, major: Byte = 3): ByteArray {
        val body = if (utf16) {
            val out = ByteArrayOutputStream()
            out.write(1) // encoding = UTF-16 with BOM
            out.write(0xFF); out.write(0xFE) // BOM（LE）
            out.write(desc.toByteArray(Charsets.UTF_16LE))
            out.write(0x00); out.write(0x00) // 描述终止符（2 字节）
            out.write(value.toByteArray(Charsets.UTF_16LE))
            out.write(0x00); out.write(0x00)
            out.toByteArray()
        } else {
            val out = ByteArrayOutputStream()
            out.write(0) // encoding = ISO-8859-1
            out.write(desc.toByteArray(Charsets.ISO_8859_1))
            out.write(0x00)
            out.write(value.toByteArray(Charsets.ISO_8859_1))
            out.write(0x00)
            out.toByteArray()
        }
        return frame("TXXX", body, major)
    }

    /** 写 syncsafe 整数：4 字节，每字节只用低 7 位 */
    private fun writeSyncSafe(b: ByteArray, at: Int, value: Int) {
        b[at] = ((value shr 21) and 0x7F).toByte()
        b[at + 1] = ((value shr 14) and 0x7F).toByte()
        b[at + 2] = ((value shr 7) and 0x7F).toByte()
        b[at + 3] = (value and 0x7F).toByte()
    }

    /** 写普通大端 32 位 */
    private fun writeBe32(b: ByteArray, at: Int, value: Int) {
        b[at] = ((value shr 24) and 0xFF).toByte()
        b[at + 1] = ((value shr 16) and 0xFF).toByte()
        b[at + 2] = ((value shr 8) and 0xFF).toByte()
        b[at + 3] = (value and 0xFF).toByte()
    }

    /** 写普通小端 32 位（Vorbis comment 用） */
    private fun writeLe32(b: ByteArray, at: Int, value: Int) {
        b[at] = (value and 0xFF).toByte()
        b[at + 1] = ((value shr 8) and 0xFF).toByte()
        b[at + 2] = ((value shr 16) and 0xFF).toByte()
        b[at + 3] = ((value shr 24) and 0xFF).toByte()
    }

    /**
     * FLAC 文件：`fLaC` + 可选 STREAMINFO 块 + 可选 VORBIS_COMMENT 块。
     * 块头 = 1 字节（最后块标志 << 7 | 类型）+ 3 字节大端长度。
     */
    private fun flacFile(vorbisComments: List<String>?, leadingStreamInfo: Boolean = false): ByteArray {
        val streamInfoBody = ByteArray(34) // 真实 STREAMINFO 固定 34 字节
        val blocks = mutableListOf<Pair<Int, ByteArray>>()
        if (leadingStreamInfo) blocks += 0 to streamInfoBody
        if (vorbisComments != null) blocks += 4 to vorbisCommentBlock(vorbisComments)

        val out = ByteArrayOutputStream()
        out.write("fLaC".toByteArray(Charsets.ISO_8859_1))
        blocks.forEachIndexed { index, (type, body) ->
            val last = index == blocks.lastIndex
            val head = ByteArray(4)
            head[0] = (((if (last) 0x80 else 0) or type)).toByte()
            head[1] = ((body.size shr 16) and 0xFF).toByte()
            head[2] = ((body.size shr 8) and 0xFF).toByte()
            head[3] = (body.size and 0xFF).toByte()
            out.write(head)
            out.write(body)
        }
        return out.toByteArray()
    }

    private fun vorbisCommentBlock(comments: List<String>): ByteArray {
        val vendor = "test-tagger".toByteArray(Charsets.UTF_8)
        val out = ByteArrayOutputStream()
        val vendorLen = ByteArray(4); writeLe32(vendorLen, 0, vendor.size)
        out.write(vendorLen); out.write(vendor)
        val count = ByteArray(4); writeLe32(count, 0, comments.size)
        out.write(count)
        comments.forEach { c ->
            val bytes = c.toByteArray(Charsets.UTF_8)
            val len = ByteArray(4); writeLe32(len, 0, bytes.size)
            out.write(len); out.write(bytes)
        }
        return out.toByteArray()
    }
}
