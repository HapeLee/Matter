package org.kazusa.matter.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.LayerBackdrop
import org.kazusa.matter.ui.effect.liquid.LiquidGlassCapsule
import org.kazusa.matter.ui.effect.liquid.liquidTextShadow

/**
 * 液态玻璃单行搜索输入框。
 *
 * 供「发现页」与「搜索结果页」等顶栏公共复用：
 * - 支持直接编辑搜索词、清除按钮（Clear）与软键盘 IME Search 联动；
 * - 外壳为 [LiquidGlassCapsule]，配合沉浸式顶栏滚动遮罩联动透明度；
 * - 自带居中 placeholder 与光标着色。
 */
@Composable
fun SearchInputField(
    query: String,
    onQueryChange: (String) -> Unit,
    onSearch: (String) -> Unit,
    onClear: () -> Unit,
    backdrop: LayerBackdrop?,
    modifier: Modifier = Modifier,
    placeholder: String = "搜索微博",
    focusRequester: FocusRequester? = null,
    scrollAlpha: Float = 1f,
    leadingIcon: (@Composable () -> Unit)? = null
) {
    // 顶栏胶囊的左右**两侧**共用同一条滚动联动曲线（与 TopBarNavigationButton / TopBarActionButton
    // 同源）：触顶时两端各留 4dp，滚动后对称内收至 16dp。两端必须同时联动——只挂一侧的话
    // 就是「车头缩、车尾不动」，胶囊并不会整体内收。因此这里用同一个 edgePadding 同时驱动 start 与 end。
    //
    // 另外该边距必须施加在玻璃胶囊**自身的外层 modifier** 上：padding 位于玻璃绘制之外才能改变胶囊几何。
    // 若加在内层 Row 上，胶囊已被 fillMaxWidth 撑满、形状不变，只有里面的文字平移。
    val edgePadding = TopBarTokens.buttonEdgePadding(scrollAlpha)
    LiquidGlassCapsule(
        backdrop = backdrop,
        glassAlpha = scrollAlpha,
        modifier = modifier.padding(start = edgePadding, end = edgePadding)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    // 内容相对胶囊的边距保持常量，使文字与玻璃胶囊作为整体一起平移
                    // （胶囊两端在 4dp → 16dp 之间联动，文字固定距胶囊边缘 16dp）。
                    start = TopBarTokens.TitlePaddingStart,
                    end = TopBarTokens.TitlePaddingEnd,
                    top = TopBarTokens.TitlePaddingTop,
                    bottom = TopBarTokens.TitlePaddingBottom
                )
        ) {
            // 前置图标必须「直接调用」，不要经由间接持有的 composable lambda 调用：
            // 滚动联动会让本组件每帧重组，若 lambda 实例每帧都是新的，
            // 整棵图标子树会跟着每帧重建，滚动时就会"卡一下"。
            if (leadingIcon != null) {
                leadingIcon()
                Spacer(modifier = Modifier.width(4.dp))
            }
            val onSurface = MaterialTheme.colorScheme.onSurface
            val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant

            Box(modifier = Modifier.weight(1f)) {
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.headlineMediumEmphasized.copy(
                        color = onSurface,
                        shadow = liquidTextShadow(onSurface)
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { onSearch(query) }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier),
                    decorationBox = { innerTextField ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (query.isEmpty()) {
                                Text(
                                    text = placeholder,
                                    style = MaterialTheme.typography.headlineMediumEmphasized.copy(
                                        shadow = liquidTextShadow(onSurfaceVariant)
                                    ),
                                    color = onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            innerTextField()
                        }
                    }
                )
            }

            if (query.isNotEmpty()) {
                Spacer(modifier = Modifier.width(8.dp))
                Icon(
                    imageVector = Icons.Outlined.Delete,
                    contentDescription = "清空",
                    tint = onSurfaceVariant,
                    modifier = Modifier
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            onClick = onClear
                        )
                )
            }
        }
    }
}
