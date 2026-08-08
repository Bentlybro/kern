package dev.kern.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The frame every full-screen overlay sits in: app background, system-bar insets and the
 * keyboard inset.
 *
 * The insets are the reason this is shared. Four screens had their own copy and had
 * already drifted, so the next system-bar or IME quirk would have been fixed where it was
 * noticed and missed in the other three. Note that [Shell] is deliberately not one of
 * these screens - it pads with `imeAnimationTarget` instead, for the reason written down
 * beside it.
 */
@Composable
fun ScreenSurface(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.systemBars)
            .imePadding(),
        content = content,
    )
}

/**
 * The bar across the top of an overlay: what the screen is called on the left, the things
 * it can do on the right.
 *
 * A screen whose header carries something other than a title does not belong here - it
 * writes its own Row rather than growing this one a slot, which is how a header nobody
 * can read gets built.
 */
@Composable
fun ScreenHeader(title: String, actions: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            fontFamily = FontFamily.Monospace,
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.weight(1f))
        actions()
    }
}

/** A word in a header bar that does something: close, refresh, recheck, stop. */
@Composable
fun HeaderAction(label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Text(label, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
    }
}
