package org.kazusa.matter.ui.effect

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.isRuntimeShaderSupported

/** 用户的全局模糊开关，同时控制底景采样、模糊和液态玻璃折射。 */
val LocalBlurEffectsEnabled = compositionLocalOf { true }

@Composable
fun rememberBlurEffectsEnabled(): Boolean {
    val enabled = LocalBlurEffectsEnabled.current
    return enabled && remember { isRuntimeShaderSupported() }
}

/**
 * 创建用于屏幕内容采样的 [LayerBackdrop]。
 *
 * 从根本上解决 LazyColumn 等容器透明导致液态玻璃采样到透明黑洞的问题：
 * 在离屏图层录制阶段，通过 [onDraw] 优先以 [backgroundColor] 铺底，再绘制子内容，
 * 确保离屏纹理恒定拥有不透明背景色，液态玻璃着色器绝不会采样到 Alpha=0 的透明像素。
 */
@Composable
fun rememberScreenBackdrop(
    backgroundColor: Color = MaterialTheme.colorScheme.surface
): LayerBackdrop? {
    if (!rememberBlurEffectsEnabled()) return null
    return rememberLayerBackdrop(
        onDraw = {
            drawRect(backgroundColor)
            drawContent()
        }
    )
}
