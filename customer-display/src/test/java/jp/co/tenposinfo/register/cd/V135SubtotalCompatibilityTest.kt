package jp.co.tenposinfo.register.cd

import org.junit.Assert.assertEquals
import org.junit.Test

class V135SubtotalCompatibilityTest {
    @Test
    fun reducerAcceptsSubtotalSnapshot() {
        val subtotal = CustomerDisplaySnapshot(
            schemaVersion = CUSTOMER_DISPLAY_SCHEMA_VERSION,
            sequence = 2L,
            serverInstanceId = "register-v135",
            sentAtMillis = 2L,
            mode = CustomerDisplayMode.SUBTOTAL,
            transactionId = null,
            storeName = "つぐレジ",
            numberOfProducts = 2,
            subtotalAmount = 2_200L,
            totalAmount = 2_200L,
            paymentMethod = null,
            receivedAmount = 0L,
            shortageAmount = 0L,
            changeAmount = 0L,
            message = "小計をご確認ください",
            orderItems = emptyList(),
        )
        val current = CustomerDisplayUiState(
            connected = true,
            snapshot = CustomerDisplaySnapshot.initial(),
            statusMessage = "接続中",
        )

        val updated = CustomerDisplayStateReducer.received(current, subtotal)

        assertEquals(CustomerDisplayMode.SUBTOTAL, updated.snapshot.mode)
        assertEquals(2_200L, updated.snapshot.totalAmount)
    }
}
