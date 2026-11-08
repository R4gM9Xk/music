package com.local.music.core.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * 液态玻璃流 · 设计 token
 * 一比一映射 player-ui-preview-glass.html 的 :root 变量与规范面板。
 */

// ── 封面取色（极光调色板）──────────────────────────────────
@Immutable
data class AuroraPalette(
    val seed: Color,    // 主色
    val seed2: Color,   // 强调色（辉光、进度渐变起点）
    val seed3: Color,   // 浅强调（高亮文字、tab 文字）
    val deep: Color,    // 极光底色（上）
    val deep2: Color,   // 极光底色（下）
) {
    /** 浅色模式底色：deep 系拉到高明度低饱和（取色仍生效，只是变浅色极光） */
    fun light(): AuroraPalette = copy(
        deep = lerp(deep, Color.White, 0.86f),
        deep2 = lerp(deep2, Color.White, 0.92f),
    )

    companion object {
        /**
         * **全 App 唯一的取色派生函数**（视觉审计 A3 归一）。
         *
         * 三条路径必须都汇到这里，否则同一个色相会有三种深浅/饱和：
         * 1. 品牌预设 [GlassPalettes] —— 保留手调值，但已降级为**校准基准**（见 [GlassPalettes]）；
         * 2. 用户手选主题色（AppState 读 customSeedArgb）→ [fromSeed]；
         * 3. 封面取色 `MediaLibrary.paletteFromBitmap` —— 它现在**只负责从位图提一个 seed 色**，
         *    派生同样调 [fromSeed]（旧实现自带一套 0.7/1.25/0.45/1.5/0.85/0.38/0.8/0.13 系数，
         *    与这里完全不同，切歌时氛围会在两套公式间跳）。
         *
         * ## 系数是怎么来的（不要凭手感改）
         * 拿 4 个手调预设（Purple/Teal/Coral/Blue，即 [GlassPalettes] 的四个成员）的
         * **4 个派生字段** seed2/seed3/deep/deep2 作拟合目标 = **16 个色点**
         * （seed 本身不参与：它是输入，恒等映射，没有可拟合的自由度）。
         * 最小二乘目标函数是 CIEDE2000，ΔE 口径写死如下，换口径数字会变：
         * - RGB→Lab：sRGB 分量先做标准 IEC 61966-2-1 逆伽马（阈 0.04045），
         *   再乘 D65 标准矩阵（X 行 0.4124564/0.3575761/0.1804375，Y 行 0.2126729/0.7151522/0.0721750，
         *   Z 行 0.0193339/0.1191920/0.9503041），白点 (0.95047, 1.0, 1.08883)，f(t) 分段阈 (6/29)³。
         * - ΔE00：kL=kC=kH=1 的标准 CIEDE2000（含 RT 旋转项与 h̄ 修正）。
         * - **RMS 是这 16 个 ΔE00 的均方根**（分母 16，不是 15）。
         *
         * 对齐前旧系数：RMS ΔE00 = 5.05、最差 9.04（Coral.seed3）——肉眼能看出
         * 「手选珊瑚」和「封面恰好是珊瑚」是两种橙。拟合后：RMS = 1.26、最差 2.50（Teal.deep），
         * 全部低于「常规观看法可辨」阈值（ΔE00 ≈ 3）。改系数前先跑 AuroraPaletteTest 里的基准表。
         *
         * ## 为什么用 HSV 而不是 OKLCH
         * 手调预设本身就是按 HSV 手感调出来的（seed2/seed3 提明度降饱和、deep 深压提饱和），
         * 用 HSV 拟合残差最小；OKLCH 虽然感知更均匀，但要逼近手调值反而要多一层特例。
         *
         * ## 为什么自己实现 RGB↔HSV 而不用 android.graphics.Color
         * 两个硬理由：
         * 1. 这是纯粹的配色数学，**不该依赖 Android 平台**——[fromSeed] 因此是可在任意线程调用的
         *    纯函数（扫描/解码线程会调它），也不能被 `@Composable`/`Context` 污染。
         * 2. `core:ui` 的 JVM 单测没有开 `isReturnDefaultValues`，而 android.jar 里的方法在单测中
         *    会抛 "not mocked"。自己实现后单测能直接跑，不靠 Robolectric、不加依赖。
         *    （反过来说，`isReturnDefaultValues = true` 在这里是**有害**的：它会让 `colorToHSV`
         *    静默返回全 0 数组，于是所有 seed 都变成纯黑——错误被吞掉，比抛异常更难查。）
         * 算法与 `android.graphics.Color` 等价：h ∈ [0,360)、s/v ∈ [0,1]，
         * 且中间量全程保持 float，**不做 8 bit 量化**（量化只发生在最终 `toArgb()` 时），
         * 这样连续取色得到的渐变不会出现肉眼可见的台阶。
         *
         * @return 五色阶梯；[seed] 原样保留（调用方传什么就是什么，不做二次量化，
         *         避免「手选色」与「封面取色」因转码误差产生肉眼可见的色偏）
         */
        fun fromSeed(seed: Color): AuroraPalette {
            val hsv = seed.toHsv()
            val h = hsv[0]
            val s = hsv[1]
            val v = hsv[2]

            // 彩度可信度门控 g：这不是调参顺手加的，是**既有 bug 的修复**。
            // 改前：deep 饱和度写成 `(s*1.05f).coerceAtLeast(0.35f)`，`coerceAtLeast` 的下限对
            // 中性色是错的——灰/白/黑封面的 swatch s≈0，会被强行抬到 0.35 饱和，再配上
            // deep 的低明度（v*0.30）就得到一块**暗红色**氛围底（实测灰 seed 的 deep=#140D0D）。
            // 灰封面本该是中性灰极光，却被染上一层假色相，这在扫描无封面歌曲时最容易被看到。
            // 修法：把「往 1 推色度」的量按 seed 自身彩度衰减——g 在 s≤0.05 时为 0（完全不注入），
            // s≥0.25 时为 1（常规彩色 seed 退化成纯线性模型，不影响拟合）。
            // 中间区间线性过渡，保证 s 连续变化时 deep 不会跳变。
            val g = ((s - 0.05f) / 0.20f).coerceIn(0f, 1f)

            // seed3（浅强调）：提亮最多、降饱和最多。浅色模式下要压得住白底，所以不把饱和归零。
            val seed3 = hsvColor(h, s * 0.36f, v + (1f - v) * 0.70f)
            // seed2（强调）：提亮一档、轻降饱和。
            val seed2 = hsvColor(h, s * 0.60f, v + (1f - v) * 0.48f)
            // deep（极光底·上）：往深压到 ~0.26 明度，饱和度往 1 推（氛围感来自高饱和暗色）。
            // 用 s+(1-s)*k 而不是旧的 s*k：**这是有意的**，因为手调预设的深色底全都比 seed 更饱和
            // （Teal seed s=0.816 → deep s=0.870；Coral 0.778→0.869；Blue 0.751→0.844；
            // Purple 0.462→0.622）。s+(1-s)*0.34 正好是「按剩余空间的比例补饱和」，
            // 在 s=0.5 处给到 0.67（旧式 1.05s 只有 0.53，深底会偏灰、氛围起不来），
            // 在 s=1 处封顶为 1（纯饱和色不再继续推），两端都被手调值锚住。
            val deep = hsvColor(h, s + (1f - s) * 0.34f * g, (v * 0.30f).coerceIn(0.08f, 0.45f))
            // deep2（极光底·下）：再暗一档，接近黑但保留明显色相；同 deep 的补饱和逻辑，但补得更多
            // （手调 Teal deep2 s=0.800 → 0.812、Coral 0.885，deep2 是整屏最浓的一块）。
            val deep2 = hsvColor(h, s + (1f - s) * 0.67f * g, (v * 0.125f).coerceIn(0.03f, 0.20f))

            return AuroraPalette(seed = seed, seed2 = seed2, seed3 = seed3, deep = deep, deep2 = deep2)
        }

        /** 取 seed 的 HSV（h ∈ [0,360)、s/v ∈ [0,1]），纯 Kotlin，见 [fromSeed] 的说明 */
        private fun Color.toHsv(): FloatArray {
            val r = red
            val g = green
            val b = blue
            val max = maxOf(r, g, b)
            val min = minOf(r, g, b)
            val d = max - min
            val h = when {
                d == 0f -> 0f
                max == r -> 60f * (((g - b) / d) % 6f)
                max == g -> 60f * ((b - r) / d + 2f)
                else -> 60f * ((r - g) / d + 4f)
            }
            // `%` 在 Kotlin 里对负数保留符号，补 +360 再取模把 h 归回 [0,360)
            return floatArrayOf((h % 360f + 360f) % 360f, if (max == 0f) 0f else d / max, max)
        }

        /** 按 HSV 造色（h ∈ [0,360)，s/v 自动收敛到 [0,1]），与平台 HSVToColor 等价 */
        private fun hsvColor(h: Float, s: Float, v: Float): Color {
            val ss = s.coerceIn(0f, 1f)
            val vv = v.coerceIn(0f, 1f)
            val hh = (h % 360f + 360f) % 360f
            val c = vv * ss
            val x = c * (1f - kotlin.math.abs((hh / 60f) % 2f - 1f))
            val m = vv - c
            val (r, g, b) = when ((hh / 60f).toInt()) {
                0 -> Triple(c, x, 0f)
                1 -> Triple(x, c, 0f)
                2 -> Triple(0f, c, x)
                3 -> Triple(0f, x, c)
                4 -> Triple(x, 0f, c)
                else -> Triple(c, 0f, x)
            }
            return Color(r + m, g + m, b + m)
        }

        /** 预设色板（F2 面板的 10 色）：覆盖常见偏好，用户也可用滑杆自定 */
        val PresetSeeds = listOf(
            Color(0xFF7F77DD), // 靛紫（品牌色）
            Color(0xFF378ADD), // 蓝
            Color(0xFF1D9E75), // 青绿
            Color(0xFF57A94A), // 草绿
            Color(0xFFE0A800), // 琥珀
            Color(0xFFD85A30), // 珊瑚橙
            Color(0xFFD64570), // 玫红
            Color(0xFF9B51E0), // 紫罗兰
            Color(0xFF8D6E63), // 棕
            Color(0xFF5A6B7C), // 石板灰蓝
        )
    }
}

