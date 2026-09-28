package com.pothole.v3

/**
 * Estimador de severidad VISUAL compensada por perspectiva.
 *
 * No estima profundidad física ni severidad estructural del pavimento. Su objetivo es
 * evitar que un mismo bache cambie de BAJA->ALTA solamente porque se acerca a la cámara.
 * La compensación está acotada deliberadamente para no amplificar ruido lejano.
 */
object SeverityEstimator {
    @Volatile private var calibratedHorizon = 0.32f
    @Volatile private var mountingHeightScale = 1.0f

    fun configure(calibration: RoadCalibration) {
        calibratedHorizon = calibration.horizonFraction.coerceIn(0.15f, 0.60f)
        mountingHeightScale = (calibration.phoneHeightCm / 120f).coerceIn(0.65f, 1.55f)
    }

    const val MEDIUM_ENTER = 0.005f   // 0.50% del área útil compensada
    const val HIGH_ENTER = 0.015f     // 1.50%
    private const val MEDIUM_EXIT = 0.0042f
    private const val HIGH_EXIT = 0.0125f

    data class Measurement(
        val rawAreaRatio: Float,
        val perspectiveFactor: Float,
        val compensatedAreaRatio: Float,
        val severity: Severity
    )

    /**
     * bottomNorm: borde inferior del box en coordenadas normalizadas del área útil [0,1].
     * Cerca de la parte inferior de la imagen un objeto parece mayor por perspectiva.
     * factor ~0.88 en zona lejana y ~1.55 cerca del vehículo.
     */
    fun measure(areaRatio: Float, bottomNorm: Float): Measurement {
        val y = bottomNorm.coerceIn(0f, 1f)
        val horizon = calibratedHorizon
        val t = ((y - horizon) / (1f - horizon).coerceAtLeast(0.20f)).coerceIn(0f, 1f)
        val smooth = t * t * (3f - 2f * t) // smoothstep
        // Ajuste heurístico por montaje. Sigue siendo severidad visual, no una medición física.
        val factor = 0.88f + (0.67f * mountingHeightScale * smooth).coerceAtMost(0.95f)
        val corrected = (areaRatio.coerceAtLeast(0f) / factor).coerceIn(0f, 1f)
        return Measurement(
            rawAreaRatio = areaRatio.coerceIn(0f, 1f),
            perspectiveFactor = factor,
            compensatedAreaRatio = corrected,
            severity = raw(corrected)
        )
    }

    fun raw(compensatedAreaRatio: Float): Severity = when {
        compensatedAreaRatio >= HIGH_ENTER -> Severity.HIGH
        compensatedAreaRatio >= MEDIUM_ENTER -> Severity.MEDIUM
        else -> Severity.LOW
    }

    /** Histeresis para evitar oscilaciones alrededor de los umbrales. */
    fun stabilized(previous: Severity, compensatedAreaRatio: Float): Severity = when (previous) {
        Severity.HIGH -> when {
            compensatedAreaRatio >= HIGH_EXIT -> Severity.HIGH
            compensatedAreaRatio >= MEDIUM_ENTER -> Severity.MEDIUM
            else -> Severity.LOW
        }
        Severity.MEDIUM -> when {
            compensatedAreaRatio >= HIGH_ENTER -> Severity.HIGH
            compensatedAreaRatio >= MEDIUM_EXIT -> Severity.MEDIUM
            else -> Severity.LOW
        }
        Severity.LOW -> raw(compensatedAreaRatio)
    }
}
