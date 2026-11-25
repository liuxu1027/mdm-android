package com.mdm.android.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val MinimalistColors = darkColorScheme(
    primary = Color(0xFFFED54F),
    onPrimary = Color(0xFF202020),
    secondary = Color(0xFF75B7AF),
    onSecondary = Color(0xFF142321),
    background = Color(0xFF202020),
    onBackground = Color(0xFFF1F1F1),
    surface = Color(0xFF303030),
    onSurface = Color(0xFFF1F1F1),
    surfaceVariant = Color(0xFF252525),
    onSurfaceVariant = Color(0xFFBFC3C3),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    outline = Color(0xFF777777)
)

@Composable
fun MdmTheme(
    themeStyle: String,
    content: @Composable () -> Unit
) {
    if (themeStyle == "minimalist") {
        MaterialTheme(colorScheme = MinimalistColors, content = content)
    } else {
        MaterialTheme(content = content)
    }
}
