package com.local.music.core.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.local.music.core.ui.theme.GlassIcons
import com.local.music.core.ui.theme.GlassMotion
import com.local.music.core.ui.theme.GlassText
import com.local.music.core.ui.theme.LocalAuroraPalette
import com.local.music.core.ui.theme.LocalGlassColors
import com.local.music.core.ui.theme.R
import com.local.music.core.ui.theme.S
import com.local.music.core.ui.theme.onSeedColor

/**
 * 统一空态：**图标 / 标题 / CTA** 三件套（+ 可选副标题），视觉审计 E2 的落地件。
 *
 * ## 为什么是「三件套」而不是一句居中灰字
 *
 * 改造前全仓空态都是「13sp textTertiary 居中一句话」（曲库五个 tab、搜索无结果、
 * 详情页空歌单、队列面板各写一遍）。这句话同时承担三个互不相干的职责：
 * 「这里没有内容」（状态）、「为什么没有」（原因）、「你能做什么」（动作）。
 * 挤在同一行同一种字号里，结果是三件事**都没有被说清**：
 * - 没有物品感 —— 一句话不构成「一个位置」，用户读到的是「列表加载完了，只是没东西」，
 *   而不是「这里本应有一张专辑」；
 * - 没有轻重 —— 原因和动作与状态同权重，眼睛没有落点；
 * - 没有出路 —— 动作是一句祈使句（「去设置页扫描音乐」），但不可点（增-U7 已登记）。
 *
 * 三件套把这三个职责**分配给三个不同的视觉层**：图标给物体感（低对比，只是「这里」的
 * 占位符）、标题给状态（主文字色，唯一要读的一句话）、CTA 给动作（唯一可点、有实底）。
 * 层级不是靠调字号挤出来的，是靠**四档对比度 + 有/无形状**分工出来的。
 *
 * ## 为什么 CTA 最多只给一枚
 *
 * 空态是「无内容」的地方，用户此刻的注意力预算最低。放两个可点元素会让选择成本盖过
 * 内容本身的缺失感，而且必然有一个是次要的、看起来像凑数。所以本组件是
 * `actionLabel: String?`（单数）而不是列表：**给不出唯一主操作时，宁可不给**。
 * 曲库五个 tab 的空态因此没有 CTA —— 那里确实没有一个能直达的动作（新建歌单在歌单页，
 * 加歌在详情页），硬塞一个跳转等于替用户决定他没想到要做的事。
 *
 * CTA 用 `palette.seed` 实底 + [onSeedColor] 前景：这是全 App 既有的「主操作」语言
 * （详情页「播放」胶囊同款，见 PlaylistDetailScreen.DetailHero）。空态里的动作是这一屏
 * 唯一能做的事，所以它就该穿主操作的衣服；但**不借用**播放胶囊的 seed 投影 ——
 * 投影是「主操作浮在内容之上」的语言，空态没有内容可浮，加上去只会显得空的地方在叫。
 *
 * ## 动画
 *
 * 入场是轻微淡入 + 上移 8dp，时长走 [GlassMotion.tweenPage]（**已内置动效强度乘数**
 * `GlassMotion.scale`，设置页「克制 ×0.6 / 增强 ×1.4」自动生效，不要另乘一次）。
 * 动画值只在 `graphicsLayer` 的绘制期读取 —— 与 `rowPressFeedback` / `WaveIndicator`
 * 同一条红线：组合期读 State 会让整棵子树按帧重组，而这只是 8dp 的位移。
 * 紧凑形态**不做**入场：歌单墙里几十张卡同时淡入是一片噪声，且卡片没有「刚刚变空」的
 * 时序语义。
 *
 * ## 横屏 / 矮容器
 *
 * 本组件**不包 `verticalScroll`**：它在曲库页是 LazyColumn 的一个 item，item 的滚动轴
 * 约束是无限的，`verticalScroll` 在这种情况下 maxValue 恒为 0，是个假安全网（写了也
 * 不生效，还会让内层 Column 变成可滚动容器、与宿主抢手势）。
 * 真正的保证是两条：
 * 1. 宿主本身可滚（曲库页 / 详情页 / 面板内容列全部满足）——内容再高都能到达，不会裁切；
 * 2. 横屏时**自动收紧几何**：图标块 64→48、竖向留白 32→16、图标-标题缝 16→12。
 *    横屏可用高度约 360dp，屏内已被页头 + 搜索 + tab 吃掉约 200dp，不收紧的话
 *    「去授权」这枚 CTA 会被挤到首屏之外 —— 而它是权限被拒时应用内**唯一**的授权入口，
 *    落到折线以下等于半失效。
 *    判据用 `screenHeightDp < screenWidthDp`（与 PlayerScreen 的横屏判定同源），
 *    **不做成 Boolean 入参**：横屏是屏幕事实而非调用方的语义选择，让每个调用点各判一次
 *    必然漂移（一处漏判就是一处裁切）。
 *
 * ## 可访问性
 *
 * 语义分成**两个节点**，而不是整块 `mergeDescendants = true`：
 * - 图标 + 标题 + 副标题合成一个节点（`mergeDescendants`），标题只朗读一次，
 *   图标 `contentDescription = null`（纯装饰，语义由标题承担）；
 * - CTA 是**独立**可聚焦节点，带 `role = Button` 与和可见文字同源的 `contentDescription`。
 *
 * 不把 CTA 一起合并的理由：合并后整块变成一个可点节点，读屏会把「这里没有专辑」念成
 * 一个按钮并提示「双击激活」—— 用户点标题文字就触发了授权/跳转，是一次假承诺。
 * 空态的陈述部分应当只被**读**，动作部分才应当被**按**，两者必须是两个节点。
 *
 * @param icon 语义化图标（[GlassIcons]，24 viewBox、1.8/1.9 笔画口径）。五个 tab 各给各的，
 *   **不要**所有空态共用一枚 —— 图标是「这里本该有什么」的唯一线索。
 * @param title 状态主文案（主文字色、16 SemiBold）：一句话说清「没有什么」。
 * @param subtitle 可选原因/补充（次级文字色、13 Normal）。
 * @param actionLabel 唯一 CTA 的可见文字；同时作为它的读屏描述（同一个变量，不可能漂移）。
 * @param onAction CTA 点击。`actionLabel` 为 null 或本参数为 null 时不渲染 CTA。
 * @param compact 紧凑形态：图标 + 短文案，收进一枚 md 玻璃胶囊（见 [CompactEmptyState]）。
 */
