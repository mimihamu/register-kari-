package jp.co.tenposinfo.register

/**
 * Formal v2.5 §16.10 PrinterProfile contract.
 *
 * The existing [PrinterProfile] enum describes ESC/POS command differences, while
 * this snapshot represents the complete effective printer profile required by the
 * formal print data model. v1.36 still supports one configured TCP 9100 printer,
 * so printerId and connectionType are stable values for that persisted endpoint.
 */
enum class PrinterConnectionTypeV136(val wireName: String) {
    TCP_9100("TCP"),
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
     * 初版では仕様根拠のある標準幅だけを永続化する。
     * 機種固有値を追加する場合は、採用機器仕様を確認した上で別途対応する。
     */
    fun validatePersistedConfiguration(configuration: PrinterConfiguration) {
        val expectedDots = standardPrintableDotWidth(configuration.paperWidthMm)
        require(configuration.printableDotWidth == expectedDots) {
            "${configuration.paperWidthMm}mmの印字可能幅は初版標準${expectedDots}dotです"
        }
        require(configuration.feedLines in MIN_FEED_LINES..MAX_FEED_LINES) {
            "紙送り行数は${MIN_FEED_LINES}～${MAX_FEED_LINES}行で入力してください"
        }
    }

    fun snapshot(configuration: PrinterConfiguration): PrinterProfileSnapshotV136 {
        val paper = ReceiptPaper.fromWidth(configuration.paperWidthMm)
        val supportsCut = configuration.cutMode != PrinterCutMode.NONE
        return PrinterProfileSnapshotV136(
            printerId = SINGLE_PRINTER_ID,
            name = configuration.name.trim().ifBlank { "レシートプリンター" },
            connectionType = PrinterConnectionTypeV136.TCP_9100,
            address = configuration.host.trim().let { host ->
                if (host.isBlank()) "" else "$host:${configuration.port}"
            },
            paperWidthMm = paper.widthMm,
            printableDotWidth = configuration.printableDotWidth,
            logicalColumns = paper.charsPerLine,
            encoding = configuration.profile.charsetName,
            supportsCut = supportsCut,
            cutMode = if (supportsCut) configuration.cutMode else PrinterCutMode.NONE,
            feedLines = configuration.feedLines,
            drawerPort = configuration.drawerPort.takeIf { configuration.drawerEnabled },
            statusCapability = configuration.profile.statusProtocol,
        )
    }

    fun isInternallyConsistent(snapshot: PrinterProfileSnapshotV136): Boolean {
        val expectedDots = when (snapshot.paperWidthMm) {
            58 -> MM58_STANDARD_DOTS
            80 -> MM80_STANDARD_DOTS
            else -> return false
        }
        val expectedColumns = when (snapshot.paperWidthMm) {
            58 -> ReceiptPaper.MM58.charsPerLine
            80 -> ReceiptPaper.MM80.charsPerLine
            else -> return false
        }
        return snapshot.printerId.isNotBlank() &&
            snapshot.name.isNotBlank() &&
            snapshot.printableDotWidth == expectedDots &&
            snapshot.logicalColumns == expectedColumns &&
            snapshot.encoding.isNotBlank() &&
            snapshot.feedLines in MIN_FEED_LINES..MAX_FEED_LINES &&
            (snapshot.supportsCut || snapshot.cutMode == PrinterCutMode.NONE) &&
            (snapshot.drawerPort == null || snapshot.drawerPort in 0..1)
    }
}
