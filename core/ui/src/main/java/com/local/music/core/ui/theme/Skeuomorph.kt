package com.local.music.core.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

/**
 * 拟物（skeuomorphism）材质系统 —— 与液态玻璃并列的**第二套视觉语言**。
 *
 * ## 设计方向：「拾音 · 随身唱机」（2026-11 重设计）
 *
 * 上一版拟物是把玻璃组件逐块换成「不透明底 + 上沿高光 + 统一投影」，结果是
 * **整屏都是浮雕**：列表行、卡片、圆钮、面板全都有厚度，谁也不比谁重要。
 * 观感上它读作一台老式音响面板，而不是一个能日常用的音乐 App。
 *
 * 这一版换了叙事：**专辑封面是唱片封套，播放控制是精密按键，其余内容保持安静。**
 * 材质只出现在三种角色上：
 *
 * | 角色 | 材质 | 出现位置 |
 * |---|---|---|
 * | 机身 | 哑光、几乎无渐变、无投影 | 页面背景、设置组卡 |
 * | 按键 | 轻微凸起 + 短阴影 + 按压下沉 | 播放键、开关滑块、主按钮 |
 * | 封套 | 纸张边缘 + 细腻落影 | 专辑/歌单封面 |
 *
 * 普通歌曲行、文字、分组标题**一律平面**。这是与上一版最重要的差别：
 * 拟物不是「所有东西都变立体」，而是「只有需要被按、需要被拿起的东西才立体」。
 *
 * ## 为什么这是一个独立 token 集，而不是「把玻璃调浓一点」
 *
 * 项目已有的四个可配置维度（主题色 / 深浅 / 玻璃强度 / 动效强度）都在调**同一套语言**
 * 的参数。玻璃强度的三档只是同一块玻璃的浓淡。拟物不是这个梯度的延续，它是
 * **相反的物理假设**：
 *
 * | | 液态玻璃 | 拟物 |
 * |---|---|---|
 * | 光的路径 | **穿过**材质（透射） | **落在**材质上（反射） |
 * | 背后内容 | 必须可见（真模糊） | 必须不可见（不透明） |
 * | 边界 | 无边界感，靠描边暗示 | 明确厚度，靠斜面表达 |
 * | 深度 | 悬浮（z 轴上的分离） | 凸起（表面上的高差） |
 *
 * 只要仍然让背景透出来，「像玻璃」这件事就压不下去——所以本类**完全不参与**
 * [GlassColors] 的半透明公式，而是给出一组 alpha 恒为 1 的贴面色。
 *
 * ## 深浅两档是同一台设备的昼夜配色（本轮修正）
 *
 * 上一版把深色定义为「磨砂金属」、浅色定义为「上釉陶瓷」，两者观感差异过大：
 * 切换深浅时用户会觉得换了一个产品。现在两档共用同一套**机身叙事**：
 * 深色是石墨机身（暖调近黑），浅色是象牙机身（微暖近白），
 * 材质、光照方向、按键行程完全一致，只有明度与少量色温变化。
 */
