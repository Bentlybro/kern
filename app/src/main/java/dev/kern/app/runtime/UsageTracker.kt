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

    /**
     * Bump whenever a surface is added, removed or re-scoped.
     *
     * Settings and Status time used to be banked as Editor, and adding [Surface.Chrome]
     * also changes the denominator [editorShare] divides by. Totals recorded under an
     * older scheme are therefore not comparable with new ones, and averaging the two
     * would corrupt the one number decision D12 is meant to be settled on.
     *
     * 2: the clock did not stop when the app was backgrounded, so every total recorded
     * before this includes however long the phone sat in a pocket with that surface open.
     * The app opens on the editor and stays there, so the error is not evenly spread - it
     * lands almost entirely on the editor, which is the number being measured. Those
     * totals are not comparable with honest ones, so they are dropped rather than carried.
     */
    private const val EPOCH = 2

    private var current: Surface? = null
    private var since: Long = 0L

    /**
     * The surface to go back to when the app returns, while the clock is stopped.
     *
     * Kept apart from [current] so that "paused on the editor" and "on the editor" are
     * different states: only the second one is accumulating time.
     */
    private var pausedOn: Surface? = null

    /** The store, with any totals from a superseded attribution scheme already dropped. */
    private fun prefs(context: Context): SharedPreferences {
        val prefs = Prefs.usage(context)
        if (prefs.getInt(Prefs.KEY_USAGE_EPOCH, 0) != EPOCH) {
            prefs.edit().clear().putInt(Prefs.KEY_USAGE_EPOCH, EPOCH).apply()
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

    /** Bank whatever the current surface has accrued, and keep counting from now. */
    fun flush(context: Context) {
        val s = current ?: return
        val elapsed = SystemClock.elapsedRealtime() - since
        // Under a second is noise from a recomposition rather than use, and is left on the
        // clock rather than thrown away: `since` only moves when the time is actually
        // banked, so a run of short flushes accumulates instead of vanishing a slice at a
        // time. It used to advance unconditionally, which discarded up to a second on
        // every call that took this branch.
        if (elapsed < 1000) return
        since = SystemClock.elapsedRealtime()
        val prefs = prefs(context)
        val key = s.name.lowercase()
        prefs.edit()
            .putLong(key, prefs.getLong(key, 0L) + elapsed)
            .apply()
    }

    /**
     * Stop the clock, because the app is no longer on screen.
     *
     * This was missing, and its absence made the one number the tracker exists to produce
     * wrong. Nothing stopped the clock when the app was backgrounded: `flush` was called
     * only when the shell left composition, so the surface that happened to be open kept
     * accruing for as long as the phone sat in a pocket. The app opens on the editor and
     * stays there, so what that silently produced was hours of "editor" time per day of
     * not using the app at all - inflating exactly the share decision D12 is meant to be
     * settled on, and inflating it in favour of the answer that costs six to nine months.
     *
     * Time with the screen off is not time in a surface. An agent still running in the
     * guest is real work, but it is not evidence about which editing surface is worth
     * building.
     */
    fun pause(context: Context) {
        if (current == null) return
        flush(context)
        pausedOn = current
        current = null
    }

    /** Start it again on whatever was open, after [pause]. */
    fun resume(context: Context) {
        val surface = pausedOn ?: return
        pausedOn = null
        enter(context, surface)
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
        prefs(context).edit().clear().putInt(Prefs.KEY_USAGE_EPOCH, EPOCH).apply()
        current = null
        pausedOn = null
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
