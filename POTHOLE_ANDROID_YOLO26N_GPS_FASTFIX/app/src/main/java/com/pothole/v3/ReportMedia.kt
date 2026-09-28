package com.pothole.v3

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Persistencia de evidencia visual V15.
 *
 * Cada bache confirmado puede generar tres artefactos independientes:
 * 1) ORIGINAL: frame sin anotaciones.
 * 2) MASK: máscara binaria blanca sobre fondo negro (PNG), útil para artículo/QA.
 * 3) OVERLAY: resultado final con contorno vectorial sólido y etiqueta.
 */
object ReportMedia {
    data class SavedImage(val name: String, val uri: Uri)
    data class SavedEvidence(
        val original: SavedImage,
        val mask: SavedImage,
        val overlay: SavedImage
    )

    private val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US)
    private val fileFormat = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US)

    @Synchronized
    fun timestampIso(timeMillis: Long): String = isoFormat.format(Date(timeMillis))

    @Synchronized
    fun fileTimestamp(timeMillis: Long): String = fileFormat.format(Date(timeMillis))

    fun saveScientificEvidence(
        context: Context,
        pixels: IntArray,
        width: Int,
        height: Int,
        target: Detection,
        timeMillis: Long
    ): SavedEvidence {
        val suffix = "${fileTimestamp(timeMillis)}_id${target.trackId}"
        val base = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        try {
            val originalName = "original_$suffix.jpg"
            val originalUri = saveBitmap(
                context, base, originalName, "image/jpeg", "Original",
                Bitmap.CompressFormat.JPEG, 94
            )

            val maskBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val maskCanvas = Canvas(maskBitmap)
            maskCanvas.drawColor(Color.BLACK)
            drawBinaryMask(maskCanvas, target)
            val maskName = "mask_$suffix.png"
            val maskUri = try {
                saveBitmap(
                    context, maskBitmap, maskName, "image/png", "Masks",
                    Bitmap.CompressFormat.PNG, 100
                )
            } finally {
                maskBitmap.recycle()
            }

            val overlayBitmap = base.copy(Bitmap.Config.ARGB_8888, true)
            val overlayUri: Uri
            val overlayName = "overlay_$suffix.jpg"
            try {
                val canvas = Canvas(overlayBitmap)
                drawScientificMask(canvas, target, width)
                drawTargetAnnotation(canvas, target, width)
                overlayUri = saveBitmap(
                    context, overlayBitmap, overlayName, "image/jpeg", "Overlay",
                    Bitmap.CompressFormat.JPEG, 94
                )
            } finally {
                overlayBitmap.recycle()
            }

            return SavedEvidence(
                original = SavedImage(originalName, originalUri),
                mask = SavedImage(maskName, maskUri),
                overlay = SavedImage(overlayName, overlayUri)
            )
        } finally {
            base.recycle()
        }
    }

    /** Compatibilidad: imageName/imageUri continúan apuntando al overlay final. */
    fun saveAnnotatedImage(
        context: Context,
        pixels: IntArray,
        width: Int,
        height: Int,
        result: InferenceResult,
        target: Detection,
        timeMillis: Long
    ): SavedImage = saveScientificEvidence(context, pixels, width, height, target, timeMillis).overlay

    private fun drawBinaryMask(canvas: Canvas, target: Detection) {
        // Para uso científico nunca inventamos una máscara rectangular. Si no existe
        // un contorno válido, el PNG queda vacío (negro) y el CSV conserva la detección.
        val path = polygonPath(target.maskPolygon) ?: return
        canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        })
    }

    private fun drawScientificMask(canvas: Canvas, d: Detection, width: Int) {
        val color = colorFor(d.severity)
        val path = polygonPath(d.maskPolygon)
        if (path != null) {
            val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                this.color = Color.argb(60, Color.red(color), Color.green(color), Color.blue(color))
            }
            val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = (width / 210f).coerceIn(5f, 10f)
                this.color = Color.argb(100, 0, 0, 0)
                strokeJoin = Paint.Join.ROUND
                strokeCap = Paint.Cap.ROUND
            }
            val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = (width / 300f).coerceIn(3.5f, 7f)
                this.color = color
                strokeJoin = Paint.Join.ROUND
                strokeCap = Paint.Cap.ROUND
            }
            canvas.drawPath(path, fill)
            canvas.drawPath(path, shadow)
            canvas.drawPath(path, stroke)
        } else {
            val stroke = (width / 240f).coerceIn(3f, 7f)
            canvas.drawRoundRect(d.box, stroke, stroke, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = stroke
                this.color = color
            })
        }
    }

    private fun drawTargetAnnotation(canvas: Canvas, d: Detection, width: Int) {
        val color = colorFor(d.severity)
        val anchor = polygonBounds(d.maskPolygon) ?: d.box
        val textSize = (width / 34f).coerceIn(20f, 36f)
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = if (d.severity == Severity.HIGH) Color.WHITE else Color.rgb(12, 18, 22)
            this.textSize = textSize
            typeface = Typeface.DEFAULT_BOLD
        }
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = Color.argb(240, Color.red(color), Color.green(color), Color.blue(color))
        }
        val prefix = when {
            d.handoffFromFar -> "APROX. • "
            d.source != DetectionSource.PRIMARY_416 -> "LEJANO • "
            else -> ""
        }
        val label = "$prefix#${d.trackId} • ${d.severity.label} • S${"%.1f".format(Locale.US, d.confidence * 100f)}%"
        val padding = 8f
        val textW = textPaint.measureText(label)
        val fm = textPaint.fontMetrics
        val labelH = (fm.descent - fm.ascent) + padding * 1.5f
        val left = anchor.left.coerceIn(0f, (width - textW - padding * 2f).coerceAtLeast(0f))
        val top = (anchor.top - labelH - 4f).coerceAtLeast(0f)
        val labelRect = RectF(left, top, left + textW + padding * 2f, top + labelH)
        canvas.drawRoundRect(labelRect, padding, padding, bg)
        canvas.drawText(label, left + padding, top + padding * 0.75f - fm.ascent, textPaint)
    }

    private fun colorFor(severity: Severity): Int = when (severity) {
        Severity.LOW -> Color.rgb(0, 232, 150)
        Severity.MEDIUM -> Color.rgb(255, 193, 7)
        Severity.HIGH -> Color.rgb(244, 67, 54)
    }

    private fun polygonPath(points: List<PointF>): Path? {
        if (points.size < 3) return null
        val path = Path()
        path.moveTo(points[0].x, points[0].y)
        for (i in 1 until points.size) path.lineTo(points[i].x, points[i].y)
        path.close()
        return path
    }

    private fun polygonBounds(points: List<PointF>): RectF? {
        if (points.size < 3) return null
        var l = Float.POSITIVE_INFINITY
        var t = Float.POSITIVE_INFINITY
        var r = Float.NEGATIVE_INFINITY
        var b = Float.NEGATIVE_INFINITY
        points.forEach {
            if (it.x < l) l = it.x
            if (it.y < t) t = it.y
            if (it.x > r) r = it.x
            if (it.y > b) b = it.y
        }
        return if (l.isFinite() && t.isFinite() && r.isFinite() && b.isFinite()) RectF(l, t, r, b) else null
    }

    private fun saveBitmap(
        context: Context,
        bitmap: Bitmap,
        name: String,
        mime: String,
        subfolder: String,
        format: Bitmap.CompressFormat,
        quality: Int
    ): Uri {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, mime)
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/DetectorBaches/$subfolder")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: error("No se pudo crear $name en MediaStore")
            try {
                resolver.openOutputStream(uri, "w")?.use { out ->
                    check(bitmap.compress(format, quality, out)) { "Error al comprimir $name" }
                } ?: error("No se pudo abrir el destino de $name")
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                return uri
            } catch (t: Throwable) {
                runCatching { resolver.delete(uri, null, null) }
                throw t
            }
        }

        val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "DetectorBaches/$subfolder").apply { mkdirs() }
        val file = File(dir, name)
        FileOutputStream(file).use { out ->
            check(bitmap.compress(format, quality, out)) { "Error al comprimir $name" }
        }
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }

    /** Elimina todas las evidencias asociadas a un registro. */
    fun deleteImage(context: Context, report: PotholeReport): Boolean {
        val uris = linkedSetOf(
            report.imageUri,
            report.overlayImageUri,
            report.originalImageUri,
            report.maskImageUri
        ).filter { it.isNotBlank() }
        var allOk = true
        uris.forEach { raw ->
            val uri = runCatching { Uri.parse(raw) }.getOrNull() ?: return@forEach
            val ok = runCatching { context.contentResolver.delete(uri, null, null) >= 0 }.getOrDefault(false)
            if (!ok) allOk = false
        }

        // En Android < 10 las evidencias viven bajo externalFilesDir y FileProvider
        // no siempre admite delete() vía ContentResolver. Se hace fallback por nombre.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            val root = File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "DetectorBaches")
            val candidates = listOf(
                File(root, "Overlay/${report.overlayImageName.ifBlank { report.imageName }}"),
                File(root, "Original/${report.originalImageName}"),
                File(root, "Masks/${report.maskImageName}")
            )
            candidates.filter { it.name.isNotBlank() }.forEach { file ->
                if (file.exists() && !file.delete()) allOk = false
            }
        }
        return allOk
    }

    fun exportCsvToDownloads(context: Context, source: File): Uri {
        require(source.exists()) { "Aún no hay reportes para exportar" }
        val name = "reporte_baches_${fileTimestamp(System.currentTimeMillis())}.csv"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "text/csv")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/DetectorBaches")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("No se pudo crear el CSV en Descargas")
            try {
                resolver.openOutputStream(uri, "w")?.use { out ->
                    FileInputStream(source).use { input -> input.copyTo(out) }
                } ?: error("No se pudo abrir el destino CSV")
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                return uri
            } catch (t: Throwable) {
                runCatching { resolver.delete(uri, null, null) }
                throw t
            }
        }

        val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), "DetectorBaches").apply { mkdirs() }
        val file = File(dir, name)
        source.copyTo(file, overwrite = true)
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }

    fun shareUriForCsv(context: Context, source: File): Uri {
        require(source.exists()) { "Aún no hay reportes para compartir" }
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", source)
    }
}
