package com.polaris.caraudiotrigger.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

private val LightColors = lightColorScheme(
    primary = Color(0xFF1598D4),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD9F1FC),
    onPrimaryContainer = Color(0xFF073A52),
    secondary = Color(0xFFFF833D),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFE5D6),
    onSecondaryContainer = Color(0xFF5C2200),
    tertiary = Color(0xFF58738A),
    background = Color(0xFFF7F9FC),
    onBackground = Color(0xFF14212A),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF14212A),
    surfaceVariant = Color(0xFFEDF3F7),
    onSurfaceVariant = Color(0xFF53636E),
    outline = Color(0xFF71828D),
    outlineVariant = Color(0xFFD5E0E7),
    error = Color(0xFFB42318),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF69C9F0),
    onPrimary = Color(0xFF003548),
    primaryContainer = Color(0xFF164D63),
    onPrimaryContainer = Color(0xFFD0F1FF),
    secondary = Color(0xFFFFB184),
    onSecondary = Color(0xFF542000),
    secondaryContainer = Color(0xFF733712),
    onSecondaryContainer = Color(0xFFFFDBC7),
    tertiary = Color(0xFFAFC9DC),
    background = Color(0xFF0C1317),
    onBackground = Color(0xFFE6EEF3),
    surface = Color(0xFF162126),
    onSurface = Color(0xFFE6EEF3),
    surfaceVariant = Color(0xFF223038),
    onSurfaceVariant = Color(0xFFBBC8CF),
    outline = Color(0xFF87969F),
    outlineVariant = Color(0xFF394951),
    error = Color(0xFFFFB4AB),
)

private val AppShapes = Shapes(
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(26.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

@Composable
fun CarAudioTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        shapes = AppShapes,
        content = content,
    )
}
