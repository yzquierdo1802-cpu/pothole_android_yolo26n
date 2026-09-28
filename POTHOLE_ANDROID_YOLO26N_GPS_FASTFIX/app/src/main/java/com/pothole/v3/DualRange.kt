package com.pothole.v3

import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.RectF
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** Distancias objetivo operativas; no son una medición métrica garantizada. */
enum class RangeBand(val label: String, val distanceBand: String) {
    FAR("MEDIO", "5-10 m"),
    LONG("LARGO", "10-15 m"),
    ULTRA("EXTENDIDO", "15-20 m")
}

/**
 * Recorta la carretera utilizando una calibración simple de horizonte y centro.
 * Esto hace que el ROI de largo alcance siga siendo útil en teléfonos montados a
 * distinta altura/inclinación sin depender de coordenadas rígidas.
 */
class RoadRangeRoiExtractor {
    data class Result(
        val frame: RgbaFrame,
        val rect: Rect,
        val band: RangeBand,
        val zoomGain: Float
    )

    private var pixels = IntArray(0)
    @Volatile private var calibration = RoadCalibration()

    fun setCalibration(value: RoadCalibration) { calibration = value }

    fun extract(source: RgbaFrame, band: RangeBand, horizontalShiftFraction: Float = 0f): Result? {
        val w = source.width
        val h = source.height
        if (w < 320 || h < 240) return null

        val landscape = w >= h
        val base = fractions(landscape, band)
        val cal = calibration
        // Los valores históricos fueron afinados alrededor de horizonte ~0.32 y centro 0.50.
        val verticalShift = (cal.horizonFraction - 0.32f).coerceIn(-0.14f, 0.18f)
        val centerShift = (cal.roadCenterFraction - 0.50f).coerceIn(-0.20f, 0.20f)
        val totalHorizontalShift = centerShift + if (band == RangeBand.ULTRA) horizontalShiftFraction else 0f

        val leftF = (base.left + totalHorizontalShift).coerceIn(0f, 0.96f)
        val rightF = (base.right + totalHorizontalShift).coerceIn(0.04f, 1f)
        val topF = (base.top + verticalShift).coerceIn(0f, 0.92f)
        val bottomF = (base.bottom + verticalShift).coerceIn(0.08f, 1f)

        var left = (w * leftF).toInt().coerceIn(0, w - 2)
        var right = (w * rightF).toInt().coerceIn(left + 2, w)
        val desiredW = ((base.right - base.left) * w).toInt().coerceAtLeast(2)
        if (right - left < desiredW) {
            if (left == 0) right = desiredW.coerceAtMost(w)
            else if (right == w) left = (w - desiredW).coerceAtLeast(0)
        }
        var top = (h * topF).toInt().coerceIn(0, h - 2)
        var bottom = (h * bottomF).toInt().coerceIn(top + 2, h)

        val minW = min(220, w)
        val minH = min(150, h)
        if (right - left < minW) {
            val cx = (w * cal.roadCenterFraction).toInt().coerceIn(0, w - 1)
            left = (cx - minW / 2).coerceAtLeast(0)
            right = (left + minW).coerceAtMost(w)
        }
        if (bottom - top < minH) {
            val cy = ((top + bottom) / 2).coerceIn(0, h - 1)
            top = (cy - minH / 2).coerceAtLeast(0)
            bottom = (top + minH).coerceAtMost(h)
        }

        val rw = right - left
        val rh = bottom - top
        val need = rw * rh
        if (pixels.size != need) pixels = IntArray(need)
        var out = 0
        for (y in top until bottom) {
            val start = y * w + left
            System.arraycopy(source.pixels, start, pixels, out, rw)
            out += rw
        }

        val widthGain = w.toFloat() / rw.toFloat().coerceAtLeast(1f)
        val heightGain = h.toFloat() / rh.toFloat().coerceAtLeast(1f)
        return Result(
            frame = RgbaFrame(pixels, rw, rh),
            rect = Rect(left, top, right, bottom),
            band = band,
            zoomGain = min(widthGain, heightGain).coerceAtLeast(1f)
        )
    }

