package com.pothole.v3

import android.graphics.PointF
import android.graphics.RectF
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * Tracker temporal optimizado para cámara montada en parabrisas.
 *
 * V7 FINAL MOBILE:
 * - Asociación global por IoU + distancia + escala + predicción de velocidad.
 * - Prioridad suave a tracks confirmados para conservar IDs.
 * - Recupera el mismo ID tras pérdidas breves, sin dibujar cajas fantasma.
 * - Fusiona tracks duplicados.
 * - Score del modelo suavizado temporalmente, pero preserva rawConfidence.
 * - Severidad basada en área compensada por perspectiva + EMA + histéresis.
 * - Requiere evidencia temporal para subir/bajar severidad y evitar parpadeos.
 */
class TemporalTracker {
    private data class Track(
        val id: Int,
        var box: RectF,
        var confidence: Float,
        var rawConfidence: Float,
        var areaEma: Float,
        var severityScoreEma: Float,
        var perspectiveFactorEma: Float,
        var severity: Severity,
        var source: DetectionSource,
        var distanceBand: String,
        var maskPolygon: List<PointF>,
        val firstSeenSource: DetectionSource,
        val firstSeenDistanceBand: String,
        var lastSeenNs: Long,
        var hits: Int = 1,
        var missed: Int = 0,
        var vx: Float = 0f,
        var vy: Float = 0f,
        var upEvidence: Int = 0,
        var downEvidence: Int = 0
    ) {
        val confirmed: Boolean get() = hits >= CONFIRM_HITS
    }

    private data class Pairing(
        val detectionIndex: Int,
        val trackIndex: Int,
        val score: Float
    )

    companion object {
        private const val CONFIRM_HITS = 2
        private const val MAX_MISSED = 4
        private const val MAX_TRACK_AGE_MS = 900L
        private const val MIN_ASSOC_IOU = 0.06f
        private const val MAX_FRAME_CENTER_DISTANCE = 0.22f
        private const val MAX_LOCAL_CENTER_DISTANCE = 1.65f
        private const val MIN_SIZE_RATIO = 0.14f
        private const val MAX_SIZE_RATIO = 7.0f
        private const val HANDOFF_FRAME_DISTANCE = 0.12f
        private const val HANDOFF_LOCAL_DISTANCE = 1.90f
        private const val HANDOFF_MIN_SIZE_RATIO = 0.08f
        private const val HANDOFF_MAX_SIZE_RATIO = 12.0f

        // Supresión final de tracks que representan el mismo bache.
        private const val DUP_IOU = 0.40f
        private const val DUP_OVERLAP_SMALL = 0.62f
        private const val DUP_CENTER_NORM = 0.32f
        private const val DUP_CENTER_OVERLAP = 0.38f

        private const val SEVERITY_UP_FRAMES = 2
        private const val SEVERITY_DOWN_FRAMES = 2
    }

    private val tracks = ArrayList<Track>()
    private var nextId = 1
    private var frameWidth = 0
    private var frameHeight = 0

    @Synchronized
    fun reset(resetIds: Boolean = true) {
        tracks.clear()
        if (resetIds) nextId = 1
        frameWidth = 0
        frameHeight = 0
    }

