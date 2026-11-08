package com.local.music.core.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import com.local.music.core.ui.theme.GlassIcons
import com.local.music.core.ui.theme.GlassMotion
import com.local.music.core.ui.theme.GlassSpring
import com.local.music.core.ui.theme.GlassText
import com.local.music.core.ui.theme.LocalAuroraPalette
import com.local.music.core.ui.theme.LocalGlassColors
import com.local.music.core.ui.theme.R
import com.local.music.core.ui.theme.auroraAccentColor

/**
 * SongRow 的行高（9dp 上下内边距 + 内容高度）——队列滚动定位与拖拽重排共享的数学基准。
 *
 * **为什么从 `val SongRowHeight = 66.dp` 改成 @Composable 函数（修 03 报告 9b / P3-60）**
 *
 * 旧实现是一个写死的 `66.dp` 常量，注释里自己也承认「不随字体缩放」：无障碍大字号
 * （fontScale ≥ 1.3）下 15sp 标题 + 2dp + 12sp 副标题的 intrinsic 高度会超过 48dp 的
 * 缩略图高度，SongRow **本身**会自然长高（它没有约束高度，是内容撑开的）——
 * 但常量还是 66dp。于是「视觉行高 78dp、数学行高 66dp」，两处依赖它的计算全部失准：
 *
 * - 队列面板（Sheet.QueueSheet）的「定位到当前曲」按 `safeIndex * 66dp` 计算滚动偏移，
 *   大字号下一首往下漂 12dp，几十首后当前曲直接滚出视口；
 * - 拖拽重排（ReorderableColumn）拿它换算手指落点对应的目标槽位，行高偏小会让长拖
 *   越拖越偏，松手落到错误的行——这是**不可撤销**的数据错位，比滚动偏移严重得多。
 *
 * 修法：把行高改成「由当前 fontScale 推导出来的函数」，让视觉与数学同源。推导式：
 *   content = max(48dp 缩略图, 标题行高 + 2dp + 副标题行高)
 *   row     = 9dp*2 + content
 * 标题 15sp / 副标题 12sp，行高按 1.35× 字号估算（贴近 Compose 默认 paragraph 行高，
 * 略偏保守——宁可算高一点让下一行轻微留白，也不要算矮导致当前曲定位漂出视口）。
 *
 * **为什么用 `sp.toDp()` 而不是读 `LocalDensity.fontScale` 自己乘**：`TextUnit.toDp()`
 * 本身就是「sp → 布局 dp」的唯一正确换算（内部已含 fontScale 与密度），自己乘 fontScale
 * 会漏掉 density 归一化，在非 1x 密度屏上直接算错。
 *
 * 代价（如实记录）：为了能读到 fontScale，这个值必须在组合期求值，所以它从
 * 「顶层 val」变成了「带 @Composable getter 的 val」。
 *
 * **刻意保持同名、同类型（Dp）、同调用写法**：`SongRowHeight` 是跨文件的共享符号
 * （Sheet.QueueSheet 两处、PlaylistDetailScreen 一处都在用，见各调用点的注释）。
 * 用 `val SongRowHeight: Dp @Composable get() = ...` 而不是改写成普通函数，
 * 是为了让全部调用点 `rowHeight = SongRowHeight` **一个字都不用改**——
 * 一旦改成 `fun SongRowHeight()`，每个调用点都得补括号，而其中几处是别的同事正在改的文件，
 * 会造成无谓的跨人冲突。带 @Composable getter 的 val 在组合作用域里读法完全相同。
 *
 * 剩下的约束：只能在组合作用域内读取。当前 4 个调用点全部满足；将来若要在非组合处使用，
 * 需要把值由调用方算好传下去，**不要**回头再引入一个写死的常量副本
 * （那就又回到「视觉一种高度、数学另一种高度」的双源漂移，本函数的全部意义就没了）。
 *
 * **本轮（A1）追加的耦合关系**：[SongRow] 的标题/副标题已改读 `GlassText.Row`（15sp）与
 * `GlassText.Caption`（12sp），下面的推导式**必须**继续跟着这两个 token 走。
 * 目前 token 字号与这里的字面量恰好一致，所以数值不变；但若将来有人调
 * `GlassType.BodyEmph` / `GlassType.Caption`，这里必须同步——否则又会重新裂成
 * 「token 是 15sp、行高数学按 14sp 算」的隐性失配。行高系数 1.35× 是刻意的保守估计
 * （token 的 lineHeight 是 21/16sp，即 1.4/1.33×），取大值可保证不算矮。
 */
