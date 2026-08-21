package jp.co.tenposinfo.register

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Window
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val IrNavy = Color(0xFF173F6B)
private val IrBlue = Color(0xFF1976B9)
private val IrBackground = Color(0xFFF4F7FA)
private val IrBorder = Color(0xFFD5DEE7)
private val IrPaleBlue = Color(0xFFEAF3FA)
private val IrPaleYellow = Color(0xFFFFF4D9)

enum class QuantityInputModeV135 { QUANTITY_THEN_ITEM, PRICE_THEN_QTY }
enum class SettingPermissionPolicyV135 { DENY, MANAGER, ALLOW }
enum class ZeroPricePolicyV135 { PRODUCT_SETTING, DENY }
enum class LineDeletePolicyV135 { ALLOW, MANAGER }
enum class RestoreWorkCartPolicyV135 { ASK, ALWAYS, NEVER }
enum class BusinessDateModeV135 { AUTO, CONFIRM, MANUAL }
enum class PrintOnHoldV135 { NONE, ORDER, OPEN_CHECK }
enum class SettlementWarningPolicyV135 { BLOCK, WARN, ALLOW }
enum class ZeroItemsPrintV135 { SHOW, HIDE }
enum class ReceiptHeaderModeV135 { TEXT, IMAGE, BOTH }

data class StoreBasicSettingsV135(
    val storeCode: String = "",
    val storeName: String = "店舗名未設定",
    val branchName: String = "",
    val postalCode: String = "",
    val address1: String = "",
    val address2: String = "",
    val phone: String = "",
    val registrationNumber: String = "",
    val receiptHeaderMode: ReceiptHeaderModeV135 = ReceiptHeaderModeV135.TEXT,
    val footerLines: List<String> = listOf("ありがとうございました", "", "", "", ""),
) {
    val currency: String get() = "JPY"
    val timeZone: String get() = "Asia/Tokyo"
}

data class SalesOperationSettingsV135(
    val quantityInputMode: QuantityInputModeV135 = QuantityInputModeV135.QUANTITY_THEN_ITEM,
    val mergeSameItem: Boolean = true,
    val repeatKeyEnabled: Boolean = true,
    val priceOverridePolicy: SettingPermissionPolicyV135 = SettingPermissionPolicyV135.MANAGER,
    val zeroPricePolicy: ZeroPricePolicyV135 = ZeroPricePolicyV135.PRODUCT_SETTING,
    val negativeProductPolicy: SettingPermissionPolicyV135 = SettingPermissionPolicyV135.MANAGER,
    val lineDeletePolicy: LineDeletePolicyV135 = LineDeletePolicyV135.ALLOW,
    val transactionCancelPolicy: SettingPermissionPolicyV135 = SettingPermissionPolicyV135.MANAGER,
    val discountLimitBasisPoints: Int = 10_000,
    val quickCashEnabled: Boolean = false,
    val tenThousandKey: Boolean = true,
    val completeScreenSeconds: Int = 3,
    val restoreWorkCart: RestoreWorkCartPolicyV135 = RestoreWorkCartPolicyV135.ASK,
    val operatorSwitchInCart: Boolean = true,
    val soundLevel: Int = 40,
)

data class BusinessSettlementSettingsV135(
    val checkAutoNumber: Boolean = true,
    val checkNumberMax: Int = 9_999,
    val longOpenMinutes: Int = 180,
    val printOnHold: PrintOnHoldV135 = PrintOnHoldV135.NONE,
    val businessDateMode: BusinessDateModeV135 = BusinessDateModeV135.CONFIRM,
    val boundaryTime: String = "05:00",
    val autoBackupAfterSettlement: Boolean = true,
    val zeroItemsPrint: ZeroItemsPrintV135 = ZeroItemsPrintV135.HIDE,
) {
    // v1.35で確定済みのREP-003安全弁。SET-001から弱めない。
    val requireCashCount: Boolean get() = true
    val openCheckPolicy: SettlementWarningPolicyV135 get() = SettlementWarningPolicyV135.BLOCK
    val unprintedPolicy: SettlementWarningPolicyV135 get() = SettlementWarningPolicyV135.WARN
}

data class DeviceAppSettingsV135(
    val keepScreenOn: Boolean = true,
    val kioskModeRequested: Boolean = false,
    val operationSoundEnabled: Boolean = true,
    val automaticUpdateCheck: Boolean = true,
    val storageWarningMb: Int = 1_024,
)

data class InitialSetupProgressV135(
    val practiceTransactionCompleted: Boolean = false,
    val initialBackupCompleted: Boolean = false,
    val setupCompleted: Boolean = false,
)

