from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    file = Path(path)
    text = file.read_text()
    if old not in text:
        raise SystemExit(f"pattern not found: {path}: {old!r}")
    file.write_text(text.replace(old, new, 1))


admin = "app/src/main/java/jp/co/tenposinfo/register/AdminSettingsActivity.kt"
anchor = '                    Spacer(Modifier.height(8.dp))\n                    Text("用紙幅・カット", fontWeight = FontWeight.Bold, color = AsNavy)'
replace_once(
    admin,
    anchor,
    '                    Spacer(Modifier.height(8.dp))\n'
    '                    DocumentPrintSettingsPanelV136(receiptAutoPrintEnabled = receiptAutoPrint)\n'
    '                    Spacer(Modifier.height(8.dp))\n'
    '                    Text("用紙幅・カット", fontWeight = FontWeight.Bold, color = AsNavy)',
)

advanced = "app/src/main/java/jp/co/tenposinfo/register/AdvancedOperationsStore.kt"
file = Path(advanced)
text = file.read_text()
record_start = text.index("    fun recordSettlement(\n")
width_line = "        val paperWidthMm = PrinterPaperSettingPolicy.currentWidthMm(appContext)\n"
width_at = text.index(width_line, record_start) + len(width_line)
text = text[:width_at] + """        val documentPrintKind = when (type) {
            SettlementReportType.X_INSPECTION -> DocumentPrintKindV136.INSPECTION
            SettlementReportType.Z_SETTLEMENT -> DocumentPrintKindV136.SETTLEMENT
        }
        val documentPrintSetting = DocumentPrintSettingsStoreV136(appContext).load(documentPrintKind)
""" + text[width_at:]
old_job = "            printJobId = insertDocumentJob(OperationDocumentType.SETTLEMENT_REPORT, id, paperWidthMm, previewText, now)\n"
if old_job not in text[record_start:]:
    raise SystemExit("settlement print job line not found")
text = text.replace(
    old_job,
    """            if (documentPrintSetting.autoPrintEnabled) {
                printJobId = insertDocumentJob(
                    OperationDocumentType.SETTLEMENT_REPORT,
                    id,
                    paperWidthMm,
                    previewText,
                    now,
                    documentPrintKind,
                )
            }
""",
    1,
)
function_start = text.index("    private fun SQLiteDatabase.insertDocumentJob(")
function_end = text.index("\n\n    private fun android.database.Cursor.toDocumentPrintJob()", function_start)
function = """    private fun SQLiteDatabase.insertDocumentJob(
        type: OperationDocumentType,
        referenceId: Long,
        paperWidthMm: Int,
        payloadText: String,
        now: Long,
        settingsKind: DocumentPrintKindV136? = DocumentPrintSettingsPolicyV136.kindFor(type),
    ): Long {
        val setting = settingsKind?.let { DocumentPrintSettingsStoreV136(appContext).load(it) }
        val copies = setting?.copies?.let(DocumentPrintSettingsPolicyV136::normalizeCopies) ?: 1
        val decoratedPayload = setting?.let { DocumentPrintSettingsPolicyV136.decorateText(payloadText, it) } ?: payloadText
        var firstJobId = 0L
        repeat(copies) { copyIndex ->
            val jobId = insertOrThrow(
                "document_print_jobs",
                null,
                ContentValues().apply {
                    put("document_type", type.name)
                    put("reference_id", referenceId)
                    put("paper_width_mm", if (paperWidthMm >= 80) 80 else 58)
                    put("status", PrintJobStatus.PENDING.name)
                    put("attempt_count", 0)
                    putNull("last_error")
                    put("payload_text", decoratedPayload)
                    put("created_at", now + copyIndex)
                    put("updated_at", now + copyIndex)
                },
            )
            if (copyIndex == 0) firstJobId = jobId
        }
        return firstJobId
    }"""
file.write_text(text[:function_start] + function + text[function_end:])

