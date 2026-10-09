package com.cnanjappa.inventory

import android.graphics.BitmapFactory
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cnanjappa.inventory.scan.BarcodeAnalyzer
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.ReaderException
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real camera frames of a shop label (tilted, shown on a laptop screen, recorded on a Galaxy M32),
 * cropped to the scan box. The scanner engine must read the label in clearly more of these frames
 * than the previous ZXing setup did (Galaxy M32, 65 frames: ML Kit 3, ZXing 0).
 */
@RunWith(AndroidJUnit4::class)
class ScanEngineTest {
    private val assets = InstrumentationRegistry.getInstrumentation().context.assets
    private val frames = assets.list("scanframes")!!.sorted().map { name ->
        assets.open("scanframes/$name").use { BitmapFactory.decodeStream(it) }
    }

    @Test fun mlKitReadsRealLabelFramesBetterThanZxing() {
        val expected = "CNLUQKY-2"
        val scanner = BarcodeAnalyzer.newScanner()
        var mlHits = 0; var mlNs = 0L
        for (bmp in frames) {
            val t = System.nanoTime()
            val found = Tasks.await(scanner.process(InputImage.fromBitmap(bmp, 0)))
            mlNs += System.nanoTime() - t
            if (found.any { it.rawValue == expected }) mlHits++
        }
        scanner.close()

        // Previous engine, same settings as the old analyser (best case: every frame in the right orientation).
        val reader = MultiFormatReader().apply {
            setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(
                BarcodeFormat.EAN_13, BarcodeFormat.EAN_8, BarcodeFormat.UPC_A, BarcodeFormat.UPC_E,
                BarcodeFormat.CODE_128, BarcodeFormat.CODE_39, BarcodeFormat.QR_CODE, BarcodeFormat.DATA_MATRIX,
            )))
        }
        var zxHits = 0; var zxNs = 0L
        for (bmp in frames) {
            val px = IntArray(bmp.width * bmp.height).also { bmp.getPixels(it, 0, bmp.width, 0, 0, bmp.width, bmp.height) }
            val t = System.nanoTime()
            val text = try {
                reader.decodeWithState(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(bmp.width, bmp.height, px)))).text
            } catch (_: ReaderException) { null } finally { reader.reset() }
            zxNs += System.nanoTime() - t
            if (text == expected) zxHits++
        }

        val n = frames.size
        Log.i("SCAN_ENGINE", "frames=$n mlkit=$mlHits (${mlNs / n / 1_000_000} ms/frame) zxing=$zxHits (${zxNs / n / 1_000_000} ms/frame)")
        assertTrue("ML Kit $mlHits vs ZXing $zxHits of $n frames", mlHits > zxHits)
    }
}
