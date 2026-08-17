package jp.co.tenposinfo.register

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** v1.35 REP-003: Z精算前に5種類の未処理状態を表示し、項目別の継続可否を強制する。 */
class V135SettlementPreflightRep003Test {
    private val root = File(System.getProperty("user.dir")).let { current ->
        if (File(current, "app").isDirectory) current else current.parentFile
    }

    private fun source(name: String): String = File(
        root,
        "app/src/main/java/jp/co/tenposinfo/register/$name",
    ).readText()

    @Test
    fun allFiveRep003CategoriesAreAlwaysReturnedInFixedOrder() {
        val result = ZSettlementPreflightPolicy.evaluate(0, 0, false)
        assertEquals(
            listOf(
                SettlementPreflightCategoryV135.OPEN_TICKETS,
                SettlementPreflightCategoryV135.INCOMPLETE_PAYMENT,
                SettlementPreflightCategoryV135.PENDING_PRINT,
                SettlementPreflightCategoryV135.BACKUP_FAILURE,
                SettlementPreflightCategoryV135.ACTUAL_CASH_MISSING,
            ),
            result.items.map { it.category },
        )
        assertTrue(result.mayProceed)
        assertTrue(result.items.none { it.active })
    }

    @Test
    fun openHeldOrCurrentCartAlwaysBlocksSettlement() {
        val held = ZSettlementPreflightPolicy.evaluate(
            heldTickets = 2,
            pendingPrints = 0,
            pendingPrintsAcknowledged = true,
        )
        val currentCart = ZSettlementPreflightPolicy.evaluate(
            heldTickets = 0,
            pendingPrints = 0,
            pendingPrintsAcknowledged = true,
            openCartItems = 3,
        )
        assertFalse(held.mayProceed)
        assertFalse(currentCart.mayProceed)
        assertEquals(
            SettlementPreflightContinuationV135.BLOCK,
            currentCart.items.first { it.category == SettlementPreflightCategoryV135.OPEN_TICKETS }.continuation,
        )
        assertTrue(currentCart.message.orEmpty().contains("販売途中取引"))
    }

    @Test
    fun incompletePaymentAlwaysBlocksSettlement() {
        val result = ZSettlementPreflightPolicy.evaluate(
            heldTickets = 0,
            pendingPrints = 0,
            pendingPrintsAcknowledged = true,
            incompletePayments = 1,
        )
        assertFalse(result.mayProceed)
        assertTrue(result.message.orEmpty().contains("未完了決済が1件"))
        assertEquals(
            SettlementPreflightContinuationV135.BLOCK,
            result.items.first { it.category == SettlementPreflightCategoryV135.INCOMPLETE_PAYMENT }.continuation,
        )
    }

    @Test
    fun pendingPrintRequiresExplicitAcknowledgementOnly() {
        val blocked = ZSettlementPreflightPolicy.evaluate(
            heldTickets = 0,
            pendingPrints = 4,
            pendingPrintsAcknowledged = false,
        )
        val approved = ZSettlementPreflightPolicy.evaluate(
            heldTickets = 0,
            pendingPrints = 4,
            pendingPrintsAcknowledged = true,
        )
        assertFalse(blocked.mayProceed)
        assertTrue(approved.mayProceed)
        assertEquals(
            SettlementPreflightContinuationV135.ACKNOWLEDGE,
            approved.items.first { it.category == SettlementPreflightCategoryV135.PENDING_PRINT }.continuation,
        )
    }

    @Test
    fun backupFailureRequiresExplicitAcknowledgementOnly() {
        val blocked = ZSettlementPreflightPolicy.evaluate(
            heldTickets = 0,
            pendingPrints = 0,
            pendingPrintsAcknowledged = false,
            backupFailureMessage = "Drive書込失敗",
            backupFailureAcknowledged = false,
        )
        val approved = ZSettlementPreflightPolicy.evaluate(
            heldTickets = 0,
            pendingPrints = 0,
            pendingPrintsAcknowledged = false,
            backupFailureMessage = "Drive書込失敗",
            backupFailureAcknowledged = true,
        )
        assertFalse(blocked.mayProceed)
        assertTrue(blocked.requiresBackupFailureAcknowledgement)
        assertTrue(approved.mayProceed)
        assertEquals(
            SettlementPreflightContinuationV135.ACKNOWLEDGE,
            approved.items.first { it.category == SettlementPreflightCategoryV135.BACKUP_FAILURE }.continuation,
        )
    }

