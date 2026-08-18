package jp.co.tenposinfo.register

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class V135InitialReleaseSettingsTest {
    private val root = File(System.getProperty("user.dir")).let { current ->
        if (File(current, "app").isDirectory) current else current.parentFile
    }

    private fun source(name: String): String = File(
        root,
        "app/src/main/java/jp/co/tenposinfo/register/$name",
    ).readText()

    @Test
    fun v25DefaultsAreRepresented() {
        val store = StoreBasicSettingsV135()
        assertEquals("JPY", store.currency)
        assertEquals("Asia/Tokyo", store.timeZone)
        assertEquals(ReceiptHeaderModeV135.TEXT, store.receiptHeaderMode)
        assertEquals("ありがとうございました", store.footerLines.first())

        val sales = SalesOperationSettingsV135()
        assertEquals(QuantityInputModeV135.QUANTITY_THEN_ITEM, sales.quantityInputMode)
        assertTrue(sales.mergeSameItem)
        assertTrue(sales.repeatKeyEnabled)
        assertEquals(SettingPermissionPolicyV135.MANAGER, sales.priceOverridePolicy)
        assertFalse(sales.quickCashEnabled)
        assertTrue(sales.tenThousandKey)
        assertEquals(3, sales.completeScreenSeconds)
        assertEquals(RestoreWorkCartPolicyV135.ASK, sales.restoreWorkCart)
        assertEquals(40, sales.soundLevel)

        val business = BusinessSettlementSettingsV135()
        assertTrue(business.checkAutoNumber)
        assertEquals(9_999, business.checkNumberMax)
        assertEquals(180, business.longOpenMinutes)
        assertEquals(PrintOnHoldV135.NONE, business.printOnHold)
        assertEquals(BusinessDateModeV135.CONFIRM, business.businessDateMode)
        assertEquals("05:00", business.boundaryTime)
        assertTrue(business.autoBackupAfterSettlement)
        assertEquals(ZeroItemsPrintV135.HIDE, business.zeroItemsPrint)

        val device = DeviceAppSettingsV135()
        assertTrue(device.keepScreenOn)
        assertFalse(device.kioskModeRequested)
        assertTrue(device.operationSoundEnabled)
        assertTrue(device.automaticUpdateCheck)
        assertEquals(1_024, device.storageWarningMb)
    }

    @Test
    fun set001CannotWeakenRep003SettlementSafety() {
        val business = BusinessSettlementSettingsV135()
        assertTrue(business.requireCashCount)
        assertEquals(SettlementWarningPolicyV135.BLOCK, business.openCheckPolicy)
        assertEquals(SettlementWarningPolicyV135.WARN, business.unprintedPolicy)

        val source = source("InitialReleaseSettingsV135.kt")
        assertTrue(source.contains("REP-003"))
        assertTrue(source.contains("val requireCashCount: Boolean get() = true"))
        assertTrue(source.contains("SettlementWarningPolicyV135.BLOCK"))
    }

    @Test
    fun validationRejectsOutOfRangeOrMalformedSettings() {
        val normalized = InitialReleaseSettingsPolicyV135.normalizeStore(
            StoreBasicSettingsV135(
                storeCode = " reg_01 ",
                storeName = " 店舗A ",
                registrationNumber = "t-1234567890123",
            ),
        )
        assertEquals("REG_01", normalized.storeCode)
        assertEquals("店舗A", normalized.storeName)
        assertEquals("T1234567890123", normalized.registrationNumber)

        assertTrue(
            runCatching {
                InitialReleaseSettingsPolicyV135.normalizeStore(
                    StoreBasicSettingsV135(storeCode = "bad code", storeName = "店舗"),
                )
            }.isFailure,
        )
        assertTrue(
            runCatching {
                InitialReleaseSettingsPolicyV135.normalizeStore(
                    StoreBasicSettingsV135(storeName = "店舗", registrationNumber = "T123"),
                )
            }.isFailure,
        )
        assertTrue(
            runCatching {
                InitialReleaseSettingsPolicyV135.normalizeSales(
                    SalesOperationSettingsV135(completeScreenSeconds = 31),
                )
            }.isFailure,
        )
        assertTrue(
            runCatching {
                InitialReleaseSettingsPolicyV135.normalizeSales(
                    SalesOperationSettingsV135(soundLevel = 101),
                )
            }.isFailure,
        )
        assertTrue(
            runCatching {
                InitialReleaseSettingsPolicyV135.normalizeBusiness(
                    BusinessSettlementSettingsV135(checkNumberMax = 1_000_000),
                )
            }.isFailure,
        )
        assertTrue(
            runCatching {
                InitialReleaseSettingsPolicyV135.normalizeBusiness(
                    BusinessSettlementSettingsV135(longOpenMinutes = 29),
                )
            }.isFailure,
        )
        assertTrue(
            runCatching {
                InitialReleaseSettingsPolicyV135.normalizeBusiness(
                    BusinessSettlementSettingsV135(boundaryTime = "25:00"),
                )
            }.isFailure,
        )
    }

    @Test
    fun settingKeysAndCanonicalIssuerStorageMatchTheSpecBoundary() {
        val settings = source("InitialReleaseSettingsV135.kt")
        listOf(
            "store.code",
            "store.branchName",
            "store.postalCode",
            "store.address1",
            "store.address2",
            "store.receiptHeaderMode",
            "sale.quantityInputMode",
            "sale.mergeSameItem",
            "sale.repeatKeyEnabled",
            "sale.priceOverridePolicy",
            "sale.zeroPricePolicy",
            "sale.negativeProductPolicy",
            "sale.lineDeletePolicy",
            "sale.transactionCancelPolicy",
            "sale.quickCashEnabled",
            "sale.tenThousandKey",
            "sale.completeScreenSeconds",
            "sale.restoreWorkCart",
            "sale.operatorSwitchInCart",
            "sale.soundLevel",
            "check.autoNumber",
            "check.numberRange",
            "check.longOpenMinutes",
            "check.printOnHold",
            "businessDate.mode",
            "businessDate.boundaryTime",
            "settlement.autoBackup",
            "settlement.zeroItemsPrint",
        ).forEach { key -> assertTrue("missing setting key $key", settings.contains("\"$key\"")) }

        assertTrue(settings.contains("TaxInvoiceSettingsStore(appContext)"))
        assertTrue(settings.contains("InvoiceIssuerProfile("))
        assertFalse(settings.contains("private const val KEY_STORE_NAME"))
        assertFalse(settings.contains("private const val KEY_REGISTRATION_NUMBER"))
    }

    @Test
    fun scr691Through695AreReachableAndRuntimeHooksExist() {
        val settings = source("InitialReleaseSettingsV135.kt")
        val admin = source("AdminSettingsActivity.kt")
        val main = source("MainActivity.kt")
        val manifest = File(root, "app/src/main/AndroidManifest.xml").readText()

        listOf("SCR-691", "SCR-692", "SCR-693", "SCR-694", "SCR-695").forEach {
            assertTrue(settings.contains(it))
        }
        assertTrue(admin.contains("SCR-690"))
        assertTrue(admin.contains("店舗・レジ設定  SCR-691～695"))
        assertTrue(admin.contains("InitialReleaseSettingsActivityV135::class.java"))
        assertTrue(main.contains("InitialReleaseSettingsStoreV135(applicationContext).loadDevice()"))
        assertTrue(main.contains("initialReleaseSettingsStore.loadSales().mergeSameItem"))
        assertTrue(main.contains("DeviceAppRuntimeV135.applyWindowPolicy"))

        val activityBlock = manifest
            .split("android:name=\".InitialReleaseSettingsActivityV135\"", limit = 2)
            .getOrNull(1)
            .orEmpty()
            .substringBefore("/>")
        assertTrue(activityBlock.contains("android:exported=\"false\""))
        assertTrue(activityBlock.contains("android:screenOrientation=\"landscape\""))
    }

    @Test
    fun setupCompletionRequiresPracticeAndInitialBackup() {
        val source = source("InitialReleaseSettingsV135.kt")
        assertTrue(source.contains("value.practiceTransactionCompleted && value.initialBackupCompleted"))
        assertTrue(source.contains("練習取引"))
        assertTrue(source.contains("初期バックアップ"))
        assertTrue(source.contains("setup.completed"))
    }
}
