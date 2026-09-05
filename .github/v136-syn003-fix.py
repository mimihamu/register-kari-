from pathlib import Path


def replace_once(path: str, old: str, new: str):
    p = Path(path)
    text = p.read_text()
    if old not in text:
        raise SystemExit(f'missing SYN-003 anchor: {path}: {old[:180]!r}')
    p.write_text(text.replace(old, new, 1))

snapshot = 'app/src/main/java/jp/co/tenposinfo/register/PrintDocumentSnapshotV136.kt'
replace_once(
    snapshot,
    '''        val payload = enrichSalePayload(
            basePayloadJson = basePayload,
            saleId = saleId,
            businessDate = businessDate,
            issuedAt = issuedAt,
            operatorName = operatorName,
            items = items,
            taxSummary = taxSummary,
            payments = payments,
            changeAmount = changeAmount,
            settings = settings,
        )
        db.update(''',
    '''        val structuredPayload = enrichSalePayload(
            basePayloadJson = basePayload,
            saleId = saleId,
            businessDate = businessDate,
            issuedAt = issuedAt,
            operatorName = operatorName,
            items = items,
            taxSummary = taxSummary,
            payments = payments,
            changeAmount = changeAmount,
            settings = settings,
        )
        val payload = Syn003FrozenPrintPayloadV136.freezeSalePayload(
            payloadJson = structuredPayload,
            saleId = saleId,
            issuedAt = issuedAt,
            operatorName = operatorName,
            items = items,
            taxSummary = taxSummary,
            payments = payments,
            changeAmount = changeAmount,
            settings = settings,
        )
        db.update(''',
)

old_ref = '''payload_json = '{"schema":"${PrintDocumentSnapshotV136.SALE_JOB_REFERENCE_SCHEMA}","schemaVersion":${PrintDocumentSnapshotV136.SCHEMA_VERSION},"saleId":' || sale_id || ',"paperWidthMm":' || paper_width_mm || '}' '''.rstrip()
new_ref = '''payload_json = COALESCE(
                       (SELECT j.payload_json
                          FROM sales_journal j
                         WHERE j.event_type = '${JournalEventType.SALE.name}'
                           AND j.aggregate_id = CAST(print_jobs.sale_id AS TEXT)
                           AND instr(j.payload_json, '"syn003FrozenPrint"') > 0
                         ORDER BY j.created_at DESC
                         LIMIT 1),
                       '{"schema":"${PrintDocumentSnapshotV136.SALE_JOB_REFERENCE_SCHEMA}","schemaVersion":${PrintDocumentSnapshotV136.SCHEMA_VERSION},"saleId":' || sale_id || ',"paperWidthMm":' || paper_width_mm || '}'
                   )'''
replace_once(snapshot, old_ref, new_ref)

old_trigger_ref = '''payload_json = '{"schema":"${PrintDocumentSnapshotV136.SALE_JOB_REFERENCE_SCHEMA}","schemaVersion":${PrintDocumentSnapshotV136.SCHEMA_VERSION},"saleId":' || NEW.sale_id || ',"paperWidthMm":' || NEW.paper_width_mm || '}' '''.rstrip()
new_trigger_ref = '''payload_json = COALESCE(
                           (SELECT j.payload_json
                              FROM sales_journal j
                             WHERE j.event_type = '${JournalEventType.SALE.name}'
                               AND j.aggregate_id = CAST(NEW.sale_id AS TEXT)
                               AND instr(j.payload_json, '"syn003FrozenPrint"') > 0
                             ORDER BY j.created_at DESC
                             LIMIT 1),
                           '{"schema":"${PrintDocumentSnapshotV136.SALE_JOB_REFERENCE_SCHEMA}","schemaVersion":${PrintDocumentSnapshotV136.SCHEMA_VERSION},"saleId":' || NEW.sale_id || ',"paperWidthMm":' || NEW.paper_width_mm || '}'
                       )'''
replace_once(snapshot, old_trigger_ref, new_trigger_ref)

receipt = 'app/src/main/java/jp/co/tenposinfo/register/Receipt.kt'
replace_once(
    receipt,
    '''        val receipt = ReceiptFactory.fromSale(
            detail,
            reprint = ReceiptReprintPolicyV136.isReprint(
                jobCreatedAt = job.createdAt,
                saleCreatedAt = detail.summary.createdAt,
                completedPrintCount = detail.summary.printCount,
            ),
        )
        val configuredSnapshot = (PrinterConfigurationRegistry.current() ?: PrinterConfiguration()).copy(
            paperWidthMm = job.paperWidthMm,
        )
        val configuredReceipt = DocumentPrintSettingsPolicyV136.applyToReceipt(receipt, saleReceiptSetting)
        val renderedPayload = EscPosEncoder.encode(configuredReceipt, configuredSnapshot)''',
    '''        val isReprint = ReceiptReprintPolicyV136.isReprint(
            jobCreatedAt = job.createdAt,
            saleCreatedAt = detail.summary.createdAt,
            completedPrintCount = detail.summary.printCount,
        )
        val renderedPayload = Syn003FrozenPrintPayloadV136.loadJobPayload(
            db = database.readableDatabase,
            jobId = job.id,
            saleId = job.saleId,
            reprint = isReprint,
        ) ?: run {
            // Legacy rows created before SYN-003 keep the historical rendering fallback.
            val receipt = ReceiptFactory.fromSale(detail, reprint = isReprint)
            val configuredSnapshot = (PrinterConfigurationRegistry.current() ?: PrinterConfiguration()).copy(
                paperWidthMm = job.paperWidthMm,
            )
            val configuredReceipt = DocumentPrintSettingsPolicyV136.applyToReceipt(receipt, saleReceiptSetting)
            EscPosEncoder.encode(configuredReceipt, configuredSnapshot)
        }''',
)

print('SYN-003 patch applied')
