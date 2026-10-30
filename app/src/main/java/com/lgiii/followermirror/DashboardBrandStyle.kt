package com.lgiii.followermirror

import androidx.compose.ui.graphics.Color

/** Selects the theme-specific artwork layers and badge colors. */
internal data class DashboardBrandStyle(
    val badgeBackground: Color,
    val badgeForeground: Color,
    val backgroundResource: Int,
    val artworkResource: Int,
    val isDark: Boolean,
)

internal fun dashboardBrandStyle(mode: ThemeMode): DashboardBrandStyle =
    if (mode == ThemeMode.DARK) {
        DashboardBrandStyle(
            badgeBackground = MirrorThemeColors.lightBrandInk,
            badgeForeground = Color.White,
            backgroundResource = R.drawable.brand_texture_dark,
            artworkResource = R.drawable.brand_art_dark,
            isDark = true,
        )
    } else {
        DashboardBrandStyle(
            badgeBackground = MirrorThemeColors.lightBrandInk,
            badgeForeground = Color.White,
            backgroundResource = R.drawable.brand_texture_light,
            artworkResource = R.drawable.brand_art_light,
            isDark = false,
        )
    }