object GlassPalettes {
    /**
     * 手调预设 —— **校准基准**，不是第二套派生公式（视觉审计 A3）。
     *
     * ## 为什么保留而不是删掉
     * 这四个五元组是品牌观感的锚点（尤其 Purple #7F77DD 是用户预期中的靛紫），
     * 而且 [All] 被 ArtStyle 演示封面直接引用。删掉它们能「消灭三套公式」，
     * 但会同时丢掉「手调值应该长什么样」这条唯一的验收依据。
     *
     * ## 为什么不再作为运行时派生路径
     * 它们的取值与 [AuroraPalette.fromSeed] 对同一 seed 的输出并不相等（那正是 A3 的病根）：
     * fromSeed 已被拟合到与下表 **ΔE00 ≤ 2.63**（16 个色点 RMS 1.28），
     * 也就是「用户手选靛紫」与「品牌靛紫」现在观感一致，不必再维护两套系数。
     *
     * ## 结论（详见交付报告）：**不保留手调覆盖表**
     * 调优后所有预设字段都落在 ΔE00 ≤ 2.63 内，低于常规观察可辨阈值，
     * 因此没有「某个预设必须特判」的情况——给某色开特例反而会重新引入公式跳变。
     *
     * 唯一用途：单测里的偏差基准（`AuroraPaletteTest.基准`）。运行时若需
     * 「品牌靛紫」这一确切值，直接引用 [Purple]，不要新增派生分支。
     */
    val Purple = AuroraPalette(
        seed = Color(0xFF7F77DD), seed2 = Color(0xFFAFA9EC), seed3 = Color(0xFFCECBF6),
        deep = Color(0xFF241C4A), deep2 = Color(0xFF0E0A20),
    )
    val Teal = AuroraPalette(
        seed = Color(0xFF1D9E75), seed2 = Color(0xFF5DCAA5), seed3 = Color(0xFF9FE1CB),
        deep = Color(0xFF062E26), deep2 = Color(0xFF04140F),
    )
    val Coral = AuroraPalette(
        seed = Color(0xFFD85A30), seed2 = Color(0xFFF0997B), seed3 = Color(0xFFF5C4B3),
        deep = Color(0xFF3D1408), deep2 = Color(0xFF1A0803),
    )
    val Blue = AuroraPalette(
        seed = Color(0xFF378ADD), seed2 = Color(0xFF85B7EB), seed3 = Color(0xFFB5D4F4),
        deep = Color(0xFF0A2540), deep2 = Color(0xFF04121F),
    )
    val All = listOf(Purple, Teal, Coral, Blue)
}

// ── 字体 token（UI 审计第 2 轮确立）───────────────────────
/**
 * 7 级字号阶梯（sp）。阶梯外禁止 0.5sp 级差（旧 10.5/11.5/12.5/13.5/14 已收敛）。
 * 行高规则：多行文本必须显式声明行高；单行（maxLines=1）可省略。
 * 行高比例 1.27–1.4：正文 1.38–1.4 保中文可读，标题 1.27–1.3 收紧行间。
 * 已登记例外：播放页歌词 PlayerScreen.kt:719 用 lerp(1.45×) 动画行高，属设计意图。
 */
object GlassType {
    const val Display = 30f     // 页面大标题
    const val Title = 20f       // 二级页大标题
    const val Heading = 16f     // 播放页歌名 / 区块标题
    const val BodyEmph = 15f    // 列表行主标题 / 输入与搜索文字
    const val Body = 13f        // 正文 / 按钮 / 设置行
    const val Caption = 12f     // 列表副标题 / meta
    const val Micro = 11f       // 时间戳 / 角标 / 来源行

    const val LHDisplay = 38f
    const val LHTitle = 26f
    const val LHHeading = 22f
    const val LHBodyEmph = 21f
    const val LHBody = 18f
    const val LHCaption = 16f
    const val LHMicro = 14f
}

/**
 * 排版 token 的**可下发形态**：字号 + 字重 + 行高 + 字距打包成一个 [TextStyle]。
 *
 * **为什么必须存在这一层**（视觉审计 A1，2026-11-08）：
 * 上面 [GlassType] 只有一堆 Float 常量，调用点要写
 * `fontSize = 15.sp, fontWeight = FontWeight.Medium, lineHeight = 21.sp`——
 * 三处手写、三处可漏。事实是**全仓 63 处 fontSize 全是裸字面量，GlassType 零引用**，
 * 于是「同语义同 token」这条规则在代码里没有任何着力点，值只能靠人工对齐（然后漂移）。
 *
 * 打包成 TextStyle 之后，调用点写 `style = GlassText.Row` 就同时得到字号/字重/行高，
 * 一致性由**构造**保证而不再由**纪律**保证。新增排版一律从这里取；
 * 改造既有调用点时若发现 `style = GlassText.X, fontSize = Y.sp` 并存，说明 X 选错了，
 * 应补一个正确的 token 而不是就地覆盖。
 *
 * 命名口径与 token-baseline 的 7 级阶梯一一对应：
 * - Display / Title / Heading / BodyEmph(=Row) / Body / Caption / Micro
 * - 带 `Medium`/`SemiBold` 后缀者为同字号的强调档（用于主标题、选中态）
 * - [Numeric] 是**等宽数字**专用档：时间/倒计时必须用它，否则每秒变化时字宽跳动
 *
 * 已登记例外：播放页歌词行的字号/字重/行高是连续 lerp 动画（PlayerScreen 的 LyricLineRow），
 * 无法套静态 TextStyle，属设计意图，不在此列。
 */
