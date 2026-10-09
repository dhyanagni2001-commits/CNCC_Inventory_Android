package com.cnanjappa.inventory

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cnanjappa.inventory.data.Code
import com.cnanjappa.inventory.data.newOpId
import com.cnanjappa.inventory.domain.Sleeve
import com.cnanjappa.inventory.domain.Validate
import com.cnanjappa.inventory.domain.VariantInput
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Builds a small, realistic demo shop (for client videos and README screenshots) in the .debug app and
 * writes it as a normal backup file, which can then be restored into any build through Backup → Restore.
 * Not part of the normal suite: run with `-e demo true` on an empty .debug app.
 */
@RunWith(AndroidJUnit4::class)
class DemoData {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val repo = ctx.container.repo

    private data class Item(val company: String, val name: String, val model: String, val colour: String,
                            val size: String, val sleeve: Sleeve, val qty: Int, val notes: String = "", val sold: Int = 0)

    @Test fun buildDemoShop(): Unit = runBlocking {
        assumeTrue("only with -e demo true", InstrumentationRegistry.getArguments().getString("demo") == "true")
        assertEquals("needs an empty .debug app", 0, repo.dao.variantCount())
        val items = listOf(
            Item("Ramraj", "Shirt", "Cotton Classic", "White", "40", Sleeve.FULL, 12, "100% cotton, rack 1", sold = 3),
            Item("Ramraj", "Shirt", "Cotton Classic", "White", "42", Sleeve.FULL, 8, "100% cotton, rack 1", sold = 2),
            Item("Ramraj", "Shirt", "Cotton Classic", "White", "42", Sleeve.HALF, 6, "100% cotton, rack 1"),
            Item("Ramraj", "Shirt", "Cotton Classic", "Cream", "44", Sleeve.FULL, 2, "Low stock — reorder", sold = 1),
            Item("Ramraj", "Jubba", "Gold Jari", "White", "42", Sleeve.FULL, 15, "Festive cotton", sold = 4),
            Item("Siyaram", "Shirt", "Linen Check", "Blue", "40", Sleeve.FULL, 5, "Linen blend", sold = 1),
            Item("Siyaram", "Shirt", "Formal", "Grey", "42", Sleeve.FULL, 7, "Poly-viscose", sold = 2),
            Item("Siyaram", "Shirt", "Formal", "Black", "44", Sleeve.FULL, 0, "Sold out — on order"),
            Item("Raymond", "Kurta", "Festive Silk", "Maroon", "L", Sleeve.FULL, 4, "Wedding season", sold = 1),
            Item("Raymond", "Kurta", "Festive Silk", "Maroon", "XL", Sleeve.FULL, 3, "Wedding season"),
            Item("Udyam", "Vest", "Rib", "White", "85", Sleeve.HALF, 24, "Box of 3", sold = 6),
            Item("Udyam", "T-Shirt", "Round Neck", "Green", "M", Sleeve.HALF, 10, "", sold = 2),
            Item("Puma", "Polo", "Sport", "Navy", "M", Sleeve.HALF, 6, "Dry-fit", sold = 2),
            Item("Puma", "Polo", "Sport", "Navy", "L", Sleeve.HALF, 1, "Last piece"),
        )
        val ids = items.map { it to createOk(it) }
        for ((item, id) in ids) repeat(item.sold) { assertEquals(Code.OK, repo.sell(newOpId(), id).code) }
        // A little history beyond sales: a delivery and a customer return.
        assertEquals(Code.OK, repo.restock(newOpId(), ids[0].second, 6).code)
        assertEquals(Code.OK, repo.returnItem(newOpId(), ids[4].second, null, 1, false).code)

        val out = File(ctx.getExternalFilesDir(null), "cncc-demo.cncbak")
        ctx.container.backup().backupTo(Uri.fromFile(out))
    }

    private suspend fun createOk(i: Item): Long {
        val v = Validate.variant(VariantInput(i.company, i.name, i.model, i.colour, i.size, i.sleeve)).first!!
        val r = repo.createVariant(newOpId(), v, i.qty, null, false, i.notes)
        assertEquals(Code.OK, r.code)
        return r.variantId!!
    }
}
