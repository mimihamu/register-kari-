from pathlib import Path


def replace_once(path: str, old: str, new: str):
    p = Path(path)
    text = p.read_text()
    if old not in text:
        raise SystemExit(f'missing fix anchor: {path}: {old[:140]!r}')
    p.write_text(text.replace(old, new, 1))

# Base patch inserted settlement freeze at the first reversal audit. Remove it there.
ops = Path('app/src/main/java/jp/co/tenposinfo/register/OperationsStore.kt')
text = ops.read_text()
wrong = '            OutboxDocumentV150.materializeLatest(this, JournalEventType.SETTLEMENT.name, id.toString())\n'
if wrong not in text:
    raise SystemExit('wrong OperationsStore insertion not found')
ops.write_text(text.replace(wrong, '', 1))

# OperationsStore: freeze every trigger-created business event before transaction commit.
replace_once(
    str(ops),
    '''            insertAudit(
                eventType = "BUSINESS_OPEN",
                referenceId = id,
                detail = "営業日 $businessDate / セッションNo.$id / 開始釣銭 ${openingCash}円",
                operatorName = operatorName,
                createdAt = now,
            )
            id''',
    '''            insertAudit(
                eventType = "BUSINESS_OPEN",
                referenceId = id,
                detail = "営業日 $businessDate / セッションNo.$id / 開始釣銭 ${openingCash}円",
                operatorName = operatorName,
                createdAt = now,
            )
            OutboxDocumentV150.materializeLatest(this, JournalEventType.BUSINESS_OPEN.name, id.toString())
            id''',
)
replace_once(
    str(ops),
    '''            insertAudit(
                eventType = "CASH_${type.name}",
                referenceId = id,
                detail = "${type.displayName} ${amount}円 / ${reason.trim()}",
                operatorName = operatorName,
                createdAt = now,
            )
            id''',
    '''            insertAudit(
                eventType = "CASH_${type.name}",
                referenceId = id,
                detail = "${type.displayName} ${amount}円 / ${reason.trim()}",
                operatorName = operatorName,
                createdAt = now,
            )
            OutboxDocumentV150.materializeLatest(this, JournalEventType.CASH_MOVEMENT.name, id.toString())
            id''',
)
replace_once(
    str(ops),
    '''            bindOperationKey(operationKey, reversalId)
            savedResult = PartialReversalResult(reversalId, refundTotal, printJobId, preview)''',
    '''            OutboxDocumentV150.materializeLatest(this, JournalEventType.REVERSAL.name, reversalId.toString())
            bindOperationKey(operationKey, reversalId)
            savedResult = PartialReversalResult(reversalId, refundTotal, printJobId, preview)''',
)
replace_once(
    str(ops),
    '''            if (operationKey != null) bindOperationKey(operationKey, id)
            id
        }
    }

    fun recentSettlements''',
    '''            OutboxDocumentV150.materializeLatest(this, JournalEventType.SETTLEMENT.name, id.toString())
            if (type == SettlementReportType.Z_SETTLEMENT) {
                OutboxDocumentV150.materializeLatest(this, JournalEventType.BUSINESS_STATE.name, session.id.toString())
            }
            if (operationKey != null) bindOperationKey(operationKey, id)
            id
        }
    }

    fun recentSettlements''',
)

# AdvancedOperationsStore has independent business write paths; freeze those as well.
adv = 'app/src/main/java/jp/co/tenposinfo/register/AdvancedOperationsStore.kt'
replace_once(
    adv,
    '''            insertAudit("BUSINESS_OPEN", id, "営業日 $dateText / セッションNo.$id / 開始釣銭 ${openingCash}円", operatorName, now)
            id''',
    '''            insertAudit("BUSINESS_OPEN", id, "営業日 $dateText / セッションNo.$id / 開始釣銭 ${openingCash}円", operatorName, now)
            OutboxDocumentV150.materializeLatest(this, JournalEventType.BUSINESS_OPEN.name, id.toString())
            id''',
)
replace_once(
    adv,
    '''            insertAudit("CASH_${type.name}", id, "${type.displayName} ${amount}円 / ${reason.trim()}", operatorName, now)
            id''',
    '''            insertAudit("CASH_${type.name}", id, "${type.displayName} ${amount}円 / ${reason.trim()}", operatorName, now)
            OutboxDocumentV150.materializeLatest(this, JournalEventType.CASH_MOVEMENT.name, id.toString())
            id''',
)
replace_once(
    adv,
    '''            insertAudit(type.name, id, "元売上 No.$originalSaleId / 返金 ${refundTotal}円 / ${reason.trim()}", operatorName, now)
            id
        }
        return ReversalSaveResult''',
    '''            insertAudit(type.name, id, "元売上 No.$originalSaleId / 返金 ${refundTotal}円 / ${reason.trim()}", operatorName, now)
            OutboxDocumentV150.materializeLatest(this, JournalEventType.REVERSAL.name, id.toString())
            id
        }
        return ReversalSaveResult''',
)
replace_once(
    adv,
    '''            if (type == SettlementReportType.Z_SETTLEMENT) {
                insertAudit("BUSINESS_CLOSE", session.id, "Z精算No.${id}により営業終了 / 現金実査 ${actual}円 / 過不足 ${variance}円", operatorName, now)
            }
            id
        }
        return SettlementSaveResult''',
    '''            if (type == SettlementReportType.Z_SETTLEMENT) {
                insertAudit("BUSINESS_CLOSE", session.id, "Z精算No.${id}により営業終了 / 現金実査 ${actual}円 / 過不足 ${variance}円", operatorName, now)
                OutboxDocumentV150.materializeLatest(this, JournalEventType.BUSINESS_STATE.name, session.id.toString())
            }
            id
        }
        return SettlementSaveResult''',
)

