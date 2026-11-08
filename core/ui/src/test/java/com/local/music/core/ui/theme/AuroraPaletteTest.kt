package com.local.music.core.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 取色公式归一（视觉审计 A3）的验收单测。
 *
 * 为什么这些断言值得写：`AuroraPalette.fromSeed` 现在是**全 App 唯一的派生函数**，
 * 品牌预设、用户手选主题色、封面取色三条路径都汇到它。它一旦漂移，症状是「同一张封面
 * 切歌时背景氛围跳一下」这种只有真机肉眼能发现、CI 永远抓不到的问题。所以这里把
 * 「派生结果必须贴近手调预设」写成可执行的基准表，而不是留在注释里。
 *
 * 这套用例能在纯 JVM 跑，是因为 `fromSeed` 已改为自己实现 RGB↔HSV、不碰
 * `android.graphics.Color`（android.jar 的方法在单测里会抛 "not mocked"）。
 * 同理，本文件只依赖 Compose `Color` 的通道访问与 [toArgb]，二者都是纯 Kotlin 实现。
 */
class AuroraPaletteTest {

    // ── 校准基准：GlassPalettes 的手调五元组 ──────────────────
    // 这四组值是品牌观感的锚点。fromSeed 的系数就是拟合到它们身上的，
    // 所以这里逐字段断言「派生值必须落在手调值附近」——系数被误改会立刻红。
    private data class Baseline(
        val name: String,
        val seed: Int,
        val seed2: Int, val seed3: Int, val deep: Int, val deep2: Int,
    )

    private val baselines = listOf(
        Baseline(
            "Purple", 0xFF7F77DD.toInt(),
            0xFFAFA9EC.toInt(), 0xFFCECBF6.toInt(), 0xFF241C4A.toInt(), 0xFF0E0A20.toInt(),
        ),
        Baseline(
            "Teal", 0xFF1D9E75.toInt(),
            0xFF5DCAA5.toInt(), 0xFF9FE1CB.toInt(), 0xFF062E26.toInt(), 0xFF04140F.toInt(),
        ),
        Baseline(
            "Coral", 0xFFD85A30.toInt(),
            0xFFF0997B.toInt(), 0xFFF5C4B3.toInt(), 0xFF3D1408.toInt(), 0xFF1A0803.toInt(),
        ),
        Baseline(
            "Blue", 0xFF378ADD.toInt(),
            0xFF85B7EB.toInt(), 0xFFB5D4F4.toInt(), 0xFF0A2540.toInt(), 0xFF04121F.toInt(),
        ),
    )

    /**
     * 允许的最大偏差（CIEDE2000）。
     *
     * 取 3.0 的依据：ΔE00 ≈ 1 是「训练过的眼睛在严格条件下」刚好可辨的界，
     * ≈ 2–3 是常规条件下勉强可辨，> 5 才是「一眼就是两个颜色」。
     * 改造前最差 9.04（Coral.seed3）、RMS 5.05，明显属于后者；
     * 拟合后最差 2.63（Teal.deep）、RMS 1.28，落在「常规观察不可辨」区间。
     * 阈值定 3.0：既能守住拟合质量，又不至于因浮点末位让用例随机红。
     */
    private val maxDeltaE00 = 3.0

