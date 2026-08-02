from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected 1 match, found {count}")
    return text.replace(old, new, 1)


main_path = Path("app/src/main/java/jp/co/tenposinfo/register/MainActivity.kt")
main = main_path.read_text(encoding="utf-8")
main = replace_once(
    main,
    '''    var paymentMessage by remember { mutableStateOf<String?>(null) }
    var saleCommitInProgress by remember { mutableStateOf(false) }
''',
    '''    var paymentMessage by remember { mutableStateOf<String?>(null) }
    var paymentCommitKey by remember { mutableStateOf<String?>(null) }
    var saleCommitInProgress by remember { mutableStateOf(false) }
''',
    "commit key state",
)
main = replace_once(
    main,
    '''        database.saveCart(cart.toList())
        paymentDraftStore.clear()
''',
    '''        database.saveCart(cart.toList())
        paymentDraftStore.clear()
        paymentCommitKey = null
''',
    "clear commit key with cart",
)
main = replace_once(
    main,
    '''                onPayment = {
                    val draft = paymentDraftStore.load(cart.toList())
                    paymentState = draft.state
''',
    '''                onPayment = {
                    val draft = paymentDraftStore.loadOrCreate(cart.toList())
                    paymentState = draft.state
                    paymentCommitKey = draft.commitKey
''',
    "load commit key",
)
main = replace_once(
    main,
    '''                onStateChange = {
                    paymentState = it
                    paymentDraftStore.save(cart.toList(), it)
                    CustomerDisplayRuntime.publish(
''',
    '''                onStateChange = {
                    paymentState = it
                    val commitKey = paymentCommitKey
                        ?: PaymentCommitKey.newKey().also { generated -> paymentCommitKey = generated }
                    paymentDraftStore.save(cart.toList(), it, commitKey)
                    CustomerDisplayRuntime.publish(
''',
    "save commit key",
)
main = replace_once(
    main,
    '''                    paymentMessage = null
                    paymentDraftStore.clear()
                    CustomerDisplayRuntime.publish(
''',
    '''                    paymentMessage = null
                    paymentDraftStore.clear()
                    paymentCommitKey = null
                    CustomerDisplayRuntime.publish(
''',
    "clear key on payment back",
)
main = replace_once(
    main,
    '''                    runCatching {
                        database.saveSale(operatorName, cart.toList(), paymentState, receiptPaper.widthMm)
                    }.onSuccess { saleId ->
''',
    '''                    runCatching {
                        val commitKey = paymentCommitKey
                            ?: paymentDraftStore.loadOrCreate(cart.toList()).commitKey
                            ?: error("会計キーを作成できませんでした")
                        paymentCommitKey = commitKey
                        database.saveSale(
                            operatorName = operatorName,
                            items = cart.toList(),
                            paymentState = paymentState,
                            paperWidthMm = receiptPaper.widthMm,
                            commitKey = commitKey,
                        )
                    }.onSuccess { saleId ->
''',
    "use persistent commit key",
)
main_path.write_text(main, encoding="utf-8")


db_path = Path("app/src/main/java/jp/co/tenposinfo/register/RegisterDatabase.kt")
db = db_path.read_text(encoding="utf-8")
db = replace_once(
    db,
    '''        paymentState: PaymentState,
        paperWidthMm: Int = 80,
    ): Long {
''',
    '''        paymentState: PaymentState,
        paperWidthMm: Int = 80,
        commitKey: String? = null,
    ): Long {
''',
    "saveSale signature",
)
db = replace_once(
    db,
    '''        val summary = TaxEngine.calculate(items)
        require(paymentState.remaining(summary.grossAmount) == 0L) { "Payment is incomplete" }
        BusinessSessionSchema.ensure(writableDatabase)
''',
    '''        val summary = TaxEngine.calculate(items)
        require(paymentState.remaining(summary.grossAmount) == 0L) { "Payment is incomplete" }
        val normalizedCommitKey = commitKey?.trim()?.takeIf { it.isNotEmpty() }
        if (normalizedCommitKey != null) {
            require(PaymentCommitKey.isValid(normalizedCommitKey)) { "Invalid sale commit key" }
        }
        val cartFingerprint = PaymentDraftFingerprint.of(items)
        SaleCommitIdempotencySchema.ensure(writableDatabase)
        SaleCommitIdempotencySchema.cleanup(writableDatabase)
        BusinessSessionSchema.ensure(writableDatabase)
''',
    "saveSale idempotency setup",
)
db = replace_once(
    db,
    '''        return writableDatabase.runInTransactionWithResult {
            val saleId = insertOrThrow(
''',
    '''        return writableDatabase.runInTransactionWithResult {
            if (normalizedCommitKey != null) {
                val existing = SaleCommitIdempotencySchema.find(this, normalizedCommitKey)
                if (existing != null) {
                    SaleCommitIdempotencySchema.requireCompatible(
                        existing = existing,
                        cartFingerprint = cartFingerprint,
                        totalAmount = summary.grossAmount,
                    )
                    delete("cart_items", null, null)
                    delete(
                        "line_tax_snapshots",
                        "scope = ? AND owner_id = ?",
                        arrayOf(LineTaxSnapshotStore.SCOPE_CART, "0"),
                    )
                    return@runInTransactionWithResult existing.saleId
                }
            }
            val saleId = insertOrThrow(
''',
    "existing commit lookup",
)
db = replace_once(
    db,
    '''            // 売上確定と作業中カート消去を同一トランザクションに含める。
''',
    '''            if (normalizedCommitKey != null) {
                SaleCommitIdempotencySchema.record(
                    db = this,
                    commitKey = normalizedCommitKey,
                    saleId = saleId,
                    cartFingerprint = cartFingerprint,
                    totalAmount = summary.grossAmount,
                    createdAt = createdAt,
                )
            }
            // 売上確定と作業中カート消去を同一トランザクションに含める。
''',
    "record commit key",
)
db_path.write_text(db, encoding="utf-8")
print("persistent sale idempotency integrated")