    @Synchronized
    fun update(
        detections: List<Detection>,
        width: Int,
        height: Int,
        nowNs: Long = System.nanoTime()
    ): List<Detection> {
        if (width <= 0 || height <= 0) return detections
        if (frameWidth != 0 && (frameWidth != width || frameHeight != height)) reset()
        frameWidth = width
        frameHeight = height

        val current = detections.sortedByDescending { it.confidence }
        val frameDiagonal = hypot(width.toDouble(), height.toDouble()).toFloat().coerceAtLeast(1f)

        val pairings = ArrayList<Pairing>(current.size * max(1, tracks.size))
        for (di in current.indices) {
            val d = current[di]
            for (ti in tracks.indices) {
                val t = tracks[ti]
                val ageSec = ((nowNs - t.lastSeenNs).coerceAtLeast(0L) / 1_000_000_000f).coerceAtMost(1.2f)
                val predicted = shifted(t.box, t.vx * ageSec, t.vy * ageSec, width, height)
                val overlap = iou(d.box, predicted)
                val frameDist = centerDistance(d.box, predicted) / frameDiagonal
                val localScale = max(18f, (boxDiagonal(d.box) + boxDiagonal(predicted)) * 0.5f)
                val localDist = centerDistance(d.box, predicted) / localScale
                val ratio = sizeRatio(d.box, predicted)

                val crossSourceHandoff = isCrossSourceHandoff(t.source, d.source)
                val handoffCompatible = crossSourceHandoff && t.confirmed &&
                    frameDist <= HANDOFF_FRAME_DISTANCE &&
                    localDist <= HANDOFF_LOCAL_DISTANCE &&
                    ratio in HANDOFF_MIN_SIZE_RATIO..HANDOFF_MAX_SIZE_RATIO
                val compatible = overlap >= MIN_ASSOC_IOU ||
                    (frameDist <= MAX_FRAME_CENTER_DISTANCE &&
                        localDist <= MAX_LOCAL_CENTER_DISTANCE &&
                        ratio in MIN_SIZE_RATIO..MAX_SIZE_RATIO) ||
                    handoffCompatible
                if (!compatible) continue

                val sizePenalty = abs(ln(ratio.coerceAtLeast(1e-3f)))
                val confirmedBonus = if (t.confirmed) 0.14f else 0f
                val recoveryBonus = if (t.missed > 0 && frameDist < 0.10f) 0.08f else 0f
                val handoffBonus = if (handoffCompatible) 0.26f else 0f
                val score = overlap * 2.45f +
                    (1f - (localDist / MAX_LOCAL_CENTER_DISTANCE).coerceIn(0f, 1f)) * 0.88f +
                    (1f - (frameDist / MAX_FRAME_CENTER_DISTANCE).coerceIn(0f, 1f)) * 0.38f +
                    confirmedBonus + recoveryBonus + handoffBonus -
                    sizePenalty * 0.16f - t.missed * 0.06f
                pairings.add(Pairing(di, ti, score))
            }
        }
        pairings.sortByDescending { it.score }

        val detAssigned = BooleanArray(current.size)
        val trackAssigned = BooleanArray(tracks.size)
        val observed = ArrayList<Detection>(current.size)
        val seenTrackIds = HashSet<Int>()

        for (p in pairings) {
            if (detAssigned[p.detectionIndex] || trackAssigned[p.trackIndex]) continue
            val d = current[p.detectionIndex]
            val t = tracks[p.trackIndex]
            updateTrack(t, d, nowNs)
            detAssigned[p.detectionIndex] = true
            trackAssigned[p.trackIndex] = true
            seenTrackIds.add(t.id)
            observed.add(toTrackedDetection(d, t))
        }

        // Detecciones sin pareja: evita crear un ID nuevo sobre una región ya representada.
        for (di in current.indices) {
            if (detAssigned[di]) continue
            val d = current[di]
            if (observed.any { strongDuplicate(d.box, it.box) }) continue

            val severity = SeverityEstimator.raw(d.severityScore)
            val t = Track(
                id = nextId++,
                box = RectF(d.box),
                confidence = d.confidence,
                rawConfidence = d.rawConfidence,
                areaEma = d.maskAreaRatio,
                severityScoreEma = d.severityScore,
                perspectiveFactorEma = d.perspectiveFactor,
                severity = severity,
                source = d.source,
                distanceBand = d.distanceBand,
                maskPolygon = d.maskPolygon.map { PointF(it.x, it.y) },
                firstSeenSource = d.source,
                firstSeenDistanceBand = d.distanceBand,
                lastSeenNs = nowNs
            )
            tracks.add(t)
            seenTrackIds.add(t.id)
            observed.add(
                d.copy(
                    severity = severity,
                    trackId = t.id,
                    predicted = false,
                    trackConfirmed = false,
                    firstSeenSource = t.firstSeenSource,
                    firstSeenDistanceBand = t.firstSeenDistanceBand,
                    handoffFromFar = false
                )
            )
        }

        // Retención interna corta para recuperar ID; nunca se dibuja si no fue observado.
        val iterator = tracks.iterator()
        while (iterator.hasNext()) {
            val t = iterator.next()
            if (t.id in seenTrackIds) continue
            t.missed++
            val ageMs = (nowNs - t.lastSeenNs).coerceAtLeast(0L) / 1_000_000L
            if (t.missed > MAX_MISSED || ageMs > MAX_TRACK_AGE_MS) iterator.remove()
        }

        return collapseDuplicateTracks(observed).sortedByDescending { it.confidence }
    }

