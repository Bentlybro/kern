package dev.kern.app.runtime

import android.content.Context
import android.os.StatFs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Environment checks (M6). Everything here is a real failure mode this project hit
 * during development, phrased as something the user can act on.
 */
object HealthCheck {

    enum class Level { Ok, Warn, Fail }

    /**
     * A one-tap remedy. Anything the app can do itself belongs here rather than in
     * [Item.fix] — a command to copy is not a fix on a device with no keyboard, and
     * neither is a button that only takes you somewhere else.
     */
    sealed interface Remedy {
        /** Install these packages in the guest, in place, then re-run the checks. */
        data class Install(val packages: List<String>) : Remedy
        /** Hand off to settings, for anything needing more than one decision. */
        data object OpenSettings : Remedy
        /** Android's own battery settings, which only the user can change. */
        data object BatterySettings : Remedy
    }

    data class Item(
        val name: String,
        val level: Level,
        val detail: String,
        /** Explanatory text for things only the user can resolve. */
        val fix: String? = null,
        val remedy: Remedy? = null,
        val remedyLabel: String? = null,
    )

    suspend fun runAll(context: Context): List<Item> = withContext(Dispatchers.IO) {
        val items = mutableListOf<Item>()

        items += batteryItem(context)
        items += storageItem(context)
        items += pageSizeItem()
        items += prootItem(context)

        if (!LinuxRuntime.isInstalled(context)) {
            items += Item(
                "Linux",
                Level.Fail,
                "Not installed yet.",
                "Run setup to download Ubuntu.",
            )
            return@withContext items
        }

        items += guestItem(context)
        items += toolchainItem(context)
        items += githubItem(context)

        items += Item(
            "Background processes",
            Level.Warn,
            "Android can kill child processes (the 32-process phantom killer). If long " +
                "builds die, this is why.",
            "Developer options > \"Disable child process restrictions\".",
        )

        return@withContext items
    }

    /**
     * PRoot is the foundation: if it cannot run, nothing else can. It lives in
     * nativeLibraryDir precisely so it is allowed to execute.
     */
    private suspend fun prootItem(context: Context): Item {
        val probe = LinuxRuntime.probe(context)
        // `proot --version` leads with ASCII art, so pick out the version number rather
        // than the first non-blank line.
        val version = Regex("""\d+\.\d+\.\d+[\d.]*""").find(probe)?.value
        return when {
            version != null -> Item("PRoot", Level.Ok, "version $version")
            probe.contains("proot", ignoreCase = true) -> Item("PRoot", Level.Ok, "running")
            else -> Item("PRoot", Level.Fail, "Did not run: ${probe.take(120)}")
        }
    }

    private suspend fun guestItem(context: Context): Item {
        val name = LinuxRuntime.osPrettyName(context).orEmpty()
        return if (name.isNotBlank()) {
            Item("Linux", Level.Ok, name)
        } else {
            Item("Linux", Level.Warn, "Installed, but the guest did not respond.")
        }
    }

    private suspend fun toolchainItem(context: Context): Item {
        val result = LinuxRuntime.run(
            context,
            """
            for b in code-server git tmux python3 node rg; do
              command -v ${'$'}b >/dev/null 2>&1 && echo "HAVE=${'$'}b" || echo "MISS=${'$'}b"
            done
            """.trimIndent(),
            timeoutMs = 30_000,
        ) ?: return Item("Toolchain", Level.Warn, "Could not query the guest.")

        val missing = result.lines
            .filter { it.startsWith("MISS=") }
            .map { it.removePrefix("MISS=") }
        val required = listOf("code-server", "git")
        val missingRequired = missing.filter { it in required }

        return when {
            // code-server does not come from apt — it is a .deb the app downloads — so a
            // missing one means the install itself is broken, and Repair is the answer.
            "code-server" in missingRequired -> Item(
                "Toolchain",
                Level.Fail,
                "Missing: ${missingRequired.joinToString(", ")}.",
                remedy = Remedy.OpenSettings,
                remedyLabel = "Repair",
            )
            missingRequired.isNotEmpty() -> Item(
                "Toolchain",
                Level.Fail,
                "Missing: ${missingRequired.joinToString(", ")}.",
                remedy = Remedy.Install(packagesFor(missingRequired)),
                remedyLabel = "Install",
            )
            missing.isNotEmpty() -> Item(
                "Toolchain",
                Level.Ok,
                "Core tools present. Not installed: ${missing.joinToString(", ")}.",
                remedy = Remedy.Install(packagesFor(missing)),
                remedyLabel = "Install",
            )
            else -> Item("Toolchain", Level.Ok, "All tools present.")
        }
    }

