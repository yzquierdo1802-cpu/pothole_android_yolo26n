package com.pothole.v3

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PointF
import android.graphics.RectF
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.channels.FileChannel
import java.util.Arrays
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * YOLO26n-seg mobile engine.
 *
 * V7 FINAL MOBILE:
 * - Elimina Bitmap/Canvas/getPixels del preprocesamiento.
 * - Prepara el tensor directamente desde el IntArray RGBA de CameraX.
 * - Hace una sola escritura masiva FloatArray -> FloatBuffer por frame.
 * - NMS más estricto + supresión por solapamiento sobre el objeto pequeño.
 * - Reutiliza todos los buffers importantes.
 * - Tamaño de entrada y máscara se derivan del modelo (preparado para tamaños móviles dinámicos).
 *
 * Se mantiene CPU/XNNPACK porque fue la ruta estable en el dispositivo probado.
 */
enum class PreprocessQuality { FAST, DETAIL, DETAIL_ENHANCED }

class YoloSegmentationEngine(
    context: Context,
    private val modelAsset: String = "pothole_yolo26n_seg_416_int8.tflite",
    private val detectionSource: DetectionSource = DetectionSource.PRIMARY_416,
    threadLimit: Int? = null
) : AutoCloseable {
    companion object {
        private const val MAX_DET = 8
        private const val IOU_THRESHOLD = 0.38f
        private const val OVERLAP_SMALL_THRESHOLD = 0.58f
        private const val MAX_CANDIDATES_FOR_NMS = 100
        private const val PAD_BYTE = 114
        private const val INV_255 = 1f / 255f
    }

    private val interpreter: Interpreter
    private val numThreads: Int

    private val inputShape: IntArray
    private val inputNchw: Boolean
    private val modelWidth: Int
    private val modelHeight: Int
    private val inputBuffer: ByteBuffer
    private val inputFloatBuffer: FloatBuffer
    private val inputFloats: FloatArray
    private val inputArray: Array<Any>

    private val outputShapes: List<IntArray>
    private val outputBuffers: List<ByteBuffer>
    private val outputMap: MutableMap<Int, Any>
    private val detIdx: Int
    private val protoIdx: Int
    private val detBuffer: FloatBuffer
    private val protoBuffer: FloatBuffer
    private val detChannelsFirst: Boolean
    private val detChannels: Int
    private val detCount: Int

    private val maskDim: Int
    private val protoChannelsFirst: Boolean
    private val maskWidth: Int
    private val maskHeight: Int
    private val maskArea: Int
    private val maskPixels: IntArray
    private val positivePixels: IntArray
    private val maskBinary: ByteArray
    private val maskScratch: ByteArray
    private val maskVisited: ByteArray
    private val componentQueue: IntArray
    private val coeffScratch: FloatArray
    private val maskBitmaps: Array<Bitmap>
    private var maskBitmapCursor = 0

    // Mapeos de redimensionamiento reutilizados. CameraX mantiene normalmente la misma
    // resolución durante toda la sesión, así evitamos roundToInt/coerceIn por píxel.
    private var xMap = IntArray(0)
    private var yMap = IntArray(0)
    private var mapSourceW = -1
    private var mapSourceH = -1
    private var mapContentW = -1
    private var mapContentH = -1

    init {
        val afd = context.assets.openFd(modelAsset)
        val mapped = FileInputStream(afd.fileDescriptor).channel.map(
            FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.declaredLength
        )
        afd.close()

        val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(2)
        val autoThreads = when {
            cores >= 8 -> 4
            cores >= 6 -> 3
            else -> 2
        }
        numThreads = (threadLimit ?: autoThreads).coerceIn(1, autoThreads)
        interpreter = Interpreter(mapped, Interpreter.Options().apply {
            setNumThreads(numThreads)
            setUseXNNPACK(true)
        })

        val inputTensor = interpreter.getInputTensor(0)
        require(inputTensor.dataType() == DataType.FLOAT32) {
            "Este build requiere input FLOAT32. Recibido ${inputTensor.dataType()}"
        }
        inputShape = inputTensor.shape()
        require(inputShape.size == 4 && (inputShape[1] == 3 || inputShape[3] == 3)) {
            "Input YOLO inesperado: ${inputShape.contentToString()}"
        }
        inputNchw = inputShape[1] == 3
        modelHeight = if (inputNchw) inputShape[2] else inputShape[1]
        modelWidth = if (inputNchw) inputShape[3] else inputShape[2]
        require(modelWidth > 0 && modelHeight > 0) { "Resolución de modelo inválida" }

        inputBuffer = ByteBuffer.allocateDirect(inputTensor.numBytes()).order(ByteOrder.nativeOrder())
        inputFloatBuffer = inputBuffer.asFloatBuffer()
        inputFloats = FloatArray(inputTensor.numBytes() / 4)
        inputArray = arrayOf(inputBuffer)

        outputShapes = (0 until interpreter.outputTensorCount).map { interpreter.getOutputTensor(it).shape() }
        outputBuffers = (0 until interpreter.outputTensorCount).map { i ->
            val tensor = interpreter.getOutputTensor(i)
            require(tensor.dataType() == DataType.FLOAT32) {
                "Output $i no es FLOAT32: ${tensor.dataType()}"
            }
            ByteBuffer.allocateDirect(tensor.numBytes()).order(ByteOrder.nativeOrder())
        }
        outputMap = HashMap<Int, Any>().apply {
            outputBuffers.forEachIndexed { i, b -> put(i, b) }
        }

        var d = -1
        var p = -1
        outputShapes.forEachIndexed { i, s ->
            if (s.size == 3 && min(s[1], s[2]) in 6..128) d = i
            if (s.size == 4) p = i
        }
        require(d >= 0 && p >= 0) { "Salidas YOLO inesperadas: ${outputShapes.joinToString { it.contentToString() }}" }
        detIdx = d
        protoIdx = p
        detBuffer = outputBuffers[detIdx].asFloatBuffer()
        protoBuffer = outputBuffers[protoIdx].asFloatBuffer()

        val ds = outputShapes[detIdx]
        detChannelsFirst = ds[1] <= ds[2]
        detChannels = if (detChannelsFirst) ds[1] else ds[2]
        detCount = if (detChannelsFirst) ds[2] else ds[1]

        maskDim = detChannels - 5 // 4 box + 1 clase + coeficientes de máscara
        require(maskDim in 1..128) { "Canales de máscara inesperados: $maskDim" }

        val ps = outputShapes[protoIdx]
        protoChannelsFirst = ps[1] == maskDim
        if (protoChannelsFirst) {
            maskHeight = ps[2]
            maskWidth = ps[3]
        } else {
            require(ps[3] == maskDim) { "Prototipos inesperados: ${ps.contentToString()}" }
            maskHeight = ps[1]
            maskWidth = ps[2]
        }
        maskArea = maskWidth * maskHeight
        require(maskWidth > 0 && maskHeight > 0 && maskArea > 0) { "Máscara inválida" }

        maskPixels = IntArray(maskArea)
        positivePixels = IntArray(maskArea)
        maskBinary = ByteArray(maskArea)
        maskScratch = ByteArray(maskArea)
        maskVisited = ByteArray(maskArea)
        componentQueue = IntArray(maskArea)
        coeffScratch = FloatArray(maskDim)
        maskBitmaps = Array(3) { Bitmap.createBitmap(maskWidth, maskHeight, Bitmap.Config.ARGB_8888) }
    }

    data class Candidate(
        val sourceIndex: Int,
        val x1: Float,
        val y1: Float,
        val x2: Float,
        val y2: Float,
        val score: Float
    )

    private data class Prep(
        val scale: Float,
        val padX: Float,
        val padY: Float,
        val contentW: Int,
        val contentH: Int
    )

    private data class MaskInfo(
        val bitmap: Bitmap?,
        val ratios: FloatArray,
        val severityScores: FloatArray,
        val perspectiveFactors: FloatArray,
        val severities: Array<Severity>,
        val polygons: Array<List<PointF>>
    )

    private data class MaskStyle(
        val red: Int,
        val green: Int,
        val blue: Int,
        val fillAlpha: Int,
        val edgeAlpha: Int,
        val glowAlpha: Int
    )

    fun description(): String = buildString {
        append("YOLO26n-seg ")
        append(modelWidth).append('x').append(modelHeight)
        append(" • INT8 • XNNPACK x").append(numThreads)
        append(" • ").append(detectionSource.label)
    }

    fun infer(frame: RgbaFrame, confidence: Float, quality: PreprocessQuality = PreprocessQuality.FAST): InferenceResult {
        val totalStart = System.nanoTime()

        val preStart = System.nanoTime()
        val prep = prepareInput(frame, quality)
        val preprocessMs = elapsedMs(preStart)

        outputBuffers.forEach { it.rewind() }
        inputBuffer.rewind()
        val modelStart = System.nanoTime()
        interpreter.runForMultipleInputsOutputs(inputArray, outputMap)
        val modelMs = elapsedMs(modelStart)

        val postStart = System.nanoTime()
        val candidates = parseDetections(confidence)
        val kept = nmsAndDeduplicate(candidates)
        val maskInfo = if (kept.isNotEmpty()) composeMasks(kept, prep) else null

        val detections = ArrayList<Detection>(kept.size)
        kept.forEachIndexed { index, c ->
            val left = ((c.x1 - prep.padX) / prep.scale).coerceIn(0f, frame.width.toFloat())
            val top = ((c.y1 - prep.padY) / prep.scale).coerceIn(0f, frame.height.toFloat())
            val right = ((c.x2 - prep.padX) / prep.scale).coerceIn(0f, frame.width.toFloat())
            val bottom = ((c.y2 - prep.padY) / prep.scale).coerceIn(0f, frame.height.toFloat())
            if (right > left && bottom > top) {
                detections.add(
                    Detection(
                        box = RectF(left, top, right, bottom),
                        confidence = c.score,
                        rawConfidence = c.score,
                        severity = maskInfo?.severities?.getOrElse(index) { Severity.LOW } ?: Severity.LOW,
                        maskAreaRatio = maskInfo?.ratios?.getOrElse(index) { 0f } ?: 0f,
                        severityScore = maskInfo?.severityScores?.getOrElse(index) { 0f } ?: 0f,
                        perspectiveFactor = maskInfo?.perspectiveFactors?.getOrElse(index) { 1f } ?: 1f,
                        source = detectionSource,
                        maskPolygon = maskInfo?.polygons?.getOrElse(index) { emptyList() } ?: emptyList()
                    )
                )
            }
        }
        val postprocessMs = elapsedMs(postStart)
        val totalMs = (System.nanoTime() - totalStart) / 1_000_000L

        val sx = maskWidth.toFloat() / modelWidth.toFloat()
        val sy = maskHeight.toFloat() / modelHeight.toFloat()
        val maskLeft = floor((prep.padX * sx).toDouble()).toInt().coerceIn(0, maskWidth - 1)
        val maskTop = floor((prep.padY * sy).toDouble()).toInt().coerceIn(0, maskHeight - 1)
        val maskRight = ceil(((modelWidth - prep.padX) * sx).toDouble()).toInt().coerceIn(1, maskWidth)
        val maskBottom = ceil(((modelHeight - prep.padY) * sy).toDouble()).toInt().coerceIn(1, maskHeight)

        return InferenceResult(
            detections = detections,
            maskBitmap = maskInfo?.bitmap,
            maskCropLeft = maskLeft,
            maskCropTop = maskTop,
            maskCropRight = maskRight,
            maskCropBottom = maskBottom,
            sourceWidth = frame.width,
            sourceHeight = frame.height,
            metrics = InferenceMetrics(
                preprocessMs = preprocessMs,
                modelMs = modelMs,
                postprocessMs = postprocessMs,
                totalMs = totalMs,
                rawCandidates = candidates.size,
                keptDetections = detections.size
            )
        )
    }

    /**
     * Preprocesamiento directo RGBA -> NCHW/NHWC Float32.
     * Evita Canvas, Bitmap.getPixels() y millones de ByteBuffer.putFloat().
     */
    private fun prepareInput(frame: RgbaFrame, quality: PreprocessQuality): Prep {
        val scale = min(modelWidth.toFloat() / frame.width, modelHeight.toFloat() / frame.height)
        val contentW = (frame.width * scale).roundToInt().coerceIn(1, modelWidth)
        val contentH = (frame.height * scale).roundToInt().coerceIn(1, modelHeight)
        val padXInt = (modelWidth - contentW) / 2
        val padYInt = (modelHeight - contentH) / 2
        val pad = PAD_BYTE * INV_255
        Arrays.fill(inputFloats, pad)

        if (quality == PreprocessQuality.DETAIL || quality == PreprocessQuality.DETAIL_ENHANCED) {
            fillBilinear(
                frame, contentW, contentH, padXInt, padYInt,
                enhance = quality == PreprocessQuality.DETAIL_ENHANCED
            )
        } else {
            ensureResizeMaps(frame.width, frame.height, contentW, contentH, scale)
            fillNearest(frame, contentW, contentH, padXInt, padYInt)
        }

        inputFloatBuffer.position(0)
        inputFloatBuffer.put(inputFloats)
        inputFloatBuffer.position(0)
        inputBuffer.rewind()

        return Prep(scale, padXInt.toFloat(), padYInt.toFloat(), contentW, contentH)
    }


    private fun fillNearest(
        frame: RgbaFrame,
        contentW: Int,
        contentH: Int,
        padXInt: Int,
        padYInt: Int
    ) {
        if (inputNchw) {
            val plane = modelWidth * modelHeight
            val gBase = plane
            val bBase = plane * 2
            for (dy in 0 until contentH) {
                val srcRow = yMap[dy] * frame.width
                val dstRow = (dy + padYInt) * modelWidth + padXInt
                for (dx in 0 until contentW) {
                    val pixel = frame.pixels[srcRow + xMap[dx]]
                    val dst = dstRow + dx
                    inputFloats[dst] = ((pixel ushr 16) and 0xff) * INV_255
                    inputFloats[gBase + dst] = ((pixel ushr 8) and 0xff) * INV_255
                    inputFloats[bBase + dst] = (pixel and 0xff) * INV_255
                }
            }
        } else {
            for (dy in 0 until contentH) {
                val srcRow = yMap[dy] * frame.width
                val dstRow = (dy + padYInt) * modelWidth + padXInt
                for (dx in 0 until contentW) {
                    val pixel = frame.pixels[srcRow + xMap[dx]]
                    val dst = (dstRow + dx) * 3
                    inputFloats[dst] = ((pixel ushr 16) and 0xff) * INV_255
                    inputFloats[dst + 1] = ((pixel ushr 8) and 0xff) * INV_255
                    inputFloats[dst + 2] = (pixel and 0xff) * INV_255
                }
            }
        }
    }

    /**
     * Interpolación bilineal reservada para el ROI de largo alcance.
     * Preserva mejor bordes y texturas pequeñas que nearest-neighbour cuando un
     * bache lejano ocupa pocos píxeles en la cámara. Se usa sólo en la pasada
     * secundaria para no penalizar el camino normal.
     */
    private fun fillBilinear(
        frame: RgbaFrame,
        contentW: Int,
        contentH: Int,
        padXInt: Int,
        padYInt: Int,
        enhance: Boolean = false
    ) {
        val sx = frame.width.toFloat() / contentW.toFloat()
        val sy = frame.height.toFloat() / contentH.toFloat()
        val plane = modelWidth * modelHeight
        val gBase = plane
        val bBase = plane * 2

        for (dy in 0 until contentH) {
            val fy = ((dy + 0.5f) * sy - 0.5f).coerceIn(0f, (frame.height - 1).toFloat())
            val y0 = floor(fy.toDouble()).toInt()
            val y1 = min(y0 + 1, frame.height - 1)
            val wy = fy - y0
            val row0 = y0 * frame.width
            val row1 = y1 * frame.width
            val dstRow = (dy + padYInt) * modelWidth + padXInt

            for (dx in 0 until contentW) {
                val fx = ((dx + 0.5f) * sx - 0.5f).coerceIn(0f, (frame.width - 1).toFloat())
                val x0 = floor(fx.toDouble()).toInt()
                val x1 = min(x0 + 1, frame.width - 1)
                val wx = fx - x0

                val p00 = frame.pixels[row0 + x0]
                val p10 = frame.pixels[row0 + x1]
                val p01 = frame.pixels[row1 + x0]
                val p11 = frame.pixels[row1 + x1]

                val r00 = ((p00 ushr 16) and 0xff).toFloat()
                val r10 = ((p10 ushr 16) and 0xff).toFloat()
                val r01 = ((p01 ushr 16) and 0xff).toFloat()
                val r11 = ((p11 ushr 16) and 0xff).toFloat()
                val g00 = ((p00 ushr 8) and 0xff).toFloat()
                val g10 = ((p10 ushr 8) and 0xff).toFloat()
                val g01 = ((p01 ushr 8) and 0xff).toFloat()
                val g11 = ((p11 ushr 8) and 0xff).toFloat()
                val b00 = (p00 and 0xff).toFloat()
                val b10 = (p10 and 0xff).toFloat()
                val b01 = (p01 and 0xff).toFloat()
                val b11 = (p11 and 0xff).toFloat()

                val topR = r00 + (r10 - r00) * wx
                val botR = r01 + (r11 - r01) * wx
                val topG = g00 + (g10 - g00) * wx
                val botG = g01 + (g11 - g01) * wx
                val topB = b00 + (b10 - b00) * wx
                val botB = b01 + (b11 - b01) * wx
                var rr = topR + (botR - topR) * wy
                var gg = topG + (botG - topG) * wy
                var bb = topB + (botB - topB) * wy

                if (enhance) {
                    // Realce MUY suave para objetos pequeños: unsharp local + contraste 5%.
                    // Sólo se usa en la banda 15-20 m para no desplazar la distribución
                    // de entrada del camino normal.
                    val ix = fx.roundToInt().coerceIn(0, frame.width - 1)
                    val iy = fy.roundToInt().coerceIn(0, frame.height - 1)
                    val xl = (ix - 1).coerceAtLeast(0)
                    val xr = (ix + 1).coerceAtMost(frame.width - 1)
                    val yu = (iy - 1).coerceAtLeast(0)
                    val yd = (iy + 1).coerceAtMost(frame.height - 1)
                    val pl = frame.pixels[iy * frame.width + xl]
                    val pr = frame.pixels[iy * frame.width + xr]
                    val pu = frame.pixels[yu * frame.width + ix]
                    val pd = frame.pixels[yd * frame.width + ix]
                    val avgR = ((((pl ushr 16) and 0xff) + ((pr ushr 16) and 0xff) +
                        ((pu ushr 16) and 0xff) + ((pd ushr 16) and 0xff)) * 0.25f)
                    val avgG = ((((pl ushr 8) and 0xff) + ((pr ushr 8) and 0xff) +
                        ((pu ushr 8) and 0xff) + ((pd ushr 8) and 0xff)) * 0.25f)
                    val avgB = (((pl and 0xff) + (pr and 0xff) + (pu and 0xff) + (pd and 0xff)) * 0.25f)
                    rr = (((rr + (rr - avgR) * 0.16f) - 127.5f) * 1.05f + 127.5f).coerceIn(0f, 255f)
                    gg = (((gg + (gg - avgG) * 0.16f) - 127.5f) * 1.05f + 127.5f).coerceIn(0f, 255f)
                    bb = (((bb + (bb - avgB) * 0.16f) - 127.5f) * 1.05f + 127.5f).coerceIn(0f, 255f)
                }

                val r = rr * INV_255
                val g = gg * INV_255
                val b = bb * INV_255

                val dst = dstRow + dx
                if (inputNchw) {
                    inputFloats[dst] = r
                    inputFloats[gBase + dst] = g
                    inputFloats[bBase + dst] = b
                } else {
                    val q = dst * 3
                    inputFloats[q] = r
                    inputFloats[q + 1] = g
                    inputFloats[q + 2] = b
                }
            }
        }
    }

    private fun ensureResizeMaps(
        sourceW: Int,
        sourceH: Int,
        contentW: Int,
        contentH: Int,
        scale: Float
    ) {
        if (mapSourceW == sourceW && mapSourceH == sourceH &&
            mapContentW == contentW && mapContentH == contentH
        ) return

        if (xMap.size != contentW) xMap = IntArray(contentW)
        if (yMap.size != contentH) yMap = IntArray(contentH)

        if (contentW == sourceW) {
            for (x in 0 until contentW) xMap[x] = x
        } else {
            for (x in 0 until contentW) {
                xMap[x] = ((x + 0.5f) / scale - 0.5f).roundToInt().coerceIn(0, sourceW - 1)
            }
        }
        if (contentH == sourceH) {
            for (y in 0 until contentH) yMap[y] = y
        } else {
            for (y in 0 until contentH) {
                yMap[y] = ((y + 0.5f) / scale - 0.5f).roundToInt().coerceIn(0, sourceH - 1)
            }
        }

        mapSourceW = sourceW
        mapSourceH = sourceH
        mapContentW = contentW
        mapContentH = contentH
    }

    private fun detAt(channel: Int, index: Int): Float {
        val pos = if (detChannelsFirst) channel * detCount + index else index * detChannels + channel
        return detBuffer.get(pos)
    }

    private fun parseDetections(conf: Float): MutableList<Candidate> {
        val out = ArrayList<Candidate>(48)
        for (i in 0 until detCount) {
            val score = detAt(4, i)
            if (!score.isFinite() || score < conf) continue
            var cx = detAt(0, i)
            var cy = detAt(1, i)
            var w = detAt(2, i)
            var h = detAt(3, i)
            if (max(max(abs(cx), abs(cy)), max(abs(w), abs(h))) <= 2.5f) {
                cx *= modelWidth
                cy *= modelHeight
                w *= modelWidth
                h *= modelHeight
            }
            val x1 = (cx - w * 0.5f).coerceIn(0f, modelWidth.toFloat())
            val y1 = (cy - h * 0.5f).coerceIn(0f, modelHeight.toFloat())
            val x2 = (cx + w * 0.5f).coerceIn(0f, modelWidth.toFloat())
            val y2 = (cy + h * 0.5f).coerceIn(0f, modelHeight.toFloat())
            if (x2 <= x1 || y2 <= y1) continue
            out.add(Candidate(i, x1, y1, x2, y2, score))
        }
        out.sortByDescending { it.score }
        if (out.size > MAX_CANDIDATES_FOR_NMS) {
            out.subList(MAX_CANDIDATES_FOR_NMS, out.size).clear()
        }
        return out
    }

    /**
     * NMS para clase única. Además de IoU usa intersección / área del box pequeño.
     * Esto elimina mejor las cajas múltiples que el modelo puede producir sobre un
     * mismo bache irregular, sin fusionar cajas vecinas que apenas se tocan.
     */
    private fun nmsAndDeduplicate(input: List<Candidate>): List<Candidate> {
        val out = ArrayList<Candidate>(MAX_DET)
        for (c in input) {
            var duplicate = false
            for (k in out) {
                if (isDuplicateCandidate(c, k)) {
                    duplicate = true
                    break
                }
            }
            if (!duplicate) out.add(c)
            if (out.size >= MAX_DET) break
        }
        return out
    }

    /**
     * Supresión conservadora para una sola clase. Además de IoU/intersección, detecta
     * cajas casi concéntricas que suelen ser propuestas múltiples del mismo bache.
     */
    private fun isDuplicateCandidate(a: Candidate, b: Candidate): Boolean {
        val l = max(a.x1, b.x1)
        val t = max(a.y1, b.y1)
        val r = min(a.x2, b.x2)
        val bot = min(a.y2, b.y2)
        val inter = max(0f, r - l) * max(0f, bot - t)
        if (inter <= 0f) return false

        val aw = max(1e-6f, a.x2 - a.x1)
        val ah = max(1e-6f, a.y2 - a.y1)
        val bw = max(1e-6f, b.x2 - b.x1)
        val bh = max(1e-6f, b.y2 - b.y1)
        val areaA = aw * ah
        val areaB = bw * bh
        val iou = inter / (areaA + areaB - inter + 1e-6f)
        if (iou >= IOU_THRESHOLD) return true

        val overlapSmall = inter / min(areaA, areaB)
        if (overlapSmall >= OVERLAP_SMALL_THRESHOLD) return true

        val acx = (a.x1 + a.x2) * 0.5f
        val acy = (a.y1 + a.y2) * 0.5f
        val bcx = (b.x1 + b.x2) * 0.5f
        val bcy = (b.y1 + b.y2) * 0.5f
        val dx = acx - bcx
        val dy = acy - bcy
        val centerDist = kotlin.math.sqrt(dx * dx + dy * dy)
        val minDiag = min(
            kotlin.math.sqrt(aw * aw + ah * ah),
            kotlin.math.sqrt(bw * bw + bh * bh)
        ).coerceAtLeast(1f)
        val centerNorm = centerDist / minDiag
        val areaSimilarity = min(areaA, areaB) / max(areaA, areaB)

        return overlapSmall >= 0.36f && centerNorm <= 0.30f && areaSimilarity >= 0.25f
    }

    private fun protoAt(ch: Int, y: Int, x: Int): Float {
        val pos = if (protoChannelsFirst) {
            (ch * maskHeight + y) * maskWidth + x
        } else {
            (y * maskWidth + x) * maskDim + ch
        }
        return protoBuffer.get(pos)
    }

    /**
     * Compone máscaras profesionales a resolución nativa de prototipos.
     *
     * La salida evita la apariencia de "nube" de la máscara cruda. Para cada detección:
     * 1) binariza la máscara logits->binaria;
     * 2) cierra y rellena huecos internos;
     * 3) conserva sólo el componente principal;
     * 4) suaviza bordes con mayoría conservadora y un cierre final;
     * 5) dibuja un relleno uniforme + borde sólido + halo externo sutil.
     *
     * Todo ocurre sobre la máscara pequeña del modelo (104/128 px aprox.), por lo que
     * el coste sigue siendo mínimo frente a la inferencia TFLite.
     */
    private fun composeMasks(dets: List<Candidate>, prep: Prep): MaskInfo {
        val ratios = FloatArray(dets.size)
        val severityScores = FloatArray(dets.size)
        val perspectiveFactors = FloatArray(dets.size) { 1f }
        val severities = Array(dets.size) { Severity.LOW }
        val polygons = Array<List<PointF>>(dets.size) { emptyList() }

        val sx = maskWidth.toFloat() / modelWidth.toFloat()
        val sy = maskHeight.toFloat() / modelHeight.toFloat()
        val contentWMask = (prep.contentW * sx).coerceAtLeast(1f)
        val contentHMask = (prep.contentH * sy).coerceAtLeast(1f)
        val contentArea = contentWMask * contentHMask

        for ((index, d) in dets.withIndex()) {
            for (k in 0 until maskDim) coeffScratch[k] = detAt(5 + k, d.sourceIndex)

            val x1 = floor((d.x1 * sx).toDouble()).toInt().coerceIn(0, maskWidth - 1)
            val y1 = floor((d.y1 * sy).toDouble()).toInt().coerceIn(0, maskHeight - 1)
            val x2 = ceil((d.x2 * sx).toDouble()).toInt().coerceIn(1, maskWidth)
            val y2 = ceil((d.y2 * sy).toDouble()).toInt().coerceIn(1, maskHeight)

            maskBinary.fill(0)
            var rawPositive = 0
            val probabilityThreshold = maskProbabilityThreshold(d.score, detectionSource)
            val logitThreshold = ln((probabilityThreshold / (1f - probabilityThreshold)).toDouble()).toFloat()
            for (y in y1 until y2) {
                for (x in x1 until x2) {
                    var logit = 0f
                    for (k in 0 until maskDim) logit += coeffScratch[k] * protoAt(k, y, x)
                    if (logit > logitThreshold) {
                        maskBinary[y * maskWidth + x] = 1
                        rawPositive++
                    }
                }
            }

            if (rawPositive > 0) {
                val bw = x2 - x1
                val bh = y2 - y1

                if (rawPositive >= 5 && bw >= 4 && bh >= 4) {
                    closeBinaryMask(x1, y1, x2, y2)
                    fillInteriorHoles(x1, y1, x2, y2)
                }

                var positiveCount = keepLargestComponent(x1, y1, x2, y2)

                if (positiveCount >= 12 && bw >= 5 && bh >= 5) {
                    majoritySmooth(x1, y1, x2, y2)
                    closeBinaryMask(x1, y1, x2, y2)
                    fillInteriorHoles(x1, y1, x2, y2)
                    positiveCount = keepLargestComponent(x1, y1, x2, y2)
                }

                // Opening sólo en máscaras con suficiente área: quita filamentos y puntos
                // sin destruir baches pequeños/lejanos.
                if (positiveCount >= 20 && bw >= 7 && bh >= 7) {
                    openBinaryMask(x1, y1, x2, y2)
                    positiveCount = keepLargestComponent(x1, y1, x2, y2)
                }

                if (positiveCount >= 28 && bw >= 7 && bh >= 7) {
                    majoritySmooth(x1, y1, x2, y2)
                    closeBinaryMask(x1, y1, x2, y2)
                    positiveCount = keepLargestComponent(x1, y1, x2, y2)
                }

                val ratio = (positiveCount / contentArea).coerceIn(0f, 1f)
                val bottomNorm = ((d.y2 - prep.padY) / prep.contentH.toFloat()).coerceIn(0f, 1f)
                val measurement = SeverityEstimator.measure(ratio, bottomNorm)
                val severity = measurement.severity
                ratios[index] = measurement.rawAreaRatio
                severityScores[index] = measurement.compensatedAreaRatio
                perspectiveFactors[index] = measurement.perspectiveFactor
                severities[index] = severity

                polygons[index] = extractScientificPolygon(
                    prep = prep,
                    x1 = x1,
                    y1 = y1,
                    x2 = x2,
                    y2 = y2,
                    positiveCount = positiveCount
                )

                // V15 renderiza el contorno como Path vectorial. No se rasteriza aquí,
                // evitando blur por reescalado y reduciendo trabajo de postproceso.
            }
        }

        return MaskInfo(null, ratios, severityScores, perspectiveFactors, severities, polygons)
    }

    private fun maskProbabilityThreshold(score: Float, source: DetectionSource): Float {
        val base = when (source) {
            DetectionSource.PRIMARY_416 -> 0.52f
            DetectionSource.FAR_416 -> 0.54f
            DetectionSource.FAR_512 -> 0.54f
        }
        val extra = when {
            score < 0.35f -> 0.035f
            score < 0.45f -> 0.020f
            else -> 0f
        }
        return (base + extra).coerceIn(0.50f, 0.59f)
    }

    /**
     * Extrae un contorno vectorial limpio y de número de puntos estable.
     *
     * En vez de ampliar los píxeles de la máscara nativa (que produce bordes borrosos),
     * se muestrean los puntos de frontera en 24 sectores angulares alrededor del centro
     * del componente principal. Los sectores vacíos se interpolan y se aplica una pasada
     * de Chaikin. El resultado final se expresa en coordenadas del frame fuente y puede
     * renderizarse con Canvas/Path con un borde nítido a cualquier resolución.
     */
    private fun extractScientificPolygon(
        prep: Prep,
        x1: Int,
        y1: Int,
        x2: Int,
        y2: Int,
        positiveCount: Int
    ): List<PointF> {
        if (positiveCount < 5) return emptyList()

        var cx = 0f
        var cy = 0f
        for (i in 0 until positiveCount) {
            val pos = positivePixels[i]
            cx += (pos % maskWidth) + 0.5f
            cy += (pos / maskWidth) + 0.5f
        }
        cx /= positiveCount.toFloat()
        cy /= positiveCount.toFloat()

        val bins = 24
        val bestX = FloatArray(bins) { Float.NaN }
        val bestY = FloatArray(bins) { Float.NaN }
        val bestR2 = FloatArray(bins) { -1f }
        var valid = 0

        for (i in 0 until positiveCount) {
            val pos = positivePixels[i]
            val x = pos % maskWidth
            val y = pos / maskWidth
            if (!isBoundaryPixel(x, y, x1, y1, x2, y2)) continue
            val px = x + 0.5f
            val py = y + 0.5f
            val dx = px - cx
            val dy = py - cy
            var angle = atan2(dy, dx)
            if (angle < 0f) angle += (Math.PI * 2.0).toFloat()
            val bin = ((angle / (Math.PI * 2.0).toFloat()) * bins).toInt().coerceIn(0, bins - 1)
            val r2 = dx * dx + dy * dy
            if (r2 > bestR2[bin]) {
                if (bestR2[bin] < 0f) valid++
                bestR2[bin] = r2
                bestX[bin] = px
                bestY[bin] = py
            }
        }
        if (valid < 5) return emptyList()

        // Interpola sectores faltantes entre el vecino válido anterior y siguiente.
        for (i in 0 until bins) {
            if (!bestX[i].isNaN()) continue
            var prev = -1
            for (step in 1..bins) {
                val candidate = (i - step + bins * 2) % bins
                if (!bestX[candidate].isNaN()) { prev = candidate; break }
            }
            var next = -1
            for (step in 1..bins) {
                val candidate = (i + step) % bins
                if (!bestX[candidate].isNaN()) { next = candidate; break }
            }
            if (prev < 0 || next < 0) continue
            var span = (next - prev + bins) % bins
            if (span == 0) span = bins
            val offset = (i - prev + bins) % bins
            val t = offset.toFloat() / span.toFloat()
            bestX[i] = bestX[prev] * (1f - t) + bestX[next] * t
            bestY[i] = bestY[prev] * (1f - t) + bestY[next] * t
        }

        val raw = ArrayList<PointF>(bins)
        for (i in 0 until bins) {
            if (bestX[i].isNaN()) continue
            raw.add(PointF(bestX[i], bestY[i]))
        }
        if (raw.size < 5) return emptyList()

        val smooth = chaikinClosed(raw)
        val sx = maskWidth.toFloat() / modelWidth.toFloat()
        val sy = maskHeight.toFloat() / modelHeight.toFloat()
        return smooth.map { p ->
            val modelX = p.x / sx
            val modelY = p.y / sy
            PointF(
                ((modelX - prep.padX) / prep.scale).coerceIn(0f, ((modelWidth - 2f * prep.padX) / prep.scale).coerceAtLeast(1f)),
                ((modelY - prep.padY) / prep.scale).coerceIn(0f, ((modelHeight - 2f * prep.padY) / prep.scale).coerceAtLeast(1f))
            )
        }
    }

    private fun chaikinClosed(input: List<PointF>): List<PointF> {
        if (input.size < 4) return input
        val out = ArrayList<PointF>(input.size * 2)
        for (i in input.indices) {
            val a = input[i]
            val b = input[(i + 1) % input.size]
            out.add(PointF(a.x * 0.75f + b.x * 0.25f, a.y * 0.75f + b.y * 0.25f))
            out.add(PointF(a.x * 0.25f + b.x * 0.75f, a.y * 0.25f + b.y * 0.75f))
        }
        return out
    }

    private fun maskStyleFor(severity: Severity, source: DetectionSource): MaskStyle {
        val (r, g, b) = when (severity) {
            Severity.LOW -> Triple(36, 244, 168)
            Severity.MEDIUM -> Triple(255, 210, 68)
            Severity.HIGH -> Triple(255, 96, 112)
        }
        val fillAlpha = when (source) {
            DetectionSource.PRIMARY_416 -> 78
            DetectionSource.FAR_416 -> 64
            DetectionSource.FAR_512 -> 58
        }
        val edgeAlpha = when (source) {
            DetectionSource.PRIMARY_416 -> 228
            DetectionSource.FAR_416 -> 214
            DetectionSource.FAR_512 -> 208
        }
        val glowAlpha = when (source) {
            DetectionSource.PRIMARY_416 -> 118
            DetectionSource.FAR_416 -> 102
            DetectionSource.FAR_512 -> 96
        }
        return MaskStyle(r, g, b, fillAlpha, edgeAlpha, glowAlpha)
    }

    private fun paintStyledMask(x1: Int, y1: Int, x2: Int, y2: Int, style: MaskStyle) {
        for (y in y1 until y2) {
            for (x in x1 until x2) {
                val pos = y * maskWidth + x
                if (maskBinary[pos].toInt() != 0) {
                    val alpha = when {
                        isBoundaryPixel(x, y, x1, y1, x2, y2) -> style.edgeAlpha
                        isNearBoundaryPixel(x, y, x1, y1, x2, y2) -> ((style.fillAlpha * 2 + style.edgeAlpha) / 3)
                        else -> style.fillAlpha
                    }
                    paintMaxAlpha(pos, style.red, style.green, style.blue, alpha)
                } else if (isOuterGlowPixel(x, y, x1, y1, x2, y2)) {
                    paintMaxAlpha(pos, style.red, style.green, style.blue, style.glowAlpha)
                }
            }
        }
    }

    private fun paintMaxAlpha(pos: Int, r: Int, g: Int, b: Int, alpha: Int) {
        val current = maskPixels[pos]
        val currentAlpha = Color.alpha(current)
        if (alpha >= currentAlpha) {
            maskPixels[pos] = Color.argb(alpha, r, g, b)
        }
    }

    private fun isNearBoundaryPixel(x: Int, y: Int, x1: Int, y1: Int, x2: Int, y2: Int): Boolean {
        for (dy in -1..1) {
            val yy = y + dy
            if (yy !in y1 until y2) continue
            for (dx in -1..1) {
                val xx = x + dx
                if (xx !in x1 until x2) continue
                if (maskBinary[yy * maskWidth + xx].toInt() == 0) return true
            }
        }
        return false
    }

    private fun isOuterGlowPixel(x: Int, y: Int, x1: Int, y1: Int, x2: Int, y2: Int): Boolean {
        val left = (x - 1).coerceAtLeast(x1)
        val right = (x + 1).coerceAtMost(x2 - 1)
        val top = (y - 1).coerceAtLeast(y1)
        val bottom = (y + 1).coerceAtMost(y2 - 1)
        for (yy in top..bottom) {
            for (xx in left..right) {
                if (maskBinary[yy * maskWidth + xx].toInt() != 0) return true
            }
        }
        return false
    }

    private fun fillInteriorHoles(x1: Int, y1: Int, x2: Int, y2: Int) {
        maskVisited.fill(0)
        var head = 0
        var tail = 0

        fun enqueueIfBackground(xx: Int, yy: Int) {
            val p = yy * maskWidth + xx
            if (maskBinary[p].toInt() == 0 && maskVisited[p].toInt() == 0) {
                maskVisited[p] = 1
                componentQueue[tail++] = p
            }
        }

        for (x in x1 until x2) {
            enqueueIfBackground(x, y1)
            enqueueIfBackground(x, y2 - 1)
        }
        for (y in y1 until y2) {
            enqueueIfBackground(x1, y)
            enqueueIfBackground(x2 - 1, y)
        }

        while (head < tail) {
            val pos = componentQueue[head++]
            val px = pos % maskWidth
            val py = pos / maskWidth
            val left = max(px - 1, x1)
            val right = min(px + 1, x2 - 1)
            val top = max(py - 1, y1)
            val bottom = min(py + 1, y2 - 1)
            for (yy in top..bottom) {
                for (xx in left..right) {
                    val np = yy * maskWidth + xx
                    if (maskBinary[np].toInt() == 0 && maskVisited[np].toInt() == 0) {
                        maskVisited[np] = 1
                        componentQueue[tail++] = np
                    }
                }
            }
        }

        for (y in y1 until y2) {
            for (x in x1 until x2) {
                val p = y * maskWidth + x
                if (maskBinary[p].toInt() == 0 && maskVisited[p].toInt() == 0) {
                    maskBinary[p] = 1
                }
            }
        }
    }

    /** 3x3 open = erosión + dilatación para remover puntos/filamentos aislados. */
    private fun openBinaryMask(x1: Int, y1: Int, x2: Int, y2: Int) {
        maskScratch.fill(0)
        // erosión
        for (y in y1 until y2) {
            for (x in x1 until x2) {
                var on = true
                loop@ for (dy in -1..1) {
                    val yy = y + dy
                    if (yy !in y1 until y2) { on = false; break@loop }
                    for (dx in -1..1) {
                        val xx = x + dx
                        if (xx !in x1 until x2 || maskBinary[yy * maskWidth + xx].toInt() == 0) {
                            on = false
                            break@loop
                        }
                    }
                }
                if (on) maskScratch[y * maskWidth + x] = 1
            }
        }
        // dilatación
        for (y in y1 until y2) {
            for (x in x1 until x2) {
                var on = false
                loop@ for (dy in -1..1) {
                    val yy = y + dy
                    if (yy !in y1 until y2) continue
                    for (dx in -1..1) {
                        val xx = x + dx
                        if (xx !in x1 until x2) continue
                        if (maskScratch[yy * maskWidth + xx].toInt() != 0) {
                            on = true
                            break@loop
                        }
                    }
                }
                maskBinary[y * maskWidth + x] = if (on) 1 else 0
            }
        }
    }

    /** 3x3 close = dilatación + erosión. Sólo se opera dentro del bbox. */
    private fun closeBinaryMask(x1: Int, y1: Int, x2: Int, y2: Int) {
        maskScratch.fill(0)
        for (y in y1 until y2) {
            for (x in x1 until x2) {
                var on = false
                loop@ for (dy in -1..1) {
                    val yy = y + dy
                    if (yy !in y1 until y2) continue
                    for (dx in -1..1) {
                        val xx = x + dx
                        if (xx !in x1 until x2) continue
                        if (maskBinary[yy * maskWidth + xx].toInt() != 0) {
                            on = true
                            break@loop
                        }
                    }
                }
                if (on) maskScratch[y * maskWidth + x] = 1
            }
        }
        for (y in y1 until y2) {
            for (x in x1 until x2) {
                var on = true
                loop@ for (dy in -1..1) {
                    val yy = y + dy
                    if (yy !in y1 until y2) continue
                    for (dx in -1..1) {
                        val xx = x + dx
                        if (xx !in x1 until x2) continue
                        if (maskScratch[yy * maskWidth + xx].toInt() == 0) {
                            on = false
                            break@loop
                        }
                    }
                }
                maskBinary[y * maskWidth + x] = if (on) 1 else 0
            }
        }
    }

    /** Suavizado 3x3 conservador para quitar dientes aislados sin borrar baches pequeños. */
    private fun majoritySmooth(x1: Int, y1: Int, x2: Int, y2: Int) {
        maskScratch.fill(0)
        for (y in y1 until y2) {
            for (x in x1 until x2) {
                var neighbours = 0
                for (dy in -1..1) {
                    val yy = y + dy
                    if (yy !in y1 until y2) continue
                    for (dx in -1..1) {
                        val xx = x + dx
                        if (xx !in x1 until x2) continue
                        if (maskBinary[yy * maskWidth + xx].toInt() != 0) neighbours++
                    }
                }
                val current = maskBinary[y * maskWidth + x].toInt() != 0
                if (neighbours >= 5 || (current && neighbours >= 4)) {
                    maskScratch[y * maskWidth + x] = 1
                }
            }
        }
        for (y in y1 until y2) {
            val row = y * maskWidth
            for (x in x1 until x2) maskBinary[row + x] = maskScratch[row + x]
        }
    }

    /**
     * Conserva sólo la región conectada dominante (8-conectividad). Quita islotes que
     * son la principal causa del aspecto de nube en la segmentación cruda.
     * Devuelve el número de píxeles y deja sus índices en positivePixels[0..n).
     */
    private fun keepLargestComponent(x1: Int, y1: Int, x2: Int, y2: Int): Int {
        maskVisited.fill(0)
        var bestCount = 0

        for (y in y1 until y2) {
            for (x in x1 until x2) {
                val start = y * maskWidth + x
                if (maskBinary[start].toInt() == 0 || maskVisited[start].toInt() != 0) continue

                var head = 0
                var tail = 0
                componentQueue[tail++] = start
                maskVisited[start] = 1

                while (head < tail) {
                    val pos = componentQueue[head++]
                    val px = pos % maskWidth
                    val py = pos / maskWidth
                    for (dy in -1..1) {
                        val yy = py + dy
                        if (yy !in y1 until y2) continue
                        for (dx in -1..1) {
                            if (dx == 0 && dy == 0) continue
                            val xx = px + dx
                            if (xx !in x1 until x2) continue
                            val np = yy * maskWidth + xx
                            if (maskBinary[np].toInt() != 0 && maskVisited[np].toInt() == 0) {
                                maskVisited[np] = 1
                                componentQueue[tail++] = np
                            }
                        }
                    }
                }

                if (tail > bestCount) {
                    bestCount = tail
                    System.arraycopy(componentQueue, 0, positivePixels, 0, tail)
                }
            }
        }

        // Reescribe el binario únicamente con el componente ganador.
        for (y in y1 until y2) {
            val row = y * maskWidth
            for (x in x1 until x2) maskBinary[row + x] = 0
        }
        for (i in 0 until bestCount) maskBinary[positivePixels[i]] = 1
        return bestCount
    }

    private fun isBoundaryPixel(x: Int, y: Int, x1: Int, y1: Int, x2: Int, y2: Int): Boolean {
        if (x <= x1 || y <= y1 || x >= x2 - 1 || y >= y2 - 1) return true
        val p = y * maskWidth + x
        return maskBinary[p - 1].toInt() == 0 ||
            maskBinary[p + 1].toInt() == 0 ||
            maskBinary[p - maskWidth].toInt() == 0 ||
            maskBinary[p + maskWidth].toInt() == 0
    }

    private fun elapsedMs(startNs: Long): Long = (System.nanoTime() - startNs) / 1_000_000L

    override fun close() {
        interpreter.close()
        maskBitmaps.forEach { it.recycle() }
    }
}
