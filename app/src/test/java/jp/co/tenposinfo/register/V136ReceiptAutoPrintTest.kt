package jp.co.tenposinfo.register

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class V136ReceiptAutoPrintTest {
    private fun source(name: String) =
        File("src/main/java/jp/co/tenposinfo/register/$name").readText()

    @Test
    fun automaticReceiptJobFollowsDedicatedSetting() {
        assertTrue(ReceiptAutoPrintPolicyV136.shouldCreateAutomaticReceiptJob(true))
        assertFalse(ReceiptAutoPrintPolicyV136.shouldCreateAutomaticReceiptJob(false))
    }

    @Test
    fun committedCashSaleDrawerDecisionNoLongerDependsOnReceiptAutoPrint() {
        assertTrue(
            ReceiptAutoPrintPolicyV136.shouldOpenDrawerAfterCommittedCashSale(
                printerUsable = true,
                drawerEnabled = true,
                drawerOpenOnCashSale = true,
                hasCashPayment = true,
            ),
        )
        assertFalse(
            ReceiptAutoPrintPolicyV136.shouldOpenDrawerAfterCommittedCashSale(
                printerUsable = false,
                drawerEnabled = true,
                drawerOpenOnCashSale = true,
                hasCashPayment = true,
            ),
        )
        assertFalse(
            ReceiptAutoPrintPolicyV136.shouldOpenDrawerAfterCommittedCashSale(
                printerUsable = true,
                drawerEnabled = true,
                drawerOpenOnCashSale = true,
                hasCashPayment = false,
            ),
        )
    }

    @Test
    fun afterReceiptIsReprintEvenWhenNoAutomaticCopyCompleted() {
        assertFalse(ReceiptReprintPolicyV136.isReprint(1_000L, 1_000L, 0))
        assertTrue(ReceiptReprintPolicyV136.isReprint(1_001L, 1_000L, 0))
        assertTrue(ReceiptReprintPolicyV136.isReprint(1_000L, 1_000L, 1))
    }

    @Test
    fun sourceContractsKeepAutoPrintReprintQueueAndDrawerIndependent() {
        val settings = source("AdminSettingsStore.kt")
        val database = source("RegisterDatabase.kt")
        val receipt = source("Receipt.kt")
        val queue = source("UnifiedPrintQueue.kt")
        val activity = source("AdminSettingsActivity.kt")
        val main = source("MainActivity.kt")
        val drawer = source("CashDrawerSafetyV136.kt")

        assertTrue(settings.contains("receipt_auto_print INTEGER NOT NULL DEFAULT 1"))
        assertTrue(settings.contains("receiptAutoPrintEnabled: Boolean = true"))
        assertTrue(database.contains("ReceiptAutoPrintPolicyV136.shouldCreateAutomaticReceiptJob"))
        assertTrue(database.contains("detail.summary.createdAt + 1L"))
        assertTrue(receipt.contains("ReceiptReprintPolicyV136.isReprint"))
        assertTrue(receipt.contains("openDrawer = false"))
        assertTrue(queue.contains("ReceiptReprintPolicyV136.isReprint"))
        assertTrue(activity.contains("会計確定時にレシートを自動発行"))
        assertTrue(activity.contains("単独開放は無効"))
        assertTrue(main.contains("ReceiptAutoPrintRuntimeV136.dispatchDrawerIfNeeded"))
        assertTrue(drawer.contains("event_key TEXT NOT NULL UNIQUE"))
    }
}
