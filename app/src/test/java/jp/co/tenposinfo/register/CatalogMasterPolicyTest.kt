package jp.co.tenposinfo.register

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogMasterPolicyTest {
    @Test
    fun scheduledProfileWithHigherPriorityIsSelected() {
        val profiles = listOf(
            SalesProfileRecord(1, "DEFAULT", "通常", true, 0, 0, 0, true),
            SalesProfileRecord(2, "LUNCH", "ランチ", true, 11 * 60, 15 * 60, 20, false),
            SalesProfileRecord(3, "SPECIAL", "特別ランチ", true, 12 * 60, 13 * 60, 50, false),
        )

        assertEquals("特別ランチ", SalesProfileSelector.select(profiles, 12 * 60 + 30)?.name)
        assertEquals("通常", SalesProfileSelector.select(profiles, 16 * 60)?.name)
    }

    @Test
    fun profileCanCrossMidnight() {
        val night = SalesProfileRecord(1, "NIGHT", "深夜", true, 17 * 60, 2 * 60, 10, false)
        assertTrue(SalesProfileSelector.matches(night, 23 * 60))
        assertTrue(SalesProfileSelector.matches(night, 60))
        assertFalse(SalesProfileSelector.matches(night, 10 * 60))
    }

    @Test
    fun disabledProfilesAreIgnored() {
        val disabled = SalesProfileRecord(1, "OFF", "停止", false, 0, 0, 999, true)
        assertNull(SalesProfileSelector.select(listOf(disabled), 600))
    }

    @Test
    fun layoutDisplayOrderUsesPageAndSlot() {
        assertEquals(1, ButtonLayoutPolicy.displayOrder(1, 1))
        assertEquals(24, ButtonLayoutPolicy.displayOrder(1, 24))
        assertEquals(25, ButtonLayoutPolicy.displayOrder(2, 1))
        assertEquals(216, ButtonLayoutPolicy.displayOrder(9, 24))
    }

    @Test
    fun timeAndCodeValidationAreNormalized() {
        assertEquals(17 * 60 + 30, CatalogValidation.parseTime("17:30"))
        assertEquals("P_001", CatalogValidation.requireCode(" p_001 ", "商品コード"))
    }

    @Test
    fun currentFiveTaxCategoriesRemainEngineCompatible() {
        TaxCategory.entries.forEach { category ->
            assertEquals(category, TaxMasterCompatibility.supportedCategory(category.name))
        }
        assertNull(TaxMasterCompatibility.supportedCategory("CUSTOM_12"))
    }
}
