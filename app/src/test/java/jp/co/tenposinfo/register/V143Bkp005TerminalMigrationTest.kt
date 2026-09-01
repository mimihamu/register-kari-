package jp.co.tenposinfo.register

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class V143Bkp005TerminalMigrationTest {
    private val backup = SalesJournalIdentity("STORE-A", "TERMINAL-OLD", generation = 4)
    private val spare = SalesJournalIdentity("STORE-A", "TERMINAL-SPARE-BOOT", generation = 1)

    @Test
    fun spareTerminalRequiresTrustedStoreReentryOldTerminalStopAndRemoteMaximum() {
        assertFailsWith<IllegalArgumentException> {
            RestoreTerminalMigrationPolicyV136.plan(
                RestoreTerminalMigrationRequestV136(
                    mode = RestoreTerminalModeV136.SPARE_TERMINAL,
                    confirmedStoreName = "店舗A",
                    oldTerminalStopped = false,
                    remoteAckMaxSaleId = 120,
                ),
                backupStoreName = "店舗A",
                backupIdentity = backup,
                currentIdentity = spare,
                currentKnownMaxSaleId = 100,
                backupKnownMaxSaleId = 90,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            RestoreTerminalMigrationPolicyV136.plan(
                RestoreTerminalMigrationRequestV136(
                    mode = RestoreTerminalModeV136.SPARE_TERMINAL,
                    confirmedStoreName = "別店舗",
                    oldTerminalStopped = true,
                    remoteAckMaxSaleId = 120,
                ),
                backupStoreName = "店舗A",
                backupIdentity = backup,
                currentIdentity = spare,
                currentKnownMaxSaleId = 100,
                backupKnownMaxSaleId = 90,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            RestoreTerminalMigrationPolicyV136.plan(
                RestoreTerminalMigrationRequestV136(
                    mode = RestoreTerminalModeV136.SPARE_TERMINAL,
                    confirmedStoreName = "店舗A",
                    oldTerminalStopped = true,
                    remoteAckMaxSaleId = null,
                ),
                backupStoreName = "店舗A",
                backupIdentity = backup,
                currentIdentity = spare,
                currentKnownMaxSaleId = 100,
                backupKnownMaxSaleId = 90,
            )
        }
        assertFailsWith<IllegalStateException> {
            RestoreTerminalMigrationPolicyV136.plan(
                RestoreTerminalMigrationRequestV136(
                    mode = RestoreTerminalModeV136.SPARE_TERMINAL,
                    confirmedStoreName = "店舗A",
                    oldTerminalStopped = true,
                    remoteAckMaxSaleId = 120,
                ),
                backupStoreName = null,
                backupIdentity = backup,
                currentIdentity = spare,
                currentKnownMaxSaleId = 100,
                backupKnownMaxSaleId = 90,
            )
        }
    }

    @Test
    fun spareTerminalIssuesNewIdentityGenerationAndUsesGreatestKnownSaleAsFloor() {
        val plan = RestoreTerminalMigrationPolicyV136.plan(
            RestoreTerminalMigrationRequestV136(
                mode = RestoreTerminalModeV136.SPARE_TERMINAL,
                confirmedStoreName = "店舗A",
                oldTerminalStopped = true,
                remoteAckMaxSaleId = 130,
            ),
            backupStoreName = "店舗A",
            backupIdentity = backup,
            currentIdentity = spare.copy(generation = 7),
            currentKnownMaxSaleId = 125,
            backupKnownMaxSaleId = 110,
            newTerminalId = { "TERMINAL-NEW" },
        )
        assertEquals("TERMINAL-NEW", plan.targetTerminalId)
        assertNotEquals(backup.terminalId, plan.targetTerminalId)
        assertEquals(8L, plan.targetGeneration)
        assertEquals(130L, plan.saleSequenceFloor)
        assertEquals(130L, plan.remoteAckMaxSaleId)
    }

    @Test
    fun sameTerminalPreservesIdentityButNeverRewindsGenerationOrSequenceFloor() {
        val current = backup.copy(generation = 6)
        val plan = RestoreTerminalMigrationPolicyV136.plan(
            RestoreTerminalMigrationRequestV136.sameTerminal(),
            backupStoreName = null,
            backupIdentity = backup,
            currentIdentity = current,
            currentKnownMaxSaleId = 222,
            backupKnownMaxSaleId = 180,
        )
        assertEquals(backup.terminalId, plan.targetTerminalId)
        assertEquals(6L, plan.targetGeneration)
        assertEquals(222L, plan.saleSequenceFloor)
    }

    @Test
    fun sourceWiresModePlanBootstrapAndSaleSequenceGuard() {
        val root = File(System.getProperty("user.dir"))
        fun source(name: String) = File(root, "src/main/java/jp/co/tenposinfo/register/$name").readText()
        val protection = source("DataProtection.kt")
        val bootstrap = source("DataRestoreBootstrapV086.kt")
        val registerDb = source("RegisterDatabase.kt")
        val identity = source("SalesJournalJsonContract.kt")
        val activity = source("DataProtectionActivity.kt")
        val preflight = source("RestorePreflightV136.kt")
        val helper = source("RestoreTerminalMigrationV136.kt")

        assertTrue(protection.contains("restore_mode"))
        assertTrue(protection.contains("sale_sequence_floor"))
        assertTrue(protection.contains("remote_ack_max_sale_id"))
        assertTrue(protection.contains("backupStoreName(extractedContent)"))
        assertTrue(bootstrap.contains("RestoreTerminalMigrationV136.apply(database, plan)"))
        assertTrue(registerDb.contains("SaleSequenceSafetyV136.enforceBeforeSale(this)"))
        assertTrue(identity.contains("sales_journal_terminal_generation"))
        assertTrue(identity.contains("terminalGeneration"))
        assertTrue(preflight.contains("allowSpareTerminalMigration"))
        assertTrue(helper.contains("o.status='SENT'"))
        assertTrue(helper.contains("sqlite_sequence"))
        assertTrue(activity.contains("同一端末復旧"))
        assertTrue(activity.contains("予備端末移行"))
        assertTrue(activity.contains("店舗名を再入力"))
        assertTrue(activity.contains("旧端末停止を確認"))
        assertTrue(activity.contains("Drive ACK/既存イベント 最大売上番号"))
    }
}
