package jp.co.tenposinfo.register

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class V136QuantityModePropagationTest {
    @Test
    fun heldTicketSafetyRawWritesPreserveQuantityMode() {
        val source = File("src/main/java/jp/co/tenposinfo/register/HeldTicketSafety.kt").readText()
        val writer = source.substringAfter("private fun CartItem.toDatabaseValues()")
        assertTrue(writer.contains("put(\"quantity_hundredths\", quantityHundredths)"))
        assertTrue(writer.contains("put(\"quantity_mode\", product.quantityMode.name)"))
    }

    @Test
    fun dynamicCatalogRuntimePreservesMasterQuantityMode() {
        val source = File("src/main/java/jp/co/tenposinfo/register/DynamicCatalogRuntime.kt").readText()
        assertTrue(source.contains("quantityMode = metadata[snapshot.productId]?.quantityMode ?: fallback.quantityMode"))
        assertTrue(source.contains("quantityMode = meta.quantityMode"))
    }
}