@Composable
fun GlassEmptyState(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    compact: Boolean = false,
) {
    if (compact) {
        CompactEmptyState(icon, title, subtitle, actionLabel, onAction, modifier)
        return
    }

    val glass = LocalGlassColors.current
    // 横屏收紧（见 KDoc「横屏 / 矮容器」）：screenHeightDp 在部分预览/测试环境下会是 0，
    // 加 > 0 判据避免 0 < 0 之外又出现「0 高也算横屏」的误判
    val config = LocalConfiguration.current
    val tight = config.screenHeightDp > 0 && config.screenHeightDp < config.screenWidthDp

    // 入场：只在首次组合播放一次。LazyColumn item 滚出再滚回会重放，这是可接受的
    // （空态本身就是整页唯一内容，正常路径下不会滚出去）
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, GlassMotion.tweenPage()) }

    Column(
        modifier
            .fillMaxWidth()
            .graphicsLayer {
                // 绘制期读值（不是组合期 `by`）：位移/透明度只影响绘制，不值得每帧重组
                val p = appear.value
                alpha = p
                translationY = (1f - p) * 8.dp.toPx()
            }
            .padding(
                horizontal = S.PageMargin.dp,
                // 32 而非刻度里更省的 24：空态是整屏最空的地方，留白是它唯一的内容 ——
                // 收紧会把它读成「一张没加载完的卡片」
                vertical = if (tight) S.Gap16.dp else S.Gap32.dp,
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // ── 陈述区（合并成一个语义节点，标题只念一次）──
        Column(
            Modifier.semantics(mergeDescendants = true) {},
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // 图标块：lo 玻璃圆盘 = 全 App 最低层级容器的既有写法（Glass.kt 的 D3 分层表：
            // Lo 只有填充、无描边无顶线）。这里**不**套描边 —— 空态图标是装饰不是控件，
            // 描边是「这是一个可以按的东西」的边界定义（见 GlassIconButton 的 KDoc）
            Box(
                Modifier
                    .size(if (tight) 48.dp else 64.dp)
                    .clip(CircleShape)
                    .background(glass.lo),
                contentAlignment = Alignment.Center,
            ) {
                // 有意 null、非遗漏：装饰性图形，语义由下方的 title/subtitle 承担。
                // 色取 textTertiary（0.50）：图标要弱于标题（textPrimary）但不能弱到看不见 ——
                // 它不是装饰纹样，是「这里本该有一张专辑/一首歌」的物体暗示
                Icon(icon, null, Modifier.size(if (tight) 22.dp else 30.dp), tint = glass.textTertiary)
            }
            Spacer(Modifier.height(if (tight) S.Gap12.dp else S.Gap16.dp))
            Text(
                title,
                // 16 SemiBold：比列表行主标题（15）高一档、比区块标题（16 SemiBold）同级 ——
                // 空态标题在信息结构上就是「一个区块的标题」，只是下方没有内容
                style = GlassText.Heading,
                color = glass.textPrimary,
                textAlign = TextAlign.Center,
            )
            if (subtitle != null) {
                Spacer(Modifier.height(S.Gap8.dp))
                Text(
                    subtitle,
                    style = GlassText.Body,
                    // 副标题走 textSecondary 而非 textTertiary：它是「为什么空」的解释，
                    // 用户需要读它；textTertiary（0.50）在这个位置会滑到「可读但不重要」，
                    // 而空态里没有别的内容可以替它承担注意力
                    color = glass.textSecondary,
                    textAlign = TextAlign.Center,
                )
            }
        }
        // ── 动作区（独立节点，见 KDoc「可访问性」）──
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(if (tight) S.Gap16.dp else S.Gap20.dp))
            EmptyStateCta(actionLabel, onAction)
        }
    }
}

