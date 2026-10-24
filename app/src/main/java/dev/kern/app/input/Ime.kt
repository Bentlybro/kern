package dev.kern.app.input

import android.content.Context
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

/**
 * The view the keyboard belongs to, by [InputTargets]' rule: the terminal if it holds focus,
 * else whichever editor is chosen. This used to pick between the terminal and the workbench
 * only, so with the native editor - the default - the key row's keyboard key and hardware
 * chords went to a workbench that was not on screen.
 */
fun focusedInputView(context: Context): View? = InputTargets.view(context)
