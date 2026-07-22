package com.sprinttiming.app

import kotlin.math.abs

class LineCrossingDetector {
    enum class Direction { LEFT_TO_RIGHT, RIGHT_TO_LEFT }
    enum class State { CLEAR, APPROACHING, ON_LINE, CROSSED_PENDING, CONFIRMED }
    data class Event(val timestampNanos: Long, val confidence: Float, val intervalNanos: Long)

    var direction = Direction.LEFT_TO_RIGHT
    @Volatile private var sensitivity = 50
    private var previous: ByteArray? = null
    private var state = State.CLEAR
    private var lastTimestamp = 0L
    private var candidateTimestamp = 0L
    private var candidateScore = 0f
    private var confirmingFrames = 0
    private var quietFrames = 0
    private var refractoryUntil = 0L

    fun reset() { previous = null; state = State.CLEAR; confirmingFrames = 0; quietFrames = 0; refractoryUntil = 0L }
    fun setSensitivity(value: Int) { sensitivity = value.coerceIn(0, 100); reset() }

    fun process(y: ByteArray, width: Int, height: Int, timestampNanos: Long): Event? {
        val old = previous
        previous = y.copyOf()
        if (old == null || old.size != y.size || timestampNanos <= refractoryUntil) { lastTimestamp = timestampNanos; return null }
        val zone = FloatArray(3)
        val counts = IntArray(3)
        val top = height / 6
        val bottom = height * 5 / 6
        val configuredSensitivity = sensitivity
        val pixelThreshold = 28 - configuredSensitivity * 18 / 100
        for (row in top until bottom step 2) for (col in 0 until width step 2) {
            val z = (col * 3 / width).coerceIn(0, 2)
            val d = abs((y[row * width + col].toInt() and 255) - (old[row * width + col].toInt() and 255))
            if (d > pixelThreshold) zone[z]++
            counts[z]++
        }
        for (i in 0..2) zone[i] /= counts[i].coerceAtLeast(1).toFloat()
        val first = if (direction == Direction.LEFT_TO_RIGHT) 0 else 2
        val last = 2 - first
        val active = 0.12f - configuredSensitivity * 0.0008f
        when (state) {
            State.CLEAR -> if (zone[first] > active && zone[first] > zone[last] * 1.15f) state = State.APPROACHING
            State.APPROACHING -> {
                if (zone[1] > active) state = State.ON_LINE
                else if (zone.max() < active / 2) state = State.CLEAR
            }
            State.ON_LINE -> if (zone[last] > active && zone[last] >= zone[first]) {
                val dt = (timestampNanos - lastTimestamp).coerceAtLeast(0)
                val slope = (zone[last] - zone[first]).coerceAtLeast(0.001f)
                val alpha = ((active - zone[first]) / slope).coerceIn(0f, 1f)
                candidateTimestamp = lastTimestamp + (dt * alpha).toLong()
                candidateScore = (zone[1] + zone[last]).coerceIn(0f, 1f)
                state = State.CROSSED_PENDING; confirmingFrames = 1
            }
            State.CROSSED_PENDING -> {
                if (zone[last] > active / 2) confirmingFrames++ else confirmingFrames = 0
                if (confirmingFrames >= 2) {
                    state = State.CONFIRMED; refractoryUntil = timestampNanos + 1_500_000_000L
                    lastTimestamp = timestampNanos
                    return Event(candidateTimestamp, (0.55f + candidateScore * 0.45f).coerceAtMost(0.99f), timestampNanos - candidateTimestamp)
                } else if (confirmingFrames == 0) state = State.CLEAR
            }
            State.CONFIRMED -> if (timestampNanos >= refractoryUntil) state = State.CLEAR
        }
        if (zone.max() < active / 3) quietFrames++ else quietFrames = 0
        if (quietFrames > 8 && state != State.CROSSED_PENDING) state = State.CLEAR
        lastTimestamp = timestampNanos
        return null
    }
}