val SongRowHeight: Dp
    @Composable get() {
        val density = LocalDensity.current
        // 文本列高度：标题 15sp + 2dp 间隔 + 副标题 12sp，各自按 1.35× 行高
        val textColumn = with(density) {
            (15.sp.toDp() * 1.35f) + 2.dp + (12.sp.toDp() * 1.35f)
        }
        // 内容高度取缩略图与文本列的较大者：正常字号下缩略图 48dp 占优（行高 66dp 不变），
        // 大字号下文本列反超，行高随之上浮——这正是我们要的行为
        return 18.dp + maxOf(48.dp, textColumn)
    }

/**
 * 列表行的按压反馈：一层由 `pressedOverlay` 驱动的极淡底色，**不缩放**。
 *
 * **为什么列表行不能用 pressScale**（视觉审计 A6，2026-11-08）
 *
 * 改造前 SongRow / SettingRow 都是 `pressScale(0.98f)`。0.98 这个值看起来「很克制」，
 * 但它缩放的是**整行**——文字、封面、图标、尾部控件全部一起向中心收缩。单行按下时
 * 这 2% 是察觉不到的，可它带来两个真实问题：
 *
 * 1. **长列表里像抖动而不是按压**：相邻行静止、当前行缩小，人眼把这种相对位移读成
 *    「列表在动」。滚动惯性未停时按下尤其明显——两种位移叠加，观感很廉价。
 * 2. **缩放在视觉上不表达「按的是哪一行」**：缩放是位置/尺寸信号（那是拖拽与弹窗的语言），
 *    而列表行的按压要回答的是「我按的这一行是哪一行」——这是**明度**问题。
 *
 * 所以改用一层 `pressedOverlay`（深色白 6%，浅色黑 5%）铺在行的范围内：
 * 底色变化不移动任何像素，只在按下那一瞬间把整行轻微提亮。这也是系统原生的做法
 * （ColorOS/Android 的 ripple 本质就是明度层，只是还带扩散动画）。
 *
 * **为什么用弹簧而不是 tween**：按压是一来一回的短动作，tween 是「定时播放」——
 * 快速连点会让两次 tween 排队重启，出现「按下去还没回来又往下走」的僵硬感；
 * 弹簧继承当前速度、可被打断，这是 ColorOS 原生手感的来源
 * （`GlassSpring.gentle()` 阻尼接近临界、不振荡，用于按压回弹正合适）。
 *
 * **为什么画方角矩形、而不是在这里也声明 R.Card14 圆角**：这个助手的两个使用者语境不同——
 * [SongRow] 自己带 `clip(RoundedCornerShape(Card14))`（行有 6dp 外边距，需要圆角），
 * 而 [SettingRow] 是设置卡内部**通栏**的一行（无外边距，行底就该是竖直边缘）。
 * 同一个硬编码圆角在两边必有一边是错的。Compose 的 `clip()` 会裁剪其**之后**的绘制，
 * 所以这里只负责铺一块满宽的明度层，圆角由调用方已有的 clip 决定——
 * 这样圆角只有一个来源，不会出现「助手圆角 14、行 clip 圆角 18」的错位。
 *
 * 注意本函数只负责**画**反馈，不挂 clickable / semantics——交互仍由调用点的
 * `clickable(interactionSource = ...)` 承担，两者靠同一个 source 串起来。
 * 这样纯展示行（无 onClick）也不会误带按压态。
 */
