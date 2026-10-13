package dev.kern.app.core

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context

/**
 * The platform clipboard, rather than Compose's: LocalClipboardManager is deprecated, and
 * the terminal - a View, not a composable - needs the same calls anyway.
 */
object Clipboard {

    private fun manager(context: Context): ClipboardManager? =
        context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager

    /** False when there is no clipboard service to write to. */
    fun copy(context: Context, label: String, text: String): Boolean {
        val cm = manager(context) ?: return false
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
        return true
    }

    /** The clipboard's first item as text, or null when it is empty or unreadable. */
    fun paste(context: Context): String? =
        manager(context)?.primaryClip?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)?.coerceToText(context)?.toString()
}
