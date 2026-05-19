package com.explorer.fileexplorer.core.designsystem

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Theme selector — persisted in DataStore by `feature:settings`.
 *
 * - [SYSTEM] follows the device's light/dark setting at runtime.
 * - [LIGHT] / [DARK] / [OLED] force a static palette.
 * - [DYNAMIC] uses Material You wallpaper-derived colors on Android 12+
 *   and gracefully falls back to [SYSTEM] on older devices.
 */
enum class ThemeMode {
    SYSTEM, LIGHT, DARK, OLED, DYNAMIC;

    companion object {
        fun fromKey(key: String?): ThemeMode = entries.firstOrNull { it.name == key } ?: SYSTEM
    }
}

private val DarkColorScheme = darkColorScheme(
    primary = AccentCyan,
    onPrimary = DarkBackground,
    primaryContainer = AccentCyanDark,
    onPrimaryContainer = TextPrimary,
    secondary = AccentPurple,
    onSecondary = DarkBackground,
    tertiary = AccentGreen,
    background = DarkBackground,
    onBackground = TextPrimary,
    surface = DarkSurface,
    onSurface = TextPrimary,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = TextSecondary,
    outline = DarkBorder,
    outlineVariant = DarkBorder,
    error = AccentRed,
    onError = DarkBackground,
    surfaceContainerLowest = DarkBackground,
    surfaceContainerLow = DarkSurface,
    surfaceContainer = DarkSurfaceVariant,
    surfaceContainerHigh = DarkSurfaceElevated,
    surfaceContainerHighest = DarkSurfaceElevated,
)

private val OledColorScheme = darkColorScheme(
    primary = AccentCyan,
    onPrimary = OledBackground,
    primaryContainer = AccentCyanDark,
    onPrimaryContainer = TextPrimary,
    secondary = AccentPurple,
    onSecondary = OledBackground,
    tertiary = AccentGreen,
    background = OledBackground,
    onBackground = TextPrimary,
    surface = OledSurface,
    onSurface = TextPrimary,
    surfaceVariant = OledSurfaceVariant,
    onSurfaceVariant = TextSecondary,
    outline = OledBorder,
    outlineVariant = OledBorder,
    error = AccentRed,
    onError = OledBackground,
    surfaceContainerLowest = OledBackground,
    surfaceContainerLow = OledSurface,
    surfaceContainer = OledSurfaceVariant,
    surfaceContainerHigh = OledSurfaceElevated,
    surfaceContainerHighest = OledSurfaceElevated,
)

private val LightColorScheme = lightColorScheme(
    primary = AccentCyanDark,
    onPrimary = LightSurface,
    primaryContainer = AccentCyanLight,
    onPrimaryContainer = LightTextPrimary,
    secondary = AccentPurple,
    onSecondary = LightSurface,
    tertiary = AccentGreen,
    background = LightBackground,
    onBackground = LightTextPrimary,
    surface = LightSurface,
    onSurface = LightTextPrimary,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightTextSecondary,
    outline = LightBorder,
    outlineVariant = LightBorder,
    error = AccentRed,
    onError = LightSurface,
    surfaceContainerLowest = LightSurface,
    surfaceContainerLow = LightBackground,
    surfaceContainer = LightSurfaceVariant,
    surfaceContainerHigh = LightSurfaceElevated,
    surfaceContainerHighest = LightSurfaceElevated,
)

val AppTypography = Typography(
    displayLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 28.sp),
    headlineLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 22.sp),
    headlineMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 18.sp),
    titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
    titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 14.sp),
    bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 14.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 13.sp),
    bodySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 12.sp),
    labelLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 13.sp),
    labelMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 11.sp),
    labelSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 10.sp),
)

@Composable
fun FileExplorerTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val systemDark = isSystemInDarkTheme()
    val supportsDynamic = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val effectiveMode = if (themeMode == ThemeMode.DYNAMIC && !supportsDynamic) ThemeMode.SYSTEM else themeMode

    val colorScheme = when (effectiveMode) {
        ThemeMode.LIGHT -> LightColorScheme
        ThemeMode.DARK -> DarkColorScheme
        ThemeMode.OLED -> OledColorScheme
        ThemeMode.DYNAMIC ->
            if (systemDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        ThemeMode.SYSTEM -> if (systemDark) DarkColorScheme else LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
        content = content,
    )
}
