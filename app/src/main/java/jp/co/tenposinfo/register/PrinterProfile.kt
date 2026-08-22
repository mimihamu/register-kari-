package jp.co.tenposinfo.register

import java.io.ByteArrayOutputStream
import java.nio.charset.Charset

/**
 * TCP 9100で使用するESC/POS互換プロファイル。
 * 日本語コードページ、カット、ドロア、双方向ステータスの機種差分を集約する。
 */
enum class PrinterStatusProtocol(val displayName: String) {
    EPSON_DLE_EOT("EPSON DLE EOT（仕様確認済み）"),
    ESC_POS_DLE_EOT_COMPATIBLE("DLE EOT互換（未検証）"),
    NONE("非対応"),
}

enum class PrinterProfile(
    val displayName: String,
    val description: String,
    val charsetName: String = "MS932",
    val codeTable: Int = 1,
    /** EPSON日本語仕様のFS C n。nullは機種依存コマンドを送信しない。 */
    val kanjiCodeSystem: Int? = null,
    val supportsDrawer: Boolean = true,
    val statusProtocol: PrinterStatusProtocol = PrinterStatusProtocol.ESC_POS_DLE_EOT_COMPATIBLE,
) {
    EPSON_TM_JAPAN(
        displayName = "EPSON TM（日本語）",
        description = "TM-m30II／TM-T88系などのESC/POS対応機。Shift JIS漢字体系とDLE EOT状態取得を使用",
        charsetName = "Shift_JIS",
        kanjiCodeSystem = 1,
        statusProtocol = PrinterStatusProtocol.EPSON_DLE_EOT,
    ),
    STAR_ESC_POS(
        displayName = "STAR ESC/POS",
        description = "STAR機のESC/POSエミュレーション。印刷互換／状態取得は要実機確認",
        statusProtocol = PrinterStatusProtocol.ESC_POS_DLE_EOT_COMPATIBLE,
    ),
    GENERIC_ESC_POS(
        displayName = "汎用ESC/POS",
        description = "TCP 9100対応の互換プリンター。状態取得は要実機確認",
        statusProtocol = PrinterStatusProtocol.ESC_POS_DLE_EOT_COMPATIBLE,
    ),
}

enum class PrinterCutMode(val displayName: String) {
    PARTIAL("部分カット"),
    FULL("フルカット"),
    NONE("カットなし"),
}

object PrinterPulsePolicy {
    fun command(port: Int, onMillis: Int, offMillis: Int): ByteArray {
        require(port == 0 || port == 1) { "ドロアポートはDK1またはDK2です" }
        require(onMillis in 20..500) { "ドロアON時間は20～500msです" }
        require(offMillis in 20..500) { "ドロアOFF時間は20～500msです" }
        val onUnits = (onMillis / 2).coerceIn(1, 255)
        val offUnits = (offMillis / 2).coerceIn(1, 255)
        return byteArrayOf(
            0x1B,
            0x70,
            port.toByte(),
            onUnits.toByte(),
            offUnits.toByte(),
        )
    }
}

/** ESC/POSバイト列生成を売上・業務帳票・テスト印刷で共通化する。 */
object PrinterCommandEncoder {
    fun encodeText(
        text: String,
        configuration: PrinterConfiguration,
        openDrawer: Boolean = false,
        appendCut: Boolean = true,
    ): ByteArray {
        val output = ByteArrayOutputStream()
        output.write(beginDocument(configuration))
        if (openDrawer && configuration.drawerEnabled && configuration.profile.supportsDrawer) {
            output.write(
                PrinterPulsePolicy.command(
                    port = configuration.drawerPort,
                    onMillis = configuration.drawerOnMillis,
                    offMillis = configuration.drawerOffMillis,
                ),
            )
        }
        output.write(text.toByteArray(Charset.forName(configuration.profile.charsetName)))
        if (appendCut) {
            require(configuration.feedLines in PrinterProfileContractV136.MIN_FEED_LINES..PrinterProfileContractV136.MAX_FEED_LINES) {
                "紙送り行数は${PrinterProfileContractV136.MIN_FEED_LINES}～${PrinterProfileContractV136.MAX_FEED_LINES}行で指定してください"
            }
            repeat(configuration.feedLines) { output.write(0x0A) }
            output.write(cutCommand(configuration.cutMode))
        }
        return output.toByteArray()
    }

    /**
     * ESC @ は漢字コード体系も初期化するため、機種初期化の直後にプロファイル固有設定を再指定する。
     * EPSON日本語プロファイルはFS C 1でShift JIS漢字コード体系を選択する。
     * Shift JIS体系ではプリンターが先頭バイトから2バイト文字を判定するためFS &は送信しない。
     */
    fun beginDocument(configuration: PrinterConfiguration): ByteArray {
        val output = ByteArrayOutputStream()
        output.write(byteArrayOf(0x1B, 0x40))
        output.write(byteArrayOf(0x1B, 0x74, configuration.profile.codeTable.toByte()))
        configuration.profile.kanjiCodeSystem?.let { codeSystem ->
            output.write(byteArrayOf(0x1C, 0x43, codeSystem.toByte()))
        }
        output.write(byteArrayOf(0x1B, 0x61, 0x00))
        return output.toByteArray()
    }

    fun drawerOnly(configuration: PrinterConfiguration): ByteArray {
        require(configuration.drawerEnabled) { "ドロア設定が無効です" }
        require(configuration.profile.supportsDrawer) { "選択中のプロファイルはドロア制御に対応していません" }
        return byteArrayOf(0x1B, 0x40) + PrinterPulsePolicy.command(
            port = configuration.drawerPort,
            onMillis = configuration.drawerOnMillis,
            offMillis = configuration.drawerOffMillis,
        )
    }

    private fun cutCommand(mode: PrinterCutMode): ByteArray = when (mode) {
        PrinterCutMode.PARTIAL -> byteArrayOf(0x1D, 0x56, 0x42, 0x00)
        PrinterCutMode.FULL -> byteArrayOf(0x1D, 0x56, 0x41, 0x00)
        PrinterCutMode.NONE -> byteArrayOf()
    }
}
