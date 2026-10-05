package com.sprinttiming.app

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

data class TimelineMark(val timeNanos: Long, val uncertaintyNanos: Long, val confidencePercent: Int)
data class ThreePointResult(val splitNanos: Long, val totalNanos: Long, val splitUncertaintyNanos: Long, val totalUncertaintyNanos: Long, val confidencePercent: Int)

object ThreePointTiming {
    /** Sorts the three marks (earliest = start). Independent uncertainties combine as root-sum-square. */
    fun calculate(marks: Collection<TimelineMark>): ThreePointResult? {
        if (marks.size != 3) return null
        val ordered = marks.sortedBy { it.timeNanos }
        val split = ordered[1].timeNanos - ordered[0].timeNanos
        val total = ordered[2].timeNanos - ordered[0].timeNanos
        if (split <= 0 || total <= split) return null
        return ThreePointResult(
            splitNanos = split,
            totalNanos = total,
            splitUncertaintyNanos = rss(ordered[0].uncertaintyNanos, ordered[1].uncertaintyNanos),
            totalUncertaintyNanos = rss(ordered[0].uncertaintyNanos, ordered[2].uncertaintyNanos),
            confidencePercent = ordered.minOf { it.confidencePercent }
        )
    }

    data class Choice(val result: ThreePointResult, val gnss: Boolean, val disagreementNanos: Long?)

    /**
     * Picks the time basis with the smaller uncertainty. When both are available they cross-check
     * each other; a disagreement beyond 3σ of their combined uncertainty caps confidence at 50 %.
     */
    fun choose(gnss: List<TimelineMark>?, wifi: List<TimelineMark>?): Choice? {
        val g = gnss?.let(::calculate)
        val w = wifi?.let(::calculate)
        if (g == null && w == null) return null
        val useGnss = g != null && (w == null || worst(g) <= worst(w))
        val picked = if (useGnss) g!! else w!!
        if (g == null || w == null) return Choice(picked, useGnss, null)
        val disagreement = max(abs(g.splitNanos - w.splitNanos), abs(g.totalNanos - w.totalNanos))
        val allowed = 3 * rss(worst(g), worst(w))
        val result = if (disagreement > allowed) picked.copy(confidencePercent = minOf(picked.confidencePercent, 50)) else picked
        return Choice(result, useGnss, disagreement)
    }

    fun rss(a: Long, b: Long): Long = sqrt(a.toDouble() * a + b.toDouble() * b).toLong()

    private fun worst(r: ThreePointResult) = max(r.splitUncertaintyNanos, r.totalUncertaintyNanos)
}
