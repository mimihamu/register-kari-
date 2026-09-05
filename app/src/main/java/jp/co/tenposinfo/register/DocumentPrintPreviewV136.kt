package jp.co.tenposinfo.register

/**
 * UI-only preview state for master checklist #32 (PRN-PREVIEW-002).
 *
 * The formal v2.5 specification does not define PRN-PREVIEW-002 as an independent
 * requirement ID. This state is therefore intentionally derived from the formal
 * SCR-640 preview requirement and ReceiptLayoutConfig.copies / existing document
 * print settings instead of inventing a second print contract.
 */
data class DocumentPrintPreviewStateV136(
    val effectiveCopies: Int,
    val printDisabled: Boolean,
    val autoPrintEnabled: Boolean,
    val validationError: String?,
) {
    val canRender: Boolean get() = validationError == null
}

object DocumentPrintPreviewPolicyV136 {
    fun evaluate(
        kind: DocumentPrintKindV136,
        setting: DocumentPrintSettingV136,
    ): DocumentPrintPreviewStateV136 {
        val effectiveCopies = DocumentPrintSettingsPolicyV136.normalizeCopies(kind, setting.copies)
        val validationError = if (kind == DocumentPrintKindV136.SALE_RECEIPT) {
            runCatching { ReceiptFooterMessagePolicyV136.normalizeForSave(setting.footer) }
                .exceptionOrNull()
                ?.message
        } else {
            null
        }
        return DocumentPrintPreviewStateV136(
            effectiveCopies = effectiveCopies,
            printDisabled = effectiveCopies == 0,
            autoPrintEnabled = setting.autoPrintEnabled,
            validationError = validationError,
        )
    }

    fun statusLines(
        state: DocumentPrintPreviewStateV136,
        paper: ReceiptPaper,
    ): List<String> {
        val lines = mutableListOf<String>()
        val copyStatus = if (state.printDisabled) {
            "プレビュー: 印刷無効（電子保存のみ）"
        } else {
            "プレビュー: 印刷部数 ${state.effectiveCopies}部"
        }
        lines += ReceiptLineWrapV136.wrap(copyStatus, paper.charsPerLine)
        if (!state.autoPrintEnabled) {
            lines += ReceiptLineWrapV136.wrap("自動印刷OFF（手動印刷可）", paper.charsPerLine)
        }
        return lines
    }
}

/**
 * Formal v2.5 SCR-640 / §16.2 print preview.
 *
 * Sale-receipt preview deliberately uses the production ReceiptRenderer so that
 * the screen does not maintain a second receipt-layout implementation. Other
 * operation documents use the same 58/80 logical-width policy and the existing
 * four-document printer-test samples until their dedicated renderers are selected.
 */
object DocumentPrintPreviewV136 {
    private const val PREVIEW_CREATED_AT = 1_767_225_600_000L // 2026-01-01T00:00:00Z; deterministic preview

    fun render(
        kind: DocumentPrintKindV136,
        setting: DocumentPrintSettingV136,
        paper: ReceiptPaper,
    ): String {
        val state = DocumentPrintPreviewPolicyV136.evaluate(kind, setting)
        require(state.canRender) {
            "${state.validationError ?: "印刷設定が不正です"}。設定項目を修正してください"
        }
        val document = when (kind) {
            DocumentPrintKindV136.SALE_RECEIPT -> renderSaleReceipt(setting, paper)
            DocumentPrintKindV136.RECEIPT_VOUCHER -> renderOperationDocument(
                kind,
                PrinterPaperTestDocumentV136.RECEIPT_VOUCHER,
                setting,
                paper,
            )
            DocumentPrintKindV136.PROVISIONAL_RECEIPT -> renderOperationDocument(
                kind,
                PrinterPaperTestDocumentV136.PROVISIONAL_RECEIPT,
                setting,
                paper,
            )
            DocumentPrintKindV136.SETTLEMENT -> renderOperationDocument(
                kind,
                PrinterPaperTestDocumentV136.SETTLEMENT,
                setting,
                paper,
            )
            DocumentPrintKindV136.INSPECTION -> renderInspection(setting, paper)
        }
        return (DocumentPrintPreviewPolicyV136.statusLines(state, paper) + document.lines())
            .joinToString("\n")
    }

