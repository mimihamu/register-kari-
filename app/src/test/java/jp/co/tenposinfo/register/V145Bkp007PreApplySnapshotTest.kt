package jp.co.tenposinfo.register

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class V145Bkp007PreApplySnapshotTest {
    private val root = File(System.getProperty("user.dir")).let { current ->
        if (File(current, "app").isDirectory) File(current, "app") else current
    }

    private fun item(id: String, page: Int, slot: Int) = MenuRevisionProduct(
        productId = id,
        name = "商品$id",
        enabled = true,
        unitPrice = 100,
        legacyTaxCategory = TaxCategory.INCLUDED_10,
        taxKey = TaxCategory.INCLUDED_10.name,
        taxLabel = TaxCategory.INCLUDED_10.displayName,
        taxRatePercent = 10,
        taxIncluded = true,
        taxable = true,
        reduced = false,
        taxSymbol = TaxCategory.INCLUDED_10.symbol,
        buttonColor = "BLUE",
        pageNo = page,
        slotNo = slot,
        displayOrder = ButtonLayoutPolicy.displayOrder(page, slot),
    )

    private fun rule() = DynamicTaxRule(
        key = TaxCategory.INCLUDED_10.name,
        label = TaxCategory.INCLUDED_10.displayName,
        ratePercent = 10,
        mode = DynamicTaxMode.INCLUDED,
        reduced = false,
        enabled = true,
        symbol = TaxCategory.INCLUDED_10.symbol,
        validFrom = "",
        validTo = "",
    )

    @Test
    fun validRevisionPassesAndDuplicatePlacementFailsClosed() {
        MenuRevisionApplyValidationV145.validate(listOf(item("A", 1, 1)), mapOf(rule().key to rule()))
        val duplicate = runCatching {
            MenuRevisionApplyValidationV145.validate(
                listOf(item("A", 1, 1), item("B", 1, 1)),
                mapOf(rule().key to rule()),
            )
        }
        assertTrue(duplicate.isFailure)
    }

    @Test
    fun taxSnapshotConflictFailsBeforeMasterMutation() {
        val changed = rule().copy(ratePercent = 8)
        val result = runCatching {
            MenuRevisionApplyValidationV145.validate(listOf(item("A", 1, 1)), mapOf(changed.key to changed))
        }
        assertTrue(result.isFailure)
    }

    @Test
    fun sourceCapturesBeforeMutationAndExplicitlyRestoresOnFailure() {
        fun source(name: String) = File(root, "src/main/java/jp/co/tenposinfo/register/$name").readText()
        val runtime = source("DynamicCatalogRuntime.kt")
        val snapshot = source("MenuRevisionPreApplySnapshotV145.kt")

        val capture = runtime.indexOf("MenuRevisionPreApplySnapshotV145.capture(db)")
        val firstMutation = runtime.indexOf("update(\"product_meta\"")
        assertTrue(capture >= 0)
        assertTrue(firstMutation > capture)
        assertTrue(runtime.contains("captured?.restore(db)"))
        assertTrue(runtime.contains("MENU_REVISION_APPLY_ROLLED_BACK"))
        assertTrue(runtime.contains("put(\"status\", \"FAILED\")"))
        assertTrue(runtime.contains("put(\"status\", \"APPLIED\")"))
        assertTrue(runtime.contains("put(\"status\", \"SUPERSEDED\")"))
        assertTrue(runtime.indexOf("applyDueRevisionIfNeeded(date)") < runtime.indexOf("val revision = activeRevision(date)"))
        assertTrue(runtime.contains("r.status IN ('APPLIED', 'SCHEDULED')"))

        assertTrue(snapshot.contains("FROM products p"))
        assertTrue(snapshot.contains("LEFT JOIN catalog_product_base"))
        assertTrue(snapshot.contains("LEFT JOIN product_meta"))
        assertTrue(snapshot.contains("LEFT JOIN product_tax_assignments"))
        assertTrue(snapshot.contains("SELECT revision FROM catalog_revision"))
        assertTrue(snapshot.contains("fun restore(db: SQLiteDatabase)"))
    }
}