@Immutable
data class SkeuomorphColors(
    /**
     * 贴面色（机身面）：**alpha 恒 1**。
     *
     * 这是整个拟物语言的地基，也是它与玻璃最不可妥协的差别。任何小于 1 的 alpha
     * 都会让背景透出来，用户立刻读出「这是玻璃」——那这个风格就白做了。
     */
    val face: Color,
    /** 贴面色·下沉（凹槽 / 轨道 / 输入框内底）：比 face 暗一档，模拟「凹进去」 */
    val faceRecessed: Color,
    /** 贴面色·凸起（按钮 / 芯片 / 开关滑块）：比 face 亮一档，模拟「从面上抬起」 */
    val faceRaised: Color,

    /**
     * 凸起斜面·上沿高光：迎光面，最亮。
     *
     * 拟物的上沿高光**宽度只有 1dp**——真实物体的倒角很窄，宽高光会立刻读成
     * 「贴了一条白边」的贴纸感。
     */
    val bevelTop: Color,
    /** 凸起斜面·下沿高光：圆角转向侧面的受光带，比上沿弱 */
    val bevelBottom: Color,
    /**
     * 凹槽斜面·上沿阴影：**只有凹陷**才有这一笔。
     *
     * 凸起与凹陷的区别不在「有没有高光」，而在**高光与阴影的方向**：
     * 凸起是上亮下暗（光先照到上沿），凹陷是上暗下亮（上沿挡住了光）。
     */
    val recessTop: Color,
    /** 凹槽斜面·下沿高光 */
    val recessBottom: Color,

    /**
     * 外投影色（凸起物投在贴面上的影子）。
     *
     * 拟物的深度**主要靠这一层**，不是靠边缘明暗。取低 alpha 的暖黑
     * （跟着主题走），而不是纯黑——纯黑投影在暖调机身上会读成脏污而不是阴影。
     */
    val shadow: Color,
    /**
     * 内高光（inset highlight）：贴面内部一层极淡的顶亮渐变。
     *
     * 它与 [bevelTop] 是两件事：bevelTop 是**边缘**的 1dp 线，
     * 这一笔是**面内**的大半径渐变（从顶部往下衰减）。本轮把它的强度大幅调低：
     * 上一版 6%–50% 的内高光让每块面都像塑料注塑件，新方案只要 2%–6%。
     */
    val innerLight: Color,

    // ── 文字与语义色 ────────────────────────────────────────
    //
    // 拟物档的贴面是不透明的，所以文字色不能沿用玻璃档的「白 60%/50%」那套
    // （那是为了穿透半透明底而选的弱对比）。不透明底上的弱对比会直接读作
    // 「看不清」，而不是「次级信息」。
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    /** 破坏性动作色（拟物档）：比玻璃档更饱和——不透明底上低饱和会读成棕色 */
    val destructive: Color,

    // ── 本轮新增：让「按键」与「机身」彻底分开的两个角色 ──────────
    //
    // 上一版没有这两个字段，于是所有面共用 faceRaised，播放键和一张设置组卡
    // 长得一样厚。新方案里：
    // - [keyFace] 只给**真正能按的东西**（播放键、主按钮、开关滑块）；
    // - [wellFace] 只给**能装东西的凹槽**（搜索框、进度轨、输入框）。
    /** 按键面：比 faceRaised 更有实体感（本轮新增，仅用于可按压控件） */
    val keyFace: Color,
    /** 凹槽面：比 faceRecessed 更明确的下沉色（本轮新增，仅用于输入/轨道） */
    val wellFace: Color,
    /** 分隔线（列表行之间、导航与迷你条之间）：低对比、不参与材质 */
    val separator: Color,
) {
    companion object {
        /**
         * 深色拟物：**石墨机身**。
         *
         * 取带一点暖意的近黑（#22241F）而不是纯黑或冷蓝黑：
         * - 纯黑贴面在深色背景上「浮不起来」（投影没有对比对象）；
         * - 冷蓝黑会让暖色高光读作「脏」，而本方案的强调色偏朱砂暖调。
         *
         * 高光取中性白而非冷白：暖调机身上冷高光会读成偏色。
         */
        fun dark(): SkeuomorphColors = SkeuomorphColors(
            face = Color(0xFF22241F),
            faceRecessed = Color(0xFF121410),
            faceRaised = Color(0xFF2D302A),
            // 上沿高光 14%（上一版 26%）：新方案要「精密」不要「塑料反光」
            bevelTop = Color.White.copy(alpha = 0.14f),
            bevelBottom = Color.White.copy(alpha = 0.05f),
            recessTop = Color.Black.copy(alpha = 0.38f),
            recessBottom = Color.White.copy(alpha = 0.05f),
            // 暖黑投影：纯黑在暖调机身上读作脏污
            shadow = Color(0xFF0A0B08).copy(alpha = 0.42f),
            // 内高光从 6% 降到 3%：面内渐变只用来「托住」上沿，不再自己形成材质
            innerLight = Color.White.copy(alpha = 0.03f),
            // 不透明深面上的文字可以比玻璃档更实。72%/56% 仍满足 WCAG AA，层级却分明。
            textPrimary = Color(0xFFF1F0E9),
            textSecondary = Color(0xFFB8B8AD),
            textTertiary = Color(0xFF9B9D91),
            // 深面上用亮暖红：不透明深底需要比玻璃档更亮才跳得出来
            destructive = Color(0xFFF09587),
            // 按键面比普通凸起再亮一档：它要表达「这块能被按下去」
            keyFace = Color(0xFF333730),
            // 凹槽面比普通下沉再暗一档：它要表达「这里能装东西」
            wellFace = Color(0xFF0E100C),
            separator = Color(0xFF383B34),
        )

        /**
         * 浅色拟物：**象牙机身**。
         *
         * 取微暖的近白（#F3F1EC）而不是纯白：纯白贴面 + 白高光 = 高光完全不可见
         * （白压白）。微暖的米白能让白高光显形，也让浅色主题不至于读作冷冰冰的
         * 白板——这是「机身」与「白卡纸」的差别。
         *
         * 投影用暖灰而不是冷黑：浅色环境的光是暖的，冷黑投影会让整页读作
         * 「蒙了一层灰」。
         */
        fun light(): SkeuomorphColors = SkeuomorphColors(
            face = Color(0xFFF3F1EC),
            faceRecessed = Color(0xFFDEDBD4),
            faceRaised = Color(0xFFFAF8F4),
            // 浅色档的高光是**纯白**：浅底上白高光的对比来源是「面本身不够白」
            bevelTop = Color.White.copy(alpha = 0.90f),
            bevelBottom = Color(0xFFB9B5AC).copy(alpha = 0.38f),
            // 浅色凹槽：上沿是**深**的阴影（同样的物理方向，只是颜色反转成暖灰）
            recessTop = Color(0xFF8A857B).copy(alpha = 0.38f),
            recessBottom = Color.White.copy(alpha = 0.80f),
            shadow = Color(0xFF6B6455).copy(alpha = 0.22f),
            // 浅色档内高光比深色档高：浅底上要压出「面」必须靠更明显的白
            innerLight = Color.White.copy(alpha = 0.34f),
            textPrimary = Color(0xFF25241F),
            textSecondary = Color(0xFF5D5A53),
            textTertiary = Color(0xFF716D65),
            // 浅色档用深砖红（不透明浅底上深色更有力）
            destructive = Color(0xFFB64234),
            keyFace = Color(0xFFFFFEFA),
            wellFace = Color(0xFFD7D3CB),
            separator = Color(0xFFD4D0C7),
        )
    }
}

