package com.cnanjappa.inventory.export

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.TextUtils
import android.text.TextPaint
import com.cnanjappa.inventory.data.Variant
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.oned.Code128Writer
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File

enum class LabelType { CODE128, QR }

/** Physical sheet layout in millimetres. */
enum class LabelPreset(
    val title: String,
    val pageW: Float, val pageH: Float,
    val cols: Int, val rows: Int,
    val labelW: Float, val labelH: Float,
    val left: Float, val top: Float,
    val gapX: Float, val gapY: Float,
) {
    A4_24("A4 sheet · 24 stickers (63.5 × 33.9 mm)", 210f, 297f, 3, 8, 63.5f, 33.9f, 7.2f, 13.1f, 2.5f, 0f),
    A4_40("A4 sheet · 40 stickers (52.5 × 29.7 mm)", 210f, 297f, 4, 10, 52.5f, 29.7f, 0f, 0f, 0f, 0f),
    ROLL_50x25("Label roll · 50 × 25 mm (one per page)", 50f, 25f, 1, 1, 50f, 25f, 0f, 0f, 0f, 0f),
    ;

    val perPage get() = cols * rows
}

data class LabelJob(val variant: Variant, val copies: Int)

/**
 * Draws labels as vector PDF content: bars/modules are filled rectangles so they stay crisp at any
 * printer resolution. Each variant's pattern is encoded once and reused for all its copies; pages are
 * written one at a time. Generating labels never touches stock.
 */
object LabelPdf {
    private const val PT_PER_MM = 72f / 25.4f
    private const val QUIET_MODULES = 10

    private class Pattern(val type: LabelType, val bars: BooleanArray?, val qr: com.google.zxing.common.BitMatrix?)

    private fun encode(code: String, type: LabelType): Pattern = when (type) {
        LabelType.CODE128 -> Pattern(type, Code128Writer().encode(code), null)
        LabelType.QR -> Pattern(
            type, null,
            QRCodeWriter().encode(code, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 0, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M)),
        )
    }

    /** Writes exactly sum(copies) labels to [target], starting at sheet cell [startCell] (0-based). */
    suspend fun write(target: File, jobs: List<LabelJob>, type: LabelType, preset: LabelPreset, startCell: Int, onPage: (Int) -> Unit): Int =
        withContext(Dispatchers.Default) {
            val doc = PdfDocument()
            var page: PdfDocument.Page? = null
            var cell = startCell.coerceIn(0, preset.perPage - 1)
            var pages = 0
            try {
                for (job in jobs) {
                    val pattern = encode(job.variant.code!!, type)
                    repeat(job.copies) {
                        if (page == null) {
                            ensureActive()
                            pages++
                            page = doc.startPage(PdfDocument.PageInfo.Builder(pt(preset.pageW).toInt(), pt(preset.pageH).toInt(), pages).create())
                            onPage(pages)
                        }
                        val c = cell
                        val x = pt(preset.left + (c % preset.cols) * (preset.labelW + preset.gapX))
                        val y = pt(preset.top + (c / preset.cols) * (preset.labelH + preset.gapY))
                        drawLabel(page!!.canvas, x, y, pt(preset.labelW), pt(preset.labelH), job.variant, pattern)
                        cell++
                        if (cell == preset.perPage) { doc.finishPage(page); page = null; cell = 0 }
                    }
                }
                page?.let { doc.finishPage(it) }
                page = null
                ensureActive()
                target.outputStream().use { doc.writeTo(it) }
                pages
            } catch (e: Throwable) {
                target.delete()
                throw e
            } finally {
                doc.close()
            }
        }

    /** Small on-screen preview of one label, drawn by the same routine as the PDF. */
    fun preview(variant: Variant, type: LabelType, preset: LabelPreset, widthPx: Int): Bitmap {
        val scale = widthPx / pt(preset.labelW)
        val h = (pt(preset.labelH) * scale).toInt()
        val bmp = Bitmap.createBitmap(widthPx, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.WHITE)
        canvas.scale(scale, scale)
        drawLabel(canvas, 0f, 0f, pt(preset.labelW), pt(preset.labelH), variant, encode(variant.code!!, type))
        return bmp
    }

    private fun pt(mm: Float) = mm * PT_PER_MM

    private val black = Paint().apply { color = Color.BLACK; style = Paint.Style.FILL; isAntiAlias = false }

    private fun drawLabel(c: Canvas, x: Float, y: Float, w: Float, h: Float, v: Variant, p: Pattern) {
        val pad = pt(2f)
        val inner = w - 2 * pad
        val title = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = (h * 0.11f).coerceAtMost(9f); typeface = Typeface.DEFAULT_BOLD }
        val body = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = (h * 0.10f).coerceAtMost(8f) }
        val lines = listOf(
            title to listOf(v.company, v.name).filter { it.isNotEmpty() }.joinToString(" · "),
            body to v.model,
            title to listOf(v.colour, "Size ${v.sizeLabel}", v.sleeve).filter { it.isNotEmpty() }.joinToString(" · "),
        )
        when (p.type) {
            LabelType.CODE128 -> {
                var ty = y + pad
                for ((paint, text) in lines) {
                    ty += paint.textSize
                    c.drawText(TextUtils.ellipsize(text, paint, inner, TextUtils.TruncateAt.END).toString(), x + pad, ty, paint)
                    ty += paint.textSize * 0.15f
                }
                val codeText = body.textSize
                val barTop = ty + pt(0.8f)
                val barBottom = y + h - pad - codeText - pt(0.5f)
                val bars = p.bars!!
                val module = inner / (bars.size + 2 * QUIET_MODULES)
                var bx = x + pad + QUIET_MODULES * module
                var i = 0
                while (i < bars.size) {
                    if (bars[i]) {
                        var run = 1
                        while (i + run < bars.size && bars[i + run]) run++
                        c.drawRect(bx, barTop, bx + run * module, barBottom, black)
                        bx += run * module; i += run
                    } else { bx += module; i++ }
                }
                body.textAlign = Paint.Align.CENTER
                c.drawText(v.code!!, x + w / 2, y + h - pad, body)
            }
            LabelType.QR -> {
                val m = p.qr!!
                val side = h - 2 * pad
                val module = side / m.width
                for (row in 0 until m.height) {
                    var col = 0
                    while (col < m.width) {
                        if (m.get(col, row)) {
                            var run = 1
                            while (col + run < m.width && m.get(col + run, row)) run++
                            c.drawRect(x + pad + col * module, y + pad + row * module, x + pad + (col + run) * module, y + pad + (row + 1) * module, black)
                            col += run
                        } else col++
                    }
                }
                val tx = x + pad + side + pt(2f)
                val tw = x + w - pad - tx
                var ty = y + pad
                for ((paint, text) in lines + (body to v.code!!)) {
                    ty += paint.textSize
                    c.drawText(TextUtils.ellipsize(text, paint, tw, TextUtils.TruncateAt.END).toString(), tx, ty, paint)
                    ty += paint.textSize * 0.25f
                }
            }
        }
    }
}