@Composable
fun Modifier.rowPressFeedback(source: MutableInteractionSource): Modifier {
    val pressed by source.collectIsPressedAsState()
    // 刻意**不**用 `by` 解构动画值：把 State 对象本身带进 drawBehind，让数值只在绘制期读取。
    // 组合期读（`val a by ...`）会让这一整行在按下的每一帧重组；而底色变化只影响绘制结果，
    // 不值得付重组代价——同一红线在 WaveIndicator 与 ArtThumb 里都已登记过。
    // 弹簧也吃得住 alpha 的「快上慢回」：gentle() 阻尼接近临界，按下与松手的
    // 收束时长天然对称，不需要拆成两条曲线。
    val overlay = animateFloatAsState(
        targetValue = if (pressed) 1f else 0f,
        // 显式写 <Float>：本次改造在无构建验证的条件下交付，去掉一处「靠返回类型推断
        // 去解 spring<T> 的 T」的依赖，类型对不上会直接编译失败——这个风险不值得省两个字符
        animationSpec = GlassSpring.gentle<Float>(),
        label = "rowPressOverlay",
    )
    val color = LocalGlassColors.current.pressedOverlay
    return drawBehind {
        // 绘制期读值：只在按下/松手的那几帧触发重绘，不产生重组
        val a = overlay.value
        // a == 0 时整段跳过：绝大多数帧行都不处于按压态，不该白付一次绘制指令
        if (a > 0f) drawRect(color.copy(alpha = color.alpha * a))
    }
}

/**
 * 大标题页头：30sp 粗体 + 可选右侧玻璃圆钮（.hd），可挂第二动作（渲染在主动作左侧）。
 *
 * **读屏语义（无障碍 P0-4）**：actionLabel / action2Label 是这两个圆钮的读屏描述，
 * 且**现在不再是可选的疏漏点**——旧实现把 `Modifier.semantics { contentDescription = it }`
 * 挂在 `if (label != null)` 分支里，null 时连 semantics 都不加，于是按钮成了一个
 * 「无描述的可点节点」：TalkBack 停上去只念一句「按钮」，盲用户完全不知道它是什么。
 *
 * 更麻烦的是这种漏法**不可见**：调用处少传一个可选参数，编译通过、视觉正常、
 * 审计 grep 也未必数得出来。本项目的 PlaylistScreen 就正是这个状态（只传了
 * actionIcon/onAction，没传 actionLabel），于是「新建歌单」这个入口对读屏是哑的。
 *
 * 兜底策略：label 为 null 时**回退到页头标题 + 序号**，保证节点永远有描述可念。
 * 这是个刻意的降级——文案不完美（「音乐 操作 1」不如「切换深浅色模式」精确），
 * 但胜过完全哑掉；同时它逼调用方去补精确文案（不补就念得难听，容易在自查时被发现）。
 */
@Composable
fun PageHeader(
    title: String,
    modifier: Modifier = Modifier,
    actionIcon: ImageVector? = null,
    onAction: (() -> Unit)? = null,
    actionLabel: String? = null,
    action2Icon: ImageVector? = null,
    onAction2: (() -> Unit)? = null,
    action2Label: String? = null,
) {
    Row(
        modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 12.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        // A1：字号/字重/行高整体读 GlassText.Display，不再在调用点手写三连。
        // 同时**删掉**原来的 letterSpacing = (-0.6).sp（B5）：负字距是拉丁排版的压排手段，
        // CJK 字形等宽且笔画已顶到字身框，压字距只会让笔画互相挤，30sp 大标题上尤其明显。
        Text(title, color = LocalGlassColors.current.textPrimary, style = GlassText.Display)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (action2Icon != null && onAction2 != null) {
                GlassIconButton(
                    onClick = onAction2,
                    // 语义恒挂（不再 if/else 分叉）：没有精确文案时用「标题 + 操作 2」兜底，
                    // 具体取舍见上方 KDoc
                    modifier = Modifier.semantics {
                        this.role = Role.Button
                        this.contentDescription = action2Label ?: ListsA11y.headerActionFallback(title, 2)
                    },
                ) {
                    // 有意 null、非遗漏：语义已挂在 GlassIconButton 的 modifier 上，
                    // 子图标再给描述会让读屏把同一句话念两遍
                    Icon(action2Icon, null, Modifier.size(20.dp), tint = LocalGlassColors.current.textPrimary)
                }
            }
            if (actionIcon != null && onAction != null) {
                GlassIconButton(
                    onClick = onAction,
                    modifier = Modifier.semantics {
                        this.role = Role.Button
                        this.contentDescription = actionLabel ?: ListsA11y.headerActionFallback(title, 1)
                    },
                ) {
                    // 有意 null、非遗漏（同上）
                    Icon(actionIcon, null, Modifier.size(20.dp), tint = LocalGlassColors.current.textPrimary)
                }
            }
        }
    }
}

