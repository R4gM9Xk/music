package com.local.music.core.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * 液态玻璃图标集：一比一转写设计稿 <svg><symbol> 的 24 枚图标（24×24 viewBox）。
 * 统一白色填充/描边，由 Icon(tint = …) 上色。
 */

private fun nodes(d: String) = PathParser().parsePathString(d).toNodes()

private fun icon(name: String, block: ImageVector.Builder.() -> Unit): ImageVector =
    ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply(block).build()

private fun ImageVector.Builder.fillPath(d: String) {
    addPath(nodes(d), fill = SolidColor(Color.White))
}

private fun ImageVector.Builder.strokePath(
    d: String,
    width: Float,
    cap: StrokeCap = StrokeCap.Round,
    join: StrokeJoin = StrokeJoin.Round,
) {
    addPath(
        nodes(d),
        fill = null,
        stroke = SolidColor(Color.White),
        strokeLineWidth = width,
        strokeLineCap = cap,
        strokeLineJoin = join,
    )
}

private fun circlePath(cx: Float, cy: Float, r: Float) =
    "M${cx - r} $cy a$r $r 0 1 0 ${r * 2} 0 a$r $r 0 1 0 ${-r * 2} 0"

object GlassIcons {

    val Back: ImageVector = icon("back") { strokePath("M14.5 5.5 8 12l6.5 6.5", 2f) }

    val Down: ImageVector = icon("down") { strokePath("M6 9.5 12 15.5l6-6", 2f) }

    val More: ImageVector = icon("more") {
        fillPath(circlePath(12f, 5.5f, 1.7f))
        fillPath(circlePath(12f, 12f, 1.7f))
        fillPath(circlePath(12f, 18.5f, 1.7f))
    }

    val Heart: ImageVector = icon("heart") {
        strokePath(
            "M12 20s-7-4.3-7-9.4A4.6 4.6 0 0 1 12 7.6 4.6 4.6 0 0 1 19 10.6C19 15.7 12 20 12 20z",
            1.8f,
        )
    }

    val HeartFilled: ImageVector = icon("heart_f") {
        fillPath("M12 20s-7-4.3-7-9.4A4.6 4.6 0 0 1 12 7.6 4.6 4.6 0 0 1 19 10.6C19 15.7 12 20 12 20z")
    }

    /** 随机（列表「随机播放全部」动作行用）。播放页改用四态族的 [PlayShuffle] */
    val Shuffle: ImageVector = icon("shuffle") {
        strokePath("M4 6h3.5c2.4 0 3.6 1.3 5 3.2M16 17.5c1.2-1.8 2.6-3.5 4-3.5M4 18h3.5c1.3 0 2.4-.6 3.5-1.8M16 6.5c1.2 1.8 2.6 3.5 4 3.5", 1.9f)
        strokePath("M15 3.5 18.5 6.5 15 9.5M15 14.5l3.5 3-3.5 3", 1.9f)
    }

    val Prev: ImageVector = icon("prev") {
        fillPath("M18 6.5v11L10 12z")
        fillPath("M5 6h2.4a1.2 1.2 0 0 1 1.2 1.2v9.6a1.2 1.2 0 0 1 -1.2 1.2H5a1.2 1.2 0 0 1 -1.2 -1.2V7.2A1.2 1.2 0 0 1 5 6z")
    }

    val Next: ImageVector = icon("next") {
        fillPath("M6 6.5v11L14 12z")
        fillPath("M16.6 6H19a1.2 1.2 0 0 1 1.2 1.2v9.6a1.2 1.2 0 0 1 -1.2 1.2h-2.4a1.2 1.2 0 0 1 -1.2 -1.2V7.2a1.2 1.2 0 0 1 1.2 -1.2z")
    }

    val Play: ImageVector = icon("play") { fillPath("M8 5.5 19 12 8 18.5z") }

    val Pause: ImageVector = icon("pause") {
        fillPath("M7 5.5h3.4a1.4 1.4 0 0 1 1.4 1.4v10.2a1.4 1.4 0 0 1 -1.4 1.4H7a1.4 1.4 0 0 1 -1.4 -1.4V6.9A1.4 1.4 0 0 1 7 5.5z")
        fillPath("M13.6 5.5H17a1.4 1.4 0 0 1 1.4 1.4v10.2a1.4 1.4 0 0 1 -1.4 1.4h-3.4a1.4 1.4 0 0 1 -1.4 -1.4V6.9a1.4 1.4 0 0 1 1.4 -1.4z")
    }

