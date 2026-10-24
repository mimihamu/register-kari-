package jp.co.tenposinfo.register

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class V136SettingsScreenContractTest {
    private fun source(name: String): String =
        File("src/main/java/jp/co/tenposinfo/register/$name").readText()

    @Test
    fun settingsScreensUseV25CanonicalScreenIds() {
        val admin = source("AdminSettingsActivity.kt")
        val backup = source("DataProtectionActivity.kt")
        val sync = source("SyncSettingsActivity.kt")
        val tax = source("TaxInvoiceSettingsActivity.kt")
        val maintenance = source("PrinterToolsHubActivity.kt")
        val receipt = source("ReceiptSettingsActivity.kt")

        assertTrue(admin.contains("AsHeader(\"SCR-690\", \"各種設定\""))
        assertTrue(admin.contains("AsHeader(\"SCR-650\", \"担当者・権限マスター\""))
        assertTrue(admin.contains("AsHeader(\"SCR-660\", \"プリンター・ドロア設定\""))
        assertTrue(admin.contains("AsHeader(\"SCR-650\", \"責任者PIN設定\""))
        assertTrue(admin.contains("AsHeader(\"SCR-680\", \"監査ログ\""))
        assertTrue(backup.contains("Text(\"SCR-670\""))
        assertTrue(sync.contains("Text(\"SCR-672  同期キュー・Google Drive\""))
        assertTrue(tax.contains("Text(\"SCR-630A  税計算・インボイス設定\""))
        assertTrue(maintenance.contains("PrinterHubHeader(\"SCR-680  保守・診断\""))
        assertTrue(receipt.contains("Text(\"SCR-640  レシート設定\""))

        listOf("SCR-760", "SCR-761", "SCR-762", "SCR-763", "SCR-764", "SCR-767", "SCR-275").forEach {
            assertFalse("legacy screen id leaked: $it", admin.contains(it) || backup.contains(it) || sync.contains(it) || tax.contains(it) || receipt.contains(it))
        }
    }

    @Test
    fun syncAndMaintenanceOperatorCopyUsesJapaneseOperationalTerms() {
        val sync = source("SyncSettingsActivity.kt")
        val maintenance = source("PrinterToolsHubActivity.kt")

        assertTrue(sync.contains("未送信データを1時間ごとに自動処理"))
        assertTrue(sync.contains("未送信データを今すぐ送信"))
        assertTrue(sync.contains("送信準備済みを再送待ちへ"))
        assertTrue(sync.contains("同期キュー（未送信・送信履歴）"))
        assertTrue(sync.contains("送信運用・個別再試行の詳細"))
        assertTrue(sync.contains("未送信キューを同一SQLiteトランザクションで保存"))
        assertFalse(sync.contains("Text(\"Outboxを1時間ごとに自動処理\")"))
        assertFalse(sync.contains("Text(\"今すぐステージ出力・送信\")"))
        assertFalse(sync.contains("Text(\"ステージ済みを再キュー\")"))
        assertFalse(sync.contains("Text(\"Outbox一覧\""))

        assertTrue(maintenance.contains("条件別通信データ採取（RAW）"))
        assertTrue(maintenance.contains("自動テスト成功だけでは、実機互換性の確認完了にはなりません"))
        assertFalse(maintenance.contains("変化ビット候補とCI成功だけでは実機互換性確認完了になりません"))
    }
}
