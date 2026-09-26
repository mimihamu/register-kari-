package jp.co.tenposinfo.register

/**
 * Formal v2.5 §16.10 PrinterProfile contract.
 *
 * The existing [PrinterProfile] enum describes ESC/POS command differences, while
 * this snapshot represents the effective printer profile required by the formal
 * print data model. TCP 9100 remains the compatibility/default transport while
 * USB and Bluetooth are additional transports behind the same PrinterGateway.
 */
enum class PrinterConnectionTypeV136(
    val wireName: String,
    val displayName: String,
) {
    TCP_9100("TCP", "LAN / TCP 9100"),
    USB("USB", "USB"),
    BLUETOOTH("BLUETOOTH", "Bluetooth"),
}

data class PrinterProfileSnapshotV136(
    val printerId: String,
    val name: String,
    val connectionType: PrinterConnectionTypeV136,
    val address: String,
    val paperWidthMm: Int,
    val printableDotWidth: Int,
    val logicalColumns: Int,
    val encoding: String,
    val supportsCut: Boolean,
    val cutMode: PrinterCutMode,
    val feedLines: Int,
    val drawerPort: Int?,
    val statusCapability: PrinterStatusProtocol,
) {
    init {
        require(printerId.isNotBlank())
        require(name.isNotBlank())
        require(paperWidthMm == 58 || paperWidthMm == 80)
        require(printableDotWidth > 0)
        require(logicalColumns > 0)
        require(encoding.isNotBlank())
        require(feedLines in PrinterProfileContractV136.MIN_FEED_LINES..PrinterProfileContractV136.MAX_FEED_LINES)
        require(drawerPort == null || drawerPort == 0 || drawerPort == 1)
        if (!supportsCut) require(cutMode == PrinterCutMode.NONE)
    }
}

object PrinterProfileContractV136 {
    const val SINGLE_PRINTER_ID = "printer-1"
    const val MIN_FEED_LINES = 3
    const val MAX_FEED_LINES = 7
    const val DEFAULT_FEED_LINES = 5
    const val MM58_STANDARD_DOTS = 384
    const val MM80_STANDARD_DOTS = 576

    fun standardPrintableDotWidth(paperWidthMm: Int): Int = when (paperWidthMm) {
        58 -> MM58_STANDARD_DOTS
        80 -> MM80_STANDARD_DOTS
        else -> throw IllegalArgumentException("用紙幅は58mmまたは80mmです")
    }

    /**
     * v2.5 §16.1 は 384/576dot を標準値としつつ、採用機器の実印字幅
     * （58mmの420dot、80mmの512/640dot等）を保存できることを要求する。
     * 紙幅変更時は [standardPrintableDotWidth] を初期値に使い、保存時は
     * 機器仕様として入力された正のdot幅を保持する。
     */
    fun validatePersistedConfiguration(configuration: PrinterConfiguration) {
        require(configuration.paperWidthMm == 58 || configuration.paperWidthMm == 80) {
            "用紙幅は58mmまたは80mmです"
        }
        require(configuration.printableDotWidth > 0) {
            "印字可能幅は1dot以上で入力してください"
        }
        require(configuration.feedLines in MIN_FEED_LINES..MAX_FEED_LINES) {
            "紙送り行数は${MIN_FEED_LINES}～${MAX_FEED_LINES}行で入力してください"
        }
    }

    fun snapshot(configuration: PrinterConfiguration): PrinterProfileSnapshotV136 {
        val paper = ReceiptPaper.fromWidth(configuration.paperWidthMm)
        val supportsCut = configuration.cutMode != PrinterCutMode.NONE
        return PrinterProfileSnapshotV136(
            printerId = configuration.printerId.trim().ifBlank { SINGLE_PRINTER_ID },
            name = configuration.name.trim().ifBlank { "レシートプリンター" },
            connectionType = configuration.connectionType,
            address = PrinterTransportPolicyV136.endpointDisplay(configuration),
            paperWidthMm = paper.widthMm,
            printableDotWidth = configuration.printableDotWidth,
            logicalColumns = paper.charsPerLine,
            encoding = configuration.profile.charsetName,
            supportsCut = supportsCut,
            cutMode = if (supportsCut) configuration.cutMode else PrinterCutMode.NONE,
            feedLines = configuration.feedLines,
            drawerPort = configuration.drawerPort.takeIf { configuration.drawerEnabled },
            statusCapability = if (PrinterTransportPolicyV136.supportsRealtimeStatus(configuration)) {
                configuration.profile.statusProtocol
            } else {
                PrinterStatusProtocol.NONE
            },
        )
    }

    fun isInternallyConsistent(snapshot: PrinterProfileSnapshotV136): Boolean {
        if (snapshot.printableDotWidth <= 0) return false
        val expectedColumns = when (snapshot.paperWidthMm) {
            58 -> ReceiptPaper.MM58.charsPerLine
            80 -> ReceiptPaper.MM80.charsPerLine
            else -> return false
        }
        return snapshot.printerId.isNotBlank() &&
            snapshot.name.isNotBlank() &&
            snapshot.printableDotWidth > 0 &&
            snapshot.logicalColumns == expectedColumns &&
            snapshot.encoding.isNotBlank() &&
            snapshot.feedLines in MIN_FEED_LINES..MAX_FEED_LINES &&
            (snapshot.supportsCut || snapshot.cutMode == PrinterCutMode.NONE) &&
            (snapshot.drawerPort == null || snapshot.drawerPort in 0..1)
    }
}
