package com.cnanjappa.inventory.export

import java.io.BufferedWriter
import java.io.File
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** Marker for a timestamp cell, written as an Excel date serial in the report timezone. */
@JvmInline
value class DateCell(val epochMillis: Long)

/**
 * Minimal streaming .xlsx (Office Open XML) writer. Rows go straight into the zip stream, so memory
 * stays bounded for million-row ledgers. Strings are written as inline strings, never formulas, so
 * user text such as "=SUM(A1)" stays literal; numbers are numeric cells.
 */
class XlsxWriter(out: OutputStream, private val zone: ZoneId) : AutoCloseable {
    private val zip = ZipOutputStream(out)
    private val writer = BufferedWriter(OutputStreamWriter(zip, Charsets.UTF_8), 64 * 1024)
    private val sheets = mutableListOf<String>()

    inner class Sheet internal constructor(private val columns: Int) {
        internal var rows = 0
        fun row(vararg cells: Any?) {
            rows++
            writer.write("<row r=\"$rows\">")
            for (c in cells) cell(c)
            writer.write("</row>")
        }

        fun header(vararg titles: String) {
            rows++
            writer.write("<row r=\"$rows\">")
            for (t in titles) writer.write("<c t=\"inlineStr\" s=\"1\"><is><t>${esc(t)}</t></is></c>")
            writer.write("</row>")
        }

        private fun cell(v: Any?) {
            when (v) {
                null -> writer.write("<c/>")
                is Int, is Long -> writer.write("<c s=\"2\"><v>$v</v></c>")
                is Number -> writer.write("<c><v>$v</v></c>")
                is DateCell -> writer.write("<c s=\"3\"><v>${serial(v.epochMillis)}</v></c>")
                else -> {
                    var s = v.toString()
                    if (s.length > MAX_CELL) s = s.take(MAX_CELL - 1) + "…"
                    writer.write("<c t=\"inlineStr\"><is><t xml:space=\"preserve\">${esc(s)}</t></is></c>")
                }
            }
        }

        internal val lastColumn get() = colName(columns)
    }

    /** Writes one worksheet with a frozen, filterable header row. */
    suspend fun sheet(name: String, widths: List<Int>, block: suspend Sheet.() -> Unit): Int {
        val safe = name.take(31)
        sheets += safe
        zip.putNextEntry(ZipEntry("xl/worksheets/sheet${sheets.size}.xml"))
        writer.write(XML_HEAD)
        writer.write("<worksheet xmlns=\"$NS\" xmlns:r=\"$NS_R\">")
        writer.write("<sheetViews><sheetView workbookViewId=\"0\"><pane ySplit=\"1\" topLeftCell=\"A2\" activePane=\"bottomLeft\" state=\"frozen\"/></sheetView></sheetViews>")
        writer.write("<cols>")
        widths.forEachIndexed { i, w -> writer.write("<col min=\"${i + 1}\" max=\"${i + 1}\" width=\"$w\" customWidth=\"1\"/>") }
        writer.write("</cols><sheetData>")
        val sheet = Sheet(widths.size)
        sheet.block()
        writer.write("</sheetData>")
        if (sheet.rows > 1) writer.write("<autoFilter ref=\"A1:${sheet.lastColumn}${sheet.rows}\"/>")
        writer.write("</worksheet>")
        writer.flush()
        zip.closeEntry()
        return sheet.rows
    }

    private fun entry(name: String, content: String) {
        zip.putNextEntry(ZipEntry(name))
        writer.write(content)
        writer.flush()
        zip.closeEntry()
    }

    override fun close() {
        entry(
            "[Content_Types].xml",
            XML_HEAD + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
                "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
                "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
                "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>" +
                "<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>" +
                sheets.indices.joinToString("") {
                    "<Override PartName=\"/xl/worksheets/sheet${it + 1}.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>"
                } + "</Types>",
        )
        entry(
            "_rels/.rels",
            XML_HEAD + "<Relationships xmlns=\"$NS_PR\"><Relationship Id=\"rId1\" Type=\"$NS_R/officeDocument\" Target=\"xl/workbook.xml\"/></Relationships>",
        )
        entry(
            "xl/workbook.xml",
            XML_HEAD + "<workbook xmlns=\"$NS\" xmlns:r=\"$NS_R\"><sheets>" +
                sheets.mapIndexed { i, n -> "<sheet name=\"${esc(n)}\" sheetId=\"${i + 1}\" r:id=\"rId${i + 1}\"/>" }.joinToString("") +
                "</sheets></workbook>",
        )
        entry(
            "xl/_rels/workbook.xml.rels",
            XML_HEAD + "<Relationships xmlns=\"$NS_PR\">" +
                sheets.indices.joinToString("") { "<Relationship Id=\"rId${it + 1}\" Type=\"$NS_R/worksheet\" Target=\"worksheets/sheet${it + 1}.xml\"/>" } +
                "<Relationship Id=\"rId${sheets.size + 1}\" Type=\"$NS_R/styles\" Target=\"styles.xml\"/></Relationships>",
        )
        entry("xl/styles.xml", STYLES)
        writer.close()
    }

