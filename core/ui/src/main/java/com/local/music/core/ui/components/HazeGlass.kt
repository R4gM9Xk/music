package com.local.music.core.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.local.music.core.ui.theme.AuroraPalette
import com.local.music.core.ui.theme.GlassColors
import com.local.music.core.ui.theme.LocalAuroraPalette
import com.local.music.core.ui.theme.LocalGlassColors
import com.local.music.core.ui.theme.LocalThemeFraction
import dev.chrisbanes.haze.HazeProgressive
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect

/** 屏幕级背景采样状态：内容区 hazeSource 采样，面板 hazeEffect 消费（兄弟关系，禁止嵌套） */
val LocalHazeState = compositionLocalOf<HazeState?> { null }

/**
 * 玻璃面板挂接背景真模糊（haze 1.6）：读取 [LocalHazeState]，无状态时退化为普通玻璃。
 * blur 半径按玻璃层级 26/20/12dp（对齐设计稿 blur 30/20/12 的视觉重量），tint 复用玻璃填充色。
 *
 * ## 为什么 chrome 层**不再**单独加「更大半径的模糊档」（2026-11-08 定量结案）
 *
 * 上一轮的结论是一句没有数学支撑的断言：「scrim 到 0.80 后模糊贡献只剩 20%，
 * 加大半径肉眼不可辨」。本轮把它算清楚了，**并且验证了「显著加大 blur + 降低 scrim」
 * 这条组合路线走不通**。算式详见 [com.local.music.core.ui.theme.GlassColors.chromeScrim]
 * 的 KDoc，这里只留与**本文件**（半径选择）相关的结论：
 *
 * ### 一、半径是线性系统的增益，不是可无限加码的旋钮
 *
 * 高斯模糊是**保均值线性滤波**。对一维矩形笔画（宽 w）与高斯核（σ）卷积，残余调制深度为
 * ```
 *     m(σ) = erf( w / (2√2·σ) )        // σ ≫ w 时  m ≈ w / (√(2π)·σ)  ∝ 1/σ ∝ 1/r
 * ```
 * 也就是说 **残余调制与半径成反比**：半径翻倍只能把残值砍一半，砍不到十分之一。
 * 用真机实测反解：迷你条 r=26dp 时残余调制 m=0.547，对应 σ_eff≈7px；
 * 要让 m 降到 0.1（肉眼基本抹平）需 r≈155dp，**约 6 倍于现值**。
 *
 * ### 二、半径还有一条**几何上限**，比 GPU 开销更早封死
 *
 * haze 采样前会把背景图层按模糊半径外扩（`Rect.inflate(blurRadiusPx)`，
 * 见 HazeEffectNode 的 layer 计算）。导航栏只有 58dp 高，当 r 超过条高时，
 * 采样窗已经越过这条 bar 的上下边界，把**更远处**的列表内容一起卷进来 ——
 * 透字没被压掉，反而混入了本来不该出现在条内的内容。所以 r 不能随意超过条高量级。
 *
 * ### 三、退化路径下 blur 的贡献恰好是 0
 *
 * 软件渲染 / 无 RenderEffect 的设备上 haze 静默退化为纯 tint（[ScrimBlurEffect] 路径），
 * 此时半径是多少**完全不影响像素**。任何指望「用半径换 scrim」的方案，
 * 在这些设备上都会直接退回「没有 scrim」的裸值（实测可辨）。
 * 这就是本轮判定不可行的硬约束，详见 chromeScrim 的 KDoc。
 *
 * 结论：**半径维持在 26/20/12dp**。它不是「懒得调」，而是
 * ①再大的半径在保均值约束下边际收益递减（Δ 对比度 <0.1）；
 * ②导航栏受条高约束先撞几何天花板；
 * ③退化设备上收益恒为 0。
 * 可读性保障仍在 [com.local.music.core.ui.theme.GlassColors.chromeScrim]。
 */
/**
 * Liquid 档的 haze tint 配方：低浓度白霜 + 歌曲 accent 色相。
 *
 * **跨模块 public 的唯一理由**：app 模块的飞行替身（`FlightGlassPanel`）落地端必须与
 * 真身同源。替身旧实现落点是 Md 的纯 hi 白霜，交接窗内明度/色相会跳半档——配方复制
 * 一份就是这个问题的温床，所以只留这一份，真身与替身都读它。
 */