/** 玻璃搜索胶囊：44h / 圆角 22（.searchbox） */
@Composable
fun GlassSearchBox(
    text: String,
    placeholder: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    GlassSurface(
        modifier = modifier.fillMaxWidth().height(44.dp),
        level = GlassLevel.Md,
        shape = RoundedCornerShape(R.Full),
        onClick = onClick,
    ) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // 装饰性图标，**有意 null、非遗漏**：整条胶囊作为 GlassSurface 的可点区域，
            // 语义由同一行里的 text（占位符「搜索歌曲、专辑、艺术家」或已输入词）承担。
            // 这里若再给「搜索」描述，读屏会念成「搜索，搜索歌曲、专辑、艺术家」——
            // 同一件事说两遍。
            Icon(GlassIcons.Search, null, Modifier.size(18.dp),
                tint = if (placeholder) LocalGlassColors.current.textSecondary else LocalGlassColors.current.textPrimary)
            Text(
                text, style = GlassText.Row,
                color = if (placeholder) LocalGlassColors.current.textSecondary else LocalGlassColors.current.textPrimary,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** tab 胶囊行：选中填充 seed + 投影（.tabs/.tab） */
@Composable
fun GlassTabs(
    tabs: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalAuroraPalette.current
    val glass = LocalGlassColors.current
    Row(
        modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, bottom = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        tabs.forEachIndexed { index, label ->
            val on = index == selected
            val interactionSource = remember { MutableInteractionSource() }
            // A3-02：选中态的填充/描边/文字色/投影四项全走 tweenHover——旧实现 if(on) 直接换
            // Modifier 链，四者瞬变（投影突现突失最扎眼）。0dp 投影等价无投影
            val bg by animateColorAsState(if (on) palette.seed else glass.lo, GlassMotion.tweenHover(), label = "tabBg$index")
            val borderColor by animateColorAsState(
                if (on) Color.Transparent else glass.strokeSoft, GlassMotion.tweenHover(), label = "tabBorder$index",
            )
            val textColor by animateColorAsState(
                if (on) Color.White else LocalGlassColors.current.textSecondary, GlassMotion.tweenHover(), label = "tabText$index",
            )
            val shadowElevation by animateDpAsState(if (on) 6.dp else 0.dp, GlassMotion.tweenHover(), label = "tabShadow$index")
            Box(
                Modifier
                    .pressScale(interactionSource, pressed = 0.95f)
                    // .tab.on：box-shadow 0 6px 16px seed 45%（elevation 动画，颜色恒定）
                    .shadow(
                        shadowElevation, RoundedCornerShape(R.Full), clip = false,
                        ambientColor = palette.seed.copy(alpha = 0.45f),
                        spotColor = palette.seed.copy(alpha = 0.45f),
                    )
                    .clip(RoundedCornerShape(R.Full))
                    .background(bg)
                    .border(1.dp, borderColor, RoundedCornerShape(R.Full))
                    .clickable(interactionSource = interactionSource, indication = null) { onSelect(index) }
                    .semantics {
                        // 读屏语义：tab 角色 + 选中态（与 Chrome.kt 底导航 GlassNavBar 同款）
                        this.role = Role.Tab
                        this.selected = on
                    }
                    .padding(horizontal = 15.dp, vertical = 7.dp),
            ) {
                // A1：13sp 档 token 化。选中态用 BodyMedium（13 Medium）、未选中用 Body（13 Normal）——
                // 与改造前「fontSize 13 + 字重条件」的观感完全一致，只是把「选中加粗」这个层级
                // 信号从裸 fontWeight 改成两个 token 的选择，字重不再是一个可以随手填的值。
                Text(label, style = if (on) GlassText.BodyMedium else GlassText.Body, color = textColor)
            }
        }
    }
}

/** 歌曲行：48dp 圆角12 缩略图 + 标题/副标题 + 时长或播放波形（.row）
 *  onMoreClick 提供时"更多"是独立按钮；onRemoveClick（队列行）与 onLongClick（操作菜单）同理。
 *  onRemoveClick 优先级高于 onMoreClick（队列行不需要菜单，只要移除）。
 *  selected = 多选选中态（添加歌曲面板）：标题走强调色、尾部「已选」标记替换时长。 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun SongRow(
    song: DemoSong,
    modifier: Modifier = Modifier,
    playing: Boolean = false,
    squareThumb: Boolean = false,
    trailing: String? = song.duration,
    onMoreClick: (() -> Unit)? = null,
    onRemoveClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit = {},
    wavePlaying: Boolean = true,
    selected: Boolean = false,
) {
    val palette = LocalAuroraPalette.current
    val interactionSource = remember { MutableInteractionSource() }
    val actionInteractionSource = remember { MutableInteractionSource() }
    val rowClickable = if (onLongClick != null) {
        Modifier.combinedClickable(
            interactionSource = interactionSource,
            indication = null,
            onClick = onClick,
            onLongClick = onLongClick,
        )
    } else {
        Modifier.clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
    }
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp)
            // C4：14dp 是 R 刻度里的 Card14（列表行卡片），不再裸写数值
            .clip(RoundedCornerShape(R.Card14.dp))
            // A6：行级缩放改按压底色（见 rowPressFeedback 的 KDoc）。
            // 旧实现 pressScale(0.98) 只把整行缩 2%，长列表里文字/封面一起位移，
            // 视觉上更像「抖动」而不是「按下了」；底色反馈只动明度，位置完全静止。
            .rowPressFeedback(interactionSource)
            .then(rowClickable)
            // ── 读屏语义（无障碍 P0-4）──
            //
            // 用户能到达这里的方式只有一个：整行可点（点行 = 播放/选中）。但 Compose 的
            // clickable 只提供 onClick 动作，**不会自动给 role**，于是 TalkBack 把整行念成
            // 「歌名，副标题」而不带任何「按钮」信息——盲用户无法判断这一行是可操作的，
            // 只会以为是一段文字。补 role = Button 之后读屏会念
            // 「歌名，副标题，按钮，双击即可播放」。
            //
            // 这里**没有**写 contentDescription：行内的歌名/副标题/「已选」/时长都是可见
            // 文字，clickable 本身带 mergeDescendants，会自动按子树文字顺序拼出描述。
            // 写死一份 contentDescription 反而会造成「界面文案 vs 读屏文案」双源漂移
            // （改文案时只改了一处，审计也查不出来，只有盲用户会撞上）。
            //
            // selected 必须显式暴露：多选场景（AddSongsSheet 添加歌曲面板）里 `selected`
            // 目前**只改颜色 + 尾槽显示「已选」**。颜色对读屏毫无意义，而「已选」两个字
            // 埋在尾槽文字里、又会被「时长」槽位替换掉（同一时刻只有一种尾槽）。
            // 补 selected 语义后读屏直接念「已选中」，这是 checkbox 类控件唯一可靠的表达。
            //
            // 注意：本行的 role 用 Button 而不是 Checkbox，因为 SongRow 在绝大多数场景
            // （曲库/队列/搜索结果）里就是「点了播放」的按钮；只有多选面板才是切换态，
            // 而那时 selected 语义已经补足，没必要为一个多场景复用组件引入两种角色分支。
            .semantics {
                this.role = Role.Button
                if (selected) this.selected = true
            }
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ArtThumb(
            song.art, Modifier.size(48.dp),
            RoundedCornerShape(R.Thumb10.dp),
            bordered = true,
            // coverBitmap 恒为 null（扫描不再预解码全库）；真封面经 artworkUri 按需解码
            bitmap = song.coverBitmap,
            artworkUri = song.artworkUri,
        )
        Column(Modifier.weight(1f)) {
            Text(
                song.title, style = GlassText.Row, maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                // 播放/选中态强调色用 auroraAccentColor()（浅色回退 seed）：seed3 过浅压不住浅色背景
                color = if (playing || selected) auroraAccentColor() else LocalGlassColors.current.textPrimary,
            )
            Spacer(Modifier.height(2.dp))
            Text(song.subtitle, style = GlassText.Caption, color = LocalGlassColors.current.textTertiary,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        // A3-03：尾槽随播放/选中/操作态交叉淡化（tweenHover）——旧实现 if 硬换，
        // 波形瞬现瞬消、时长文字与之硬替。targetState 收敛为槽位标识，
        // 只有槽位变化才触发过渡（同一槽位内的参数变化走各自内部逻辑）
        AnimatedContent(
            targetState = when {
                playing -> "wave"
                selected -> "selected"
                onRemoveClick != null -> "remove"
                onMoreClick != null -> "more"
                else -> "trailing"
            },
            transitionSpec = {
                fadeIn(GlassMotion.tweenHover()) togetherWith fadeOut(GlassMotion.tweenHover())
            },
            label = "songRowTail",
            contentAlignment = Alignment.CenterEnd,
        ) { tail ->
            when (tail) {
                "wave" -> {
                    WaveIndicator(barCount = 4, playing = wavePlaying)
                }
                "selected" -> {
                    // A1：尾部状态标记用 CaptionMedium（12 Medium），与主/副标题同阶梯的强调档
                    Text("已选", style = GlassText.CaptionMedium, color = auroraAccentColor())
                }
                "remove" -> {
                    Box(
                        Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(R.Thumb10.dp))
                            .clickable(
                                interactionSource = actionInteractionSource,
                                indication = null,
                                onClick = onRemoveClick ?: {},
                            )
                            // 读屏（无障碍 P0-4）：这里**刻意保留**自己的语义、不要被外层
                            // SongRow 的合并吞掉——它是行内独立可点目标（点行=播放，
                            // 点这个圆=从队列移除），两者是不同动作，绝不能合并成一个节点。
                            // 旧实现只有 contentDescription、没有 role：读屏念得出
                            // 「从队列移除」但不提示这是按钮、也不提示双击激活，盲用户
                            // 大概率以为那是一句说明文字。
                            //
                            // 文案带上「这首歌」：它会被读屏单独聚焦朗读，脱离视觉上下文时
                            // 光一句「从队列移除」不知道指的是哪首。读屏在该节点上会连读
                            // 「从队列移除，按钮」——队列面板里当前位置的上一节点就是歌名，
                            // 语义连贯。
                            .semantics {
                                this.role = Role.Button
                                this.contentDescription = ListsA11y.REMOVE_FROM_QUEUE
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(GlassIcons.MinusCircle, null, Modifier.size(16.dp),
                            tint = LocalGlassColors.current.textTertiary)
                    }
                }
                "more" -> {
                    Box(
                        Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(R.Thumb10.dp))
                            .clickable(
                                interactionSource = actionInteractionSource,
                                indication = null,
                                onClick = onMoreClick ?: {},
                            )
                            // 读屏（无障碍 P0-4）：同 remove 分支——独立可点目标，
                            // 补 role 让读屏念「更多操作，按钮」而不只是一句话。
                            // 文案保留「更多操作」而不是「更多」：单独念「更多」时
                            // 无法判断是「更多歌曲」还是「更多操作」。
                            .semantics {
                                this.role = Role.Button
                                this.contentDescription = ListsA11y.MORE_ACTIONS
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(GlassIcons.More, null, Modifier.size(16.dp),
                            tint = LocalGlassColors.current.textTertiary)
                    }
                }
                else -> {
                    if (trailing != null) {
                        // B4：尾槽常态是时长（mm:ss），必须走 Numeric 等宽数字 token——
                        // 比例数字下 "1" 比 "8" 窄，一列时长右对齐会参差不齐，滑动时字宽还会闪。
                        // 特例：ActionsSheet 会把这里换成「已添加」，tnum 对 CJK 无副作用（只作用数字），
                        // 所以同一个 token 可以安全覆盖两种文案，不需要再加分支。
                        Text(trailing, style = GlassText.Numeric, color = LocalGlassColors.current.textTertiary)
                    }
                }
            }
        }
    }
}

/** 分组小标题（.sec） */
@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    // A1：SectionLabel 自带 0.3 字距——小号标签没有字号/字重可用时，字距是它唯一的层级信号，
    // 所以这个 +0.3 不是装饰而是语义，必须跟 token 一起走，不能留在这里裸写
    Text(
        text, style = GlassText.SectionLabel, color = LocalGlassColors.current.textTertiary,
        modifier = modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 8.dp),
    )
}

