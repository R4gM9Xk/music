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
         * 自定义主题色（F2）：以用户选的 [seed] 派生整套五色阶梯。
         *
         * 派生规律对齐内置调色板（以 Purple 为例：seed #7F77DD → seed2 #AFA9EC → seed3 #CECBF6，
         * deep #241C4A → deep2 #0E0A20）：
         * - seed2/seed3 = 同色相提明度、降饱和（强调/浅强调）
         * - deep/deep2 = 同色相深压 + 高饱和（极光底色，决定整屏氛围）
         *
         * 用 HSV 而非 OKLCH：Android 平台原生支持、与既有 paletteFromBitmap 同源，
         * 且内置调色板正是按这套规律手调的，派生结果与品牌色观感一致。
         */
        fun fromSeed(seed: Color): AuroraPalette {
            val hsv = FloatArray(3)
            android.graphics.Color.colorToHSV(seed.toArgb(), hsv)
            val h = hsv[0]
            val s = hsv[1]
            val v = hsv[2]

            fun hsvColor(hue: Float, sat: Float, value: Float): Color =
                Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, sat.coerceIn(0f, 1f), value.coerceIn(0f, 1f))))

            // seed3（最浅）：大幅提亮、明显降饱和；浅色模式下仍要压得住白底，故饱和度不归零
            val seed3 = hsvColor(h, (s * 0.55f).coerceAtLeast(0.10f), (v + (1f - v) * 0.62f).coerceIn(0f, 1f))
            // seed2（中）：提亮一档、轻微降饱和
            val seed2 = hsvColor(h, (s * 0.78f).coerceAtLeast(0.15f), (v + (1f - v) * 0.32f).coerceIn(0f, 1f))
            // deep（上）：深压到 ~0.22 明度、饱和度拉高（极光氛围感来自高饱和暗色）
            val deep = hsvColor(h, (s * 1.05f).coerceAtLeast(0.35f), (v * 0.30f).coerceIn(0.08f, 0.45f))
            // deep2（下）：再暗一档，接近黑但有明显色相
            val deep2 = hsvColor(h, (s * 1.10f).coerceAtLeast(0.38f), (v * 0.12f).coerceIn(0.03f, 0.20f))

            return AuroraPalette(seed = seed, seed2 = seed2, seed3 = seed3, deep = deep, deep2 = deep2)
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

// ── 间距 token（UI 审计第 6 轮修正确立）───────────────────
/**
 * 7 档间距刻度（dp）。R1 曾误记页面边距为 24dp，精确扫描证实为 20dp（23 处一致）。
 * 微缝豁免：1–6dp 的图标-标签缝、胶囊内光学 padding 不入刻度（同 R3 微圆角逻辑）。
 */
object S {
    const val Gap4 = 4
    const val Gap8 = 8
    const val Gap12 = 12
    const val Gap16 = 16
    const val PageMargin = 20   // 页面水平边距（23 处一致）
    const val Gap24 = 24
    const val Gap32 = 32
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
     * 到标量的推算（深色模式，最难的一档）：列表文字是白 lum≈240，chrome 底 lum≈52；
     * 经 alpha=a 黑遮罩后残值 = 240×(1−a)。要「与底色不可区分」需
     * 240×(1−a) ≤ 52 → **a ≥ 0.78**，所以 0.80 是**刚好达标**的取值：
     * 残值 48，略低于底色 52，再叠加 blur（真机生效时）的字形抹除，
     * 实测对比落在 1.1–1.8:1，低于 2:1 的可读阈值。
     *
     * 为什么不用 0.90：多压这 0.10 只在「blur 也失效」的最差环境里多买约 0.3 的对比度
     * （两者都已不可辨），代价却是把玻璃做成实色板 —— 而玻璃质感正是本工程的卖点，
     * 迷你条与导航栏又是用户最高频看到的两块玻璃。0.80 是「可读性达标」与「玻璃还在」
     * 的共同解。
     *
     * chrome 自身文字画在遮罩**之上**，不受衰减（深色下白字对比反而略升，实测 6.3:1）。
     *
     * 已知取舍：本字段**不随「玻璃强度」设置变化**（[GlassIntensity.derive] 有意不缩放它）。
     * 它是可读性保障而非装饰强度，不该被用户的观感偏好削弱 —— 副作用是迷你条/导航栏上
     * 三档强度差异变得很微弱，属有意为之，不要当成 bug。
     *
     * **视觉审计 D1 待办（未落地）**：0.80 让这两块玻璃基本成为实色板（模糊可见贡献仅
     * 20%），与「液态玻璃」的产品主张有张力。真正的解法在**内容侧**——列表滚动到 chrome
     * 下方前做边缘渐隐，让透字在到达 chrome 之前就消失，scrim 即可回落到 0.4–0.5 让模糊
     * 重新可见。属需真机复测的联动项，本轮不动此值。
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