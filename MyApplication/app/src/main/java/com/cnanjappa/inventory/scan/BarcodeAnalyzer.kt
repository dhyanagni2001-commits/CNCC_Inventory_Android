package com.cnanjappa.inventory.scan

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.cnanjappa.inventory.domain.ScannedCode
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.multi.GenericMultipleBarcodeReader

/**
 * Decodes the scan-box region of each analysis frame with the bundled ZXing core. Works directly on
 * the Y plane into one reused buffer (no per-frame bitmaps). Odd frames are transposed so 1D labels
 * held sideways still read. Reports every frame: an empty list means "no label visible", which the
 * scanner uses to know a held label has left the frame.
 */
class BarcodeAnalyzer(
    private val minIntervalMs: () -> Long,
    private val onFrame: (List<ScannedCode>) -> Unit,
) : ImageAnalysis.Analyzer {
    private val hints = mapOf(
        DecodeHintType.POSSIBLE_FORMATS to FORMATS,
        DecodeHintType.CHARACTER_SET to "UTF-8",
    )
    private val reader = MultiFormatReader().apply { setHints(hints) }
    private val multi = GenericMultipleBarcodeReader(reader)
    private var buffer = ByteArray(0)
    private var lastRun = 0L
    private var frame = 0L

    override fun analyze(image: ImageProxy) {
        try {
            val now = System.currentTimeMillis()
            if (now - lastRun < minIntervalMs()) return
            lastRun = now
            onFrame(decode(image, transpose = (frame++ % 2L) == 1L))
        } catch (_: Exception) {
            // A bad frame must never stop scanning; the next frame is tried.
        } finally {
            image.close()
        }
    }

    private fun decode(image: ImageProxy, transpose: Boolean): List<ScannedCode> {
        val plane = image.planes[0]
        val data = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val w = image.width
        val h = image.height
        val rot = (image.imageInfo.rotationDegrees + if (transpose) 90 else 0) % 360
        // Scan box in display orientation: centre 80% width x 50% height (matches the on-screen frame).
        val dispW = if (rot % 180 == 0) w else h
        val dispH = if (rot % 180 == 0) h else w
        val boxW = (dispW * 0.8f).toInt()
        val boxH = (dispH * 0.5f).toInt()
        val x0 = (dispW - boxW) / 2
        val y0 = (dispH - boxH) / 2
        if (buffer.size < boxW * boxH) buffer = ByteArray(boxW * boxH)
        var o = 0
        for (dy in 0 until boxH) {
            val y = y0 + dy
            for (dx in 0 until boxW) {
                val x = x0 + dx
                val bx: Int
                val by: Int
                when (rot) {
                    90 -> { bx = y; by = h - 1 - x }
                    180 -> { bx = w - 1 - x; by = h - 1 - y }
                    270 -> { bx = w - 1 - y; by = x }
                    else -> { bx = x; by = y }
                }
                buffer[o++] = data.get(by * rowStride + bx * pixelStride)
            }
        }
        val bitmap = BinaryBitmap(HybridBinarizer(PlanarYUVLuminanceSource(buffer, boxW, boxH, 0, 0, boxW, boxH, false)))
        return try {
            val first = reader.decodeWithState(bitmap)
            // Only when something was found: check for other labels in view so two labels never sell.
            val all = try { multi.decodeMultiple(bitmap, hints).toList() } catch (_: NotFoundException) { listOf(first) }
            (all + first).map { ScannedCode(it.text, it.barcodeFormat.name) }.distinctBy { it.key }
        } catch (_: ReaderException) {
            emptyList()
        } finally {
            reader.reset()
        }
    }

    companion object {
        val FORMATS = listOf(
            BarcodeFormat.EAN_13, BarcodeFormat.EAN_8, BarcodeFormat.UPC_A, BarcodeFormat.UPC_E,
            BarcodeFormat.CODE_128, BarcodeFormat.CODE_39, BarcodeFormat.QR_CODE, BarcodeFormat.DATA_MATRIX,
        )
    }
}
