package com.local.music.core.ui.theme

import androidx.compose.ui.graphics.Color
import com.local.music.core.ui.state.VisualStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/**
 * 拟物材质 token 的不变量测试。
 *
 * ## 为什么这些是「不变量」而不是「快照值」
 *
 * 拟物的具体色值一定会随设计调整而变（就像 GlassColors 的 scrim 从 0.34 调到 0.86
 * 走了四版）。所以测试断言的不是「face 必须是 #1B1C22」，而是**这套 token 必须满足的
 * 物理/可读性质**：
 *
 * - 面**必须**不透明——这是拟物与玻璃的分水岭，一旦出现 alpha < 1，「像玻璃」就压不下去；
 * - 凸起的高光方向必须成立（上沿比下沿亮）——反了就变成「凹陷」，整个视觉语言崩掉；
 * - 浅色档必须比深色档亮（否则「浅色主题」名不副实）；
 * - 文字对比度必须过 WCAG AA（4.5:1）——不透明底上弱对比会读作「看不清」而不是「次要」。
 *
 * 这些性质与色值无关，所以它们能长期守住「有人调色时不要把这套语言弄坏」。
 *
 * ## 为什么纯 JVM 可测
 * `SkeuomorphColors` 只依赖 `androidx.compose.ui.graphics.Color`（纯 Kotlin 值类），
 * 不碰 android.jar，所以 core:ui 的 JVM 单测（未开 isReturnDefaultValues）能直接跑。
 * 这也是把它设计成纯数据 + 纯派生函数的理由之一。
 */
class SkeuomorphColorsTest {

    private val dark = SkeuomorphColors.dark()
    private val light = SkeuomorphColors.light()

    // ── 不透明度：拟物的地基 ────────────────────────────────

    @Test
    fun `深色档所有贴面都不透明`() {
        assertEquals("face 必须完全不透明", 1f, dark.face.alpha, 0.001f)
        assertEquals("faceRecessed 必须完全不透明", 1f, dark.faceRecessed.alpha, 0.001f)
        assertEquals("faceRaised 必须完全不透明", 1f, dark.faceRaised.alpha, 0.001f)
    }

    @Test
    fun `浅色档所有贴面都不透明`() {
        assertEquals("face 必须完全不透明", 1f, light.face.alpha, 0.001f)
        assertEquals("faceRecessed 必须完全不透明", 1f, light.faceRecessed.alpha, 0.001f)
        assertEquals("faceRaised 必须完全不透明", 1f, light.faceRaised.alpha, 0.001f)
    }

    // ── 凸起/凹陷的物理方向 ─────────────────────────────────

    @Test
    fun `凸起是上亮下暗_吸附的是斜面而非填充`() {
        // 深色档：上沿高光比下沿更亮（光从上方来）
        assertTrue(
            "深色凸起：上沿高光应比下沿亮（bevelTop.alpha > bevelBottom.alpha）",
            dark.bevelTop.alpha > dark.bevelBottom.alpha,
        )
        assertTrue(
            "浅色凸起：上沿高光应比下沿亮",
            light.bevelTop.alpha > light.bevelBottom.alpha,
        )
    }

    @Test
    fun `凹陷是上暗下亮_方向与凸起相反`() {
        // 凹陷的判据不是「颜色更深」，而是**方向反转**：上沿是阴影（挡光），下沿受光。
        // 用亮度比较而不是 alpha：两档的 alpha 量级不同，但「上沿比下沿暗」恒成立。
        assertTrue(
            "深色凹槽：上沿（阴影）应比下沿（受光）暗",
            dark.recessTop.luminance() < dark.recessBottom.luminance(),
        )
        assertTrue(
            "深色凹槽：上沿必须是一个「暗」的颜色（不是高光）",
            dark.recessTop.luminance() < dark.face.luminance(),
        )
    }

    // ── 深浅两档的关系 ─────────────────────────────────────

    @Test
    fun `浅色档比深色档亮`() {
        assertTrue(
            "浅色 face 的亮度必须显著高于深色 face（否则「浅色主题」名不副实）",
            light.face.luminance() > dark.face.luminance() + 0.3f,
        )
        assertTrue(
            "浅色 textPrimary 必须比深色 textPrimary 暗（深底白字 / 浅底深字）",
            light.textPrimary.luminance() < dark.textPrimary.luminance(),
        )
    }

    @Test
    fun `凸起面比平面亮_吸附面比平面暗`() {
        assertTrue("深色：raised 比 face 亮", dark.faceRaised.luminance() > dark.face.luminance())
        assertTrue("深色：recessed 比 face 暗", dark.faceRecessed.luminance() < dark.face.luminance())
        assertTrue("浅色：raised 比 face 亮", light.faceRaised.luminance() > light.face.luminance())
        assertTrue("浅色：recessed 比 face 暗", light.faceRecessed.luminance() < light.face.luminance())
    }

