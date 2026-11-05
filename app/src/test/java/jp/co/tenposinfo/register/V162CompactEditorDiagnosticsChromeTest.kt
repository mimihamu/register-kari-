package jp.co.tenposinfo.register

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression coverage for cramped 3-column revision editing on compact POS tablets. */
class V162CompactEditorDiagnosticsChromeTest {
    private val root = File("src/main/java/jp/co/tenposinfo/register")
    private fun src(name: String): String = File(root, name).readText()

    @Test fun twoPaneEditingIsSelectedFor800x480AndMediumTablets() {
        assertEquals(RegisterWindowClass.COMPACT, RegisterResponsiveLayoutPolicy.classify(800, 480))
        assertEquals(RegisterWindowClass.MEDIUM, RegisterResponsiveLayoutPolicy.classify(1280, 800))
        assertEquals(RegisterWindowClass.EXPANDED, RegisterResponsiveLayoutPolicy.classify(1600, 900))
        val editor = src("MenuRevisionEditorActivity.kt")
        assertTrue(editor.contains("val threePanels = layout.windowClass == RegisterWindowClass.EXPANDED"))
        assertTrue(editor.contains("if (threePanels || !showEditor)"))
        assertTrue(editor.contains("if (threePanels || showEditor)"))
        assertTrue(editor.contains("if (!threePanels) showEditor = true"))
        assertTrue(editor.contains("Modifier.width(if (layout.isCompact) 210.dp else 285.dp)"))
        assertTrue(editor.contains("Modifier.weight(1f).fillMaxHeight()"))
    }

    @Test fun editorUsesUnifiedHeaderAndKeepsDateSensitiveSafety() {
        val editor = src("MenuRevisionEditorActivity.kt")
        assertTrue(editor.contains("RegisterTopBarV141("))
        assertTrue(editor.contains("title = \"メニュー改定\""))
        assertTrue(editor.contains("onBack = onClose"))
        assertTrue(editor.contains("\"一覧・比較\" else \"商品編集\""))
        assertFalse(editor.contains("private fun MrHeader("))
        assertTrue(editor.contains("fun businessDate(): LocalDate = BusinessDateResolver.current(db)"))
        assertTrue(editor.contains("store.isEditable(revision)"))
    }

    @Test fun emptyTaxRulesCannotCrashCycleAction() {
        val editor = src("MenuRevisionEditorActivity.kt")
        assertTrue(editor.contains("editable && rules.isNotEmpty()"))
        assertTrue(editor.contains("if (rules.isNotEmpty()) taxKey = rules[(index + 1).mod(rules.size)].key"))
    }

    @Test fun performanceDiagnosticsScrollAndAlwaysHaveBackAndRetest() {
        val s = src("PerformanceDiagnosticsActivityV136.kt")
        assertTrue(s.contains("RegisterSafeAreaV141 {"))
        assertTrue(s.contains("RegisterTopBarV141("))
        assertTrue(s.contains("title = \"性能診断\""))
        assertTrue(s.contains("onBack = onClose"))
        assertTrue(s.contains("Button(onClick = { revision++ })"))
        assertTrue(s.contains("verticalScroll(rememberScrollState())"))
        assertTrue(s.contains("PerformanceDiagnosticsV136.capture(context)"))
        assertFalse(s.contains("DIAG-001 / DIAG-002 性能診断"))
        assertFalse(s.contains("height(62.dp).background(Color(0xFF173F6B))"))
    }
}
