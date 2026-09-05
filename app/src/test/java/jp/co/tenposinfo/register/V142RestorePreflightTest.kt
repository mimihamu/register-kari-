package jp.co.tenposinfo.register

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class V142RestorePreflightTest {
    private val root = File(System.getProperty("user.dir")).let { current ->
        if (File(current, "app").isDirectory) current else current.parentFile
    }

    private fun base() = RestorePreflightInputsV136(
        envelopeFormat = BackupEnvelopeV136.FORMAT,
        contentFormat = BackupContentBundleV136.FORMAT,
        backupAppVersion = "1.34.0-dev.1",
        currentAppVersion = "1.34.0-dev.1",
        backupDatabaseSchema = 36,
        currentDatabaseSchema = 36,
        backupStoreId = "STORE-A",
        currentStoreId = "STORE-A",
        backupTerminalId = "TERMINAL-A",
        currentTerminalId = "TERMINAL-A",
        hashVerified = true,
        requiredFreeBytes = 256L * 1024L * 1024L,
        availableFreeBytes = 1024L * 1024L * 1024L,
        backupDrive = RestoreDriveDestinationV136(true, true, "permission-1", "つぐレジ"),
        currentDrive = RestoreDriveDestinationV136(true, true, "permission-1", "つぐレジ"),
    )

    @Test
    fun matchingBackupPassesAllPreflightChecks() {
        val decision = RestorePreflightPolicyV136.evaluate(base())
        assertTrue(decision.mayRestore)
        assertTrue(decision.checks.all { it.disposition == RestorePreflightDispositionV136.PASS })
    }

    @Test
    fun olderAppAndSchemaAreExplicitMigrationNotSilentMismatch() {
        val decision = RestorePreflightPolicyV136.evaluate(
            base().copy(backupAppVersion = "1.33.0", backupDatabaseSchema = 35),
        )
        assertTrue(decision.mayRestore)
        assertTrue(decision.checks.any { it.code == "APP_VERSION" && it.disposition == RestorePreflightDispositionV136.MIGRATE })
        assertTrue(decision.checks.any { it.code == "DB_SCHEMA" && it.disposition == RestorePreflightDispositionV136.MIGRATE })
    }

    @Test
    fun futureAppOrSchemaIsRejectedWithReason() {
        val decision = RestorePreflightPolicyV136.evaluate(
            base().copy(backupAppVersion = "1.35.0", backupDatabaseSchema = 37),
        )
        assertFalse(decision.mayRestore)
        assertTrue(decision.blockingReasons.any { it.contains("アプリ版") })
        assertTrue(decision.blockingReasons.any { it.contains("DB schema") })
    }

    @Test
    fun storeAndTerminalMismatchAreRejectedUntilBkp005Migration() {
        val decision = RestorePreflightPolicyV136.evaluate(
            base().copy(currentStoreId = "STORE-B", currentTerminalId = "TERMINAL-B"),
        )
        assertFalse(decision.mayRestore)
        assertTrue(decision.blockingReasons.any { it.contains("storeId") })
        assertTrue(decision.blockingReasons.any { it.contains("terminalId") && it.contains("BKP-005") })
    }

    @Test
    fun hashAndFreeSpaceFailuresAreRejected() {
        val decision = RestorePreflightPolicyV136.evaluate(
            base().copy(hashVerified = false, availableFreeBytes = 1L),
        )
        assertFalse(decision.mayRestore)
        assertTrue(decision.blockingReasons.any { it.contains("hash") })
        assertTrue(decision.blockingReasons.any { it.contains("空き容量") })
    }

    @Test
    fun driveAccountMismatchBlocksWrongDestination() {
        val decision = RestorePreflightPolicyV136.evaluate(
            base().copy(
                currentDrive = RestoreDriveDestinationV136(true, true, "permission-OTHER", "つぐレジ"),
            ),
        )
        assertFalse(decision.mayRestore)
        assertTrue(decision.blockingReasons.any { it.contains("Google Drive接続先") })
    }

    @Test
    fun missingDriveAuthorizationAndFolderDifferenceAreMigrationPaths() {
        val noAuth = RestorePreflightPolicyV136.evaluate(
            base().copy(currentDrive = RestoreDriveDestinationV136(true, false, null, "つぐレジ")),
        )
        assertTrue(noAuth.mayRestore)
        assertTrue(noAuth.checks.any { it.code == "DRIVE_DESTINATION" && it.disposition == RestorePreflightDispositionV136.MIGRATE })

        val folder = RestorePreflightPolicyV136.evaluate(
            base().copy(
                backupDrive = RestoreDriveDestinationV136(true, false, null, "旧フォルダ"),
                currentDrive = RestoreDriveDestinationV136(true, false, null, "現フォルダ"),
            ),
        )
        assertTrue(folder.mayRestore)
        assertTrue(folder.checks.any { it.code == "DRIVE_DESTINATION" && it.disposition == RestorePreflightDispositionV136.MIGRATE })
    }

    @Test
    fun productionRestoreRunsPreflightAndUiShowsDetailedResult() {
        val protection = File(root, "app/src/main/java/jp/co/tenposinfo/register/DataProtection.kt").readText()
        val activity = File(root, "app/src/main/java/jp/co/tenposinfo/register/DataProtectionActivity.kt").readText()
        val content = File(root, "app/src/main/java/jp/co/tenposinfo/register/BackupContentV136.kt").readText()
        assertTrue(protection.contains("fun preflightRestore(fileName: String): RestorePreflightReportV136"))
        assertTrue(protection.contains("require(preflight.mayRestore)"))
        assertTrue(protection.contains("RestorePreflightPolicyV136.evaluate"))
        assertTrue(activity.contains("manager.preflightRestore(file)"))
        assertTrue(activity.contains("preflight.displayText()"))
        assertTrue(content.contains("drive.account_key"))
        assertTrue(content.contains("drive.folder_name_b64"))
    }
}
