package com.sprinttiming.app

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Leading-edge line-crossing detector on an upright luma grid.
 *
 * Only a vertical strip around the timing line is analysed. Each pixel is compared with an adaptive
 * background (learned for a short period after arming), after removing the global brightness shift
 * and with a threshold scaled by the measured noise. The per-column foreground occupancy gives the
 * athlete's silhouette; its edge facing the line is tracked with sub-column precision. The crossing
 * time is interpolated between the two frames whose edge positions bracket the line, using each
 * observation's own rolling-shutter exposure time, so precision is not limited to the frame interval.
 * The crossing is confirmed only if the body is still across the line on the following frames.
 *
 * Not thread-safe: call every method from the analysis thread.
 */
class LineCrossingDetector {
    enum class State { LEARNING, CLEAR, APPROACHING, CONFIRMING, COOLDOWN }

    data class Event(
        val timestampNanos: Long,
        val uncertaintyNanos: Long,
        val confidence: Float,
        val leftToRight: Boolean,
        val frameIntervalNanos: Long
    )

    var state = State.LEARNING; private set
    private var sensitivity = 50
    private var lineFraction = 0.5f

    // Geometry (rebuilt when the frame size or line changes).
    private var width = 0
    private var height = 0
    private var x0 = 0
    private var stripW = 0
    private var y0 = 0
    private var rows = 0
    private var lineInStrip = 0f
    private var background = FloatArray(0)
    private var diff = IntArray(0)
    private var occupancy = FloatArray(0)
    private var profile = FloatArray(0)
    private val histogram = IntArray(511)
    private val absHistogram = IntArray(256)

    // Background learning.
    private var learnFrames = 0
    private var learnStart = 0L
    private var sigma = 2f

    // Blobs found in the current frame (start/end columns inclusive, strip coordinates).
    private val blobStart = IntArray(MAX_BLOBS)
    private val blobEnd = IntArray(MAX_BLOBS)
    private val blobMass = FloatArray(MAX_BLOBS)
    private var blobCount = 0

    // Tracking.
    private var side = 0 // -1: athlete left of the line moving right, +1: right of the line moving left
    private var prevEdge = 0f
    private var prevTime = 0L
    private var trackPoints = 0
    private var lastSpeed = 0f
    private var missFrames = 0
    private var candidate: Event? = null
    private var confirmFrames = 0
    private var cooldownUntil = 0L
    private var clearFrames = 0
    private var lastFrameTime = 0L
    private var nominalInterval = 0L

    fun reset() {
        state = State.LEARNING; learnFrames = 0; width = 0; candidate = null
        lastFrameTime = 0L; nominalInterval = 0L
    }

    fun setSensitivity(value: Int) { sensitivity = value.coerceIn(0, 100) }

    fun setLine(fraction: Float) {
        val clamped = fraction.coerceIn(0.05f, 0.95f)
        if (clamped != lineFraction) { lineFraction = clamped; reset() }
    }

