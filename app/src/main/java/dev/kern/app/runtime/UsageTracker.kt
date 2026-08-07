package dev.kern.app.runtime

import android.content.Context
import android.content.SharedPreferences
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

    enum class Surface { Editor, Terminal, Cockpit, Projects, Chrome }

    private const val PREFS = "kern_usage"
    private const val EPOCH_KEY = "usage_epoch"

    /**
     * Bump whenever a surface is added, removed or re-scoped.
     *
     * Settings and Status time used to be banked as Editor, and adding [Surface.Chrome]
     * also changes the denominator [editorShare] divides by. Totals recorded under an
     * older scheme are therefore not comparable with new ones, and averaging the two
     * would corrupt the one number decision D12 is meant to be settled on.
     */
    private const val EPOCH = 1

    private var current: Surface? = null
    private var since: Long = 0L

    /** The store, with any totals from a superseded attribution scheme already dropped. */
    private fun prefs(context: Context): SharedPreferences {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getInt(EPOCH_KEY, 0) != EPOCH) {
            prefs.edit().clear().putInt(EPOCH_KEY, EPOCH).apply()
        }
        return prefs
    }

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
        val prefs = prefs(context)
        val key = s.name.lowercase()
        prefs.edit()
            .putLong(key, prefs.getLong(key, 0L) + elapsed)
            .apply()
    }

    fun totals(context: Context): Map<Surface, Long> {
        val prefs = prefs(context)
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
        // Keep the epoch stamp, or the next read would see an unstamped store and treat
        // an intentional reset as a migration.
        prefs(context).edit().clear().putInt(EPOCH_KEY, EPOCH).apply()
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
