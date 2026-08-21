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
    fun drawerIsPreservedOnlyAsSeparateCheckoutActionWhenAutoReceiptIsOff() {
        assertTrue(
  ReceiptAutoPrintPolicyV136.shouldOpenDrawerSeparately(
      receiptAutoPrintEnabled = false,
      printerUsable = true,
      drawerEnabled = true,
      drawerOpenOnCashSale = true,
      hasCashPayment = true,
  ),
        )
        assertFalse(
  ReceiptAutoPrintPolicyV136.shouldOpenDrawerSeparately(
      receiptAutoPrintEnabled = true,
      printerUsable = true,
      drawerEnabled = true,
      drawerOpenOnCashSale = true,
      hasCashPayment = true,
  ),
        )
        assertFalse(
  ReceiptAutoPrintPolicyV136.shouldOpenDrawerSeparately(
      receiptAutoPrintEnabled = false,
      printerUsable = false,
      drawerEnabled = true,
      drawerOpenOnCashSale = true,
      hasCashPayment = true,
  ),
        )
        assertFalse(
  ReceiptAutoPrintPolicyV136.shouldOpenDrawerSeparately(
      receiptAutoPrintEnabled = false,
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

        assertTrue(settings.contains("receipt_auto_print INTEGER NOT NULL DEFAULT 1"))
        assertTrue(settings.contains("receiptAutoPrintEnabled: Boolean = true"))
        assertTrue(database.contains("ReceiptAutoPrintPolicyV136.shouldCreateAutomaticReceiptJob"))
        assertTrue(database.contains("detail.summary.createdAt + 1L"))
        assertTrue(receipt.contains("ReceiptReprintPolicyV136.isReprint"))
        assertTrue(queue.contains("ReceiptReprintPolicyV136.isReprint"))
        assertTrue(activity.contains("会計確定時にレシートを自動発行"))
        assertTrue(activity.contains("OFFでも売上一覧・会計完了から後レシート／再印字できます"))
        assertTrue(main.contains("ReceiptAutoPrintRuntimeV136.dispatchDrawerIfNeeded"))
    }
}
