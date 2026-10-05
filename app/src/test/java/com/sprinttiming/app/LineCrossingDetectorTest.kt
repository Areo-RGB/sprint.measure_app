package com.sprinttiming.app

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LineCrossingDetectorTest {
    private val width = 90
    private val height = 60
    private val frameNanos = 16_666_667L

    private fun frameWithBar(left: Int): ByteArray {
        val frame = ByteArray(width * height)
        for (row in 0 until height) for (col in left until left + 10) if (col in 0 until width) frame[row * width + col] = 200.toByte()
        return frame
    }

    private fun run(positions: IntProgression): LineCrossingDetector.Event? {
        val detector = LineCrossingDetector()
        var timestamp = 1_000_000_000L
        for (left in positions) {
            detector.process(frameWithBar(left), width, height, timestamp)?.let { return it }
            timestamp += frameNanos
        }
        return null
    }

    @Test fun detectsLeftToRightCrossing() {
        val event = run(-10..100 step 5)
        assertNotNull(event)
        assertTrue(event!!.timestampNanos > 1_000_000_000L)
    }

    @Test fun detectsRightToLeftCrossing() {
        val event = run(100 downTo -10 step 5)
        assertNotNull(event)
        assertTrue(event!!.timestampNanos > 1_000_000_000L)
    }
}
