package jp.co.tenposinfo.register

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class V136MultiPrinterRoutingTest {
    @Test
    fun printerConfigurationHasStableIdentityAndSupportsMixedWidths() {
        val mm58 = PrinterConfiguration(
            printerId = "printer-58",
            name = "58mm",
            paperWidthMm = 58,
            printableDotWidth = 420,
        )
        val mm80 = PrinterConfiguration(
            printerId = "printer-80",
            name = "80mm",
            paperWidthMm = 80,
            printableDotWidth = 640,
        )

        assertEquals("printer-58", PrinterProfileContractV136.snapshot(mm58).printerId)
        assertEquals(58, PrinterProfileContractV136.snapshot(mm58).paperWidthMm)
        assertEquals(420, PrinterProfileContractV136.snapshot(mm58).printableDotWidth)
        assertEquals("printer-80", PrinterProfileContractV136.snapshot(mm80).printerId)
        assertEquals(80, PrinterProfileContractV136.snapshot(mm80).paperWidthMm)
        assertEquals(640, PrinterProfileContractV136.snapshot(mm80).printableDotWidth)
    }

    @Test
    fun profileStoreDefinesNonDestructiveLegacyMigrationAndPerDocumentRoutes() {
        val source = File("src/main/java/jp/co/tenposinfo/register/PrinterProfileStoreV136.kt").readText()

        assertTrue(source.contains("CREATE TABLE IF NOT EXISTS $PROFILE_TABLE"))
        assertTrue(source.contains("CREATE TABLE IF NOT EXISTS $ROUTE_TABLE"))
        assertTrue(source.contains("seedLegacyProfileIfNeeded()"))
        assertTrue(source.contains("printerId = PrinterProfileContractV136.SINGLE_PRINTER_ID"))
        assertTrue(source.contains("DocumentPrintKindV136.entries.forEach"))
        assertTrue(source.contains("fun setDefault(kind: DocumentPrintKindV136"))
        assertTrue(source.contains("fun duplicateTcpPrinterIds"))
        assertFalse(source.contains("DROP TABLE printer_settings"))
    }

    @Test
    fun saleAndDocumentJobsFreezePrinterIdentityWidthAndDotWidth() {
        val sale = File("src/main/java/jp/co/tenposinfo/register/RegisterDatabase.kt").readText()
        val operations = File("src/main/java/jp/co/tenposinfo/register/AdvancedOperationsStore.kt").readText()
        val voucher = File("src/main/java/jp/co/tenposinfo/register/ReceiptVoucher.kt").readText()
        val provisional = File("src/main/java/jp/co/tenposinfo/register/HeldTicketProvisionalPrintV135.kt").readText()
        val manualReturn = File("src/main/java/jp/co/tenposinfo/register/ManualReturnV135.kt").readText()

        listOf(sale, operations, voucher, provisional, manualReturn).forEach { source ->
            assertTrue(source.contains("put(\"printer_id\""))
            assertTrue(source.contains("put(\"printable_dot_width\""))
        }
        assertTrue(sale.contains("PrinterRoutingV136.resolve("))
        assertTrue(operations.contains("PrinterRoutingV136.resolve("))
        assertTrue(voucher.contains("DocumentPrintKindV136.RECEIPT_VOUCHER"))
        assertTrue(provisional.contains("DocumentPrintKindV136.PROVISIONAL_RECEIPT"))
    }

    @Test
    fun automaticAndManualDispatchUseFrozenJobPrinterId() {
        val automatic = File("src/main/java/jp/co/tenposinfo/register/AutomaticPrintWorker.kt").readText()
        val queue = File("src/main/java/jp/co/tenposinfo/register/UnifiedPrintQueue.kt").readText()

        assertTrue(automatic.contains("Triple(job.printerId, job.paperWidthMm, job.printableDotWidth)"))
        assertTrue(automatic.contains("PrinterRoutingV136.loadById(applicationContext, snapshot.first)"))
        assertTrue(automatic.contains("PrinterEndpointSendGate.withPermit("))
        assertTrue(automatic.contains(").processJob(candidate.sourceId)"))
        assertTrue(queue.contains("PrinterRoutingV136.loadById(applicationContext, job.printerId)"))
        assertTrue(queue.contains("printableDotWidth = job.printableDotWidth"))
    }

    @Test
    fun structuredPrintJournalIncludesPrinterRouteSnapshot() {
        val source = File("src/main/java/jp/co/tenposinfo/register/PrintDocumentSnapshotV136.kt").readText()

        assertTrue(source.contains("\\\"printerId\\\""))
        assertTrue(source.contains("\\\"printableDotWidth\\\""))
        assertTrue(source.contains("printer_id, printable_dot_width, rendered_text"))
        assertTrue(source.contains("NEW.printer_id, NEW.printable_dot_width"))
    }

    @Test
    fun scr660ExposesMultipleProfilesAndRoutesWithDuplicateIpWarningOnly() {
        val ui = File("src/main/java/jp/co/tenposinfo/register/AdminSettingsActivity.kt").readText()

        assertTrue(ui.contains("Text(\"登録プリンター\""))
        assertTrue(ui.contains("Text(\"文書別の既定出力先\""))
        assertTrue(ui.contains("プリンター追加"))
        assertTrue(ui.contains("選択プリンター削除"))
        assertTrue(ui.contains("DocumentPrintKindV136.entries.forEach"))
        assertTrue(ui.contains("profileStore.setDefault("))
        assertTrue(ui.contains("duplicateTcpPrinterIds"))
        assertTrue(ui.contains("注意：同じIP/ポートの登録があります"))
    }

    @Test
    fun formalPrintJobSnapshotColumnsAreMigratedWithoutDestructiveReset() {
        val schema = File("src/main/java/jp/co/tenposinfo/register/PrinterJobRouteSchemaV136.kt").readText()

        assertTrue(schema.contains("ensureColumn(db, \"print_jobs\", \"printer_id\""))
        assertTrue(schema.contains("ensureColumn(db, \"print_jobs\", \"printable_dot_width\""))
        assertTrue(schema.contains("ensureColumn(db, \"document_print_jobs\", \"printer_id\""))
        assertTrue(schema.contains("ensureColumn(db, \"document_print_jobs\", \"printable_dot_width\""))
        assertFalse(schema.contains("DROP TABLE"))
    }
}
