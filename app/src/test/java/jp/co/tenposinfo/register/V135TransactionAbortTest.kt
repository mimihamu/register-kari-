package jp.co.tenposinfo.register

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File

class V135TransactionAbortTest {
    private val product = Product(
        id = "P-COR004",
        name = "中止テスト商品",
        unitPrice = 550L,
        taxCategory = TaxCategory.INCLUDED_10,
        displayOrder = 1,
    )

    @Test
    fun abortReasonIsMandatoryAndTrimmed() {
        assertThrows(IllegalArgumentException::class.java) {
            TransactionAbortPolicyV135.normalizedReason("   ")
        }
        assertEquals("お客様都合", TransactionAbortPolicyV135.normalizedReason("  お客様都合  "))
    }

    @Test
    fun abortSnapshotCapturesUncommittedCartWithoutCreatingSaleSemantics() {
        val snapshot = TransactionAbortPolicyV135.snapshot(
            listOf(
                CartItem(product, quantity = 2),
                CartItem(product.copy(id = "P2", unitPrice = 330L), quantity = 1),
            ),
        )
        assertEquals(2, snapshot.lineCount)
        assertEquals(3, snapshot.totalQuantity)
        assertEquals(1_430L, snapshot.grossAmount)
    }

    @Test
    fun emptyCartCannotBeAborted() {
        assertThrows(IllegalArgumentException::class.java) {
            TransactionAbortPolicyV135.snapshot(emptyList())
        }
    }

    @Test
    fun sourceRequiresCurrentOperatorAndWritesAuditBeforeClearingWorkCart() {
        val source = File("src/main/java/jp/co/tenposinfo/register/TransactionAbortV135.kt").readText()
        assertTrue(source.contains("OperatorSessionRegistry.current(appContext)"))
        assertTrue(source.contains("\"event_type\", \"TRANSACTION_ABORT\""))
        assertTrue(source.contains("\"operator_name\", operator.name"))
        assertTrue(source.contains("取引中止理由（必須）"))
        assertTrue(source.contains("reason.isNotBlank() && items.isNotEmpty()"))

        val auditIndex = source.indexOf("db.insertOrThrow(\n                \"operation_audit\"")
        val clearIndex = source.indexOf("db.delete(\"cart_items\"")
        val callbackIndex = source.indexOf("onAbortCommitted()")
        assertTrue(auditIndex >= 0)
        assertTrue(clearIndex > auditIndex)
        assertTrue(callbackIndex > clearIndex)
    }

    @Test
    fun abortPathDoesNotCreateOrMutateCommittedSales() {
        val source = File("src/main/java/jp/co/tenposinfo/register/TransactionAbortV135.kt").readText()
        assertFalse(source.contains("insertOrThrow(\"sales\""))
        assertFalse(source.contains("insertOrThrow(\"sale_items\""))
        assertFalse(source.contains("insertOrThrow(\"sale_payments\""))
        assertFalse(Regex("(?i)(UPDATE|DELETE\\s+FROM)\\s+(sales|sale_items|sale_payments)").containsMatchIn(source))
        assertFalse(Regex("(?i)\\.delete\\s*\\(\\s*\"(sales|sale_items|sale_payments)\"").containsMatchIn(source))
    }

    @Test
    fun mainSalesScreenUsesAuditedAbortButtonInsteadOfDirectClearButton() {
        val source = File("src/main/java/jp/co/tenposinfo/register/MainActivity.kt").readText()
        assertTrue(source.contains("TransactionAbortButtonV135("))
        assertTrue(source.contains("items = cart"))
        assertTrue(source.contains("onAbortCommitted = onCancelTransaction"))
        val legacy = "Button(\n                                    onClick = onCancelTransaction"
        assertFalse(source.contains(legacy))
    }
}