    /**
     * @param baseTimeNanos frame time of sensor row 0 at mid-exposure (elapsed-realtime base)
     * @param skewNanos rolling-shutter skew (first to last sensor row)
     * @param readoutA,readoutB sensor readout fraction of an upright column: a + b * x / width
     */
    fun process(frame: ByteArray, frameWidth: Int, frameHeight: Int, baseTimeNanos: Long, skewNanos: Long = 0L, readoutA: Float = 0f, readoutB: Float = 0f): Event? {
        if (frameWidth != width || frameHeight != height) configure(frameWidth, frameHeight)
        val dt = if (lastFrameTime > 0L) baseTimeNanos - lastFrameTime else 0L
        if (dt <= 0L && lastFrameTime > 0L) return null // duplicate or reordered frame
        if (dt > 0L) nominalInterval = when {
            nominalInterval == 0L -> dt
            dt < nominalInterval * 3 / 2 -> (nominalInterval * 7 + dt) / 8
            else -> nominalInterval
        }
        lastFrameTime = baseTimeNanos

        if (state == State.LEARNING) { learn(frame, baseTimeNanos); return null }
        val foregroundFraction = segment(frame)
        // A sudden change of most of the strip is camera movement or an exposure jump, not an athlete.
        if (foregroundFraction > 0.65f && state == State.CLEAR) { state = State.LEARNING; learnFrames = 0; return null }
        findBlobs()

        fun timeAt(edgeInStrip: Float): Long {
            val xFraction = (x0 + edgeInStrip) / width
            return baseTimeNanos + (skewNanos * (readoutA + readoutB * xFraction)).toLong()
        }

        when (state) {
            State.CLEAR -> {
                // Start tracking a silhouette that is completely on one side of the line.
                var best = -1
                for (i in 0 until blobCount) if (!straddles(i) && (best < 0 || blobMass[i] > blobMass[best])) best = i
                if (best >= 0) {
                    side = if (blobEnd[best] + 1 <= lineInStrip) -1 else 1
                    prevEdge = leadingEdge(best)
                    prevTime = timeAt(prevEdge)
                    trackPoints = 1; missFrames = 0; lastSpeed = 0f
                    state = State.APPROACHING
                }
            }
            State.APPROACHING -> {
                val blob = trackedBlob()
                if (blob < 0) { if (++missFrames > 3) state = State.CLEAR; return null }
                missFrames = 0
                val edge = leadingEdge(blob)
                val time = timeAt(edge)
                val progress = (edge - prevEdge) * -side
                val crossed = if (side < 0) edge >= lineInStrip else edge <= lineInStrip
                val elapsed = time - prevTime
                if (elapsed <= 0L) return null
                val speed = progress / elapsed
                if (crossed) {
                    if (progress <= 0f) { state = State.CLEAR; return null }
                    val alpha = ((lineInStrip - prevEdge) / (edge - prevEdge)).coerceIn(0f, 1f)
                    val crossing = prevTime + (elapsed * alpha).toLong()
                    val consistent = trackPoints >= 2 && lastSpeed > 0f && abs(speed - lastSpeed) <= 0.5f * lastSpeed
                    val gap = nominalInterval > 0L && elapsed > nominalInterval * 3 / 2
                    var interpolation = elapsed * if (consistent) 0.15 else 0.35
                    if (gap) interpolation = max(interpolation, elapsed / 2.0)
                    // The silhouette edge is only defined to about one analysis column.
                    val edgeAmbiguity = EDGE_AMBIGUITY_PX / speed.toDouble()
                    val uncertainty = sqrt(interpolation * interpolation + edgeAmbiguity * edgeAmbiguity).toLong()
                    var confidence = 0.55f
                    if (trackPoints >= 2) confidence += 0.15f
                    if (consistent) confidence += 0.15f
                    if (!gap) confidence += 0.1f
                    confidence += min(0.04f, blobMass[blob] / stripW)
                    candidate = Event(crossing, uncertainty, confidence.coerceIn(0.3f, 0.99f), side < 0, elapsed)
                    confirmFrames = 0
                    state = State.CONFIRMING
                } else {
                    if (progress > 0f) lastSpeed = speed
                    prevEdge = edge; prevTime = time; trackPoints++
                }
            }
            State.CONFIRMING -> {
                // The body must stay across the line, otherwise it was a flicker or the athlete turned back.
                var across = false
                for (i in 0 until blobCount) if (if (side < 0) blobEnd[i] + 1 > lineInStrip else blobStart[i] < lineInStrip) across = true
                if (!across) { state = State.CLEAR; candidate = null; return null }
                if (++confirmFrames >= CONFIRM_FRAMES) {
                    val event = candidate
                    candidate = null
                    state = State.COOLDOWN; clearFrames = 0
                    cooldownUntil = baseTimeNanos + COOLDOWN_NANOS
                    return event
                }
            }
            State.COOLDOWN -> {
                var onLine = false
                for (i in 0 until blobCount) if (straddles(i)) onLine = true
                clearFrames = if (onLine) 0 else clearFrames + 1
                if (clearFrames >= 3 && baseTimeNanos >= cooldownUntil) state = State.CLEAR
            }
            State.LEARNING -> {}
        }
        return null
    }

    private fun configure(frameWidth: Int, frameHeight: Int) {
        width = frameWidth; height = frameHeight
        val line = lineFraction * frameWidth
        val half = max(4, (frameWidth * STRIP_HALF_WIDTH).roundToInt())
        x0 = max(0, (line - half).toInt())
        val x1 = min(frameWidth, (line + half).toInt())
        stripW = max(1, x1 - x0)
        y0 = (frameHeight * ROI_TOP).toInt()
        rows = max(1, (frameHeight * ROI_BOTTOM).toInt() - y0)
        lineInStrip = line - x0
        background = FloatArray(stripW * rows)
        diff = IntArray(stripW * rows)
        occupancy = FloatArray(stripW)
        profile = FloatArray(stripW)
        state = State.LEARNING; learnFrames = 0
    }

    private fun learn(frame: ByteArray, time: Long) {
        var i = 0
        histogram.fill(0)
        for (y in y0 until y0 + rows) {
            val base = y * width + x0
            for (x in 0 until stripW) {
                val v = (frame[base + x].toInt() and 255).toFloat()
                if (learnFrames == 0) background[i] = v
                else {
                    histogram[(v - background[i]).roundToInt().coerceIn(-255, 255) + 255]++
                    background[i] += 0.3f * (v - background[i])
                }
                i++
            }
        }
        if (learnFrames == 0) learnStart = time else sigma = robustSigma(median(histogram) - 255)
        learnFrames++
        if (learnFrames >= LEARN_MIN_FRAMES && time - learnStart >= LEARN_NANOS) state = State.CLEAR
    }

