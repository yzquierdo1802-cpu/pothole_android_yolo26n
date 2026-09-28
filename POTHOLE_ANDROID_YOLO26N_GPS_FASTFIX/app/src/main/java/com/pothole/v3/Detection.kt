package com.pothole.v3

import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.RectF

enum class Severity(val label: String) {
    LOW("BAJA"),
    MEDIUM("MEDIA"),
    HIGH("ALTA")
}

enum class DetectionSource(val label: String, val distanceBand: String, val modelInput: Int) {
    PRIMARY_416("PRIMARIO 416", "GENERAL", 416),
    FAR_416("ROI 416", "5-20 m", 416),
    FAR_512("ROI 512", "10-20 m", 512)
}

data class Detection(
    val box: RectF,
    /** Score suavizado/mostrado. No se presenta como probabilidad calibrada. */
    val confidence: Float,
    /** Score crudo entregado por el modelo en el frame actual. */
    val rawConfidence: Float = confidence,
    val severity: Severity = Severity.LOW,
    /** Área segmentada observada, relativa al área útil del frame. */
    val maskAreaRatio: Float = 0f,
    /** Área visual compensada por perspectiva; se usa para severidad. */
    val severityScore: Float = maskAreaRatio,
    /** Factor de perspectiva aplicado a maskAreaRatio. */
    val perspectiveFactor: Float = 1f,
    val trackId: Int = -1,
    val predicted: Boolean = false,
    val trackConfirmed: Boolean = false,
    val source: DetectionSource = DetectionSource.PRIMARY_416,
    val distanceBand: String = "GENERAL",
    /** Fuente y banda donde este track fue observado por primera vez. */
    val firstSeenSource: DetectionSource = DetectionSource.PRIMARY_416,
    val firstSeenDistanceBand: String = "GENERAL",
    /** True cuando un track nacido en el detector lejano ya fue tomado por el 416 principal. */
    val handoffFromFar: Boolean = false,
    /** Contorno vectorial limpio en coordenadas del frame fuente. */
    val maskPolygon: List<PointF> = emptyList()
)

data class InferenceMetrics(
    val preprocessMs: Long,
    val modelMs: Long,
    val postprocessMs: Long,
    val totalMs: Long,
    val rawCandidates: Int = 0,
    val keptDetections: Int = 0
)

/**
 * Capa de máscara adicional proyectada sobre una región del frame original.
 * Se utiliza para la pasada LEJANA (ROI) del modo dual-range sin escalar la
 * máscara a un bitmap grande en CPU.
 */
data class MaskLayer(
    val bitmap: Bitmap,
    val cropLeft: Int,
    val cropTop: Int,
    val cropRight: Int,
    val cropBottom: Int,
    /** Rectángulo destino expresado en coordenadas del frame completo. */
    val frameRect: RectF
)

data class InferenceResult(
    val detections: List<Detection>,
    /** Máscara principal conservada en la resolución nativa de prototipos del modelo. */
    val maskBitmap: Bitmap?,
    val maskCropLeft: Int,
    val maskCropTop: Int,
    val maskCropRight: Int,
    val maskCropBottom: Int,
    val sourceWidth: Int,
    val sourceHeight: Int,
    val metrics: InferenceMetrics,
    /** Máscaras de pasadas adicionales, p. ej. ROI lejano. */
    val extraMaskLayers: List<MaskLayer> = emptyList()
)
