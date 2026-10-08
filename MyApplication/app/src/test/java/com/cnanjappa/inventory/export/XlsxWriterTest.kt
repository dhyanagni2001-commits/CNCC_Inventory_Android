package com.cnanjappa.inventory.export

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.ZoneId
import java.util.zip.ZipFile

class XlsxWriterTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun write(block: suspend XlsxWriter.() -> Unit) = tmp.newFile("t.xlsx").also { f ->
        runBlocking { XlsxWriter(f.outputStream(), ZoneId.of("Asia/Kolkata")).use { it.block() } }
    }

    private fun sheetXml(f: java.io.File, n: Int = 1) =
        ZipFile(f).use { z -> z.getInputStream(z.getEntry("xl/worksheets/sheet$n.xml")).readBytes().toString(Charsets.UTF_8) }

    @Test fun writesValidWorkbook() {
        val f = write {
            sheet("Inventory", listOf(10, 10)) { header("Name", "Pieces"); row("Shirt", 5L); row("Pant", 0L) }
            sheet("Second", listOf(10)) { row("x") }
        }
        assertTrue(XlsxWriter.validate(f, 2))
        assertFalse(XlsxWriter.validate(f, 3))
    }

    @Test fun userTextIsNeverAFormula() {
        val xml = sheetXml(write { sheet("S", listOf(10)) { row("=HYPERLINK(\"x\")") } })
        assertFalse(xml.contains("<f>"))
        assertTrue(xml.contains("inlineStr"))
    }

    @Test fun escapesXmlAndDropsInvalidChars() {
        assertEquals("a&amp;b&lt;c&gt;&quot;", XlsxWriter.esc("a&b<c>\""))
        assertEquals("ab\tc\n", XlsxWriter.esc("a\u0001b\tc\n"))
        assertEquals("Rack 4\nCotton", XlsxWriter.esc("Rack 4\nCotton"))
    }

    @Test fun columnNames() {
        assertEquals(listOf("A", "Z", "AA", "AZ", "BA", "XFD"), listOf(1, 26, 27, 52, 53, 16384).map(XlsxWriter::colName))
    }

    @Test fun truncatedFileFailsValidation() {
        val f = write { sheet("S", listOf(10)) { repeat(1000) { row("row $it") } } }
        val bytes = f.readBytes()
        val cut = tmp.newFile("cut.xlsx").apply { writeBytes(bytes.copyOf(bytes.size / 2)) }
        assertFalse(XlsxWriter.validate(cut, 1))
    }
}