object InitialReleaseSettingsPolicyV135 {
    private val storeCodePattern = Regex("[A-Za-z0-9_-]{1,20}")
    private val timePattern = Regex("(?:[01]\\d|2[0-3]):[0-5]\\d")

    fun normalizeStore(value: StoreBasicSettingsV135): StoreBasicSettingsV135 {
        val code = value.storeCode.trim().uppercase()
        require(code.isEmpty() || storeCodePattern.matches(code)) { "店舗コードは英数字・_・-で1～20文字です" }
        val name = singleLine(value.storeName)
        require(name.isNotBlank() && name.length <= 64) { "店舗名は1～64文字です" }
        val branch = singleLine(value.branchName)
        require(branch.length <= 64) { "支店名は64文字以内です" }
        val postal = singleLine(value.postalCode)
        require(postal.length <= 10) { "郵便番号は10文字以内です" }
        val address1 = singleLine(value.address1)
        val address2 = singleLine(value.address2)
        require(address1.length <= 64 && address2.length <= 64) { "住所は各64文字以内です" }
        val phone = singleLine(value.phone)
        require(phone.length <= 24) { "電話番号は24文字以内です" }
        val footer = value.footerLines.take(5).map(::singleLine).toMutableList()
        while (footer.size < 5) footer += ""
        require(footer.all { it.length <= 64 }) { "レシート末尾文は各64文字以内です" }
        val registration = value.registrationNumber.uppercase().replace("-", "").replace(" ", "").trim()
        require(registration.isBlank() || Regex("T[0-9]{13}").matches(registration)) {
            "登録番号はTに続く13桁で入力してください"
        }
        return value.copy(
            storeCode = code,
            storeName = name,
            branchName = branch,
            postalCode = postal,
            address1 = address1,
            address2 = address2,
            phone = phone,
            registrationNumber = registration,
            footerLines = footer,
        )
    }

    fun normalizeSales(value: SalesOperationSettingsV135): SalesOperationSettingsV135 {
        require(value.discountLimitBasisPoints in 1..10_000) { "値引率上限は0.01～100.00%です" }
        require(value.completeScreenSeconds in 0..30) { "会計完了表示は0～30秒です" }
        require(value.soundLevel in 0..100) { "操作音量は0～100です" }
        return value
    }

    fun normalizeBusiness(value: BusinessSettlementSettingsV135): BusinessSettlementSettingsV135 {
        require(value.checkNumberMax in 1..999_999) { "伝票番号上限は1～999999です" }
        require(value.longOpenMinutes == 0 || value.longOpenMinutes in 30..1_440) {
            "長時間伝票警告は0または30～1440分です"
        }
        require(timePattern.matches(value.boundaryTime)) { "営業日境界はHH:mmで入力してください" }
        return value
    }

    fun normalizeDevice(value: DeviceAppSettingsV135): DeviceAppSettingsV135 {
        require(value.storageWarningMb in 128..65_536) { "容量警告は128～65536MBです" }
        return value
    }

    private fun singleLine(value: String): String = value.replace(Regex("[\\r\\n\\t]+"), " ").trim()
}

