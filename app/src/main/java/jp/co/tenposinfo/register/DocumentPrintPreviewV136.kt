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
 * Preview uses the same production renderer as the corresponding printed document
 * wherever a dedicated renderer exists. This prevents screen-only templates from
 * drifting from the 58/80mm production layout.
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
            DocumentPrintKindV136.RECEIPT_VOUCHER -> renderReceiptVoucher(setting, paper)
            DocumentPrintKindV136.PROVISIONAL_RECEIPT -> renderProvisionalReceipt(setting, paper)
            DocumentPrintKindV136.SETTLEMENT -> renderSettlement(setting, paper, SettlementReportType.Z_SETTLEMENT)
            DocumentPrintKindV136.INSPECTION -> renderSettlement(setting, paper, SettlementReportType.X_INSPECTION)
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

    private fun renderReceiptVoucher(
        setting: DocumentPrintSettingV136,
        paper: ReceiptPaper,
    ): String {
        val body = ReceiptVoucherRenderer.render(
            ReceiptVoucherDocumentData(
                issuanceId = 123L,
                saleId = 100L,
                sequenceNo = 1,
                sequenceCount = 1,
                amount = 12_345L,
                addressee = "サンプル株式会社",
                purpose = "お食事代",
                operatorName = "担当者",
                issuedAt = PREVIEW_CREATED_AT,
                issuer = previewIssuer(),
            ),
            paper,
        )
        return decorateAndWrap(DocumentPrintKindV136.RECEIPT_VOUCHER, body, setting, paper)
    }

    private fun renderProvisionalReceipt(
        setting: DocumentPrintSettingV136,
        paper: ReceiptPaper,
    ): String {
        val ticket = HeldTicket(
            id = 123L,
            name = "テーブル1",
            createdAt = PREVIEW_CREATED_AT,
            operatorName = "担当者",
            guestCount = 2,
        )
        val items = listOf(
            CartItem(
                product = Product("PREVIEW-10", "通常商品サンプル", 1_100L, TaxCategory.INCLUDED_10, 1),
                quantity = 2,
            ),
        )
        val body = HeldTicketProvisionalReceiptRendererV135.render(ticket, items, paper)
        return decorateAndWrap(DocumentPrintKindV136.PROVISIONAL_RECEIPT, body, setting, paper)
    }

    private fun renderSettlement(
        setting: DocumentPrintSettingV136,
        paper: ReceiptPaper,
        type: SettlementReportType,
    ): String {
        val body = OperationDocumentRenderer.renderSettlement(
            SettlementDocumentData(
                reportId = 0L,
                businessDate = "2026-01-01",
                type = type,
                createdAt = PREVIEW_CREATED_AT,
                operatorName = "担当者",
                salesGross = 12_345L,
                reversalGross = 0L,
                netSales = 12_345L,
                openingCash = 10_000L,
                cashIn = 0L,
                cashOut = 0L,
                expectedCash = 22_345L,
                actualCash = 22_345L,
                variance = 0L,
                transactionCount = 3,
                reversalCount = 0,
                pendingPrints = 0,
                heldTickets = 0,
                paymentTotals = listOf(PaymentTotal(PaymentMethod.CASH.name, 12_345L)),
            ),
            paper,
        )
        val kind = if (type == SettlementReportType.Z_SETTLEMENT) {
            DocumentPrintKindV136.SETTLEMENT
        } else {
            DocumentPrintKindV136.INSPECTION
        }
        return decorateAndWrap(kind, body, setting, paper)
    }

    private fun previewIssuer(): InvoiceIssuerProfile = InvoiceIssuerProfile(
        storeName = "つぐレジ プレビュー店",
        address = "埼玉県越谷市サンプル1-2-3",
        phone = "048-000-0000",
        registrationNumber = "T1234567890123",
    )

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
