package dev.kern.app.runtime

import android.content.Context
import android.content.pm.PackageInstaller
import android.util.Log
import dev.kern.app.BuildConfig
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Optional over-the-air updates, from GitHub Releases.
 *
 * Deliberately never automatic. It asks, it can be skipped for a given version, and a
 * skipped version stays skipped — an IDE that restarts itself while you are working is
 * worse than one that is a version behind.
 *
 * **What actually keeps this safe.** Not this code: Android refuses to install an update
 * signed with a different key than the installed app, so the release signing key is the
 * security boundary. Someone who serves a malicious APK from a compromised release cannot
 * get it installed over Kern without that key. This class adds the cheap checks on top —
 * HTTPS only, the asset must come from this repository's release, and the download is
 * staged in app-private storage where nothing else can rewrite it before it is handed to
 * the installer.
 */
object Updates {

    private const val TAG = "Kern"

    /** The repository releases are published from. */
    const val REPO = "Bentlybro/kern"

    private const val LATEST = "https://api.github.com/repos/$REPO/releases/latest"

    private const val PREFS = "kern"
    private const val KEY_SKIPPED = "update_skipped_version"

    data class Release(
        val version: String,
        val notes: String,
        val apkUrl: String,
        val sizeBytes: Long,
    )

    sealed interface State {
        data object Idle : State
        data object Checking : State
        data object UpToDate : State
        data class Available(val release: Release) : State
        data class Downloading(val percent: Int) : State
        data object Installing : State
        data class Failed(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    val currentVersion: String get() = BuildConfig.VERSION_NAME

    fun reset() {
        _state.value = State.Idle
    }

    /** Reported by [InstallReceiver] when the platform declines or the user cancels. */
    fun failed(message: String) {
        _state.value = State.Failed(message)
    }

    // ---- checking -----------------------------------------------------------

    /** Ask GitHub what the newest release is. Returns null when already current. */
    suspend fun check(context: Context, includeSkipped: Boolean = false): Release? =
        withContext(Dispatchers.IO) {
            _state.value = State.Checking
            try {
                val release = fetchLatest() ?: run {
                    _state.value = State.UpToDate
                    return@withContext null
                }

                val skipped = skippedVersion(context)
                when {
                    !isNewer(release.version, currentVersion) -> {
                        _state.value = State.UpToDate
                        null
                    }
                    !includeSkipped && release.version == skipped -> {
                        _state.value = State.Idle
                        null
                    }
                    else -> {
                        _state.value = State.Available(release)
                        release
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "update check failed", e)
                // Network failures surface as the URL or a bare class name, neither of
                // which means anything to a reader.
                val reason = e.message
                    ?.takeIf { it.isNotBlank() && !it.startsWith("http") }
                    ?: "Could not reach GitHub"
                _state.value = State.Failed(reason)
                null
            }
        }

    private fun fetchLatest(): Release? {
        val connection = (URL(LATEST).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 15_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "Kern/${BuildConfig.VERSION_NAME}")
        }

        // Read the status rather than letting getInputStream throw: a 404 is the ordinary
        // answer for a repository with no published release yet (and for a private one),
        // and the exception's message is just the URL — which is a useless thing to show
        // a user in place of an explanation.
        when (val code = connection.responseCode) {
            200 -> Unit
            404 -> return null
            403 -> throw IllegalStateException("GitHub rate limit reached — try again later")
            else -> throw IllegalStateException("GitHub returned $code")
        }

        connection.inputStream.use { stream ->
            val json = JSONObject(stream.reader().readText())
            if (json.optBoolean("draft") || json.optBoolean("prerelease")) return null

            val tag = json.optString("tag_name").removePrefix("v")
            if (tag.isBlank()) return null

            val assets = json.optJSONArray("assets") ?: return null
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                val name = asset.optString("name")
                if (!name.endsWith(".apk")) continue
                val url = asset.optString("browser_download_url")
                // Belt and braces against a redirect somewhere odd: the asset has to be
                // served over TLS from GitHub for this repository.
                if (!url.startsWith("https://github.com/$REPO/releases/")) continue
                return Release(
                    version = tag,
                    notes = json.optString("body").trim(),
                    apkUrl = url,
                    sizeBytes = asset.optLong("size"),
                )
            }
            return null
        }
    }

    /**
     * Compare dotted versions numerically, so 0.10.0 is correctly newer than 0.9.0 —
     * which a string comparison gets backwards. Any suffix after a dash (`-rc1`) is
     * treated as older than the plain release, as semver requires.
     */
    internal fun isNewer(candidate: String, current: String): Boolean {
        fun parts(v: String) = v.substringBefore('-')
            .split('.')
            .map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }

        val a = parts(candidate)
        val b = parts(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val left = a.getOrElse(i) { 0 }
            val right = b.getOrElse(i) { 0 }
            if (left != right) return left > right
        }
        // Equal numerically: a plain release beats a pre-release of the same number.
        val candidatePre = candidate.contains('-')
        val currentPre = current.contains('-')
        return currentPre && !candidatePre
    }

    // ---- skipping -----------------------------------------------------------

    fun skippedVersion(context: Context): String? =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_SKIPPED, null)

    fun skip(context: Context, version: String) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_SKIPPED, version).apply()
        _state.value = State.Idle
    }

    // ---- downloading and installing -----------------------------------------

    /**
     * Fetch the APK into app-private cache.
     *
     * Private storage rather than Downloads on purpose: nothing else on the device can
     * rewrite the file between verifying it and installing it.
     */
    suspend fun download(context: Context, release: Release): File? = withContext(Dispatchers.IO) {
        _state.value = State.Downloading(0)
        val target = File(context.cacheDir, "kern-${release.version}.apk")
        try {
            LinuxRuntime.download(release.apkUrl, target) { got, total ->
                val percent = if (total > 0) (got * 100 / total).toInt() else 0
                _state.value = State.Downloading(percent)
            }
            if (target.length() <= 0) throw IllegalStateException("Downloaded nothing")
            target
        } catch (e: Exception) {
            Log.w(TAG, "update download failed", e)
            runCatching { target.delete() }
            _state.value = State.Failed(e.message ?: "Download failed")
            null
        }
    }

    /**
     * Hand the APK to Android's package installer.
     *
     * A session rather than an ACTION_VIEW intent: no file URI leaves the app, and the
     * platform still shows its own confirmation, so the user gets a second chance to say
     * no after having said yes here.
     */
    suspend fun install(context: Context, apk: File): Boolean = withContext(Dispatchers.IO) {
        _state.value = State.Installing
        try {
            val installer = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL,
            )
            val sessionId = installer.createSession(params)
            installer.openSession(sessionId).use { session ->
                session.openWrite("kern", 0, apk.length()).use { out ->
                    apk.inputStream().use { it.copyTo(out) }
                    session.fsync(out)
                }
                session.commit(InstallReceiver.intentSender(context, sessionId))
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "update install failed", e)
            _state.value = State.Failed(e.message ?: "Install failed")
            false
        }
    }
}
