from pathlib import Path


def rep(path: str, old: str, new: str, count: int = 1) -> None:
    p = Path(path)
    s = p.read_text()
    if old not in s:
        raise SystemExit(f"pattern not found in {path}: {old[:180]!r}")
    p.write_text(s.replace(old, new, count))


# MainActivity: route raw HID events through ScannerGateway/InputRouter.
p = "app/src/main/java/jp/co/tenposinfo/register/MainActivity.kt"
rep(p,
'''class MainActivity : ComponentActivity() {
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (BarcodeScannerRuntimeV135.handle(event)) return true
        return super.dispatchKeyEvent(event)
    }

    override fun onCreate(savedInstanceState: Bundle?) {''',
'''class MainActivity : ComponentActivity() {
    private val scannerGatewayV136 = ScannerGatewayV136()

    override fun onStart() {
        super.onStart()
        scannerGatewayV136.start()
    }

    override fun onStop() {
        scannerGatewayV136.stop()
        super.onStop()
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (scannerGatewayV136.handle(event)) return true
        return super.dispatchKeyEvent(event)
    }

    override fun onCreate(savedInstanceState: Bundle?) {''')

rep(p,
'''                canOpenSettings = currentOperator?.isManager == true && currentOperator?.allows(RegisterPermission.SETTINGS) == true,
                canOpenManagement = currentOperator?.permissions?.let(
                    ManagementNavigationPolicyV030::canOpenManagement,
                ) == true,
                onOpenSettings = { context.startActivity(Intent(context, AdminSettingsActivity::class.java)) },
                onOpenManagement = { context.startActivity(Intent(context, OperationsHubActivityV030::class.java)) },''',
'''                canOpenSettings = currentOperator?.isManager == true && currentOperator?.allows(RegisterPermission.SETTINGS) == true,
                canOpenManagement = currentOperator?.permissions?.let(
                    ManagementNavigationPolicyV030::canOpenManagement,
                ) == true,
                onOpenSettings = { context.startActivity(Intent(context, AdminSettingsActivity::class.java)) },
                onOpenCatalogSettings = { scannedCode ->
                    context.startActivity(CatalogNavigationContractV030.productRegistrationIntent(context, scannedCode))
                },
                onOpenManagement = { context.startActivity(Intent(context, OperationsHubActivityV030::class.java)) },''')

rep(p,
'''    canOpenSettings: Boolean,
    canOpenManagement: Boolean,
    onOpenSettings: () -> Unit,
    onOpenManagement: () -> Unit,''',
'''    canOpenSettings: Boolean,
    canOpenManagement: Boolean,
    onOpenSettings: () -> Unit,
    onOpenCatalogSettings: (String) -> Unit,
    onOpenManagement: () -> Unit,''')

rep(p,
'''    var pendingQuantity by remember { mutableStateOf<Int?>(null) }
    var showProductSearch by remember { mutableStateOf(false) }
    var lookupMessage by remember { mutableStateOf<String?>(null) }
    val responsive = rememberRegisterResponsiveMetrics()

    androidx.compose.runtime.DisposableEffect(products, pendingQuantity, onAddProduct) {
        val listener: (String) -> Unit = { scanned ->
            val product = ProductLookupPolicyV135.findExact(products, scanned)
            if (product == null) {
                lookupMessage = "商品未登録: ${scanned.take(20)}"
            } else {
                onAddProduct(product, pendingQuantity ?: 1)
                pendingQuantity = null
                numericInput = ""
                lookupMessage = null
            }
        }
        BarcodeScannerRuntimeV135.setListener(listener)
        onDispose { BarcodeScannerRuntimeV135.clearListener(listener) }
    }

    if (showProductSearch) {''',
'''    var pendingQuantity by remember { mutableStateOf<Int?>(null) }
    var showProductSearch by remember { mutableStateOf(false) }
    var lookupMessage by remember { mutableStateOf<String?>(null) }
    var unregisteredBarcode by remember { mutableStateOf<String?>(null) }
    val barcodeIndex = remember(products) { BarcodeProductIndexV136(products) }
    val responsive = rememberRegisterResponsiveMetrics()

    androidx.compose.runtime.DisposableEffect(barcodeIndex, pendingQuantity, onAddProduct) {
        val listener: (BarcodeScannedV136) -> Unit = { event ->
            val product = barcodeIndex.findExact(event.code)
            if (product == null) {
                unregisteredBarcode = event.code
                lookupMessage = null
            } else {
                onAddProduct(product, pendingQuantity ?: 1)
                pendingQuantity = null
                numericInput = ""
                lookupMessage = null
                unregisteredBarcode = null
            }
        }
        InputRouterV136.setBarcodeListener(listener)
        onDispose { InputRouterV136.clearBarcodeListener(listener) }
    }

    unregisteredBarcode?.let { scannedCode ->
        UnregisteredBarcodeDialogV136(
            code = scannedCode,
            canOpenProductSettings = canOpenSettings,
            onTemporaryProduct = { product ->
                onAddProduct(product, pendingQuantity ?: 1)
                pendingQuantity = null
                numericInput = ""
                lookupMessage = "仮商品として登録しました"
            },
            onOpenProductSettings = { onOpenCatalogSettings(scannedCode) },
            onDismiss = { unregisteredBarcode = null },
        )
    }

    if (showProductSearch) {''')

