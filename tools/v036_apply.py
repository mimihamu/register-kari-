from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    target = Path(path)
    text = target.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{path}: expected one occurrence, found {count}: {old[:100]!r}")
    target.write_text(text.replace(old, new, 1), encoding="utf-8")


old_build = '''object OutboxPayloadAssembler {
    fun build(db: SQLiteDatabase, record: JournalOutboxRecord): String = when (record.eventType) {
        JournalEventType.SALE.name -> salePayload(db, record)
        JournalEventType.REVERSAL.name -> reversalPayload(db, record)
        JournalEventType.SETTLEMENT.name -> settlementPayload(db, record)
        JournalEventType.MENU_REVISION.name -> menuRevisionPayload(db, record)
        else -> genericPayload(db, record)
    }
'''
new_build = '''object OutboxPayloadAssembler {
    fun build(db: SQLiteDatabase, record: JournalOutboxRecord): String {
        val legacyPayload = when (record.eventType) {
            JournalEventType.SALE.name -> salePayload(db, record)
            JournalEventType.REVERSAL.name -> reversalPayload(db, record)
            JournalEventType.SETTLEMENT.name -> settlementPayload(db, record)
            JournalEventType.MENU_REVISION.name -> menuRevisionPayload(db, record)
            else -> genericPayload(db, record)
        }
        return SalesJournalJsonContract.wrap(
            record = record,
            legacyPayload = legacyPayload,
            identity = SalesJournalIdentityStore.resolve(db),
        )
    }
'''
replace_once(
    "app/src/main/java/jp/co/tenposinfo/register/BusinessSyncFoundation.kt",
    old_build,
    new_build,
)

print("v0.36 production assembler connected")
