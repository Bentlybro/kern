package dev.foldcode.app.runtime

import android.content.Context
import android.os.PowerManager
import android.os.StatFs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Environment checks (M6). Everything here is a real failure mode this project hit
 * during development, phrased as something the user can act on.
 */
object HealthCheck {

    enum class Level { Ok, Warn, Fail }

    data class Item(
        val name: String,
        val level: Level,
        val detail: String,
        val fix: String? = null,
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
        val result = LinuxRuntime.run(
            context,
            ". /etc/os-release 2>/dev/null; echo \"\$PRETTY_NAME\"",
            timeoutMs = 25_000,
        )
        val name = result?.stdout?.trim().orEmpty()
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
            missingRequired.isNotEmpty() -> Item(
                "Toolchain",
                Level.Fail,
                "Missing: ${missingRequired.joinToString(", ")}.",
                "apt install ${missingRequired.joinToString(" ")}",
            )
            missing.isNotEmpty() -> Item(
                "Toolchain",
                Level.Ok,
                "Core tools present. Not installed: ${missing.joinToString(", ")}.",
                "apt install ${missing.joinToString(" ")}",
            )
            else -> Item("Toolchain", Level.Ok, "All tools present.")
        }
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

    private fun batteryItem(context: Context): Item {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return if (pm.isIgnoringBatteryOptimizations(context.packageName)) {
            Item("Battery", Level.Ok, "Exempt from battery optimisation.")
        } else {
            Item(
                "Battery",
                Level.Warn,
                "Android may suspend the session when the screen is off.",
                "Allow unrestricted battery use in app settings.",
            )
        }
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
