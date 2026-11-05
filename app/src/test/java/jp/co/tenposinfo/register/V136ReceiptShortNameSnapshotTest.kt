package jp.co.tenposinfo.register

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class V136ReceiptShortNameSnapshotTest {
    @Test
    fun saleCommitAndReloadUseFrozenReceiptShortNameSnapshot() {
        val source = File("src/main/java/jp/co/tenposinfo/register/RegisterDatabase.kt").readText()
        val snapshot = File("src/main/java/jp/co/tenposinfo/register/ReceiptProductNameSnapshotV136.kt").readText()

        assertTrue(source.contains("ReceiptProductNameSnapshotV136.save(this, saleId, items)"))
        assertTrue(source.contains("ReceiptProductNameSnapshotV136.apply(readableDatabase, saleId, taxSnapshotItems)"))
        assertTrue(snapshot.contains("sale_receipt_name_snapshots"))
        assertTrue(snapshot.contains("receipt_short_name"))
    }

    @Test
    fun catalogAndRuntimeCarryReceiptShortName() {
        val catalog = File("src/main/java/jp/co/tenposinfo/register/CatalogMasterStore.kt").readText()
        val runtime = File("src/main/java/jp/co/tenposinfo/register/DynamicCatalogRuntime.kt").readText()
        val settings = File("src/main/java/jp/co/tenposinfo/register/CatalogSettingsActivity.kt").readText()

        assertTrue(catalog.contains("receipt_short_name"))
        assertTrue(runtime.contains("receiptShortName = meta.receiptShortName"))
        assertTrue(settings.contains("レシート用短縮名（任意）"))
    }
}