fun liquidHazeTint(glass: GlassColors, palette: AuroraPalette): Color =
    lerp(glass.liquidFill, palette.seed2.copy(alpha = 0.14f), 0.55f)

@Composable
fun Modifier.glassHaze(level: GlassLevel): Modifier {
    val state = LocalHazeState.current ?: return this
    val glass = LocalGlassColors.current
    val palette = LocalAuroraPalette.current
    val style = remember(level, glass, palette) {
        // Liquid（控制台） tint = 低浓度白霜 + 歌曲 accent 色相：背后已是真模糊的极光/照片，
        // 纯白霜会把浅色罩成奶白卡、深色罩成灰卡（用户反馈「太偏黑/偏白」）。注入 seed2 色相
        // 后玻璃读作「有色玻璃」，色彩来自当前歌曲而非固定白/黑。其余档保持纯填充色。
        val tint = if (level == GlassLevel.Liquid) {
            liquidHazeTint(glass, palette)
        } else {
            glass.fill(level)
        }
        HazeStyle(
            backgroundColor = Color.Transparent,
            tints = listOf(HazeTint(tint)),
            blurRadius = when (level) {
                GlassLevel.Hi -> 26.dp
                GlassLevel.Md -> 20.dp
                GlassLevel.Lo -> 12.dp
                // Liquid 取 26dp（与 Hi 同级）：它的差异化在装饰层不在模糊强度。
                // **不要因为「液态应该更糊」把它往上调**——半径是保均值线性滤波的增益，
                // 本文件 KDoc 已经算过：26→48dp 只能把残余调制砍掉约 45%，
                // 而采样窗会按 Rect.inflate(r) 外扩，大面板上代价是实打实的 GPU。
                GlassLevel.Liquid -> 26.dp
            },
            noiseFactor = 0.05f,
            fallbackTint = HazeTint(tint),
        )
    }
    return hazeEffect(state = state, style = style)
}


/**
 * 「靠模糊挡字、不靠遮罩」的玻璃配方档位。
 *
 * ## 为什么需要一个枚举而不是一个布尔（2026-11-21 用户反馈两次迭代）
 *
 * 用户第一轮反馈：「Chrome 太暗/亮了，遮住下方文字，我要的是**模糊**下方文字，不是遮挡」。
 * 第二轮：「模糊程度再低一点」，随后：「队列面板、下拉浮层也一样改」。
 *
 * 三处表面的**层级与背景内容完全不同**，不能照抄同一组数字：
 *
 * | 档位 | 表面 | 背景是什么 | progressive 方向 |
 * |---|---|---|---|
 * | [Chrome] | 迷你条 / 导航胶囊 | 滚动列表（文字从下往上滑入） | 竖向，上沿最糊 |
 * | [Panel] | 队列 / 动作面板 | 列表 + 模态 scrim 压过的页面 | 竖向，上沿最糊 |
 * | [Popup] | 设置页下拉浮层 | **设置行本身**（同为高对比文字） | 无（小面积，均匀即可） |
 *
 * 三个档位的共同点：**tint 只定调、不遮光**，遮挡职责交给模糊。差别在半径、
 * 下沿强度、以及是否用 progressive。
 */
enum class BlurVeilKind {
    /**
     * 底部 chrome（迷你条 / 导航胶囊）。
     *
     * 半径 12dp（用户连续三轮要求降低后从 36 → 26 → 18 → 12）；
     * 竖向 progressive 上沿 1.0 → 下沿 0.92（这一档几乎不衰减，见 [endIntensity]）。
     */
    Chrome,

    /**
     * 底部模态面板（队列 / 歌曲操作 / 加入歌单 / 新建重命名 / 删除确认）。
     *
     * 比 Chrome 略强一档（14dp）：面板是**大面积**表面，且它背后是整页内容
     * （列表 + 封面 + 标题），只糊一点点会让背后的大字在面板上形成可读的鬼影。
     */
    Panel,

    /**
     * 设置页下拉浮层。
     *
     * 不用 progressive（面积只有 ~200×150dp，竖向渐变在这个尺度上看不出方向感，
     * 反而像「上清下糊」的瑕疵）；半径 10dp，均匀。
     *
     * 它压的是**设置行本身**（同样高对比的文字 + 副标题），比 chrome 压滚动列表
     * 更需要挡字——所以半径虽小，靠均匀模糊 + tint 解决，不放松。
     */
    Popup,
}

