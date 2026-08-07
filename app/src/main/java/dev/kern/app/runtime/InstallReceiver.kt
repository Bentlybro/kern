package dev.kern.app.runtime

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageInstaller
import android.util.Log

/**
 * Receives the package installer's verdict on an update.
 *
 * The interesting case is [PackageInstaller.STATUS_PENDING_USER_ACTION]: the platform
 * will not install anything without the user confirming, and it hands back the intent
 * that asks them. Forwarding it is what makes the system's own confirmation appear — so
 * even after agreeing in Kern, the user still gets Android's dialog and can decline.
 */
class InstallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { context.startActivity(confirm) }
                }
            }

            PackageInstaller.STATUS_SUCCESS -> {
                Log.i(TAG, "update installed")
                Updates.reset()
            }

            else -> {
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                Log.w(TAG, "update install failed: status=$status $message")
                Updates.failed(message ?: "Install was cancelled")
            }
        }
    }

    companion object {
        private const val TAG = "Kern"
        private const val ACTION = "dev.kern.app.INSTALL_RESULT"

        fun intentSender(context: Context, sessionId: Int): IntentSender {
            val intent = Intent(ACTION).setPackage(context.packageName)
            return PendingIntent.getBroadcast(
                context,
                sessionId,
                intent,
                // Mutable because the installer fills in its own status extras.
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            ).intentSender
        }
    }
}
