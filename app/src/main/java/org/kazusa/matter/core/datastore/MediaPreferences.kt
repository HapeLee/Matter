package org.kazusa.matter.core.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

val Context.mediaDataStore: DataStore<Preferences> by preferencesDataStore(name = "matter_media_pref")

/**
 * 大图清晰度策略（原图/高清/标清下载质量配置）。
 */
enum class ImageQualityMode {
    /** 始终不自动加载原图：先显示中等档，必须手动点「查看原图」 */
    THUMBNAIL_ONLY,

    /** Wi-Fi / 非计费网络自动加载原图；移动网络先显示中等档，需手动点按（默认） */
    WIFI_ORIGINAL,

    /** 始终自动加载原图 */
    ALWAYS_ORIGINAL;

    companion object {
        fun fromValue(value: Int): ImageQualityMode =
            entries.getOrElse(value) { WIFI_ORIGINAL }
    }
}

data class MediaPreferenceState(
    /** 大图清晰度策略 */
    val imageQualityMode: ImageQualityMode = ImageQualityMode.WIFI_ORIGINAL
)

class MediaPreferences(private val context: Context) {

    private object Keys {
        val IMAGE_QUALITY_MODE = intPreferencesKey("image_quality_mode")
    }

    val mediaStateFlow: Flow<MediaPreferenceState> = context.mediaDataStore.data
        .catch { exception ->
            if (exception is IOException) emit(emptyPreferences()) else throw exception
        }
        .map { prefs ->
            MediaPreferenceState(
                imageQualityMode = ImageQualityMode.fromValue(prefs[Keys.IMAGE_QUALITY_MODE] ?: 1)
            )
        }

    suspend fun setImageQualityMode(mode: ImageQualityMode) {
        context.mediaDataStore.edit { prefs ->
            prefs[Keys.IMAGE_QUALITY_MODE] = mode.ordinal
        }
    }
}