    private data class Fractions(val left: Float, val right: Float, val top: Float, val bottom: Float)

    private fun fractions(landscape: Boolean, band: RangeBand): Fractions = if (landscape) {
        when (band) {
            RangeBand.FAR -> Fractions(0.14f, 0.86f, 0.23f, 0.71f)
            RangeBand.LONG -> Fractions(0.25f, 0.75f, 0.16f, 0.55f)
            RangeBand.ULTRA -> Fractions(0.29f, 0.71f, 0.11f, 0.44f)
        }
    } else {
        when (band) {
            RangeBand.FAR -> Fractions(0.08f, 0.92f, 0.23f, 0.67f)
            RangeBand.LONG -> Fractions(0.17f, 0.83f, 0.16f, 0.55f)
            RangeBand.ULTRA -> Fractions(0.21f, 0.79f, 0.11f, 0.44f)
        }
    }

    fun close() { pixels = IntArray(0) }
}

data class LongRangeObservation(
    val result: InferenceResult,
    val roi: Rect,
    val band: RangeBand,
    val zoomGain: Float,
    val sweepLabel: String,
    val capturedAtNs: Long,
    val fullWidth: Int,
    val fullHeight: Int,
    val source: DetectionSource
) {
    fun recycle() {
        runCatching { result.maskBitmap?.recycle() }
        result.extraMaskLayers.forEach { runCatching { it.bitmap.recycle() } }
    }
}

object DualRangeFusion {
    /**
     * Integra una observación lejana UNA sola vez en el siguiente frame principal.
     * Así un resultado antiguo no cuenta como evidencia repetida del tracker.
     */
    fun combine(
        base: InferenceResult,
        secondary: InferenceResult,
        roi: Rect,
        fullWidth: Int,
        fullHeight: Int,
        source: DetectionSource,
        distanceBand: String
    ): InferenceResult {
        val roiAreaFraction = ((roi.width().toFloat() * roi.height().toFloat()) /
            (fullWidth.toFloat() * fullHeight.toFloat()).coerceAtLeast(1f)).coerceIn(0f, 1f)

        val mappedSecondary = secondary.detections.map { d ->
            val box = RectF(
                d.box.left + roi.left,
                d.box.top + roi.top,
                d.box.right + roi.left,
                d.box.bottom + roi.top
            )
            val rawAreaFull = (d.maskAreaRatio * roiAreaFraction).coerceIn(0f, 1f)
            val bottomNorm = (box.bottom / fullHeight.toFloat()).coerceIn(0f, 1f)
            val measurement = SeverityEstimator.measure(rawAreaFull, bottomNorm)
            d.copy(
                box = box,
                severity = measurement.severity,
                maskAreaRatio = measurement.rawAreaRatio,
                severityScore = measurement.compensatedAreaRatio,
                perspectiveFactor = measurement.perspectiveFactor,
                source = source,
                distanceBand = distanceBand,
                maskPolygon = d.maskPolygon.map { p -> android.graphics.PointF(p.x + roi.left, p.y + roi.top) }
            )
        }

        val fused = deduplicate(base.detections + mappedSecondary)

        // V15 usa contornos vectoriales por detección. Ya no es necesario trasladar
        // bitmaps de máscara del ROI entre executors, evitando copias y blur por escalado.
        val bm = base.metrics
        val sm = secondary.metrics
        return base.copy(
            detections = fused,
            metrics = InferenceMetrics(
                preprocessMs = bm.preprocessMs,
                modelMs = bm.modelMs,
                postprocessMs = bm.postprocessMs,
                totalMs = bm.totalMs,
                rawCandidates = bm.rawCandidates + sm.rawCandidates,
                keptDetections = fused.size
            ),
            extraMaskLayers = emptyList()
        )
    }

