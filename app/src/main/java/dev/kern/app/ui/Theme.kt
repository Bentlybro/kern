package dev.kern.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Kern's palette: near-black layers that step up in small increments, one warm accent used
 * sparingly, and text in two weights of grey. Every container role is set rather than left
 * to Material's defaults, which tint menus, dialogs and sheets purple.
 *
 * The background matches the editor's (VS Code Dark Modern, #1F1F1F family) closely
 * enough that the editor reads as part of the app rather than a window inside it.
 */
private val Scheme = darkColorScheme(
    primary = Color(0xFFE2B15F),
    onPrimary = Color(0xFF221603),
    primaryContainer = Color(0xFF3A2E17),
    onPrimaryContainer = Color(0xFFF3D9A6),
    secondary = Color(0xFF8E939C),
    onSecondary = Color(0xFF121417),
    secondaryContainer = Color(0xFF2B3036),
    onSecondaryContainer = Color(0xFFE3E5E8),
    tertiaryContainer = Color(0xFF1E2F40),
    onTertiaryContainer = Color(0xFFCFE2F5),
    tertiary = Color(0xFF7FA7D0),
    background = Color(0xFF111316),
    onBackground = Color(0xFFE3E5E8),
    surface = Color(0xFF16191D),
    onSurface = Color(0xFFE3E5E8),
    surfaceVariant = Color(0xFF22262B),
    onSurfaceVariant = Color(0xFF9CA1AA),
    surfaceContainerLowest = Color(0xFF0D0F11),
    surfaceContainerLow = Color(0xFF15181B),
    surfaceContainer = Color(0xFF1A1D21),
    surfaceContainerHigh = Color(0xFF1F2328),
    surfaceContainerHighest = Color(0xFF262A30),
    surfaceBright = Color(0xFF2C3036),
    outline = Color(0xFF3A3F46),
    outlineVariant = Color(0xFF2A2E34),
    error = Color(0xFFE07A62),
    onError = Color(0xFF2A0D06),
    errorContainer = Color(0xFF4A1E15),
    onErrorContainer = Color(0xFFF6C9BD),
    inverseSurface = Color(0xFFE3E5E8),
    inverseOnSurface = Color(0xFF16191D),
    scrim = Color(0xCC000000),
)

private val Type = Typography().let { base ->
    base.copy(
        titleLarge = base.titleLarge.copy(fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.copy(fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
        titleSmall = base.titleSmall.copy(fontSize = 15.sp, fontWeight = FontWeight.Medium),
        bodyLarge = base.bodyLarge.copy(fontSize = 15.sp),
        bodyMedium = base.bodyMedium.copy(fontSize = 14.sp),
        bodySmall = base.bodySmall.copy(fontSize = 12.5.sp),
        labelLarge = base.labelLarge.copy(fontSize = 14.sp, fontWeight = FontWeight.Medium),
        // Section labels: small caps-style, tracked out, quiet.
        labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.9.sp),
    )
}

/**
 * The colours that carry a status, in one place.
 *
 * Only what the scheme above has no name for lives here: "failed" is already `error` and
 * "unknown" is already `secondary`, and giving either a second name here is how the two
 * copies drift apart again.
 */
object KernColors {
    val Ok = Color(0xFF6FBF86)
    val Warn = Color(0xFFD9A54E)
    val DiffHunk = Color(0xFF7FA7D0)
}

@Composable
fun KernTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, typography = Type, content = content)
}
