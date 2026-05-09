package com.watchapp.service

import android.app.Service
import android.content.Intent
import android.os.IBinder

/**
 * Foreground service of type `health|location`. Owns the wake lock, the
 * notification, and the collector → streamer pipe. Implementation lands in a
 * follow-up commit; this stub exists so the manifest reference resolves.
 */
class HealthStreamService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
}
