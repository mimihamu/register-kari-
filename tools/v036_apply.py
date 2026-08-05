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

workflow = ".github/workflows/build-apk.yml"
replace_once(workflow, "Verify cumulative v0.14-v0.35 sources", "Verify cumulative v0.14-v0.36 sources")
replace_once(workflow, "grep -q 'versionCode = 65' app/build.gradle.kts", "grep -q 'versionCode = 66' app/build.gradle.kts")
replace_once(workflow, "grep -q 'versionName = \"0.35.0-dev.1\"' app/build.gradle.kts", "grep -q 'versionName = \"0.36.0-dev.1\"' app/build.gradle.kts")
replace_once(
    workflow,
    "            app/src/main/java/jp/co/tenposinfo/register/BusinessSyncFoundation.kt \\\n",
    "            app/src/main/java/jp/co/tenposinfo/register/BusinessSyncFoundation.kt \\\n            app/src/main/java/jp/co/tenposinfo/register/SalesJournalJsonContract.kt \\\n",
)
replace_once(
    workflow,
    "            app/src/test/java/jp/co/tenposinfo/register/V035OutboxExternalDeliveryTest.kt \\\n",
    "            app/src/test/java/jp/co/tenposinfo/register/V036SalesJournalJsonContractTest.kt \\\n            app/src/test/java/jp/co/tenposinfo/register/V035OutboxExternalDeliveryTest.kt \\\n",
)
replace_once(
    workflow,
    "            docs/V0.35_OUTBOX_EXTERNAL_DELIVERY.md \\\n",
    "            docs/V0.36_SALES_JOURNAL_JSON_CONTRACT.md \\\n            docs/V0.36_RELEASE_NOTES.md \\\n            docs/schemas/tsuguregi-sales-journal-v1.schema.json \\\n            docs/V0.35_OUTBOX_EXTERNAL_DELIVERY.md \\\n",
)
replace_once(
    workflow,
    "          delivery = (root / 'OutboxExternalDelivery.kt').read_text()\n",
    "          contract = (root / 'SalesJournalJsonContract.kt').read_text()\n          delivery = (root / 'OutboxExternalDelivery.kt').read_text()\n",
)
replace_once(
    workflow,
    "          # v0.35 verified external Outbox delivery.\n",
    """          # v0.36 fixed sales-journal JSON contract for future つぐレジ＋ import.
          for token in (
              'SCHEMA_VERSION = 1',
              'MINIMUM_READER_VERSION = 1',
              'DUPLICATE_KEY_VERSION = 1',
              'duplicateImportKey',
              'storeId',
              'terminalId',
              'Z_SETTLEMENT',
              'register.sale.v2',
              'register.reversal.v2',
              'register.settlement.v1',
          ):
              assert token in contract, token
          assert 'SalesJournalJsonContract.wrap' in foundation
          assert 'SalesJournalIdentityStore.resolve(db)' in foundation
          schema = Path('docs/schemas/tsuguregi-sales-journal-v1.schema.json').read_text()
          assert 'jp.co.tenposinfo.tsuguregi.sales-journal' in schema
          assert '\"schemaVersion\"' in schema
          assert '\"duplicateImportKey\"' in schema
          assert '\"taxTotals\"' in schema

          # v0.35 verified external Outbox delivery.
""",
)
replace_once(
    workflow,
    "          assert not list(Path('tools').glob('v035*'))\n",
    "          assert not list(Path('tools').glob('v036*'))\n          assert not Path('.github/workflows/v036-apply.yml').exists()\n          assert not list(Path('tools').glob('v035*'))\n",
)
replace_once(
    workflow,
    "          cp app/build/outputs/apk/debug/app-debug.apk artifacts/TSUGUREGI_v0.35.0_dev1_outbox_external_delivery_debug.apk\n",
    "          cp app/build/outputs/apk/debug/app-debug.apk artifacts/TSUGUREGI_v0.36.0_dev1_sales_journal_contract_debug.apk\n",
)
replace_once(
    workflow,
    "          sha256sum artifacts/TSUGUREGI_v0.35.0_dev1_outbox_external_delivery_debug.apk | tee artifacts/TSUGUREGI_v0.35.0_dev1_outbox_external_delivery_debug.apk.sha256\n",
    "          sha256sum artifacts/TSUGUREGI_v0.36.0_dev1_sales_journal_contract_debug.apk | tee artifacts/TSUGUREGI_v0.36.0_dev1_sales_journal_contract_debug.apk.sha256\n",
)
replace_once(
    workflow,
    "          stat --printf='REGISTER_APK_SIZE_BYTES=%s\\n' artifacts/TSUGUREGI_v0.35.0_dev1_outbox_external_delivery_debug.apk | tee artifacts/build-summary.txt\n",
    "          stat --printf='REGISTER_APK_SIZE_BYTES=%s\\n' artifacts/TSUGUREGI_v0.36.0_dev1_sales_journal_contract_debug.apk | tee artifacts/build-summary.txt\n",
)
replace_once(workflow, "          REGISTER_VERSION_NAME=0.35.0-dev.1\n", "          REGISTER_VERSION_NAME=0.36.0-dev.1\n")
replace_once(workflow, "          REGISTER_VERSION_CODE=65\n", "          REGISTER_VERSION_CODE=66\n")
replace_once(
    workflow,
    "          OUTBOX_EXTERNAL_DELIVERY=android-document-tree\n",
    """          SALES_JOURNAL_SCHEMA=jp.co.tenposinfo.tsuguregi.sales-journal
          SALES_JOURNAL_SCHEMA_VERSION=1
          SALES_JOURNAL_DUPLICATE_KEY=sha256-store-terminal-businessdate-event-eventid
          SALES_JOURNAL_LEGACY_PAYLOAD_COMPATIBLE=true
          SALES_JOURNAL_IMPORTER_TARGET=tsuguregi-plus
          OUTBOX_EXTERNAL_DELIVERY=android-document-tree
""",
)
replace_once(
    workflow,
    "          name: TSUGUREGI-v0.35.0-dev1-outbox-external-delivery-apks\n",
    "          name: TSUGUREGI-v0.36.0-dev1-sales-journal-contract-apks\n",
)
replace_once(
    workflow,
    "            artifacts/TSUGUREGI_v0.35.0_dev1_outbox_external_delivery_debug.apk\n            artifacts/TSUGUREGI_v0.35.0_dev1_outbox_external_delivery_debug.apk.sha256\n",
    "            artifacts/TSUGUREGI_v0.36.0_dev1_sales_journal_contract_debug.apk\n            artifacts/TSUGUREGI_v0.36.0_dev1_sales_journal_contract_debug.apk.sha256\n",
)

print("v0.36 source and CI changes applied")