/**
 * 空态的唯一 CTA：`palette.seed` 实底胶囊 + [onSeedColor] 前景。
 *
 * 材质**照抄**详情页「播放」胶囊（pressScale 0.95、CircleShape、seed 实底），只省掉
 * 那枚 seed 投影 —— 理由见 [GlassEmptyState] 的 KDoc。不新造材质是本条的硬约束：
 * 空态按钮一旦有自己的一套观感，用户就得重新学「这个胶囊和播放胶囊是不是一回事」。
 *
 * 读屏：`contentDescription` 与可见文字**同一个变量**（`label`），不存在双源漂移；
 * 显式写它而不是靠子树文字合并，是因为合并出来的描述可能带上外层节点顺序的不确定性，
 * 而这是一屏唯一的动作，必须一次念准。
 */
@Composable
private fun EmptyStateCta(label: String, onAction: () -> Unit) {
    val palette = LocalAuroraPalette.current
    // onSeedColor() 是 @Composable（读 LocalAuroraPalette），必须在组合期取值，
    // 不能塞进 clickable 的 lambda（那不在组合作用域内）
    val onSeed = onSeedColor()
    val interactionSource = remember { MutableInteractionSource() }
    Row(
        Modifier
            .pressScale(interactionSource, pressed = 0.95f)
            .clip(CircleShape)
            .background(palette.seed)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onAction)
            .semantics {
                this.role = Role.Button
                this.contentDescription = label
            }
            .padding(horizontal = S.Gap20.dp, vertical = S.Gap8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = onSeed, style = GlassText.BodyMedium, maxLines = 1)
    }
}

/**
 * 紧凑形态：图标 + 短文案，收进一枚 md 玻璃胶囊。用于**卡片 / 封面这类有限空间**。
 *
 * ## 为什么自带玻璃底，而不是像完整形态那样直接画在背景上
 *
 * 紧凑形态的典型落点就是封面之上（空歌单卡的 1:1 占位纹样）。占位纹样是彩色渐变，
 * 而主题文字色必须跟随深浅两态：深色模式 textPrimary 是白、浅色模式是近黑。
 * 浅色模式那块渐变不会跟着变浅（ArtStyle 的设计稿渐变是固定色），
 * 于是近黑文字直接压上去 ≈ 不可读。玻璃 md 档 fill 是深浅两态各自校正过的明度层
 * （深色白 10% / 浅色白 48%），文字画在它之上，两态都落在可读明度带里 —— 这是全 App
 * 「文字压在图像上」唯一既有的解法（chromeScrim 只服务迷你条/导航，不适用于卡片，
 * 因为它是 0.74–0.80 的实心遮罩，会把封面整个抹掉）。
 *
 * md 而非 lo：light 档 lo 只有白 32%，压在深紫渐变上明度仍约 0.2，近黑文字对比只有
 * ≈3.6:1（13sp 小字不够）；md 的白 48% 把底抬到约 0.35，对比回到 ≈5.7:1。
 * 这个档位**由对比度推算决定**，不是凭观感挑的。
 *
 * 胶囊形状（R.Full）而不是卡片圆角：它要读成「一枚标签」而不是「卡片里的又一张卡」
 * —— 后者正是 E1（同一内容三种容器待遇）在清算的毛病。
 */
