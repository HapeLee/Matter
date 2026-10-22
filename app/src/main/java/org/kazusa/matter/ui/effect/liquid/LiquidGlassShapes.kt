package org.kazusa.matter.ui.effect.liquid

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import org.kazusa.matter.ui.theme.smoothCapsule
import org.kazusa.matter.ui.theme.smoothRoundedCorner
import org.kazusa.matter.ui.theme.smoothUnevenRoundedCorner

/**
 * 液态玻璃组件使用的形状，全部委托给 [org.kazusa.matter.ui.theme] 里的平滑圆角工厂
 * （G2 连续曲率，近似 G3），保证玻璃表面与普通卡片是同一套圆角观感。
 *
 * 普通圆角是「四分之一圆弧 + 直线」，圆弧与直边的接缝处曲率从 `1/r` 突变为 0，
 * 在大圆角胶囊、悬浮面板上能看到一圈轻微的「折角」；连续曲率让过渡更柔和。
 */

/** 胶囊 / 圆形玻璃（组合内记忆化）。 */
@Composable
internal fun rememberLiquidCapsuleShape(): Shape =
    remember { liquidCapsuleShape() }

/** 胶囊 / 圆形玻璃；非组合环境（逐帧构造）用这个。 */
internal fun liquidCapsuleShape(): Shape = smoothCapsule()

/** 圆角矩形玻璃（悬浮面板、菜单等）；半径逐帧变化时直接调用本工厂。 */
internal fun liquidRoundedRectShape(cornerRadius: Dp): Shape =
    smoothRoundedCorner(cornerRadius)

/** 圆角矩形玻璃（组合内记忆化）。 */
@Composable
internal fun rememberLiquidRoundedRectShape(cornerRadius: Dp): Shape =
    remember(cornerRadius) { liquidRoundedRectShape(cornerRadius) }

/** 只有顶部两角是圆角的浮动面板（底部两角在屏幕 / 键盘外，取 0）。 */
internal fun liquidSheetShape(topRadius: Dp): Shape =
    smoothUnevenRoundedCorner(topStart = topRadius, topEnd = topRadius)

/** 浮动面板（组合内记忆化）。 */
@Composable
internal fun rememberLiquidSheetShape(topRadius: Dp): Shape =
    remember(topRadius) { liquidSheetShape(topRadius) }