/**
 * 各档位的模糊半径。
 *
 * ## 取值演进（2026-11-21，用户连续三轮要求降低）
 *
 * | 轮次 | Chrome | Panel | Popup | 用户反馈 |
 * |---|---|---|---|---|
 * | 初版 | 36dp | 30dp | 24dp | 「模糊程度再低一点」 |
 * | 二版 | 26dp | 30dp | 24dp | 「模糊程度再降一点」 |
 * | 三版 | 18dp | 20dp | 16dp | 「再次降低模糊程度」 |
 * | 现在 | **12dp** | **14dp** | **10dp** | — |
 *
 * ## 为什么这一档要格外小心（12dp 已接近「模糊失效」边界）
 *
 * 本仓此前的玻璃档最低是 Lo = 12dp，而 chrome 现在正好压在这个值上。
 * 再往下（<10dp）会出现两个问题：
 * 1. **文字重新可读**：列表行标题是 15sp SemiBold（笔画宽约 2–3px @3x），
 *    12dp 高斯核的残余调制 m = erf(w/(2√2·σ)) 还能把笔画抹成雾状；
 *    降到 8dp 时 σ 只剩约 2.5px，笔画会重新分离成「能认出的字」。
 * 2. **haze 采样窗外扩变小**：`Rect.inflate(r)` 的窗口窄到接近条高时，
 *    边缘会出现「模糊只在中间生效、上下沿仍是清晰背景」的条带瑕疵。
 *
 * 所以 12dp 是本轮的下限档。**若用户还要求更低，不要再动半径**——那已经不是
 * 「更轻的玻璃」而是「没有玻璃」。此时应该改 tint 浓度（让玻璃更薄更透）
 * 或直接去掉 progressive，而不是继续压半径。
 */
private val BlurVeilKind.radius: Dp
    get() = when (this) {
        BlurVeilKind.Chrome -> 12.dp
        BlurVeilKind.Panel -> 14.dp
        BlurVeilKind.Popup -> 10.dp
    }

/**
 * 竖向 progressive 的下沿强度（null = 不用 progressive，均匀模糊）。
 *
 * 取值的共同约束：**上沿必须够糊**（那里是背景文字滑入/贴近的位置），
 * 下沿可以松（那里靠近屏幕边/手势区，通透才像玻璃）。
 *
 * ## 半径与下沿是乘性叠加，必须联动（2026-11-21，第三次降档）
 *
 * 有效半径 ≈ radius × intensity。每降一次半径都要重算下沿，否则会在条内
 * 制造一条「下沿几乎没被糊」的清晰带——那正是列表行从下方滑入的路径。
 *
 * | 半径 | 下沿 | 有效半径 | 说明 |
 * |---|---|---|---|
 * | 26dp | 0.62 | 16.1dp | 二版 |
 * | 18dp | 0.72 | 13.0dp | 三版（收紧下沿保住下限） |
 * | **12dp** | **0.92** | **11.0dp** | 现在 |
 *
 * 为什么这次把下沿收到 0.92（几乎不衰减）：12dp 已经贴着可用下限，
 * 任何明显衰减都会让下沿有效半径掉到 9dp 以下（文字重新可读）。
 * 也就是说**这一档实际上放弃了「下沿更通透」的渐变**——通透感改由
 * 更低的 tint 浓度承担（见 [veilTint]），这是更安全的实现路径。
 */
private val BlurVeilKind.endIntensity: Float?
    get() = when (this) {
        BlurVeilKind.Chrome -> 0.92f
        BlurVeilKind.Panel -> 0.94f
        BlurVeilKind.Popup -> null
    }

/**
 * 「靠模糊挡字」的统一入口：tint 只定调、不遮光，遮挡职责交给模糊。
 *
 * 三个表面（chrome / 面板 / 下拉浮层）共用这一份实现，差别只在 [BlurVeilKind] 的参数。
 * 这样「遮挡感」这类问题只需要改一处配方，不会出现「chrome 改了、面板忘了」的漂移。
 */
