package dev.kern.app.core

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import dev.kern.app.MainActivity

/**
 * The two things every notification Kern posts needs: a channel, and a tap that opens the
 * app. Each poster used to spell both out, three times over.
 */
object Notifications {

    fun manager(context: Context): NotificationManager =
        context.getSystemService(NotificationManager::class.java)

    /** Create [id] if it does not exist yet. Cheap and idempotent, so call before posting. */
    fun channel(context: Context, id: String, name: String, importance: Int) {
        manager(context).createNotificationChannel(NotificationChannel(id, name, importance))
    }

    /** A tap that brings Kern forward. [requestCode] keeps one notification's tap distinct. */
    fun openApp(
        context: Context,
        requestCode: Int,
        intent: Intent = Intent(context, MainActivity::class.java),
    ): PendingIntent = PendingIntent.getActivity(
        context,
        requestCode,
        intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}
