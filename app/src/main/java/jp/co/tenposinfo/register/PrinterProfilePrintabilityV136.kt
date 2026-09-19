package jp.co.tenposinfo.register

import java.nio.charset.Charset

/**
 * v1.36 / formal v2.5 §16.2 printer-profile printability diagnostics.
 *
 * The diagnostic is intentionally sent through the same PrinterCommandEncoder and
 * TCP 9100 gateway as normal printing. A successful socket write is software-side
 * evidence only; paper output, cutter movement, drawer movement and live status
 * still require the configured physical printer.
 */
object PrinterProfilePrintabilityV136 {
    const val QR_PAYLOAD = "TSUGUREGI-PRINTER-TEST"

    /** Formal §16.2 test-print content that can be checked before saving. */
    fun diagnosticText(): String = buildString {
        append("[PrinterProfile 印字可能性確認]\n")
        append("全角：つぐレジ 印刷確認\n")
        append("半角/英数字: ABC abc 1234567890\n")
        append("記号: ! # % & ( ) + , - . / : ; = ? @\n")
        append("最大桁金額: ¥9,999,999,999\n")
        append("10%対象: ¥1,100 / 税 ¥100\n")
        append("8%対象※: ¥1,080 / 税 ¥80\n")
        append("非課税: ¥1,000\n")
        append("値引: -¥100\n")
        append("負数: -¥9,999,999\n")
        append("QR: $QR_PAYLOAD\n")
        append(qrControlSequence(QR_PAYLOAD))
        append('\n')
    }

    /**
     * ESC/POS GS ( k QR Code Model 2 command sequence represented as control chars.
     * All command bytes and the fixed diagnostic payload are 7-bit values, so the
     * existing Shift_JIS/MS932 text encoding preserves the bytes unchanged.
     */
    fun qrControlSequence(payload: String): String {
        val data = payload.toByteArray(Charsets.US_ASCII)
        require(data.isNotEmpty()) { "QRテストデータが空です" }
        require(data.size <= 120) { "QRテストデータが長すぎます" }
        val storeLength = data.size + 3
        require(storeLength < 256)

        return buildString {
            // Select QR model 2.
            appendControl(0x1D, 0x28, 0x6B, 0x04, 0x00, 0x31, 0x41, 0x32, 0x00)
            // Module size = 4.
            appendControl(0x1D, 0x28, 0x6B, 0x03, 0x00, 0x31, 0x43, 0x04)
            // Error correction level M (49).
            appendControl(0x1D, 0x28, 0x6B, 0x03, 0x00, 0x31, 0x45, 0x31)
            // Store data in symbol storage area.
            appendControl(0x1D, 0x28, 0x6B, storeLength, 0x00, 0x31, 0x50, 0x30)
            data.forEach { byte -> append((byte.toInt() and 0xFF).toChar()) }
            // Print symbol data.
            appendControl(0x1D, 0x28, 0x6B, 0x03, 0x00, 0x31, 0x51, 0x30)
        }
    }

    fun validateSoftwarePreflight(configuration: PrinterConfiguration) {
        require(configuration.host.isNotBlank()) { "IPアドレスまたはホスト名を入力してください" }
        require(configuration.port in 1..65535) { "ポート番号は1～65535で入力してください" }
        require(configuration.timeoutMillis in 1_000..30_000) { "タイムアウトは1000～30000msで入力してください" }
        PrinterProfileContractV136.validatePersistedConfiguration(configuration)
        require(Charset.isSupported(configuration.profile.charsetName)) {
            "文字コード ${configuration.profile.charsetName} をこの端末で使用できません"
        }
        if (configuration.drawerEnabled) {
            require(configuration.profile.supportsDrawer) { "選択中のプロファイルはドロア制御に対応していません" }
        }
    }

    private fun StringBuilder.appendControl(vararg values: Int) {
        values.forEach { value ->
            require(value in 0..255)
            append(value.toChar())
        }
    }
}
