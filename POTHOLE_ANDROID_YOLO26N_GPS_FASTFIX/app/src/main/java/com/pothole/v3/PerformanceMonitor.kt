package com.pothole.v3

import java.util.ArrayDeque
import kotlin.math.ceil

class PerformanceMonitor(private val historySize: Int = 60) {
    data class Snapshot(
        val fps: Double,
        val averageTotalMs: Long,
        val p95TotalMs: Long
    )

    private val durations = ArrayDeque<Long>()
    private val completionTimes = ArrayDeque<Long>()

    fun reset() {
        durations.clear()
        completionTimes.clear()
    }

    fun record(totalMs: Long, nowNs: Long = System.nanoTime()): Snapshot {
        durations.addLast(totalMs)
        while (durations.size > historySize) durations.removeFirst()

        completionTimes.addLast(nowNs)
        val oneSecondAgo = nowNs - 1_000_000_000L
        while (completionTimes.isNotEmpty() && completionTimes.first() < oneSecondAgo) {
            completionTimes.removeFirst()
        }

        val sorted = durations.toList().sorted()
        val avg = if (sorted.isEmpty()) 0L else sorted.sum() / sorted.size
        val p95Index = if (sorted.isEmpty()) 0 else (ceil(sorted.size * 0.95).toInt() - 1).coerceIn(0, sorted.lastIndex)
        val p95 = if (sorted.isEmpty()) 0L else sorted[p95Index]
        return Snapshot(completionTimes.size.toDouble(), avg, p95)
    }
}
