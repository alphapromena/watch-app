package com.watchapp.networking.tcp

import kotlin.random.Random

/**
 * Backoff policy for the TCP reconnect loop.
 *
 * **Normal mode** — exponential backoff: 1s, 2s, 4s, 8s, 16s, 32s, then capped at
 * 60s. Each delay is jittered ±20% to avoid synchronized reconnect storms across
 * a fleet of watches.
 *
 * **Slow mode** — engaged when either:
 *  - more than [slowModeAfterMs] (default 5 min) has elapsed since the first
 *    failure of the current outage *and* we are still failing; or
 *  - app-shell forwards a `ConnectivityManager.onLost` signal via [onNetworkLost].
 *
 * In slow mode we wait [slowModeDelayMs] (default 5 min) between attempts. Slow
 * mode is exited only by [onNetworkAvailable] or [onConnected], at which point
 * the next attempt fires with a fresh small delay.
 *
 * The class is *not* thread-safe — the streamer drives it from a single
 * `Dispatchers.IO` thread, which matches the brief.
 */
class ReconnectPolicy(
    private val baseDelayMs: Long = DEFAULT_BASE_DELAY_MS,
    private val maxNormalDelayMs: Long = DEFAULT_MAX_NORMAL_DELAY_MS,
    private val slowModeAfterMs: Long = DEFAULT_SLOW_MODE_AFTER_MS,
    private val slowModeDelayMs: Long = DEFAULT_SLOW_MODE_DELAY_MS,
    private val jitterFraction: Double = DEFAULT_JITTER_FRACTION,
    private val random: Random = Random.Default,
) {

    private var attemptCount: Int = 0
    private var firstFailureAtMs: Long = NO_FAILURE
    private var networkLost: Boolean = false

    /**
     * Records that a connect attempt just failed and returns the duration the
     * caller should wait before the next attempt.
     *
     * @param nowMs current wall-clock millis (caller-supplied for testability).
     */
    fun nextDelayMs(nowMs: Long): Long {
        if (attemptCount == 0) firstFailureAtMs = nowMs
        attemptCount += 1

        val outageMs = nowMs - firstFailureAtMs
        val inSlowMode = networkLost || outageMs >= slowModeAfterMs
        return if (inSlowMode) slowModeDelayMs else jittered(exponentialDelayMs())
    }

    /** Resets the policy after a successful connect. */
    fun onConnected() = reset()

    /**
     * Connectivity is back according to `ConnectivityManager`. Exits slow mode
     * and resets the attempt counter so the next failure starts at [baseDelayMs].
     */
    fun onNetworkAvailable() = reset()

    /**
     * Connectivity is gone according to `ConnectivityManager`. Forces subsequent
     * delay computations into slow mode immediately, even if we have not yet
     * crossed [slowModeAfterMs].
     */
    fun onNetworkLost() {
        networkLost = true
    }

    private fun reset() {
        attemptCount = 0
        firstFailureAtMs = NO_FAILURE
        networkLost = false
    }

    private fun exponentialDelayMs(): Long {
        val shift = (attemptCount - 1).coerceIn(0, MAX_SHIFT)
        return (baseDelayMs shl shift).coerceAtMost(maxNormalDelayMs)
    }

    private fun jittered(delayMs: Long): Long {
        val variance = (delayMs * jitterFraction).toLong()
        if (variance <= 0L) return delayMs
        val jitter = random.nextLong(-variance, variance + 1L)
        return (delayMs + jitter).coerceAtLeast(0L)
    }

    companion object {
        /** First failure → ~1 s before the next attempt. */
        const val DEFAULT_BASE_DELAY_MS: Long = 1_000L

        /** Hard cap on the exponential ramp before slow mode kicks in. */
        const val DEFAULT_MAX_NORMAL_DELAY_MS: Long = 60_000L

        /** After this much continuous failure, switch to slow mode. */
        const val DEFAULT_SLOW_MODE_AFTER_MS: Long = 5L * 60_000L

        /** Delay between attempts while in slow mode. */
        const val DEFAULT_SLOW_MODE_DELAY_MS: Long = 5L * 60_000L

        /** Symmetric jitter factor: ±20%. */
        const val DEFAULT_JITTER_FRACTION: Double = 0.2

        private const val NO_FAILURE: Long = -1L

        // baseDelayMs shl 30 already exceeds maxNormalDelayMs by many orders of
        // magnitude; cap the shift to avoid Long overflow on pathologically long
        // outages.
        private const val MAX_SHIFT: Int = 30
    }
}
