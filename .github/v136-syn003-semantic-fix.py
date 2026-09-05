from pathlib import Path


def replace_once(path: str, old: str, new: str):
    p = Path(path)
    text = p.read_text()
    if old not in text:
        raise SystemExit(f'missing SYN-003 semantic anchor: {path}: {old[:200]!r}')
    p.write_text(text.replace(old, new, 1))

frozen = 'app/src/main/java/jp/co/tenposinfo/register/Syn003FrozenPrintPayloadV136.kt'
replace_once(
    frozen,
    '''        settings: TaxInvoiceSettings,
    ): String {''',
    '''        settings: TaxInvoiceSettings,
        printerConfiguration: PrinterConfiguration,
        documentPrintSetting: DocumentPrintSettingV136,
    ): String {''',
)
replace_once(
    frozen,
    '''        val configuration = PrinterConfigurationRegistry.current() ?: PrinterConfiguration()
        val issuer = settings.issuer
        fun receipt(reprint: Boolean) = ReceiptData(
            storeName = issuer.storeName,
            storeAddress = issuer.address,
            storePhone = issuer.phone,
            registrationNumber = issuer.registrationNumber,
            saleId = saleId,
            createdAt = issuedAt,
            operatorName = operatorName,
            items = items,
            taxSummary = taxSummary,
            payments = payments,
            changeAmount = changeAmount,
            reprint = reprint,
            invoiceAggregationBasis = settings.invoiceAggregationBasis,
        )
        val normalBytes = EscPosEncoder.encode(receipt(false), configuration)
        val reprintBytes = EscPosEncoder.encode(receipt(true), configuration)''',
    '''        val configuration = printerConfiguration.copy(
            paperWidthMm = PrinterPaperSettingPolicy.normalizeWidthMm(printerConfiguration.paperWidthMm),
        )
        val issuer = settings.issuer
        fun receipt(reprint: Boolean): ReceiptData = DocumentPrintSettingsPolicyV136.applyToReceipt(
            ReceiptData(
                storeName = issuer.storeName,
                storeAddress = issuer.address,
                storePhone = issuer.phone,
                registrationNumber = issuer.registrationNumber,
                saleId = saleId,
                createdAt = issuedAt,
                operatorName = operatorName,
                items = items,
                taxSummary = taxSummary,
                payments = payments,
                changeAmount = changeAmount,
                reprint = reprint,
                invoiceAggregationBasis = settings.invoiceAggregationBasis,
            ),
            documentPrintSetting,
        )
        val normalBytes = EscPosEncoder.encode(receipt(false), configuration)
        val reprintBytes = EscPosEncoder.encode(receipt(true), configuration)''',
)
replace_once(
    frozen,
    '''            append("\\\"printerProfileSnapshot\\\":{")
            append("\\\"profile\\\":\\\"").append(configuration.profile.name).append("\\\",")
            append("\\\"paperWidthMm\\\":").append(configuration.paperWidthMm).append(',')
            append("\\\"cutMode\\\":\\\"").append(configuration.cutMode.name).append("\\\",")
            append("\\\"feedLines\\\":").append(configuration.feedLines)
            append("},")''',
    '''            append("\\\"printerProfileSnapshot\\\":{")
            append("\\\"profile\\\":\\\"").append(configuration.profile.name).append("\\\",")
            append("\\\"charsetName\\\":\\\"").append(escape(configuration.profile.charsetName)).append("\\\",")
            append("\\\"codeTable\\\":").append(configuration.profile.codeTable).append(',')
            append("\\\"kanjiCodeSystem\\\":")
                .append(configuration.profile.kanjiCodeSystem?.toString() ?: "null").append(',')
            append("\\\"paperWidthMm\\\":").append(configuration.paperWidthMm).append(',')
            append("\\\"printableDotWidth\\\":").append(configuration.printableDotWidth).append(',')
            append("\\\"cutMode\\\":\\\"").append(configuration.cutMode.name).append("\\\",")
            append("\\\"feedLines\\\":").append(configuration.feedLines)
            append("},")
            append("\\\"documentPrintSettingSnapshot\\\":{")
            append("\\\"copies\\\":").append(DocumentPrintSettingsPolicyV136.normalizeCopies(documentPrintSetting.copies)).append(',')
            append("\\\"header\\\":\\\"").append(escape(documentPrintSetting.header.trim())).append("\\\",")
            append("\\\"footer\\\":\\\"")
                .append(escape(ReceiptFooterMessagePolicyV136.migrateLegacy(documentPrintSetting.footer))).append("\\\"},")''',
)

