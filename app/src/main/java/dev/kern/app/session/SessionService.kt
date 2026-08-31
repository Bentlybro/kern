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
import dev.kern.app.runtime.AgentPrompt
import dev.kern.app.runtime.AgentRepository
import dev.kern.app.runtime.CodeServer
import dev.kern.app.runtime.GuestConfig
import dev.kern.app.ui.TerminalSessions
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

        /** Enough to outlast the toolchain install racing the first start, no more. */
        private const val START_ATTEMPTS = 3
        private const val START_TIMEOUT_MS = 60_000L

        /** How often the server is asked whether it is alive. */
        private const val HEALTH_TICK_MS = 15_000L

        /**
         * How long a *restarted* server gets to answer, against [START_TIMEOUT_MS] for a
         * cold one. Shorter because everything slow about the first start - unpacking,
         * apt, a cold page cache - has already happened by the time this runs.
         */
        private const val RESTART_TIMEOUT_MS = 45_000L
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
                // An explicit start is a request to try again, and it has to be able to
                // lift a give-up. RestartPolicy stops restarting after five consecutive
                // failures but the loop stays alive to keep watching - so the "already
                // supervising" check below saw a live job and did nothing at all, and the
                // Retry button on the failure screen was inert. Caught on device: the
                // supervisor gave up as designed, the binary was put back, and there was
                // no way to get the server started again short of force-stopping the app.
                if (_state.value is SessionState.Failed) {
                    superviseJob?.cancel()
                    superviseJob = null
                }
                if (superviseJob?.isActive != true) {
                    superviseJob = scope.launch { supervise() }
                }
                START_STICKY
            }
        }
    }

    override fun onDestroy() {
        releaseWakeLock()
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun supervise() {
        _state.value = SessionState.Starting
        Log.i(TAG, "supervise: starting code-server in the Linux guest")

        // Point the guest at this network's resolvers before anything in it asks a
        // question. Doing it only in front of apt and git left everything else in the
        // guest - a curl in a terminal, an agent, an extension code-server fetches - on
        // whatever DNS was current when the guest was installed, which on a phone is
        // wrong within the day. Verified on device: an existing guest sat on the public
        // resolvers indefinitely because neither apt nor clone had run since setup.
        GuestConfig.refreshDns(this)

        if (isHealthy()) {
            Log.i(TAG, "supervise: adopted an already-running server")
        } else {
            CodeServer.applyWorkbenchSettings(this)
            if (!launchWithRetries()) return
        }
        _state.value = SessionState.Healthy
        updateNotification(running())

        var watch = RestartPolicy.State()
        var wasAwaiting = false
        var agentTick = 0
        while (scope.isActive) {
            delay(HEALTH_TICK_MS)

            // Pocket workflow (M5): while a session is running, watch for the agent
            // stopping to ask something and raise a notification so the phone can be in
            // a pocket. Polled every other health tick - cheap, and tool-agnostic.
            //
            // Reads the rendered screen rather than a byte stream, because a TUI agent
            // repaints in place: the last thing written and the last thing shown are
            // routinely different, and only the second one is the question.
            if (++agentTick % 2 == 0) {
                runCatching {
                    val screen = TerminalSessions.agentScreen()
                    val awaiting = screen != null && AgentPrompt.awaitingInput(screen)
                    if (awaiting && !wasAwaiting) {
                        notifyAgent(
                            "Agent needs you",
                            AgentPrompt.lastLine(screen!!)?.take(120) ?: "Waiting for input",
                        )
                    }
                    wasAwaiting = awaiting
                }
            }

            val wasDegraded = watch != RestartPolicy.State()
            val (nextWatch, decision) = RestartPolicy.next(watch, isHealthy())
            watch = nextWatch

            when (decision) {
                RestartPolicy.Decision.Healthy -> {
                    // Only when something had gone wrong, or this rewrites the same
                    // notification every fifteen seconds for the life of the session.
                    if (wasDegraded) {
                        acquireWakeLock()
                        updateNotification(running())
                    }
                    _state.value = SessionState.Healthy
                }

                RestartPolicy.Decision.Wait -> Unit

                is RestartPolicy.Decision.Abandoned -> Unit

                is RestartPolicy.Decision.Restart -> {
                    _state.value = SessionState.Reconnecting
                    updateNotification(
                        if (decision.attempt == 1) {
                            "Server died - restarting..."
                        } else {
                            "Server died - restarting (attempt ${decision.attempt})..."
                        },
                    )
                    delay(decision.backoffMs)
                    CodeServer.stop()
                    CodeServer.start(this, Secrets.token(this))
                    if (awaitHealthy(RESTART_TIMEOUT_MS)) {
                        // Reset here as well as in the policy: awaitHealthy is a whole
                        // recovery the next tick has no way to learn about otherwise, and
                        // leaving the restart count standing would spend the budget on a
                        // server that came back.
                        watch = RestartPolicy.State()
                        _state.value = SessionState.Healthy
                        updateNotification(running())
                    }
                }

                is RestartPolicy.Decision.GiveUp -> {
                    Log.e(TAG, "supervise: ${decision.message}")
                    _state.value = SessionState.Failed(decision.message)
                    updateNotification("Server is down - open the app for details")
                    // Nothing left to keep the CPU awake for. The only way back from here
                    // is the user running Repair, and they will be looking at the screen
                    // when they do - at which point a healthy check re-acquires it.
                    releaseWakeLock()
                }
            }
        }
    }

    private fun running() = "Running on 127.0.0.1:${CodeServer.PORT}"

    /**
     * Start the server, and try again if it dies on the way up.
     *
     * The first start after a fresh setup fails reproducibly on device: the toolchain apt
     * runs on in the same guest behind the opening editor, and code-server's bash exits
     * within about a hundred milliseconds without creating so much as its log directory.
     * The same start succeeds every time once that has finished, so it is transient rather
     * than broken. What made it fatal was the response, not the fault - one attempt, then
     * ninety seconds spent polling a process we had already been told was dead, then a
     * failure blaming a log file that was never created.
     *
     * Returns false only after every attempt has failed, having already reported why.
     */
    private suspend fun launchWithRetries(): Boolean {
        repeat(START_ATTEMPTS) { attempt ->
            if (attempt > 0) {
                Log.i(TAG, "supervise: retrying code-server (attempt ${attempt + 1})")
                CodeServer.stop()
                delay(3_000)
            }
            if (!CodeServer.start(this, Secrets.token(this))) {
                // A refused spawn is known immediately. Retrying it is still worth a turn,
                // since fd and process pressure during setup is exactly what causes it.
                return@repeat
            }
            if (awaitHealthy(START_TIMEOUT_MS)) return true
        }

        val why = "code-server did not start after $START_ATTEMPTS attempts. " +
            "See /root/.kern/server.log in the terminal, or try Repair in settings."
        Log.e(TAG, "supervise: $why")
        _state.value = SessionState.Failed(why)
        updateNotification("Failed to start - open the app for details")
        return false
    }

    /**
     * Poll until the server answers, or until it is gone.
     *
     * Stopping early when the process has died is the point: without it a server that
     * exited in a tenth of a second still cost the full timeout before anyone noticed,
     * which is most of what made this look like a hang rather than a crash.
     */
    private suspend fun awaitHealthy(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (isHealthy()) return true
            if (!CodeServer.isRunning()) return false
            // Poll briskly: this delay is most of the perceived startup time.
            delay(300)
        }
        return isHealthy()
    }

    private fun isHealthy(): Boolean = try {
        val conn = URL(CodeServer.HEALTH_URL).openConnection() as HttpURLConnection
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
        CodeServer.stop()
        _state.value = SessionState.Idle
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
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
