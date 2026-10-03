package jp.co.tenposinfo.register.cd

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class V136FractionalQuantityDisplayTest {
    @Test
    fun exactQuantityFieldsAreParsedAndRendered() {
        val model = File("src/main/java/jp/co/tenposinfo/register/cd/CustomerDisplayModel.kt").readText()
        val ui = File("src/main/java/jp/co/tenposinfo/register/cd/MainActivity.kt").readText()

        assertTrue(model.contains("quantityHundredths"))
        assertTrue(model.contains("quantityText"))
        assertTrue(model.contains("item.optLong(\"quantityHundredths\")"))
        assertTrue(model.contains("item.optString(\"quantityText\")"))
        assertTrue(ui.contains("item.quantityText"))
    }
}
