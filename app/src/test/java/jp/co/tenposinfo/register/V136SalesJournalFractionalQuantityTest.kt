package jp.co.tenposinfo.register

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class V136SalesJournalFractionalQuantityTest {
    private val source = File("src/main/java/jp/co/tenposinfo/register/BusinessSyncFoundation.kt").readText()

    @Test
    fun salePayloadCarriesScaledQuantityModeAndExactTaxAggregation() {
        assertTrue(source.contains("COALESCE(si.quantity_hundredths, si.quantity * 100) AS quantity_scaled"))
        assertTrue(source.contains("\\\"quantityScaled\\\":\${line.quantityScaled}"))
        assertTrue(source.contains("\\\"quantityScale\\\":2"))
        assertTrue(source.contains("\\\"quantityMode\\\":\\\"\${line.quantityMode.name}\\\""))
        assertTrue(source.contains("TaxEngine.calculate(payloadLines.map(ExactPayloadLine::toCartItem))"))
    }

    @Test
    fun reversalPayloadCarriesExactReturnQuantityAndSaleTimeMode() {
        assertTrue(source.contains("COALESCE(ri.return_quantity_hundredths, ri.return_quantity * 100) AS quantity_scaled"))
        assertTrue(source.contains("LEFT JOIN sale_items si ON si.id = ri.sale_item_id"))
        assertTrue(source.contains("WHEN si.quantity_mode IN ('INTEGER','DECIMAL') THEN si.quantity_mode"))
    }

    @Test
    fun legacyQuantityFieldIsRetainedForBackwardCompatibility() {
        assertTrue(source.contains("\\\"quantity\\\":\${line.legacyQuantity}"))
    }
}
