package org.kazusa.matter.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.materialkolor.DynamicMaterialTheme
import com.materialkolor.PaletteStyle
import org.kazusa.matter.ui.effect.LocalBlurEffectsEnabled

private val FallbackDarkColorScheme = darkColorScheme(
    primary = WeiboOrange,
    secondary = WeiboOrangeDark,
    background = DarkBackground,
    surface = DarkSurface,
    onPrimary = Color.White,
    onBackground = DarkTextPrimary,
    onSurface = DarkTextPrimary
)

private val FallbackLightColorScheme = lightColorScheme(
    primary = WeiboOrange,
    secondary = WeiboOrangeDark,
    background = SurfaceLight,
    surface = CardBackgroundLight,
    onPrimary = Color.White,
    onBackground = TextPrimary,
    onSurface = TextPrimary
)

@Composable
fun MatterTheme(
    appThemeState: AppThemeState = AppThemeState(),
    content: @Composable () -> Unit
) {
    val isSystemDark = isSystemInDarkTheme()
    val isDark = appThemeState.isDarkTheme ?: isSystemDark

    val context = LocalContext.current
    // 动态取色方案计算开销较大，按关键输入记忆化，避免每次重组重算
    val activeColorScheme = remember(context, isDark, appThemeState.dynamicColor, appThemeState.isAmoled) {
        if (appThemeState.dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+ 原生 Monet 动态取色
            val baseScheme = if (isDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            if (isDark && appThemeState.isAmoled) {
                baseScheme.copy(
                    background = Color.Black,
                    surface = Color.Black,
                    surfaceContainerLowest = Color.Black,
                    surfaceContainerLow = Color(0xFF101010),
                    surfaceContainer = Color(0xFF161616),
                    surfaceContainerHigh = Color(0xFF202020),
                    surfaceContainerHighest = Color(0xFF2C2C2C)
                )
            } else {
                baseScheme
            }
        } else {
            null
        }
    }

    // Establish Expressive defaults before either color provider. Nested MaterialTheme calls
    // inherit the expressive motion scheme while preserving Monet / seed-color animations.
    MaterialExpressiveTheme(
        motionScheme = MotionScheme.expressive(),
        shapes = MatterShapes,
        typography = Typography
    ) {
        if (activeColorScheme != null) {
            MaterialTheme(
                colorScheme = activeColorScheme,
                content = {
                    CompositionLocalProvider(LocalBlurEffectsEnabled provides appThemeState.blurEffectsEnabled) {
                        content()
                    }
                }
            )
        } else {
            DynamicMaterialTheme(
                seedColor = appThemeState.seedColor,
                isDark = isDark,
                isAmoled = isDark && appThemeState.isAmoled,
                style = appThemeState.paletteStyle,
                animate = true,
                content = {
                    CompositionLocalProvider(LocalBlurEffectsEnabled provides appThemeState.blurEffectsEnabled) {
                        content()
                    }
                }
            )
        }
    }
}
