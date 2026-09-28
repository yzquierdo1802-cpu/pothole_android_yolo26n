package com.pothole.v3

import android.content.Context

/** Preferencias de producción. Los valores por defecto priorizan detección inmediata. */
data class RoadCalibration(
    val horizonFraction: Float = 0.32f,
    val roadCenterFraction: Float = 0.50f,
    val phoneHeightCm: Int = 120
)

enum class PerformanceMode(val label: String) {
    AUTO("AUTO"), FAST("RÁPIDO"), BALANCED("EQUILIBRADO"), PRECISE("PRECISIÓN")
}

class ProfessionalConfig(private val context: Context) {
    companion object {
        private const val PREFS = "professional_v12"
        private const val KEY_HORIZON = "horizon"
        private const val KEY_CENTER = "road_center"
        private const val KEY_HEIGHT = "phone_height_cm"
        private const val KEY_MODE = "performance_mode"
        private const val KEY_LOCATION = "location_enabled"
        private const val KEY_DIAGNOSTICS = "diagnostics_enabled"
    }

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun calibration(): RoadCalibration = RoadCalibration(
        horizonFraction = prefs.getFloat(KEY_HORIZON, 0.32f).coerceIn(0.15f, 0.60f),
        roadCenterFraction = prefs.getFloat(KEY_CENTER, 0.50f).coerceIn(0.30f, 0.70f),
        phoneHeightCm = prefs.getInt(KEY_HEIGHT, 120).coerceIn(60, 220)
    )

    fun saveCalibration(value: RoadCalibration) {
        prefs.edit()
            .putFloat(KEY_HORIZON, value.horizonFraction.coerceIn(0.15f, 0.60f))
            .putFloat(KEY_CENTER, value.roadCenterFraction.coerceIn(0.30f, 0.70f))
            .putInt(KEY_HEIGHT, value.phoneHeightCm.coerceIn(60, 220))
            .apply()
    }

    fun performanceMode(): PerformanceMode = runCatching {
        PerformanceMode.valueOf(prefs.getString(KEY_MODE, PerformanceMode.AUTO.name) ?: PerformanceMode.AUTO.name)
    }.getOrDefault(PerformanceMode.AUTO)

    fun savePerformanceMode(mode: PerformanceMode) = prefs.edit().putString(KEY_MODE, mode.name).apply()
    fun locationEnabled(): Boolean = prefs.getBoolean(KEY_LOCATION, false)
    fun setLocationEnabled(enabled: Boolean) = prefs.edit().putBoolean(KEY_LOCATION, enabled).apply()
    fun diagnosticsEnabled(): Boolean = prefs.getBoolean(KEY_DIAGNOSTICS, false)
    fun setDiagnosticsEnabled(enabled: Boolean) = prefs.edit().putBoolean(KEY_DIAGNOSTICS, enabled).apply()
}
