package jp.co.tenposinfo.register

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class V184PostCommitCompletionSafetyTest {
    private fun code(file: String) = File("src/main/java/jp/co/tenposinfo/register/$file").readText()

    @Test fun committedSaleIsNeverReversedByPeripheralFailures() {
        val source = code("MainActivity.kt")
        val completion = source.substringAfter("}.onSuccess { saleId ->", source)
            .substringBefore("}.onFailure { error ->")
        assertTrue(completion.contains("lastSaleId = saleId"))
        assertTrue(completion.contains("screen = AppScreen.COMPLETE"))
        assertTrue(completion.contains("completedDraftCleanupPendingV184"))
        assertTrue(completion.contains("runCatching { paymentDraftStore.clear() }"))
        assertTrue(completion.contains("runCatching {\n                                database.loadSaleDetail(saleId)"))
        assertTrue(completion.contains("AutomaticPrintScheduler.enqueueNow("))
        assertTrue(completion.contains("DriveOutboxScheduler.enqueueNow("))
        assertFalse(completion.contains("replaceCart(emptyList())"))
        assertFalse(completion.contains("database.saveCart("))
    }

    @Test fun nextSaleIsBlockedIfPostCommitDraftCleanupFailed() {
        assertFalse(SalePostCommitPolicyV184.canStartNextSale(true))
        assertTrue(SalePostCommitPolicyV184.canStartNextSale(false))
        val source = code("MainActivity.kt")
        val next = source.substringAfter("fun moveToNextSaleV184() {")
            .substringBefore("// Android system Back and on-screen Back")
        assertTrue(next.contains("if (completedDraftCleanupPendingV184)"))
        assertTrue(next.contains("runCatching { paymentDraftStore.clear() }"))
        assertTrue(next.contains("return"))
        assertTrue(next.contains("screen = AppScreen.SALES"))
        assertTrue(next.indexOf("paymentDraftStore.clear()") < next.indexOf("screen = AppScreen.SALES"))
        assertTrue(source.contains("AppScreen.COMPLETE -> moveToNextSaleV184()"))
        assertTrue(source.contains("onNext = ::moveToNextSaleV184"))
    }

    @Test fun successScreenAlwaysClearlyIdentifiesConfirmedSaleAndFailure() {
        val source = code("MainActivity.kt")
        val screen = source.substringAfter("private fun CompleteScreen(")
            .substringBefore("private fun PrintQueueScreen(")
        assertTrue(screen.contains("notice: String?"))
        assertTrue(screen.contains("Text(\n                    notice,"))
        assertTrue(screen.contains("会計が完了しました"))
        assertTrue(SalePostCommitPolicyV184.DRAFT_CLEANUP_WARNING.contains("売上は確定済み"))
        assertTrue(SalePostCommitPolicyV184.NONCRITICAL_WARNING.contains("再会計しないで"))
    }

    @Test fun atomicSaleTransactionRetainsCartCleanupGuarantee() {
        val db = code("RegisterDatabase.kt")
        val save = db.substringAfter("    fun saveSale(").substringBefore("    fun listSales(")
        assertTrue(save.contains("runInTransactionWithResult"))
        assertTrue(save.contains("delete(\"cart_items\", null, null)"))
        assertTrue(save.contains("SaleCommitIdempotencySchema.record("))
    }
}
