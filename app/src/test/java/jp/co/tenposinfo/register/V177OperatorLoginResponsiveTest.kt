package jp.co.tenposinfo.register

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** v1.77 PIN-login page operator buttons should not be crushed into 3 columns. */
class V177OperatorLoginResponsiveTest {
    @Test fun operatorGridUsesOneTwoOrThreeColumnsByAvailableWidthAndFontScale() {
        assertEquals(1, RegisterOperatorGridPolicyV177.columns(280))
        assertEquals(1, RegisterOperatorGridPolicyV177.columns(339))
        assertEquals(2, RegisterOperatorGridPolicyV177.columns(340))
        assertEquals(2, RegisterOperatorGridPolicyV177.columns(489))
        assertEquals(3, RegisterOperatorGridPolicyV177.columns(490))
        assertEquals(2, RegisterOperatorGridPolicyV177.columns(600, 1.5f))
        assertEquals(3, RegisterOperatorGridPolicyV177.columns(800, 1.0f))
    }

    @Test fun loginUsesDynamicColumnsAndRetainsPinFreeSelection() {
        val source = File("src/main/java/jp/co/tenposinfo/register/MainActivity.kt").readText()
        assertTrue(source.contains("val operatorColumnsV177 = RegisterOperatorGridPolicyV177.columns("))
        assertTrue(source.contains("for (rowStart in operators.indices step operatorColumnsV177)"))
        assertTrue(source.contains("rowStart + operatorColumnsV177"))
        assertTrue(source.contains("if (!operator.pinRequired) onLogin(operator.id, \"\")"))
        assertTrue(source.contains("NumberPad("))
        assertTrue(source.contains("preferredMinKeyDp = 72"))
    }
}
