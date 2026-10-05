package com.sprinttiming.app

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.abs

class PeerClockTest {
    /** Simulates exchanges with a peer whose clock = local × (1 + drift) + offset, with asymmetric queueing. */
    private fun simulate(clock: PeerClock, offset: Long, drift: Double, seconds: Int, random: Random) {
        var local = 1_000_000_000_000L
        repeat(seconds * 4) {
            val outbound = 1_000_000L + (if (random.nextInt(4) == 0) random.nextInt(30_000_000) else random.nextInt(800_000))
            val inbound = 1_000_000L + (if (random.nextInt(4) == 0) random.nextInt(30_000_000) else random.nextInt(800_000))
            val t1 = local
            fun remote(at: Long) = at + offset + ((at - 1_000_000_000_000L) * drift).toLong()
            val t2 = remote(t1 + outbound)
            val t3 = t2 + 50_000L
            val t4 = t1 + outbound + 50_000L + inbound
            clock.add(t1, t2, t3, t4)
            local += 250_000_000L
        }
    }

    @Test fun needsWarmUp() {
        val clock = PeerClock()
        assertNull(clock.estimate(0L))
        clock.add(0, 5, 6, 10)
        assertNull(clock.estimate(0L))
    }

    @Test fun recoversOffsetDespiteQueueingSpikes() {
        val clock = PeerClock()
        simulate(clock, offset = 123_456_789L, drift = 0.0, seconds = 20, random = Random(1))
        val estimate = clock.estimate(1_000_000_000_000L + 20_000_000_000L)!!
        assertTrue("error ${estimate.offsetNanos - 123_456_789L}", abs(estimate.offsetNanos - 123_456_789L) < 500_000L)
        assertTrue(estimate.uncertaintyNanos < 1_500_000L)
    }

    @Test fun tracksCrystalDrift() {
        val clock = PeerClock()
        val drift = 40e-6 // 40 ppm: 1.6 ms over the 40 s window
        simulate(clock, offset = -9_000_000L, drift = drift, seconds = 40, random = Random(2))
        val at = 1_000_000_000_000L + 40_000_000_000L
        val truth = -9_000_000L + (40_000_000_000L * drift).toLong()
        val estimate = clock.estimate(at)!!
        assertTrue("error ${estimate.offsetNanos - truth}", abs(estimate.offsetNanos - truth) < 500_000L)
    }

    @Test fun rejectsImpossibleExchanges() {
        val clock = PeerClock()
        assertTrue(!clock.add(100, 50, 40, 200)) // responder clock went backwards
        assertTrue(!clock.add(100, 0, 0, 50))     // reply before request
    }
}
