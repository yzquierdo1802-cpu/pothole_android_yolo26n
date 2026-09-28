package com.pothole.v3

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import kotlin.math.max

/**
 * Overlay V15 SCIENTIFIC MASK RENDER.
 *
 * La visualización principal ya no depende del bitmap de máscara ampliado. Ese camino
 * producía una apariencia borrosa debido a que la máscara nativa (104/128 px aprox.)
 * era interpolada hasta la resolución de pantalla. V15 dibuja el contorno vectorial
 * refinado entregado por el motor:
 *
 * - relleno uniforme y semitransparente;
 * - contorno sólido, continuo y anti-aliased;
 * - ligera línea de contraste por debajo del contorno;
 * - bounding box sólo como apoyo cuando la máscara aún no es fiable;
 * - etiquetas compactas y aptas para capturas de resultados científicos.
 */
class OverlayView(context: Context, attrs: AttributeSet?) : View(context, attrs) {
    @Volatile private var result: InferenceResult? = null

    private val density = resources.displayMetrics.density
    private val scaledDensity = resources.displayMetrics.scaledDensity
    private val boxStroke = (1.25f * density).coerceIn(2.2f, 4.5f)
    private val labelRadius = (6f * density).coerceIn(9f, 22f)
    private val labelPadX = (6f * density).coerceIn(9f, 22f)
    private val labelPadY = (3.5f * density).coerceIn(5f, 14f)