# Plus multipart must emit CRLF bytes, not literal backslash-r/backslash-n characters.
plus_drive = Path('management-app/src/main/java/jp/co/tenposinfo/register/plus/GoogleDriveDirectSync.kt')
text = plus_drive.read_text()
if r'\\r\\n' not in text:
    raise SystemExit('expected escaped multipart CRLF not found')
plus_drive.write_text(text.replace(r'\\r\\n', r'\r\n'))

# Strengthen the focused REGISTER source contract to cover every trigger-produced event path.
test_path = Path('app/src/test/java/jp/co/tenposinfo/register/V150Syn001002ImmutableOutboxDocumentTest.kt')
test = test_path.read_text()
needle = '''    @Test fun menuApplicationResultIsMaterializedForAllCommittedOutcomes() {'''
extra = '''    @Test fun allTriggerProducedBusinessEventsFreezeBeforeCommit() {
        val operations = source("OperationsStore.kt")
        val advanced = source("AdvancedOperationsStore.kt")
        listOf(
            "JournalEventType.BUSINESS_OPEN.name",
            "JournalEventType.CASH_MOVEMENT.name",
            "JournalEventType.REVERSAL.name",
            "JournalEventType.SETTLEMENT.name",
            "JournalEventType.BUSINESS_STATE.name",
        ).forEach { marker ->
            assertTrue("OperationsStore missing $marker", operations.contains("OutboxDocumentV150.materializeLatest(this, $marker"))
            assertTrue("AdvancedOperationsStore missing $marker", advanced.contains("OutboxDocumentV150.materializeLatest(this, $marker"))
        }
        assertTrue(source("DynamicCatalogRuntime.kt").contains("JournalEventType.MENU_REVISION.name"))
    }

'''
if needle not in test:
    raise SystemExit('REGISTER test insertion anchor missing')
test_path.write_text(test.replace(needle, extra + needle, 1))

# Strengthen Plus test against malformed multipart line endings.
plus_test_path = Path('management-app/src/test/java/jp/co/tenposinfo/register/plus/V150Syn001002AckOutboxTest.kt')
plus_test = plus_test_path.read_text()
needle = '''    @Test fun menuApplyResultIsAcceptedWithoutBlockingDriveImport() {'''
extra = r'''    @Test fun ackMultipartUsesHttpCrLfEscapes() {
        val drive = source("GoogleDriveDirectSync.kt")
        assertTrue(drive.contains("--$boundary\\r\\nContent-Type"))
        assertTrue(!drive.contains("--$boundary\\\\r\\\\nContent-Type"))
    }

'''
if needle not in plus_test:
    raise SystemExit('Plus test insertion anchor missing')
plus_test_path.write_text(plus_test.replace(needle, extra + needle, 1))

# Documentation: explicitly list the full atomic generation coverage.
doc = Path('docs/V1.36_SYN_001_002_IMMUTABLE_OUTBOX_DOCUMENT.md')
d = doc.read_text()
anchor = '- `outbox_document` に documentId / documentType / sourceBusinessId / schemaVersion / canonicalPayloadBytes / sha256 / producerId / sequenceNo / completionMode / status を永続化する。\n'
if anchor not in d:
    raise SystemExit('doc anchor missing')
d = d.replace(anchor, anchor + '- 原子生成対象は SALE / REVERSAL / SETTLEMENT / CASH_MOVEMENT / BUSINESS_OPEN / BUSINESS_STATE / MENU_REVISION / MENU_APPLY_RESULT。通常OperationsStoreとAdvancedOperationsStoreの両経路を同一契約にする。\n', 1)
doc.write_text(d)

print('SYN-001/002 corrections applied')