    /**
     * 循环（旧版三态按钮用）。**播放页已改用 [PlayOrder]/[PlayListLoop]/[PlayRepeatOne] 四态族**，
     * 本图标目前全仓零引用。
     *
     * 为什么保留而不删：它是「循环」这个动作最通用的象形，`Repeat` 这个名字也已被
     * token-baseline 的图标清单登记；下一轮若要给「播放队列面板」或「设置页默认播放模式」
     * 补一个循环入口，直接用它比重新画一枚更省事，且不会引入第五种循环轮廓。
     * 但**不要**再把它和 [PlayListLoop] 放在同一屏——两者轮廓相似而语义相同，
     * 并列出现只会让人怀疑它们是不是不同功能。
     */
    val Repeat: ImageVector = icon("repeat") {
        strokePath("M6.5 8.5H16a4 4 0 0 1 4 4v0a4 4 0 0 1 -4 4H8", 1.9f)
        strokePath("M9.5 5.5 6.5 8.5l3 3", 1.9f)
    }

    val Queue: ImageVector = icon("queue") {
        strokePath("M4 7h11M4 12h11M4 17h7", 1.9f)
        strokePath(circlePath(18f, 16.5f, 2.6f), 1.9f)
    }

    val Search: ImageVector = icon("search") {
        strokePath(circlePath(11f, 11f, 6f), 1.9f)
        strokePath("m15.6 15.6 3.4 3.4", 1.9f)
    }

    /**
     * 关闭 / 清除（本轮新增）：搜索框「清空输入」与若干弹窗的关闭位。
     *
     * 为什么必须是**圆底叉**而不是裸叉（两条交叉线）：
     * 裸叉在 18dp 下与 [Plus]（加号）在快速扫读时容易混——两者都是「中心对称的两条线」，
     * 差别只在旋转角度。加一圈浅底把「这是一个可点的圆形按钮」和「这是清除语义」
     * 一起表达出来，这也是系统搜索框的通用形态（Material SearchBar 同款）。
     *
     * 笔画 1.7（略细于主体的 1.8）：加号仍在 2.0，两者不再处于同一笔重档。
     */
    val Close: ImageVector = icon("close") {
        // 圆底：用极浅的描边圆提示「可点区域」，不填充（填充会在浅色玻璃上显脏）
        strokePath(circlePath(12f, 12f, 8.4f), 1.4f)
        // 叉：两条线交于圆心，长度控制在圆底内径的 62%
        strokePath("M9.4 9.4 14.6 14.6", 1.7f)
        strokePath("M14.6 9.4 9.4 14.6", 1.7f)
    }

    /**
     * 设置（**真齿轮**）。
     *
     * ## 本轮重画的理由（P3-27）
     * 旧实现是「圆 + 8 条短放射线」——那是**太阳**的构图，不是齿轮。带齿的轮子与
     * 放光的圆在 20dp 下的差别恰好在「边缘是不是平的」这一点上，而旧图形每条放射线
     * 都是独立笔画、与内圆不相连，读起来只能是「发光」。
     *
     * 后果是它被当成太阳用在三处不同语义（底部导航「设置」、曲库页「切换深浅色」、
     * 设置页「动效强度」），其中曲库页那处**语义完全错**（拿齿轮当太阳用）。
     *
     * ## 新构图
     * 6 齿传动轮：内圆（轴心孔）+ 外圈 6 段短齿（每齿是外圈上的一段加宽笔画）。
     * 6 齿而非 8 齿：8 齿在 20dp 下齿槽只有约 1.2dp，会糊成一圈；6 齿留出 2dp 级
     * 齿槽，缩到 16dp 仍分得清「有齿」。
     *
     * 与新增的 [Sun] 的关系：两者轮廓完全不同（齿轮是「带凸起的环」，太阳是
     * 「实心圆 + 放射线」），不再有混用空间。
     */
    val Gear: ImageVector = icon("gear") {
        // 轴心孔
        strokePath(circlePath(12f, 12f, 2.6f), 1.7f)
        // 内圈（齿轮体）
        strokePath(circlePath(12f, 12f, 6.2f), 1.7f)
        // 6 段齿：从内圈向外凸出约 2dp，沿法向短笔画。
        // 用 6 个方向（0°/60°/.../300°）保证旋转对称，视觉上读作「传动轮」而非「光芒」
        strokePath("M12 5.8v-2.3", 2.4f)     // 上
        strokePath("M12 18.2v2.3", 2.4f)     // 下
        strokePath("M6.6 8.9 4.6 7.7", 2.4f)  // 左上
        strokePath("M17.4 15.1 19.4 16.3", 2.4f) // 右下
        strokePath("M6.6 15.1 4.6 16.3", 2.4f)   // 左下
        strokePath("M17.4 8.9 19.4 7.7", 2.4f)   // 右上
    }

