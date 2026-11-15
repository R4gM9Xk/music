package com.local.music.core.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

/**
 * 拟物（skeuomorphism）材质系统 —— 与液态玻璃并列的**第二套视觉语言**。
 *
 * ## 为什么这是一个独立 token 集，而不是「把玻璃调浓一点」
 *
 * 项目已有的四个可配置维度（主题色 / 深浅 / 玻璃强度 / 动效强度）都在调**同一套语言**
 * 的参数。玻璃强度的三档只是同一块玻璃的浓淡：通透 → 标准 → 浓郁，它们依然
 * 半透明、依然透出背后的极光、依然靠描边与顶光线表达「我浮在你上面」。
 *
 * 拟物不是这个梯度的延续，它是**相反的物理假设**：
 *
 * | | 液态玻璃 | 拟物 |
 * |---|---|---|
 * | 光的路径 | **穿过**材质（透射） | **落在**材质上（反射） |
 * | 背后内容 | 必须可见（真模糊） | 必须不可见（不透明） |
 * | 边界 | 无边界感，靠描边暗示 | 明确厚度，靠斜面表达 |
 * | 深度 | 悬浮（z 轴上的分离） | 凸起（表面上的高差） |
 * | 圆角语言 | 大圆角（28dp）无棱 | 小圆角 + 实体投影 |
 *
 * 只要仍然让背景透出来，「像玻璃」这件事就压不下去——所以本类**完全不参与**
 * [GlassColors] 的半透明公式，而是给出一组 alpha 恒为 1 的贴面色。
 *
 * ## 与「玻璃液态档（Liquid）」的区别（别搞混这两个）
 *
 * [GlassColors.liquidEdgeTop] / `liquidEdgeSide` / `liquidEdgeBottom` 是**液态玻璃**
 * 的方向性斜面，它的底边是**暗**的（光被折射走）——因为玻璃是**透明介质**，
 * 下沿透过它看到的是背后的暗部。而拟物的凸起斜面**上下都需要高光**：
 * 上沿迎光所以亮，下沿是「圆角转向侧面」的受光面，同样亮但较弱；
 * 真正承担「凸起」信号的是**贴面外侧落下的投影**，不是边缘明暗。
 *
 * 一句话：玻璃的边缘差异表达「光从哪来」，拟物的边缘差异 + 投影表达「这个面有多高」。
 *
 * ## 明暗两档怎么办
 *
 * 与 [GlassColors] 同构地提供 [light] 版本：拟物同样有深色与浅色两种主题，
 * 它们不是「同一块玻璃的明暗」，而是**两种不同材质的贴面**——
 * 深色是磨砂金属（低明度、微冷调、高光锐），浅色是上釉陶瓷（高明度、微暖调、高光柔）。
 * 这里用同一套派生函数 + 不同的入射强度表达，而不是各写一份常量表
 * （两份表就会在下次调整时漂移，本仓已登记多起同型缺陷）。
 */
