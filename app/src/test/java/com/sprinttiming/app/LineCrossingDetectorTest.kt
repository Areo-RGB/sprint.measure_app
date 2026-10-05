package com.sprinttiming.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.abs
import kotlin.math.roundToLong

class LineCrossingDetectorTest {
    private val width = 240
    private val height = 320
    private val line = width / 2f

    /**
     * Synthetic athlete: a 50 px wide body on a textured background, covering 70% of the rows,
     * moving at [pxPerSecond]. The leading edge is soft (3 px ramp) with a ±0.5 px per-row jitter so
     * column occupancy changes gradually, as with a real silhouette.
     */
    private fun render(leadingEdge: Double, leftToRight: Boolean, noise: Random?, brightness: Int = 0): ByteArray {
        val frame = ByteArray(width * height)
        for (y in 0 until height) {
            val jag = (y * 0.618034) % 1.0 - 0.5
            for (x in 0 until width) {
                var v = 60 + ((x * 7 + y * 13) % 23) + brightness
                if (y in (height * 15 / 100) until (height * 85 / 100)) {
                    val front = leadingEdge + jag
                    val depth = if (leftToRight) front - x else x - front // distance behind the leading edge
                    val cover = when { depth >= 3.0 -> 1.0; depth <= 0.0 -> 0.0; else -> depth / 3.0 }
                    if (depth < 50.0) v = (v * (1 - cover) + 190 * cover).toInt()
                }
                if (noise != null) v += noise.nextInt(7) - 3
                frame[y * width + x] = v.coerceIn(0, 255).toByte()
            }
        }
        return frame
    }

    private data class Run(val event: LineCrossingDetector.Event?, val truthNanos: Long)

    private fun sprint(fps: Int, pxPerSecond: Double, leftToRight: Boolean, phase: Double = 0.37, skewNanos: Long = 0L, readout: Pair<Float, Float> = 0f to 0f, noise: Boolean = true): Run {
        val detector = LineCrossingDetector()
        val random = if (noise) Random(42) else null
        val interval = 1e9 / fps
        val start = 5_000_000_000L
        // Empty background for 0.4 s while learning; leading edge reaches the line at 0.9 s (+ sub-frame phase).
        val truthSeconds = 0.9 + phase / fps
        val hidden = (fps * 0.4).toInt()
        var event: LineCrossingDetector.Event? = null
        for (i in 0 until (fps * 1.5).toInt()) {
            val t = i / fps.toDouble()
            // Position of the edge at the time this frame's line column is exposed.
            val lineReadout = skewNanos * (readout.first + readout.second * 0.5) / 1e9
            val travelled = (t + lineReadout - truthSeconds) * pxPerSecond
            val edge = if (leftToRight) line + travelled else line - travelled
            val frame = render(if (i < hidden) (if (leftToRight) -1000.0 else 2000.0) else edge, leftToRight, random)
            val base = start + (i * interval).roundToLong()
            detector.process(frame, width, height, base, skewNanos, readout.first, readout.second)?.let { if (event == null) event = it }
        }
        return Run(event, start + (truthSeconds * 1e9).roundToLong())
    }

    private fun assertAccurate(run: Run, toleranceNanos: Long) {
        assertNotNull("no crossing detected", run.event)
        val error = run.event!!.timestampNanos - run.truthNanos
        println("crossing error ${"%.3f".format(error / 1e6)} ms, reported ±${"%.2f".format(run.event.uncertaintyNanos / 1e6)} ms")
        assertTrue("error ${error / 1e6} ms exceeds ${toleranceNanos / 1e6} ms", abs(error) <= toleranceNanos)
    }

    @Test fun interpolatesBetweenFramesAt60Fps() {
        // 16.7 ms frames; athlete edge moves 480 px/s (~8 px per frame).
        for (phase in listOf(0.1, 0.37, 0.5, 0.83)) assertAccurate(sprint(60, 480.0, true, phase), 3_000_000L)
    }

    @Test fun detectsRightToLeftCrossing() {
        val run = sprint(60, 480.0, false)
        assertAccurate(run, 3_000_000L)
        assertTrue(!run.event!!.leftToRight)
    }

    @Test fun interpolatesAt30FpsWithFastAthlete() = assertAccurate(sprint(30, 900.0, true), 3_000_000L)

    @Test fun highSpeedModeTightensTiming() = assertAccurate(sprint(240, 480.0, true), 1_500_000L)

    @Test fun compensatesRollingShutterReadout() {
        // Line at the middle of the readout: the line column is exposed 10 ms after row 0.
        assertAccurate(sprint(60, 480.0, true, skewNanos = 20_000_000L, readout = 0f to 1f), 3_000_000L)
    }

    @Test fun reportsUncertaintyBelowFrameInterval() {
        val event = sprint(60, 480.0, true).event!!
        assertTrue(event.uncertaintyNanos < 16_700_000L / 2)
        assertTrue(event.confidence > 0.7f)
    }

    @Test fun ignoresSensorNoiseAndBrightnessSteps() {
        val detector = LineCrossingDetector()
        val random = Random(7)
        var time = 1_000_000_000L
        for (i in 0 until 200) {
            val brightness = if (i > 100) 25 else 0 // cloud / exposure step
            assertNull(detector.process(render(-1000.0, true, random, brightness), width, height, time))
            time += 16_666_667L
        }
    }

    @Test fun rejectsAthleteThatStopsBeforeTheLine() {
        val detector = LineCrossingDetector()
        val random = Random(3)
        var time = 1_000_000_000L
        for (i in 0 until 120) {
            val edge = if (i < 20) -1000.0 else minOf(line - 6.0, (i - 20) * 8.0)
            assertNull(detector.process(render(edge, true, random), width, height, time))
            time += 16_666_667L
        }
    }

    @Test fun triggersOncePerPassage() {
        val detector = LineCrossingDetector()
        var events = 0
        var time = 1_000_000_000L
        for (i in 0 until 120) {
            val edge = if (i < 20) -1000.0 else (i - 20) * 8.0
            if (detector.process(render(edge, true, Random(i.toLong())), width, height, time) != null) events++
            time += 16_666_667L
        }
        assertEquals(1, events)
    }
}
