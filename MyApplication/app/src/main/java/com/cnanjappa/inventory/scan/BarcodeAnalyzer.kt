package com.cnanjappa.inventory.scan

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.cnanjappa.inventory.domain.ScannedCode
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage

/**
 * Decodes each analysis frame with the bundled ML Kit barcode model (offline, no Play services). It
 * reads tilted, slightly blurred and on-screen labels in any orientation, so a label is usually picked
 * up within a frame or two. Only codes whose centre is inside the on-screen scan box count. Reports
 * every frame: an empty list means "no label visible", which the scanner uses to know a held label
 * has left the frame.
 */
class BarcodeAnalyzer(
    private val minIntervalMs: () -> Long,
    private val onFrame: (List<ScannedCode>) -> Unit,
) : ImageAnalysis.Analyzer {
    private val scanner = newScanner()
    private var lastRun = 0L

    @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
    override fun analyze(image: ImageProxy) {
        try {
            val now = System.currentTimeMillis()
            if (now - lastRun < minIntervalMs()) return
            lastRun = now
            val media = image.image ?: return
            val rot = image.imageInfo.rotationDegrees
            val found = Tasks.await(scanner.process(InputImage.fromMediaImage(media, rot)))
            // ML Kit reports boxes in the upright image.
            val w = if (rot % 180 == 0) image.width else image.height
            val h = if (rot % 180 == 0) image.height else image.width
            onFrame(inScanBox(found, w, h))
        } catch (_: Exception) {
            // A bad frame must never stop scanning; the next frame is tried.
        } finally {
            image.close()
        }
    }

    fun close() = scanner.close()

    companion object {
        private val FORMATS = mapOf(
            Barcode.FORMAT_EAN_13 to "EAN_13", Barcode.FORMAT_EAN_8 to "EAN_8",
            Barcode.FORMAT_UPC_A to "UPC_A", Barcode.FORMAT_UPC_E to "UPC_E",
            Barcode.FORMAT_CODE_128 to "CODE_128", Barcode.FORMAT_CODE_39 to "CODE_39",
            Barcode.FORMAT_QR_CODE to "QR_CODE", Barcode.FORMAT_DATA_MATRIX to "DATA_MATRIX",
        )

        fun newScanner(): BarcodeScanner = BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_EAN_13, Barcode.FORMAT_EAN_8, Barcode.FORMAT_UPC_A, Barcode.FORMAT_UPC_E,
                    Barcode.FORMAT_CODE_128, Barcode.FORMAT_CODE_39, Barcode.FORMAT_QR_CODE, Barcode.FORMAT_DATA_MATRIX)
                .build(),
        )

        /**
         * Scan box: centre 80% width x 50% height of the upright frame (matches the on-screen frame).
         * Format names stay ZXing's, as stored barcodes and lookup keys use them.
         */
        fun inScanBox(found: List<Barcode>, w: Int, h: Int): List<ScannedCode> = found.mapNotNull { b ->
            val raw = b.rawValue ?: return@mapNotNull null
            val format = FORMATS[b.format] ?: return@mapNotNull null
            val box = b.boundingBox
            if (box != null && (box.exactCenterX() !in w * 0.1f..w * 0.9f || box.exactCenterY() !in h * 0.25f..h * 0.75f)) return@mapNotNull null
            ScannedCode(raw, format)
        }.distinctBy { it.key }
    }
}
