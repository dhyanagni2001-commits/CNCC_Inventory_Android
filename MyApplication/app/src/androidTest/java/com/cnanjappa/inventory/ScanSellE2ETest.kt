package com.cnanjappa.inventory

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cnanjappa.inventory.data.AppDatabase
import com.cnanjappa.inventory.data.Code
import com.cnanjappa.inventory.data.InventoryRepository
import com.cnanjappa.inventory.data.Resolution
import com.cnanjappa.inventory.data.newOpId
import com.cnanjappa.inventory.domain.Codes
import com.cnanjappa.inventory.domain.ScannedCode
import com.cnanjappa.inventory.domain.Sleeve
import com.cnanjappa.inventory.domain.Validate
import com.cnanjappa.inventory.domain.VariantInput
import com.cnanjappa.inventory.scan.BarcodeAnalyzer
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Scan-to-sell end to end, minus the physical lens: a printed label is rendered into a camera-sized
 * frame (720x1280, upright), decoded by the app's ML Kit scanner and scan-box filter, looked up and
 * sold through the repository. Each label is checked with the product added the two ways staff do it:
 * by scanning the tag (format known) and by typing the number printed under the bars.
 */
@RunWith(AndroidJUnit4::class)
class ScanSellE2ETest {
    private lateinit var db: AppDatabase
    private lateinit var repo: InventoryRepository
    private val scanner = BarcodeAnalyzer.newScanner()

    @Before fun setUp() {
        db = AppDatabase.build(InstrumentationRegistry.getInstrumentation().targetContext, name = null)
        repo = InventoryRepository(db)
    }

    @After fun tearDown() { scanner.close(); db.close() }

    data class Label(val raw: String, val fmt: BarcodeFormat, val name: String)

    private val labels = listOf(
        Label("8901234567890", BarcodeFormat.EAN_13, "Shirt EAN-13"),
        Label("0012345678905", BarcodeFormat.EAN_13, "Shirt EAN-13 leading 0"),
        Label("012345678905", BarcodeFormat.UPC_A, "Trouser UPC-A"),
        Label("01234565", BarcodeFormat.UPC_E, "Vest UPC-E"),
        Label("96385074", BarcodeFormat.EAN_8, "Hanky EAN-8"),
        Label("RMJ-SH-4402-L", BarcodeFormat.CODE_128, "Ramraj Code128 text"),
        Label("8901234567890", BarcodeFormat.CODE_128, "Code128 numeric 13"),
        Label("100045", BarcodeFormat.CODE_128, "Code128 short numeric"),
        Label("KURTA42", BarcodeFormat.CODE_39, "Kurta Code39"),
        Label("CNABCDE-1F", BarcodeFormat.QR_CODE, "QR text"),
        Label("SAREE-SILK-0099", BarcodeFormat.DATA_MATRIX, "Saree DataMatrix"),
    )

    /** A label drawn as a camera would see it: centred, optionally tilted, slightly blurred by scaling. */
    private fun frame(l: Label, tilt: Float = 0f, scale: Float = 1f): Bitmap {
        val square = l.fmt == BarcodeFormat.QR_CODE || l.fmt == BarcodeFormat.DATA_MATRIX
        val (bw, bh) = if (square) 300 to 300 else 520 to 200
        val m = MultiFormatWriter().encode(l.raw, l.fmt, bw, bh, mapOf(EncodeHintType.MARGIN to if (square) 4 else 12))
        val code = Bitmap.createBitmap(m.width, m.height, Bitmap.Config.ARGB_8888)
        for (y in 0 until m.height) for (x in 0 until m.width) code.setPixel(x, y, if (m[x, y]) Color.BLACK else Color.WHITE)
        val out = Bitmap.createBitmap(720, 1280, Bitmap.Config.ARGB_8888)
        val c = Canvas(out); c.drawColor(Color.rgb(200, 190, 175)) // fabric-ish background
        val mx = Matrix().apply {
            postTranslate(-code.width / 2f, -code.height / 2f); postScale(scale, scale); postRotate(tilt); postTranslate(360f, 640f)
        }
        c.drawBitmap(code, mx, Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }

    private fun decode(bmp: Bitmap): List<ScannedCode> =
        BarcodeAnalyzer.inScanBox(Tasks.await(scanner.process(InputImage.fromBitmap(bmp, 0))), bmp.width, bmp.height)

    private suspend fun addProduct(n: Int, name: String, code: ScannedCode): Long {
        val v = Validate.variant(VariantInput("Test", "$name $n", "M$n", "White", "L", Sleeve.FULL)).first!!
        return repo.createVariant(newOpId(), v, 5, code, false, "").variantId!!
    }

    @Test fun everyLabelScansAndSells() = runBlocking {
        val problems = mutableListOf<String>()
        var n = 0
        for (l in labels) for ((tilt, scale) in listOf(0f to 1f, 3f to 0.6f, 45f to 1f)) {
            val seen = decode(frame(l, tilt, scale))
            val tag = "${l.name} [${l.raw}] tilt=$tilt scale=$scale"
            if (seen.isEmpty()) {
                val bmp = frame(l, tilt, scale)
                val all = Tasks.await(scanner.process(InputImage.fromBitmap(bmp, 0)))
                problems += "$tag: NOT DECODED (ML Kit saw ${all.map { "${it.format}:${it.rawValue}@${it.boundingBox}" }})"
                continue
            }
            val got = seen.first()
            Log.i("SCAN_E2E", "$tag -> raw=${got.raw} fmt=${got.format} key=${got.key}")

            // 1) Product added by scanning its tag, then sold by scanning again.
            val printedFmt = l.fmt.name
            val id1 = addProduct(++n, l.name, ScannedCode(l.raw, printedFmt))
            check(repo, got, id1, "$tag (added by scan)", problems)

            // 2) Product added by typing the number printed on the tag.
            db.close(); setUp()
            val id2 = addProduct(++n, l.name, ScannedCode(l.raw, Codes.TYPED))
            check(repo, got, id2, "$tag (added by typing)", problems)
            db.close(); setUp()
        }
        problems.forEach { Log.e("SCAN_E2E", it) }
        assertTrue("Scan-to-sell failures:\n" + problems.joinToString("\n"), problems.isEmpty())
    }

    private suspend fun check(repo: InventoryRepository, got: ScannedCode, id: Long, tag: String, problems: MutableList<String>) {
        when (val r = repo.resolve(got)) {
            is Resolution.One -> {
                if (r.variant.id != id) { problems += "$tag: matched wrong product"; return }
                val s = repo.sell(newOpId(), id)
                if (s.code != Code.OK || s.remaining != 4) problems += "$tag: sell failed ${s.code} remaining=${s.remaining}"
            }
            else -> problems += "$tag: PRODUCT NOT FOUND (scanned ${got.format} key=${got.key})"
        }
    }

    @Test fun labelOutsideScanBoxIsIgnored() {
        val l = labels.first()
        val bmp = frame(l)
        val shifted = Bitmap.createBitmap(720, 1280, Bitmap.Config.ARGB_8888)
        Canvas(shifted).apply { drawColor(Color.WHITE); drawBitmap(bmp, 0f, -560f, null) } // label near the top edge
        assertTrue(decode(shifted).isEmpty())
    }
}
