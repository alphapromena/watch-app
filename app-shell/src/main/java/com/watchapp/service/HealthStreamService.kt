package com.watchapp.service

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
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import com.watchapp.container
import com.watchapp.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Foreground service that owns the live `collector → streamer` pipeline.
 *
 * Lifecycle:
 *  - onCreate(): create the notification channel, post the ongoing notification
 *    (`startForeground` with type `health|location`), acquire a partial wake
 *    lock so HR/GPS keep flowing with the screen off.
 *  - onStartCommand(): wire collector and streamer via [serviceScope].
 *  - onDestroy(): cancel the pipeline, suspend-stop collector + streamer
 *    under a short timeout, release the wake lock.
 *
 * A 30 s ticker calls [com.watchapp.contracts.Streamer.sendHeartbeat] only
 * when no [com.watchapp.contracts.SensorEvent] has been forwarded in the
 * preceding window — matches the FCAF v2 contract phrasing "every 30s when
 * no other data is flowing" and confirms the suppression decision raised in
 * INTEGRATION_NOTES item #6.
 */
class HealthStreamService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var wakeLock: PowerManager.WakeLock? = null

    /** Monotonic time of the last forwarded event; drives heartbeat suppression. */
    @Volatile private var lastEventElapsedMs: Long = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
        )
        wakeLock = getSystemService<PowerManager>()!!
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
            .apply {
                setReferenceCounted(false)
                acquire(WAKE_LOCK_TIMEOUT_MS)
            }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startPipeline()
        return START_STICKY
    }

    private fun startPipeline() {
        val c = container

        // 1. Open the TCP connection (idempotent — safe if already connected).
        serviceScope.launch { c.streamer.connect() }

        // 2. Forward every sensor event into the streamer's outbound queue.
        serviceScope.launch {
            c.collector.start(this)
                .onEach {
                    lastEventElapsedMs = nowElapsedMs()
                    c.lastEventAt.value = System.currentTimeMillis()
                    c.streamer.enqueue(it)
                }
                .collect()
        }

        // 3. Idle-heartbeat ticker. Fires only if no event in the last window.
        serviceScope.launch {
            while (true) {
                delay(HEARTBEAT_PERIOD_MS)
                val idle = nowElapsedMs() - lastEventElapsedMs
                if (idle >= HEARTBEAT_PERIOD_MS) {
                    c.streamer.sendHeartbeat()
                }
            }
        }
    }

    override fun onDestroy() {
        val c = container
        serviceScope.cancel()
        // Best-effort orderly shutdown. Suspend funcs need a scope; bound by a
        // 2 s timeout so the watch never ANRs on stopService().
        runBlocking {
            withTimeoutOrNull(SHUTDOWN_TIMEOUT_MS) {
                withContext(NonCancellable + Dispatchers.IO) {
                    runCatching { c.collector.stop() }
                    runCatching { c.streamer.disconnect() }
                }
            }
        }
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        super.onDestroy()
    }

    private fun nowElapsedMs(): Long = android.os.SystemClock.elapsedRealtime()

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = getSystemService<NotificationManager>() ?: return
        if (mgr.getNotificationChannel(NOTIFICATION_CHANNEL_ID) != null) return
        mgr.createNotificationChannel(
            NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Health streaming",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Live heart-rate, SpO2 and GPS upload."
                setShowBadge(false)
            },
        )
    }

    private fun buildNotification(): Notification {
        val tap = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("WatchApp")
            .setContentText("Streaming health data")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentIntent(tap)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    companion object {
        const val NOTIFICATION_ID = 1
        const val NOTIFICATION_CHANNEL_ID = "health_stream"
        const val HEARTBEAT_PERIOD_MS: Long = 30_000L
        private const val WAKE_LOCK_TAG = "WatchApp:HealthStream"
        private const val WAKE_LOCK_TIMEOUT_MS: Long = 24 * 60 * 60 * 1000L
        private const val SHUTDOWN_TIMEOUT_MS: Long = 2_000L

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, HealthStreamService::class.java),
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, HealthStreamService::class.java))
        }
    }
}
