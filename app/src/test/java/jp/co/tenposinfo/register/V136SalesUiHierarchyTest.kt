package jp.co.tenposinfo.register

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class V136SalesUiHierarchyTest {
    @Test
    fun primaryCheckoutActionAndSelectionStateRemainUnambiguous() {
        val source = File("src/main/java/jp/co/tenposinfo/register/MainActivity.kt").readText()

        assertTrue(source.contains("\"会計へ  \${yen(summary.grossAmount)}\""))
        assertFalse(source.contains("\"小計／会計  \${yen(summary.grossAmount)}\""))
        assertTrue(source.contains("if (selectedIndex != null)"))
        assertTrue(source.contains("Surface(color = PaleBlue, shape = RoundedCornerShape(12.dp))"))
        assertTrue(source.contains("\"選択中\""))
        assertTrue(source.contains("fontSize = if (responsive.isCompact) 24.sp else 28.sp"))
    }
}
