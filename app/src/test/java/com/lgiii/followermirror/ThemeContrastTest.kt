package com.lgiii.followermirror

import androidx.compose.ui.graphics.Color
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeContrastTest {
    @Test fun bothPalettesMeetTextContrastForKeyUiPairs() {
        listOf(MirrorThemeColors.dark, MirrorThemeColors.light).forEach { scheme ->
            assertReadable(scheme.onBackground, scheme.background, "background text")
            assertReadable(scheme.onSurface, scheme.surface, "card text")
            assertReadable(scheme.onSurfaceVariant, scheme.background, "secondary text")
            assertReadable(scheme.onSurfaceVariant, scheme.surface, "secondary card text")
            assertReadable(scheme.onPrimary, scheme.primary, "primary button text")
            assertReadable(scheme.onError, scheme.error, "destructive button text")
        }

        val lightBrand = dashboardBrandStyle(ThemeMode.LIGHT)
        assertReadable(lightBrand.badgeForeground, lightBrand.badgeBackground, "LGIII edition badge text")
        assertContrast(MirrorThemeColors.dark.primary, MirrorThemeColors.darkCard, 3.0, "large dark statistic accent")
    }

    private fun assertReadable(foreground: Color, background: Color, label: String) {
        assertContrast(foreground, background, 4.5, label)
    }

    private fun assertContrast(foreground: Color, background: Color, minimum: Double, label: String) {
        assertTrue("$label contrast was ${contrastRatio(foreground, background)}:1", contrastRatio(foreground, background) >= minimum)
    }

    private fun contrastRatio(foreground: Color, background: Color): Double {
        val first = luminance(foreground)
        val second = luminance(background)
        return (max(first, second) + 0.05) / (min(first, second) + 0.05)
    }

    private fun luminance(color: Color): Double {
        fun linear(channel: Float): Double {
            val value = channel.toDouble()
            return if (value <= 0.04045) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * linear(color.red) + 0.7152 * linear(color.green) + 0.0722 * linear(color.blue)
    }
}