    // ── 可读性（WCAG AA）────────────────────────────────────

    @Test
    fun `主文字在贴面上达到 AA 对比度`() {
        // 不透明底的好处：文字色与面色的对比是**确定**的，不像玻璃档那样随背后内容漂移。
        // 也正因为确定，这里可以硬性要求 AA（正文 4.5:1）。
        assertTrue(
            "深色：textPrimary 在 face 上应达 AA（4.5:1），实测 ${contrast(dark.textPrimary, dark.face)}",
            contrast(dark.textPrimary, dark.face) >= 4.5f,
        )
        assertTrue(
            "浅色：textPrimary 在 face 上应达 AA，实测 ${contrast(light.textPrimary, light.face)}",
            contrast(light.textPrimary, light.face) >= 4.5f,
        )
    }

    @Test
    fun `次级与三级文字仍可辨`() {
        // 次级/三级文字允许弱一档（它们承载的不是必须精读的信息），
        // 但仍应明显高于「看不清」的下界（3:1 是 AA 对大字号的门槛）。
        assertTrue(
            "深色 textSecondary ≥ 4.5:1（次级信息也常是正文长度），实测 ${contrast(dark.textSecondary, dark.face)}",
            contrast(dark.textSecondary, dark.face) >= 4.5f,
        )
        assertTrue(
            "深色 textTertiary ≥ 3:1（note 级），实测 ${contrast(dark.textTertiary, dark.face)}",
            contrast(dark.textTertiary, dark.face) >= 3f,
        )
        assertTrue(
            "浅色 textTertiary ≥ 3:1，实测 ${contrast(light.textTertiary, light.face)}",
            contrast(light.textTertiary, light.face) >= 3f,
        )
    }

    @Test
    fun `破坏性色在两种贴面上都跳得出来`() {
        // 破坏性色是「这一步不一样」的唯一视觉信号，必须与贴面拉开距离。
        // 门限取 3:1：它主要用于行标题/动作行（15sp Medium，接近大字号档）。
        assertTrue(
            "深色 destructive ≥ 3:1，实测 ${contrast(dark.destructive, dark.face)}",
            contrast(dark.destructive, dark.face) >= 3f,
        )
        assertTrue(
            "浅色 destructive ≥ 3:1，实测 ${contrast(light.destructive, light.face)}",
            contrast(light.destructive, light.face) >= 3f,
        )
    }

    // ── 插值 ──────────────────────────────────────────────

    @Test
    fun `插值两端精确复现原值`() {
        val atZero = lerpSkeuomorph(dark, light, 0f)
        val atOne = lerpSkeuomorph(dark, light, 1f)
        assertEquals("fraction=0 应精确等于 from", dark.face, atZero.face)
        assertEquals("fraction=0 应精确等于 from（文字色也要一致）", dark.textPrimary, atZero.textPrimary)
        assertEquals("fraction=1 应精确等于 to", light.face, atOne.face)
        assertEquals("fraction=1 应精确等于 to（文字色也要一致）", light.textPrimary, atOne.textPrimary)
    }

    @Test
    fun `插值中点不等于两端`() {
        // 防「所有字段都漏了插值」这类静默错误：中途必须真的落在两端之间。
        val mid = lerpSkeuomorph(dark, light, 0.5f)
        assertNotEquals("中点 face 不应等于深色端", dark.face, mid.face)
        assertNotEquals("中点 face 不应等于浅色端", light.face, mid.face)
        assertNotEquals("中点 shadow 也必须插值（漏字段会回落深色默认值）", dark.shadow, mid.shadow)
        assertNotEquals("中点 innerLight 也必须插值", dark.innerLight, mid.innerLight)
        assertNotEquals("中点 destructive 也必须插值", dark.destructive, mid.destructive)
        assertNotEquals("中点 recessTop 也必须插值", dark.recessTop, mid.recessTop)
        assertNotEquals("中点 recessBottom 也必须插值", dark.recessBottom, mid.recessBottom)
        assertNotEquals("中点 bevelTop 也必须插值", dark.bevelTop, mid.bevelTop)
        assertNotEquals("中点 bevelBottom 也必须插值", dark.bevelBottom, mid.bevelBottom)
        assertNotEquals("中点 faceRaised 也必须插值", dark.faceRaised, mid.faceRaised)
        assertNotEquals("中点 faceRecessed 也必须插值", dark.faceRecessed, mid.faceRecessed)
        assertNotEquals("中点 textSecondary 也必须插值", dark.textSecondary, mid.textSecondary)
        assertNotEquals("中点 textTertiary 也必须插值", dark.textTertiary, mid.textTertiary)
        // 本轮新增的三个字段同样必须插值（漏掉会回落深色默认值）
        assertNotEquals("中点 keyFace 也必须插值", dark.keyFace, mid.keyFace)
        assertNotEquals("中点 wellFace 也必须插值", dark.wellFace, mid.wellFace)
        assertNotEquals("中点 separator 也必须插值", dark.separator, mid.separator)
    }