    private fun updateTrack(t: Track, d: Detection, nowNs: Long) {
        val dt = ((nowNs - t.lastSeenNs).coerceAtLeast(1L) / 1_000_000_000f).coerceIn(0.03f, 1.2f)
        val oldCx = centerX(t.box)
        val oldCy = centerY(t.box)
        val newCx = centerX(d.box)
        val newCy = centerY(d.box)
        val instVx = (newCx - oldCx) / dt
        val instVy = (newCy - oldCy) / dt
        t.vx = t.vx * 0.70f + instVx * 0.30f
        t.vy = t.vy * 0.70f + instVy * 0.30f

        // 5 FPS: prioriza frame actual para no arrastrar la caja detrás del vehículo.
        val alpha = 0.80f
        t.box = RectF(
            t.box.left * (1f - alpha) + d.box.left * alpha,
            t.box.top * (1f - alpha) + d.box.top * alpha,
            t.box.right * (1f - alpha) + d.box.right * alpha,
            t.box.bottom * (1f - alpha) + d.box.bottom * alpha
        )

        // El score mostrado es suavizado; rawConfidence preserva el valor del frame.
        t.rawConfidence = d.rawConfidence
        t.confidence = t.confidence * 0.45f + d.confidence * 0.55f
        // Handoff profesional: un track que nació en 512/ROI conserva su ID al entrar
        // en el 416 principal. El 416 pasa a ser la fuente autoritativa cuando ya ve el bache.
        if (d.source == DetectionSource.PRIMARY_416 ||
            (t.source != DetectionSource.PRIMARY_416 && sourcePriority(d.source) > sourcePriority(t.source))) {
            t.source = d.source
            t.distanceBand = d.distanceBand
        }
        if (d.maskPolygon.isNotEmpty()) {
            t.maskPolygon = smoothPolygon(t.maskPolygon, d.maskPolygon, 0.72f)
        }

        // EMA en lugar de máximo histórico: evita que un único frame cercano deje ALTA permanente.
        val measurementAlpha = if (t.hits < 3) 0.58f else 0.42f
        t.areaEma = t.areaEma * (1f - measurementAlpha) + d.maskAreaRatio * measurementAlpha
        t.severityScoreEma = t.severityScoreEma * (1f - measurementAlpha) + d.severityScore * measurementAlpha
        t.perspectiveFactorEma = t.perspectiveFactorEma * (1f - measurementAlpha) + d.perspectiveFactor * measurementAlpha
        updateSeverityEvidence(t)

        t.lastSeenNs = nowNs
        t.missed = 0
        t.hits++
    }

    private fun updateSeverityEvidence(t: Track) {
        val candidate = SeverityEstimator.stabilized(t.severity, t.severityScoreEma)
        val currentRank = t.severity.ordinal
        val candidateRank = candidate.ordinal
        when {
            candidateRank > currentRank -> {
                t.upEvidence++
                t.downEvidence = 0
                if (t.upEvidence >= SEVERITY_UP_FRAMES) {
                    t.severity = candidate
                    t.upEvidence = 0
                }
            }
            candidateRank < currentRank -> {
                t.downEvidence++
                t.upEvidence = 0
                if (t.downEvidence >= SEVERITY_DOWN_FRAMES) {
                    t.severity = candidate
                    t.downEvidence = 0
                }
            }
            else -> {
                t.upEvidence = 0
                t.downEvidence = 0
            }
        }
    }

    private fun toTrackedDetection(d: Detection, t: Track): Detection = d.copy(
        box = RectF(t.box),
        confidence = t.confidence,
        rawConfidence = t.rawConfidence,
        severity = t.severity,
        maskAreaRatio = t.areaEma,
        severityScore = t.severityScoreEma,
        perspectiveFactor = t.perspectiveFactorEma,
        trackId = t.id,
        predicted = false,
        trackConfirmed = t.confirmed,
        source = t.source,
        distanceBand = t.distanceBand,
        firstSeenSource = t.firstSeenSource,
        firstSeenDistanceBand = t.firstSeenDistanceBand,
        handoffFromFar = t.firstSeenSource != DetectionSource.PRIMARY_416 &&
            t.source == DetectionSource.PRIMARY_416,
        maskPolygon = t.maskPolygon.map { PointF(it.x, it.y) }
    )

