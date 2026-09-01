package jp.co.tenposinfo.register

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class V143Bkp005TerminalMigrationTest {
    private val backup = SalesJournalIdentity("STORE-A", "TERMINAL-OLD", generation = 4)
    private val spare = SalesJournalIdentity("STORE-UNCONFIGURED", "TERMINAL-SPARE-BOOT", generation = 1)
    private val root = File(System.getProperty("user.dir")).let { current ->
        if (File(current, "app").isDirectory) File(current, "app") else current
    }

    @Test
    fun spareTerminalRequiresTrustedStoreReentryOldTerminalStopAndRemoteMaximum() {
        assertThrows(IllegalArgumentException::class.java) {
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
                currentKnownMaxSaleId = 0,
                backupKnownMaxSaleId = 90,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
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
                currentKnownMaxSaleId = 0,
                backupKnownMaxSaleId = 90,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
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
                currentKnownMaxSaleId = 0,
                backupKnownMaxSaleId = 90,
            )
        }
        assertThrows(IllegalStateException::class.java) {
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
                currentKnownMaxSaleId = 0,
                backupKnownMaxSaleId = 90,
            )
        }
    }

    @Test
    fun spareTerminalRejectsConfiguredOrPreviouslyUsedCurrentTerminal() {
        val request = RestoreTerminalMigrationRequestV136(
            mode = RestoreTerminalModeV136.SPARE_TERMINAL,
            confirmedStoreName = "店舗A",
            oldTerminalStopped = true,
            remoteAckMaxSaleId = 130,
        )
        assertThrows(IllegalArgumentException::class.java) {
            RestoreTerminalMigrationPolicyV136.plan(
                request,
                backupStoreName = "店舗A",
                backupIdentity = backup,
                currentIdentity = spare.copy(storeId = "STORE-A"),
                currentKnownMaxSaleId = 0,
                backupKnownMaxSaleId = 110,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            RestoreTerminalMigrationPolicyV136.plan(
                request,
                backupStoreName = "店舗A",
                backupIdentity = backup,
                currentIdentity = spare,
                currentKnownMaxSaleId = 1,
                backupKnownMaxSaleId = 110,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            RestoreTerminalMigrationPolicyV136.plan(
                request,
                backupStoreName = "店舗A",
                backupIdentity = backup,
                currentIdentity = spare.copy(generation = 2),
                currentKnownMaxSaleId = 0,
                backupKnownMaxSaleId = 110,
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
            currentIdentity = spare,
            currentKnownMaxSaleId = 0,
            backupKnownMaxSaleId = 110,
            newTerminalId = { "TERMINAL-NEW" },
        )
        assertEquals("TERMINAL-NEW", plan.targetTerminalId)
        assertNotEquals(backup.terminalId, plan.targetTerminalId)
        assertEquals(5L, plan.targetGeneration)
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
        assertTrue(helper.contains("currentIdentity.storeId == \"STORE-UNCONFIGURED\""))
        assertTrue(helper.contains("o.status='SENT'"))
        assertTrue(helper.contains("sqlite_sequence"))
        assertTrue(activity.contains("同一端末復旧"))
        assertTrue(activity.contains("予備端末移行"))
        assertTrue(activity.contains("店舗名を再入力"))
        assertTrue(activity.contains("旧端末停止を確認"))
        assertTrue(activity.contains("Drive ACK/既存イベント 最大売上番号"))
    }
}
