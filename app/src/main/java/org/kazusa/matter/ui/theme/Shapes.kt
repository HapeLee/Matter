package org.kazusa.matter.ui.theme

import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Expressive 主题使用的应用自定义圆角。
 */
val MatterShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(36.dp)
)

/**
 * Material 三段式之外、项目内高频复用的语义化圆角。
 * 小标签、媒体角标、胶囊、列表项卡片统一从这里取，避免各界面写死不同的 dp。
 */
object MatterShapeTokens {
    /** 小标签 / 计数徽标 */
    val tag = RoundedCornerShape(6.dp)

    /** 图片、视频上的半透明角标 */
    val mediaBadge = RoundedCornerShape(4.dp)

    /** 全圆角胶囊 */
    val pill = RoundedCornerShape(percent = 50)

    /** 列表项卡片（略小于标准卡片） */
    val listItem = RoundedCornerShape(16.dp)
}
