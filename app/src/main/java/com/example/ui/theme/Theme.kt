package com.example.ui.theme

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

private val JarvisDarkColorScheme = darkColorScheme(
    primary = JarvisCyan,
    onPrimary = JarvisDeepNavy,
    primaryContainer = JarvisSurfaceCard,
    onPrimaryContainer = JarvisCyan,
    secondary = JarvisHoloBlue,
    onSecondary = JarvisDeepNavy,
    secondaryContainer = Color(0xFF0F2B48),
    onSecondaryContainer = Color(0xFFBAE6FD),
    tertiary = JarvisCoreAmber,
    onTertiary = JarvisDeepNavy,
    background = JarvisDeepNavy,
    onBackground = JarvisTextPrimary,
    surface = JarvisSurfaceDark,
    onSurface = JarvisTextPrimary,
    surfaceVariant = JarvisSurfaceCard,
    onSurfaceVariant = JarvisTextSecondary,
    outline = JarvisBorderCyan,
    outlineVariant = Color(0xFF1E293B),
    error = JarvisAlertPink,
    onError = Color.White
)

private val LightColorScheme = lightColorScheme(
    primary = JarvisCyan,
    onPrimary = JarvisDeepNavy,
    primaryContainer = Color(0xFFCCFBF1),
    onPrimaryContainer = JarvisDeepNavy,
    secondary = JarvisHoloBlue,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE0F2FE),
    onSecondaryContainer = Color(0xFF0369A1),
    tertiary = JarvisCoreAmber,
    onTertiary = Color.White,
    background = JarvisDeepNavy,
    onBackground = JarvisTextPrimary,
    surface = JarvisSurfaceDark,
    onSurface = JarvisTextPrimary,
    surfaceVariant = JarvisSurfaceCard,
    onSurfaceVariant = JarvisTextSecondary,
    outline = JarvisBorderCyan,
    outlineVariant = Color(0xFF1E293B),
    error = JarvisAlertPink,
    onError = Color.White
)

@Composable
fun NahidAiTheme(
    darkTheme: Boolean = true, // Futuristic Dark AI OS Theme as primary default
    dynamicColor: Boolean = false, // Keep false to preserve NAHID AI cyan/blue HUD identity
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        else -> JarvisDarkColorScheme
    }

    MaterialTheme(colorScheme = colorScheme, typography = Typography, content = content)
}
