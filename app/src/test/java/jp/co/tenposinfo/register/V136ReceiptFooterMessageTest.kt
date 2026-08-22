package jp.co.tenposinfo.register

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class V136ReceiptFooterMessageTest {
    @Test
    fun formalLimitsAreTenLinesAndSixtyFourCodePointsPerLine() {
        assertEquals(10, ReceiptFooterMessagePolicyV136.MAX_LOGICAL_LINES)
        assertEquals(64, ReceiptFooterMessagePolicyV136.MAX_CODE_POINTS_PER_LINE)

        val tenLines = (1..10).joinToString("\n") { "固定文$it" }
        assertEquals(tenLines, ReceiptFooterMessagePolicyV136.normalizeForSave(tenLines))

        val elevenLines = (1..11).joinToString("\n") { "固定文$it" }
        assertTrue(runCatching { ReceiptFooterMessagePolicyV136.normalizeForSave(elevenLines) }.isFailure)

        val sixtyFour = "あ".repeat(64)
        assertEquals(sixtyFour, ReceiptFooterMessagePolicyV136.normalizeForSave(sixtyFour))
        assertTrue(
            runCatching { ReceiptFooterMessagePolicyV136.normalizeForSave("あ".repeat(65)) }.isFailure,
        )
    }

    @Test
    fun blankIsAllowedAndControlCharactersAreRemoved() {
        assertEquals("", ReceiptFooterMessagePolicyV136.normalizeForSave("  \n  "))
        assertEquals("A B\nC", ReceiptFooterMessagePolicyV136.cleanInput("A\tB\u0001\r\nC"))
    }

    @Test
    fun legacyTwoHundredCharacterFooterMigratesWithoutDroppingCharacters() {
        val legacy = "A".repeat(200)
        val migrated = ReceiptFooterMessagePolicyV136.migrateLegacy(legacy)
        val lines = migrated.lines()

        assertTrue(lines.size <= ReceiptFooterMessagePolicyV136.MAX_LOGICAL_LINES)
        assertTrue(lines.all { it.codePointCount(0, it.length) <= ReceiptFooterMessagePolicyV136.MAX_CODE_POINTS_PER_LINE })
        assertEquals(legacy, lines.joinToString(""))
    }

    @Test
    fun paperWidthPreviewWrapsWithoutLossAndCentersEveryPhysicalLine() {
        val longMessage = "あ".repeat(40)

        listOf(ReceiptPaper.MM58, ReceiptPaper.MM80).forEach { paper ->
            val rendered = ReceiptFooterMessagePolicyV136.renderLines(longMessage, paper)
            assertTrue(rendered.size >= 2)
            assertTrue(rendered.all { ReceiptLineWrapV136.displayWidth(it) <= paper.charsPerLine })
            assertEquals(longMessage, rendered.joinToString("") { it.trim() })
        }

        val shortMessage = "ABC"
        assertEquals(
            " ".repeat((ReceiptPaper.MM58.charsPerLine - 3) / 2) + shortMessage,
            ReceiptFooterMessagePolicyV136.renderLines(shortMessage, ReceiptPaper.MM58).single(),
        )
        assertEquals(
            " ".repeat((ReceiptPaper.MM80.charsPerLine - 3) / 2) + shortMessage,
            ReceiptFooterMessagePolicyV136.renderLines(shortMessage, ReceiptPaper.MM80).single(),
        )
    }

    @Test
    fun receiptUsesConfiguredFooterAndKeepsReissueAsAbsoluteLastLine() {
        val base = ReceiptData(
            storeName = "つぐレジ店",
            registrationNumber = "",
            saleId = 26L,
            createdAt = 0L,
            operatorName = "担当者",
            items = emptyList(),
            taxSummary = TaxEngine.calculate(emptyList()),
            payments = emptyList(),
            changeAmount = 0L,
            documentFooter = "またお越しください\n営業時間 11:00-22:00",
        )

        listOf(ReceiptPaper.MM58, ReceiptPaper.MM80).forEach { paper ->
            val lines = ReceiptRenderer.render(base.copy(reprint = true), paper).lines()
            val firstFooter = lines.first { it.contains("またお越しください") }
            val secondFooter = lines.first { it.contains("営業時間 11:00-22:00") }
            assertTrue(firstFooter.startsWith(" "))
            assertTrue(secondFooter.startsWith(" "))
            assertEquals("【再発行】", lines.last().trim())
            assertTrue(lines.indexOf(firstFooter) < lines.lastIndex)
            assertTrue(lines.indexOf(secondFooter) < lines.lastIndex)
        }
    }

    @Test
    fun explicitBlankSuppressesDefaultMessageWhileReceiptDefaultKeepsCompatibility() {
        val base = ReceiptData(
            storeName = "つぐレジ店",
            registrationNumber = "",
            saleId = 260L,
            createdAt = 0L,
            operatorName = "担当者",
            items = emptyList(),
            taxSummary = TaxEngine.calculate(emptyList()),
            payments = emptyList(),
            changeAmount = 0L,
        )
        assertTrue(ReceiptRenderer.render(base, ReceiptPaper.MM58).contains("ありがとうございました"))
        assertFalse(ReceiptRenderer.render(base.copy(documentFooter = ""), ReceiptPaper.MM58).contains("ありがとうございました"))
    }

    @Test
    fun settingsUiUsesFormalLimitAndBothPaperWidthPreviews() {
        val source = File("src/main/java/jp/co/tenposinfo/register/DocumentPrintSettingsV136.kt").readText()
        assertTrue(source.contains("店舗固定文（最大10行・1行64文字）"))
        assertTrue(source.contains("58mmプレビュー"))
        assertTrue(source.contains("80mmプレビュー"))
        assertFalse(source.contains("MAX_LOGICAL_LINES = 12"))
    }
}
