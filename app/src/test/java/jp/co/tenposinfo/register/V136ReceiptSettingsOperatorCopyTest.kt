package jp.co.tenposinfo.register

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class V136ReceiptSettingsOperatorCopyTest {
    @Test
    fun scr640HidesInternalContractTermsFromOperatorCopy() {
        val root = File("src/main/java/jp/co/tenposinfo/register")
        val document = File(root, "DocumentPrintSettingsV136.kt").readText()
        val textStamp = File(root, "ReceiptTextStampSettingsPanelV136.kt").readText()
        val imageStamp = File(root, "ReceiptStampV136.kt").readText()

        assertTrue(document.contains("Text(\"文書別設定\""))
        assertTrue(document.contains("レシートの自動発行は左側の基本設定を使用します。"))
        assertTrue(document.contains("再印字でも同じ内容を使用します。"))
        assertTrue(document.contains("Text(\"印刷プレビュー（保存前）\""))

        assertFalse(document.contains("Text(\"文書別設定（RCP-016）\""))
        assertFalse(document.contains("RCP-002設定"))
        assertFalse(document.contains("スタンプsnapshot"))

        assertTrue(textStamp.contains("Text(\"文字スタンプ\""))
        assertFalse(textStamp.contains("Text(\"文字スタンプ（SCR-720）\""))
        assertFalse(textStamp.contains("（version "))

        assertTrue(imageStamp.contains("Text(\"店名画像スタンプ\""))
        assertFalse(imageStamp.contains("Text(\"店名画像スタンプ（SCR-720）\""))
        assertFalse(imageStamp.contains(" / stampVersion "))
        assertFalse(imageStamp.contains("（version "))
    }
}