@Composable
private fun CompactEmptyState(
    icon: ImageVector,
    title: String,
    subtitle: String?,
    actionLabel: String?,
    onAction: (() -> Unit)?,
    modifier: Modifier,
) {
    val glass = LocalGlassColors.current
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        GlassSurface(
            level = GlassLevel.Md,
            shape = RoundedCornerShape(R.Full),
            // 非可点容器：这里只承载提示，点击一律由所在卡片承担（不新增交互路径）
        ) {
            Row(
                Modifier.padding(horizontal = S.Gap12.dp, vertical = S.Gap8.dp),
                verticalAlignment = Alignment.CenterVertically,
                // 6dp 图标-标签缝：S 刻度的微缝豁免区间（0–6dp），保持裸值
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                // 有意 null、非遗漏：装饰性图形，语义由同一枚胶囊里的 title 承担
                Icon(icon, null, Modifier.size(16.dp), tint = glass.textTertiary)
                Column {
                    Text(
                        title,
                        style = GlassText.BodyMedium,
                        color = glass.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (subtitle != null) {
                        Text(
                            subtitle,
                            style = GlassText.Caption,
                            color = glass.textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        // 紧凑形态也允许带 CTA（有就给，只是排在胶囊下方）：宁可多一枚按钮，
        // 也不要静默丢掉调用点传进来的动作 —— 静默丢参数是比拥挤更难查的缺陷
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(S.Gap12.dp))
            EmptyStateCta(actionLabel, onAction)
        }
    }
}

/**
 * 空歌单卡（视觉审计 §11.1 ⑤）：0 首歌的歌单在封面墙上长什么样。
 *
 * ## 为什么需要它
 *
 * 0 首歌的歌单卡与正常卡长得**完全一样**：整块占位纹样封面 + 下方「0 首」。1:1 封面改版
 * 之后这块空白被放大了一倍（§11.1 ⑤），观感上像是「封面没加载出来」，而不是
 * 「这个歌单还是空的、去加歌」。空态三件套的卡片级特化就是修这件事：封面中央给一枚
 * 小图标 + 一句「去添加歌曲」，把「空」从**故障感**变成**下一步**。
 *
 * ## 为什么不去改 PlaylistCard
 *
 * 本轮写域不含 `Lists.kt`（PlaylistCard 的定义处），而 PlaylistCard 没有「封面叠加层」
 * 这种插槽 —— 它能接受的只有 title/meta/art/封面地址。所以这里把卡片外壳按
 * PlaylistCard 的结构复刻一遍（md 玻璃 + Card18 + 1:1 封面 + lo 文字带），只在封面
 * 区域多叠一层紧凑空态。
 *
 * **已知代价（如实记录）**：卡片外壳的结构在这里出现第二份。两处**必须同源演进**：
 * 改 PlaylistCard 的圆角/封面比例/文字带内距时，这里要同步。正解是给 PlaylistCard
 * 加一个 `coverOverlay: @Composable BoxScope.() -> Unit?` 插槽，让本函数改回一次普通
 * 调用 —— 那需要动 `Lists.kt`，属下一轮。
 * 文字带内距 `top = 10.dp` 刻意与 PlaylistCard 保持一致而非归位到 12：两张卡在同一行
 * 并排，2dp 的高差会让文字带基线错开，而「两列等高」是 §11.1 已验收过的观感红线。
 *
 * ## 不新增交互路径
 *
 * 卡片点击行为完全不变（仍进详情页，加歌入口在详情页顶栏的 ＋）。中央那句「去添加歌曲」
 * **不是按钮**：它是提示，点它等于点卡片 —— 所以它没有 clickable、不进无障碍焦点序列，
 * 读屏靠卡片的 role = Button + 标题「歌单名，0 首」进入。
 */
@Composable
fun EmptyPlaylistCard(
    title: String,
    meta: String,
    art: ArtStyle,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
) {
    val glass = LocalGlassColors.current
    GlassSurface(
        modifier = modifier,
        level = GlassLevel.Md,
        // C4：18dp = R.Card18（标准卡片容器），与 PlaylistCard 同档
        shape = RoundedCornerShape(R.Card18.dp),
        onClick = onClick,
        onLongClick = onLongClick,
    ) {
        Column {
            Box(Modifier.fillMaxWidth().aspectRatio(1f)) {
                // 封面仍是那一块占位纹样（卡片的色相身份来自它，去掉会让空歌单在墙里
                // 变成一块无标识的灰板）；圆角 0 由外层 GlassSurface 一次裁出，
                // 自己再带圆角会形成双圆弧（C4 嵌套圆角）
                ArtThumb(art, Modifier.matchParentSize(), RoundedCornerShape(0.dp))
                GlassEmptyState(
                    icon = GlassIcons.NoteAdd,
                    title = "去添加歌曲",
                    compact = true,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(glass.lo)
                    // top 10dp 与 PlaylistCard 逐字一致（理由见函数 KDoc「已知代价」）
                    .padding(start = S.Gap12.dp, end = S.Gap12.dp, top = 10.dp, bottom = S.Gap12.dp),
            ) {
                Text(
                    title, color = glass.textPrimary, style = GlassText.BodyMedium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    meta, color = glass.textTertiary, style = GlassText.Micro,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
