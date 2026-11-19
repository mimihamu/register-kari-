package jp.co.tenposinfo.register

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** v1.93 rejects a restored sale whose tender details do not match its ledger. */
class V193SalePaymentLedgerBackupIntegrityTest {
    private fun code(file: String): String =
        File("src/main/java/jp/co/tenposinfo/register/$file").readText()

    @Test fun validTendersAndContiguousSequencesPass() {
        assertTrue(SalePaymentLedgerIntegrityV193.validSequence(emptyList()))
        assertTrue(SalePaymentLedgerIntegrityV193.validSequence(listOf(1L, 2L, 3L)))
        assertTrue(SalePaymentLedgerIntegrityV193.validTender("CARD", 300L, 300L))
        assertTrue(SalePaymentLedgerIntegrityV193.validTender("CASH", 700L, 1000L))
    }

    @Test fun wrongMethodOrReceivedAmountNeverPassesEvenWhenSaleTotalMatches() {
        assertFalse(SalePaymentLedgerIntegrityV193.validTender("INVALID_METHOD", 1000L, 1000L))
        assertFalse(SalePaymentLedgerIntegrityV193.validTender("CASH", 500L, 400L))
        assertFalse(SalePaymentLedgerIntegrityV193.validTender("CASH", 0L, 0L))
        assertFalse(SalePaymentLedgerIntegrityV193.validTender("CASH", -1L, 1000L))
        assertFalse(SalePaymentLedgerIntegrityV193.validTender("QR", 500L, -1L))
    }

    @Test fun wrongOrDuplicateSequenceFailsEvenWhenAmountsSumCorrectly() {
        for (sequence in listOf(
            listOf(0L), listOf(-1L), listOf(2L),
            listOf(1L, 3L), listOf(1L, 1L, 3L),
            listOf(1L, 2L, 2L), listOf(1L, 2L, 4L),
            listOf(4_294_967_297L),
        )) {
            assertFalse(SalePaymentLedgerIntegrityV193.validSequence(sequence))
        }
    }

    @Test fun preRestoreAndPostRestoreBothRunTheAdditionalSqlChecks() {
        val source = code("DataProtection.kt")
        assertTrue(source.contains("ConsistencyCheck(\"SALE_PAYMENT_MISMATCH\""))
        assertTrue(source.contains("ConsistencyCheck(\"SALE_PAYMENT_ROW_INVALID\""))
        assertTrue(source.contains("ConsistencyCheck(\"SALE_PAYMENT_SEQUENCE_INVALID\""))
        assertTrue(source.contains("SalePaymentLedgerIntegrityV193.INVALID_ROW_SQL"))
        assertTrue(source.contains("SalePaymentLedgerIntegrityV193.INVALID_SEQUENCE_SQL"))

        val inspect = source.substringAfter("    private fun inspectDatabase(database: SQLiteDatabase)")
            .substringBefore("    private fun readTableCounts(")
        assertTrue(inspect.contains("issues += IntegrityIssue(check.code, check.message, IntegritySeverity.ERROR, count)"))
        assertTrue(code("DatabaseRecoveryIntegrityV116.kt")
            .contains("DataProtectionManager(appContext).diagnose()"))
        assertTrue(source.contains("require(report.healthy)"))
    }

    @Test fun sqlDoesNotDependOnUnsupportedSqliteWindowFunctions() {
        val policy = SalePaymentLedgerIntegrityV193
        assertTrue(policy.INVALID_ROW_SQL.contains("payment_method NOT IN"))
        assertTrue(policy.INVALID_ROW_SQL.contains("'GIFT_CERTIFICATE'"))
        assertTrue(policy.INVALID_ROW_SQL.contains("'ACCOUNT_RECEIVABLE'"))
        assertTrue(policy.INVALID_ROW_SQL.contains("received_amount < applied_amount"))
        assertTrue(policy.INVALID_SEQUENCE_SQL.contains("COUNT(DISTINCT sequence_no)"))
        assertTrue(policy.INVALID_SEQUENCE_SQL.contains("GROUP BY sale_id"))
        assertFalse(policy.INVALID_SEQUENCE_SQL.contains("ROW_NUMBER("))
        assertFalse(code("SalePaymentLedgerIntegrityV193.kt").contains("UPDATE sale_payments"))
        assertFalse(code("SalePaymentLedgerIntegrityV193.kt").contains("DELETE FROM sale_payments"))
    }
}
