package com.lgiii.followermirror

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardBrandStyleTest {
    @Test fun darkBrandingUsesItsOwnArtworkAndTexture() {
        val style = dashboardBrandStyle(ThemeMode.DARK)

        assertEquals(MirrorThemeColors.lightBrandInk, style.badgeBackground)
        assertEquals(androidx.compose.ui.graphics.Color.White, style.badgeForeground)
        assertEquals(R.drawable.brand_texture_dark, style.backgroundResource)
        assertEquals(R.drawable.brand_art_dark, style.artworkResource)
        assertTrue(style.isDark)
    }

    @Test fun lightBrandTagUsesItsOwnArtworkAndTexture() {
        val style = dashboardBrandStyle(ThemeMode.LIGHT)

        assertEquals(MirrorThemeColors.lightBrandInk, style.badgeBackground)
        assertEquals(R.drawable.brand_texture_light, style.backgroundResource)
        assertEquals(R.drawable.brand_art_light, style.artworkResource)
        assertFalse(style.isDark)
    }
}
