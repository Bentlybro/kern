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

    fun token(context: Context): String {
        val prefs = Prefs.of(context)
        prefs.getString(Prefs.KEY_SESSION_TOKEN, null)?.let { return it }
        val bytes = ByteArray(24)
        SecureRandom().nextBytes(bytes)
        val generated = bytes.joinToString("") { "%02x".format(it) }
        prefs.edit().putString(Prefs.KEY_SESSION_TOKEN, generated).apply()
        return generated
    }
}
