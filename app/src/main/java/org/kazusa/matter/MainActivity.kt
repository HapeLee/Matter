package org.kazusa.matter

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Modifier
import org.kazusa.matter.ui.navigation.MatterNavGraph
import org.kazusa.matter.ui.theme.AppThemeState
import org.kazusa.matter.ui.theme.MatterTheme
import org.kazusa.matter.ui.theme.ThemePreferences
import org.koin.android.ext.android.inject

class MainActivity : ComponentActivity() {

    private val themePreferences: ThemePreferences by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val themeState by themePreferences.themeStateFlow.collectAsStateWithLifecycle(
                initialValue = AppThemeState()
            )
            MatterTheme(appThemeState = themeState) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MatterNavGraph()
                }
            }
        }
    }
}
