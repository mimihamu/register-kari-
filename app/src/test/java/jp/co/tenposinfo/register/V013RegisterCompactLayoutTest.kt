package jp.co.tenposinfo.register

import org.junit.Assert.assertTrue
import org.junit.Test

class V013RegisterCompactLayoutTest {
    @Test
    fun salesUtilityControlsFitObservedTabletPanel() {
        val requiredHeight = RegisterLayoutPolicy.salesUtilityRequiredHeightDp()
        assertTrue(requiredHeight in 340..350)
    }

    @Test
    fun paymentControlsFitObservedTabletPanel() {
        val requiredHeight = RegisterLayoutPolicy.paymentControlsRequiredHeightDp()
        assertTrue(requiredHeight in 390..400)
    }

    @Test
    fun diagnosticCardLeavesRoomForContinueButton() {
        assertTrue(RegisterLayoutPolicy.DIAGNOSTIC_CARD_HEIGHT_DP <= 280)
    }
}
