package jp.co.tenposinfo.register

import org.junit.Assert.assertTrue
import org.junit.Test

class V013RegisterCompactLayoutTest {
    @Test
    fun salesUtilityControlsFitObservedTabletPanel() {
        assertTrue(RegisterLayoutPolicy.salesUtilityRequiredHeightDp() <= 310)
    }

    @Test
    fun paymentControlsFitObservedTabletPanel() {
        assertTrue(RegisterLayoutPolicy.paymentControlsRequiredHeightDp() <= 360)
    }

    @Test
    fun diagnosticCardLeavesRoomForContinueButton() {
        assertTrue(RegisterLayoutPolicy.DIAGNOSTIC_CARD_HEIGHT_DP <= 280)
    }
}