    private fun serial(ms: Long): Double {
        val local = Instant.ofEpochMilli(ms).atZone(zone).toLocalDateTime()
        val days = ChronoUnit.DAYS.between(EPOCH, local.toLocalDate())
        return days + local.toLocalTime().toSecondOfDay() / 86400.0
    }

    companion object {
        const val MAX_ROWS = 1_048_576
        private const val MAX_CELL = 32_767
        private val EPOCH = java.time.LocalDate.of(1899, 12, 30)
        private const val XML_HEAD = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
        private const val NS = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
        private const val NS_R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
        private const val NS_PR = "http://schemas.openxmlformats.org/package/2006/relationships"
        private val STYLES = XML_HEAD + "<styleSheet xmlns=\"$NS\">" +
            "<numFmts count=\"1\"><numFmt numFmtId=\"164\" formatCode=\"yyyy-mm-dd hh:mm\"/></numFmts>" +
            "<fonts count=\"2\"><font><sz val=\"11\"/><name val=\"Calibri\"/></font><font><b/><sz val=\"11\"/><name val=\"Calibri\"/></font></fonts>" +
            "<fills count=\"3\"><fill><patternFill patternType=\"none\"/></fill><fill><patternFill patternType=\"gray125\"/></fill>" +
            "<fill><patternFill patternType=\"solid\"><fgColor rgb=\"FFDCE6F2\"/></patternFill></fill></fills>" +
            "<borders count=\"1\"><border/></borders>" +
            "<cellStyleXfs count=\"1\"><xf/></cellStyleXfs>" +
            "<cellXfs count=\"4\"><xf/><xf fontId=\"1\" fillId=\"2\" applyFont=\"1\" applyFill=\"1\"/>" +
            "<xf numFmtId=\"1\" applyNumberFormat=\"1\"/><xf numFmtId=\"164\" applyNumberFormat=\"1\"/></cellXfs>" +
            "</styleSheet>"

        fun colName(n: Int): String {
            var x = n
            val sb = StringBuilder()
            while (x > 0) { val r = (x - 1) % 26; sb.insert(0, ('A' + r)); x = (x - 1) / 26 }
            return sb.toString()
        }

        /** XML-escapes text and drops characters that XML 1.0 cannot carry. */
        fun esc(s: String): String {
            val sb = StringBuilder(s.length + 16)
            for (ch in s) when {
                ch == '&' -> sb.append("&amp;")
                ch == '<' -> sb.append("&lt;")
                ch == '>' -> sb.append("&gt;")
                ch == '"' -> sb.append("&quot;")
                ch < ' ' && ch != '\t' && ch != '\n' && ch != '\r' -> {}
                ch == '￾' || ch == '￿' -> {}
                else -> sb.append(ch)
            }
            return sb.toString()
        }

        /** Structural check of a finished workbook before it is offered to the user. */
        fun validate(file: File, expectedSheets: Int): Boolean = runCatching {
            ZipFile(file).use { z ->
                listOf("[Content_Types].xml", "xl/workbook.xml", "xl/styles.xml", "_rels/.rels").all { z.getEntry(it) != null } &&
                    (1..expectedSheets).all { i ->
                        val e = z.getEntry("xl/worksheets/sheet$i.xml") ?: return@all false
                        // Stream through (sheets can be ~100 MB) keeping only the tail.
                        z.getInputStream(e).use { input ->
                            val buf = ByteArray(16 * 1024)
                            var tail = ByteArray(0)
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                val joined = tail + buf.copyOf(n)
                                tail = joined.copyOfRange(maxOf(0, joined.size - 14), joined.size)
                            }
                            tail.toString(Charsets.UTF_8).endsWith("</worksheet>")
                        }
                    }
            }
        }.getOrDefault(false)
    }
}
