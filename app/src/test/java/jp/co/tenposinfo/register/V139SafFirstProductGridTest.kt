package jp.co.tenposinfo.register

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

class V139SafFirstProductGridTest {
    @Test fun productSlotIdentityAndTouchableButtonMinimumArePreserved() {
        val sizes = listOf(210 to 290, 320 to 450, 450 to 720, 720 to 1_000)
        sizes.forEach { (width, height) ->
            val grid = RegisterProductGridPolicy.metrics(width, height)
            assertEquals(3, grid.columns)
            assertEquals(8, grid.rows)
            assertEquals(24, grid.columns * grid.rows)
            assertTrue(grid.buttonHeightDp in 56..116)
            assertTrue(grid.textSizeSp in 11..16)
            assertEquals((1..24).toList(), (0 until grid.rows).flatMap { row ->
                (0 until grid.columns).map { column -> row * grid.columns + column + 1 }
            })
        }
        assertTrue(RegisterProductGridPolicy.metrics(240, 290).scrollRequired)
        assertFalse(RegisterProductGridPolicy.metrics(650, 1_000).scrollRequired)
    }

    @Test fun largerScreenGrowsKeysWhileLargeFontConservesSpace() {
        val small = RegisterProductGridPolicy.metrics(330, 490)
        val large = RegisterProductGridPolicy.metrics(680, 1_000)
        assertTrue(large.buttonHeightDp > small.buttonHeightDp)
        assertTrue(large.textSizeSp >= small.textSizeSp)
        assertTrue(RegisterProductGridPolicy.metrics(680, 1_000, 1.4f).textSizeSp <= 12)
    }

    @Test fun salesUsesActualPaneConstraintsAndTwoRowActionBar() {
        val source = File("src/main/java/jp/co/tenposinfo/register/MainActivity.kt").readText()
        val sales = source.substring(source.indexOf("internal fun SalesScreen("), source.indexOf("private fun LineEditScreen("))
        assertTrue(sales.contains("RegisterProductGridPolicy.metrics("))
        assertTrue(sales.contains("heightDp = maxHeight.value.toInt()"))
        assertTrue(sales.contains("val slot = row * grid.columns + column + 1"))
        assertTrue(sales.contains("grid.buttonHeightDp.dp"))
        assertTrue(sales.contains("Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()))"))
        assertTrue(sales.contains("val twoRowActions ="))
        assertTrue(sales.contains("else if (twoRowActions)"))
        assertTrue(sales.contains("height(122.dp)"))
        assertTrue(sales.contains("onPayment"))
        assertFalse(sales.contains("DELETE FROM sales", ignoreCase = true))
    }

    @Test fun folderExchangeIsPrimaryWhileApiRemainsOptional() {
        val sync = File("src/main/java/jp/co/tenposinfo/register/SyncSettingsActivity.kt").readText()
        assertTrue(sync.contains("Google Driveの送信フォルダを選択"))
        assertTrue(sync.contains("OutboxDeliverySettingsActivity::class.java"))
        assertTrue(sync.contains("Googleかんたん接続（API方式・詳細）"))
        val settingsSave = sync.substring(sync.indexOf("DriveSyncSettingsStore.save("), sync.indexOf("message = \"同期設定を保存しました\""))
        assertFalse(settingsSave.contains("GoogleDriveDirectUploadScheduler.ensurePeriodic"))
        assertTrue(sync.contains("DriveOutboxScheduler.enqueueNow(context.applicationContext)"))
    }
}