object GlassText {
    /** 页面大标题（30 Bold 38）：CJK 不加负字距——负字距是拉丁排版习惯，中文只会挤字形 */
    val Display = TextStyle(
        fontSize = GlassType.Display.sp,
        fontWeight = FontWeight.Bold,
        lineHeight = GlassType.LHDisplay.sp,
        letterSpacing = 0.sp,
    )

    /** 二级页/详情大标题（20 Bold 26） */
    val Title = TextStyle(
        fontSize = GlassType.Title.sp,
        fontWeight = FontWeight.Bold,
        lineHeight = GlassType.LHTitle.sp,
        letterSpacing = 0.sp,
    )

    /** 详情页顶栏页名（16 Bold 22）：与下方 Hero 标题拉开一档，又不至于和大标题同重 */
    val TitleCompact = TextStyle(
        fontSize = GlassType.Heading.sp,
        fontWeight = FontWeight.Bold,
        lineHeight = GlassType.LHHeading.sp,
        letterSpacing = 0.sp,
    )

    /** 区块/面板标题、播放页歌名（16 SemiBold 22） */
    val Heading = TextStyle(
        fontSize = GlassType.Heading.sp,
        fontWeight = FontWeight.SemiBold,
        lineHeight = GlassType.LHHeading.sp,
        letterSpacing = 0.sp,
    )

    /** 列表行主标题（15 Medium 21）——全 App 出现密度最高的文本档 */
    val Row = TextStyle(
        fontSize = GlassType.BodyEmph.sp,
        fontWeight = FontWeight.Medium,
        lineHeight = GlassType.LHBodyEmph.sp,
        letterSpacing = 0.sp,
    )

    /** 列表行主标题·强调（15 SemiBold 21）：常驻 chrome 的歌名、面板动作项 */
    val RowStrong = TextStyle(
        fontSize = GlassType.BodyEmph.sp,
        fontWeight = FontWeight.SemiBold,
        lineHeight = GlassType.LHBodyEmph.sp,
        letterSpacing = 0.sp,
    )

    /** 正文/按钮/设置行（13 Normal 18） */
    val Body = TextStyle(
        fontSize = GlassType.Body.sp,
        fontWeight = FontWeight.Normal,
        lineHeight = GlassType.LHBody.sp,
        letterSpacing = 0.sp,
    )

    /** 正文·强调（13 Medium 18）：按钮文字、可点的强调动作（如搜索「取消」「全部 N 张」） */
    val BodyMedium = TextStyle(
        fontSize = GlassType.Body.sp,
        fontWeight = FontWeight.Medium,
        lineHeight = GlassType.LHBody.sp,
        letterSpacing = 0.sp,
    )

    /** 列表副标题 / meta（12 Normal 16） */
    val Caption = TextStyle(
        fontSize = GlassType.Caption.sp,
        fontWeight = FontWeight.Normal,
        lineHeight = GlassType.LHCaption.sp,
        letterSpacing = 0.sp,
    )

    /** 副标题·强调（12 Medium 16）：尾部「已选」「已添加」等状态标记 */
    val CaptionMedium = TextStyle(
        fontSize = GlassType.Caption.sp,
        fontWeight = FontWeight.Medium,
        lineHeight = GlassType.LHCaption.sp,
        letterSpacing = 0.sp,
    )

    /** 时间戳/角标/来源行（11 Normal 14） */
    val Micro = TextStyle(
        fontSize = GlassType.Micro.sp,
        fontWeight = FontWeight.Normal,
        lineHeight = GlassType.LHMicro.sp,
        letterSpacing = 0.sp,
    )

    /**
     * 时间/倒计时/进度数字（11 Normal 14 **等宽数字**）。
     *
     * `fontFeatureSettings = "tnum"` 让每个数字占同一字宽：已播时长每秒递增时
     * 文本宽度不再抖动（比例数字下 "1" 比 "8" 窄，逐秒跳变肉眼可见），
     * 列表右对齐的时长列也随之齐整。视觉审计 B4。
     */
    val Numeric = TextStyle(
        fontSize = GlassType.Micro.sp,
        fontWeight = FontWeight.Normal,
        lineHeight = GlassType.LHMicro.sp,
        letterSpacing = 0.sp,
        fontFeatureSettings = "tnum",
    )

    /** 分组小标题（12 Normal 16 + 0.3 字距）：全大写式的小标签，字距是它唯一的层级信号 */
    val SectionLabel = TextStyle(
        fontSize = GlassType.Caption.sp,
        fontWeight = FontWeight.Normal,
        lineHeight = GlassType.LHCaption.sp,
        letterSpacing = 0.3.sp,
    )

    /** 分组小标题·微档（11 Normal 14 + 0.3 字距）：面板内部段头 */
    val SectionLabelMicro = TextStyle(
        fontSize = GlassType.Micro.sp,
        fontWeight = FontWeight.Normal,
        lineHeight = GlassType.LHMicro.sp,
        letterSpacing = 0.3.sp,
    )
}


// ── 圆角 token（UI 审计第 3 轮确立）───────────────────────
/**
 * 6 级圆角刻度（dp）。阶梯外禁止 8/11/12/16/20/22 等中间值。
 * 索引方式：R.Thumb10 / R.Card18 / R.Player28 / R.Full。
 * 微圆角（1–6dp）仅限功能性细条（进度轨道、指示条），不计入刻度。
 * 已登记例外：播放页封面飞行过渡用 lerp 插值形状（PlayerFlightOverlay），属设计意图。
 */
object R {
    const val Thumb10 = 10      // 40–48dp 封面缩略图（全 app 唯一缩略图圆角）
    const val Card14 = 14       // 列表行卡片、面板内嵌条目
    const val Card18 = 18       // 标准卡片容器（横幅卡、封面卡）
    const val Card24 = 24       // 大封面（歌单详情 132dp）
    const val Player28 = 28     // 播放页大面板 / GlassSurface 默认
    const val Full = 50         // 胶囊（搜索框 44dp 高 → 22dp = 半高）

    /**
     * 嵌套圆角的**共心推导**（视觉审计 C4）：外容器圆角 Rout、内边距 pad 时，
     * 内侧子元素要用的圆角 = Rout − pad。
     *
     * 为什么这条几何律值得单独成 token：圆角不是「每块各自好看」的装饰，而是一条
     * **等距曲线**。外圆角 28 + 内边距 12 而内圆角仍写 10 时，内外两条弧线在四角
     * 平行关系断掉——肉眼说不出哪里错，但会觉得「这角有点脏」。Apple 系控件的
     * 高级感很大一部分就来自这条不写在任何设计稿上的规律。
     *
     * 已知历史点：迷你条外圆角 28、内边距 12、封面圆角 10（应为 16）。
     * 注意推导结果可能落到刻度外（如 16 不在 R 刻度上）——**此处以几何正确优先**，
     * 刻度服务于一致性，而一致性本身包含嵌套关系。结果下限 4dp 防退化。
     */
    fun nested(outer: Int, padding: Int): Int = (outer - padding).coerceAtLeast(4)
}