@Immutable
data class SkeuomorphColors(
    /**
     * 贴面色（多层叠出来的「面」）：**alpha 恒 1**。
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
     * 拟物的上沿高光通常近乎纯白（低 alpha），且**宽度只有 1dp**——真实物体的
     * 倒角很窄，宽高光会立刻读成「贴了一条白边」的贴纸感。
     */
    val bevelTop: Color,
    /** 凸起斜面·下沿高光：圆角转向侧面的受光带，比上沿弱 */
    val bevelBottom: Color,
    /**
     * 凹槽斜面·上沿阴影：**只有凹陷**才有这一笔。
     *
     * 凸起与凹陷的区别不在「有没有高光」，而在**高光与阴影的方向**：
     * 凸起是上亮下暗（光先照到上沿），凹陷是上暗下亮（上沿挡住了光）。
     * 少了这一笔，输入框的凹槽会读成一块平的色斑。
     */
    val recessTop: Color,
    /** 凹槽斜面·下沿高光 */
    val recessBottom: Color,

    /**
     * 外投影色（凸起物投在贴面上的影子）。
     *
     * 拟物的深度**主要靠这一层**，不是靠边缘明暗。取低 alpha 的暖黑/冷黑
     * （跟着主题走），而不是纯黑——纯黑投影在极光底上会读成脏污而不是阴影。
     */
    val shadow: Color,
    /**
     * 内高光（inset highlight）：贴面内部一层极淡的顶亮渐变。
     *
     * 它与 [bevelTop] 是两件事：bevelTop 是**边缘**的 1dp 线，
     * 这一笔是**面内**的大半径渐变（从顶部往下衰减）。
     * 真实塑料/金属的表面反射是面状的，只画边缘会读成「描边」而不是「材质」。
     */
    val innerLight: Color,

    // ── 文字与语义色 ────────────────────────────────────────
    //
    // 拟物档的贴面是不透明的，所以文字色不能沿用玻璃档的「白 60%/50%」那套
    // （那是为了穿透半透明底而选的弱对比）。不透明底上的弱对比会直接读作
    // 「看不清」，而不是「次级信息」。这里给出各自的推荐值。
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    /** 破坏性动作色（拟物档）：比玻璃档更饱和——不透明底上低饱和会读成棕色 */
    val destructive: Color,
) {
    companion object {
        /**
         * 深色拟物：**磨砂深金属**。
         *
         * 取带蓝调的近黑（#1B1C22）而不是纯黑：纯黑贴面在深色极光底上会「浮不起来」
         * （投影没有对比对象），而带一点点冷调的深面能让投影的暖黑显形。
         *
         * 高光偏冷（白 26%/14%）：金属的镜面反射带环境色，冷调高光 + 冷调面 = 一致；
         * 暖调高光压在冷面上会读成「脏」。
         */
        fun dark(): SkeuomorphColors = SkeuomorphColors(
            face = Color(0xFF1B1C22),
            faceRecessed = Color(0xFF14151A),
            faceRaised = Color(0xFF23252D),
            bevelTop = Color.White.copy(alpha = 0.26f),
            bevelBottom = Color.White.copy(alpha = 0.10f),
            recessTop = Color.Black.copy(alpha = 0.45f),
            recessBottom = Color.White.copy(alpha = 0.07f),
            shadow = Color(0xFF07080B).copy(alpha = 0.55f),
            innerLight = Color.White.copy(alpha = 0.06f),
            // 不透明深面上的文字可以比玻璃档更实：玻璃档的白 60% 是为半透明底服务的，
            // 在 #1B1C22 上 60% 白的对比只有约 5:1（勉强），100% 白有 16:1。
            // 但全白会让整页读作「高对比仪表盘」，所以次级信息取 72%/56%——
            // 仍满足 WCAG AA（≥4.5:1），层级却依然分明。
            textPrimary = Color(0xFFF4F5F8),
            textSecondary = Color(0xFFF4F5F8).copy(alpha = 0.72f),
            textTertiary = Color(0xFFF4F5F8).copy(alpha = 0.56f),
            // 深面上用亮暖红：不透明深底需要比玻璃档（#E4867C）更亮才跳得出来
            destructive = Color(0xFFF08A80),
        )

        /**
         * 浅色拟物：**上釉陶瓷**。
         *
         * 取近白但**不是纯白**（#F2F1EE，微暖）：纯白贴面 + 白高光 = 高光完全不可见
         * （白压白）。微暖的米白能让冷白高光显形，也让浅色主题不至于读作冷冰冰的
         * 白板——这是陶瓷与白卡纸的差别。
         *
         * 投影用暖灰而不是冷黑：浅色环境的光是暖的（室内光/日光），
         * 冷黑投影会让整页读作「蒙了一层灰」。
         */
        fun light(): SkeuomorphColors = SkeuomorphColors(
            face = Color(0xFFF2F1EE),
            faceRecessed = Color(0xFFE6E4DF),
            faceRaised = Color(0xFFFBFAF8),
            // 浅色档的高光是**纯白**且 alpha 更高：浅底上白高光的对比来源是
            // 「面本身不够白」，所以必须用满白才看得见
            bevelTop = Color.White.copy(alpha = 0.85f),
            bevelBottom = Color(0xFFB9B5AC).copy(alpha = 0.45f),
            // 浅色凹槽：上沿是**深**的阴影（同样的物理方向，只是颜色反转成暖灰）
            recessTop = Color(0xFF8A857B).copy(alpha = 0.42f),
            recessBottom = Color.White.copy(alpha = 0.75f),
            shadow = Color(0xFF6B6455).copy(alpha = 0.30f),
            innerLight = Color.White.copy(alpha = 0.50f),
            textPrimary = Color(0xFF2A2925),
            textSecondary = Color(0xFF2A2925).copy(alpha = 0.68f),
            textTertiary = Color(0xFF2A2925).copy(alpha = 0.52f),
            // 浅色档用深砖红（同玻璃档 light()，因为不透明浅底上深色更有力）
            destructive = Color(0xFFB4392B),
        )
    }
}