    /**
     * 太阳（本轮新增，P3-27）。
     *
     * 用途：曲库页右上角的「切换深浅色」——它表达的是**当前/目标明暗态**，
     * 不是「设置」。旧实现借用 Gear（其实是太阳形）导致两件事纠缠：
     * 一旦把 Gear 改成真齿轮，那处就会变成一个语义错误的齿轮图标；
     * 而若保持 Gear 是太阳，底部导航的「设置」又永远戴着太阳。
     *
     * ## 构图与 Gear 的区别（这是拆分的核心）
     * - 太阳 = **实心圆**（盘体）+ 8 条**不相连**的放射线，射线与盘体有明显间隙；
     * - 齿轮 = **空心环**（stroke）+ 6 段与环相连的短齿。
     * 一个看中心（实心/空心），一个看边缘（射线/齿）。16dp 下都分得清。
     *
     * 8 条射线用 1.8 笔画（与全仓一致）；盘体半径 3.6，射线从 6.2 起到 9.4 止，
     * 留 2.6 的间隙——「不相连」正是太阳与齿轮的分界。
     */
    val Sun: ImageVector = icon("sun") {
        // 盘体：填充圆（太阳是「实心的光球」，不是空心环）
        fillPath(circlePath(12f, 12f, 3.6f))
        // 8 条放射线（0°/45°/90°/.../315°），起点 6.2 终点 9.4 → 与盘体留 2.6 间隙
        strokePath("M12 5.8v-2.4", 1.8f)      // 上
        strokePath("M12 18.2v2.4", 1.8f)      // 下
        strokePath("M5.8 12h-2.4", 1.8f)      // 左
        strokePath("M18.2 12h2.4", 1.8f)      // 右
        strokePath("M7.6 7.6 5.9 5.9", 1.8f)   // 左上
        strokePath("M16.4 16.4 18.1 18.1", 1.8f) // 右下
        strokePath("M7.6 16.4 5.9 18.1", 1.8f)   // 左下
        strokePath("M16.4 7.6 18.1 5.9", 1.8f)   // 右上
    }

    val Home: ImageVector = icon("home") {
        strokePath("M4.5 11 12 4.8 19.5 11v7.2a1.5 1.5 0 0 1 -1.5 1.5H6a1.5 1.5 0 0 1 -1.5 -1.5z", 1.8f)
    }

    val Compass: ImageVector = icon("compass") {
        strokePath(circlePath(12f, 12f, 8f), 1.8f)
        strokePath("m14.8 9.2-1.5 3.9-3.9 1.5 1.5-3.9z", 1.8f)
    }

    val User: ImageVector = icon("user") {
        strokePath(circlePath(12f, 8.6f, 3.4f), 1.8f)
        strokePath("M5.5 19.5c.9-3.5 3.4-5.4 6.5-5.4s5.6 1.9 6.5 5.4", 1.8f)
    }

    val Plus: ImageVector = icon("plus") { strokePath("M12 5.5v13M5.5 12h13", 2f) }

    val ChevronRight: ImageVector = icon("right") { strokePath("m9.5 5.5 6 6.5-6 6.5", 1.8f) }

    val Mic: ImageVector = icon("mic") {
        strokePath("M9.5 3.5h5a2.5 2.5 0 0 1 2.5 2.5v4a2.5 2.5 0 0 1 -2.5 2.5h-5a2.5 2.5 0 0 1 -2.5 -2.5V6a2.5 2.5 0 0 1 2.5 -2.5z", 1.8f)
        strokePath("M6 11.5a6 6 0 0 0 12 0M12 17.5v3", 1.8f)
    }

