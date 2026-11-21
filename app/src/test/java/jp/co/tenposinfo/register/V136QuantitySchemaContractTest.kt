package jp.co.tenposinfo.register

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class V136QuantitySchemaContractTest {
    @Test
    fun schemaIsAdditiveAndBackfillsLegacyIntegerQuantity() {
        val source = File("src/main/java/jp/co/tenposinfo/register/QuantitySchemaV136.kt").readText()
        assertTrue(source.contains("ADD COLUMN quantity_hundredths INTEGER"))
        assertTrue(source.contains("quantity_hundredths = quantity * 100"))
        assertTrue(source.contains("WHERE quantity_hundredths IS NULL"))
    }

    @Test
    fun databaseOpenBootstrapsQuantityCompatibilitySchema() {
        val source = File("src/main/java/jp/co/tenposinfo/register/RegisterDatabase.kt").readText()
        assertTrue(source.contains("QuantitySchemaV136.ensure(db)"))
    }
}
