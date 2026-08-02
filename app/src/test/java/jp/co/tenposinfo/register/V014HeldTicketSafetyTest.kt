package jp.co.tenposinfo.register

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class V014HeldTicketSafetyTest {
    @Test
    fun ticketNameIsTrimmedSanitizedAndLimited() {
        val normalized = HeldTicketSafetyPolicy.normalizeName(
            raw = "  宴会\n30名\t${"長".repeat(50)}  ",
            fallback = "伝票1",
        )

        assertFalse(normalized.contains('\n'))
        assertFalse(normalized.contains('\t'))
        assertTrue(normalized.startsWith("宴会 30名"))
        assertEquals(HeldTicketSafetyPolicy.MAX_NAME_LENGTH, normalized.length)
    }

    @Test
    fun blankNameUsesFallback() {
        assertEquals(
            "伝票3",
            HeldTicketSafetyPolicy.normalizeName("   \n", "伝票3"),
        )
    }

    @Test
    fun defaultNameUsesFirstAvailableNumberInsteadOfListSize() {
        assertEquals(
            "伝票2",
            HeldTicketSafetyPolicy.defaultName(listOf("伝票1", "伝票3", "宴会")),
        )
    }

    @Test
    fun parkedNameIsRecognizableAndBounded() {
        val name = HeldTicketSafetyPolicy.parkedName(
            operatorName = "責任者責任者責任者責任者責任者",
            timestampMillis = 0L,
        )

        assertTrue(name.startsWith("作業中退避-"))
        assertTrue(name.length <= HeldTicketSafetyPolicy.MAX_NAME_LENGTH)
    }

    @Test
    fun saleCommitGuardRejectsDuplicateUntilReset() {
        val guard = SaleCommitGuard()

        assertTrue(guard.tryBegin())
        assertFalse(guard.tryBegin())
        assertTrue(guard.isLocked())

        guard.resetForNewPayment()

        assertTrue(guard.tryBegin())
    }

    @Test
    fun failedCommitCanBeRetriedButSuccessfulCommitRemainsLocked() {
        val guard = SaleCommitGuard()
        assertTrue(guard.tryBegin())

        guard.releaseAfterFailure()
        assertTrue(guard.tryBegin())
        assertFalse(guard.tryBegin())
    }
}
