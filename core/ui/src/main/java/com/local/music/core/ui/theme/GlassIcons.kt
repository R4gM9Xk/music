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

    val Gear: ImageVector = icon("gear") {
        strokePath(circlePath(12f, 12f, 3.1f), 1.9f)
        strokePath("M12 3.5v2.2M12 18.3v2.2M4.9 7.8l1.9 1.1M17.2 15.1l1.9 1.1M4.9 16.2l1.9-1.1M17.2 8.9l1.9-1.1", 1.9f)
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
    val PlayOrder: ImageVector = icon("play_order") {
        strokePath("M4 8h11a3.4 3.4 0 0 1 3.4 3.4v0", 1.9f)
        strokePath("M15.4 8.4 18.6 11.4 15.4 14.4", 1.9f)
        strokePath("M4 16h7", 1.9f)
    }

    /**
     * 列表循环：完整闭环 + 箭头（环 = 会回到列表开头）。
     * 与 [Repeat] 的区别是有**两个**箭头（进出各一），读作「循环一整圈」而不只是「回到开头」。
     */
    val PlayListLoop: ImageVector = icon("play_list_loop") {
        strokePath("M7 7.4h9a3.6 3.6 0 0 1 3.6 3.6v2a3.6 3.6 0 0 1 -3.6 3.6H8", 1.9f)
        strokePath("M9.6 4.6 7 7.4l2.6 2.8", 1.9f)
        // 第二枚箭头：环的「出口」，这是它与 PlayOrder 之外所有态的公共标记
        strokePath("M14.4 19.2 17 16.4l-2.6-2.8", 1.9f)
    }

    /**
     * 单曲循环：闭环 + 中央实心 `1`。
     * 用**填实的数字**而非再叠一枚角标：角标（旧实现是 6dp 圆点）在 20dp 图标上
     * 会被压成半颗像素，且与「未读点」视觉语义撞车；把 `1` 画进环心，识别度是角标的数倍。
     */
    val PlayRepeatOne: ImageVector = icon("play_repeat_one") {
        strokePath("M7 7.4h9a3.6 3.6 0 0 1 3.6 3.6v2a3.6 3.6 0 0 1 -3.6 3.6H8", 1.9f)
        strokePath("M9.6 4.6 7 7.4l2.6 2.8", 1.9f)
        strokePath("M14.4 19.2 17 16.4l-2.6-2.8", 1.9f)
        // 环心 `1`：竖笔 + 起画笔，两笔即可辨认，不画衬线（衬线在 20dp 下会糊成竖条）
        strokePath("M11.3 9.8 12.9 8.6v6.8", 1.8f)
    }

    /**
     * 随机播放：交叉箭头（沿用 [Shuffle] 的构图语言，但**重画**为与上面三态同族的
     * 「环形基础 + 交叉」——四态并排时轮廓必须同高同宽，否则切换瞬间图标会跳大小）。
     */
    val PlayShuffle: ImageVector = icon("play_shuffle") {
        strokePath("M4 8h3.2c2.2 0 3.4 1.2 4.7 3M4 16h3.2c1.2 0 2.2-.5 3.2-1.6M15 8.2c1.1 1.6 2.4 3.1 3.7 3.1M15 16.6c1.1-1.6 2.4-3.1 3.7-3.1", 1.9f)
        strokePath("M16.4 5.4 19.2 8.2 16.4 11M16.4 14.2 19.2 17l-2.8 2.8", 1.9f)
    }
}
