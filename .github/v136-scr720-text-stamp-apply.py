from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    p = Path(path)
    text = p.read_text()
    if old not in text:
        raise SystemExit(f"missing anchor: {path}: {old[:160]!r}")
    p.write_text(text.replace(old, new, 1))


replace_once(
    "app/src/main/java/jp/co/tenposinfo/register/AutomaticPrintWorker.kt",
    '''                                val receiptGateway = ReceiptStampGatewayV136(
                                    context = applicationContext,
                                    delegate = rawGateway,
                                    paperWidthMm = width,
                                )
                                val deliveryGateway = DeliveryConfirmingPrinterGatewayV136(
                                    context = applicationContext,
                                    configuration = configuration.copy(paperWidthMm = width),
                                    kind = PrintDeliveryJobKindV136.SALE_RECEIPT,
                                    jobId = candidate.sourceId,
                                    delegate = receiptGateway,
                                )''',
    '''                                val receiptGateway = ReceiptStampGatewayV136(
                                    context = applicationContext,
                                    delegate = rawGateway,
                                    paperWidthMm = width,
                                )
                                val textStampGateway = ReceiptTextStampGatewayV136(
                                    context = applicationContext,
                                    delegate = receiptGateway,
                                    configuration = configuration.copy(paperWidthMm = width),
                                )
                                val deliveryGateway = DeliveryConfirmingPrinterGatewayV136(
                                    context = applicationContext,
                                    configuration = configuration.copy(paperWidthMm = width),
                                    kind = PrintDeliveryJobKindV136.SALE_RECEIPT,
                                    jobId = candidate.sourceId,
                                    delegate = textStampGateway,
                                )''',
)

replace_once(
    "app/src/main/java/jp/co/tenposinfo/register/UnifiedPrintQueue.kt",
    '''                        val receiptGateway = ReceiptStampGatewayV136(
                            context = applicationContext,
                            delegate = rawGateway,
                            paperWidthMm = job.paperWidthMm,
                        )
                        val deliveryGateway = DeliveryConfirmingPrinterGatewayV136(
                            context = applicationContext,
                            configuration = configuration.copy(paperWidthMm = job.paperWidthMm),
                            kind = PrintDeliveryJobKindV136.SALE_RECEIPT,
                            jobId = job.sourceId,
                            delegate = receiptGateway,
                        )''',
    '''                        val receiptGateway = ReceiptStampGatewayV136(
                            context = applicationContext,
                            delegate = rawGateway,
                            paperWidthMm = job.paperWidthMm,
                        )
                        val textStampGateway = ReceiptTextStampGatewayV136(
                            context = applicationContext,
                            delegate = receiptGateway,
                            configuration = configuration.copy(paperWidthMm = job.paperWidthMm),
                        )
                        val deliveryGateway = DeliveryConfirmingPrinterGatewayV136(
                            context = applicationContext,
                            configuration = configuration.copy(paperWidthMm = job.paperWidthMm),
                            kind = PrintDeliveryJobKindV136.SALE_RECEIPT,
                            jobId = job.sourceId,
                            delegate = textStampGateway,
                        )''',
)

replace_once(
    "app/src/main/java/jp/co/tenposinfo/register/DocumentPrintSettingsV136.kt",
    '''        if (selected == DocumentPrintKindV136.SALE_RECEIPT) {
            ReceiptStampSettingsPanelV136()
        }''',
    '''        if (selected == DocumentPrintKindV136.SALE_RECEIPT) {
            ReceiptTextStampSettingsPanelV136()
            ReceiptStampSettingsPanelV136()
        }''',
)

print("SCR-720 text stamp integration applied")