/**
 * 按主题进度在深/浅两套拟物贴面之间插值。
 *
 * 与 [lerpGlassColors] 的差别在于**它插的是两套完整材质**，不是同一材质的两个亮度。
 * 但本轮之后两档共用同一套叙事，所以切换时用户看到的是「石墨机身变象牙机身」，
 * 而不是上一版那种「磨砂金属变成上釉陶瓷」的产品级跳变。
 */
fun lerpSkeuomorph(from: SkeuomorphColors, to: SkeuomorphColors, fraction: Float): SkeuomorphColors =
    SkeuomorphColors(
        face = lerp(from.face, to.face, fraction),
        faceRecessed = lerp(from.faceRecessed, to.faceRecessed, fraction),
        faceRaised = lerp(from.faceRaised, to.faceRaised, fraction),
        bevelTop = lerp(from.bevelTop, to.bevelTop, fraction),
        bevelBottom = lerp(from.bevelBottom, to.bevelBottom, fraction),
        recessTop = lerp(from.recessTop, to.recessTop, fraction),
        recessBottom = lerp(from.recessBottom, to.recessBottom, fraction),
        shadow = lerp(from.shadow, to.shadow, fraction),
        innerLight = lerp(from.innerLight, to.innerLight, fraction),
        textPrimary = lerp(from.textPrimary, to.textPrimary, fraction),
        textSecondary = lerp(from.textSecondary, to.textSecondary, fraction),
        textTertiary = lerp(from.textTertiary, to.textTertiary, fraction),
        destructive = lerp(from.destructive, to.destructive, fraction),
        keyFace = lerp(from.keyFace, to.keyFace, fraction),
        wellFace = lerp(from.wellFace, to.wellFace, fraction),
        separator = lerp(from.separator, to.separator, fraction),
    )

