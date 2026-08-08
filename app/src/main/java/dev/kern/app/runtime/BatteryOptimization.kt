package dev.kern.app.runtime

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/**
 * Android's battery optimisation, which suspends background work — and with it builds,
 * agents and the guest itself — as soon as the screen goes off. Three places ask about
 * it and two of them used to send the user to different system screens for the same
 * button, so the request lives here once.
 */
object BatteryOptimization {

    fun isExempt(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * Ask for the exemption. The package Uri is what makes this the one-tap Allow
     * dialog; without it Android opens the full list of installed apps and leaves the
     * user to hunt for Kern in it.
     */
    fun requestExemption(context: Context) {
        val asked = runCatching {
            context.startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + context.packageName),
                ),
            )
        }
        if (asked.isSuccess) return
        // Not every build answers the direct request — the app list is a worse
        // experience, but it is still a way through rather than a dead button.
        runCatching {
            context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }
}
