package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

enum class ThemeMode {
    DARK, LIGHT, SYSTEM
}

private val TorveDarkColorScheme = darkColorScheme(
    primary = TorveIndigoBright,
    onPrimary = Color(0xFF0A0D1A),
    primaryContainer = TorveIndigoContainerDark,
    onPrimaryContainer = Color(0xFFE0E7FF),
    secondary = TorveCyan,
    onSecondary = Color(0xFF042028),
    secondaryContainer = TorveCyanContainerDark,
    onSecondaryContainer = Color(0xFFCFFAFE),
    tertiary = TorveEmerald,
    onTertiary = Color(0xFF032318),
    tertiaryContainer = TorveEmeraldContainerDark,
    onTertiaryContainer = Color(0xFFA7F3D0),
    background = TorveObsidian,
    onBackground = TorveTextPrimaryDark,
    surface = TorveObsidian,
    onSurface = TorveTextPrimaryDark,
    surfaceVariant = TorveSurfaceElevatedDark,
    onSurfaceVariant = TorveTextSecondaryDark,
    surfaceContainerLowest = TorveCodeBgDark,
    surfaceContainerLow = TorveSurfaceDark,
    surfaceContainer = TorveSurfaceElevatedDark,
    surfaceContainerHigh = TorveSurfaceHighDark,
    surfaceContainerHighest = TorveSurfaceHighestDark,
    outline = TorveBorderDark,
    outlineVariant = Color(0xFF1F2A3F),
    error = TorveRose,
    onError = Color.White,
    errorContainer = TorveRoseContainerDark,
    onErrorContainer = Color(0xFFFFCBD5)
)

private val TorveLightColorScheme = lightColorScheme(
    primary = TorveIndigoDeep,
    onPrimary = Color.White,
    primaryContainer = TorveIndigoContainerLight,
    onPrimaryContainer = Color(0xFF1E1B4B),
    secondary = TorveCyanDeep,
    onSecondary = Color.White,
    secondaryContainer = TorveCyanContainerLight,
    onSecondaryContainer = Color(0xFF083344),
    tertiary = TorveEmerald,
    onTertiary = Color.White,
    tertiaryContainer = TorveEmeraldContainerLight,
    onTertiaryContainer = Color(0xFF064E3B),
    background = TorveAlabaster,
    onBackground = TorveTextPrimaryLight,
    surface = TorveAlabaster,
    onSurface = TorveTextPrimaryLight,
    surfaceVariant = TorveSurfaceElevatedLight,
    onSurfaceVariant = TorveTextSecondaryLight,
    surfaceContainerLowest = TorveSurfaceLight,
    surfaceContainerLow = Color(0xFFF5F8FC),
    surfaceContainer = TorveSurfaceLight,
    surfaceContainerHigh = TorveSurfaceElevatedLight,
    surfaceContainerHighest = TorveSurfaceHighLight,
    outline = TorveBorderLight,
    outlineVariant = Color(0xFFE2E8F0),
    error = Color(0xFFE11D48),
    onError = Color.White,
    errorContainer = TorveRoseContainerLight,
    onErrorContainer = Color(0xFF881337)
)

val TorveShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun TorveAiTheme(
    themeMode: ThemeMode = ThemeMode.DARK,
    content: @Composable () -> Unit
) {
    val isDark = when (themeMode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    val colorScheme = if (isDark) TorveDarkColorScheme else TorveLightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = TorveTypography,
        shapes = TorveShapes,
        content = content
    )
}
