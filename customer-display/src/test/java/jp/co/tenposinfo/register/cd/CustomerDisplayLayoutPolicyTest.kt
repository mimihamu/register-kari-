package jp.co.tenposinfo.register.cd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomerDisplayLayoutPolicyTest {
    @Test
    fun phonePortraitUsesCompactStackedLayout() {
        val mode = CustomerDisplayLayoutPolicy.select(widthDp = 360f, heightDp = 800f)
        assertEquals(CustomerDisplayLayoutMode.PHONE_PORTRAIT, mode)
        assertTrue(mode.compact)
        assertTrue(mode.stacked)
    }

    @Test
    fun phoneLandscapeUsesCompactSideBySideLayout() {
        val mode = CustomerDisplayLayoutPolicy.select(widthDp = 800f, heightDp = 360f)
        assertEquals(CustomerDisplayLayoutMode.PHONE_LANDSCAPE, mode)
        assertTrue(mode.compact)
        assertFalse(mode.stacked)
    }

    @Test
    fun tabletPortraitUsesExpandedStackedLayout() {
        val mode = CustomerDisplayLayoutPolicy.select(widthDp = 800f, heightDp = 1280f)
        assertEquals(CustomerDisplayLayoutMode.TABLET_PORTRAIT, mode)
        assertFalse(mode.compact)
        assertTrue(mode.stacked)
    }

    @Test
    fun tabletLandscapeKeepsExpandedSideBySideLayout() {
        val mode = CustomerDisplayLayoutPolicy.select(widthDp = 1280f, heightDp = 800f)
        assertEquals(CustomerDisplayLayoutMode.TABLET_LANDSCAPE, mode)
        assertFalse(mode.compact)
        assertFalse(mode.stacked)
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidDimensionsAreRejected() {
        CustomerDisplayLayoutPolicy.select(widthDp = 0f, heightDp = 800f)
    }
}
