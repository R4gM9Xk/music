package com.local.music.core.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.util.lerp
import com.local.music.core.ui.theme.LocalThemeFraction

/**
 * 照片背景：把用户选的一张图铺满整屏，作为**玻璃后面真正的内容**。
 *
 * ## 为什么需要它（本仓此前的结构性缺口）
 *
 * 在此之前全屏背景只有 [AuroraBackground]（纯 `DrawScope` 绘制的双色渐变 + 3 团径向
 * 光源 + 暗角，零位图）。这带来一个产品级后果：**玻璃后面没有高频、高对比的内容可透**。
 * 用户那张参考图（照片 + 毛玻璃面板）里「明暗从玻璃透上来」的观感，本工程此前
 * 根本没有输入源——不是玻璃参数不对，是背后什么都没有。
 *
 * ## 与 haze 的关系（关键，不要弄反）
 *
 * 照片必须落在 haze 采样源的**子树内**，否则玻璃只把它当普通底色做 tint、拿不到模糊。
 * 所以调用方要把本组件与内容区包进**同一个** `.hazeSource(...)` 容器（两层 Box 叠放），
 * 而不是把照片放成内容区的兄弟。GlassApp 与 PlayerScreen 的接线都是这个形状。
 *
 * ## 可读性处理（本轮故意做到最轻）
 *
 * 用户明确要求「可读性问题先不管，先实现」。所以这里**不做**任何压暗补偿，
 * 只在浅色主题下按 [LocalThemeFraction] 把照片往白里提（否则浅色玻璃压在深色照片上
 * 会读作「深色主题套了个白框」）。压暗 / 局部 scrim / 内容侧渐隐都是后续的事，
 * 不做不等于忘了——见 `docs/proto/glass-feasibility.md` 的 D 段。
 *
 * ## 解码与内存
 *
 * 位图由调用方传入，**本组件不做 IO**：全屏照片动辄 4000×3000，解码必须走调用方的
 * 降采样路径，且**不能**进 [ArtworkBitmapCache]——那个缓存 24MB 预算是给 160px
 * 缩略图的，塞一张全屏图会把它整个挤爆。
 */
@Composable
fun PhotoBackground(
    bitmap: androidx.compose.ui.graphics.ImageBitmap,
    modifier: Modifier = Modifier,
    /**
     * 照片的「氛围色」（一般从照片主色派生）。非 null 时叠一层极淡的竖向渐变，
     * 让整屏色调与极光取色体系不脱节。传 null 则纯用照片本身。
     */
    tint: Color? = null,
) {
    val themeFraction = LocalThemeFraction.current
    // 浅色主题把照片整体**往白线插值**（0 = 原样，1 = 抬到 0.72 白度）。
    // 为什么用颜色矩阵而不是叠一层白：叠白层会把照片压成灰片，色彩全失；
    // 线性插值只压缩动态范围、保留色相。
    val lighten = lerp(0f, 0.72f, themeFraction)
    val colorFilter = remember(lighten) {
        if (lighten <= 0.001f) {
            null
        } else {
            // ColorMatrix 是 4×5 行主序（20 个 float）。对 RGB 做 c' = c·(1−L) + 255·L，
            // alpha 行保持单位阵（不能让照片变半透明——那是背景，必须不透明）。
            val s = 1f - lighten
            val o = 255f * lighten
            ColorFilter.colorMatrix(
                ColorMatrix(
                    floatArrayOf(
                        s, 0f, 0f, 0f, o,
                        0f, s, 0f, 0f, o,
                        0f, 0f, s, 0f, o,
                        0f, 0f, 0f, 1f, 0f,
                    ),
                ),
            )
        }
    }
    // 兜底底色：照片解码成功前/极端长宽比下 Crop 之外的区域不能是系统白，否则会闪一下
    Box(modifier.fillMaxSize().background(Color.Black)) {
        Image(
            bitmap = bitmap,
            contentDescription = null, // 纯装饰背景：内容由前景文字承担，读屏重复播报无意义
            contentScale = ContentScale.Crop,
            colorFilter = colorFilter,
            modifier = Modifier.fillMaxSize(),
        )
        if (tint != null) {
            // 氛围色极淡叠加（12% → 6%），只负责「这张照片属于当前取色体系」这一件事，
            // 不参与明暗（明暗由照片自己决定，这正是它相对极光的价值）。
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                tint.copy(alpha = 0.12f),
                                Color.Transparent,
                                tint.copy(alpha = 0.06f),
                            ),
                        ),
                    ),
            )
        }
        // 底部暗角：任何照片铺满后都需要一点「地面感」，让悬浮 Chrome 有落点。
        // 强度按主题插值（深色 0.42 / 浅色 0.10），与 [AuroraBackground] 的暗角同量级。
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.55f to Color.Transparent,
                        1f to Color.Black.copy(alpha = lerp(0.42f, 0.10f, themeFraction)),
                    ),
                ),
        )
    }
}

/** 背景模式：极光（默认）/ 照片 */
enum class BackgroundMode(val label: String) {
    Aurora("极光"),
    Photo("照片"),
}

/**
 * 照片背景的解析结果。
 *
 * [bitmap] 为 null 表示「用户选了照片但还没解出来」（解码中 / 文件已失效）——
 * 此时调用方**必须回落到极光**，不要画一块黑板。
 */
data class PhotoBacking(
    val uri: String,
    val bitmap: androidx.compose.ui.graphics.ImageBitmap?,
    /** 从照片派生的氛围色（供 [PhotoBackground] 的 tint 使用） */
    val tint: Color?,
)

/**
 * 当前照片背景（null = 未启用/未解出，调用方回落极光）。
 *
 * 为什么走 CompositionLocal 而不是层层传参：背景是**整屏级**属性，而读它的只有
 * 根层两个渲染点（GlassApp / PlayerScreen）。传参要穿过 4~5 层，且每层都会长出一个
 * 「我知道背景是什么」的知识——这正是本仓 `LocalIsDark` / `LocalThemeFraction`
 * 已经采用同一做法的理由。
 */
val LocalPhotoBacking = compositionLocalOf<PhotoBacking?> { null }
