package jp.co.tenposinfo.register

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class V029ResponsiveLayoutTest {
    @Test
    fun classifiesRepresentativeLandscapeScreens() {
        assertEquals(
            RegisterWindowClass.COMPACT,
            RegisterResponsiveLayoutPolicy.classify(800, 480),
        )
        assertEquals(
            RegisterWindowClass.MEDIUM,
            RegisterResponsiveLayoutPolicy.classify(1_280, 800),
        )
        assertEquals(
            RegisterWindowClass.EXPANDED,
            RegisterResponsiveLayoutPolicy.classify(1_920, 1_080),
        )
    }

    @Test
    fun largerFontScaleUsesSaferLayoutClass() {
        assertEquals(
            RegisterWindowClass.COMPACT,
            RegisterResponsiveLayoutPolicy.classify(1_280, 800, fontScale = 1.3f),
        )
    }

    @Test
    fun salesAndPaymentWeightsAlwaysFillAvailableWidth() {
        val profiles = listOf(
            RegisterResponsiveLayoutPolicy.metrics(800, 480),
            RegisterResponsiveLayoutPolicy.metrics(1_280, 800),
            RegisterResponsiveLayoutPolicy.metrics(1_920, 1_080),
        )
        profiles.forEach { metrics ->
            assertEquals(
                1f,
                metrics.salesListWeight + metrics.salesKeypadWeight + metrics.salesProductsWeight,
                0.0001f,
            )
            assertEquals(
                1f,
                metrics.paymentDetailWeight + metrics.paymentKeypadWeight,
                0.0001f,
            )
        }
    }

    @Test
    fun keypadUsesAllAvailableHeightWithoutBreakingMinimumTouchSize() {
        val medium = RegisterResponsiveLayoutPolicy.keypadMetrics(
            availableHeightDp = 620,
            functionRows = 2,
        )
        val expanded = RegisterResponsiveLayoutPolicy.keypadMetrics(
            availableHeightDp = 820,
            functionRows = 2,
        )

        assertTrue(medium.keyHeightDp >= RegisterResponsiveLayoutPolicy.MIN_TOUCH_DP)
        assertTrue(expanded.keyHeightDp >= medium.keyHeightDp)
        assertTrue(expanded.keyHeightDp <= RegisterResponsiveLayoutPolicy.MAX_KEY_HEIGHT_DP)
        assertFalse(expanded.scrollRequired)
    }

    @Test
    fun undersizedPanelRequestsScrollInsteadOfOverlappingControls() {
        val result = RegisterResponsiveLayoutPolicy.keypadMetrics(
            availableHeightDp = 330,
            functionRows = 2,
        )

        assertEquals(RegisterResponsiveLayoutPolicy.MIN_TOUCH_DP, result.keyHeightDp)
        assertTrue(result.functionHeightDp >= RegisterResponsiveLayoutPolicy.MIN_TOUCH_DP)
        assertTrue(result.valueHeightDp >= RegisterResponsiveLayoutPolicy.MIN_TOUCH_DP)
        assertTrue(result.scrollRequired)
    }

    @Test
    fun allSupportedProfilesKeepRequiredActionsVisibleByPolicy() {
        val profiles = listOf(
            800 to 480,
            1_024 to 600,
            1_280 to 800,
            1_536 to 960,
            1_920 to 1_080,
        )

        profiles.forEach { (width, height) ->
            val metrics = RegisterResponsiveLayoutPolicy.metrics(width, height)
            assertTrue(metrics.headerHeightDp >= 56)
            assertTrue(metrics.bottomBarHeightDp >= 72)
            assertTrue(metrics.screenPaddingDp >= 8)
            assertTrue(metrics.panelGapDp >= 8)
            assertTrue(metrics.cardPaddingDp >= 10)
        }
    }
}
