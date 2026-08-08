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

            // Belt and braces behind the UI's own pre-check: the grant can be revoked
            // between checking it and committing the session, and the platform's text
            // for this ("blocked by unknown source package") names the problem without
            // naming the one settings switch that fixes it.
            PackageInstaller.STATUS_FAILURE_BLOCKED -> {
                Log.w(TAG, "update install blocked by the platform")
                Updates.failed(
                    "Android blocked the install. Allow \"Install unknown apps\" for " +
                        "Kern in Android settings, then press Update again.",
                )
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

/**
 * Brings Kern back after it updates itself.
 *
 * Installing an update kills the running app — that is the platform, not a choice — so
 * the user was left staring at their launcher wondering whether it worked. Nothing in
 * the old process can fix that, because the old process is gone; MY_PACKAGE_REPLACED is
 * delivered to the *new* version, and this receiver is what turns it back into an open
 * app.
 *
 * Gated on a timestamp [Updates.install] stamps just before committing the session, so
 * only an update the user asked for moments ago relaunches. A sideload from adb, a
 * backup restore, or a package replace hours later must not fling the app onto whatever
 * the user is doing by then — that is also why the stamp expires.
 */
class UpdateRelaunchReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return

        val prefs = Prefs.of(context)
        val asked = prefs.getLong(Prefs.KEY_RELAUNCH_AFTER_UPDATE, 0L)
        prefs.edit().remove(Prefs.KEY_RELAUNCH_AFTER_UPDATE).apply()

        val fresh = asked > 0 && System.currentTimeMillis() - asked < 10 * 60 * 1000
        if (!fresh) return

        Log.i("Kern", "updated to a version the user just asked for; reopening")
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: return
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // The direct start succeeds inside the recent-foreground grace the real update
        // flow has (the user pressed Update in Kern seconds ago) and is silently
        // dropped outside it — there is no way to observe which happened. The
        // notification is the guarantee: if the launch worked it is one dismissible
        // line, and if the launch was dropped it is the way back in.
        runCatching { context.startActivity(launch) }
        postUpdatedNotification(context, launch)
    }

    private fun postUpdatedNotification(context: Context, launch: Intent) {
        val nm = context.getSystemService(android.app.NotificationManager::class.java)
        nm.createNotificationChannel(
            android.app.NotificationChannel(
                CHANNEL_ID,
                "Updates",
                android.app.NotificationManager.IMPORTANCE_HIGH,
            ),
        )
        val tap = PendingIntent.getActivity(
            context,
            0,
            launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val version = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull()
        val notification = android.app.Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Kern updated" + (version?.let { " to $it" } ?: ""))
            .setContentText("Tap to reopen.")
            .setContentIntent(tap)
            .setAutoCancel(true)
            // Gone on its own once it has clearly not been needed.
            .setTimeoutAfter(10 * 60 * 1000)
            .build()
        runCatching { nm.notify(NOTIFICATION_ID, notification) }
    }

    private companion object {
        const val CHANNEL_ID = "updates"
        const val NOTIFICATION_ID = 41
    }
}
