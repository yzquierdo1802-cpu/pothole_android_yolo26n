package com.pothole.v3

import android.app.ActivityManager
import android.content.Context
import java.util.ArrayDeque

/**
 * Ajusta la pasada de largo alcance sin retrasar la inferencia principal 416.
 * La pasada secundaria siempre vive en otro executor y nunca bloquea la cola principal.
 */
class AdaptivePerformanceController(context: Context, private val config: ProfessionalConfig) {
    data class Policy(
        val secondaryIntervalFrames: Int,
        val prefer512: Boolean,
        val label: String
    )

    private val modelMs = ArrayDeque<Long>(12)
    private val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
    private val memoryClassMb = (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).memoryClass

    @Synchronized
    fun observePrimary(modelTimeMs: Long) {
        if (modelMs.size == 12) modelMs.removeFirst()
        modelMs.addLast(modelTimeMs.coerceAtLeast(0L))
    }

    @Synchronized
    fun averagePrimaryMs(): Long = if (modelMs.isEmpty()) 0L else modelMs.sum() / modelMs.size

    @Synchronized
    fun policy(thermalAllowsSecondary: Boolean): Policy {
        if (!thermalAllowsSecondary) return Policy(Int.MAX_VALUE, false, "TÉRMICO")
        val avg = averagePrimaryMs()
        val capable512 = cores >= 6 && memoryClassMb >= 256
        return when (config.performanceMode()) {
            PerformanceMode.FAST -> Policy(4, false, "RÁPIDO")
            PerformanceMode.BALANCED -> Policy(2, capable512, "EQUILIBRADO")
            PerformanceMode.PRECISE -> Policy(1, capable512, "PRECISIÓN")
            PerformanceMode.AUTO -> when {
                avg == 0L -> Policy(2, capable512, "AUTO")
                avg <= 190L -> Policy(1, capable512, "AUTO-RÁPIDO")
                avg <= 240L -> Policy(2, capable512, "AUTO-EQUILIBRADO")
                avg <= 300L -> Policy(4, false, "AUTO-SEGURO")
                else -> Policy(Int.MAX_VALUE, false, "AUTO-PRIMARIO")
            }
        }
    }

    fun canUse512ByHardware(): Boolean = cores >= 6 && memoryClassMb >= 256

    fun deviceSummary(): String = "CPU ${cores}c • RAMclass ${memoryClassMb}MB"
}