    val Folder: ImageVector = icon("folder") {
        strokePath("M4 7.5A1.5 1.5 0 0 1 5.5 6h3.7l1.8 2h7.5A1.5 1.5 0 0 1 20 9.5v8A1.5 1.5 0 0 1 18.5 19h-13A1.5 1.5 0 0 1 4 17.5z", 1.8f)
    }

    val Wave: ImageVector = icon("wave") {
        strokePath("M4 12h2l2-5 3 10 3-13 3 16 2-8h1", 1.8f, join = StrokeJoin.Round)
    }

    val Book: ImageVector = icon("book") {
        strokePath("M5 5.5h9.5A2.5 2.5 0 0 1 17 8v11H7a2 2 0 0 1 -2 -2z", 1.8f)
        strokePath("M17 8h2v11h-2", 1.8f)
    }

    /** 新月（浅色模式下右上角按钮的图标） */
    val Moon: ImageVector = icon("moon") {
        fillPath("M21 12.8A9 9 0 1 1 11.2 3a7 7 0 0 0 9.8 9.8z")
    }

    /** 下一首播放（列表 + 播放三角） */
    val PlayNext: ImageVector = icon("play_next") {
        strokePath("M4 6.5h10M4 11.5h7M4 16.5h7", 1.9f)
        fillPath("M15.5 9.2 21 12.5l-5.5 3.3z")
    }

    /** 最后播放（列表 + 加号） */
    val PlayLast: ImageVector = icon("play_last") {
        strokePath("M4 6.5h10M4 11.5h10M4 16.5h6", 1.9f)
        strokePath("M17.5 9.5v7M14 13h7", 1.9f)
    }

    /** 从队列/歌单移除（圆底减号） */
    val MinusCircle: ImageVector = icon("minus_circle") {
        strokePath(circlePath(12f, 12f, 8f), 1.8f)
        strokePath("M8.5 12h7", 1.8f)
    }

    /** 排序（上下双箭头） */
    val Sort: ImageVector = icon("sort") {
        strokePath("M8 18.5v-13M8 5.5 5.5 8M8 5.5l2.5 2.5", 1.8f)
        strokePath("M16 5.5v13M16 18.5l-2.5-2.5M16 18.5l2.5-2.5", 1.8f)
    }

    /**
     * 对勾：下拉选择器里标记「当前选中项」。
     *
     * 为什么这枚必须存在而不是复用别的图形：档位选择（玻璃强度/动效强度）的选中态
     * 在系统设置里的通用语言就是**右侧一枚对勾**——它不是装饰，而是「这一项已经生效」
     * 的唯一确认信号。用圆点/描边代替会与「单选按钮」语义混淆（单选按钮承诺的是
     * 「还在编辑、未提交」，而这里点了立即生效）。
     *
     * 笔画 1.9 与同族「轻量确认」图标（Chevron/More）一致；收笔处不加短横，
     * 20dp 下短横会糊成勾上的毛刺。
     */
    val Check: ImageVector = icon("check") {
        strokePath("M5.5 12.6 10 17 18.6 7.4", 1.9f)
    }

    /**
     * 上下尖角（折叠/展开指示）：设置行尾的「这一项可以展开选择」暗示。
     * 与 [ChevronRight] 的分工：右尖角读作「进入下一页」，上下尖角读作「在此处展开」——
     * 两者混用会让用户预期错位（以为点进去是另一个页面，实际是个下拉）。
     */
    val ChevronUpDown: ImageVector = icon("chevron_up_down") {
        strokePath("m8.5 9.5 3.5-3.5 3.5 3.5", 1.8f)
        strokePath("m8.5 14.5 3.5 3.5 3.5-3.5", 1.8f)
    }

    /** 编辑 / 重命名（铅笔） */
    val Edit: ImageVector = icon("edit") {
        strokePath("M4.5 19.5 8 18.8 18.6 8.2a1.9 1.9 0 0 0-2.7-2.7L5.3 16.1z", 1.8f)
        strokePath("m14.7 6.7 2.7 2.7", 1.8f)
    }

