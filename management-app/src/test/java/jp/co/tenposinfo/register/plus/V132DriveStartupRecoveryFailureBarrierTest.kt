package jp.co.tenposinfo.register.plus

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class V132DriveStartupRecoveryFailureBarrierTest {
    @Test
    fun barrierBlocksDriveSyncUntilNextProcessReset() {
        GoogleDriveStartupRecoveryBarrierV132.resetForProcessStart()
        try {
            assertFalse(GoogleDriveStartupRecoveryBarrierV132.isBlocked())

            GoogleDriveStartupRecoveryBarrierV132.block(
                stage = "orphan recovery",
                error = IllegalStateException("commit failed"),
            )

            assertTrue(GoogleDriveStartupRecoveryBarrierV132.isBlocked())
            assertTrue(GoogleDriveStartupRecoveryBarrierV132.reason().orEmpty().contains("commit failed"))

            GoogleDriveStartupRecoveryBarrierV132.resetForProcessStart()
            assertFalse(GoogleDriveStartupRecoveryBarrierV132.isBlocked())
        } finally {
            GoogleDriveStartupRecoveryBarrierV132.resetForProcessStart()
        }
    }

    @Test
    fun blockedBarrierRejectsRepositoryStartWithoutStatusMutation() {
        GoogleDriveStartupRecoveryBarrierV132.resetForProcessStart()
        try {
            GoogleDriveStartupRecoveryBarrierV132.block(
                stage = "history recovery",
                error = IllegalStateException("baseline commit failed"),
            )

            try {
                GoogleDriveStartupRecoveryBarrierV132.requireDriveSyncAllowed()
                fail("blocked barrier must reject Drive sync start")
            } catch (error: GoogleDriveStartupRecoveryBlockedException) {
                assertTrue(error.message.orEmpty().contains("同期を開始しません"))
            }
        } finally {
            GoogleDriveStartupRecoveryBarrierV132.resetForProcessStart()
        }
    }

    @Test
    fun startupProvidersAndAllDriveStartPathsAreFailClosed() {
        val orphan = File(
            "src/main/java/jp/co/tenposinfo/register/plus/GoogleDriveOrphanedRunRecoveryV131.kt",
        ).readText()
        val history = File(
            "src/main/java/jp/co/tenposinfo/register/plus/GoogleDriveVerificationHistoryRecoveryV130.kt",
        ).readText()
        val direct = File(
            "src/main/java/jp/co/tenposinfo/register/plus/GoogleDriveDirectSync.kt",
        ).readText()

        val orphanProvider = orphan.indexOf("class GoogleDriveOrphanedRunRecoveryProviderV131")
        val barrierReset = orphan.indexOf("GoogleDriveStartupRecoveryBarrierV132.resetForProcessStart()", orphanProvider)
        val orphanRecovery = orphan.indexOf("val recovery = runCatching", barrierReset)
        val orphanBlock = orphan.indexOf("GoogleDriveStartupRecoveryBarrierV132.block(", orphanRecovery)
        assertTrue(orphanProvider >= 0)
        assertTrue(barrierReset > orphanProvider)
        assertTrue(orphanRecovery > barrierReset)
        assertTrue(orphanBlock > orphanRecovery)

        val install = history.indexOf("fun install(context: Context)")
        val installGuard = history.indexOf("GoogleDriveStartupRecoveryBarrierV132.isBlocked()", install)
        val listener = history.indexOf("OnSharedPreferenceChangeListener", install)
        assertTrue(installGuard > install)
        assertTrue(listener > installGuard)
        assertTrue(history.contains("check(stateStore.markObservedDurably(status.lastCompletedAt))"))

        val historyProvider = history.indexOf("class GoogleDriveVerificationHistoryRecoveryProviderV130")
        val historyProviderGuard = history.indexOf("GoogleDriveStartupRecoveryBarrierV132.isBlocked()", historyProvider)
        val historyRecovery = history.indexOf("val recovery = runCatching", historyProviderGuard)
        val historyBlock = history.indexOf("GoogleDriveStartupRecoveryBarrierV132.block(", historyRecovery)
        assertTrue(historyProviderGuard > historyProvider)
        assertTrue(historyRecovery > historyProviderGuard)
        assertTrue(historyBlock > historyRecovery)

        val repository = direct.indexOf("class GoogleDriveDirectSyncRepository")
        val repositoryGuard = direct.indexOf(
            "GoogleDriveStartupRecoveryBarrierV132.requireDriveSyncAllowed()",
            repository,
        )
        val statusRunning = direct.indexOf("val runToken = statusStore.running()", repository)
        assertTrue(repositoryGuard > repository)
        assertTrue(statusRunning > repositoryGuard)

        val worker = direct.indexOf("class GoogleDriveDirectSyncWorker")
        val workerGuard = direct.indexOf(
            "if (GoogleDriveStartupRecoveryBarrierV132.isBlocked()) return Result.success()",
            worker,
        )
        val accountLoad = direct.indexOf("GoogleDriveAccountStore(applicationContext).load()", worker)
        assertTrue(workerGuard > worker)
        assertTrue(accountLoad > workerGuard)

        val workerEnd = direct.indexOf("object GoogleDriveDirectSyncScheduler", worker)
        val workerSource = direct.substring(worker, workerEnd)
        val blockedFailure = workerSource.indexOf("error is GoogleDriveStartupRecoveryBlockedException")
        val statusFailure = workerSource.indexOf("statusStore.failed(")
        assertTrue(blockedFailure >= 0)
        assertTrue(statusFailure > blockedFailure)

        val scheduler = direct.substring(workerEnd, direct.indexOf("class GoogleDriveDirectSyncBootstrapProvider", workerEnd))
        assertTrue(scheduler.contains("fun setAutomaticSyncEnabled"))
        assertTrue(scheduler.contains("fun ensurePeriodic"))
        assertTrue(scheduler.contains("fun enqueueStartup"))
        assertTrue(scheduler.contains("fun enqueueNow"))
        assertTrue(scheduler.contains("GoogleDriveStartupRecoveryBarrierV132.isBlocked()"))

        val bootstrap = direct.indexOf("class GoogleDriveDirectSyncBootstrapProvider")
        val bootstrapGuard = direct.indexOf(
            "if (GoogleDriveStartupRecoveryBarrierV132.isBlocked()) return true",
            bootstrap,
        )
        val bootstrapSchedule = direct.indexOf("GoogleDriveDirectSyncScheduler.setAutomaticSyncEnabled", bootstrap)
        assertTrue(bootstrapGuard > bootstrap)
        assertTrue(bootstrapSchedule > bootstrapGuard)
    }
}