// ── 间距 token（UI 审计第 6 轮确立，第 7 轮接线）────────────────
/**
 * 7 档间距刻度（dp）。R1 曾误记页面边距为 24dp，精确扫描证实为 20dp（23 处一致）。
 *
 * ## 为什么这个 object 到第 7 轮才真正被引用（视觉审计 A1 / C3）
 *
 * 第 6 轮把刻度定义出来之后，全仓对它的引用数长期是 **0**——117 处间距字面量各自
 * 裸写，于是同族缝隙在同一屏里出现 12/13/14 三种值，肉眼说不出哪里错但读作「手抖」。
 * 定义 token 不等于使用 token：只要调用点还能裸写 dp，刻度就只是文档。本轮把调用点
 * 逐处接上来，并**顺手把刻度外值归位**——这一步会真实改变几个 dp 的观感，
 * 是「一致性优于局部手感」的一次有意取舍。
 *
 * ## 什么算间距、什么不算（这条边界比刻度本身更容易搞错）
 *
 * 入刻度：`padding`、`Spacer` 的宽高、`Arrangement.spacedBy`、区块之间的留白。
 * 不入刻度（保持裸值）：
 * - **组件几何**——`.size/.width/.height` 给定的控件宽高、图标尺寸、头像边长。
 *   这些是「这个元件多大」而非「元件之间隔多远」，混进刻度会让刻度失去语义。
 * - **微缝豁免 0–6dp**——图标与标签之间的光学缝、胶囊内 padding（同 R3 微圆角逻辑）。
 *   这类值服务于单个元件内部的对齐，跨元件复用没有意义。
 * - **几何推导量**——封面边长上限、飞行端点偏移等由布局算式推出的中间值。
 *
 * ## 刻度外值的归位口径（本轮统一裁定的唯一标准）
 *
 * **就近取刻度；正中间时取「让留白不缩小」的那一档。** 宁可松不可挤：
 * 间距收紧会让元素显得拥挤，而略松只是呼吸感多一点，后者在极简方向里是安全方向。
 *
 * | 原值 | 归入 | 理由 |
 * |------|------|------|
 * | 7 | 8 | 就近向上 |
 * | 9 | 8 | 就近向下（9−8=1 < 12−9=3） |
 * | 10 | 12 | 正中间偏上（10−8=2 > 12−10=2 时取不缩小的一档） |
 * | 13 | 12 | 就近向下 |
 * | 14 | 12（区块大留白处 16） | 就近向上；语义判断优先 |
 * | 15 | 16 | 就近向上 |
 * | 18 | 16（区块大留白处 20） | 语义判断：面板外留白取 20 |
 * | 28 / 30 / 34 | 24 / 32 / 32 | 就近 |
 *
 * 归位点若偏离「直觉值」，调用处写一行注释说明为何选这档——归位的判断依据不能只留在
 * 这里，否则下一个人看到 `16.dp` 会以为是随手写的。
 */
object S {
    const val Gap4 = 4
    const val Gap8 = 8
    const val Gap12 = 12
    const val Gap16 = 16
    const val Gap20 = 20
    const val Gap24 = 24
    const val Gap32 = 32

    /**
     * 页面水平边距：**等于** [Gap20]，不是第 8 档刻度。
     *
     * 保留这个别名是因为它承载语义（「这一屏的内容左缘」），23 处调用点读作
     * `S.PageMargin` 比 `S.Gap20` 更能说明「为什么是 20 而不是随手选的值」。
     * 但它必须**引用** Gap20 而不是另写一个 20——两份 20 就是两个真相，
     * 将来调刻度时其中一个必被漏掉。
     */
    const val PageMargin = Gap20
}

