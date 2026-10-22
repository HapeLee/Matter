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
 * **平滑圆角工厂**（Kyant0 Shapes，G2 连续曲率、近似 G3）。
 *
 * 普通 `CircleShape` / `RoundedCornerShape` 的圆角是「四分之一圆弧 + 直线」，圆弧与直边
 * 的接缝处曲率从 `1/r` 突变为 0，大圆角、大尺寸表面上能看出一圈轻微的「折角」；
 * [RoundedCornerStyle.Continuous] 让曲率连续过渡，就是苹果那套 squircle 观感。
 *
 * 三个函数都是 [Shape]，用法与 `CircleShape` / `RoundedCornerShape` 完全一致，
 * 可直接给 `clip` / `background` / `border` / `Surface` / `Card` / `Button` 使用。
 *
 * **只用于液态玻璃组件**：`ui/effect/liquid` 下的玻璃按钮 / 胶囊 / 导航栏 / 形变菜单 /
 * 分栏 / 悬浮栏 / 回复面板，统一从这里取，保证玻璃表面是连续曲率的 squircle。
 * 普通 M3 组件（卡片、聊天气泡、列表项、图标按钮、头像、圆点、≤ 8dp 小角标）一律
 * 继续用 `CircleShape` / `RoundedCornerShape`，既符合 Material 规范，也省下每帧的轮廓计算。
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

    /** 全圆角胶囊：M3 普通胶囊（非玻璃，走普通圆角） */
    val pill: Shape = RoundedCornerShape(percent = 50)

    /** 列表项卡片（略小于标准卡片，M3 普通圆角） */
    val listItem: Shape = RoundedCornerShape(16.dp)
}
