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
    primary = FrameNestBlue,
    onPrimary = Color.White,
    secondary = FrameNestTeal,
    onSecondary = Color.White,
    tertiary = FrameNestBlueLight,
    background = FrameNestSurfaceLight,
    surface = FrameNestSurfaceLight,
    onBackground = FrameNestBlueDark,
    onSurface = FrameNestBlueDark,
)

private val DarkColorScheme = darkColorScheme(
    primary = FrameNestBlueLight,
    onPrimary = FrameNestBlueDark,
    secondary = FrameNestTeal,
    onSecondary = Color.White,
    tertiary = FrameNestBlue,
    background = FrameNestSurfaceDark,
    surface = FrameNestSurfaceDark,
    onBackground = Color(0xFFE8EEF5),
    onSurface = Color(0xFFE8EEF5),
)

@Composable
fun FrameNestTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
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
