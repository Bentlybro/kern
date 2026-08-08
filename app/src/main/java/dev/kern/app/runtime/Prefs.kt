package dev.kern.app.runtime

import android.content.Context
import android.content.SharedPreferences

/**
 * Every key Kern persists, and the two files they live in.
 *
 * The list is the point. Nothing else answered "what state does Kern keep", so a settings
 * export, a reset to defaults, or clearing per-guest state when the guest is deleted each
 * had to start with a grep across the runtime package.
 */
internal object Prefs {

    private const val FILE = "kern"
    private const val USAGE_FILE = "kern_usage"

    fun of(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Surface timings, kept in their own file because [UsageTracker] clears it wholesale. */
    fun usage(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(USAGE_FILE, Context.MODE_PRIVATE)

    const val KEY_RECENTS = "recent_projects"
    const val KEY_CURRENT_FOLDER = "current_folder"
    const val KEY_AGENT_COMMAND = "agent_command"
    const val KEY_SESSION_TOKEN = "session_token"
    const val KEY_STORAGE_LIMIT_MB = "storage_limit_mb"
    const val KEY_UPDATE_SKIPPED_VERSION = "update_skipped_version"
    const val KEY_LAYOUT_EPOCH = "layout_epoch"
    const val KEY_USAGE_EPOCH = "usage_epoch"
    const val KEY_TERMINAL_FONT_SP = "terminal_font_sp"
}
