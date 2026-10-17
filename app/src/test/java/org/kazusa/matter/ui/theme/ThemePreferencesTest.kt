package org.kazusa.matter.ui.theme

import androidx.compose.ui.graphics.Color
import com.materialkolor.PaletteStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ThemePreferencesTest {

    @Test
    fun defaultAppThemeState_hasTonalSpotPaletteStyle() {
        val state = AppThemeState()
        assertEquals(PaletteStyle.TonalSpot, state.paletteStyle)
        assertEquals(Color(0xFFFF8200), state.seedColor)
    }

    @Test
    fun availablePaletteStyles_containsExpectedStyles() {
        val styles = AvailablePaletteStyles.map { it.style }
        assert(styles.contains(PaletteStyle.TonalSpot))
        assert(styles.contains(PaletteStyle.Neutral))
        assert(styles.contains(PaletteStyle.Vibrant))
        assert(styles.contains(PaletteStyle.Expressive))
        assert(styles.contains(PaletteStyle.Monochrome))

        assertEquals("柔和", PaletteStyle.TonalSpot.displayName())
        assertEquals("中性", PaletteStyle.Neutral.displayName())
        assertEquals("鲜艳", PaletteStyle.Vibrant.displayName())
    }

    @Test
    fun colorToHexString_formatsCorrectly() {
        val orange = Color(0xFFFF8200)
        assertEquals("#FF8200", orange.toHexString())
        assertEquals("#FFFF8200", orange.toHexString(includeAlpha = true))

        val black = Color(0xFF000000)
        assertEquals("#000000", black.toHexString())
    }

    @Test
    fun parseColorOrNull_handlesValidAndInvalidHex() {
        val parsed1 = parseColorOrNull("#FF8200")
        assertNotNull(parsed1)
        assertEquals(0xFFFF8200.toInt(), (parsed1!!.value shr 32).toInt())

        val parsedWithoutHash = parseColorOrNull("FF8200")
        assertNotNull(parsedWithoutHash)

        val invalidHex = parseColorOrNull("ZZZZZZ")
        assertNull(invalidHex)

        val tooShort = parseColorOrNull("123")
        assertNull(tooShort)
    }
}
