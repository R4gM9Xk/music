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
}
