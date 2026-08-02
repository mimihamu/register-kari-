from pathlib import Path

path = Path("app/src/main/java/jp/co/tenposinfo/register/MainActivity.kt")
text = path.read_text(encoding="utf-8")


def replace_once(old: str, new: str, label: str) -> None:
    global text
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected 1 match, found {count}")
    text = text.replace(old, new, 1)


replace_once(
    '''    val heldTicketCoordinator = remember { HeldTicketSafetyCoordinator(database) }
    val saleCommitGuard = remember { SaleCommitGuard() }
''',
    '''    val heldTicketCoordinator = remember { HeldTicketSafetyCoordinator(database) }
    val paymentDraftStore = remember { PaymentDraftStore(database) }
    val saleCommitGuard = remember { SaleCommitGuard() }
''',
    "payment draft store",
)

replace_once(
    '''    fun replaceCart(items: List<CartItem>) {
        cart.clear()
        cart.addAll(items)
        selectedIndex = null
        database.saveCart(cart.toList())
    }
''',
    '''    fun replaceCart(items: List<CartItem>) {
        cart.clear()
        cart.addAll(items)
        selectedIndex = null
        database.saveCart(cart.toList())
        paymentDraftStore.clear()
    }
''',
    "clear draft when cart replaced",
)

replace_once(
    '''                onPayment = {
                    paymentState = PaymentState()
                    paymentMessage = null
                    saleCommitInProgress = false
                    saleCommitGuard.resetForNewPayment()
''',
    '''                onPayment = {
                    val draft = paymentDraftStore.load(cart.toList())
                    paymentState = draft.state
                    paymentMessage = if (draft.restored) {
                        "前回中断した支払入力を復元しました"
                    } else {
                        null
                    }
                    saleCommitInProgress = false
                    saleCommitGuard.resetForNewPayment()
''',
    "load payment draft",
)

replace_once(
    '''                onStateChange = {
                    paymentState = it
                    CustomerDisplayRuntime.publish(
''',
    '''                onStateChange = {
                    paymentState = it
                    paymentDraftStore.save(cart.toList(), it)
                    CustomerDisplayRuntime.publish(
''',
    "save payment draft",
)

replace_once(
    '''                onBack = {
                    saleCommitGuard.resetForNewPayment()
                    saleCommitInProgress = false
                    paymentMessage = null
''',
    '''                onBack = {
                    saleCommitGuard.resetForNewPayment()
                    saleCommitInProgress = false
                    paymentMessage = null
                    paymentDraftStore.clear()
''',
    "clear draft on payment back",
)

path.write_text(text, encoding="utf-8")
print("v0.14 payment draft UI integration applied")
