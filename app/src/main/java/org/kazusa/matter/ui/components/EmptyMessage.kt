package org.kazusa.matter.ui.components

import org.kazusa.matter.ui.theme.smoothRoundedCorner

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 默认颜文字，点击可随机切换一个 */
val DefaultEmptyFaces = listOf(
    "(；′⌒`)", "(つ﹏⊂)", "(•̀ᴗ•́)و", "(๑•́ ₃ •̀๑)",
    "(눈‸눈)", "(ಥ﹏ಥ)", "(｡•́︿•̀｡)"
)

/**
 * 通用占位组件：列表为空、数据无法获取时的统一表达。
 *
 * - 一句颜文字，点击可换一个，弱化「失败」的负面感；
 * - 一行说明文案（默认建议「没有可显示的内容」）；
 * - 可选的刷新按钮，用于手动重试。
 *
 * @param message 展示给用户的说明文案
 * @param isLoading 为 true 时颜文字位置换成加载指示器
 * @param buttonText 按钮文案，与 [onButtonClick] 同时提供时才显示按钮
 * @param buttonIcon 按钮图标，默认刷新
 * @param faces 颜文字集合，传空列表则不展示
 * @param onFaceClick 点击颜文字的额外回调（例如触发重试）
 */
@Composable
fun EmptyMessage(
    message: String,
    modifier: Modifier = Modifier,
    isLoading: Boolean = false,
    buttonText: String? = null,
    buttonIcon: ImageVector = Icons.Outlined.Refresh,
    buttonEnabled: Boolean = true,
    onButtonClick: (() -> Unit)? = null,
    faces: List<String> = DefaultEmptyFaces,
    faceTextSize: TextUnit = 30.sp,
    onFaceClick: (() -> Unit)? = null
) {
    var currentFace by remember(faces) {
        mutableStateOf(faces.randomOrNull() ?: "")
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        AnimatedContent(
            targetState = isLoading,
            // 只做淡入淡出：默认的 scaleIn / scaleOut 会「弹」一下，尺寸补间还会带着
            // 下方文案与按钮一起位移；这里关掉尺寸过渡，让占位区的排版保持稳定
            transitionSpec = {
                (fadeIn(animationSpec = tween(180)) togetherWith fadeOut(animationSpec = tween(120)))
                    .using(null)
            },
            label = "EmptyMessageLoading"
        ) { loading ->
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary
                )
            } else if (currentFace.isNotBlank()) {
                Text(
                    text = currentFace,
                    fontSize = faceTextSize,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .clip(smoothRoundedCorner(12.dp))
                        .clickable {
                            if (faces.isNotEmpty()) currentFace = faces.random()
                            onFaceClick?.invoke()
                        }
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                )
            }
        }

        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 260.dp)
        )

        if (buttonText != null && onButtonClick != null) {
            Spacer(modifier = Modifier.width(2.dp))
            FilledTonalButton(
                onClick = onButtonClick,
                enabled = buttonEnabled,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Icon(
                    imageVector = buttonIcon,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = buttonText,
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }
    }
}
