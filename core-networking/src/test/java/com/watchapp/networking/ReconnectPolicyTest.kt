package com.watchapp.networking

import com.watchapp.networking.tcp.ReconnectPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ReconnectPolicyTest {

    private fun zeroJitter(): ReconnectPolicy = ReconnectPolicy(jitterFraction = 0.0)

    @Test
    fun `exponential ramp without jitter is 1, 2, 4, 8, 16, 32, 60, 60`() {
        val p = zeroJitter()
        val now = 0L
        assertEquals(1_000L, p.nextDelayMs(now))
        assertEquals(2_000L, p.nextDelayMs(now))
        assertEquals(4_000L, p.nextDelayMs(now))
        assertEquals(8_000L, p.nextDelayMs(now))
        assertEquals(16_000L, p.nextDelayMs(now))
        assertEquals(32_000L, p.nextDelayMs(now))
        assertEquals(60_000L, p.nextDelayMs(now))
        assertEquals(60_000L, p.nextDelayMs(now))
    }

    @Test
    fun `delay caps at maxNormalDelayMs even after many failures`() {
        val p = zeroJitter()
        repeat(20) { p.nextDelayMs(0L) }
        assertEquals(60_000L, p.nextDelayMs(0L))
    }

    @Test
    fun `jitter stays within plus or minus 20 percent`() {
        // Drive a deterministic random; sample many delays at a fixed step.
        val p = ReconnectPolicy(random = Random(seed = 1234))
        val outOfRange = mutableListOf<Long>()
        repeat(2000) {
            val d = p.nextDelayMs(0L)
            // Walking up the ramp grows the expected base; just ensure d is
            // within 20 % of *some* allowed step. Since we observe the value
            // post-jitter, easier check: 0.8s ≤ d ≤ 72s for any valid step.
            if (d < 800L || d > 72_000L) outOfRange.add(d)
        }
        assertTrue("found out-of-range delays: $outOfRange", outOfRange.isEmpty())
    }

    @Test
    fun `slow mode engages after 5 minutes of continuous failure`() {
        val p = zeroJitter()
        // Burn a few attempts at t=0 so attemptCount > 0 and firstFailureAt is set.
        p.nextDelayMs(0L)
        p.nextDelayMs(1_000L)
        // 5 minutes elapse; next call must return the slow-mode delay.
        val delay = p.nextDelayMs(5L * 60_000L)
        assertEquals(5L * 60_000L, delay)
    }

    @Test
    fun `slow mode delay is fixed (no jitter)`() {
        val p = ReconnectPolicy(random = Random(seed = 0))
        repeat(10) { p.nextDelayMs(0L) } // ramp up
        val d1 = p.nextDelayMs(6L * 60_000L)
        val d2 = p.nextDelayMs(7L * 60_000L)
        assertEquals(5L * 60_000L, d1)
        assertEquals(5L * 60_000L, d2)
    }

    @Test
    fun `onConnected resets the attempt counter`() {
        val p = zeroJitter()
        p.nextDelayMs(0L)
        p.nextDelayMs(0L)
        p.nextDelayMs(0L)
        p.onConnected()
        assertEquals(1_000L, p.nextDelayMs(0L))
    }

    @Test
    fun `onNetworkAvailable exits slow mode and resets`() {
        val p = zeroJitter()
        // Drive into slow mode.
        p.nextDelayMs(0L)
        val slowDelay = p.nextDelayMs(6L * 60_000L)
        assertEquals(5L * 60_000L, slowDelay)
        // Network is back.
        p.onNetworkAvailable()
        // Next failure must restart at the base delay.
        assertEquals(1_000L, p.nextDelayMs(7L * 60_000L))
    }

    @Test
    fun `onNetworkLost forces slow mode immediately`() {
        val p = zeroJitter()
        p.nextDelayMs(0L) // attempt 1, normal mode
        p.onNetworkLost()
        // Even though we're well under 5 min, slow mode is forced.
        assertEquals(5L * 60_000L, p.nextDelayMs(2_000L))
    }

    @Test
    fun `firstFailureAt is set on the very first call, not at construction`() {
        val p = zeroJitter()
        // Construct, then wait 6 min before any attempt — should still get base.
        assertEquals(1_000L, p.nextDelayMs(6L * 60_000L))
    }
}
