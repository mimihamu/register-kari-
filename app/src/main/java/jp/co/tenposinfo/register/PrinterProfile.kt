package jp.co.tenposinfo.register

import java.io.ByteArrayOutputStream
import java.nio.charset.Charset

/**
 * TCP 9100で使用するESC/POS互換プロファイル。
 * 現段階では日本語CP932・標準カット・ドロアキックを共通基盤とし、
 * 機種固有差分をこのクラスへ集約する。
 */
enum class PrinterProfile(
    val displayName: String,
    val description: String,
    val charsetName: String = "MS932",
    val codeTable: Int = 1,
    val supportsDrawer: Boolean = true,
) {
    EPSON_TM_JAPAN(
        displayName = "EPSON TM（日本語）",
        description = "TM-m30II／TM-T88系などのESC/POS対応機",
    ),
    STAR_ESC_POS(
        displayName = "STAR ESC/POS",
        description = "mC-Print／TSP系のESC/POSエミュレーション",
    ),
    GENERIC_ESC_POS(
        displayName = "汎用ESC/POS",
        description = "TCP 9100対応の互換プリンター",
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
        output.write(byteArrayOf(0x1B, 0x40))
        output.write(byteArrayOf(0x1B, 0x74, configuration.profile.codeTable.toByte()))
        output.write(byteArrayOf(0x1B, 0x61, 0x00))
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
            output.write(byteArrayOf(0x0A, 0x0A, 0x0A))
            output.write(cutCommand(configuration.cutMode))
        }
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
