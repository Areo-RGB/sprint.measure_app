package com.sprinttiming.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
        assertEquals(3_000_000L, result.splitUncertaintyNanos)
        assertEquals(4_000_000L, result.totalUncertaintyNanos)
        assertEquals(92, result.confidencePercent)
    }

    @Test fun rejectsMissingOrDuplicateTimestamps() {
        assertNull(ThreePointTiming.calculate(listOf(TimelineMark(1, 0, 100), TimelineMark(2, 0, 100))))
        assertNull(ThreePointTiming.calculate(listOf(TimelineMark(1, 0, 100), TimelineMark(1, 0, 100), TimelineMark(2, 0, 100))))
    }
}
