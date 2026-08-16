package jp.co.tenposinfo.register

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File

class V135ManualReturnPolicyTest {
    private val product = Product(
        id = "P1",
        name = "返品商品",
        unitPrice = 1_100L,
        taxCategory = TaxCategory.INCLUDED_10,
        displayOrder = 1,
    )

    @Test
    fun receiptlessReturnPersistsNegativeQuantityAndAmountSemantics() {
        assertEquals(-2, ManualReturnPolicyV135.signedQuantity(2))
        assertEquals(-2_200L, ManualReturnPolicyV135.signedAmount(2_200L))
    }

    @Test
    fun reasonCanBeRequiredOrOptionalBySetting() {
        val request = ManualReturnRequestV135(
            lines = listOf(ManualReturnLineRequestV135(product, 1)),
            reason = "",
            refundMethod = ManualRefundMethodV135.CASH,
        )
        assertThrows(IllegalArgumentException::class.java) {
            ManualReturnPolicyV135.toPositiveCartItems(request, reasonRequired = true)
        }
        assertEquals(1, ManualReturnPolicyV135.toPositiveCartItems(request, reasonRequired = false).size)
    }

    @Test
    fun emptyOrNonPositiveReturnIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            ManualReturnPolicyV135.toPositiveCartItems(
                ManualReturnRequestV135(emptyList(), "理由", ManualRefundMethodV135.CASH),
                reasonRequired = true,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ManualReturnPolicyV135.toPositiveCartItems(
                ManualReturnRequestV135(
                    listOf(ManualReturnLineRequestV135(product, 0)),
                    "理由",
                    ManualRefundMethodV135.CASH,
                ),
                reasonRequired = true,
            )
        }
    }

    @Test
    fun sourceRequiresReversalPermissionAndManagerPin() {
        val source = File("src/main/java/jp/co/tenposinfo/register/ManualReturnV135.kt").readText()
        assertTrue(source.contains("operator.allows(RegisterPermission.REVERSAL)"))
        assertTrue(source.contains("managerNameForPin(managerPin)"))
        assertTrue(source.contains("責任者PINを入力してください"))
        assertTrue(source.contains("event_type\", \"MANUAL_RETURN"))
    }

    @Test
    fun sourceUsesDedicatedAppendOnlyTablesAndNoFakeOriginalSale() {
        val source = File("src/main/java/jp/co/tenposinfo/register/ManualReturnV135.kt").readText()
        assertTrue(source.contains("CREATE TABLE IF NOT EXISTS manual_return_transactions"))
        assertTrue(source.contains("CREATE TABLE IF NOT EXISTS manual_return_items"))
        assertTrue(source.contains("CREATE TABLE IF NOT EXISTS manual_return_payments"))
        assertTrue(source.contains("CHECK(gross_amount < 0)"))
        assertTrue(source.contains("CHECK(quantity < 0)"))
        assertTrue(source.contains("CHECK(amount < 0)"))
        assertFalse(source.contains("put(\"original_sale_id\""))
        assertFalse(source.contains("insertOrThrow(\n                    \"sales\""))
        assertFalse(Regex("(?i)DELETE\\s+FROM\\s+manual_return_").containsMatchIn(source))
        assertFalse(Regex("(?i)\\.delete\\s*\\(\\s*\"manual_return_").containsMatchIn(source))
    }

    @Test
    fun sourceBindsReturnToCurrentOpenSessionAndExplicitRefundMethod() {
        val source = File("src/main/java/jp/co/tenposinfo/register/ManualReturnV135.kt").readText()
        assertTrue(source.contains("WHERE status = ? ORDER BY opened_at DESC LIMIT 1"))
        assertTrue(source.contains("put(\"business_session_id\", session.first)"))
        assertTrue(source.contains("put(\"business_date\", session.second)"))
        assertTrue(source.contains("put(\"refund_method\", request.refundMethod.name)"))
        assertTrue(source.contains("put(\"payment_method\", request.refundMethod.name)"))
    }

    @Test
    fun operationsSummaryIncludesManualReturnsExactlyOnceAndHubUsesUnifiedSummary() {
        val operations = File("src/main/java/jp/co/tenposinfo/register/OperationsStore.kt").readText()
        val hub = File("src/main/java/jp/co/tenposinfo/register/OperationsHubActivityV030.kt").readText()

        assertTrue(operations.contains("val manualReturnGross = if (SchemaMigration.tableExists(db, \"manual_return_transactions\"))"))
        assertTrue(operations.contains("val reversalGross = linkedReversalGross + manualReturnGross"))
        assertTrue(operations.contains("val reversalCount = linkedReversalCount + manualReturnCount"))
        assertTrue(operations.contains("FROM manual_return_payments p"))
        assertTrue(operations.contains("paymentMap[method] = (paymentMap[method] ?: 0L) + cursor.getLong(1)"))
        assertTrue(hub.contains("store.dailySummary()"))
        assertFalse(hub.contains("ManualReturnAccountingV135.apply(context.applicationContext, store.dailySummary())"))
    }

    @Test
    fun hubExposesReceiptlessReturnOnlyWithReversalPermission() {
        val source = File("src/main/java/jp/co/tenposinfo/register/OperationsHubActivityV030.kt").readText()
        assertTrue(source.contains("ManualReturnActivityV135::class.java"))
        assertTrue(source.contains("\"元取引なし返品\""))
        assertTrue(source.contains("RegisterPermission.REVERSAL in permissions"))
    }

    @Test
    fun manifestKeepsManualReturnActivityInternalAndLandscape() {
        val source = File("src/main/AndroidManifest.xml").readText()
        val block = source.substringAfter("android:name=\".ManualReturnActivityV135\"").substringBefore("/>")
        assertTrue(block.contains("android:exported=\"false\""))
        assertTrue(block.contains("android:screenOrientation=\"landscape\""))
    }
}