    /** 删除（垃圾桶） */
    val Trash: ImageVector = icon("trash") {
        strokePath("M5.5 6.5h13", 1.8f)
        strokePath("M9.5 6.5v-1A1.5 1.5 0 0 1 11 4h2a1.5 1.5 0 0 1 1.5 1.5v1", 1.8f)
        strokePath("M7 6.5l.7 11.4a1.9 1.9 0 0 0 1.9 1.8h4.8a1.9 1.9 0 0 0 1.9-1.8L17 6.5", 1.8f)
        strokePath("M10.2 10.5v5M13.8 10.5v5", 1.8f)
    }

    /**
     * 锁（关闭状态）：桌面歌词「锁定位置」。
     *
     * 为什么必须新画一枚而不是复用 Gear：Gear 在本工程的既定语义是「设置 / 系统选项」
     * （见设置页三处用法的注释）。用它表示「锁定」会让用户以为点进去是一个设置面板，
     * 而这一行实际是一个**开关**（点一下立刻锁定/解锁悬浮窗）。语义错位在这里的代价
     * 是「用户不敢点」——他以为这会跳走。
     *
     * 图形按 Material "lock" 语义：上方的锁梁（开口向下的 U）+ 下方圆角方体。
     * 笔画 1.8 与全仓口径一致。
     */
    val Lock: ImageVector = icon("lock") {
        // 锁梁：从左柱顶起，向上拱过再落回右柱
        strokePath("M8.5 10.5V8a3.5 3.5 0 0 1 7 0v2.5", 1.8f)
        // 锁体：圆角矩形（10.2 → 上下各留视觉呼吸）
        strokePath("M6.5 10.5h11a1 1 0 0 1 1 1v7a1 1 0 0 1-1 1h-11a1 1 0 0 1-1-1v-7a1 1 0 0 1 1-1z", 1.8f)
    }

    /** 专辑（唱片） */
    val Disc: ImageVector = icon("disc") {
        strokePath(circlePath(12f, 12f, 8f), 1.8f)
        fillPath(circlePath(12f, 12f, 1.6f))
    }

    /**
     * 分享（向上箭头出框）：P0-5 A 分享歌曲入口。
     * 图形按 Material "ios_share" 语义：一个开口的方框 + 从中升起的箭头，
     * 与平台分享图标一致，用户不必重新学习。
     */
    val Share: ImageVector = icon("share") {
        // 方框：底边 + 两条竖边，顶边中间断开（箭头从这里出去）
        strokePath("M7.5 11.5H6a1.5 1.5 0 0 0-1.5 1.5v6A1.5 1.5 0 0 0 6 20.5h12a1.5 1.5 0 0 0 1.5-1.5v-6A1.5 1.5 0 0 0 18 11.5h-1.5", 1.8f)
        // 箭头竖线 + 双翼
        strokePath("M12 3.5v11", 1.8f)
        strokePath("M8.5 7 12 3.5 15.5 7", 1.8f)
    }

    /**
     * 铃声（铃铛）：P0-5 A「设为铃声」入口。
     * 用经典钟形 + 底部小圆舌，20dp 下仍可辨认；不用「音符」是因为曲库里到处都是
     * 音符类视觉元素（播放/波形），铃声必须与它们区分开。
     */
    val Bell: ImageVector = icon("bell") {
        strokePath(
            "M12 3.5a5.5 5.5 0 0 0-5.5 5.5c0 4-1.5 5.6-2.3 6.4a.9.9 0 0 0 .6 1.6h14.4a.9.9 0 0 0 .6-1.6C18.5 14.6 17.5 13 17.5 9A5.5 5.5 0 0 0 12 3.5z",
            1.8f,
        )
        // 铃舌：下方的小弧
        strokePath("M9.8 19.4a2.4 2.4 0 0 0 4.4 0", 1.8f)
    }

    // ── 空态图标（视觉审计 E2，追加于文件末尾）────────────────────────
    //
    // 空态需要「这里本该有什么」的语义化暗示，而既有图标集里没有成套的：
    // Disc/User/Folder 可直接复用（专辑/艺术家/文件夹三个 tab），但歌曲/歌单/流派
    // 三处只能落到「随便挑一枚近似的」——那会让空态图标变成装饰纹样而不是语义信号。
    //
    // 四条笔画口径与既有条目严格一致：24 viewBox、1.8/1.9 描边、Round cap/join。
    // 全部**追加**在文件末尾、不改动上面任何既有条目的名字/顺序/路径
    // （本文件与另一名 teammate 共享，只能尾部追加）。

