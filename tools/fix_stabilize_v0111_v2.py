#!/usr/bin/env python3
from pathlib import Path

path = Path(__file__).with_name("stabilize_v0111.py")
text = path.read_text(encoding="utf-8")


def replace(old: str, new: str, label: str) -> None:
    global text
    if old not in text:
        raise RuntimeError(f"fix target not found: {label}")
    text = text.replace(old, new, 1)


replace(
    """data class BusinessSessionWindow(
    val id: Long,
    val businessDate: String,
    val openedAt: Long,
    val closedAt: Long?,
)""",
    """data class BusinessSessionWindow(
    val id: Long,
    val businessDate: String,
    val openedAt: Long,
    val closedAt: Long?,
    val openingCash: Long = 0L,
)""",
    "business session opening cash",
)

replace(
    '"SELECT id, business_date, opened_at, closed_at FROM business_sessions WHERE business_date=? ORDER BY opened_at DESC LIMIT 1"',
    '"SELECT id, business_date, opened_at, closed_at, opening_cash FROM business_sessions WHERE business_date=? ORDER BY opened_at DESC LIMIT 1"',
    "business session query",
)

replace(
    """                openedAt = cursor.getLong(2),
                closedAt = if (cursor.isNull(3)) null else cursor.getLong(3),
            )""",
    """                openedAt = cursor.getLong(2),
                closedAt = if (cursor.isNull(3)) null else cursor.getLong(3),
                openingCash = cursor.getLong(4),
            )""",
    "business session mapping",
)

replace(
    """        val session = BusinessSessionSchema.sessionForDate(db, date)
            ?: activeSession()?.takeIf { it.businessDate == date.toString() }
            ?: error(\"営業日 ${date} の営業セッションが見つかりません\")""",
    """        val session = BusinessSessionSchema.sessionForDate(db, date)
            ?: activeSession()?.takeIf { it.businessDate == date.toString() }?.let {
                BusinessSessionWindow(it.id, it.businessDate, it.openedAt, it.closedAt, it.openingCash)
            }
            ?: error(\"営業日 ${date} の営業セッションが見つかりません\")""",
    "daily summary session type",
)

replace(
    """        val candidates = db.runInTransactionWithResult {
            val selected = rawQuery(""",
    """        val candidates = db.run {
            beginTransaction()
            try {
                val selected = rawQuery(""",
    "outbox transaction start",
)

replace(
    """            selected.mapNotNull { record ->
                val changed = update(""",
    """                val claimed = selected.mapNotNull { record ->
                    val changed = update(""",
    "outbox claimed records",
)

replace(
    """                if (changed == 1) record.copy(status = SyncOutboxStatus.PROCESSING, attemptCount = record.attemptCount + 1) else null
            }
        }
        var completed = 0""",
    """                    if (changed == 1) record.copy(status = SyncOutboxStatus.PROCESSING, attemptCount = record.attemptCount + 1) else null
                }
                setTransactionSuccessful()
                claimed
            } finally {
                endTransaction()
            }
        }
        var completed = 0""",
    "outbox transaction finish",
)

replace(
    """    val taxCategory: TaxCategory,
    val taxKey: String,
    val taxLabel: String,
    val taxRatePercent: Int,
    val taxIncluded: Boolean,
    val taxable: Boolean,
    val reduced: Boolean,
    val taxSymbol: String,
    val originalQuantity: Int,""",
    """    val taxCategory: TaxCategory,
    val taxKey: String = taxCategory.name,
    val taxLabel: String = taxCategory.displayName,
    val taxRatePercent: Int = taxCategory.ratePercent,
    val taxIncluded: Boolean = taxCategory.taxIncluded,
    val taxable: Boolean = taxCategory.taxable,
    val reduced: Boolean = taxCategory.symbol.contains(\"※\"),
    val taxSymbol: String = taxCategory.symbol,
    val originalQuantity: Int,""",
    "return-line compatibility defaults",
)

replace(
    """            '    val permissions: Set<RegisterPermission>,\\n    val revision: Long,\\n) {',""",
    """            '    val permissions: Set<RegisterPermission>,\\n    val revision: Long = 0L,\\n) {',""",
    "operator revision compatibility default",
)

replace(
    '''    workflow = ROOT / ".github/workflows/build-apk.yml"
    def workflow_patch(text: str) -> str:
        text = text.replace('name: Build REGISTER APK', 'name: Build つぐレジ APK')
        text = text.replace('REGISTER_v0.11_debug.apk', 'TSUGUREGI_v0.11.1_debug.apk')
        text = text.replace('REGISTER-v0.11-debug-apk', 'TSUGUREGI-v0.11.1-debug-apk')
        return text
    patch(workflow, workflow_patch)
''',
    '',
    "defer permanent workflow update to connector",
)

replace(
    '''    transient_workflow = ROOT / ".github/workflows/stabilize-v0111.yml"
    if transient_workflow.exists():
        transient_workflow.unlink()
''',
    '',
    "keep transient workflow until connector cleanup",
)

path.write_text(text, encoding="utf-8")
Path(__file__).with_name("fix_stabilize_v0111.py").unlink(missing_ok=True)
Path(__file__).unlink()
print("v0.11.1 stabilization fixer v2 applied")