    @Test
    fun `派生结果贴合手调预设基准`() {
        val failures = mutableListOf<String>()
        var worst = 0.0
        var sq = 0.0
        var n = 0
        for (b in baselines) {
            val p = AuroraPalette.fromSeed(Color(b.seed))
            val pairs = listOf(
                "seed2" to (p.seed2 to Color(b.seed2)),
                "seed3" to (p.seed3 to Color(b.seed3)),
                "deep" to (p.deep to Color(b.deep)),
                "deep2" to (p.deep2 to Color(b.deep2)),
            )
            for ((field, pair) in pairs) {
                val (got, want) = pair
                val d = deltaE2000(lab(got), lab(want))
                worst = maxOf(worst, d)
                sq += d * d
                n++
                if (d > maxDeltaE00) {
                    failures += "${b.name}.$field: 基准 #${hex(want)} 派生 #${hex(got)} ΔE00=%.2f".format(d)
                }
            }
        }
        val rms = sqrt(sq / n)
        println("[A3] 校准基准偏差: RMS ΔE00=%.3f, 最差=%.3f (阈值 %.1f)".format(rms, worst, maxDeltaE00))
        assertTrue("超出校准阈值的字段:\n" + failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun `品牌预设与同 seed 的 fromSeed 派生观感一致`() {
        // 这是 A3 的核心症状：同一个色相「品牌靛紫」与「用户手选靛紫」曾是两种深浅。
        // 现在 by construction 一致到 ΔE00 ≤ 3；seed 本身必须逐位相同（不做二次量化）。
        for (b in baselines) {
            val p = AuroraPalette.fromSeed(Color(b.seed))
            assertEquals("${b.name}: seed 必须原样保留", b.seed, p.seed.toArgb())
        }
    }

    @Test
    fun `三路径同 seed 输出逐位相同`() {
        // 三条路径在代码里都调 fromSeed，唯一的差异是 seed 怎么来的：
        // - 用户手选：AppState 里 Color(customSeedArgb)
        // - 封面取色：MediaLibrary 里 Color(swatch.rgb)
        // - 缓存回读：MediaLibrary.readCacheEntries 里 Color(缓存里的 argb)
        // 前两条都是直接由 ARGB int 构造；缓存回读要走一次 toArgb 落盘再回读。
        // 所以真正要守的不变式是：**seed 经过 ARGB 往返后派生结果必须逐位不变**，
        // 否则「刚取色」与「重启后从缓存恢复」会得到两套氛围（这是 A3 的残留形态）。
        for (b in baselines) {
            val direct = AuroraPalette.fromSeed(Color(b.seed))
            val roundTripped = AuroraPalette.fromSeed(Color(Color(b.seed).toArgb()))
            assertEquals(b.name + " seed", direct.seed.toArgb(), roundTripped.seed.toArgb())
            assertEquals(b.name + " seed2", direct.seed2.toArgb(), roundTripped.seed2.toArgb())
            assertEquals(b.name + " seed3", direct.seed3.toArgb(), roundTripped.seed3.toArgb())
            assertEquals(b.name + " deep", direct.deep.toArgb(), roundTripped.deep.toArgb())
            assertEquals(b.name + " deep2", direct.deep2.toArgb(), roundTripped.deep2.toArgb())
        }
    }

    @Test
    fun `PresetSeeds 十色全部可派生且不超界`() {
        assertEquals(10, AuroraPalette.PresetSeeds.size)
        for (seed in AuroraPalette.PresetSeeds) {
            val p = AuroraPalette.fromSeed(seed)
            for ((name, c) in listOf(
                "seed" to p.seed, "seed2" to p.seed2, "seed3" to p.seed3,
                "deep" to p.deep, "deep2" to p.deep2,
            )) {
                assertFalse("#${hex(seed)}.$name 出现 NaN", c.red.isNaN() || c.green.isNaN() || c.blue.isNaN())
                assertTrue("#${hex(seed)}.$name 通道越界", inRange(c.red) && inRange(c.green) && inRange(c.blue))
                assertFalse("#${hex(seed)}.$name 色相不是有限值", c.red.isInfinite())
            }
        }
    }

    @Test
    fun `边界 seed 不产生 NaN 不超界`() {
        // 纯黑/纯白/中灰/极暗/极亮/三原色/高饱和/单通道极小值
        val seeds = listOf(
            0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0xFF808080.toInt(), 0xFF7F7F7F.toInt(),
            0xFF010101.toInt(), 0xFFFEFEFE.toInt(), 0xFF020202.toInt(), 0xFFFCFCFC.toInt(),
            0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFF0000FF.toInt(),
            0xFFFFFF00.toInt(), 0xFF00FFFF.toInt(), 0xFFFF00FF.toInt(),
            0xFF010000.toInt(), 0xFF1D1D1D.toInt(),
        )
        for (s in seeds) {
            val p = AuroraPalette.fromSeed(Color(s))
            val all = listOf(p.seed, p.seed2, p.seed3, p.deep, p.deep2)
            for ((i, c) in all.withIndex()) {
                assertFalse("#${hex(s)} 第 $i 色 NaN", c.red.isNaN() || c.green.isNaN() || c.blue.isNaN())
                assertTrue(
                    "#${hex(s)} 第 $i 色越界 (${c.red},${c.green},${c.blue})",
                    inRange(c.red) && inRange(c.green) && inRange(c.blue),
                )
            }
        }
    }

    @Test
    fun `中性 seed 不产生假色相`() {
        // 既有 bug 回归：旧公式 deep 用 `s*1.05 coerceAtLeast 0.35`，会给纯灰/纯白/纯黑
        // 强行注入 0.35 的假饱和度 → 灰封面的「极光底」偏红（deep 曾是 #140D0D 而不是纯灰）。
        // 新公式的彩度门控让中性 seed 严格保持 R=G=B。
        val neutrals = listOf(
            0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0xFF808080.toInt(),
            0xFF7F7F7F.toInt(), 0xFF020202.toInt(), 0xFFFCFCFC.toInt(),
        )
        for (s in neutrals) {
            val p = AuroraPalette.fromSeed(Color(s))
            for ((name, c) in listOf(
                "seed2" to p.seed2, "seed3" to p.seed3, "deep" to p.deep, "deep2" to p.deep2,
            )) {
                val d = maxOf(abs(c.red - c.green), abs(c.green - c.blue), abs(c.red - c.blue))
                assertTrue(
                    "#${hex(s)}.$name 应为纯灰，实际 #${hex(c)}（通道最大差 $d）",
                    d < 0.002f, // 只容浮点误差；8bit 下同一灰度通道应完全相等
                )
            }
        }
    }

    @Test
    fun `seed 饱和度升高时 deep 饱和度单调不减`() {
        // 保真性要求：色彩上的「更深/更浓」不能非单调，否则相邻两首歌的氛围会忽浓忽淡。
        var prev = -1f
        for (s in listOf(0f, 0.02f, 0.05f, 0.10f, 0.15f, 0.20f, 0.25f, 0.30f, 0.40f, 0.50f, 0.70f, 0.90f, 1f)) {
            val seed = hsvToColor(250f, s, 0.60f)
            val deep = AuroraPalette.fromSeed(seed).deep
            val sat = hsv(deep)[1]
            assertTrue("seed s=$s 时 deep 饱和度 $sat 低于前一档 $prev", sat >= prev - 1e-6f)
            prev = sat
        }
    }

    @Test
    fun `seed 明度升高时 deep 明度单调不减`() {
        var prev = -1f
        for (v in listOf(0f, 0.05f, 0.10f, 0.20f, 0.30f, 0.40f, 0.50f, 0.60f, 0.70f, 0.80f, 0.90f, 1f)) {
            val seed = hsvToColor(250f, 0.6f, v)
            val deep = AuroraPalette.fromSeed(seed).deep
            val val_ = hsv(deep)[2]
            assertTrue("seed v=$v 时 deep 明度 $val_ 低于前一档 $prev", val_ >= prev - 1e-6f)
            prev = val_
        }
    }

    @Test
    fun `浅色模式与插值语义未被归一改造影响`() {
        // 任务硬约束：light() / 五字段语义不变。light() 只动 deep 系、seed 系原样。
        val p = AuroraPalette.fromSeed(Color(0xFF7F77DD))
        val l = p.light()
        assertEquals("light() 不得改 seed", p.seed.toArgb(), l.seed.toArgb())
        assertEquals("light() 不得改 seed2", p.seed2.toArgb(), l.seed2.toArgb())
        assertEquals("light() 不得改 seed3", p.seed3.toArgb(), l.seed3.toArgb())
        // deep 系应被拉向白（明度升高）
        assertTrue("light() 应提亮 deep", lightness(l.deep) > lightness(p.deep))
        assertTrue("light() 应提亮 deep2", lightness(l.deep2) > lightness(p.deep2))

        // lerpPalette 端点语义：fraction=0/1 取原值，中间值在两者之间
        val q = GlassPalettes.Purple
        assertEquals("lerpPalette(0) 应为 from", p.deep.toArgb(), lerpPalette(p, q, 0f).deep.toArgb())
        assertEquals("lerpPalette(1) 应为 to", q.deep.toArgb(), lerpPalette(p, q, 1f).deep.toArgb())
        val mid = lerpPalette(p, q, 0.5f).deep
        val lo = minOf(lightness(p.deep), lightness(q.deep))
        val hi = maxOf(lightness(p.deep), lightness(q.deep))
        assertTrue("lerpPalette 中点应落在两端之间", lightness(mid) in lo..hi)
    }

    @Test
    fun `fromSeed 是同输入同输出的纯函数`() {
        // 线程纪律的回归防线：不得引入可变状态/时间/随机源。
        // 若有人往 fromSeed 里塞缓存或 @Composable 依赖，这条会不稳定地红。
        val seed = Color(0xFFD85A30)
        val first = AuroraPalette.fromSeed(seed)
        repeat(50) {
            val again = AuroraPalette.fromSeed(seed)
            assertEquals(first, again)
        }
    }

    // ── 纯 Kotlin 判据工具（不依赖 Android，也不引入新依赖）──────────

    private fun inRange(x: Float) = x >= 0f && x <= 1f

    /** 诊断信息用：Color → "#RRGGBB" */
    private fun hex(c: Color): String = "#%06X".format(c.toArgb() and 0xFFFFFF)

    /** 诊断信息用：ARGB int → "#RRGGBB" */
    private fun hex(argb: Int): String = "#%06X".format(argb and 0xFFFFFF)

    /** CIELAB 的 L*（0..100）：只用于「提亮/在两端之间」这类单调性判据 */
    private fun lightness(c: Color): Double = lab(c).first

    private fun hsv(c: Color): FloatArray {
        val r = c.red; val g = c.green; val b = c.blue
        val max = maxOf(r, g, b); val min = minOf(r, g, b); val d = max - min
        val h = when {
            d == 0f -> 0f
            max == r -> 60f * (((g - b) / d) % 6f)
            max == g -> 60f * ((b - r) / d + 2f)
            else -> 60f * ((r - g) / d + 4f)
        }
        return floatArrayOf((h % 360f + 360f) % 360f, if (max == 0f) 0f else d / max, max)
    }

    private fun hsvToColor(h: Float, s: Float, v: Float): Color {
        val c = v * s
        val x = c * (1f - abs((h / 60f) % 2f - 1f))
        val m = v - c
        val (r, g, b) = when ((h / 60f).toInt()) {
            0 -> Triple(c, x, 0f); 1 -> Triple(x, c, 0f); 2 -> Triple(0f, c, x)
            3 -> Triple(0f, x, c); 4 -> Triple(x, 0f, c); else -> Triple(c, 0f, x)
        }
        return Color(r + m, g + m, b + m)
    }

    // CIELAB（D65）—— 只用来算 ΔE，与生产代码无关
    private fun lab(c: Color): Triple<Double, Double, Double> {
        fun lin(v: Float): Double {
            val x = v.toDouble()
            return if (x <= 0.04045) x / 12.92 else ((x + 0.055) / 1.055).pow(2.4)
        }
        val r = lin(c.red); val g = lin(c.green); val b = lin(c.blue)
        val x = r * 0.4124564 + g * 0.3575761 + b * 0.1804375
        val y = r * 0.2126729 + g * 0.7151522 + b * 0.0721750
        val z = r * 0.0193339 + g * 0.1191920 + b * 0.9503041
        fun f(t: Double): Double = if (t > 0.008856451679) t.pow(1.0 / 3.0) else t / 0.1284185493 + 0.1379310345
        val fx = f(x / 0.95047); val fy = f(y / 1.0); val fz = f(z / 1.08883)
        return Triple(116.0 * fy - 16.0, 500.0 * (fx - fy), 200.0 * (fy - fz))
    }

    /** CIEDE2000（kL=kC=kH=1）：比 CIE76 更贴近人眼，作为验收判据 */
    private fun deltaE2000(a: Triple<Double, Double, Double>, b: Triple<Double, Double, Double>): Double {
        val (l1, a1, b1) = a
        val (l2, a2, b2) = b
        val c1 = hypot(a1, b1); val c2 = hypot(a2, b2)
        val cb = (c1 + c2) / 2.0
        val g = if (cb > 0) 0.5 * (1 - sqrt(cb.pow(7) / (cb.pow(7) + 25.0.pow(7)))) else 0.5
        val a1p = a1 * (1 + g); val a2p = a2 * (1 + g)
        val c1p = hypot(a1p, b1); val c2p = hypot(a2p, b2)
        val h1p = Math.toDegrees(kotlin.math.atan2(b1, a1p)).let { (it + 360) % 360 }
        val h2p = Math.toDegrees(kotlin.math.atan2(b2, a2p)).let { (it + 360) % 360 }
        val dLp = l2 - l1
        val dCp = c2p - c1p
        val dhp = when {
            c1p * c2p == 0.0 -> 0.0
            else -> {
                var d = h2p - h1p
                if (d > 180) d -= 360 else if (d < -180) d += 360
                d
            }
        }
        val dHp = 2 * sqrt(c1p * c2p) * sin(Math.toRadians(dhp / 2))
        val lbp = (l1 + l2) / 2
        val cbp = (c1p + c2p) / 2
        val hbp = when {
            c1p * c2p == 0.0 -> h1p + h2p
            abs(h1p - h2p) > 180 -> if (h1p + h2p < 360) (h1p + h2p + 360) / 2 else (h1p + h2p - 360) / 2
            else -> (h1p + h2p) / 2
        }
        val t = 1 - 0.17 * cos(Math.toRadians(hbp - 30)) + 0.24 * cos(Math.toRadians(2 * hbp)) +
            0.32 * cos(Math.toRadians(3 * hbp + 6)) - 0.20 * cos(Math.toRadians(4 * hbp - 63))
        val dTheta = 30 * exp(-(((hbp - 275) / 25).pow(2)))
        val rc = if (cbp > 0) 2 * sqrt(cbp.pow(7) / (cbp.pow(7) + 25.0.pow(7))) else 0.0
        val sl = 1 + 0.015 * (lbp - 50).pow(2) / sqrt(20 + (lbp - 50).pow(2))
        val sc = 1 + 0.045 * cbp
        val sh = 1 + 0.015 * cbp * t
        val rt = -sin(Math.toRadians(2 * dTheta)) * rc
        return sqrt(
            (dLp / sl).pow(2) + (dCp / sc).pow(2) + (dHp / sh).pow(2) +
                rt * (dCp / sc) * (dHp / sh),
        )
    }
}