    /**
     * 单音符：曲库 tab「还没有歌曲」+ 任何「这里没有音乐」的通用空态。
     *
     * 与下面的 [NoteList]（双音符 + 符杠）刻意区分：一枚音符读作「一首歌/没有歌」，
     * 两枚连杠读作「一组歌/歌单」。空态图标是用户区分「哪个 tab 空了」的主要线索，
     * 三个音乐类 tab 共用同一枚音符会让它们看起来是同一个空态。
     */
    val MusicNote: ImageVector = icon("music_note") {
        strokePath(circlePath(8.6f, 17.4f, 3.1f), 1.8f)
        strokePath("M11.7 17.4V4.9", 1.8f)
        // 符尾：向右下的一小段弧，缺了它这根竖线会被读成「旗杆」而不是音符
        strokePath("M11.7 5.2c1.9.5 3.4 1.5 4.2 2.9", 1.8f)
    }

    /** 音符 + 加号：歌单空卡的「去添加歌曲」引导（紧凑形态里 16dp 仍可辨，故无符尾） */
    val NoteAdd: ImageVector = icon("note_add") {
        strokePath(circlePath(8.4f, 17.4f, 2.9f), 1.8f)
        strokePath("M11.3 17.4V5.2", 1.8f)
        strokePath("M17.6 11v4.4M15.4 13.2h4.4", 1.9f)
    }

    /**
     * 双音符（符杠相连）：歌单 tab「还没有歌单」。
     * 刻意不用「清单 + 音符」的构图——那与既有 [Queue]（播放队列：三横线 + 圆圈）
     * 撞形，会让「播放队列」和「歌单」这两个完全不同的概念在图标层混为一谈。
     */
    val NoteList: ImageVector = icon("note_list") {
        strokePath(circlePath(8.4f, 16.8f, 2.6f), 1.8f)
        strokePath(circlePath(16.4f, 15.2f, 2.6f), 1.8f)
        strokePath("M11 16.8V7.4M19 15.2V5.8", 1.8f)
        // 符杠：把两枚音符连成「一组」，这是它与 [MusicNote] 的语义分界
        strokePath("M11 7.4 19 5.8", 1.8f)
    }

    /** 标签：流派 tab「还没有流派」（流派本质就是一枚分类标签，不用抽象图形） */
    val Tag: ImageVector = icon("tag") {
        strokePath(
            "M11.2 4.2H6.4A2.2 2.2 0 0 0 4.2 6.4v4.8a1.6 1.6 0 0 0 .47 1.13l7.5 7.5" +
                "a2 2 0 0 0 2.83 0l4.66-4.66a2 2 0 0 0 0-2.83l-7.5-7.5A1.6 1.6 0 0 0 11.2 4.2z",
            1.8f,
        )
        // 穿绳孔：实心小圆，20dp 下是这枚图标唯一的识别点
        fillPath(circlePath(8.4f, 8.4f, 1.3f))
    }

    // ── 播放模式四态（QQ 音乐同款单按钮）────────────────────
    //
    // 为什么需要四枚**独立**图标，而不是「随机 + 循环」两枚图标的组合：
    // 四态合并成一枚按钮后，用户扫一眼必须能分辨当前是哪一态。若沿用两枚图标拼装
    // （例如循环图标上叠角标），四态里有三态长得很像（列表循环/单曲循环/顺序播放
    // 只在箭头与角标上差一点），20dp 下几乎不可辨。四枚独立图标把「状态」编码进
    // **整体轮廓**，这是小尺寸下唯一可靠的区分维度。
    //
    // 与既有 [Repeat]/[Shuffle] 的关系：这两枚旧图标保留（面板/其它入口可能引用），
    // 但播放页不再用它们——四态图标与旧图标刻意在轮廓上拉开距离，避免同一屏出现
    // 「一枚说得清、一枚说不清」的混搭。