// ── 玻璃 token ────────────────────────────────────────────
@Immutable
data class GlassColors(
    val hi: Color = Color.White.copy(alpha = 0.16f),   // 高层玻璃：迷你条
    val md: Color = Color.White.copy(alpha = 0.10f),   // 中层：控制台 / 导航 / 卡片
    val lo: Color = Color.White.copy(alpha = 0.07f),   // 低层：圆钮 / 芯片 / 卡片底
    val stroke: Color = Color.White.copy(alpha = 0.28f),       // 高光描边
    val strokeSoft: Color = Color.White.copy(alpha = 0.14f),   // 常规描边
    val topLine: Color = Color.White.copy(alpha = 0.55f),      // 顶部亮线
    val textPrimary: Color = Color.White,
    val textSecondary: Color = Color.White.copy(alpha = 0.60f),
    val textTertiary: Color = Color.White.copy(alpha = 0.50f),
    // R-C1（UI 审计第 4 轮收窄）：textFaint 仅限 ≥13sp 文字或纯装饰图形；
    // ≤12sp 的可读文字与 16dp 导航图标一律用 textTertiary（0.50）。
    // 0.42 白在深色玻璃底上对比 ≈3.5:1，低于小字可辨阈值。
    val textFaint: Color = Color.White.copy(alpha = 0.42f),
    val lyricFar: Color = Color.White.copy(alpha = 0.34f),
    val lyricAdjacent: Color = Color.White.copy(alpha = 0.55f),
    /**
     * 破坏性动作色（视觉审计 A4）：删除/清空/放弃快照这类**不可撤销**操作的唯一视觉信号。
     *
     * 为什么需要它：改前「确认删除」（ActionsSheet 删除确认行）与「全部播放」视觉完全同级——
     * 都吃 textPrimary。破坏性操作没有任何可感知的层级差，等于让用户凭文案自己小心。
     * 成熟系统都用一枚克制的警示色表达「这一步不一样」，而不是把整行染红。
     *
     * 取**低饱和暖红**而不是 Material error 的纯红：本 App 底色是极光暗紫/暗青且带大量
     * 半透明玻璃，纯红在高饱和暗底上会「跳出来」变成廉价告警条；降饱和后它仍然是
     * 唯一的暖色语义，但不与极光取色争艳。浅色档同理用深一档的砖红保证白底可读。
     */
    val destructive: Color = Color(0xFFE4867C),
    /**
     * 按压态底色（视觉审计 A6）：全 App `indication = null`，按下一个列表行/动作行时
     * **没有任何底色变化**，只剩一个 0.98 的行级缩放——肉眼几乎不可见，等于零反馈。
     *
     * 为什么用「底色微亮」而不是继续加大缩放：整行缩放会让文字/封面一起位移，
     * 在长列表里是噪声；而一层极淡的浮起底色是静止的、只改明度的信号，
     * 观感更接近系统原生（ColorOS/Android 的 ripple 本质也是明度层）。
     * alpha 取 0.06 量级：刚好看得见，且叠在玻璃上不会破坏通透。
     */
    val pressedOverlay: Color = Color.White.copy(alpha = 0.06f),
    /**
     * chrome 层遮罩（F3 / G2 透字修复）：**只**给迷你播放条与底部导航栏用，
     * 其余玻璃面板（控制台/卡片/面板）不传，保持原观感。
     *
     * 为什么需要它：haze 真机实测**没有失效**（透字笔画边缘过渡 14–29px，
     * 清晰文字仅 2px —— 模糊确实在起作用），但「有模糊 ≠ 不可辨」。
     * 滚动曲库时列表行文字滑进 chrome 后方，经 blur+tint 后仍保留
     * 迷你条 4.3:1 / 导航栏 11.4:1 的局部对比（自身文字 8.7 / 11.8），
     * 幽灵字达到自身文字的 50%–96%，与 chrome 文案双重显影。
     *
     * 为什么是「与主题同向」而不是单纯提亮：叠一层与背景同向的低 alpha 底，
     * 会把「幽灵字笔画」和「玻璃底色」**一起**推向该方向的极值；而 WCAG
     * 对比度公式里的 +0.05 常数项在两端会压缩比值 —— 深色模式双双逼近黑、
     * 浅色模式双双逼近白，透字对比因此塌陷，而 chrome 自身文字画在遮罩**之上**、
     * 完全不受衰减（反而因底色与文字色差拉大而略微更清晰）。
     *
     * 为什么用深底而不是深色玻璃 fill：fill 是白色系（hi=白16%），叠白只会
     * 提亮背景、把浅色幽灵字衬得更明显 —— 深色模式必须往黑的方向压。
     *
     * ## 为什么 alpha 取这么高（0.80 / 0.74）
     *
     * 这一层是**唯一可靠的兜底**，不能指望 blur 一定生效：haze 的 blur 依赖
     * RenderEffect（Android 12+，且需硬件加速 GPU）。软件渲染 / 无 RenderEffect 的
     * 设备上它会**静默退化为纯 tint**（零模糊）—— 本工程历史审计（G2）就误判成
     * 「haze 链失效」，而实测在模拟器（`ro.hardware.egl=emulation`）上确实退化：
     * 透字裁剪后 grad_p97 仍有 55.9–60.0，与清晰文字同量级，字形完全可辨。
     *
     * 也就是说：blur 生效时它是「锦上添花」，不生效时 scrim 必须**独立**把透字压到
     * 不可辨。实测走了四步才收敛（模拟器 SwiftShader 环境，blur 全程失效）：
     *   - a=0.34（首版）→ 透字 grad_p97 仍 55.9–60.0，肉眼完全可读 → 失败
     *   - a=0.62（二版）→ grad_p97 降到 42.5（迷你条）/ 29.7（导航栏）→ 变暗但仍可辨 → 失败
     *   - a=0.90（三版）→ 透字对比降到 1.32:1，彻底不可辨 ✅ 但通透度只剩 10%，
     *     迷你条/导航栏这两块最高频可见的玻璃名存实亡 → 观感过度
     *   - a=0.80（本版）→ 见下方推算，两个目标同时成立
     *
     * ### 透字残值的完整算式（2026-11-08 定量结案，替代旧版的单点线性推导）
     *
     * 旧版用的是「残值 = 240×(1−a) ≤ 52 → a ≥ 0.78」。这条式子**方向对但低估了**：
     * 透字不是一个标量，而是一条从「行内无字处」到「笔画中心」的**调制带**。
     * 正确的坐标系是把透字拆成「局部均值 M」+「半幅 h」（峰 = M+h，谷 = M−h）：
     *
     * ```
     *   M  : 该区域模糊后的局部均值（低频项，blur 不变 —— 高斯模糊保均值）
     *   h  : 残余半幅（高频项，随 blur 半径衰减）
     *
     *   透字峰 P(r) = M + h(r)      h(r) = h₀ · r₀/r      （推导见下「一」）
     *   透字谷 Q(r) = M − h(r)
     *
     *   经 HazeTint(白 t) 与 scrim(色 S、alpha a) 两级叠加：
     *      P' = (1−a)·[ (1−t)·P + 255t ] + a·S
     *      Q' = (1−a)·[ (1−t)·Q + 255t ] + a·S
     *   判据（沿用既有口径）：c(P', Q') ≤ 阈值，c 为 WCAG 对比度
     *      c = (L_hi + 0.05) / (L_lo + 0.05)
     * ```
     *
     * 两个实测锚点（一加 13 真机，本轮不改动，只用来标定模型）：
     * | 表面 | 基准半径 r₀ | tint t | 实测透字峰 P | 实测透字谷 Q | 实测对比 |
     * |------|------------|--------|-------------|-------------|---------|
     * | 迷你条 Hi | 26dp | 0.16 | 182 | 75 | 4.3:1 |
     * | 导航栏 Md | 20dp | 0.10 | 251 | 56 | 11.4:1 |
     *
     * 反解出未加 scrim 时的形态：迷你条 M=104.4 / h₀=63.7；导航栏 M=142.2 / h₀=108.3。
     * 用这组参数复算 a=0.80：迷你条 **1.40:1**、导航栏 **1.80:1**，
     * 落在既有实测带 1.1–1.8:1 内 —— 模型与真机自洽，才敢往下推。
     *
     * ## 为什么「加大 blur 半径 + 降低 scrim」这条路**不可行**（用户点名的方案）
     *
     * 上一轮只回了一句「加大半径肉眼不可辨」，没有算式。本轮算完，结论不可行，
     * 而且**不是因为 GPU 太贵**，是三个独立的、每一个都足以封死方案的原因：
     *
     * ### 一、半径是线性增益：残幅 ∝ 1/r，砍不到十分之一
     *
     * 高斯模糊是**保均值线性滤波**。一维矩形笔画（宽 w）与高斯核（σ）卷积，残余调制：
     * ```
     *   m(σ) = erf( w / (2√2·σ) ) →  σ ≫ w 时  m ≈ w / (√(2π)·σ)  ∝ 1/σ ∝ 1/r
     * ```
     * 用实测反解：迷你条 r=26dp 时 m=0.547，对应 σ_eff≈7px（约 2dp@560dpi，
     * 与「14–29px 边缘过渡」的实测一致）。要让 m 降到 0.1 需 **r ≈ 155dp（现值 6 倍）**。
     * 半径翻倍只能把残值砍一半 —— 这是**线性系统的基本性质**，不是实现缺陷。
     *
     * ### 二、存在不可约下界：低频项 blur 完全无法消除
     *
     * 透字信号可以正交分解：
     * ```
     *   透字 = 高频（笔画形状、边缘）      ← blur 能衰减
     *        + 低频（整行文字把这块玻璃整体提亮）← blur **完全不能**改变（保均值）
     * ```
     * 低频项就是 r→∞ 的下界。把半径推到无穷大（糊成一个平色场）：
     * | 表面 | 现状 (r₀, a=0.80) | r→∞ 下界 | blur 的全部收益 |
     * |------|------------------|----------|----------------|
     * | 迷你条 | 1.40:1 | **1.20:1** | 0.20 |
     * | 导航栏 | 1.80:1 | **1.33:1** | 0.47 |
     *
     * 花无穷大的 GPU 代价，买 0.2–0.5 的对比度。而把 scrim 从 0.80 提到 0.86（**零成本**）
     * 能把迷你条拉到 1.25、导航栏拉到 1.41 —— 同样效果，一个要翻倍显存带宽，一个不要。
     *
     * ### 三、退化路径下 blur 收益恒为 0，而降低 scrim 的代价全额兑现
     *
     * 这是压倒性的一条。软件渲染 / 无 RenderEffect 时 haze 走 [ScrimBlurEffect]，
     * 半径是多少**完全不影响像素**。此时「加大半径」收益 = 0，「降低 scrim」代价 = 全部。
     * 反解退化路径（零模糊）守 2:1 的硬下限：
     * ```
     *   迷你条 c = 1.76  @ a=0.80   →  a ≥ 0.761
     *   导航栏 c = 1.82  @ a=0.80   →  a ≥ 0.772
     * ```
     * **导航栏先封口：a 最低只能到 0.772，即最多从 0.80 降到 0.78。**
     * 而 0.78 相对 0.80 在 blur 生效时只买回 0.04 的对比度（1.40→1.44）—— 肉眼不可辨。
     * 结论：这条路线在可行域上宽度≈0，不存在有意义的实现空间。
     *
     * ### 四、可行域扫描（若强行要降 scrim，需要多大的半径）
     *
     * 深色、判据 c ≤ 1.5:1、tint 维持现值：
     * | scrim a | 迷你条所需 r | ×现半径 | 导航栏所需 r | ×现半径 | 判定 |
     * |---------|-------------|--------|-------------|--------|------|
     * | 0.80 | 18.5dp | 0.71× | 49.3dp | 2.47× | ✅ 可满足（现状已达标）|
     * | 0.74 | 34.8dp | 1.34× | **455dp** | **22.8×** | ❌ 导航栏需整屏模糊 |
     * | 0.70 | 56.7dp | 2.18× | 下界 1.58 > 1.5 | — | ❌ 半径再大也无解 |
     * | ≤0.65 | 下界已 > 1.5 | — | 下界已 > 1.5 | — | ❌ 数学上无解 |
     *
     * 注意 455dp 那格：haze 会把背景图层按半径外扩采样（`Rect.inflate(blurRadiusPx)`），
     * 导航栏本体只有 58dp 高，455dp 的半径意味着采样层面积是条本体的 **>100 倍**，
     * 且观感上它早已不是「一条悬浮导航栏」而是整屏毛玻璃。
     *
     * ## 那正确的方向是什么（给下一轮的真机复测留话）
     *
     * **瓶颈是低频项，低频项来自「文字本身」，所以真正的解法在内容侧**——让列表在滚动到
     * chrome 下方之前渐隐消失（视觉审计 D1 的方向，仍未落地）。文字根本不进入采样区，
     * 低频项归零，此时 scrim 的职责就从「压死字形」退回「建立材质」，0.4–0.5 量级可行。
     *
     * 但**本轮不做**，原因有二：① 内容侧渐隐是新增的滚动耦合逻辑，需真机复测；
     * ② 若渐隐不完全（快速甩动时常有一两帧文字漏进 chrome），0.4–0.5 的 scrim 在
     * 深色下实测是 2.42–2.55:1 —— 直接退回「可辨」区间。也就是说渐隐必须先有
     * **硬保证**（例如 chrome 区域遮罩而非依赖时序），才能动 scrim 值。
     * 在那之前，0.80/0.74 是唯一同时满足「blur 生效 / blur 失效」两个世界的解。
     *
     * ## 浅色档（0.74）为什么可以比深色档低 —— 附本轮推算
     *
     * 浅色是「深色笔画压在浅色玻璃上」，方向相反。已知浅色 fill 本身就有
     * 白 60%（Hi）/48%（Md），tint 同值，白 tint 把深笔画往白推，起点对比就低得多。
     * 按同一坐标系推算（笔画覆盖率 φ=0.25，无实测锚点，标注为推算）：
     * ```
     *   scrim  0.74 → 迷你条 1.05:1 / 导航栏 1.06:1   ← 现状
     *   scrim  0.50 → 迷你条 1.10:1 / 导航栏 1.13:1
     *   scrim  0.30 → 迷你条 1.14:1 / 导航栏 1.18:1
     *   无 scrim    → 迷你条 1.20:1 / 导航栏 1.28:1
     * ```
     * 也就是说**浅色档不是瓶颈**：即便把 scrim 全去掉也读不出字形。但本轮**不动**它，
     * 因为①浅色全程无真机实测锚点（本工程从未在浅色下做过对比度采样），
     * ②深浅两档的 alpha 刻意保持同量级，避免切换主题时出现可感知的材质跳变。
     * 若下轮补上浅色实测，0.74 有下调到 0.5 量级的空间 —— 这是**待验证项，不是结论**。
     *
     * chrome 自身文字画在遮罩**之上**，不受衰减（深色下白字对比反而略升，实测 6.3:1）。
     *
     * 已知取舍：本字段**不随「玻璃强度」设置变化**（[GlassIntensity.derive] 有意不缩放它）。
     * 它是可读性保障而非装饰强度，不该被用户的观感偏好削弱 —— 副作用是迷你条/导航栏上
     * 三档强度差异变得很微弱，属有意为之，不要当成 bug。
     *
     * **视觉审计 D1 待办（仍未落地，本轮已量化其前置条件）**：0.80 让这两块玻璃基本成为
     * 实色板，与「液态玻璃」的产品主张有张力。但本轮已经算清楚：**只靠调 blur 解决不了**
     * （见上四条），唯一有希望的杠杆是内容侧边缘渐隐 + 真机复测；且渐隐必须有硬保证，
     * 否则 scrim 一降就破 2:1。这也是为什么本轮**没有**把任何数值改小。
     */
    val chromeScrim: Color = Color(0xFF1A1A24).copy(alpha = 0.80f),
) {
    companion object {
        /** 浅色令牌：玻璃改实白面板、描边与文字转近黑 */
        fun light(): GlassColors = GlassColors(
            hi = Color.White.copy(alpha = 0.60f),
            md = Color.White.copy(alpha = 0.48f),
            lo = Color.White.copy(alpha = 0.32f),
            // 浅色描边反转（视觉审计 D5）：白色系描边压在白 60% 玻璃 + 近白极光上等于消失，
            // 卡片/封面失去边缘定义。改近黑低 alpha 才能在浅底上「画出」轮廓。
            stroke = Color(0xFF23232B).copy(alpha = 0.14f),
            strokeSoft = Color(0xFF23232B).copy(alpha = 0.09f),
            topLine = Color.White.copy(alpha = 0.90f),
            textPrimary = Color(0xFF23232B),
            textSecondary = Color(0xFF23232B).copy(alpha = 0.62f),
            textTertiary = Color(0xFF23232B).copy(alpha = 0.52f),
            textFaint = Color(0xFF23232B).copy(alpha = 0.42f),
            lyricFar = Color(0xFF23232B).copy(alpha = 0.34f),
            lyricAdjacent = Color(0xFF23232B).copy(alpha = 0.55f),
            // 浅色下纯红更刺眼，用砖红一档
            destructive = Color(0xFFC0392B),
            pressedOverlay = Color(0xFF23232B).copy(alpha = 0.05f),
            // 浅色模式的透字是「深色笔画压在浅玻璃上」，方向必须反过来往白压。
            // 按同一推算：深色文字 lum≈35、玻璃底 lum≈241，经 alpha=a 白遮罩后
            // 残值 = 35 + (241−35)×a，要它与底 241 不可区分需 a ≈ 0.78 以上；
            // 浅色模式 hi/md 已有 60%/48% 白填充，透字起点对比本就比深色弱，
            // 取 0.74（略低于深色档的 0.80）即可达标，同时保住浅色玻璃的轻盈感。
            //
            // 2026-11-08 复算修正：上面那条单点式子与深色档一样低估了透字（透字是
            // 「均值 M ± 半幅 h」的调制带，不是一个标量）。按完整模型推算，浅色档的实际
            // 对比是 **1.05:1（迷你条）/ 1.06:1（导航栏）** —— 远低于深色档，说明
            // 0.74 对浅色是**过剩**的：即便把 scrim 完全去掉也只有 1.20/1.28。
            // 之所以仍然保留 0.74，不是因为它必须，而是因为①浅色档全程**无真机实测锚点**
            // （本工程从未在浅色下采样过对比度），1.05 这个数是推算不是实测；
            // ②深浅两档 alpha 刻意保持同量级，避免切换主题时出现材质跳变。
            // 若下轮补上浅色实测，这一档有下调到 0.5 量级的空间 —— 待验证项，不是结论。
            chromeScrim = Color(0xFFF2F2F6).copy(alpha = 0.74f),
        )
    }
}

