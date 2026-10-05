package com.sprinttiming.app

import kotlin.math.abs
import kotlin.math.roundToLong
import kotlin.math.sqrt

/**
 * Two-way UDP clock relationship with one peer.
 *
 * Sign convention: offset = peer clock − local clock, so `local = remote − offset`.
 * Each exchange gives offset = ((t2 − t1) + (t3 − t4)) / 2 and delay = (t4 − t1) − (t3 − t2); the
 * error of a sample is bounded by delay / 2. Only low-delay samples are used; with enough span a
 * line is fitted through them so drift between the two crystals is tracked instead of averaged.
 */
class PeerClock {
    data class Estimate(val offsetNanos: Long, val uncertaintyNanos: Long, val roundTripNanos: Long, val samples: Int)

    private class Sample(val localTime: Long, val offset: Double, val delay: Long)

    private val samples = ArrayDeque<Sample>()

    @Synchronized fun add(t1: Long, t2: Long, t3: Long, t4: Long): Boolean {
        val delay = (t4 - t1) - (t3 - t2)
        if (t4 < t1 || t3 < t2 || delay < 0L || delay > MAX_DELAY_NANOS) return false
        val local = t1 + (t4 - t1) / 2
        if (samples.isNotEmpty() && local < samples.last().localTime) return false
        samples.addLast(Sample(local, ((t2 - t1) + (t3 - t4)) / 2.0, delay))
        while (samples.size > MAX_SAMPLES || local - samples.first().localTime > WINDOW_NANOS) samples.removeFirst()
        return true
    }

    @Synchronized fun lastSampleTime(): Long? = samples.lastOrNull()?.localTime

    /** Offset (peer − local) at [atLocalNanos], or null until enough exchanges have completed. */
    @Synchronized fun estimate(atLocalNanos: Long): Estimate? {
        if (samples.size < MIN_SAMPLES) return null
        val minDelay = samples.minOf { it.delay }
        val tolerance = maxOf(300_000L, minDelay / 2)
        val good = samples.filter { it.delay <= minDelay + tolerance }
        val best = samples.minBy { it.delay }
        var offset = best.offset
        var residual = 0.0
        val span = good.last().localTime - good.first().localTime
        if (good.size >= 4 && span >= FIT_SPAN_NANOS) {
            val ref = good.first().localTime
            var sw = 0.0; var sx = 0.0; var sy = 0.0
            for (s in good) { val w = weight(s, minDelay); sw += w; sx += w * (s.localTime - ref); sy += w * s.offset }
            val mx = sx / sw; val my = sy / sw
            var num = 0.0; var den = 0.0
            for (s in good) { val w = weight(s, minDelay); val dx = s.localTime - ref - mx; num += w * dx * (s.offset - my); den += w * dx * dx }
            val slope = if (den > 0.0) num / den else 0.0
            if (abs(slope) <= MAX_DRIFT) {
                offset = my + slope * (atLocalNanos - ref - mx)
                var sq = 0.0
                for (s in good) { val e = s.offset - (my + slope * (s.localTime - ref - mx)); sq += e * e }
                residual = sqrt(sq / good.size)
            }
        } else if (good.size >= 3) {
            offset = good.map { it.offset }.sorted()[good.size / 2]
        }
        val uncertainty = (minDelay / 2.0 + residual).roundToLong()
        return Estimate(offset.roundToLong(), uncertainty, minDelay, samples.size)
    }

    private fun weight(s: Sample, minDelay: Long): Double { val d = (s.delay - minDelay + 50_000L).toDouble(); return 1.0 / (d * d) }

    companion object {
        const val MIN_SAMPLES = 3
        const val MAX_SAMPLES = 160
        const val WINDOW_NANOS = 40_000_000_000L
        const val FIT_SPAN_NANOS = 5_000_000_000L
        const val MAX_DELAY_NANOS = 500_000_000L
        const val MAX_DRIFT = 200e-6
    }
}