    /**
     * 顺序播放（列表到头即停）：单横线 + 向右箭头，**无环**。
     * 无环是关键——其它三态都带循环箭头，只有它不带，于是「有没有环」本身就是一档信号。
     */
    /**
     * 顺序播放（列表到头即停）：**直的**箭头 + 两条横线，无环、无弯曲。
     *
     * ## 本轮重做（形状语言分层）
     * 四态旧版全部建立在「圆角矩形环」上，靠环上有没有箭头、环心有没有 `1`、
     * 有没有交叉线来区分。20dp 下环本身占掉了大部分像素，剩下的差异（一个箭头朝向、
     * 一个数字）只剩 2-4dp 的落地面积——实测四个图标切换时用户读不出差别。
     *
     * 新方案是**每档换一个基础形状**，让差异落在「整体轮廓」这一层：
     * | 档位 | 基础形状 | 一句话特征 |
     * |---|---|---|
     * | 顺序 | 直线 + 右箭头 | 一条走到头 |
     * | 列表循环 | 闭合圆环 | 转一圈回来 |
     * | 单曲循环 | 圆环 + 中心圆点 | 圈里只有这一颗 |
     * | 随机 | 交叉双线 | 乱序穿梭 |
     *
     * 「有没有环」成为顺序与其他三档的分界；「环心是空的还是有点」成为列表循环与
     * 单曲循环的分界；「有没有交叉」成为随机的独有特征。三条判据彼此正交，
     * 任意两档之间都至少差一层。
     */
    val PlayOrder: ImageVector = icon("play_order") {
        // 两条横线（列表）+ 右侧直箭头：箭头**不带弧度**，与其余三档的环形语言对立
        strokePath("M4 8.5h9", 2f)
        strokePath("M4 15.5h6", 2f)
        strokePath("M14.5 12h5", 2f)
        strokePath("M17 8.5 20.5 12 17 15.5", 2f)
    }

    /**
     * 列表循环：**完整闭合圆环** + 环上一个箭头。
     *
     * 用整圆而非圆角矩形：圆是「循环」最无歧义的几何，也是与单曲循环共享的底座
     * （两者只差环心有没有点）。箭头压在环的右上方，表达「沿这个方向转」。
     */
    val PlayListLoop: ImageVector = icon("play_list_loop") {
        strokePath(circlePath(12f, 12f, 6.8f), 2f)
        // 箭头嵌在环上（与环同半径），不额外占空间
        strokePath("M14.6 5.4 17.4 8.2 14.6 11", 2f)
    }

    /**
     * 单曲循环：圆环 + **环心实心圆点**。
     *
     * 为什么中心用圆点而不是数字 `1`：`1` 在 20dp 图标里只剩约 5dp 高，笔画挤在一起
     * 会糊成一个竖条；而实心点在任何尺寸下都清晰，且「一颗」本身就是「单曲」的隐喻
     * ——不需要会读数字也能理解。
     *
     * 与 [PlayListLoop] 的唯一差别就是这个点，所以点必须**够大**（半径 2.6，
     * 约为环半径的 38%），否则两档在切换动画（缩到 0.72）的中间态会难以区分。
     */
    val PlayRepeatOne: ImageVector = icon("play_repeat_one") {
        strokePath(circlePath(12f, 12f, 6.8f), 2f)
        strokePath("M14.6 5.4 17.4 8.2 14.6 11", 2f)
        fillPath(circlePath(12f, 12f, 2.6f))
    }

    /**
     * 随机播放：**交叉的双向箭头**，无环。
     *
     * 交叉是「打乱顺序」的通用象形（两条线各自从一端出发、在中途交叉后到另一端）。
     * 它没有环，因此与两个循环态天然分开；它没有横线，因此与顺序态分开。
     *
     * 两端各给一枚小箭头：一条线向右、一条线向左，读作「互相换位」——
     * 只给单侧箭头会读成「重排成另一个固定顺序」，而随机是每次都不一样的。
     */
    val PlayShuffle: ImageVector = icon("play_shuffle") {
        // 上线：左下 → 右上
        strokePath("M4 17.5C7 17.5 8.6 15.4 10.4 13c1.8-2.4 3.4-4.5 6.4-4.5", 2f)
        // 下线：左上 → 右下
        strokePath("M4 6.5c3 0 4.6 2.1 6.4 4.5 1.8 2.4 3.4 4.5 6.4 4.5", 2f)
        // 右侧两枚箭头（上下各一，方向相反）
        strokePath("M14.6 5.6 17.4 8.5 14.6 11.4", 2f)
        strokePath("M14.6 12.6 17.4 15.5 14.6 18.4", 2f)
    }
}
