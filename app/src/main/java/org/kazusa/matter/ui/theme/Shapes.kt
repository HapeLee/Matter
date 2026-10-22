package org.kazusa.matter.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.shapes.Capsule
import com.kyant.shapes.RoundedCornerStyle
import com.kyant.shapes.RoundedRectangle
import com.kyant.shapes.UnevenRoundedRectangle

/**
 * Expressive 主题使用的应用自定义圆角。
 *
 * 这里必须继续用 `RoundedCornerShape`：M3 的 `Shapes` 要求 `CornerBasedShape`
 * （会按 `topStart/topEnd` 等角单独取值），连续曲率的形状不是它的子类。
 */
val MatterShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(36.dp)
)

/**
 * **平滑圆角**（Kyant0 Shapes，G2 连续曲率、近似 G3）。
 *
 * 普通 `CircleShape` / `RoundedCornerShape` 的圆角是「四分之一圆弧 + 直线」，圆弧与直边
 * 的接缝处曲率从 `1/r` 突变为 0，大圆角、大尺寸表面上能看出一圈轻微的「折角」；
 * [RoundedCornerStyle.Continuous] 让曲率连续过渡，就是苹果那套 squircle 观感。
 *
 * 三个函数都是 [Shape]，用法与 `CircleShape` / `RoundedCornerShape` 完全一致，
 * 可直接给 `clip` / `background` / `border` / `Surface` / `Card` / `Button` 使用。
 *
 * **适用范围**（否则就是白交一份轮廓计算开销）：
 * - 卡片、面板、底部 sheet、聊天气泡、按钮这类 **≥ 10dp 圆角的面** 才用；
 * - 真正的**圆圈**（头像、圆点、圆形图标容器）继续用 `CircleShape` —— `Capsule` 在正方形上
 *   得到的是略偏 squircle 的形状，不是正圆；
 * - **≤ 8dp 的小元素**（角标、小标签、媒体圆角）继续用 `RoundedCornerShape`：
 *   这个尺寸下两种圆角肉眼无差别，但列表里每帧都要多算一次轮廓。
 */

/** 全圆角胶囊 / 圆形。 */
fun smoothCapsule(): Shape = Capsule(RoundedCornerStyle.Continuous)

/** 四角等大的圆角矩形。 */
fun smoothRoundedCorner(radius: Dp): Shape = RoundedRectangle(radius, RoundedCornerStyle.Continuous)

/** 四角可不等大的圆角矩形；缺省角为 0。 */
fun smoothUnevenRoundedCorner(
    topStart: Dp = 0.dp,
    topEnd: Dp = 0.dp,
    bottomEnd: Dp = 0.dp,
    bottomStart: Dp = 0.dp
): Shape = UnevenRoundedRectangle(
    topStart,
    topEnd,
    bottomEnd,
    bottomStart,
    RoundedCornerStyle.Continuous
)

/**
 * Material 三段式之外、项目内高频复用的语义化圆角。
 * 小标签、媒体角标、胶囊、列表项卡片统一从这里取，避免各界面写死不同的 dp。
 */
object MatterShapeTokens {
    /** 小标签 / 计数徽标：6dp 太小，连续曲率看不出差别，走普通圆角 */
    val tag: Shape = RoundedCornerShape(6.dp)

    /** 图片、视频上的半透明角标：同上，且在列表里高频出现 */
    val mediaBadge: Shape = RoundedCornerShape(4.dp)

    /** 全圆角胶囊：连续曲率胶囊（非正圆，两端是连续过渡的圆角） */
    val pill: Shape = smoothCapsule()

    /** 列表项卡片（略小于标准卡片） */
    val listItem: Shape = smoothRoundedCorner(16.dp)
}
