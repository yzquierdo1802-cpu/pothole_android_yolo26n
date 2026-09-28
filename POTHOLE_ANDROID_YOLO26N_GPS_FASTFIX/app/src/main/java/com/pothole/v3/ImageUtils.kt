package com.pothole.v3

import androidx.camera.core.ImageProxy

/**
 * Frame RGBA ya convertido por CameraX.
 *
 * El IntArray es reutilizado por RgbaFrameConverter. MainActivity mantiene un único
 * frame en procesamiento (busy=true), por lo que no se modifica mientras YOLO lo usa.
 */
data class RgbaFrame(
    val pixels: IntArray,
    val width: Int,
    val height: Int
)

/**
 * CameraX hace YUV -> RGBA en código nativo. Aquí únicamente copiamos el plano RGBA
 * a un IntArray ARGB reutilizable. No se crea Bitmap por frame.
 */
class RgbaFrameConverter : AutoCloseable {
    private var pixels = IntArray(0)
    private var rotatedPixels = IntArray(0)
    private var rowBytes = ByteArray(0)

    fun convert(image: ImageProxy): RgbaFrame {
        require(image.planes.isNotEmpty()) { "ImageProxy sin plano RGBA" }
        val crop = image.cropRect
        val width = crop.width()
        val height = crop.height()
        require(width > 0 && height > 0) { "Crop inválido: $crop" }

        val plane = image.planes[0]
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        require(pixelStride >= 4) { "pixelStride RGBA inesperado: $pixelStride" }

        val count = width * height
        if (pixels.size != count) pixels = IntArray(count)
        val rowNeed = width * pixelStride
        if (rowBytes.size < rowNeed) rowBytes = ByteArray(rowNeed)

        val buffer = plane.buffer.duplicate()
        var out = 0
        for (y in 0 until height) {
            val rowStart = (crop.top + y) * rowStride + crop.left * pixelStride
            buffer.position(rowStart)
            buffer.get(rowBytes, 0, rowNeed)
            var offset = 0
            for (x in 0 until width) {
                val r = rowBytes[offset].toInt() and 0xff
                val g = rowBytes[offset + 1].toInt() and 0xff
                val b = rowBytes[offset + 2].toInt() and 0xff
                pixels[out++] = (0xff shl 24) or (r shl 16) or (g shl 8) or b
                offset += pixelStride
            }
        }

        // setOutputImageRotationEnabled(true) debe entregar rotationDegrees=0.
        // Se conserva este fallback sin crear Bitmaps para OEMs poco habituales.
        return when (val rotation = ((image.imageInfo.rotationDegrees % 360) + 360) % 360) {
            0 -> RgbaFrame(pixels, width, height)
            90 -> rotate90(width, height)
            180 -> rotate180(width, height)
            270 -> rotate270(width, height)
            else -> RgbaFrame(pixels, width, height)
        }
    }

    private fun ensureRotated(size: Int): IntArray {
        if (rotatedPixels.size != size) rotatedPixels = IntArray(size)
        return rotatedPixels
    }

    private fun rotate90(width: Int, height: Int): RgbaFrame {
        val dst = ensureRotated(width * height)
        val newW = height
        val newH = width
        for (y in 0 until height) {
            for (x in 0 until width) {
                val nx = height - 1 - y
                val ny = x
                dst[ny * newW + nx] = pixels[y * width + x]
            }
        }
        return RgbaFrame(dst, newW, newH)
    }

    private fun rotate180(width: Int, height: Int): RgbaFrame {
        val dst = ensureRotated(width * height)
        val size = width * height
        for (i in 0 until size) dst[size - 1 - i] = pixels[i]
        return RgbaFrame(dst, width, height)
    }

    private fun rotate270(width: Int, height: Int): RgbaFrame {
        val dst = ensureRotated(width * height)
        val newW = height
        val newH = width
        for (y in 0 until height) {
            for (x in 0 until width) {
                val nx = y
                val ny = width - 1 - x
                dst[ny * newW + nx] = pixels[y * width + x]
            }
        }
        return RgbaFrame(dst, newW, newH)
    }

    override fun close() {
        pixels = IntArray(0)
        rotatedPixels = IntArray(0)
        rowBytes = ByteArray(0)
    }
}