/** GlassColors 全字段插值（深浅主题 400ms 交叉渐变用）
 *
 *  [GlassColors.chromeScrim] 必须一起插值：它是深浅两套**不同方向**的颜色
 *  （深色往黑压、浅色往白压）。漏掉这个字段的话，构造出来的 GlassColors 会回落到
 *  默认值（深色深底），浅色模式整条 chrome 会在切换瞬间变成一块半透明黑板。 */
fun lerpGlassColors(from: GlassColors, to: GlassColors, fraction: Float): GlassColors = GlassColors(
    hi = lerp(from.hi, to.hi, fraction),
    md = lerp(from.md, to.md, fraction),
    lo = lerp(from.lo, to.lo, fraction),
    stroke = lerp(from.stroke, to.stroke, fraction),
    strokeSoft = lerp(from.strokeSoft, to.strokeSoft, fraction),
    topLine = lerp(from.topLine, to.topLine, fraction),
    textPrimary = lerp(from.textPrimary, to.textPrimary, fraction),
    textSecondary = lerp(from.textSecondary, to.textSecondary, fraction),
    textTertiary = lerp(from.textTertiary, to.textTertiary, fraction),
    textFaint = lerp(from.textFaint, to.textFaint, fraction),
    lyricFar = lerp(from.lyricFar, to.lyricFar, fraction),
    lyricAdjacent = lerp(from.lyricAdjacent, to.lyricAdjacent, fraction),
    destructive = lerp(from.destructive, to.destructive, fraction),
    pressedOverlay = lerp(from.pressedOverlay, to.pressedOverlay, fraction),
    chromeScrim = lerp(from.chromeScrim, to.chromeScrim, fraction),
)