/**
 * 歌单卡：md 玻璃卡 + 封面 **1:1** + 底部 lo 玻璃文字区（.pcard glass / .pt）；onLongClick = 操作菜单
 *
 * **C1（视觉审计，本轮最高优先）**：封面比例由 1.6f 改为 1f。
 * 1.6:1 是横幅/头图的构图比例（宽银幕），用在**歌单封面卡**上时，封面被裁成一条扁带：
 * 专辑/歌单封面的原始素材几乎都是正方形，非等比裁切会切掉构图主体（人脸、唱片、
 * 文字标题都在中心区），而且卡片整体变成一个矮宽的「色块」——它看起来像一条横幅，
 * 不像一张可以点进去的唱片。全 App 的封面语言（SongRow 48dp 方图、详情页 Hero、
 * 播放页封面）都是方的，只有这里是扁的，比例本身就在跟其它页面对不上。
 * 改 1:1 之后：封面题材不被裁、卡片轮廓与其它封面元素同源、歌单页的栅格也变得更整齐。
 *
 * 内层 [ArtThumb] 的 `RoundedCornerShape(0.dp)` **保持不动**：封面是卡片的顶边元素，
 * 必须由外层 [GlassSurface] 的 R.Card18 一次性裁出上圆角，自己再带圆角会在顶部
 * 与卡片边缘形成「双圆弧」，也就是嵌套圆角里最难看的那种错位（C4）。
 *
 * **A6 按下反馈：本卡片没有单独接 [rowPressFeedback]，这是有意的**
 * 卡片的点击完全委托给 [GlassSurface]（onClick/onLongClick 都是它的参数），而
 * GlassSurface 内部自建 MutableInteractionSource、**不对外暴露**——要从外面接一层底色，
 * 只能给 GlassSurface 加一个 interactionSource 参数，而那是 Glass.kt 的写域且属改签名。
 * 更重要的是**没必要**：GlassSurface 自身正在由材质层负责改为 pressedOverlay 反馈
 * （task-2 A6），本卡片作为它的直接使用者会自动获得同一套观感。
 * 在这里再叠一层会出现「两层底色叠加」= 双倍亮度，也就是同一个反馈被画了两次。
 */