    /**
     * The checks look for binaries, but apt wants package names, and for two of them
     * those differ — `node` lives in `nodejs`, `rg` in `ripgrep`. Installing by binary
     * name would simply fail to find the package.
     */
    private fun packagesFor(binaries: List<String>): List<String> =
        binaries.map { PACKAGE_FOR[it] ?: it }

    private val PACKAGE_FOR = mapOf(
        "node" to "nodejs",
        "rg" to "ripgrep",
    )

    /** Run an [Remedy.Install]. Returns true when every requested binary is present. */
    suspend fun install(context: Context, packages: List<String>): Boolean =
        withContext(Dispatchers.IO) {
            LinuxRuntime.run(context, "apt-get update -qq", timeoutMs = 300_000)
            val result = LinuxRuntime.run(
                context,
                "apt-get install -y ${packages.joinToString(" ")}",
                timeoutMs = 1_200_000,
            )
            result?.ok == true
        }

    /**
     * Whether the guest can actually reach GitHub. Cloning a public repository works
     * without this; pushing anything does not, so it is worth saying out loud rather
     * than letting the first `git push` of a session be the thing that discovers it.
     */
    private suspend fun githubItem(context: Context): Item =
        when (val account = GitHubAuth.account(context)) {
            is GitHubAuth.Account.Unavailable -> Item(
                "GitHub",
                Level.Warn,
                "Could not reach the guest.",
            )
            is GitHubAuth.Account.ToolsMissing -> Item(
                "GitHub",
                Level.Warn,
                "Needs ${account.missing.joinToString(", ")}.",
                // Install here; signing in is a separate decision and gets its own
                // button once the tools are actually present.
                remedy = Remedy.Install(packagesFor(account.missing)),
                remedyLabel = "Install",
            )
            is GitHubAuth.Account.SignedOut -> Item(
                "GitHub",
                Level.Warn,
                "Not signed in - you can clone public repositories but not push.",
                remedy = Remedy.OpenSettings,
                remedyLabel = "Sign in",
            )
            is GitHubAuth.Account.SignedIn -> Item(
                "GitHub",
                Level.Ok,
                "Signed in as ${account.login}.",
            )
        }

    /**
     * 16 KB-page kernels break some prebuilt binaries; read it from the platform because
     * a minimal guest has no `getconf`.
     */
    private fun pageSizeItem(): Item =
        when (val size = android.system.Os.sysconf(android.system.OsConstants._SC_PAGESIZE)) {
            4096L -> Item("Kernel page size", Level.Ok, "4 KB.")
            16384L -> Item(
                "Kernel page size",
                Level.Warn,
                "16 KB pages - some prebuilt binaries may fail to load.",
            )
            else -> Item("Kernel page size", Level.Warn, "Unexpected: $size bytes.")
        }

    private fun batteryItem(context: Context): Item =
        if (BatteryOptimization.isExempt(context)) {
            Item("Battery", Level.Ok, "Exempt from battery optimisation.")
        } else {
            Item(
                "Battery",
                Level.Warn,
                "Android may suspend the session when the screen is off.",
                remedy = Remedy.BatterySettings,
                remedyLabel = "Allow",
            )
        }

    private fun storageItem(context: Context): Item {
        // Only free space here: measuring the guest means walking tens of thousands of
        // files, which is far too slow for a screen that opens on demand. Settings does
        // that on request instead.
        val stat = StatFs(context.filesDir.absolutePath)
        val freeGb = stat.availableBytes / (1024.0 * 1024 * 1024)
        val detail = "%.1f GB free".format(freeGb)
        return when {
            freeGb < 1.0 -> Item("Storage", Level.Fail, "$detail - too little to work in.")
            freeGb < 3.0 -> Item("Storage", Level.Warn, detail)
            else -> Item("Storage", Level.Ok, detail)
        }
    }
}
