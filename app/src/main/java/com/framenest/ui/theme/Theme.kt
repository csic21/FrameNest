package com.framenest.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val LightColorScheme = lightColorScheme(
    primary = FrameNestPrimaryLight,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE2E0FF),
    onPrimaryContainer = Color(0xFF1A1558),
    secondary = FrameNestSecondaryLight,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFECE3FF),
    onSecondaryContainer = Color(0xFF24123D),
    tertiary = FrameNestTertiaryLight,
    onTertiary = Color.White,
    tertiaryContainer = FrameNestIvory,
    onTertiaryContainer = Color(0xFF251B00),
    background = FrameNestBackgroundLight,
    onBackground = FrameNestOnSurfaceLight,
    surface = FrameNestSurfaceLight,
    onSurface = FrameNestOnSurfaceLight,
    surfaceVariant = Color(0xFFE6E2F0),
    onSurfaceVariant = Color(0xFF484654),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF5F2FC),
    surfaceContainer = Color(0xFFEFECF6),
    surfaceContainerHigh = Color(0xFFE9E6F0),
    surfaceContainerHighest = Color(0xFFE3E0EA),
    outline = Color(0xFF797785),
    outlineVariant = Color(0xFFC9C5D2),
)

private val DarkColorScheme = darkColorScheme(
    primary = FrameNestPeriwinkle,
    onPrimary = FrameNestMidnight,
    primaryContainer = Color(0xFF35317E),
    onPrimaryContainer = Color(0xFFE3E1FF),
    secondary = FrameNestLavender,
    onSecondary = Color(0xFF282047),
    secondaryContainer = Color(0xFF4F3B6F),
    onSecondaryContainer = Color(0xFFECE3FF),
    tertiary = FrameNestIvory,
    onTertiary = Color(0xFF3D2F00),
    tertiaryContainer = Color(0xFF594600),
    onTertiaryContainer = Color(0xFFFFF0C2),
    background = FrameNestMidnight,
    onBackground = FrameNestOnSurfaceDark,
    surface = FrameNestSurfaceDark,
    onSurface = FrameNestOnSurfaceDark,
    surfaceVariant = Color(0xFF282D47),
    onSurfaceVariant = Color(0xFFC9C6D4),
    surfaceContainerLowest = Color(0xFF080E22),
    surfaceContainerLow = Color(0xFF121932),
    surfaceContainer = Color(0xFF161D37),
    surfaceContainerHigh = Color(0xFF202640),
    surfaceContainerHighest = Color(0xFF2A304A),
    outline = Color(0xFF928F9E),
    outlineVariant = Color(0xFF454A64),
)

@Composable
fun FrameNestTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Brand colors are the product default. Callers may opt into wallpaper colors explicitly.
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content,
    )
}
