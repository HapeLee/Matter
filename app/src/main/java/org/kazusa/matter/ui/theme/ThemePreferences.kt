package org.kazusa.matter.ui.theme

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.materialkolor.PaletteStyle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

val Context.themeDataStore: DataStore<Preferences> by preferencesDataStore(name = "matter_theme_pref")

/**
 * 预置主题调色板
 */
data class PresetThemeColor(
    val name: String,
    val color: Color
)

val PresetThemeColors = listOf(
    PresetThemeColor("微博橙", Color(0xFFFF8200)),
    PresetThemeColor("烈焰红", Color(0xFFE53935)),
    PresetThemeColor("活力粉", Color(0xFFE91E63)),
    PresetThemeColor("极客紫", Color(0xFF7C4DFF)),
    PresetThemeColor("经典蓝", Color(0xFF1976D2)),
    PresetThemeColor("松石青", Color(0xFF00897B)),
    PresetThemeColor("森林绿", Color(0xFF43A047)),
    PresetThemeColor("向日葵", Color(0xFFFFA000)),
    PresetThemeColor("深邃夜", Color(0xFF455A64))
)

data class PaletteStyleOption(
    val style: PaletteStyle,
    val title: String,
    val subtitle: String
)

val AvailablePaletteStyles = listOf(
    PaletteStyleOption(PaletteStyle.TonalSpot, "柔和", "默认 M3 风格，色调平衡和谐"),
    PaletteStyleOption(PaletteStyle.Neutral, "中性", "低饱和度，沉稳素雅接近单色"),
    PaletteStyleOption(PaletteStyle.Vibrant, "鲜艳", "高饱和度，活力明艳视觉冲击强"),
    PaletteStyleOption(PaletteStyle.Expressive, "生动", "跳跃丰富，高对比与互补色"),
    PaletteStyleOption(PaletteStyle.Rainbow, "彩虹", "色彩缤纷，柔美绚丽的多彩渐变"),
    PaletteStyleOption(PaletteStyle.FruitSalad, "果盘", "果味撞色，明亮轻快的趣味感"),
    PaletteStyleOption(PaletteStyle.Monochrome, "单色", "黑白灰阶，极简素净无彩色"),
    PaletteStyleOption(PaletteStyle.Fidelity, "保真", "高保真，尽可能保留种子色原貌"),
    PaletteStyleOption(PaletteStyle.Content, "内容", "内容优先，自适应低干扰浅淡色彩")
)

fun PaletteStyle.displayName(): String {
    return AvailablePaletteStyles.find { it.style == this }?.title ?: name
}

fun Color.toHexString(includeAlpha: Boolean = false): String {
    val a = (alpha * 255).toInt().coerceIn(0, 255)
    val r = (red * 255).toInt().coerceIn(0, 255)
    val g = (green * 255).toInt().coerceIn(0, 255)
    val b = (blue * 255).toInt().coerceIn(0, 255)
    return if (includeAlpha) {
        String.format("#%02X%02X%02X%02X", a, r, g, b)
    } else {
        String.format("#%02X%02X%02X", r, g, b)
    }
}

fun parseColorOrNull(hex: String): Color? {
    val clean = hex.trim().removePrefix("#")
    return try {
        when (clean.length) {
            6 -> {
                val r = clean.substring(0, 2).toInt(16)
                val g = clean.substring(2, 4).toInt(16)
                val b = clean.substring(4, 6).toInt(16)
                Color(r, g, b)
            }
            8 -> {
                val a = clean.substring(0, 2).toInt(16)
                val r = clean.substring(2, 4).toInt(16)
                val g = clean.substring(4, 6).toInt(16)
                val b = clean.substring(6, 8).toInt(16)
                Color(r, g, b, a)
            }
            else -> null
        }
    } catch (_: Exception) {
        null
    }
}

data class AppThemeState(
    val dynamicColor: Boolean = true, // 是否跟随系统 Monet 取色 (Android 12+)
    val isAmoled: Boolean = false,     // 纯黑 AMOLED 暗黑底
    val seedColor: Color = Color(0xFFFF8200), // 自定义种子颜色 (Kolor)
    val isDarkTheme: Boolean? = null,  // null: 跟随系统, true: 深色, false: 浅色
    val paletteStyle: PaletteStyle = PaletteStyle.TonalSpot // Kolor 调色板样式
)

class ThemePreferences(private val context: Context) {

    private object Keys {
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val IS_AMOLED = booleanPreferencesKey("is_amoled")
        val SEED_COLOR = intPreferencesKey("seed_color")
        val DARK_THEME_MODE = intPreferencesKey("dark_theme_mode") // 0: 跟随系统, 1: 浅色, 2: 深色
        val PALETTE_STYLE = stringPreferencesKey("palette_style")
    }

    val themeStateFlow: Flow<AppThemeState> = context.themeDataStore.data
        .catch { exception ->
            if (exception is IOException) emit(emptyPreferences()) else throw exception
        }
        .map { prefs ->
            val dynamicColor = prefs[Keys.DYNAMIC_COLOR] ?: true
            val isAmoled = prefs[Keys.IS_AMOLED] ?: false
            val seedColorArgb = prefs[Keys.SEED_COLOR] ?: 0xFFFF8200.toInt()
            val darkThemeMode = prefs[Keys.DARK_THEME_MODE] ?: 0
            val paletteStyleStr = prefs[Keys.PALETTE_STYLE]
            val paletteStyle = paletteStyleStr?.let {
                try {
                    PaletteStyle.valueOf(it)
                } catch (_: Exception) {
                    PaletteStyle.TonalSpot
                }
            } ?: PaletteStyle.TonalSpot

            val isDarkTheme = when (darkThemeMode) {
                1 -> false
                2 -> true
                else -> null
            }

            AppThemeState(
                dynamicColor = dynamicColor,
                isAmoled = isAmoled,
                seedColor = Color(seedColorArgb),
                isDarkTheme = isDarkTheme,
                paletteStyle = paletteStyle
            )
        }

    suspend fun setDynamicColor(enabled: Boolean) {
        context.themeDataStore.edit { prefs ->
            prefs[Keys.DYNAMIC_COLOR] = enabled
        }
    }

    suspend fun setIsAmoled(isAmoled: Boolean) {
        context.themeDataStore.edit { prefs ->
            prefs[Keys.IS_AMOLED] = isAmoled
        }
    }

    suspend fun setSeedColor(color: Color) {
        context.themeDataStore.edit { prefs ->
            // 转为 ARGB Int 保存
            val argb = ((color.alpha * 255).toInt() shl 24) or
                    ((color.red * 255).toInt() shl 16) or
                    ((color.green * 255).toInt() shl 8) or
                    (color.blue * 255).toInt()
            prefs[Keys.SEED_COLOR] = argb
            prefs[Keys.DYNAMIC_COLOR] = false // 选取自定义色彩时自动关闭跟随系统动态色
        }
    }

    suspend fun setPaletteStyle(style: PaletteStyle) {
        context.themeDataStore.edit { prefs ->
            prefs[Keys.PALETTE_STYLE] = style.name
            prefs[Keys.DYNAMIC_COLOR] = false // 选取调色板样式时自动关闭跟随系统动态色
        }
    }

    suspend fun setDarkThemeMode(mode: Int) {
        context.themeDataStore.edit { prefs ->
            prefs[Keys.DARK_THEME_MODE] = mode
        }
    }
}
