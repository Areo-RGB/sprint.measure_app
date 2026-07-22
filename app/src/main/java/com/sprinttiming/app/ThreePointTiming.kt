package com.sprinttiming.app

data class TimelineMark(val timeNanos: Long, val uncertaintyNanos: Long, val confidencePercent: Int)
data class ThreePointResult(val splitNanos: Long, val totalNanos: Long, val splitUncertaintyNanos: Long, val totalUncertaintyNanos: Long, val confidencePercent: Int)

object ThreePointTiming {
    fun calculate(marks: Collection<TimelineMark>): ThreePointResult? {
        if (marks.size != 3) return null
        val ordered = marks.sortedBy { it.timeNanos }
        val split = ordered[1].timeNanos - ordered[0].timeNanos
        val total = ordered[2].timeNanos - ordered[0].timeNanos
        if (split <= 0 || total <= split) return null
        return ThreePointResult(
            splitNanos = split,
            totalNanos = total,
            splitUncertaintyNanos = ordered[0].uncertaintyNanos + ordered[1].uncertaintyNanos,
            totalUncertaintyNanos = ordered[0].uncertaintyNanos + ordered[2].uncertaintyNanos,
            confidencePercent = ordered.minOf { it.confidencePercent }
        )
    }
}
