package jp.co.tenposinfo.register

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class V136AdminSettingsHomeUiTest {
    @Test
    fun settingsSearchMatchesTitleDescriptionAndKeywords() {
        assertTrue(
            settingsMenuMatchesV136(
                "プリンター",
                "プリンター設定",
                "機種、IP、用紙、カット、ドロア",
                "周辺機器 紙幅 58mm 80mm",
            ),
        )
        assertTrue(
            settingsMenuMatchesV136(
                "drive",
                "Google Drive・同期",
                "初期設定、アカウント、送信状況、診断",
                "Google Drive 同期 Outbox",
            ),
        )
        assertTrue(
            settingsMenuMatchesV136(
                " バック ",
                "データ保全",
                "整合性診断、バックアップ、復元",
                "DB 整合性",
            ),
        )
        assertFalse(
            settingsMenuMatchesV136(
                "領収書",
                "担当者・権限",
                "担当者登録、停止、並び順、権限",
                "PIN ロール",
            ),
        )
    }

    @Test
    fun settingsHomeKeepsRequiredNavigationAndStatusSignals() {
        val source = File("src/main/java/jp/co/tenposinfo/register/AdminSettingsActivity.kt").readText()

        assertTrue(source.contains("label = { Text(\"設定を検索\") }"))
        assertTrue(source.contains("title = \"日常設定\""))
        assertTrue(source.contains("title = \"保守・診断・データ\""))
        assertTrue(source.contains("AsValueRow(\"未保存変更\", \"0件（ホーム）\")"))
        assertTrue(source.contains("\"最終バックアップ\""))
        assertTrue(source.contains("\"設定異常\""))
        assertTrue(source.contains("title = \"店舗・レジ設定\""))
        assertTrue(source.contains("title = \"担当者・権限\""))
        assertTrue(source.contains("title = \"商品設定\""))
        assertTrue(source.contains("title = \"プリンター設定\""))
        assertTrue(source.contains("title = \"顧客表示\""))
        assertTrue(source.contains("title = \"データ保全\""))
        assertTrue(source.contains("title = \"Google Drive・同期\""))
        assertTrue(source.contains("title = \"プリンター運用\""))
        assertTrue(source.contains("title = \"監査ログ\""))
        assertTrue(source.contains("title = \"責任者PIN\""))
        assertTrue(source.contains("onCustomerDisplay ="))
        assertTrue(source.contains("onDataProtection ="))
        assertTrue(source.contains("onSync ="))
    }
}
