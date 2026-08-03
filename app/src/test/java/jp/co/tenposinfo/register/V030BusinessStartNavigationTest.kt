package jp.co.tenposinfo.register

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class V030BusinessStartNavigationTest {
    private fun source(name: String) = File("src/main/java/jp/co/tenposinfo/register/$name").readText()

    @Test
    fun businessStartGateUsesZSettlementPermission() {
        val application = source("RegisterApplication.kt")
        val block = application
            .substringAfter("private fun updateBusinessDayGate")
            .substringBefore("private fun isCanonicalBusinessSessionOpen")

        assertTrue(block.contains("operator.allows(RegisterPermission.Z_SETTLEMENT)"))
        assertTrue(block.contains("operator.allows(RegisterPermission.SETTLEMENT)"))
        assertTrue(block.contains("営業開始・状態画面へ"))
        assertFalse(block.contains("if (operator.allows(RegisterPermission.SETTLEMENT)) {"))
    }

    @Test
    fun managementRoutesRecognizeSplitInspectionAndSettlementPermissions() {
        val application = source("RegisterApplication.kt")

        assertTrue(application.countOccurrences("it == RegisterPermission.X_INSPECTION") >= 3)
        assertTrue(application.countOccurrences("it == RegisterPermission.Z_SETTLEMENT") >= 3)
    }

    private fun String.countOccurrences(value: String): Int =
        windowed(value.length, 1).count { it == value }
}