    @Test
    fun missingActualCashCannotBeAcknowledgedAround() {
        val result = ZSettlementPreflightPolicy.evaluate(
            heldTickets = 0,
            pendingPrints = 0,
            pendingPrintsAcknowledged = true,
            actualCashEntered = false,
            backupFailureAcknowledged = true,
        )
        assertFalse(result.mayProceed)
        assertTrue(result.actualCashMissing)
        assertTrue(result.message.orEmpty().contains("現金実査"))
        assertEquals(
            SettlementPreflightContinuationV135.BLOCK,
            result.items.first { it.category == SettlementPreflightCategoryV135.ACTUAL_CASH_MISSING }.continuation,
        )
    }

    @Test
    fun multipleAcknowledgementWarningsRequireAllApprovals() {
        val onlyPrintApproved = ZSettlementPreflightPolicy.evaluate(
            heldTickets = 0,
            pendingPrints = 2,
            pendingPrintsAcknowledged = true,
            backupFailureMessage = "直近バックアップ失敗",
            backupFailureAcknowledged = false,
        )
        val allApproved = ZSettlementPreflightPolicy.evaluate(
            heldTickets = 0,
            pendingPrints = 2,
            pendingPrintsAcknowledged = true,
            backupFailureMessage = "直近バックアップ失敗",
            backupFailureAcknowledged = true,
        )
        assertFalse(onlyPrintApproved.mayProceed)
        assertTrue(allApproved.mayProceed)
    }

    @Test
    fun storeCollectsAllFiveFactsAndRechecksImmediatelyBeforeZPersistence() {
        val store = source("OperationsStore.kt")
        assertTrue(store.contains("SELECT COUNT(*) FROM held_tickets"))
        assertTrue(store.contains("SELECT COUNT(*) FROM cart_items"))
        assertTrue(store.contains("payment_draft_meta"))
        assertTrue(store.contains("AutoBackupStatusStore(appContext).load()"))
        assertTrue(store.contains("actualCashEntered = actualCash != null"))
        assertTrue(store.contains("backupFailureAcknowledged = backupFailureAcknowledged"))

        val recordStart = store.indexOf("fun recordSettlement(")
        val insertStart = store.indexOf("\"settlement_reports\"", recordStart)
        val preflightStart = store.indexOf("ZSettlementPreflightPolicy.evaluate(", recordStart)
        assertTrue(recordStart >= 0)
        assertTrue(preflightStart in (recordStart + 1) until insertStart)
        assertTrue(store.indexOf("check(preflight.mayProceed)", preflightStart) in (preflightStart + 1) until insertStart)
    }

    @Test
    fun xInspectionStillUsesRep001OutputPathAndDoesNotCloseSession() {
        val store = source("OperationsStore.kt")
        val coordinator = source("SecureOperationsCoordinator.kt")
        val operations = source("OperationsActivity.kt")
        val responsive = source("SettlementActivityV030.kt")

        val recordStart = store.indexOf("fun recordSettlement(")
        val recentStart = store.indexOf("fun recentSettlements(", recordStart)
        val record = store.substring(recordStart, recentStart)
        assertTrue(record.contains("\"settlement_reports\""))
        assertTrue(record.contains("insertDocumentJob("))
        assertTrue(record.contains("if (type == SettlementReportType.Z_SETTLEMENT)"))
        assertFalse(record.contains("require(type == SettlementReportType.Z_SETTLEMENT)"))

        assertTrue(coordinator.contains("runCatching { AutomaticPrintScheduler.enqueueNow(appContext) }"))
        assertTrue(operations.contains("SettlementReportType.X_INSPECTION"))
        assertTrue(operations.contains("X点検は期間を締めず現在値を保存・印刷します"))
        assertTrue(responsive.contains("secureStore.recordSettlement("))
    }

    @Test
    fun bothSettlementScreensExposeFiveItemPreflightAndAcknowledgements() {
        val operations = source("OperationsActivity.kt")
        val responsive = source("SettlementActivityV030.kt")
        listOf(operations, responsive).forEach { screen ->
            assertTrue(screen.contains("精算前確認（REP-003）"))
            assertTrue(screen.contains("SettlementPreflightCategoryV135.PENDING_PRINT"))
            assertTrue(screen.contains("SettlementPreflightCategoryV135.BACKUP_FAILURE"))
            assertTrue(screen.contains("backupFailureAcknowledged"))
            assertTrue(screen.contains("preflight.mayProceed") || screen.contains("zPreflight.mayProceed"))
        }
    }
}
