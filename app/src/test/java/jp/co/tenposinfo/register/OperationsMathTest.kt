package jp.co.tenposinfo.register

import org.junit.Assert.assertEquals
import org.junit.Test

class OperationsMathTest {
    @Test
    fun expectedCash_includesCashSalesCashInAndCashOut() {
        val expected = OperationsMath.expectedCash(
            cashSalesAfterRefunds = 12_000,
            cashIn = 5_000,
            cashOut = 1_500,
        )

        assertEquals(15_500, expected)
    }

    @Test
    fun expectedCash_refundAlreadyReflectedInCashPaymentTotal() {
        val expected = OperationsMath.expectedCash(
            cashSalesAfterRefunds = 8_000,
            cashIn = 0,
            cashOut = 0,
        )

        assertEquals(8_000, expected)
    }

    @Test
    fun variance_positiveMeansCashOver() {
        assertEquals(300, OperationsMath.variance(actualCash = 10_300, expectedCash = 10_000))
    }

    @Test
    fun variance_negativeMeansCashShort() {
        assertEquals(-200, OperationsMath.variance(actualCash = 9_800, expectedCash = 10_000))
    }
}
