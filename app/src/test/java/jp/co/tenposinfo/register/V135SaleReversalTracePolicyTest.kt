package jp.co.tenposinfo.register

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class V135SaleReversalTracePolicyTest {
    @Test
    fun noReferencesIsActive() {
        val trace = SaleReversalTracePolicyV135.resolve(
            originalQuantity = 2,
            returnedQuantity = 0,
            references = emptyList(),
        )

        assertEquals(SaleReversalStateV135.ACTIVE, trace.state)
        assertFalse(trace.hasReversal)
        assertFalse(trace.blocksFurtherReversal)
    }

    @Test
    fun partialReturnRemainsReversible() {
        val trace = SaleReversalTracePolicyV135.resolve(
            originalQuantity = 3,
            returnedQuantity = 1,
            references = listOf(reference(1L, ReversalType.RETURN)),
        )

        assertEquals(SaleReversalStateV135.PARTIAL_RETURN, trace.state)
        assertTrue(trace.hasReversal)
        assertFalse(trace.blocksFurtherReversal)
    }

    @Test
    fun fullyReturnedQuantityIsReturnedAndBlocked() {
        val trace = SaleReversalTracePolicyV135.resolve(
            originalQuantity = 3,
            returnedQuantity = 3,
            references = listOf(reference(2L, ReversalType.RETURN)),
        )

        assertEquals(SaleReversalStateV135.RETURNED, trace.state)
        assertTrue(trace.blocksFurtherReversal)
    }

    @Test
    fun cancellationIsVoidedAndBlocked() {
        val trace = SaleReversalTracePolicyV135.resolve(
            originalQuantity = 1,
            returnedQuantity = 1,
            references = listOf(reference(3L, ReversalType.CANCEL)),
        )

        assertEquals(SaleReversalStateV135.VOIDED, trace.state)
        assertEquals("取消済（VOIDED）", trace.state.displayLabel)
        assertTrue(trace.blocksFurtherReversal)
    }

    @Test
    fun legacyReturnWithoutItemsIsTreatedAsFullReturn() {
        val trace = SaleReversalTracePolicyV135.resolve(
            originalQuantity = 0,
            returnedQuantity = 0,
            references = listOf(reference(4L, ReversalType.RETURN, hasItems = false)),
        )

        assertEquals(SaleReversalStateV135.RETURNED, trace.state)
        assertTrue(trace.blocksFurtherReversal)
    }

    @Test
    fun cancellationWinsWhenMultipleReferencesExist() {
        val trace = SaleReversalTracePolicyV135.resolve(
            originalQuantity = 2,
            returnedQuantity = 1,
            references = listOf(
                reference(5L, ReversalType.RETURN),
                reference(6L, ReversalType.CANCEL),
            ),
        )

        assertEquals(SaleReversalStateV135.VOIDED, trace.state)
        assertEquals(listOf(5L, 6L), trace.references.map { it.reversalId })
    }

    private fun reference(
        id: Long,
        type: ReversalType,
        hasItems: Boolean = true,
    ) = SaleReversalReferenceV135(
        reversalId = id,
        type = type,
        grossAmount = 1_000L,
        businessDate = "2026-08-16",
        createdAt = 1_700_000_000_000L + id,
        hasItems = hasItems,
    )
}
