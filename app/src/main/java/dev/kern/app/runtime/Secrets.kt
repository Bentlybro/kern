package dev.kern.app.runtime

import android.content.Context
import java.security.SecureRandom

/**
 * Per-install secret used to authenticate the local code-server (risk R16).
 *
 * Android does not isolate localhost between apps, so any app holding INTERNET
 * permission could otherwise reach the IDE server. code-server runs with
 * `--auth password` using this value, and the WebView logs in silently.
 */
object Secrets {

    private const val PREFS = "kern"
    private const val KEY = "session_token"

    fun token(context: Context): String {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY, null)?.let { return it }
        val bytes = ByteArray(24)
        SecureRandom().nextBytes(bytes)
        val generated = bytes.joinToString("") { "%02x".format(it) }
        prefs.edit().putString(KEY, generated).apply()
        return generated
    }
}
