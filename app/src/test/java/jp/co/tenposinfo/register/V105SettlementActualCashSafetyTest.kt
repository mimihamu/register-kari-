package jp.co.tenposinfo.register

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class V105SettlementActualCashSafetyTest {
    private val root = File(System.getProperty("user.dir")).let { current ->
        if (File(current, "app").isDirectory) current else current.parentFile
    }

    @Test
    fun zSettlementRequiresExplicitActualCash() {
        assertFalse(SettlementActualCashSafetyV105.maySubmit(SettlementReportType.Z_SETTLEMENT, null))
        assertEquals(
            SettlementActualCashSafetyV105.Z_REQUIRED_MESSAGE,
            SettlementActualCashSafetyV105.validationMessage(SettlementReportType.Z_SETTLEMENT, null),
        )
        assertFailureMessage(SettlementActualCashSafetyV105.Z_REQUIRED_MESSAGE) {
            SettlementActualCashSafetyV105.effectiveActualCash(
                SettlementReportType.Z_SETTLEMENT,
                null,
                expectedCash = 12_345L,
            )
        }
    }

    @Test
    fun zSettlementAcceptsExplicitZeroAndPositiveActualCash() {
        assertTrue(SettlementActualCashSafetyV105.maySubmit(SettlementReportType.Z_SETTLEMENT, 0L))
        assertEquals(
            0L,
            SettlementActualCashSafetyV105.effectiveActualCash(
                SettlementReportType.Z_SETTLEMENT,
                0L,
                expectedCash = 12_345L,
            ),
        )
        assertEquals(
            12_300L,
            SettlementActualCashSafetyV105.effectiveActualCash(
                SettlementReportType.Z_SETTLEMENT,
                12_300L,
                expectedCash = 12_345L,
            ),
        )
    }

    @Test
    fun negativeActualCashIsRejectedForBothXAndZ() {
        listOf(SettlementReportType.X_INSPECTION, SettlementReportType.Z_SETTLEMENT).forEach { type ->
            assertFailureMessage(SettlementActualCashSafetyV105.NON_NEGATIVE_MESSAGE) {
                SettlementActualCashSafetyV105.effectiveActualCash(type, -1L, expectedCash = 10_000L)
            }
        }
    }

    @Test
    fun xInspectionBlankActualCashKeepsHistoricalTheoreticalFallback() {
        assertTrue(SettlementActualCashSafetyV105.maySubmit(SettlementReportType.X_INSPECTION, null))
        assertEquals(
            9_876L,
            SettlementActualCashSafetyV105.effectiveActualCash(
                SettlementReportType.X_INSPECTION,
                null,
                expectedCash = 9_876L,
            ),
        )
    }

    @Test
    fun uiCoordinatorAndStoreAllEnforceTheSamePolicy() {
        val ui = File(
            root,
            "app/src/main/java/jp/co/tenposinfo/register/SettlementActivityV030.kt",
        ).readText()
        val coordinator = File(
            root,
            "app/src/main/java/jp/co/tenposinfo/register/SecureOperationsCoordinator.kt",
        ).readText()
        val store = File(
            root,
            "app/src/main/java/jp/co/tenposinfo/register/OperationsStore.kt",
        ).readText()

        assertTrue(ui.contains("現金実査額（必須）"))
        assertTrue(ui.contains("SettlementActualCashSafetyV105.maySubmit(reportType, actualCash.toLongOrNull())"))
        assertTrue(ui.contains("if (isZ) actual else actual ?: summary.expectedCash"))
        assertTrue(ui.contains("?: \"未入力\""))
        assertTrue(ui.contains("?: \"未計算\""))
        assertTrue(coordinator.contains("SettlementActualCashSafetyV105.validate(type, actualCash)"))
        assertTrue(store.contains("SettlementActualCashSafetyV105.effectiveActualCash("))
        assertFalse(store.contains("val actual = actualCash ?: summary.expectedCash"))
    }

    @Test
    fun zSafetyDoesNotWeakenExistingSettlementTransactionOrPreflight() {
        val store = File(
            root,
            "app/src/main/java/jp/co/tenposinfo/register/OperationsStore.kt",
        ).readText()
        val start = store.indexOf("fun recordSettlement(")
        val end = store.indexOf("fun recentSettlements(", start).takeIf { it > start } ?: store.length
        val body = store.substring(start, end)

        assertTrue(body.contains("return db.transaction"))
        assertTrue(body.contains("ZSettlementPreflightPolicy.evaluate("))
        assertTrue(body.contains("claimOperationKey("))
        assertTrue(body.contains("SettlementActualCashSafetyV105.effectiveActualCash("))

        // 現行の営業終了は同一transaction内の条件付きUPDATE。
        assertTrue(body.contains("\"business_sessions\""))
        assertTrue(body.contains("BusinessSessionLifecyclePolicy.resultStatus(type, session.status).name"))
        assertTrue(body.contains("\"id = ? AND status = ?\""))
        assertTrue(body.contains("BusinessSessionStatus.OPEN.name"))
        assertTrue(body.contains("check(updated == 1)"))
    }

    @Test
    fun releaseHistoryDocumentationAndPhysicalDeferralAreExplicit() {
        val gradle = File(root, "app/build.gradle.kts").readText()
        val currentVersionCode = Regex("versionCode = (\\d+)")
            .find(gradle)?.groupValues?.get(1)?.toIntOrNull() ?: error("versionCode missing")
        val currentVersion = Regex("versionName = \\\"([^\\\"]+)\\\"")
            .find(gradle)?.groupValues?.get(1) ?: error("versionName missing")
        assertTrue(currentVersionCode >= 135)
        assertTrue(currentVersion.isNotBlank())

        val design = File(root, "docs/V1.05_Z_SETTLEMENT_ACTUAL_CASH_SAFETY.md")
        val notes = File(root, "docs/V1.05_RELEASE_NOTES.md")
        assertTrue(design.isFile)
        assertTrue(notes.isFile)
        assertTrue(design.readText().contains(SettlementActualCashSafetyV105.Z_REQUIRED_MESSAGE))
        val notesText = notes.readText()
        assertTrue(notesText.contains("versionCode `135`"))
        assertTrue(notesText.contains("1.05.0-dev.1"))
        assertTrue(notesText.contains("最終総合実機試験"))
    }

    private fun assertFailureMessage(expected: String, action: () -> Unit) {
        val error = runCatching(action).exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
        assertEquals(expected, error?.message)
    }
}