# Catalog navigation: allow permission-gated product registration to retain scanned barcode.
p = "app/src/main/java/jp/co/tenposinfo/register/CatalogHubActivityV030.kt"
rep(p,
'''object CatalogNavigationContractV030 {
    const val EXTRA_INITIAL_SCREEN = "jp.co.tenposinfo.register.extra.CATALOG_INITIAL_SCREEN"
    const val PRODUCTS = "PRODUCTS"''',
'''object CatalogNavigationContractV030 {
    const val EXTRA_INITIAL_SCREEN = "jp.co.tenposinfo.register.extra.CATALOG_INITIAL_SCREEN"
    const val EXTRA_PREFILL_BARCODE = "jp.co.tenposinfo.register.extra.CATALOG_PREFILL_BARCODE"
    const val PRODUCTS = "PRODUCTS"''')
rep(p,
'''    fun intent(context: Context, destination: String): Intent =
        Intent(context, CatalogSettingsActivity::class.java)
            .putExtra(EXTRA_INITIAL_SCREEN, destination)
}''',
'''    fun intent(context: Context, destination: String): Intent =
        Intent(context, CatalogSettingsActivity::class.java)
            .putExtra(EXTRA_INITIAL_SCREEN, destination)

    fun productRegistrationIntent(context: Context, scannedCode: String): Intent =
        intent(context, PRODUCTS)
            .putExtra(EXTRA_PREFILL_BARCODE, scannedCode.take(64))
}''')

# Product master: prefill the scanned barcode when entered from SAL-007 product registration.
p = "app/src/main/java/jp/co/tenposinfo/register/CatalogSettingsActivity.kt"
rep(p,
'''private fun CatalogSettingsApp(onClose: () -> Unit) {
    val context = LocalContext.current
    val store = remember { CatalogMasterStore(context.applicationContext) }
    val actor = remember { OperatorSessionRegistry.current(context.applicationContext)?.name ?: "責任者" }''',
'''private fun CatalogSettingsApp(onClose: () -> Unit) {
    val context = LocalContext.current
    val store = remember { CatalogMasterStore(context.applicationContext) }
    val actor = remember { OperatorSessionRegistry.current(context.applicationContext)?.name ?: "責任者" }
    val initialBarcode = remember {
        (context as? ComponentActivity)?.intent
            ?.getStringExtra(CatalogNavigationContractV030.EXTRA_PREFILL_BARCODE)
            ?.let { runCatching { CatalogValidation.normalizeBarcode(it) }.getOrNull() }
            .orEmpty()
    }''')
rep(p,
'''            CatalogScreen.PRODUCTS -> ProductMasterScreen(
                store = store,
                refresh = refresh,
                actor = actor,
                onSaved = { saved("商品マスターを保存しました") },''',
'''            CatalogScreen.PRODUCTS -> ProductMasterScreen(
                store = store,
                refresh = refresh,
                actor = actor,
                initialBarcode = initialBarcode,
                onSaved = { saved("商品マスターを保存しました") },''')
rep(p,
'''private fun ProductMasterScreen(
    store: CatalogMasterStore,
    refresh: Int,
    actor: String,
    onSaved: () -> Unit,''',
'''private fun ProductMasterScreen(
    store: CatalogMasterStore,
    refresh: Int,
    actor: String,
    initialBarcode: String,
    onSaved: () -> Unit,''')
rep(p,
'''    var kana by remember { mutableStateOf("") }
    var barcode by remember { mutableStateOf("") }
    var price by remember { mutableStateOf("0") }''',
'''    var kana by remember { mutableStateOf("") }
    var barcode by remember(initialBarcode) { mutableStateOf(initialBarcode) }
    var price by remember { mutableStateOf("0") }''')
rep(p,
'''        kana = selected?.kana.orEmpty()
        barcode = selected?.barcode.orEmpty()
        price = selected?.basePrice?.toString() ?: "0"''',
'''        kana = selected?.kana.orEmpty()
        barcode = selected?.barcode ?: initialBarcode
        price = selected?.basePrice?.toString() ?: "0"''')