/** AuroraPalette 全字段插值（深浅主题各自动画后按进度混合，避免中途整包跳变） */
fun lerpPalette(from: AuroraPalette, to: AuroraPalette, fraction: Float): AuroraPalette = AuroraPalette(
    seed = lerp(from.seed, to.seed, fraction),
    seed2 = lerp(from.seed2, to.seed2, fraction),
    seed3 = lerp(from.seed3, to.seed3, fraction),
    deep = lerp(from.deep, to.deep, fraction),
    deep2 = lerp(from.deep2, to.deep2, fraction),
)

// ── 动效（统一弹簧曲线）───────────────────────────────────
object GlassMotion {
    /** cubic-bezier(.32,.72,.28,1) —— 全 app 唯一缓动曲线 */
    val Curve = CubicBezierEasing(0.32f, 0.72f, 0.28f, 1f)

    /** 动效强度乘数（设置页三档），只作用于过渡动画；极光/波形等无限动画不受控 */
    var scale = 1f

    const val Press = 180     // 按压回弹 180ms scale(.92)
    const val Palette = 400   // 取色交叉渐变 400ms
    const val Lyric = 500     // 歌词滚动 / 高亮 500ms
    const val Page = 360      // 页面切换 360ms 淡入上移
    const val Hover = 250     // tab / 开关等微交互
    const val Flight = 500    // 共享元素飞行（迷你条 ⇄ 播放页）500ms
    const val Sheet = 420     // 播放页覆盖层整屏升降 420ms（行程比页面切换长，需更多时间）

    fun <T> tweenPress() = tween<T>((Press * scale).toInt().coerceAtLeast(1), easing = Curve)
    fun <T> tweenPalette() = tween<T>((Palette * scale).toInt().coerceAtLeast(1), easing = Curve)
    fun <T> tweenLyric() = tween<T>((Lyric * scale).toInt().coerceAtLeast(1), easing = Curve)
    fun <T> tweenPage() = tween<T>((Page * scale).toInt().coerceAtLeast(1), easing = Curve)
    fun <T> tweenHover() = tween<T>((Hover * scale).toInt().coerceAtLeast(1), easing = Curve)
    fun <T> tweenFlight() = tween<T>((Flight * scale).toInt().coerceAtLeast(1), easing = Curve)
    fun <T> tweenSheet() = tween<T>((Sheet * scale).toInt().coerceAtLeast(1), easing = Curve)
}

// ── 弹性动效（P1-2：回弹/落位类动画的正确语言）───────────────
/**
 * 弹簧 token，与上面的 tween 并列，共同构成「全 App 单一动效语言」。
 *
 * **为什么需要**：tween 是「定时播放」——按下 180ms 后到 0.92，松手再 180ms 回到 1。
 * 快速连点时两次 tween 会排队重启，观感僵硬；而弹簧**继承当前速度**、可被打断后
 * 从当前位置自然重定向，这是 iOS/ColorOS 原生手感的来源。全仓此前 0 个 spring
 * （动效审计 P1-2）。
 *
 * 三档语义（不要按数值记忆，按**用途**选）：
 * - [springSnap]：落位/归位（拖拽松手、重排落点）。低阻尼、带一点点回弹，
 *   让「吸附」有物理感而不是滑到位就停。
 * - [springGentle]：按压回弹、开关滑块。阻尼接近临界，**不振荡**——按钮回弹过头
 *   会显得廉价，这里要的是「柔和地恢复」。
 * - [springPanel]：面板/覆盖层升降。大位移用低刚度，收尾才不显得急。
 *
 * 注意：弹簧的 [spring] 不带时长，`GlassMotion.scale`（动效强度）无法直接乘——
 * 用 stiffness/dampingRatio 的倒数近似缩放，见各函数实现。这是**有意的不完美**：
 * 弹簧的语义就是「按物理收束」，强行改成定时反而丢掉它的全部好处。
 */
object GlassSpring {
    /** 落位/归位：略带回弹 */
    fun <T> snap() = spring<T>(dampingRatio = 0.72f, stiffness = 520f)

    /** 按压/开关：近似临界阻尼，不回弹 */
    fun <T> gentle() = spring<T>(dampingRatio = 0.9f, stiffness = 700f)

    /** 面板/覆盖层：大位移低刚度 */
    fun <T> panel() = spring<T>(dampingRatio = 0.85f, stiffness = 340f)
}

/** 玻璃强度（设置页三档）：按系数派生填充与描边透明度 */
enum class GlassIntensity(val label: String, val fillScale: Float, val strokeScale: Float) {
    Transparent("通透", 0.6f, 0.7f),
    Standard("标准", 1f, 1f),
    Rich("浓郁", 1.5f, 1.2f),
    ;

    fun derive(base: GlassColors): GlassColors = base.copy(
        hi = base.hi.copy(alpha = (base.hi.alpha * fillScale).coerceAtMost(1f)),
        md = base.md.copy(alpha = (base.md.alpha * fillScale).coerceAtMost(1f)),
        lo = base.lo.copy(alpha = (base.lo.alpha * fillScale).coerceAtMost(1f)),
        stroke = base.stroke.copy(alpha = (base.stroke.alpha * strokeScale).coerceAtMost(1f)),
        strokeSoft = base.strokeSoft.copy(alpha = (base.strokeSoft.alpha * strokeScale).coerceAtMost(1f)),
    )
}

/** 按两档缩放派生（A2-02：glassLevel 切换走 400ms 过渡，缩放是动画值而非枚举常量），与 [GlassIntensity.derive] 同式 */
private fun GlassColors.deriveBy(fillScale: Float, strokeScale: Float): GlassColors = copy(
    hi = hi.copy(alpha = (hi.alpha * fillScale).coerceAtMost(1f)),
    md = md.copy(alpha = (md.alpha * fillScale).coerceAtMost(1f)),
    lo = lo.copy(alpha = (lo.alpha * fillScale).coerceAtMost(1f)),
    stroke = stroke.copy(alpha = (stroke.alpha * strokeScale).coerceAtMost(1f)),
    strokeSoft = strokeSoft.copy(alpha = (strokeSoft.alpha * strokeScale).coerceAtMost(1f)),
)

/** 动效强度（设置页三档） */
enum class MotionIntensity(val label: String, val factor: Float) {
    Restrained("克制", 0.6f),
    Standard("标准", 1f),
    Enhanced("增强", 1.4f),
}

// ── CompositionLocal ──────────────────────────────────────
val LocalAuroraPalette = compositionLocalOf { GlassPalettes.Purple }
val LocalGlassColors = compositionLocalOf { GlassColors() }

/**
 * 曲库来源（UI 审计 R8，#10/#11）：真曲库无封面占位须从当前极光派生同色相渐变；
 * 演示内容保留设计稿 ArtStyle 固有渐变。GlassApp 根层按 state.realLibraryLoaded 提供。
 */
enum class LibraryOrigin { Live, Demo }
val LocalLibraryOrigin = compositionLocalOf { LibraryOrigin.Demo }

/** 取色切换时 5 个颜色分别做 400ms 交叉渐变（CSS transition 的 Compose 等价） */
@Composable
private fun animatePalette(target: AuroraPalette): AuroraPalette {
    val spec = GlassMotion.tweenPalette<Color>()
    val s by animateColorAsState(target.seed, spec, label = "seed")
    val s2 by animateColorAsState(target.seed2, spec, label = "seed2")
    val s3 by animateColorAsState(target.seed3, spec, label = "seed3")
    val d by animateColorAsState(target.deep, spec, label = "deep")
    val d2 by animateColorAsState(target.deep2, spec, label = "deep2")
    return AuroraPalette(s, s2, s3, d, d2)
}

