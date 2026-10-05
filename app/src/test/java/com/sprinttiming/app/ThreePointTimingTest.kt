package com.sprinttiming.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThreePointTimingTest {
    @Test fun sortsArrivalOrderIntoStartSplitAndFinish() {
        val result = ThreePointTiming.calculate(listOf(
            TimelineMark(30_000_000_000L, 3_000_000L, 92),
            TimelineMark(10_000_000_000L, 1_000_000L, 98),
            TimelineMark(18_500_000_000L, 2_000_000L, 95)
        ))!!

        assertEquals(8_500_000_000L, result.splitNanos)
        assertEquals(20_000_000_000L, result.totalNanos)
        assertEquals(2_236_067L, result.splitUncertaintyNanos)
        assertEquals(3_162_277L, result.totalUncertaintyNanos)
        assertEquals(92, result.confidencePercent)
    }

    @Test fun rejectsMissingOrDuplicateTimestamps() {
        assertNull(ThreePointTiming.calculate(listOf(TimelineMark(1, 0, 100), TimelineMark(2, 0, 100))))
        assertNull(ThreePointTiming.calculate(listOf(TimelineMark(1, 0, 100), TimelineMark(1, 0, 100), TimelineMark(2, 0, 100))))
    }

    private fun marks(offset: Long, uncertainty: Long, splitShift: Long = 0L) = listOf(
        TimelineMark(offset, uncertainty, 90),
        TimelineMark(offset + 2_000_000_000L + splitShift, uncertainty, 90),
        TimelineMark(offset + 4_000_000_000L, uncertainty, 90)
    )

    @Test fun choosesTheMorePreciseBasis() {
        val gnssBetter = ThreePointTiming.choose(marks(1_000_000_000_000L, 300_000L), marks(5_000L, 2_000_000L))!!
        assertTrue(gnssBetter.gnss)
        val wifiBetter = ThreePointTiming.choose(marks(1_000_000_000_000L, 8_000_000L), marks(5_000L, 1_000_000L))!!
        assertFalse(wifiBetter.gnss)
        assertEquals(90, wifiBetter.result.confidencePercent)
    }

    @Test fun flagsGnssWifiDisagreement() {
        val choice = ThreePointTiming.choose(marks(1_000_000_000_000L, 300_000L), marks(5_000L, 500_000L, splitShift = 20_000_000L))!!
        assertEquals(20_000_000L, choice.disagreementNanos)
        assertEquals(50, choice.result.confidencePercent)
    }

    @Test fun fallsBackToWifiWithoutGnss() {
        val choice = ThreePointTiming.choose(null, marks(5_000L, 1_000_000L))!!
        assertFalse(choice.gnss)
        assertNull(choice.disagreementNanos)
    }
}