snapshot = 'app/src/main/java/jp/co/tenposinfo/register/PrintDocumentSnapshotV136.kt'
replace_once(
    snapshot,
    '''        settings: TaxInvoiceSettings,
    ): String {''',
    '''        settings: TaxInvoiceSettings,
        printerConfiguration: PrinterConfiguration,
        documentPrintSetting: DocumentPrintSettingV136,
    ): String {''',
)
replace_once(
    snapshot,
    '''            settings = settings,
        )
        db.update(''',
    '''            settings = settings,
            printerConfiguration = printerConfiguration,
            documentPrintSetting = documentPrintSetting,
        )
        db.update(''',
)

register_db = 'app/src/main/java/jp/co/tenposinfo/register/RegisterDatabase.kt'
replace_once(
    register_db,
    '''        val printerConfiguration = PrinterPaperSettingPolicy.currentConfiguration(applicationContext)
        val paperWidthMm = PrinterPaperSettingPolicy.normalizeWidthMm(printerConfiguration.paperWidthMm)''',
    '''        val printerConfiguration = PrinterPaperSettingPolicy.currentConfiguration(applicationContext)
        val paperWidthMm = PrinterPaperSettingPolicy.normalizeWidthMm(printerConfiguration.paperWidthMm)
        val saleReceiptSetting = DocumentPrintSettingsStoreV136(applicationContext)
            .load(DocumentPrintKindV136.SALE_RECEIPT)''',
)
replace_once(
    register_db,
    '''                settings = taxSettings,
            )
            if (normalizedCommitKey != null) {''',
    '''                settings = taxSettings,
                printerConfiguration = printerConfiguration.copy(paperWidthMm = paperWidthMm),
                documentPrintSetting = saleReceiptSetting,
            )
            if (normalizedCommitKey != null) {''',
)

# Strengthen SYN-003 contract test for exact finalization-time settings, not global runtime registry.
test = 'app/src/test/java/jp/co/tenposinfo/register/V151Syn003FrozenPrintSnapshotTest.kt'
replace_once(
    test,
    '''        assertTrue(frozen.contains("PrinterConfigurationRegistry.current()"))
        assertTrue(frozen.contains("EscPosEncoder.encode(receipt(false), configuration)"))''',
    '''        assertFalse(frozen.contains("PrinterConfigurationRegistry.current()"))
        assertTrue(frozen.contains("printerConfiguration.copy("))
        assertTrue(frozen.contains("documentPrintSettingSnapshot"))
        assertTrue(frozen.contains("DocumentPrintSettingsPolicyV136.applyToReceipt"))
        assertTrue(frozen.contains("EscPosEncoder.encode(receipt(false), configuration)"))''',
)
replace_once(
    test,
    '''        assertTrue(snapshot.contains("ContentValues().apply { put(\\\"payload_json\\\", payload) }"))
        assertTrue(snapshot.contains("put(\\\"payload_json\\\", payload)"))''',
    '''        assertTrue(snapshot.contains("ContentValues().apply { put(\\\"payload_json\\\", payload) }"))
        assertTrue(snapshot.contains("put(\\\"payload_json\\\", payload)"))
        val database = source("RegisterDatabase.kt")
        assertTrue(database.contains("printerConfiguration = printerConfiguration.copy(paperWidthMm = paperWidthMm)"))
        assertTrue(database.contains("documentPrintSetting = saleReceiptSetting"))''',
)

print('SYN-003 semantic patch applied')
