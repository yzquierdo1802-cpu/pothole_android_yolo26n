package com.pothole.v3

import android.content.Context
import android.os.Build
import android.os.PowerManager

class ThermalController(context: Context) {
    private val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private var lastCheckNs = 0L
    private var cachedStatus = PowerManager.THERMAL_STATUS_NONE

    fun minFrameIntervalNs(nowNs: Long = System.nanoTime()): Long {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && nowNs - lastCheckNs > 1_000_000_000L) {
            cachedStatus = power.currentThermalStatus
            lastCheckNs = nowNs
        }
        return when {
            Build.VERSION.SDK_INT < Build.VERSION_CODES.Q -> 0L
            cachedStatus >= PowerManager.THERMAL_STATUS_SEVERE -> 140_000_000L // ~7 FPS cap
            cachedStatus >= PowerManager.THERMAL_STATUS_MODERATE -> 85_000_000L // ~12 FPS cap
            else -> 0L
        }
    }

    fun allowSecondaryInference(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || cachedStatus < PowerManager.THERMAL_STATUS_SEVERE
    }

    fun label(): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return "Térmico: n/d"
        return when (cachedStatus) {
            PowerManager.THERMAL_STATUS_NONE -> "Térmico: normal"
            PowerManager.THERMAL_STATUS_LIGHT -> "Térmico: ligero"
            PowerManager.THERMAL_STATUS_MODERATE -> "Térmico: moderado"
            PowerManager.THERMAL_STATUS_SEVERE -> "Térmico: alto"
            PowerManager.THERMAL_STATUS_CRITICAL, PowerManager.THERMAL_STATUS_EMERGENCY, PowerManager.THERMAL_STATUS_SHUTDOWN -> "Térmico: crítico"
            else -> "Térmico: $cachedStatus"
        }
    }
}