class InitialReleaseSettingsStoreV135(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun loadStore(): StoreBasicSettingsV135 {
        val issuer = TaxInvoiceSettingsStore(appContext).load().issuer
        return StoreBasicSettingsV135(
            storeCode = prefs.getString(KEY_STORE_CODE, "").orEmpty(),
            storeName = issuer.storeName,
            branchName = prefs.getString(KEY_BRANCH_NAME, "").orEmpty(),
            postalCode = prefs.getString(KEY_POSTAL_CODE, "").orEmpty(),
            address1 = prefs.getString(KEY_ADDRESS1, issuer.address).orEmpty(),
            address2 = prefs.getString(KEY_ADDRESS2, "").orEmpty(),
            phone = issuer.phone,
            registrationNumber = issuer.registrationNumber,
            receiptHeaderMode = enumValue(KEY_HEADER_MODE, ReceiptHeaderModeV135.TEXT),
            footerLines = (1..5).map { index ->
                prefs.getString("footer_$index", if (index == 1) "ありがとうございました" else "").orEmpty()
            },
        )
    }

    fun saveStore(value: StoreBasicSettingsV135, actor: String): StoreBasicSettingsV135 {
        val clean = InitialReleaseSettingsPolicyV135.normalizeStore(value)
        val existingCode = prefs.getString(KEY_STORE_CODE, "").orEmpty()
        require(existingCode.isBlank() || existingCode == clean.storeCode) {
            "店舗コードは初回確定後にこの画面から変更できません"
        }
        val taxStore = TaxInvoiceSettingsStore(appContext)
        val currentTax = taxStore.load()
        taxStore.save(
            currentTax.copy(
                issuer = InvoiceIssuerProfile(
                    storeName = clean.storeName,
                    address = listOf(clean.address1, clean.address2).filter(String::isNotBlank).joinToString(" "),
                    phone = clean.phone,
                    registrationNumber = clean.registrationNumber,
                ),
            ),
        )
        prefs.edit()
            .putString(KEY_STORE_CODE, clean.storeCode)
            .putString(KEY_BRANCH_NAME, clean.branchName)
            .putString(KEY_POSTAL_CODE, clean.postalCode)
            .putString(KEY_ADDRESS1, clean.address1)
            .putString(KEY_ADDRESS2, clean.address2)
            .putString(KEY_HEADER_MODE, clean.receiptHeaderMode.name)
            .apply {
                clean.footerLines.forEachIndexed { index, line -> putString("footer_${index + 1}", line) }
            }
            .apply()
        audit("SETTINGS_STORE_BASIC_UPDATED", "SCR-691 店舗基本設定を保存", actor)
        return clean
    }

    fun loadSales(): SalesOperationSettingsV135 = SalesOperationSettingsV135(
        quantityInputMode = enumValue(KEY_QUANTITY_MODE, QuantityInputModeV135.QUANTITY_THEN_ITEM),
        mergeSameItem = prefs.getBoolean(KEY_MERGE_SAME, true),
        repeatKeyEnabled = prefs.getBoolean(KEY_REPEAT, true),
        priceOverridePolicy = enumValue(KEY_PRICE_OVERRIDE, SettingPermissionPolicyV135.MANAGER),
        zeroPricePolicy = enumValue(KEY_ZERO_PRICE, ZeroPricePolicyV135.PRODUCT_SETTING),
        negativeProductPolicy = enumValue(KEY_NEGATIVE, SettingPermissionPolicyV135.MANAGER),
        lineDeletePolicy = enumValue(KEY_LINE_DELETE, LineDeletePolicyV135.ALLOW),
        transactionCancelPolicy = enumValue(KEY_TX_CANCEL, SettingPermissionPolicyV135.MANAGER),
        discountLimitBasisPoints = prefs.getInt(KEY_DISCOUNT_LIMIT, 10_000),
        quickCashEnabled = prefs.getBoolean(KEY_QUICK_CASH, false),
        tenThousandKey = prefs.getBoolean(KEY_TEN_THOUSAND, true),
        completeScreenSeconds = prefs.getInt(KEY_COMPLETE_SECONDS, 3),
        restoreWorkCart = enumValue(KEY_RESTORE_CART, RestoreWorkCartPolicyV135.ASK),
        operatorSwitchInCart = prefs.getBoolean(KEY_OPERATOR_SWITCH, true),
        soundLevel = prefs.getInt(KEY_SOUND_LEVEL, 40),
    )

    fun saveSales(value: SalesOperationSettingsV135, actor: String): SalesOperationSettingsV135 {
        val clean = InitialReleaseSettingsPolicyV135.normalizeSales(value)
        prefs.edit()
            .putString(KEY_QUANTITY_MODE, clean.quantityInputMode.name)
            .putBoolean(KEY_MERGE_SAME, clean.mergeSameItem)
            .putBoolean(KEY_REPEAT, clean.repeatKeyEnabled)
            .putString(KEY_PRICE_OVERRIDE, clean.priceOverridePolicy.name)
            .putString(KEY_ZERO_PRICE, clean.zeroPricePolicy.name)
            .putString(KEY_NEGATIVE, clean.negativeProductPolicy.name)
            .putString(KEY_LINE_DELETE, clean.lineDeletePolicy.name)
            .putString(KEY_TX_CANCEL, clean.transactionCancelPolicy.name)
            .putInt(KEY_DISCOUNT_LIMIT, clean.discountLimitBasisPoints)
            .putBoolean(KEY_QUICK_CASH, clean.quickCashEnabled)
            .putBoolean(KEY_TEN_THOUSAND, clean.tenThousandKey)
            .putInt(KEY_COMPLETE_SECONDS, clean.completeScreenSeconds)
            .putString(KEY_RESTORE_CART, clean.restoreWorkCart.name)
            .putBoolean(KEY_OPERATOR_SWITCH, clean.operatorSwitchInCart)
            .putInt(KEY_SOUND_LEVEL, clean.soundLevel)
            .apply()
        audit("SETTINGS_SALES_OPERATION_UPDATED", "SCR-692 販売操作設定を保存", actor)
        return clean
    }

    fun loadBusiness(): BusinessSettlementSettingsV135 = BusinessSettlementSettingsV135(
        checkAutoNumber = prefs.getBoolean(KEY_CHECK_AUTO, true),
        checkNumberMax = prefs.getInt(KEY_CHECK_MAX, 9_999),
        longOpenMinutes = prefs.getInt(KEY_LONG_OPEN, 180),
        printOnHold = enumValue(KEY_PRINT_HOLD, PrintOnHoldV135.NONE),
        businessDateMode = enumValue(KEY_BUSINESS_MODE, BusinessDateModeV135.CONFIRM),
        boundaryTime = prefs.getString(KEY_BOUNDARY, "05:00").orEmpty(),
        autoBackupAfterSettlement = prefs.getBoolean(KEY_AUTO_BACKUP, true),
        zeroItemsPrint = enumValue(KEY_ZERO_PRINT, ZeroItemsPrintV135.HIDE),
    )

    fun saveBusiness(value: BusinessSettlementSettingsV135, actor: String): BusinessSettlementSettingsV135 {
        val clean = InitialReleaseSettingsPolicyV135.normalizeBusiness(value)
        prefs.edit()
            .putBoolean(KEY_CHECK_AUTO, clean.checkAutoNumber)
            .putInt(KEY_CHECK_MAX, clean.checkNumberMax)
            .putInt(KEY_LONG_OPEN, clean.longOpenMinutes)
            .putString(KEY_PRINT_HOLD, clean.printOnHold.name)
            .putString(KEY_BUSINESS_MODE, clean.businessDateMode.name)
            .putString(KEY_BOUNDARY, clean.boundaryTime)
            .putBoolean(KEY_AUTO_BACKUP, clean.autoBackupAfterSettlement)
            .putString(KEY_ZERO_PRINT, clean.zeroItemsPrint.name)
            .apply()
        audit("SETTINGS_BUSINESS_SETTLEMENT_UPDATED", "SCR-693 営業日・点検精算設定を保存", actor)
        return clean
    }

    fun loadDevice(): DeviceAppSettingsV135 = DeviceAppSettingsV135(
        keepScreenOn = prefs.getBoolean(KEY_KEEP_SCREEN, true),
        kioskModeRequested = prefs.getBoolean(KEY_KIOSK, false),
        operationSoundEnabled = prefs.getBoolean(KEY_OPERATION_SOUND, true),
        automaticUpdateCheck = prefs.getBoolean(KEY_UPDATE_CHECK, true),
        storageWarningMb = prefs.getInt(KEY_STORAGE_WARN, 1_024),
    )

    fun saveDevice(value: DeviceAppSettingsV135, actor: String): DeviceAppSettingsV135 {
        val clean = InitialReleaseSettingsPolicyV135.normalizeDevice(value)
        prefs.edit()
            .putBoolean(KEY_KEEP_SCREEN, clean.keepScreenOn)
            .putBoolean(KEY_KIOSK, clean.kioskModeRequested)
            .putBoolean(KEY_OPERATION_SOUND, clean.operationSoundEnabled)
            .putBoolean(KEY_UPDATE_CHECK, clean.automaticUpdateCheck)
            .putInt(KEY_STORAGE_WARN, clean.storageWarningMb)
            .apply()
        audit("SETTINGS_DEVICE_APP_UPDATED", "SCR-694 端末・アプリ設定を保存", actor)
        return clean
    }

    fun loadSetupProgress(): InitialSetupProgressV135 = InitialSetupProgressV135(
        practiceTransactionCompleted = prefs.getBoolean(KEY_PRACTICE_DONE, false),
        initialBackupCompleted = prefs.getBoolean(KEY_BACKUP_DONE, false),
        setupCompleted = prefs.getBoolean(KEY_SETUP_DONE, false),
    )

    fun saveSetupProgress(value: InitialSetupProgressV135, actor: String): InitialSetupProgressV135 {
        require(!value.setupCompleted || (value.practiceTransactionCompleted && value.initialBackupCompleted)) {
            "設定完了には練習取引と初期バックアップの確認が必要です"
        }
        prefs.edit()
            .putBoolean(KEY_PRACTICE_DONE, value.practiceTransactionCompleted)
            .putBoolean(KEY_BACKUP_DONE, value.initialBackupCompleted)
            .putBoolean(KEY_SETUP_DONE, value.setupCompleted)
            .apply()
        audit("INITIAL_SETUP_PROGRESS_UPDATED", "SCR-695 初期設定進捗を更新", actor)
        return value
    }

    private inline fun <reified T : Enum<T>> enumValue(key: String, default: T): T = runCatching {
        enumValueOf<T>(prefs.getString(key, default.name).orEmpty())
    }.getOrDefault(default)

    private fun audit(event: String, detail: String, actor: String) {
        runCatching {
            AdminSettingsStore(appContext).use {
                it.recordOperationalAudit(event, 0L, detail, actor.ifBlank { "責任者" })
            }
        }
    }

    companion object {
        private const val PREFS = "initial_release_settings_v135"
        private const val KEY_STORE_CODE = "store.code"
        private const val KEY_BRANCH_NAME = "store.branchName"
        private const val KEY_POSTAL_CODE = "store.postalCode"
        private const val KEY_ADDRESS1 = "store.address1"
        private const val KEY_ADDRESS2 = "store.address2"
        private const val KEY_HEADER_MODE = "store.receiptHeaderMode"
        private const val KEY_QUANTITY_MODE = "sale.quantityInputMode"
        private const val KEY_MERGE_SAME = "sale.mergeSameItem"
        private const val KEY_REPEAT = "sale.repeatKeyEnabled"
        private const val KEY_PRICE_OVERRIDE = "sale.priceOverridePolicy"
        private const val KEY_ZERO_PRICE = "sale.zeroPricePolicy"
        private const val KEY_NEGATIVE = "sale.negativeProductPolicy"
        private const val KEY_LINE_DELETE = "sale.lineDeletePolicy"
        private const val KEY_TX_CANCEL = "sale.transactionCancelPolicy"
        private const val KEY_DISCOUNT_LIMIT = "sale.discountLimitBasisPoints"
        private const val KEY_QUICK_CASH = "sale.quickCashEnabled"
        private const val KEY_TEN_THOUSAND = "sale.tenThousandKey"
        private const val KEY_COMPLETE_SECONDS = "sale.completeScreenSeconds"
        private const val KEY_RESTORE_CART = "sale.restoreWorkCart"
        private const val KEY_OPERATOR_SWITCH = "sale.operatorSwitchInCart"
        private const val KEY_SOUND_LEVEL = "sale.soundLevel"
        private const val KEY_CHECK_AUTO = "check.autoNumber"
        private const val KEY_CHECK_MAX = "check.numberRange"
        private const val KEY_LONG_OPEN = "check.longOpenMinutes"
        private const val KEY_PRINT_HOLD = "check.printOnHold"
        private const val KEY_BUSINESS_MODE = "businessDate.mode"
        private const val KEY_BOUNDARY = "businessDate.boundaryTime"
        private const val KEY_AUTO_BACKUP = "settlement.autoBackup"
        private const val KEY_ZERO_PRINT = "settlement.zeroItemsPrint"
        private const val KEY_KEEP_SCREEN = "device.keepScreenOn"
        private const val KEY_KIOSK = "device.kioskModeRequested"
        private const val KEY_OPERATION_SOUND = "device.operationSoundEnabled"
        private const val KEY_UPDATE_CHECK = "app.automaticUpdateCheck"
        private const val KEY_STORAGE_WARN = "device.storageWarningMb"
        private const val KEY_PRACTICE_DONE = "setup.practiceTransactionCompleted"
        private const val KEY_BACKUP_DONE = "setup.initialBackupCompleted"
        private const val KEY_SETUP_DONE = "setup.completed"
    }
}

