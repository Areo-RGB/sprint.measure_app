package com.sprinttiming.app

import android.location.GnssClock
import kotlin.math.sqrt

class GnssClockModel {
    enum class State { ACQUIRING, LOCKED, DEGRADED, DISCONTINUITY, INVALID }
    data class Snapshot(val state: State, val samples: Int, val uncertaintyNanos: Long, val rate: Double, val offset: Double)
    private data class Sample(val mono: Long, val gps: Long, val uncertainty: Double)
    private val samples = ArrayDeque<Sample>()
    private var discontinuity = -1
    @Volatile var snapshot = Snapshot(State.ACQUIRING, 0, Long.MAX_VALUE, 1.0, 0.0); private set

    @Synchronized fun add(clock: GnssClock) {
        if (!clock.hasFullBiasNanos() || !clock.hasElapsedRealtimeNanos()) { snapshot = snapshot.copy(state = State.INVALID); return }
        if (discontinuity != -1 && discontinuity != clock.hardwareClockDiscontinuityCount) {
            samples.clear(); snapshot = Snapshot(State.DISCONTINUITY, 0, Long.MAX_VALUE, 1.0, 0.0)
        }
        discontinuity = clock.hardwareClockDiscontinuityCount
        val gps = (clock.timeNanos - clock.fullBiasNanos - if (clock.hasBiasNanos()) clock.biasNanos else 0.0).toLong()
        val u = (if (clock.hasTimeUncertaintyNanos()) clock.timeUncertaintyNanos else 50.0) +
            (if (clock.hasElapsedRealtimeUncertaintyNanos()) clock.elapsedRealtimeUncertaintyNanos else 100.0)
        samples.addLast(Sample(clock.elapsedRealtimeNanos, gps, u.coerceAtLeast(1.0)))
        while (samples.size > 60) samples.removeFirst()
        fit()
    }

    private fun fit() {
        if (samples.size < 6) { snapshot = Snapshot(State.ACQUIRING, samples.size, Long.MAX_VALUE, 1.0, 0.0); return }
        val x0 = samples.first().mono.toDouble(); val y0 = samples.first().gps.toDouble()
        var sw = 0.0; var sx = 0.0; var sy = 0.0
        for (s in samples) { val w = 1.0 / (s.uncertainty * s.uncertainty); sw += w; sx += w * (s.mono - x0); sy += w * (s.gps - y0) }
        val mx = sx / sw; val my = sy / sw
        var num = 0.0; var den = 0.0
        for (s in samples) { val w = 1.0 / (s.uncertainty * s.uncertainty); val x = s.mono - x0; val y = s.gps - y0; num += w * (x - mx) * (y - my); den += w * (x - mx) * (x - mx) }
        val rate = if (den > 0) num / den else 1.0
        val offset = (y0 + my) - rate * (x0 + mx)
        var error = 0.0
        for (s in samples) { val e = s.gps - (rate * s.mono + offset); error += e * e }
        val rms = sqrt(error / samples.size).toLong().coerceAtLeast(1)
        val span = samples.last().mono - samples.first().mono
        val state = if (span >= 5_000_000_000L && rms <= 2_000_000L && rate in 0.9999..1.0001) State.LOCKED else State.DEGRADED
        snapshot = Snapshot(state, samples.size, rms, rate, offset)
    }

    fun toGps(monotonicNanos: Long): Pair<Long, Long>? {
        val s = snapshot
        if (s.state != State.LOCKED && s.state != State.DEGRADED) return null
        return (s.rate * monotonicNanos + s.offset).toLong() to s.uncertaintyNanos
    }
}