receipt = "app/src/main/java/jp/co/tenposinfo/register/Receipt.kt"
replace_once(
    receipt,
    "    val invoiceAggregationBasis: InvoiceAggregationBasisV136 = InvoiceAggregationBasisV136.TAX_INCLUDED,\n)",
    "    val invoiceAggregationBasis: InvoiceAggregationBasisV136 = InvoiceAggregationBasisV136.TAX_INCLUDED,\n"
    '    val documentCopies: Int = 1,\n    val documentHeader: String = "",\n    val documentFooter: String = "",\n)',
)
replace_once(
    receipt,
    "        val lines = mutableListOf<String>()\n        lines += center(data.storeName, width)",
    "        val lines = mutableListOf<String>()\n"
    "        data.documentHeader.lineSequence().map { it.trim() }.filter { it.isNotBlank() }.forEach { lines += fit(it, width) }\n"
    "        lines += center(data.storeName, width)",
)
replace_once(
    receipt,
    '        lines += center("ありがとうございました", width)\n        return lines.joinToString("\\n")',
    '        lines += center("ありがとうございました", width)\n'
    '        data.documentFooter.lineSequence().map { it.trim() }.filter { it.isNotBlank() }.forEach { lines += fit(it, width) }\n'
    '        return lines.joinToString("\\n")',
)
file = Path(receipt)
text = file.read_text()
encoder_start = text.index("        return PrinterCommandEncoder.encodeText(", text.index("object EscPosEncoder"))
encoder_end = text.index("\n    }\n}", encoder_start)
new_encoder = """        val copies = DocumentPrintSettingsPolicyV136.normalizeCopies(data.documentCopies)
        return (0 until copies).fold(ByteArray(0)) { payload, copyIndex ->
            payload + PrinterCommandEncoder.encodeText(
                text = ReceiptRenderer.render(data, PrinterPaperSettingPolicy.paper(configuration)),
                configuration = configuration,
                openDrawer = openDrawer && copyIndex == 0,
                appendCut = true,
            )
        }"""
text = text[:encoder_start] + new_encoder + text[encoder_end:]
old_processor = "class PrintQueueProcessor(\n    private val database: RegisterDatabase,\n    private val gateway: PrinterGateway,\n) {"
new_processor = "class PrintQueueProcessor(\n    private val database: RegisterDatabase,\n    private val gateway: PrinterGateway,\n    private val saleReceiptSetting: DocumentPrintSettingV136 = DocumentPrintSettingV136(),\n) {"
if old_processor not in text:
    raise SystemExit("PrintQueueProcessor constructor not found")
text = text.replace(old_processor, new_processor, 1)
old_send = "        val result = gateway.send(EscPosEncoder.encode(receipt, configuredSnapshot))"
new_send = "        val configuredReceipt = DocumentPrintSettingsPolicyV136.applyToReceipt(receipt, saleReceiptSetting)\n        val result = gateway.send(EscPosEncoder.encode(configuredReceipt, configuredSnapshot))"
if old_send not in text:
    raise SystemExit("receipt queue send not found")
file.write_text(text.replace(old_send, new_send, 1))

worker = "app/src/main/java/jp/co/tenposinfo/register/AutomaticPrintWorker.kt"
replace_once(
    worker,
    "        val database = RegisterDatabase(applicationContext)\n        val operations = AdvancedOperationsStore(applicationContext)\n        try {",
    "        val database = RegisterDatabase(applicationContext)\n        val operations = AdvancedOperationsStore(applicationContext)\n"
    "        val saleReceiptSetting = DocumentPrintSettingsStoreV136(applicationContext).load(DocumentPrintKindV136.SALE_RECEIPT)\n        try {",
)
replace_once(
    worker,
    "                                PrintQueueProcessor(database, gateway).processNext()",
    "                                PrintQueueProcessor(database, gateway, saleReceiptSetting).processNext()",
)

unified = "app/src/main/java/jp/co/tenposinfo/register/UnifiedPrintQueue.kt"
replace_once(
    unified,
    "    private val monitoringStore = PrinterMonitoringStore(applicationContext)\n",
    "    private val monitoringStore = PrinterMonitoringStore(applicationContext)\n"
    "    private val documentPrintSettingsStore = DocumentPrintSettingsStoreV136(applicationContext)\n",
)
replace_once(
    unified,
    "                ReceiptRenderer.render(receipt, ReceiptPaper.fromWidth(job.paperWidthMm))",
    "                ReceiptRenderer.render(\n"
    "                    DocumentPrintSettingsPolicyV136.applyToReceipt(\n"
    "                        receipt,\n"
    "                        documentPrintSettingsStore.load(DocumentPrintKindV136.SALE_RECEIPT),\n"
    "                    ),\n"
    "                    ReceiptPaper.fromWidth(job.paperWidthMm),\n"
    "                )",
)
replace_once(
    unified,
    "            data = receipt,\n            configuration = configuration.copy(paperWidthMm = claimed.paperWidthMm),",
    "            data = DocumentPrintSettingsPolicyV136.applyToReceipt(\n"
    "                receipt,\n"
    "                documentPrintSettingsStore.load(DocumentPrintKindV136.SALE_RECEIPT),\n"
    "            ),\n"
    "            configuration = configuration.copy(paperWidthMm = claimed.paperWidthMm),",
)
