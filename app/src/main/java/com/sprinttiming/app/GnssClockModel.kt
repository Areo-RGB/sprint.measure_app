package com.sprinttiming.app

import android.location.GnssClock
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlin.math.sqrt

/**
 * Affine elapsed-realtime → GPS time model: `gps = gpsRef + rate × (mono − monoRef)`.
 * References are kept as Longs so no precision is lost on the ~1.4e18 ns GPS epoch.
 * Weighted least squares over a rolling 60 s window, one MAD outlier-rejection pass, reset on
 * hardware clock discontinuity, and STALE when no sample arrived recently.
 */
class GnssClockModel {
    enum class State { ACQUIRING, LOCKED, DEGRADED, DISCONTINUITY, INVALID, STALE }

    data class Snapshot(
        val state: State,
        val samples: Int,
        val uncertaintyNanos: Long,
        val rate: Double,
        val monoRef: Long,
        val gpsRef: Long,
        val lastSampleMono: Long
    )

    private data class Sample(val mono: Long, val gps: Long, val uncertainty: Double)

    private val samples = ArrayDeque<Sample>()
    private var discontinuity = -1
    @Volatile var snapshot = Snapshot(State.ACQUIRING, 0, Long.MAX_VALUE, 1.0, 0L, 0L, 0L); private set

    fun add(clock: GnssClock) {
        if (!clock.hasFullBiasNanos() || !clock.hasElapsedRealtimeNanos()) { invalidate(); return }
        val bias = if (clock.hasBiasNanos()) clock.biasNanos else 0.0
        val gps = clock.timeNanos - clock.fullBiasNanos - bias.roundToLong()
        val u = (if (clock.hasTimeUncertaintyNanos()) clock.timeUncertaintyNanos else 50.0) +
            (if (clock.hasBiasUncertaintyNanos()) clock.biasUncertaintyNanos else 0.0) +
            (if (clock.hasElapsedRealtimeUncertaintyNanos()) clock.elapsedRealtimeUncertaintyNanos else 100_000.0)
        addSample(clock.elapsedRealtimeNanos, gps, u, clock.hardwareClockDiscontinuityCount)
    }

    @Synchronized fun invalidate() { snapshot = snapshot.copy(state = State.INVALID) }

    @Synchronized fun addSample(mono: Long, gps: Long, uncertaintyNanos: Double, discontinuityCount: Int) {
        if (discontinuity != -1 && discontinuity != discontinuityCount) {
            samples.clear(); snapshot = Snapshot(State.DISCONTINUITY, 0, Long.MAX_VALUE, 1.0, 0L, 0L, 0L)
        }
        discontinuity = discontinuityCount
        samples.addLast(Sample(mono, gps, uncertaintyNanos.coerceAtLeast(1.0)))
        while (samples.size > MAX_SAMPLES || mono - samples.first().mono > WINDOW_NANOS) samples.removeFirst()
        fit()
    }

    private fun fit() {
        val last = samples.last()
        if (samples.size < MIN_SAMPLES) { snapshot = Snapshot(State.ACQUIRING, samples.size, Long.MAX_VALUE, 1.0, 0L, 0L, last.mono); return }
        var used = samples.toList()
        var line = regress(used)
        // One robust pass: drop samples further than 5 MAD from the first fit, then refit.
        val residuals = used.map { abs(residual(it, line)) }.sorted()
        val mad = residuals[residuals.size / 2]
        val limit = maxOf(5.0 * 1.4826 * mad, 1_000.0)
        val kept = used.filter { abs(residual(it, line)) <= limit }
        if (kept.size >= MIN_SAMPLES && kept.size < used.size) { used = kept; line = regress(used) }
        var sq = 0.0
        for (s in used) { val e = residual(s, line); sq += e * e }
        val rms = sqrt(sq / used.size).roundToLong().coerceAtLeast(1L)
        val span = used.last().mono - used.first().mono
        val state = if (span >= 5_000_000_000L && rms <= 2_000_000L && line.rate in 0.9999..1.0001) State.LOCKED else State.DEGRADED
        snapshot = Snapshot(state, used.size, rms, line.rate, line.monoRef, line.gpsRef, last.mono)
    }

    private class Line(val monoRef: Long, val gpsRef: Long, val rate: Double)

    private fun residual(s: Sample, line: Line) = (s.gps - line.gpsRef) - line.rate * (s.mono - line.monoRef)

    private fun regress(points: List<Sample>): Line {
        val monoRef = points.first().mono; val gpsRef = points.first().gps
        var sw = 0.0; var sx = 0.0; var sy = 0.0
        for (s in points) { val w = 1.0 / (s.uncertainty * s.uncertainty); sw += w; sx += w * (s.mono - monoRef); sy += w * (s.gps - gpsRef) }
        val mx = sx / sw; val my = sy / sw
        var num = 0.0; var den = 0.0
        for (s in points) { val w = 1.0 / (s.uncertainty * s.uncertainty); val x = s.mono - monoRef - mx; num += w * x * (s.gps - gpsRef - my); den += w * x * x }
        val rate = if (den > 0) num / den else 1.0
        // Re-anchor the line at the weighted centroid.
        return Line(monoRef + mx.roundToLong(), gpsRef + my.roundToLong(), rate)
    }

    /** State as seen at [nowMono]: STALE when the last sample is older than [STALE_NANOS]. */
    fun stateAt(nowMono: Long): State {
        val s = snapshot
        return if ((s.state == State.LOCKED || s.state == State.DEGRADED) && nowMono - s.lastSampleMono > STALE_NANOS) State.STALE else s.state
    }

    /** GPS time and uncertainty for an elapsed-realtime instant, or null when the model is unusable. */
    fun toGps(monotonicNanos: Long): Pair<Long, Long>? {
        val s = snapshot
        if (s.state != State.LOCKED && s.state != State.DEGRADED) return null
        val age = abs(monotonicNanos - s.lastSampleMono)
        if (age > STALE_NANOS || s.uncertaintyNanos > MAX_USABLE_UNCERTAINTY) return null
        val gps = s.gpsRef + (s.rate * (monotonicNanos - s.monoRef)).roundToLong()
        // Extrapolation penalty: assume up to 1 ppm unmodelled drift since the last sample.
        return gps to s.uncertaintyNanos + age / 1_000_000L
    }

    companion object {
        const val MIN_SAMPLES = 6
        const val MAX_SAMPLES = 120
        const val WINDOW_NANOS = 60_000_000_000L
        const val STALE_NANOS = 5_000_000_000L
        const val MAX_USABLE_UNCERTAINTY = 20_000_000L
    }
}
