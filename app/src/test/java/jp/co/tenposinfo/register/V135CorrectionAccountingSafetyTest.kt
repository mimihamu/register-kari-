package jp.co.tenposinfo.register

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** v1.35 COR-007 / COR-009 / COR-010 cumulative source safety gates. */
class V135CorrectionAccountingSafetyTest {
    @Test
    fun cor007ReversalKeepsOriginalTransactionCrossReference() {
        val source = File("src/main/java/jp/co/tenposinfo/register/OperationsStore.kt").readText()
        val reversal = source.functionBody("fun createReversal(", "fun createFullReversal(")

        assertTrue(reversal.contains("put(\"original_sale_id\", originalSaleId)"))
        assertTrue(reversal.contains("insertOrThrow(\n                \"reversal_transactions\""))
        assertTrue(reversal.contains("put(\"reversal_type\", type.name)"))
        assertTrue(source.contains("fun reversedSaleIds(): Set<Long>"))
        assertTrue(source.contains("SELECT rt.original_sale_id"))
        assertTrue(source.contains("fun recentReversals(limit: Int = 50): List<ReversalRecord>"))
        assertTrue(source.contains("arrayOf(\"id\", \"original_sale_id\", \"reversal_type\""))
    }

    @Test
    fun cor009CorrectionPostsToExecutionBusinessSessionWithoutRewritingOriginalSaleOrOldSettlement() {
        val source = File("src/main/java/jp/co/tenposinfo/register/OperationsStore.kt").readText()
        val reversal = source.functionBody("fun createReversal(", "fun createFullReversal(")

        assertTrue(reversal.contains("val session = queryActiveSession(this)"))
        assertTrue(reversal.contains("put(\"business_session_id\", session.id)"))
        assertTrue(reversal.contains("put(\"business_date\", session.businessDate)"))
        assertTrue(reversal.contains("put(\"original_sale_id\", originalSaleId)"))
        assertFalse(reversal.contains("update(\"sales\""))
        assertFalse(reversal.contains("delete(\"sales\""))
        assertFalse(reversal.contains("update(\"settlement_reports\""))
        assertFalse(reversal.contains("delete(\"settlement_reports\""))
        assertFalse(Regex("(?i)UPDATE\\s+sales\\b").containsMatchIn(reversal))
        assertFalse(Regex("(?i)UPDATE\\s+settlement_reports\\b").containsMatchIn(reversal))
    }

    @Test
    fun cor010ConfirmedBusinessTablesHaveNoPhysicalDeletePathInNormalApplicationKotlin() {
        val sourceRoot = File("src/main/java")
        val protectedTables = listOf("sales", "reversal_transactions", "cash_movements")
        val violations = mutableListOf<String>()

        sourceRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { file ->
                val source = file.readText()
                protectedTables.forEach { table ->
                    val sqlDelete = Regex("(?i)\\bDELETE\\s+FROM\\s+[`\\\"']?${Regex.escape(table)}[`\\\"']?\\b")
                    val androidDelete = Regex("(?i)\\.delete\\s*\\(\\s*[\\\"']${Regex.escape(table)}[\\\"']")
                    if (sqlDelete.containsMatchIn(source) || androidDelete.containsMatchIn(source)) {
                        violations += "${file.relativeTo(sourceRoot).path}:$table"
                    }
                }
            }

        assertTrue(
            "確定業務データを物理削除する通常アプリ経路があります: ${violations.joinToString()}",
            violations.isEmpty(),
        )
    }

    @Test
    fun reversalScreenTreatsFullyReversedSalesAsCompletedAndNonSelectable() {
        val source = File("src/main/java/jp/co/tenposinfo/register/OperationsActivity.kt").readText()
        val reversalScreen = source.substringAfter("private fun ReversalScreen(")

        assertTrue(reversalScreen.contains("val completed = sale.id in reversedSaleIds"))
        assertTrue(reversalScreen.contains(".clickable(enabled = !completed && !contextSaleLocked)"))
        assertTrue(reversalScreen.contains("if (completed) Text(\" 完了\""))
        assertTrue(reversalScreen.contains("全量返品・取消済みです"))
    }

    @Test
    fun normalSalesLookupShowsVoidedStatusAndOppositeTransactionReferences() {
        val source = File("src/main/java/jp/co/tenposinfo/register/BusinessDateSalesLookupActivity.kt").readText()
        val traceSource = File("src/main/java/jp/co/tenposinfo/register/SaleReversalTraceV135.kt").readText()

        assertTrue(source.contains("SaleReversalTraceReadStoreV135"))
        assertTrue(source.contains("val pageReversalTraces"))
        assertTrue(source.contains("selectedReversalTrace.state.displayLabel"))
        assertTrue(source.contains("反対取引 \${reference.type.displayName} No.\${reference.reversalId}"))
        assertTrue(source.contains("enabled = !selectedReversalTrace.blocksFurtherReversal"))
        assertTrue(source.contains("全量処理済み（再返品・取消不可）"))
        assertTrue(traceSource.contains("VOIDED(\"取消済（VOIDED）\", true)"))
        assertTrue(traceSource.contains("RETURNED(\"返品済\", true)"))
        assertTrue(traceSource.contains("PARTIAL_RETURN(\"一部返品\", false)"))
    }

    private fun String.functionBody(startMarker: String, nextMarker: String): String {
        val start = indexOf(startMarker)
        require(start >= 0) { "start marker not found: $startMarker" }
        val end = indexOf(nextMarker, start + startMarker.length)
        require(end > start) { "next marker not found: $nextMarker" }
        return substring(start, end)
    }
}