    fun previewDotWidth(paper: ReceiptPaper): Int =
        PrinterProfileContractV136.standardPrintableDotWidth(paper.widthMm)

    private fun renderSaleReceipt(
        setting: DocumentPrintSettingV136,
        paper: ReceiptPaper,
    ): String {
        val items = listOf(
            CartItem(
                product = Product(
                    id = "PREVIEW-10",
                    name = "通常商品サンプル",
                    unitPrice = 1_100L,
                    taxCategory = TaxCategory.INCLUDED_10,
                    displayOrder = 1,
                ),
                quantity = 1,
            ),
            CartItem(
                product = Product(
                    id = "PREVIEW-08",
                    name = "軽減税率商品サンプル",
                    unitPrice = 1_080L,
                    taxCategory = TaxCategory.INCLUDED_8,
                    displayOrder = 2,
                ),
                quantity = 1,
            ),
        )
        val normalizedFooter = ReceiptFooterMessagePolicyV136.normalizeForSave(setting.footer)
        val data = ReceiptData(
            storeName = "つぐレジ プレビュー店",
            storeAddress = "埼玉県越谷市サンプル1-2-3",
            storePhone = "048-000-0000",
            registrationNumber = "T1234567890123",
            saleId = 123L,
            createdAt = PREVIEW_CREATED_AT,
            operatorName = "担当者",
            items = items,
            taxSummary = TaxEngine.calculate(items),
            payments = emptyList(),
            changeAmount = 0L,
            documentCopies = DocumentPrintSettingsPolicyV136.normalizeCopies(setting.copies),
            documentHeader = setting.header,
            documentFooter = normalizedFooter,
        )
        return ReceiptRenderer.render(data, paper)
    }

    private fun renderOperationDocument(
        kind: DocumentPrintKindV136,
        sample: PrinterPaperTestDocumentV136,
        setting: DocumentPrintSettingV136,
        paper: ReceiptPaper,
    ): String {
        val body = PrinterPaperWidthTestV136.buildDocument(sample, paper).trimEnd()
        return decorateAndWrap(kind, body, setting, paper)
    }

    private fun renderInspection(
        setting: DocumentPrintSettingV136,
        paper: ReceiptPaper,
    ): String {
        val separator = "-".repeat(paper.charsPerLine)
        val body = buildString {
            append("[点検票] ${paper.widthMm}mm / ${paper.charsPerLine}桁\n")
            append(separator).append('\n')
            append("点検売上  ¥12,345\n")
            append("現金       ¥6,789\n")
            append("点検は締め処理を行いません\n")
            append(separator)
        }
        return decorateAndWrap(DocumentPrintKindV136.INSPECTION, body, setting, paper)
    }

    private fun decorateAndWrap(
        kind: DocumentPrintKindV136,
        body: String,
        setting: DocumentPrintSettingV136,
        paper: ReceiptPaper,
    ): String {
        val normalized = DocumentPrintSettingV136(
            autoPrintEnabled = setting.autoPrintEnabled,
            copies = DocumentPrintSettingsPolicyV136.normalizeCopies(kind, setting.copies),
            header = setting.header.trim(),
            footer = setting.footer.trim(),
        )
        return DocumentPrintSettingsPolicyV136.decorateText(body, normalized)
            .lineSequence()
            .flatMap { line ->
                if (line.isEmpty()) sequenceOf("")
                else ReceiptLineWrapV136.wrap(line, paper.charsPerLine).asSequence()
            }
            .joinToString("\n")
    }
}
