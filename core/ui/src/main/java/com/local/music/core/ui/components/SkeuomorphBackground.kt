package com.local.music.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import com.local.music.core.ui.theme.LocalAuroraPalette
import com.local.music.core.ui.theme.LocalSkeuomorphColors
import com.local.music.core.ui.theme.LocalThemeFraction

/**
 * 拟物档的**机身背景**（本轮新增，2026-11）。
 *
 * ## 为什么拟物档不能用极光背景
 *
 * 这是真机验证时发现的一处设计偏差：切到拟物档后，界面上的面板、卡片、按键
 * 全都变成了不透明的实体，但**背后的背景仍然是那层会漂移的彩色极光**。
 * 结果是一台「塑料机身浮在极光里」的怪东西——既不像实体设备，也不像玻璃。
 *
 * 拟物的物理前提是「这是一个放在桌上的设备」，所以它的背景必须是**机身本身**：
 * 一块哑光、低反差、几乎没有渐变的板子。极光是「光穿过玻璃」的语言，
 * 与不透明贴面互斥（同 [com.local.music.core.ui.theme.SkeuomorphColors] 的 KDoc）。
 *
 * ## 但仍然要「属于这张封面」
 *
 * 完全中性的灰会让切歌失去氛围反馈——而「封面取色驱动全局」是本 App 的核心体验之一。
 * 折中：机身基色保持中性，但掺入**极少量**（约 6%–10%）当前主题色，
 * 且只在顶部做一次极弱的径向提亮。用户切歌时能感到「环境变了」，
 * 但不会看到一片彩色雾在漂。
 *
 * 这条界线很重要：机身是**被照亮的物体**，不是**发光的光源**。
 *
 * ## 为什么不参与 haze 采样源
 *
 * 拟物档没有任何真模糊表面（[com.local.music.core.ui.components.GlassSurface] 的拟物
 * 分支整条不挂 haze），所以机身背景不需要 `hazeSource`。这也省掉一次全屏纹理拷贝。
 */
@Composable
fun SkeuomorphBackground(modifier: Modifier = Modifier) {
    val skeuo = LocalSkeuomorphColors.current
    val palette = LocalAuroraPalette.current
    val themeFraction = LocalThemeFraction.current

    // 机身基色：直接用拟物 token 的 face（深色 #22241F / 浅色 #F3F1EC）。
    // 它与所有面板的 faceRaised/faceRecessed 同族，于是「背景是一块更大的机身」
    // 这件事在色彩上成立——这是拟物语言自洽的前提。
    val body = skeuo.face
    // 环境色：主题色只以极低强度掺入（深色 8% / 浅色 5%）。
    // 浅色档更低：白底上一点点彩色就会读成「脏」，而深底上反而需要多一点才看得出氛围。
    val ambient = lerp(palette.seed, body, if (themeFraction > 0.5f) 0.95f else 0.92f)

    Box(
        modifier
            .fillMaxSize()
            .background(body)
            .drawWithCache {
                val w = size.width.coerceAtLeast(1f)
                val h = size.height.coerceAtLeast(1f)
                // 顶部一次极弱的径向提亮：模拟「光从上方来」落在机身上。
                // 半径给到 0.9×屏宽、中心在屏宽正中偏上——太大就成了渐变壁纸，
                // 太小则读作一块光斑；这个量级是「看得出有光，但说不出光在哪」。
                val glow = Brush.radialGradient(
                    colors = listOf(ambient, Color.Transparent),
                    center = Offset(w * 0.5f, h * 0.12f),
                    radius = w * 0.95f,
                )
                onDrawBehind {
                    drawRect(brush = glow)
                }
            },
    )
}