/**
 * 液态玻璃主题根：注入动画化后的取色 + 玻璃 token。
 * 所有页面共用一个极光底，取色随封面主色变化；isDark 切换深浅令牌并做 400ms 交叉渐变。
 */
@Composable
fun GlassTheme(
    palette: AuroraPalette = GlassPalettes.Purple,
    isDark: Boolean = true,
    glassIntensity: GlassIntensity = GlassIntensity.Standard,
    content: @Composable () -> Unit,
) {
    // A3-01：时长走 tweenPalette（Palette×动效强度乘数）——旧实现裸常量 400ms，
    // 「克制/增强」档对主题切换无效，且与同源的 palette 渐变时长脱节
    val themeFraction by animateFloatAsState(
        targetValue = if (isDark) 0f else 1f,
        animationSpec = GlassMotion.tweenPalette(),
        label = "themeFraction",
    )
    // A2-02：玻璃强度三档同样过渡化——themeMode/palette 均有 400ms 渐变，
    // derive 直接读瞬时枚举会让全 App 玻璃填充/描边一步跳变
    val fillScale by animateFloatAsState(glassIntensity.fillScale, GlassMotion.tweenPalette(), label = "glassFill")
    val strokeScale by animateFloatAsState(glassIntensity.strokeScale, GlassMotion.tweenPalette(), label = "glassStroke")
    // 深浅两套取色各自动画后按主题进度混合（浅色 = deep 系拉高明度的派生）。
    // 这里就应是「两层」：dark/light 各自 400ms 交叉渐变，再按 themeFraction 线性混合。
    // 旧实现在 provides 处又套了一层 animatePalette(effectivePalette)：那是第三层级联，
    // 让取色切换变成约 2×400ms 的拖尾，且与注释描述的两层设计不符（笔误）。
    // 去掉后：切换总时长回到一个 tweenPalette（400ms），与 GlassColors 交叉渐变同步收束。
    val darkPalette = animatePalette(palette)
    val lightPalette = animatePalette(palette.light())
    val effectivePalette = lerpPalette(darkPalette, lightPalette, themeFraction)
    val darkGlass = GlassColors().deriveBy(fillScale, strokeScale)
    val lightGlass = GlassColors.light().deriveBy(fillScale, strokeScale)
    val glass = lerpGlassColors(darkGlass, lightGlass, themeFraction)

    CompositionLocalProvider(
        // 直接提供混合结果：第三层动画是多余滞后（见上），不是设计意图
        LocalAuroraPalette provides effectivePalette,
        LocalGlassColors provides glass,
        // A1-03：LocalIsDark 仍是阈值布尔（供非视觉/必须二选一的消费者），但所有
        // 视觉连续值消费者改读 LocalThemeFraction——旧实现的极光混合模式、暗角、
        // auroraAccentColor、面板 veil 都在 400ms 渐变中点硬翻转
        LocalIsDark provides (themeFraction < 0.5f),
        LocalThemeFraction provides themeFraction,
    ) {
        content()
    }
}

/** 深浅模式标记：极光背景混合/暗角、系统栏外观等处使用 */
val LocalIsDark = compositionLocalOf { true }

/**
 * 主题交叉渐变进度（0 = 深色端，1 = 浅色端）：渐变期需要**连续值**的视觉
 * （极光光源混合/暗角、auroraAccentColor、面板 veil）据此插值，不再中途跳变。
 */
val LocalThemeFraction = compositionLocalOf { 0f }

/** 强调色（选中/开启态）：深色用浅强调 seed3，浅色下 seed3 太浅改用主色 seed */
@Composable
fun auroraAccentColor(): Color {
    val palette = LocalAuroraPalette.current
    // A1-03：按主题进度连续插值（旧实现按 LocalIsDark 阈值瞬时切换，
    // 主题渐变中点选中态文字/输入光标/播放高亮集体跳色）
    return lerp(palette.seed3, palette.seed, LocalThemeFraction.current)
}

/**
 * 压在 seed 实底上的前景色（视觉审计 A4）：tab 选中态、详情页「播放」胶囊、开关滑块、
 * 进度条等凡以 palette.seed 作背景处一律读它。
 *
 * **为什么必须由派生决定而不是固定白色**：项目允许用户从 10 色预设（含琥珀 #E0A800、
 * 草绿 #57A94A、青绿 #1D9E75）或任意封面主色作 seed。白字压在琥珀上实测对比只有
 * ≈2.15:1——**选中态反而成了全屏最难读的元素**，与「清晰视觉层级」正好相反。
 *
 * ## 为什么不能简单用「亮度 > 0.5 就返回黑」
 *
 * 第一版就是这么写的，实测 10 个预设色**全部 ≤0.44**（最亮的琥珀 0.439，最暗的石板灰蓝
 * 0.141），没有一枚越过 0.5 —— 等于这条判据从未生效，全部仍然吃到白字，琥珀上 2.15:1
 * 的问题原封不动。根因：这些 seed 不是「亮色块」，而是**中低明度的高饱和彩色**，
 * 用「亮/暗」这条二分线去切它们，切点落在了一个所有样本都不在的位置。
 *
 * 正确的问法不是「这个颜色亮吗」，而是「**白字和黑字，哪个在上面更清楚**」。
 * 这就是 WCAG 的相对亮度与对比度公式直接回答的问题：
 *   对比度 = (L亮 + 0.05) / (L暗 + 0.05)
 * 所以这里直接算两个候选前景色各自的对比度，取大的那个。这个判据对任意 seed
 * （预设色 / 自定义色 / 封面提取色）都成立，不需要针对色板调参。
 *
 * 实测该判据在 10 个预设色上的结果：
 * - 琥珀（0.439）→ 黑字 8.04:1（白字仅 2.15）✅ 这是修复的重点目标
 * - 草绿（0.309）→ 黑字 5.91（白 2.93）✅ 同理
 * - 青绿（0.260）/ 蓝（0.242）/ 靛紫（0.229）/ 珊瑚橙（0.221）→ 黑字 4.6–5.1（白 3.4–3.8）✅
 * - 玫红（0.197）/ 紫罗兰（0.182）/ 棕（0.177）/ 石板灰蓝（0.141）→ 白字 3.8–5.5（黑字 3.2–4.1）✅
 *
 * 注意：多数 seed 上白字也都达不到 AA 大字号 3:1 以上（琥珀 2.15、草绿 2.93），
 * 说明**白字本来就不是这些色的正确选择**——这不是 onSeedColor 能完全挽救的问题，
 * 而是「seed 被用作大面积实底」这个设计本身的上限。彻底解决需要给 seed 实底再加
 * 一层明度保障（如压暗 8%），属下一轮候选；本轮先把「明显更优的那个」选对。
 */
@Composable
fun onSeedColor(): Color {
    val seed = LocalAuroraPalette.current.seed
    // WCAG 相对亮度（linearized）。Compose 的 luminance() 就是这套公式，直接复用。
    val seedL = seed.luminance()
    val whiteL = Color.White.luminance()   // = 1.0
    val inkL = OnSeedInk.luminance()       // 近黑，约 0.01

    fun contrast(a: Float, b: Float): Float {
        val hi = maxOf(a, b)
        val lo = minOf(a, b)
        return (hi + 0.05f) / (lo + 0.05f)
    }

    return if (contrast(seedL, inkL) >= contrast(seedL, whiteL)) OnSeedInk else Color.White
}

/** onSeedColor 的近黑候选：带一点蓝调（#1A1A22）而不是纯黑，与 App 的深色令牌同族 */
private val OnSeedInk = Color(0xFF1A1A22)