@Composable
fun PlaylistCard(
    title: String,
    meta: String,
    art: ArtStyle,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    bitmap: androidx.compose.ui.graphics.ImageBitmap? = null,
    artworkUri: String? = null,
    onLongClick: (() -> Unit)? = null,
) {
    val glass = LocalGlassColors.current
    GlassSurface(
        modifier = modifier,
        level = GlassLevel.Md,
        // C4：18dp = R.Card18（标准卡片容器）。封面靠这一层裁上圆角（见函数 KDoc 末段）
        shape = RoundedCornerShape(R.Card18.dp),
        onClick = onClick,
        onLongClick = onLongClick,
    ) {
        Column {
            ArtThumb(
                // C1：1:1 方形封面（原本 1.6f）——理由见函数 KDoc
                art, Modifier.fillMaxWidth().aspectRatio(1f), RoundedCornerShape(0.dp),
                bitmap = bitmap, artworkUri = artworkUri,
            )
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(glass.lo)
                    .padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 12.dp),
            ) {
                Text(title, color = LocalGlassColors.current.textPrimary, style = GlassText.BodyMedium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(meta, color = LocalGlassColors.current.textTertiary, style = GlassText.Micro,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/**
 * 设置行：32dp 图标块 + 主副标题 + 尾部控件（.setrow）。
 *
 * **读屏语义（无障碍 P0-4）**：`onClick != null` 时才挂 role = Button（与 Modifier 链
 * 的 if 分支保持严格同步）。纯展示行（如设置页的分组说明）不该被念成按钮——
 * 读屏提示「双击激活」却什么都不会发生，是比无描述更糟的假承诺。
 *
 * 装饰性图标保持 null，这一点是**有意为之、非遗漏**（下一轮审计不要再当成 bug）：
 * - 行首 32dp 图标块（[icon]）：纯视觉锚点，语义由同一行的 [title]/[subtitle] 承担。
 * - 尾部 chevron（默认 [trailing]）：只是「这行能进去」的视觉暗示，可点行已经靠
 *   role = Button + 「双击激活」表达同一件事，再念一次「右箭头」纯属噪声。
 */
@Composable
fun SettingRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    trailing: @Composable () -> Unit = {
        // 有意 null、非遗漏：chevron 是「可进入」的视觉暗示，语义由整行的 role = Button
        // 与副标题承担（见函数 KDoc）
        Icon(GlassIcons.ChevronRight, null, Modifier.size(16.dp),
            tint = LocalGlassColors.current.textTertiary)
    },
) {
    val glass = LocalGlassColors.current
    val interactionSource = remember { MutableInteractionSource() }
    Row(
        modifier
            .fillMaxWidth()
            // A2-08：有 onClick 时补按压反馈——旧实现 indication=null 且零视觉反馈，
            // 设置页全部行按下毫无响应，与其它玻璃元件的按压语言断裂。
            // A6：反馈从 pressScale(0.98) 换成底色层（见 rowPressFeedback 的 KDoc）：
            // 整行等比缩放会让图标块与文字一起位移，设置页一行里有开关等控件时尤其像抖动。
            .then(
                if (onClick != null) {
                    Modifier
                        .rowPressFeedback(interactionSource)
                        .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
                        // 语义只在真可点时挂，见函数 KDoc
                        .semantics { this.role = Role.Button }
                } else {
                    Modifier
                },
            )
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(R.Thumb10.dp))
                .background(glass.md),
            contentAlignment = Alignment.Center,
        ) {
            // 有意 null、非遗漏（原文注释见函数 KDoc 末段）
            Icon(icon, null, Modifier.size(18.dp), tint = LocalGlassColors.current.textPrimary)
        }
        Column(Modifier.weight(1f)) {
            Text(title, color = LocalGlassColors.current.textPrimary, style = GlassText.BodyMedium)
            Spacer(Modifier.height(2.dp))
            // B5：副标题补 maxLines = 2 —— 副标题里的「当前 x」状态说明（如导入/更新失败原因）
            // 文案长度不可控，此前换行无上限：长文案会折到 3 行以上，把整行撑高，
            // 相邻行的基线随之错位（设置页是密集列表，一处撑高整段看起来就"散"了）。
            // 两行是「说清楚一件事」与「不破坏行节奏」的折中，超出用省略号收尾。
            Text(
                subtitle, color = LocalGlassColors.current.textTertiary, style = GlassText.Micro,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }
        trailing()
    }
}

/**
 * 列表类组件的读屏文案集中地（无障碍 P0-4）。
 *
 * **为什么要集中成对象**：本轮（task-4）不建 strings.xml（那是 task-5 的活），文案先用
 * 中文字面量。但若把字面量直接散进 `semantics { contentDescription = "..." }`，下一轮
 * 做资源抽取时就得反过来在 UI 代码里逐个 grep 定位，且极易漏掉注释里提到的那些。
 * 集中到对象后，抽取动作收敛为「把这个对象整体搬进 resources + 改这里的引用」，
 * 每个文案的**唯一来源**始终只有这里。
 *
 * 只放**纯语义文案**（界面上不显示、只给读屏念的那种）。列表行的标题/副标题/时长等
 * 可见文字**不搬进来**——它们来自形参，读屏靠 mergeDescendants 自动朗读；
 * 硬编码到常量里会造成「界面文案 vs 读屏文案」双源漂移，这类缺陷审计查不出来，
 * 只有盲用户会撞上。
 */
internal object ListsA11y {
    /** 队列行尾的「移除」圆钮（点行=播放，点它=从队列移除，是两个不同目标） */
    const val REMOVE_FROM_QUEUE = "从播放队列移除"

    /** 行尾「更多」圆钮：保留「操作」二字，避免单独念「更多」时指代不明 */
    const val MORE_ACTIONS = "更多操作"

    /**
     * 页头圆钮缺 actionLabel 时的兜底描述（见 [PageHeader] 的 KDoc）。
     *
     * 刻意做得「不完美」：一个好文案是「切换深浅色模式」，而这里只能给出
     * 「音乐，操作 1」这种可用但含糊的描述。之所以不做得更聪明（比如按 icon 猜语义），
     * 是因为猜错比含糊更危险——读屏念错动作名会让盲用户做出错误的操作决策。
     * 兜底的作用是「不哑」，并顺带在自查时逼调用方去补精确文案。
     */
    fun headerActionFallback(title: String, ordinal: Int): String = "$title，操作 $ordinal"
}
