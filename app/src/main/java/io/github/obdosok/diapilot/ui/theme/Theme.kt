package io.github.obdosok.diapilot.ui.theme

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

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF8FD5AE),
    onPrimary = Color(0xFF073824),
    primaryContainer = Color(0xFF214C37),
    onPrimaryContainer = Color(0xFFC7F4D8),
    secondary = Color(0xFFFFAA91),
    onSecondary = Color(0xFF4B1608),
    secondaryContainer = Color(0xFF4D3027),
    onSecondaryContainer = Color(0xFFFFD8CC),
    tertiary = Color(0xFFE6C47A),
    onTertiary = Color(0xFF3B2F00),
    tertiaryContainer = Color(0xFF493F1F),
    onTertiaryContainer = Color(0xFFFFE8A8),
    background = Color(0xFF101612),
    onBackground = Color(0xFFE4E9E4),
    surface = Color(0xFF151D18),
    onSurface = Color(0xFFE4E9E4),
    surfaceVariant = Color(0xFF273129),
    surfaceContainerLowest = Color(0xFF0D120F),
    surfaceContainerLow = Color(0xFF18211B),
    surfaceContainer = Color(0xFF1D2821),
    surfaceContainerHigh = Color(0xFF253129),
    surfaceContainerHighest = Color(0xFF2D3A31),
    onSurfaceVariant = Color(0xFFB9C6BC),
    outline = Color(0xFF7F9084),
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF21664A),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCEBE2),
    onPrimaryContainer = Color(0xFF103D2B),
    secondary = Color(0xFFE46D4C),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFDACE),
    onSecondaryContainer = Color(0xFF5B1B09),
    tertiary = Color(0xFFD99532),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFE7B3),
    onTertiaryContainer = Color(0xFF493500),
    background = Color(0xFFF7F5EF),
    onBackground = Color(0xFF17211B),
    surface = Color(0xFFFFFBF5),
    onSurface = Color(0xFF17211B),
    surfaceVariant = Color(0xFFE7E2D8),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF4F0E8),
    surfaceContainer = Color(0xFFEEEAE0),
    surfaceContainerHigh = Color(0xFFE7E1D6),
    surfaceContainerHighest = Color(0xFFDED8CC),
    onSurfaceVariant = Color(0xFF59635C),
    outline = Color(0xFF788079),
)

@Composable
fun DiaPilotTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Dynamic color is available on Android 12+
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
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
        content = content
    )
}