    // ── 材质角色（2026-11 重设计）─────────────────────────────

    @Test
    fun `按键面比机身面更有实体感`() {
        // 「按键」与「机身」必须真的不同——这是本轮重设计的核心：
        // 上一版所有面共用 faceRaised，播放键和一张设置组卡长得一样厚。
        assertTrue(
            "深色：keyFace 应比 face 亮（按键从机身上抬起）",
            dark.keyFace.luminance() > dark.face.luminance(),
        )
        assertTrue(
            "浅色：keyFace 应比 face 亮（浅色机身按键靠更白表达抬起）",
            light.keyFace.luminance() > light.face.luminance(),
        )
    }

    @Test
    fun `凹槽面比机身面更暗`() {
        // 凹槽是「装东西的地方」，光进不去，必须比机身暗
        assertTrue(
            "深色：wellFace 应比 face 暗",
            dark.wellFace.luminance() < dark.face.luminance(),
        )
        assertTrue(
            "浅色：wellFace 应比 face 暗",
            light.wellFace.luminance() < light.face.luminance(),
        )
    }

    @Test
    fun `机身面板的内高光必须极弱`() {
        // 上一版内高光 6%（深）/50%（浅），每块面都像塑料注塑件。
        // 新方案机身是平放的板子，面上不该有强烈的自上而下渐变。
        assertTrue(
            "深色 innerLight 应 ≤ 6%（当前 ${dark.innerLight.alpha}）",
            dark.innerLight.alpha <= 0.06f,
        )
        assertTrue(
            "浅色 innerLight 应 ≤ 40%（当前 ${light.innerLight.alpha}）",
            light.innerLight.alpha <= 0.40f,
        )
    }

    @Test
    fun `分隔线是可辨但极弱的一层`() {
        // 分隔线是「最弱的一层」：它只负责分组，不能抢过任何内容。
        // 判据不是亮度差（那取决于机身明度），而是**对比度区间**：
        // - 下界 > 1.05:1 —— 必须真的看得见，否则等于没画；
        // - 上界 < 3:1 —— 必须远低于正文（正文 ≥ 4.5:1），否则会与内容争层级。
        val darkSep = contrast(dark.separator, dark.face)
        val lightSep = contrast(light.separator, light.face)
        assertTrue("深色分隔线必须可辨（实测 $darkSep）", darkSep > 1.05f)
        assertTrue("深色分隔线不能抢内容（实测 $darkSep）", darkSep < 3f)
        assertTrue("浅色分隔线必须可辨（实测 $lightSep）", lightSep > 1.05f)
        assertTrue("浅色分隔线不能抢内容（实测 $lightSep）", lightSep < 3f)
    }

    @Test
    fun `材质角色枚举覆盖四种语义且缺一不可`() {
        // 四个角色各自对应一种物理关系，删掉任何一个都会让某类表面无家可归
        assertEquals("必须有四个角色", 4, SkeuoRole.entries.size)
        assertEquals(
            "角色集合必须恰好是 机身/按键/凹槽/封套",
            listOf(SkeuoRole.Body, SkeuoRole.Key, SkeuoRole.Well, SkeuoRole.Sleeve),
            SkeuoRole.entries.toList(),
        )
    }

    // ── VisualStyle 枚举 ───────────────────────────────────

    @Test
    fun `视觉风格枚举有两个成员且各带标签`() {
        assertEquals("设计上只有两种材质语言", 2, VisualStyle.entries.size)
        assertTrue("每个档位都必须有可读标签", VisualStyle.entries.all { it.label.isNotBlank() })
        assertEquals("默认档必须是液态玻璃（老用户升级观感不变）", VisualStyle.Glass, VisualStyle.entries.first())
    }

    // ── WCAG 对比度工具 ─────────────────────────────────────

    /** WCAG 2.x 相对亮度（sRGB 线性化），与 Compose 的 `Color.luminance()` 同一套公式 */
    private fun Color.luminance(): Float {
        fun channel(c: Float): Float =
            if (c <= 0.03928f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)
        return 0.2126f * channel(red) + 0.7152f * channel(green) + 0.0722f * channel(blue)
    }

    /** Float 的幂：`kotlin.math.pow` 是**接收者扩展**（`Float.pow(Float)`），
     *  直接 `kotlin.math.pow(x, y)` 调不到——必须 import 后以方法形式调用。 */
    private fun Float.pow(exp: Float): Float = this.toDouble().pow(exp.toDouble()).toFloat()

    /** WCAG 对比度 */
    private fun contrast(a: Color, b: Color): Float {
        val la = a.luminance()
        val lb = b.luminance()
        val hi = maxOf(la, lb)
        val lo = minOf(la, lb)
        return (hi + 0.05f) / (lo + 0.05f)
    }
}
