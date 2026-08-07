package dev.kern.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val AmoledScheme = darkColorScheme(
    primary = Color(0xFFE4B15C),
    onPrimary = Color(0xFF1A1102),
    secondary = Color(0xFF8F929A),
    background = Color(0xFF0A0B0D),
    onBackground = Color(0xFFD9DBDF),
    surface = Color(0xFF121417),
    onSurface = Color(0xFFD9DBDF),
    surfaceVariant = Color(0xFF1A1D21),
    onSurfaceVariant = Color(0xFF9DA0A8),
    error = Color(0xFFD07158),
)

@Composable
fun KernTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = AmoledScheme, content = content)
}