    private fun deduplicate(input: List<Detection>): List<Detection> {
        if (input.size < 2) return input
        val ordered = input.sortedByDescending { it.confidence }
        val kept = ArrayList<Detection>(ordered.size)
        for (d in ordered) {
            val duplicateIndex = kept.indexOfFirst { strongDuplicate(d.box, it.box) }
            if (duplicateIndex < 0) {
                kept.add(d)
            } else {
                val k = kept[duplicateIndex]
                // Handoff V13: si el 416 principal ya ve el mismo bache, pasa a ser
                // la fuente autoritativa. Mientras siga fuera de su alcance, 512 tiene
                // prioridad sobre 416-ROI para conservar detalle de objetos pequeños.
                val preferredSource = when {
                    d.source == DetectionSource.PRIMARY_416 || k.source == DetectionSource.PRIMARY_416 -> DetectionSource.PRIMARY_416
                    d.source == DetectionSource.FAR_512 || k.source == DetectionSource.FAR_512 -> DetectionSource.FAR_512
                    else -> DetectionSource.FAR_416
                }
                val preferredBand = when {
                    d.source == preferredSource -> d.distanceBand
                    k.source == preferredSource -> k.distanceBand
                    preferredSource == DetectionSource.PRIMARY_416 -> "GENERAL"
                    else -> k.distanceBand
                }
                val preferredPolygon = when {
                    d.source == preferredSource && d.maskPolygon.isNotEmpty() -> d.maskPolygon
                    k.source == preferredSource && k.maskPolygon.isNotEmpty() -> k.maskPolygon
                    d.maskPolygon.isNotEmpty() -> d.maskPolygon
                    else -> k.maskPolygon
                }
                kept[duplicateIndex] = k.copy(
                    confidence = max(k.confidence, d.confidence),
                    rawConfidence = max(k.rawConfidence, d.rawConfidence),
                    severity = if (k.severity.ordinal >= d.severity.ordinal) k.severity else d.severity,
                    maskAreaRatio = max(k.maskAreaRatio, d.maskAreaRatio),
                    severityScore = max(k.severityScore, d.severityScore),
                    source = preferredSource,
                    distanceBand = preferredBand,
                    maskPolygon = preferredPolygon
                )
            }
        }
        return kept.take(10)
    }

    private fun strongDuplicate(a: RectF, b: RectF): Boolean {
        val l = max(a.left, b.left)
        val t = max(a.top, b.top)
        val r = min(a.right, b.right)
        val bot = min(a.bottom, b.bottom)
        val inter = max(0f, r - l) * max(0f, bot - t)
        if (inter <= 0f) return false
        val areaA = max(1f, a.width() * a.height())
        val areaB = max(1f, b.width() * b.height())
        val iou = inter / (areaA + areaB - inter + 1e-6f)
        if (iou >= 0.34f) return true
        val overlapSmall = inter / min(areaA, areaB)
        if (overlapSmall >= 0.58f) return true
        val acx = (a.left + a.right) * 0.5f
        val acy = (a.top + a.bottom) * 0.5f
        val bcx = (b.left + b.right) * 0.5f
        val bcy = (b.top + b.bottom) * 0.5f
        val dist = hypot((acx - bcx).toDouble(), (acy - bcy).toDouble()).toFloat()
        val diagA = hypot(a.width().toDouble(), a.height().toDouble()).toFloat().coerceAtLeast(1f)
        val diagB = hypot(b.width().toDouble(), b.height().toDouble()).toFloat().coerceAtLeast(1f)
        val norm = dist / min(diagA, diagB)
        return overlapSmall >= 0.30f && norm <= 0.30f
    }

    /**
     * Copia sólo datos vectoriales para cruzar executors. V15 evita copiar bitmaps de
     * máscara en el camino secundario; cada polígono se clona para aislar el resultado.
     */
    fun detachedCopy(result: InferenceResult): InferenceResult = result.copy(
        detections = result.detections.map { d ->
            d.copy(maskPolygon = d.maskPolygon.map { p -> android.graphics.PointF(p.x, p.y) })
        },
        maskBitmap = null,
        extraMaskLayers = emptyList()
    )
}
