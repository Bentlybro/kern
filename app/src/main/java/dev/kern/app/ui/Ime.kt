package dev.kern.app.ui

import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/** Whether the soft keyboard is up, asked of the window this view sits in. */
fun View.imeVisible(): Boolean =
    ViewCompat.getRootWindowInsets(this)?.isVisible(WindowInsetsCompat.Type.ime()) == true

/**
 * Raise the soft keyboard for this view.
 *
 * The focus request is not optional: the IME attaches to whatever holds focus, so asking
 * for it without one gets a keyboard that types into nothing.
 */
fun View.showIme() {
    requestFocus()
    ViewCompat.getWindowInsetsController(this)?.show(WindowInsetsCompat.Type.ime())
}

fun View.hideIme() {
    ViewCompat.getWindowInsetsController(this)?.hide(WindowInsetsCompat.Type.ime())
}

fun View.toggleIme() {
    if (imeVisible()) hideIme() else showIme()
}

/** Terminal if it holds focus, else the workbench. The one place this rule is written. */
fun focusedInputView(): View? =
    if (TerminalSessions.hasFocus()) TerminalSessions.current() else WorkbenchWebView.current()