object DeviceAppRuntimeV135 {
    fun applyWindowPolicy(window: Window, settings: DeviceAppSettingsV135) {
        if (settings.keepScreenOn) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
}

class InitialReleaseSettingsActivityV135 : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureRegisterSystemBars(window)
        setContent {
            MaterialTheme {
                InitialReleaseSettingsAppV135(
                    actor = intent.getStringExtra(EXTRA_ACTOR).orEmpty().ifBlank { "責任者" },
                    onClose = { finish() },
                )
            }
        }
    }

    companion object {
        const val EXTRA_ACTOR = "jp.co.tenposinfo.register.extra.SETTINGS_ACTOR_V135"
    }
}

private enum class InitialReleaseScreenV135(val id: String, val label: String) {
    STORE("SCR-691", "店舗基本"),
    SALES("SCR-692", "販売操作"),
    BUSINESS("SCR-693", "営業日・精算"),
    DEVICE("SCR-694", "端末・アプリ"),
    WIZARD("SCR-695", "初期設定"),
}

@Composable
private fun InitialReleaseSettingsAppV135(actor: String, onClose: () -> Unit) {
    val context = LocalContext.current
    val store = remember { InitialReleaseSettingsStoreV135(context.applicationContext) }
    var screen by remember { mutableStateOf(InitialReleaseScreenV135.STORE) }
    Surface(Modifier.fillMaxSize(), color = IrBackground) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().height(64.dp).background(IrNavy).padding(horizontal = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(screen.id, color = Color.White, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(16.dp))
                Text(screen.label + "設定", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                Text("認証: $actor", color = Color.White)
            }
            Row(Modifier.weight(1f).padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.width(210.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    InitialReleaseScreenV135.entries.forEach { item ->
                        val selected = item == screen
                        if (selected) {
                            Button(
                                onClick = { screen = item },
                                modifier = Modifier.fillMaxWidth().height(52.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = IrBlue),
                            ) { Text("${item.id} ${item.label}") }
                        } else {
                            OutlinedButton(
                                onClick = { screen = item },
                                modifier = Modifier.fillMaxWidth().height(52.dp),
                            ) { Text(item.label) }
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    OutlinedButton(onClick = onClose, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("戻る") }
                }
                Card(
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    shape = RoundedCornerShape(10.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                ) {
                    when (screen) {
                        InitialReleaseScreenV135.STORE -> StoreBasicScreenV135(store, actor)
                        InitialReleaseScreenV135.SALES -> SalesOperationScreenV135(store, actor)
                        InitialReleaseScreenV135.BUSINESS -> BusinessSettingsScreenV135(store, actor)
                        InitialReleaseScreenV135.DEVICE -> DeviceSettingsScreenV135(store, actor)
                        InitialReleaseScreenV135.WIZARD -> SetupWizardScreenV135(store, actor)
                    }
                }
            }
        }
    }
}

@Composable
private fun StoreBasicScreenV135(store: InitialReleaseSettingsStoreV135, actor: String) {
    var value by remember { mutableStateOf(store.loadStore()) }
    var message by remember { mutableStateOf<String?>(null) }
    val scroll = rememberScrollState()
    Column(Modifier.fillMaxSize().padding(18.dp).verticalScroll(scroll), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("店舗情報・適格請求書・店名スタンプ", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = IrNavy)
        SettingTextFieldV135("店舗コード", value.storeCode, { value = value.copy(storeCode = it) }, "初回確定後は変更不可")
        SettingTextFieldV135("店舗名", value.storeName, { value = value.copy(storeName = it) })
        SettingTextFieldV135("支店名", value.branchName, { value = value.copy(branchName = it) })
        SettingTextFieldV135("郵便番号", value.postalCode, { value = value.copy(postalCode = it) })
        SettingTextFieldV135("住所1", value.address1, { value = value.copy(address1 = it) })
        SettingTextFieldV135("住所2", value.address2, { value = value.copy(address2 = it) })
        SettingTextFieldV135("電話番号", value.phone, { value = value.copy(phone = it) })
        SettingTextFieldV135("適格請求書登録番号", value.registrationNumber, { value = value.copy(registrationNumber = it) }, "空欄またはT+13桁")
        Text("通貨: ${value.currency}（固定） / タイムゾーン: ${value.timeZone}（固定）")
        EnumCycleRowV135("店名スタンプ", value.receiptHeaderMode) { value = value.copy(receiptHeaderMode = it) }
        value.footerLines.forEachIndexed { index, line ->
            SettingTextFieldV135("レシート末尾 ${index + 1}", line, { updated ->
                value = value.copy(footerLines = value.footerLines.toMutableList().apply { set(index, updated) })
            })
        }
        SaveRowV135(message) {
            runCatching { store.saveStore(value, actor) }
                .onSuccess { value = it; message = "保存しました" }
                .onFailure { message = it.message }
        }
    }
}

@Composable
private fun SalesOperationScreenV135(store: InitialReleaseSettingsStoreV135, actor: String) {
    var value by remember { mutableStateOf(store.loadSales()) }
    var message by remember { mutableStateOf<String?>(null) }
    val scroll = rememberScrollState()
    Column(Modifier.fillMaxSize().padding(18.dp).verticalScroll(scroll), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("置数・行統合・訂正・値引・会計完了動作", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = IrNavy)
        EnumCycleRowV135("置数方式", value.quantityInputMode) { value = value.copy(quantityInputMode = it) }
        BoolRowV135("同一商品を行統合", value.mergeSameItem) { value = value.copy(mergeSameItem = it) }
        BoolRowV135("リピートキー", value.repeatKeyEnabled) { value = value.copy(repeatKeyEnabled = it) }
        EnumCycleRowV135("単価上書き", value.priceOverridePolicy) { value = value.copy(priceOverridePolicy = it) }
        EnumCycleRowV135("0円商品", value.zeroPricePolicy) { value = value.copy(zeroPricePolicy = it) }
        EnumCycleRowV135("マイナス商品", value.negativeProductPolicy) { value = value.copy(negativeProductPolicy = it) }
        EnumCycleRowV135("未確定行削除", value.lineDeletePolicy) { value = value.copy(lineDeletePolicy = it) }
        EnumCycleRowV135("取引中止", value.transactionCancelPolicy) { value = value.copy(transactionCancelPolicy = it) }
        NumericSettingV135("値引率上限（0.01%単位）", value.discountLimitBasisPoints) { value = value.copy(discountLimitBasisPoints = it) }
        Text("客数既定値: 即時会計 0 / 保留伝票 1（仕様固定）")
        BoolRowV135("現金クイック", value.quickCashEnabled) { value = value.copy(quickCashEnabled = it) }
        BoolRowV135("万円キー", value.tenThousandKey) { value = value.copy(tenThousandKey = it) }
        NumericSettingV135("会計完了表示秒数", value.completeScreenSeconds) { value = value.copy(completeScreenSeconds = it) }
        EnumCycleRowV135("異常終了カート復元", value.restoreWorkCart) { value = value.copy(restoreWorkCart = it) }
        BoolRowV135("取引中の担当者切替", value.operatorSwitchInCart) { value = value.copy(operatorSwitchInCart = it) }
        NumericSettingV135("操作音量", value.soundLevel) { value = value.copy(soundLevel = it) }
        SaveRowV135(message) {
            runCatching { store.saveSales(value, actor) }
                .onSuccess { value = it; message = "保存しました" }
                .onFailure { message = it.message }
        }
    }
}

@Composable
private fun BusinessSettingsScreenV135(store: InitialReleaseSettingsStoreV135, actor: String) {
    var value by remember { mutableStateOf(store.loadBusiness()) }
    var message by remember { mutableStateOf<String?>(null) }
    val scroll = rememberScrollState()
    Column(Modifier.fillMaxSize().padding(18.dp).verticalScroll(scroll), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("伝票・営業日・点検精算", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = IrNavy)
        BoolRowV135("伝票番号自動採番", value.checkAutoNumber) { value = value.copy(checkAutoNumber = it) }
        NumericSettingV135("伝票番号上限", value.checkNumberMax) { value = value.copy(checkNumberMax = it) }
        Text("同一営業日の伝票番号重複: 禁止（固定）")
        NumericSettingV135("長時間伝票警告（分、0=無効）", value.longOpenMinutes) { value = value.copy(longOpenMinutes = it) }
        EnumCycleRowV135("保留時自動印刷", value.printOnHold) { value = value.copy(printOnHold = it) }
        EnumCycleRowV135("営業日決定", value.businessDateMode) { value = value.copy(businessDateMode = it) }
        SettingTextFieldV135("営業日境界", value.boundaryTime, { value = value.copy(boundaryTime = it) }, "HH:mm")
        Card(colors = CardDefaults.cardColors(containerColor = IrPaleYellow), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text("v1.35 精算安全弁", fontWeight = FontWeight.Bold)
                Text("現金実査: 必須 / 未会計伝票: BLOCK / 未印刷: 責任者確認")
                Text("REP-003で確定済みの安全条件はこの設定画面から弱めません。", fontSize = 13.sp)
            }
        }
        BoolRowV135("Z精算後の自動バックアップ", value.autoBackupAfterSettlement) { value = value.copy(autoBackupAfterSettlement = it) }
        EnumCycleRowV135("0円項目印字", value.zeroItemsPrint) { value = value.copy(zeroItemsPrint = it) }
        SaveRowV135(message) {
            runCatching { store.saveBusiness(value, actor) }
                .onSuccess { value = it; message = "保存しました" }
                .onFailure { message = it.message }
        }
    }
}

@Composable
private fun DeviceSettingsScreenV135(store: InitialReleaseSettingsStoreV135, actor: String) {
    val context = LocalContext.current
    var value by remember { mutableStateOf(store.loadDevice()) }
    var message by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().padding(18.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("キオスク・画面・音・更新・容量警告", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = IrNavy)
        BoolRowV135("販売中は画面を消灯しない", value.keepScreenOn) { value = value.copy(keepScreenOn = it) }
        BoolRowV135("キオスク運用を要求", value.kioskModeRequested) { value = value.copy(kioskModeRequested = it) }
        Text("※ Androidの端末固定モード自体はDevice Owner等の端末側許可がある場合のみ有効化します。", fontSize = 13.sp)
        BoolRowV135("操作音を有効", value.operationSoundEnabled) { value = value.copy(operationSoundEnabled = it) }
        BoolRowV135("更新確認を有効", value.automaticUpdateCheck) { value = value.copy(automaticUpdateCheck = it) }
        NumericSettingV135("空き容量警告しきい値（MB）", value.storageWarningMb) { value = value.copy(storageWarningMb = it) }
        OutlinedButton(
            onClick = { context.startActivity(Intent(context, DataProtectionActivity::class.java)) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("容量・データ保全を確認") }
        SaveRowV135(message) {
            runCatching { store.saveDevice(value, actor) }
                .onSuccess {
                    value = it
                    (context as? android.app.Activity)?.window?.let { window -> DeviceAppRuntimeV135.applyWindowPolicy(window, it) }
                    message = "保存しました。販売画面の画面維持設定は次回表示から反映します"
                }
                .onFailure { message = it.message }
        }
    }
}

@Composable
private fun SetupWizardScreenV135(store: InitialReleaseSettingsStoreV135, actor: String) {
    val context = LocalContext.current
    var progress by remember { mutableStateOf(store.loadSetupProgress()) }
    var message by remember { mutableStateOf<String?>(null) }
    val basic = remember { store.loadStore() }
    val printerReady = remember {
        runCatching { AdminSettingsStore(context.applicationContext).use { it.loadPrinterConfiguration().usable } }.getOrDefault(false)
    }
    val operatorReady = remember {
        runCatching { AdminSettingsStore(context.applicationContext).use { it.listOperators().any { op -> op.enabled } } }.getOrDefault(false)
    }
    val productReady = remember {
        runCatching { RegisterDatabase(context.applicationContext).use { it.loadProducts().isNotEmpty() } }.getOrDefault(false)
    }
    Column(Modifier.fillMaxSize().padding(18.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("初期設定ウィザード", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = IrNavy)
        Text("店舗 → 税 → 商品 → 支払 → 担当者 → プリンタ → 練習取引 → 初期バックアップ")
        SetupStepV135("1. 店舗", basic.storeCode.isNotBlank() && basic.storeName != "店舗名未設定") {
            context.startActivity(Intent(context, InitialReleaseSettingsActivityV135::class.java).putExtra(InitialReleaseSettingsActivityV135.EXTRA_ACTOR, actor))
        }
        SetupStepV135("2. 税・インボイス", true) { context.startActivity(Intent(context, TaxInvoiceSettingsActivity::class.java)) }
        SetupStepV135("3. 商品", productReady) { context.startActivity(Intent(context, CatalogHubActivityV030::class.java)) }
        SetupStepV135("4. 支払", true) { message = "現金支払は初版で必須・常時有効です" }
        SetupStepV135("5. 担当者", operatorReady) { context.startActivity(Intent(context, AdminSettingsActivity::class.java)) }
        SetupStepV135("6. プリンタ", printerReady) { context.startActivity(Intent(context, AdminSettingsActivity::class.java)) }
        BoolRowV135("7. 練習取引を確認済み", progress.practiceTransactionCompleted) {
            progress = progress.copy(practiceTransactionCompleted = it, setupCompleted = false)
        }
        BoolRowV135("8. 初期バックアップを確認済み", progress.initialBackupCompleted) {
            progress = progress.copy(initialBackupCompleted = it, setupCompleted = false)
        }
        OutlinedButton(onClick = { context.startActivity(Intent(context, DataProtectionActivity::class.java)) }, modifier = Modifier.fillMaxWidth()) {
            Text("バックアップ・復元画面を開く")
        }
        Button(
            onClick = {
                val completed = progress.copy(setupCompleted = true)
                runCatching { store.saveSetupProgress(completed, actor) }
                    .onSuccess { progress = it; message = "初期設定を完了しました。販売を開始できます" }
                    .onFailure { message = it.message }
            },
            enabled = progress.practiceTransactionCompleted && progress.initialBackupCompleted,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            colors = ButtonDefaults.buttonColors(containerColor = IrBlue),
        ) { Text(if (progress.setupCompleted) "設定完了済み" else "設定完了") }
        message?.let { Text(it, color = IrNavy, fontWeight = FontWeight.Bold) }
    }
}

@Composable
private fun SetupStepV135(label: String, ready: Boolean, onOpen: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Checkbox(checked = ready, onCheckedChange = null)
        Text(label, modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
        OutlinedButton(onClick = onOpen) { Text("設定") }
    }
}

@Composable
private fun BoolRowV135(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private inline fun <reified T : Enum<T>> EnumCycleRowV135(label: String, value: T, crossinline onChange: (T) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
        OutlinedButton(onClick = {
            val values = enumValues<T>()
            onChange(values[(value.ordinal + 1) % values.size])
        }) { Text(value.name) }
    }
}

@Composable
private fun NumericSettingV135(label: String, value: Int, onChange: (Int) -> Unit) {
    OutlinedTextField(
        value = value.toString(),
        onValueChange = { text -> text.filter(Char::isDigit).toIntOrNull()?.let(onChange) },
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun SettingTextFieldV135(label: String, value: String, onChange: (String) -> Unit, supporting: String? = null) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        supportingText = supporting?.let { { Text(it) } },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun SaveRowV135(message: String?, onSave: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(message.orEmpty(), modifier = Modifier.weight(1f), color = IrNavy, fontWeight = FontWeight.Bold)
        Button(onClick = onSave, colors = ButtonDefaults.buttonColors(containerColor = IrBlue), modifier = Modifier.width(180.dp).height(50.dp)) {
            Text("保存")
        }
    }
}
