package com.sprinttiming.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.abs

class GnssClockModelTest {
    private val gpsEpoch = 1_400_000_000_000_000_000L

    private fun feed(model: GnssClockModel, seconds: Int, rate: Double = 1.0 + 3e-6, discontinuity: Int = 0, outlierEvery: Int = 0) {
        val random = Random(9)
        for (i in 0 until seconds) {
            val mono = 50_000_000_000L + i * 1_000_000_000L
            var gps = gpsEpoch + ((mono - 50_000_000_000L) * rate).toLong() + random.nextInt(200_000) - 100_000
            if (outlierEvery > 0 && i % outlierEvery == 5) gps += 40_000_000L
            model.addSample(mono, gps, 150_000.0, discontinuity)
        }
    }

    @Test fun locksAndMapsWithSubMillisecondError() {
        val model = GnssClockModel()
        feed(model, 30, outlierEvery = 10)
        assertEquals(GnssClockModel.State.LOCKED, model.snapshot.state)
        val mono = 50_000_000_000L + 29_500_000_000L
        val (gps, uncertainty) = model.toGps(mono)!!
        val truth = gpsEpoch + ((mono - 50_000_000_000L) * (1.0 + 3e-6)).toLong()
        assertTrue("error ${gps - truth}", abs(gps - truth) < 200_000L)
        assertTrue(uncertainty < 1_000_000L)
    }

    @Test fun resetsOnDiscontinuity() {
        val model = GnssClockModel()
        feed(model, 20)
        model.addSample(80_000_000_000L, gpsEpoch, 150_000.0, discontinuityCount = 1)
        assertEquals(GnssClockModel.State.ACQUIRING, model.snapshot.state)
        assertNull(model.toGps(80_000_000_000L))
    }

    @Test fun refusesToExtrapolateStaleModel() {
        val model = GnssClockModel()
        feed(model, 20)
        assertNull(model.toGps(50_000_000_000L + 19_000_000_000L + 6_000_000_000L))
        assertEquals(GnssClockModel.State.STALE, model.stateAt(50_000_000_000L + 30_000_000_000L))
    }
}
