package dev.kern.app.session

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import dev.kern.app.MainActivity
import dev.kern.app.R
import dev.kern.app.runtime.AgentRepository
import dev.kern.app.runtime.LinuxRuntime
import dev.kern.app.runtime.Secrets
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

sealed interface SessionState {
    data object Idle : SessionState
    data object Starting : SessionState
    data object Healthy : SessionState
    data object Reconnecting : SessionState
    data class Failed(val message: String) : SessionState
}

/**
 * Foreground service that supervises the IDE session: starts code-server inside the
 * Linux guest, health-polls it, restarts it when it dies, and holds a partial wakelock so
 * the guest keeps running with the screen off.
 */
class SessionService : Service() {

    companion object {
        private const val NOTIF_ID = 1
        private const val NOTIF_AGENT_ID = 2
        private const val CHANNEL_ID = "session"
        private const val AGENT_CHANNEL_ID = "agent"
        private const val TAG = "Kern"
        const val ACTION_START = "dev.kern.app.action.START"
        const val ACTION_STOP = "dev.kern.app.action.STOP"

        private val _state = MutableStateFlow<SessionState>(SessionState.Idle)
        val state: StateFlow<SessionState> = _state.asStateFlow()

        fun start(context: Context) {
            context.startForegroundService(
                Intent(context, SessionService::class.java).setAction(ACTION_START),
            )
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, SessionService::class.java).setAction(ACTION_STOP),
            )
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var superviseJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return when (intent?.action) {
            ACTION_STOP -> {
                shutdown()
                START_NOT_STICKY
            }
            else -> {
                goForeground("Starting code-server...")
                acquireWakeLock()
                if (superviseJob?.isActive != true) {
                    superviseJob = scope.launch { supervise() }
                }
                START_STICKY
            }
        }
    }

    override fun onDestroy() {
        wakeLock?.let { if (it.isHeld) it.release() }
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun supervise() {
        _state.value = SessionState.Starting
        Log.i(TAG, "supervise: starting code-server in the Linux guest")

        if (isHealthy()) {
            Log.i(TAG, "supervise: adopted an already-running server")
        } else {
            LinuxRuntime.applyWorkbenchSettings(this)
            LinuxRuntime.startCodeServer(this, Secrets.token(this))
            if (!awaitHealthy(90_000)) {
                val why = "code-server did not start. See /root/.kern/server.log " +
                    "in the terminal."
                Log.e(TAG, "supervise: $why")
                _state.value = SessionState.Failed(why)
                updateNotification("Failed to start - open the app for details")
                return
            }
        }
        _state.value = SessionState.Healthy
        updateNotification("Running on 127.0.0.1:${LinuxRuntime.CODE_SERVER_PORT}")

        var misses = 0
        var lastAgentState: AgentRepository.State? = null
        var agentTick = 0
        while (scope.isActive) {
            delay(15_000)

            // Pocket workflow (M5): while a session is running, watch for the agent
            // stopping to ask something and raise a notification so the phone can be in
            // a pocket. Polled every other health tick - cheap, and tool-agnostic.
            if (++agentTick % 2 == 0) {
                runCatching {
                    val snap = AgentRepository.snapshot(this@SessionService, 12)
                    if (snap.state != lastAgentState) {
                        if (snap.state == AgentRepository.State.AwaitingInput) {
                            notifyAgent(
                                "Agent needs you",
                                snap.tail.lastOrNull { it.isNotBlank() }?.trim()?.take(120)
                                    ?: "Waiting for input",
                            )
                        }
                        lastAgentState = snap.state
                    }
                }
            }

            if (isHealthy()) {
                if (misses > 0) {
                    updateNotification("Running on 127.0.0.1:${LinuxRuntime.CODE_SERVER_PORT}")
                }
                misses = 0
                _state.value = SessionState.Healthy
            } else {
                misses++
                if (misses >= 2) {
                    _state.value = SessionState.Reconnecting
                    updateNotification("Server died - restarting...")
                    LinuxRuntime.stopCodeServer()
                    LinuxRuntime.startCodeServer(this, Secrets.token(this))
                    if (awaitHealthy(45_000)) {
                        misses = 0
                        _state.value = SessionState.Healthy
                        updateNotification(
                            "Running on 127.0.0.1:${LinuxRuntime.CODE_SERVER_PORT}",
                        )
                    }
                }
            }
        }
    }

    private suspend fun awaitHealthy(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (isHealthy()) return true
            // Poll briskly: this delay is most of the perceived startup time.
            delay(300)
        }
        return isHealthy()
    }

    private fun isHealthy(): Boolean = try {
        val conn = URL(LinuxRuntime.HEALTH_URL).openConnection() as HttpURLConnection
        conn.connectTimeout = 2_000
        conn.readTimeout = 2_000
        val ok = conn.responseCode in 200..299
        conn.disconnect()
        ok
    } catch (e: Exception) {
        false
    }

    private fun shutdown() {
        superviseJob?.cancel()
        LinuxRuntime.stopCodeServer()
        _state.value = SessionState.Idle
        wakeLock?.let { if (it.isHeld) it.release() }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "kern:session").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    /** Separate, higher-importance channel so "agent needs you" actually buzzes. */
    private fun notifyAgent(title: String, text: String) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(
                AGENT_CHANNEL_ID,
                "Agent attention",
                NotificationManager.IMPORTANCE_HIGH,
            ),
        )
        val openIntent = PendingIntent.getActivity(
            this,
            2,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        nm.notify(
            NOTIF_AGENT_ID,
            Notification.Builder(this, AGENT_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_chat)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setContentIntent(openIntent)
                .setAutoCancel(true)
                .build(),
        )
    }

    private fun goForeground(text: String) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel_session),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
        val notification = buildNotification(text)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NOTIF_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, SessionService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(getString(R.string.notif_session_title))
            .setContentText(text)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(
                    null,
                    getString(R.string.notif_action_stop),
                    stopIntent,
                ).build(),
            )
            .build()
    }
}