    /** Fills [profile] with smoothed per-column foreground occupancy; returns the strip's foreground fraction. */
    private fun segment(frame: ByteArray): Float {
        histogram.fill(0)
        var i = 0
        for (y in y0 until y0 + rows) {
            val base = y * width + x0
            for (x in 0 until stripW) {
                val d = (frame[base + x].toInt() and 255) - background[i].roundToInt()
                diff[i] = d
                histogram[d.coerceIn(-255, 255) + 255]++
                i++
            }
        }
        val shift = median(histogram) - 255
        sigma = 0.9f * sigma + 0.1f * robustSigma(shift)
        val base = 30f - 0.22f * sensitivity
        val threshold = max(base, 4f * sigma).coerceAtMost(60f)
        occupancy.fill(0f)
        var foreground = 0
        i = 0
        for (y in y0 until y0 + rows) {
            val rowBase = y * width + x0
            for (x in 0 until stripW) {
                val v = (frame[rowBase + x].toInt() and 255).toFloat()
                if (abs(diff[i] - shift) > threshold) {
                    occupancy[x] += 1f; foreground++
                    background[i] += BG_ALPHA_FOREGROUND * (v - background[i])
                } else background[i] += BG_ALPHA * (v - background[i])
                i++
            }
        }
        for (x in 0 until stripW) occupancy[x] /= rows
        for (x in 0 until stripW) {
            val l = occupancy[max(0, x - 1)]; val r = occupancy[min(stripW - 1, x + 1)]
            profile[x] = (l + 2f * occupancy[x] + r) / 4f
        }
        return foreground.toFloat() / (stripW * rows)
    }

    private fun columnThreshold() = 0.30f - 0.0025f * sensitivity

    private fun findBlobs() {
        blobCount = 0
        val threshold = columnThreshold()
        val gap = max(1, stripW / 60)
        var start = -1; var end = -1; var mass = 0f
        fun flush() {
            if (start >= 0 && end - start + 1 >= 2 && mass >= threshold * 3f && blobCount < MAX_BLOBS) {
                blobStart[blobCount] = start; blobEnd[blobCount] = end; blobMass[blobCount] = mass; blobCount++
            }
            start = -1; mass = 0f
        }
        for (x in 0 until stripW) {
            if (profile[x] >= threshold) {
                if (start >= 0 && x - end - 1 > gap) flush()
                if (start < 0) start = x
                end = x; mass += profile[x]
            }
        }
        flush()
    }

    private fun straddles(i: Int) = blobStart[i] < lineInStrip && blobEnd[i] + 1 > lineInStrip

    /** Sub-column position of the blob edge facing the line (threshold crossing between column centres). */
    private fun leadingEdge(i: Int): Float {
        val threshold = columnThreshold()
        return if (side < 0) {
            val e = blobEnd[i]
            if (e + 1 >= stripW) e + 1f else {
                val inside = profile[e]; val outside = profile[e + 1]
                e + 0.5f + ((inside - threshold) / (inside - outside).coerceAtLeast(1e-3f)).coerceIn(0f, 1f)
            }
        } else {
            val s = blobStart[i]
            if (s == 0) 0f else {
                val inside = profile[s]; val outside = profile[s - 1]
                s + 0.5f - ((inside - threshold) / (inside - outside).coerceAtLeast(1e-3f)).coerceIn(0f, 1f)
            }
        }
    }

    /** The blob continuing the current track: heaviest one whose leading edge is near the previous edge. */
    private fun trackedBlob(): Int {
        val maxJump = stripW * 0.6f
        var best = -1
        for (i in 0 until blobCount) {
            val edge = leadingEdge(i)
            val move = (edge - prevEdge) * -side
            if (move < -stripW * 0.15f || move > maxJump) continue
            if (best < 0 || blobMass[i] > blobMass[best]) best = i
        }
        return best
    }

    private fun median(hist: IntArray): Int {
        var total = 0
        for (c in hist) total += c
        var seen = 0
        for (b in hist.indices) { seen += hist[b]; if (seen * 2 >= total) return b }
        return hist.size / 2
    }

    /** 1.4826 × median absolute deviation of the last difference histogram around [shift]. */
    private fun robustSigma(shift: Int): Float {
        absHistogram.fill(0)
        for (b in histogram.indices) if (histogram[b] > 0) absHistogram[abs(b - 255 - shift).coerceAtMost(255)] += histogram[b]
        return 1.4826f * median(absHistogram)
    }

    private companion object {
        const val STRIP_HALF_WIDTH = 0.2f
        const val EDGE_AMBIGUITY_PX = 1.0
        const val ROI_TOP = 0.08f
        const val ROI_BOTTOM = 0.92f
        const val MAX_BLOBS = 16
        const val LEARN_MIN_FRAMES = 8
        const val LEARN_NANOS = 300_000_000L
        const val CONFIRM_FRAMES = 2
        const val COOLDOWN_NANOS = 1_000_000_000L
        const val BG_ALPHA = 0.05f
        const val BG_ALPHA_FOREGROUND = 0.003f
    }
}