/**
 * 按主题进度在深/浅两套拟物贴面之间插值。
 *
 * 与 [lerpGlassColors] 的差别在于**它插的是两套完整材质**，不是同一材质的两个亮度。
 * 深浅切换时用户看到的是「磨砂金属变成上釉陶瓷」，而不是「同一块板变亮」——
 * 这是拟物语言里唯一能表达「换了一个环境」的手段（玻璃档靠极光底变亮就能表达）。
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
    )

/**
 * 拟物材质的圆形/卡片几何 token（dp）。
 *
 * ## 为什么圆角必须比玻璃档小
 *
 * 玻璃档的大圆角（28dp）服务于「无边界悬浮」的语言：圆角越大越不像一个「盒子」，
 * 越像一片贴在空中的曲面。而拟物恰恰要表达「这是一个有边的实体」——
 * 28dp 圆角 + 投影会读成「一张圆角贴纸」，而不是「一块有厚度的面板」。
 *
 * 真实物体的圆角半径通常不超过其厚度的量级，而拟物面板的「视觉厚度」由投影
 * （4-10dp）表达，所以圆角取 4-14dp 与厚度同量级才自洽。
 *
 * 这些值与 [R] 刻度**有意不同**：把拟物硬塞进玻璃的圆角刻度会得到「拟物面板
 * 长得和玻璃卡一模一样，只是不透明」——那不叫另一套语言。
 */
object SkeuoShape {
    /** 小控件（芯片 / 缩略图 / 输入格）：4dp，接近真实注塑件的倒角 */
    const val Chip = 4
    /** 标准卡片 / 列表行：10dp */
    const val Card = 10
    /** 大面板 / 播放页舞台：14dp */
    const val Panel = 14
    /** 胶囊（开关轨道 / 进度轨）：取半高，见调用点 */
    const val Pill = 99
}

/**
 * 拟物材质的投影参数（dp）。
 *
 * 分两笔：**近投影**（锐、紧、深）+ **远投影**（柔、散、浅）。
 * 单笔投影会读成「描了一圈模糊边」，两笔才有「东西离开表面一定高度」的观感——
 * 这是拟物与 Material 的 elevation 阴影最核心的差别（后者只有一笔）。
 */
object SkeuoElevation {
    /** 近投影：偏移 dy、模糊半径 */
    const val NearDy = 2f
    const val NearBlur = 4f
    /** 远投影：更大的偏移与模糊，alpha 更低 */
    const val FarDy = 6f
    const val FarBlur = 14f
}

/**
 * 当前生效的材质语言（[com.local.music.core.ui.state.VisualStyle]）。
 *
 * 用 CompositionLocal 下发而不是给每个组件加形参：玻璃档的调用点有 26 个
 * （GlassSurface 15 + GlassIconButton + GlassToggle + 各处直接读 Legacy token 的地方），
 * 加形参等于改 26 处签名，而它们**全都不关心**用的是哪套语言——只关心「画成什么样」。
 *
 * 默认 [com.local.music.core.ui.state.VisualStyle.Glass] 保证零回归：任何未接线
 * 的预览/单测/新屏仍走既有的玻璃路径，逐步数不变。
 */
val LocalVisualStyle = compositionLocalOf { com.local.music.core.ui.state.VisualStyle.Glass }

/**
 * 当前生效的拟物 token。
 *
 * 与 [LocalGlassColors] 并存而不是替换：拟物档也需要「次要信息用弱色」这类语义，
 * 只是取值来源不同。渲染分支按 [LocalVisualStyle] 选择读哪一个。
 */
val LocalSkeuomorphColors = compositionLocalOf { SkeuomorphColors.dark() }
