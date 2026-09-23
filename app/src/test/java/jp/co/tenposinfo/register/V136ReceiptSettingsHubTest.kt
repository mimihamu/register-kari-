package jp.co.tenposinfo.register

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class V136ReceiptSettingsHubTest {
    private val root = File(System.getProperty("user.dir")).parentFile ?: File(".")

    @Test
    fun scr640CollectsReceiptSettingsWithoutDuplicatingStores() {
        val source = File("src/main/java/jp/co/tenposinfo/register/ReceiptSettingsActivity.kt").readText()

        assertTrue(source.contains("SCR-640  レシート設定"))
        assertTrue(source.contains("DocumentPrintSettingsPanelV136(receiptAutoPrintEnabled = receiptAutoPrint)"))
        assertTrue(source.contains("ReceiptTextStampSettingsPanelV136()"))
        assertTrue(source.contains("ReceiptStampSettingsPanelV136()"))
        assertTrue(source.contains("printer.copy(receiptAutoPrintEnabled = receiptAutoPrint)"))
        assertTrue(source.contains("店舗基本設定を開く"))
        assertTrue(source.contains("58mm／80mmプレビュー"))
    }

    @Test
    fun receiptSettingsActivityIsPrivateLandscapeActivity() {
        val manifest = File(root, "app/src/main/AndroidManifest.xml").readText()
        val block = manifest
            .split("android:name=\".ReceiptSettingsActivity\"", limit = 2)
            .getOrNull(1)
            .orEmpty()
            .substringBefore("/>")

        assertTrue(block.contains("android:exported=\"false\""))
        assertTrue(block.contains("android:screenOrientation=\"landscape\""))
    }
}