    private val contourFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        isDither = true
    }
    private val contourStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = (3.0f * density).coerceIn(4.5f, 9f)
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val contourShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = (4.6f * density).coerceIn(7f, 13f)
        color = Color.argb(112, 0, 0, 0)
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = boxStroke
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val boxShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = boxStroke + 2.0f * density
        color = Color.argb(105, 0, 0, 0)
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = (13.5f * scaledDensity).coerceIn(24f, 40f)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val textBgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val predictedDash = DashPathEffect(floatArrayOf(14f, 10f), 0f)

    fun setResults(r: InferenceResult) {
        result?.extraMaskLayers?.forEach { layer -> runCatching { layer.bitmap.recycle() } }
        result = r
        postInvalidateOnAnimation()
    }

    fun clear() {
        result?.extraMaskLayers?.forEach { layer -> runCatching { layer.bitmap.recycle() } }
        result = null
        postInvalidateOnAnimation()
    }

    private fun colorFor(severity: Severity): Int = when (severity) {
        Severity.LOW -> Color.rgb(0, 232, 150)
        Severity.MEDIUM -> Color.rgb(255, 193, 7)
        Severity.HIGH -> Color.rgb(244, 67, 54)
    }

    private fun isFar(d: Detection): Boolean = d.source != DetectionSource.PRIMARY_416

    private fun statePrefix(d: Detection): String = when {
        d.predicted -> "~"
        !d.trackConfirmed && isFar(d) -> "LEJANO • POSIBLE • "
        !d.trackConfirmed -> "POSIBLE • "
        d.handoffFromFar -> "APROX. • "
        isFar(d) -> "LEJANO • "
        else -> ""
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val r = result ?: return
        val iw = r.sourceWidth.coerceAtLeast(1)
        val ih = r.sourceHeight.coerceAtLeast(1)

        // PreviewView usa FILL_CENTER y comparte ViewPort con ImageAnalysis.
        val scale = max(width.toFloat() / iw, height.toFloat() / ih)
        val dx = (width - iw * scale) * 0.5f
        val dy = (height - ih * scale) * 0.5f

        // 1) Primero las máscaras vectoriales: son la información principal.
        for (d in r.detections) {
            if (d.predicted || d.maskPolygon.size < 5) continue
            drawVectorMask(canvas, d, scale, dx, dy)
        }

        // 2) Etiqueta y, sólo cuando hace falta, bbox de apoyo.
        for (d in r.detections) {
            val color = colorFor(d.severity)
            val box = mappedBox(d.box, scale, dx, dy)
            val hasScientificMask = d.maskPolygon.size >= 5 && !d.predicted
            val shouldDrawBox = !hasScientificMask || !d.trackConfirmed

            if (shouldDrawBox) {
                boxPaint.color = color
                boxPaint.alpha = when {
                    d.predicted -> 105
                    !d.trackConfirmed -> 185
                    else -> 210
                }
                boxPaint.pathEffect = if (d.predicted) predictedDash else null
                boxShadowPaint.pathEffect = if (d.predicted) predictedDash else null
                boxShadowPaint.alpha = if (d.predicted) 65 else 96
                canvas.drawRoundRect(box, 3f * density, 3f * density, boxShadowPaint)
                canvas.drawRoundRect(box, 3f * density, 3f * density, boxPaint)
            }

            val anchor = if (hasScientificMask) polygonBounds(d.maskPolygon, scale, dx, dy) else box
            drawLabel(canvas, d, anchor, color)
        }

        boxPaint.pathEffect = null
        boxPaint.alpha = 255
        boxShadowPaint.pathEffect = null
        boxShadowPaint.alpha = 96
    }

    private fun drawVectorMask(canvas: Canvas, d: Detection, scale: Float, dx: Float, dy: Float) {
        val color = colorFor(d.severity)
        val path = polygonPath(d.maskPolygon, scale, dx, dy) ?: return

        val fillAlpha = when {
            !d.trackConfirmed -> 34
            isFar(d) -> 44
            else -> 58
        }
        contourFillPaint.color = Color.argb(fillAlpha, Color.red(color), Color.green(color), Color.blue(color))
        contourStrokePaint.color = color
        contourStrokePaint.alpha = if (d.trackConfirmed) 255 else 225
        contourShadowPaint.alpha = if (d.trackConfirmed) 108 else 82

        canvas.drawPath(path, contourFillPaint)
        canvas.drawPath(path, contourShadowPaint)
        canvas.drawPath(path, contourStrokePaint)
    }

    private fun drawLabel(canvas: Canvas, d: Detection, anchor: RectF, color: Int) {
        val idText = if (d.trackConfirmed && d.trackId > 0) "#${d.trackId} • " else ""
        val scoreText = "%.1f".format(d.confidence * 100f)
        val label = "${statePrefix(d)}$idText${d.severity.label} • S$scoreText%"

        textPaint.textSize = ((if (isFar(d)) 12.5f else 13.5f) * scaledDensity).coerceIn(23f, 40f)
        textPaint.color = if (d.severity == Severity.HIGH) Color.WHITE else Color.rgb(13, 18, 22)
        val tw = textPaint.measureText(label)
        val fm = textPaint.fontMetrics
        val textH = fm.descent - fm.ascent
        val labelW = tw + labelPadX * 2f
        val labelH = textH + labelPadY * 2f
        val left = anchor.left.coerceIn(0f, (width - labelW).coerceAtLeast(0f))
        val top = (anchor.top - labelH - 3f * density).coerceAtLeast(0f)
        val labelRect = RectF(left, top, left + labelW, top + labelH)

        textBgPaint.color = Color.argb(
            if (d.trackConfirmed) 238 else 220,
            Color.red(color), Color.green(color), Color.blue(color)
        )
        canvas.drawRoundRect(labelRect, labelRadius, labelRadius, textBgPaint)
        canvas.drawText(label, left + labelPadX, top + labelPadY - fm.ascent, textPaint)
    }

    private fun polygonPath(points: List<PointF>, scale: Float, dx: Float, dy: Float): Path? {
        if (points.size < 3) return null
        val path = Path()
        path.moveTo(dx + points[0].x * scale, dy + points[0].y * scale)
        for (i in 1 until points.size) {
            path.lineTo(dx + points[i].x * scale, dy + points[i].y * scale)
        }
        path.close()
        return path
    }

    private fun polygonBounds(points: List<PointF>, scale: Float, dx: Float, dy: Float): RectF {
        var l = Float.POSITIVE_INFINITY
        var t = Float.POSITIVE_INFINITY
        var r = Float.NEGATIVE_INFINITY
        var b = Float.NEGATIVE_INFINITY
        for (p in points) {
            val x = dx + p.x * scale
            val y = dy + p.y * scale
            if (x < l) l = x
            if (y < t) t = y
            if (x > r) r = x
            if (y > b) b = y
        }
        if (!l.isFinite() || !t.isFinite() || !r.isFinite() || !b.isFinite()) return RectF()
        return RectF(l, t, r, b)
    }

    private fun mappedBox(box: RectF, scale: Float, dx: Float, dy: Float): RectF = RectF(
        dx + box.left * scale,
        dy + box.top * scale,
        dx + box.right * scale,
        dy + box.bottom * scale
    )
}
