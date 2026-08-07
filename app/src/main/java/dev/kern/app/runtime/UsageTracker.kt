package dev.kern.app.runtime

import android.content.Context
import android.os.SystemClock

/**
 * Measures where session time actually goes (milestone M5a).
 *
 * Decision D12 deferred the native-editor rewrite behind a measurement rather than a
 * hunch: if hand-editing turns out to be a small share of real use — likely, in an
 * agent-driven workflow — then a 6–9 month editor rewrite has not earned itself, and the
 * project stops with a fold-native app that works. This records the number that decides
 * it.
 *
 * Local only; nothing leaves the device.
 */
object UsageTracker {

    enum class Surface { Editor, Terminal, Cockpit, Projects }

    private const val PREFS = "kern_usage"

    private var current: Surface? = null
    private var since: Long = 0L

    /** Call when a surface becomes visible. Closes out the previous one. */
    fun enter(context: Context, surface: Surface) {
        if (current == surface) return
        flush(context)
        current = surface
        since = SystemClock.elapsedRealtime()
    }

    /** Call when the app goes to the background. */
    fun flush(context: Context) {
        val s = current ?: return
        val elapsed = SystemClock.elapsedRealtime() - since
        since = SystemClock.elapsedRealtime()
        if (elapsed < 1000) return
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val key = s.name.lowercase()
        prefs.edit()
            .putLong(key, prefs.getLong(key, 0L) + elapsed)
            .apply()
    }

    fun totals(context: Context): Map<Surface, Long> {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return Surface.entries.associateWith { prefs.getLong(it.name.lowercase(), 0L) }
    }

    /** Share of time in the editor — the number decision D12 hinges on. */
    fun editorShare(context: Context): Float {
        val t = totals(context)
        val total = t.values.sum()
        if (total <= 0L) return 0f
        return (t[Surface.Editor] ?: 0L).toFloat() / total
    }

    fun reset(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().clear().apply()
        current = null
    }

    fun format(ms: Long): String {
        val minutes = ms / 60_000
        return when {
            minutes < 1 -> "<1m"
            minutes < 60 -> "${minutes}m"
            else -> "${minutes / 60}h ${minutes % 60}m"
        }
    }
}