@Composable
fun Modifier.blurVeil(kind: BlurVeilKind): Modifier {
    val state = LocalHazeState.current ?: return this
    val glass = LocalGlassColors.current
    // tint 必须在 remember 之外取：veilTint 是 @Composable（要读 LocalThemeFraction），
    // 而 remember 的计算块不是组合上下文。
    val tint = veilTint(glass, kind)
    val style = remember(kind, tint) {
        HazeStyle(
            backgroundColor = Color.Transparent,
            tints = listOf(HazeTint(tint)),
            blurRadius = kind.radius,
            noiseFactor = 0.05f,
            fallbackTint = HazeTint(tint),
        )
    }
    return hazeEffect(state = state, style = style) {
        val end = kind.endIntensity
        if (end != null) {
            this.progressive = HazeProgressive.verticalGradient(
                startIntensity = 1f,
                endIntensity = end,
                // preferPerformance = false 走真正的多档模糊
                // （true 会退化成单档 + 遮罩，正是我们要避免的「遮挡」）
                preferPerformance = false,
            )
        }
    }
}

/**
 * 各档位的 tint 浓度（相对 [GlassColors.hi] 的倍数）。
 *
 * ## 为什么深色端要比浅色端低（实测修正，2026-11-21）
 *
 * 初版两端同用 0.35，实测深色档 chrome 条内 mean=55.9，而条外列表只有 26.5 ——
 * chrome 比背景**亮一倍**，正是用户说的「太亮」。原因是深色档 `hi` 是白色 16%，
 * 它叠在深色极光上会把条内整体提亮；而模糊只抹高频、不改均值，提亮全部来自 tint。
 *
 * 所以深色端反而要**降低** tint 浓度（白霜越少越不亮），浅色端维持较高值
 * （白色 tint 叠在白底上几乎不提亮，它只需要一点奶白感）。
 *
 * 面板与下拉浮层的深色端可以比 chrome 稍高一点：它们是**前景浮层**，
 * 需要与背后的页面拉开明度差，否则面板边界读不出来。
 *
 * ## 第三轮降档时整体再压一档（2026-11-21）
 *
 * 半径降到 12/14/10dp 后，progressive 下沿被迫收到 0.92/0.94（几乎不衰减，
 * 见 [endIntensity] 的推导）——「下沿更通透」这条通道实际已经关闭。
 * 通透感改由 tint 承担：三档浓度各降约 25%，玻璃读作更薄的一片。
 *
 * 这是**安全的替代路径**：tint 只改明度均值、不碰模糊核，不会让文字重新可读；
 * 而继续压半径会。
 */
@Composable
fun veilTint(glass: GlassColors, kind: BlurVeilKind): Color {
    val dark = LocalThemeFraction.current < 0.5f
    val scale = when (kind) {
        // 深色：白 16% × 0.09 ≈ 白 1.4%（几乎不提亮，只剩极淡的霜感）
        // 浅色：白 60% × 0.26 ≈ 白 16%（保持奶白，但更薄）
        BlurVeilKind.Chrome -> if (dark) 0.09f else 0.26f
        // 面板要能「立起来」：深色端给到 0.22（≈白 3.5%），浅色端 0.32（≈白 19%）
        BlurVeilKind.Panel -> if (dark) 0.22f else 0.32f
        // 下拉浮层面积小、要更实一点才能与设置行分开：深色 0.28 / 浅色 0.36
        BlurVeilKind.Popup -> if (dark) 0.28f else 0.36f
    }
    return glass.hi.copy(alpha = glass.hi.alpha * scale)
}

/**
 * chrome 模糊半径。
 *
 * ## 取值演进（2026-11-21）
 *
 * - 初版 36dp：为了把透字压掉而加大，但用户反馈「模糊程度再低一点」——
 *   36dp 下背景内容被抹成一团色块，读作磨砂贴纸，不再是「隔着玻璃看内容」。
 * - 现取 **26dp**，与 Hi 档同值（本仓常规玻璃的基准半径）。
 *
 * 26dp 已经足够挡字：竖向 progressive 让上沿（列表行滑入的位置）拿满这个半径，
 * 而下沿衰减到 0.62 只留通透。**不要再往上调**——半径是保均值线性滤波的增益，
 * 本文件顶部长推导已算过边际收益递减；同时采样窗按 `Rect.inflate(r)` 外扩，
 * 导航胶囊只有 58dp 高，半径超过条高量级会把条外内容卷进来。
 */
private val CHROME_BLUR_RADIUS = 26.dp
