package jp.co.tenposinfo.register

import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.system.measureTimeMillis

enum class PrinterStatusProbePreset(
    val displayName: String,
    val description: String,
    val requestBytes: ByteArray,
    val experimental: Boolean,
) {
    TCP_CONNECT_ONLY(
        displayName = "TCP接続のみ",
        description = "印刷データや状態コマンドを送らず、TCP接続の可否だけ確認します。",
        requestBytes = byteArrayOf(),
        experimental = false,
    ),
    EPSON_DLE_EOT_BATCH(
        displayName = "EPSON DLE EOT n=1～4",
        description = "EPSON仕様のリアルタイムステータス4コマンドを一括送信し、RAW応答を取得します。",
        requestBytes = PrinterRealtimeStatusProtocol.requestBytes(),
        experimental = false,
    ),
    DLE_EOT_COMPATIBILITY_BATCH(
        displayName = "DLE EOT互換試行 n=1～4",
        description = "STAR／汎用機へEPSON形式のDLE EOTを試行します。互換性を保証せず、自動監視には反映しません。",
        requestBytes = PrinterRealtimeStatusProtocol.requestBytes(),
        experimental = true,
    ),
}

data class PrinterStatusProbeResult(
    val preset: PrinterStatusProbePreset,
    val host: String,
    val port: Int,
    val startedAt: Long,
    val elapsedMillis: Long,
    val requestBytes: ByteArray,
    val responseBytes: ByteArray,
) {
    val requestHex: String
        get() = requestBytes.toHex()

    val responseHex: String
        get() = responseBytes.toHex()

    val responseAscii: String
        get() = responseBytes.joinToString("") { value ->
            val unsigned = value.toInt() and 0xFF
            if (unsigned in 0x20..0x7E) unsigned.toChar().toString() else "."
        }

    val parsedEpsonStatus: PrinterRealtimeStatus?
        get() = if (responseBytes.size == 4) {
            runCatching {
                PrinterRealtimeStatusProtocol.parse(
                    responseBytes,
                    checkedAt = startedAt,
                    elapsedMillis = elapsedMillis,
                )
            }.getOrNull()
        } else {
            null
        }
}

object PrinterStatusProbePolicy {
    const val MAX_RESPONSE_BYTES = 256

    fun presetFor(profile: PrinterProfile): PrinterStatusProbePreset = when (profile) {
        PrinterProfile.EPSON_TM_JAPAN -> PrinterStatusProbePreset.EPSON_DLE_EOT_BATCH
        PrinterProfile.STAR_ESC_POS,
        PrinterProfile.GENERIC_ESC_POS,
        -> PrinterStatusProbePreset.DLE_EOT_COMPATIBILITY_BATCH
    }

    fun canRun(preset: PrinterStatusProbePreset, experimentalConfirmed: Boolean): Boolean =
        !preset.experimental || experimentalConfirmed
}

class TcpPrinterStatusProbeClient(
    private val configuration: PrinterConfiguration,
) {
    fun execute(preset: PrinterStatusProbePreset): Result<PrinterStatusProbeResult> = runCatching {
        require(configuration.host.isNotBlank()) { "IPアドレスまたはホスト名が未設定です" }
        require(configuration.port in 1..65535) { "プリンターポートが不正です" }

        val startedAt = System.currentTimeMillis()
        val response = ByteArrayOutputStream()
        val elapsed = measureTimeMillis {
            Socket().use { socket ->
                val timeout = configuration.timeoutMillis.coerceIn(1_000, 30_000)
                socket.tcpNoDelay = true
                socket.soTimeout = timeout
                socket.connect(InetSocketAddress(configuration.host.trim(), configuration.port), timeout)

                if (preset.requestBytes.isNotEmpty()) {
                    socket.getOutputStream().also { output ->
                        output.write(preset.requestBytes)
                        output.flush()
                    }
                    val input = socket.getInputStream()
                    val buffer = ByteArray(64)
                    while (response.size() < PrinterStatusProbePolicy.MAX_RESPONSE_BYTES) {
                        try {
                            val remaining = PrinterStatusProbePolicy.MAX_RESPONSE_BYTES - response.size()
                            val count = input.read(buffer, 0, minOf(buffer.size, remaining))
                            if (count < 0) break
                            response.write(buffer, 0, count)
                        } catch (timeoutError: SocketTimeoutException) {
                            if (response.size() == 0) {
                                throw PrinterStatusQueryException(
                                    "状態コマンド送信後に応答がありません。非対応、オフライン、通信設定を確認してください",
                                    timeoutError,
                                )
                            }
                            break
                        }
                    }
                }
            }
        }

        PrinterStatusProbeResult(
            preset = preset,
            host = configuration.host.trim(),
            port = configuration.port,
            startedAt = startedAt,
            elapsedMillis = elapsed,
            requestBytes = preset.requestBytes.copyOf(),
            responseBytes = response.toByteArray(),
        )
    }
}

object PrinterStatusProbeCsv {
    private val dateFormat = SimpleDateFormat("yyyy/MM/dd HH:mm:ss.SSS", Locale.JAPAN)

    fun render(profile: PrinterProfile, result: PrinterStatusProbeResult): String = buildString {
        appendRow("つぐレジ プリンター状態RAWプローブ")
        appendRow("実行日時", synchronized(dateFormat) { dateFormat.format(Date(result.startedAt)) })
        appendRow("プリンター機種", profile.displayName)
        appendRow("プリセット", result.preset.displayName)
        appendRow("検証区分", if (result.preset.experimental) "互換試行・未検証" else "仕様確認済み／接続のみ")
        appendRow("接続先", "${result.host}:${result.port}")
        appendRow("応答時間ms", result.elapsedMillis.toString())
        appendRow("送信バイト数", result.requestBytes.size.toString())
        appendRow("受信バイト数", result.responseBytes.size.toString())
        appendRow("送信HEX", result.requestHex)
        appendRow("受信HEX", result.responseHex)
        appendRow("受信ASCII", result.responseAscii)
        result.parsedEpsonStatus?.let { status ->
            appendRow("EPSON形式解析", "${status.level.displayName} / ${status.summary}")
            appendRow("EPSON固定ビット", if (status.protocolValid) "一致" else "不一致")
        }
    }

    fun escape(value: String): String {
        val normalized = value.replace("\r\n", "\n").replace('\r', '\n')
        val escaped = normalized.replace("\"", "\"\"")
        return if (normalized.any { it == ',' || it == '\"' || it == '\n' }) "\"$escaped\"" else escaped
    }

    private fun StringBuilder.appendRow(vararg values: String) {
        append(values.joinToString(",") { escape(it) })
        append('\n')
    }
}

private fun ByteArray.toHex(): String =
    joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