/**
 * 拟物材质的几何 token（dp）。
 *
 * ## 本轮修正：圆角不再刻意做小
 *
 * 上一版的规则是「拟物圆角必须比玻璃小（4/10/14 vs 18/24/28），因为大圆角 + 投影
 * 会读成圆角贴纸」。这条判断在「所有东西都凸起」的前提下是对的，但新方案里
 * **大部分东西不凸起**，小圆角反而让界面显得生硬、像老式仪器。
 *
 * 现在按产品形态取值：
 * - 缩略图 8dp（小尺寸封面）
 * - 设置组卡 16dp
 * - 底部控制座 / 底部面板 20dp
 * - 大封面 16dp（封套是方的，圆角过大就不像纸张）
 */
object SkeuoShape {
    /** 小控件（缩略图 / 芯片 / 输入格） */
    const val Chip = 8
    /** 标准卡片 / 设置组卡 */
    const val Card = 16
    /** 大面板 / 底部控制座 / Sheet */
    const val Panel = 20
    /** 播放页封套 */
    const val Sleeve = 16
    /** 胶囊（开关轨道 / 进度轨）：取半高，见调用点 */
    const val Pill = 99
}

/**
 * 拟物材质的投影参数（dp）。
 *
 * 分三档，对应三种真实关系：
 * - [Resting]：东西**放在**表面上（封面、组卡）——近投影锐而浅，远投影柔而更浅；
 * - [Key]：东西**高于**表面且会被按下（播放键）——更短的偏移、更紧的模糊；
 * - [Overlay]：东西**盖在**整个界面之上（Sheet）——大偏移、大模糊。
 *
 * 上一版只有一对 Near/Far 常量，于是所有东西的厚度都一样，这正是「整屏浮雕」的成因之一。
 */
object SkeuoElevation {
    /** 静置：近投影偏移 dy、模糊半径（dp） */
    const val NearDy = 2f
    const val NearBlur = 8f
    /** 静置：远投影 */
    const val FarDy = 6f
    const val FarBlur = 18f

    /** 按键：短而紧（按下时归零，见 [PressTravel]） */
    const val KeyBlur = 6f
    /** 按键按下时向下位移（dp）：这是「按下去」唯一可靠的信号 */
    const val PressTravel = 2f

    /** 弹层：盖在整个界面之上 */
    const val OverlayBlur = 28f
}

/**
 * 当前生效的材质语言（[com.local.music.core.ui.state.VisualStyle]）。
 *
 * 用 CompositionLocal 下发而不是给每个组件加形参：玻璃档的调用点有 26 个，
 * 加形参等于改 26 处签名，而它们**全都不关心**用的是哪套语言——只关心「画成什么样」。
 *
 * 默认 [com.local.music.core.ui.state.VisualStyle.Glass] 保证零回归。
 */
val LocalVisualStyle = compositionLocalOf { com.local.music.core.ui.state.VisualStyle.Glass }

/**
 * 当前生效的拟物 token。
 *
 * 与 [LocalGlassColors] 并存而不是替换：拟物档也需要「次要信息用弱色」这类语义，
 * 只是取值来源不同。渲染分支按 [LocalVisualStyle] 选择读哪一个。
 */
val LocalSkeuomorphColors = compositionLocalOf { SkeuomorphColors.dark() }

/**
 * 材质**角色**（本轮新增）——决定一个表面该怎么被画，而不是只决定它多浓。
 *
 * 上一版的渲染分支只有两个变量：`level`（Hi/Md/Lo）与 `raised`（是否凸起）。
 * 结果是「设置组卡」与「播放键」共享同一个凸起路径，只是尺寸不同——
 * 这正是新方案要消灭的东西：**卡片不是按键**。
 *
 * 现在由调用方声明意图，材质层按角色选笔：
 * - [Body]：机身面板（设置组卡、底部控制座）。**只有一层薄边**，不画斜面、不画投影。
 * - [Key]：可按压的实体键。斜面 + 短投影 + 按下位移。
 * - [Well]：凹槽（搜索框、进度轨）。上沿暗下沿亮，**没有任何外投影**。
 * - [Sleeve]：封套（封面）。纸张边缘 + 静置投影，不加斜面。
 */
enum class SkeuoRole { Body, Key, Well, Sleeve }