    /**
     * Última barrera anti-duplicados. Conserva el track más estable/antiguo.
     */
    private fun collapseDuplicateTracks(input: List<Detection>): List<Detection> {
        if (input.size < 2) return input

        val ordered = input.sortedWith(
            compareByDescending<Detection> { it.trackConfirmed }
                .thenByDescending { trackHits(it.trackId) }
                .thenByDescending { it.confidence }
                .thenBy { it.trackId }
        )
        val kept = ArrayList<Detection>(ordered.size)
        val removeIds = HashSet<Int>()

        for (d in ordered) {
            var duplicateIndex = -1
            for (i in kept.indices) {
                if (strongDuplicate(d.box, kept[i].box)) {
                    duplicateIndex = i
                    break
                }
            }
            if (duplicateIndex < 0) {
                kept.add(d)
                continue
            }

            val winner = kept[duplicateIndex]
            val mergedArea = (winner.maskAreaRatio + d.maskAreaRatio) * 0.5f
            val mergedSeverityScore = max(winner.severityScore, d.severityScore)
            val mergedSeverity = SeverityEstimator.stabilized(winner.severity, mergedSeverityScore)
            kept[duplicateIndex] = winner.copy(
                confidence = max(winner.confidence, d.confidence),
                rawConfidence = max(winner.rawConfidence, d.rawConfidence),
                severity = mergedSeverity,
                maskAreaRatio = mergedArea,
                severityScore = mergedSeverityScore,
                perspectiveFactor = (winner.perspectiveFactor + d.perspectiveFactor) * 0.5f
            )
            if (d.trackId > 0 && d.trackId != winner.trackId) removeIds.add(d.trackId)
        }

        if (removeIds.isNotEmpty()) tracks.removeAll { it.id in removeIds }
        return kept
    }

    private fun trackHits(id: Int): Int = tracks.firstOrNull { it.id == id }?.hits ?: 0

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
        if (iou >= DUP_IOU) return true

        val overlapSmall = inter / min(areaA, areaB)
        if (overlapSmall >= DUP_OVERLAP_SMALL) return true

        val centerNorm = centerDistance(a, b) / max(1f, min(boxDiagonal(a), boxDiagonal(b)))
        val areaSimilarity = min(areaA, areaB) / max(areaA, areaB)
        return overlapSmall >= DUP_CENTER_OVERLAP &&
            centerNorm <= DUP_CENTER_NORM && areaSimilarity >= 0.25f
    }

    /** Estabiliza el contorno entre frames sin añadir retraso perceptible. */
    private fun smoothPolygon(previous: List<PointF>, current: List<PointF>, alpha: Float): List<PointF> {
        if (current.isEmpty()) return previous
        if (previous.size != current.size || previous.isEmpty()) {
            return current.map { PointF(it.x, it.y) }
        }
        val a = alpha.coerceIn(0f, 1f)
        return current.indices.map { i ->
            val p = previous[i]
            val c = current[i]
            PointF(p.x * (1f - a) + c.x * a, p.y * (1f - a) + c.y * a)
        }
    }

    private fun shifted(box: RectF, dx: Float, dy: Float, width: Int, height: Int): RectF {
        val w = box.width().coerceAtLeast(1f)
        val h = box.height().coerceAtLeast(1f)
        var l = box.left + dx
        var t = box.top + dy
        l = l.coerceIn(0f, (width - w).coerceAtLeast(0f))
        t = t.coerceIn(0f, (height - h).coerceAtLeast(0f))
        return RectF(l, t, (l + w).coerceAtMost(width.toFloat()), (t + h).coerceAtMost(height.toFloat()))
    }

    private fun sourcePriority(source: DetectionSource): Int = when (source) {
        DetectionSource.PRIMARY_416 -> 3
        DetectionSource.FAR_512 -> 2
        DetectionSource.FAR_416 -> 1
    }

    private fun isCrossSourceHandoff(a: DetectionSource, b: DetectionSource): Boolean {
        val aFar = a != DetectionSource.PRIMARY_416
        val bFar = b != DetectionSource.PRIMARY_416
        return aFar != bFar
    }

    private fun centerX(r: RectF) = (r.left + r.right) * 0.5f
    private fun centerY(r: RectF) = (r.top + r.bottom) * 0.5f

    private fun centerDistance(a: RectF, b: RectF): Float = hypot(
        (centerX(a) - centerX(b)).toDouble(),
        (centerY(a) - centerY(b)).toDouble()
    ).toFloat()

    private fun boxDiagonal(r: RectF): Float = hypot(
        r.width().coerceAtLeast(1f).toDouble(),
        r.height().coerceAtLeast(1f).toDouble()
    ).toFloat()

    private fun sizeRatio(a: RectF, b: RectF): Float {
        val aa = max(1f, a.width() * a.height())
        val bb = max(1f, b.width() * b.height())
        return aa / bb
    }

    private fun iou(a: RectF, b: RectF): Float {
        val l = max(a.left, b.left)
        val t = max(a.top, b.top)
        val r = min(a.right, b.right)
        val bot = min(a.bottom, b.bottom)
        val inter = max(0f, r - l) * max(0f, bot - t)
        val areaA = max(0f, a.width()) * max(0f, a.height())
        val areaB = max(0f, b.width()) * max(0f, b.height())
        return inter / (areaA + areaB - inter + 1e-6f)
    }
}
