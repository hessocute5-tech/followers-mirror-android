package com.lgiii.followermirror

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** All application colors are defined here so both themes use the same semantic tokens. */
internal object MirrorThemeColors {
    val lightBrandInk = Color(0xFF171515)
    val lightBrandShade = Color(0xFF252222)
    val lightBrandRedDeep = Color(0xFF85151D)
    val lightCardHighlight = Color(0xFFFEFCFB)
    val darkBrandRed = Color(0xFFE50914)
    val darkCard = Color(0xD9161719)
    val darkOutline = Color(0xFF48494D)
    val lightPaper = Color(0xFFFBF6F0)

    val dark = darkColorScheme(
        primary = darkBrandRed,
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFF7F1118),
        onPrimaryContainer = Color(0xFFFFDADA),
        secondary = Color(0xFFFF555E),
        onSecondary = Color(0xFF690005),
        background = Color(0xFF08090A),
        onBackground = Color(0xFFF5F1F0),
        surface = Color(0xFF151619),
        onSurface = Color(0xFFF5F1F0),
        surfaceVariant = Color(0xFF222326),
        onSurfaceVariant = Color(0xFFD1D0D0),
        outline = Color(0xFF737478),
        outlineVariant = darkOutline,
        error = Color(0xFFFF666B),
        onError = Color(0xFF690005),
        errorContainer = Color(0xFF93000A),
        onErrorContainer = Color(0xFFFFDAD6),
    )

    val light = lightColorScheme(
        primary = Color(0xFFB3262D),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFFFDAD8),
        onPrimaryContainer = Color(0xFF410006),
        secondary = Color(0xFF8A3330),
        onSecondary = Color(0xFFFFFFFF),
        background = lightPaper,
        onBackground = Color(0xFF161416),
        surface = Color(0xFFFFFFFF),
        onSurface = Color(0xFF161416),
        surfaceVariant = Color(0xFFF0E6DE),
        onSurfaceVariant = Color(0xFF514A46),
        outline = Color(0xFF817370),
        outlineVariant = Color(0xFFD2C4C1),
        error = Color(0xFFB3261E),
        onError = Color(0xFFFFFFFF),
        errorContainer = Color(0xFFFFDAD6),
        onErrorContainer = Color(0xFF410002),
    )
}

@Composable
internal fun FollowerMirrorTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (mode == ThemeMode.DARK) MirrorThemeColors.dark else MirrorThemeColors.light,
        content = content,
    )
}
