package org.kazusa.matter.ui.components.composer

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import org.kazusa.matter.ui.effect.liquid.rememberLiquidSheetShape

/**
 * 底部输入面板的外壳：连续曲率上圆角 + 与液态玻璃组件一致的容器色。
 *
 * 只负责「容器长什么样」，内容由调用方给：正文回复是完整的编辑器 + 工具条 + 表情面板，
 * 私信则只把表情面板塞进来。因此这里不含任何编辑器 / 发送语义。
 *
 * [applyWindowInsets] 用于已经处在外层避让容器内的场景（例如私信内容区已经统一避让了
 * 键盘与导航栏），此时传 false，避免重复叠加 inset 高度。
 */
@Composable
internal fun ComposerSheet(
    modifier: Modifier = Modifier,
    applyWindowInsets: Boolean = true,
    shape: Shape? = null,
    content: @Composable () -> Unit
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (applyWindowInsets) {
                    Modifier.windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))
                } else {
                    Modifier
                }
            ),
        shape = shape ?: rememberLiquidSheetShape(26.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 3.dp
    ) {
        content()
    }
}
