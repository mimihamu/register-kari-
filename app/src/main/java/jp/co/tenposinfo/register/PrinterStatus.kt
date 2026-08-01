package jp.co.tenposinfo.register

import java.io.EOFException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import kotlin.system.measureTimeMillis

enum class PrinterStatusLevel(val displayName: String) {
    READY("正常"),
    WARNING("注意"),
    OFFLINE("オフライン"),
    ERROR("エラー"),
}

data class PrinterRealtimeStatus(
    val checkedAt: Long,
    val elapsedMillis: Long,
    val online: Boolean,
    val drawerSignalHigh: Boolean,
    val waitingForOnlineRecovery: Boolean,
    val feedButtonPressed: Boolean,
    val coverOpen: Boolean,
    val paperFeedStopped: Boolean,
    val offlineErrorPresent: Boolean,
    val recoverableError: Boolean,
    val cutterError: Boolean,
    val unrecoverableError: Boolean,
    val autoRecoverableError: Boolean,
    val paperNearEnd: Boolean,
    val paperOut: Boolean,
    val protocolValid: Boolean,
    val rawStatus: ByteArray,
) {
    val level: PrinterStatusLevel
        get() = when {
            unrecoverableError || cutterError || recoverableError -> PrinterStatusLevel.ERROR
            !online || coverOpen || paperOut -> PrinterStatusLevel.OFFLINE
            autoRecoverableError || paperNearEnd || waitingForOnlineRecovery || !protocolValid -> PrinterStatusLevel.WARNING
            else -> PrinterStatusLevel.READY
        }

    val summary: String
        get() = when {
            unrecoverableError -> "復帰不可能エラー"
            cutterError -> "オートカッターエラー"
            recoverableError -> "復帰可能エラー"
            coverOpen -> "カバーが開いています"
            paperOut -> "ロール紙がありません"
            !online -> "プリンターがオフラインです"
            autoRecoverableError -> "自動復帰エラーを検出"
            paperNearEnd -> "ロール紙残量が少なくなっています"
            waitingForOnlineRecovery -> "オンライン復帰待ちです"
            !protocolValid -> "応答形式を確認してください"
            else -> "印刷可能です"
        }

    val rawHex: String
        get() = rawStatus.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
}

object PrinterRealtimeStatusProtocol {
    private const val DLE = 0x10
    private const val EOT = 0x04

    /** EPSON DLE EOT n=1～4を最大4コマンドの一括送信として問い合わせる。 */
    fun requestBytes(): ByteArray = byteArrayOf(
        DLE.toByte(), EOT.toByte(), 0x01,
        DLE.toByte(), EOT.toByte(), 0x02,
        DLE.toByte(), EOT.toByte(), 0x03,
        DLE.toByte(), EOT.toByte(), 0x04,
    )

    fun parse(bytes: ByteArray, checkedAt: Long = System.currentTimeMillis(), elapsedMillis: Long = 0): PrinterRealtimeStatus {
        require(bytes.size == 4) { "リアルタイムステータスは4バイト必要です" }
        val printer = bytes[0].toInt() and 0xFF
        val offlineCause = bytes[1].toInt() and 0xFF
        val errorCause = bytes[2].toInt() and 0xFF
        val paper = bytes[3].toInt() and 0xFF
        val protocolValid = bytes.all { value ->
            val unsigned = value.toInt() and 0xFF
            unsigned and 0x93 == 0x12
        }
        return PrinterRealtimeStatus(
            checkedAt = checkedAt,
            elapsedMillis = elapsedMillis,
            online = printer and 0x08 == 0,
            drawerSignalHigh = printer and 0x04 != 0,
            waitingForOnlineRecovery = printer and 0x20 != 0,
            feedButtonPressed = printer and 0x40 != 0 || offlineCause and 0x08 != 0,
            coverOpen = offlineCause and 0x04 != 0,
            paperFeedStopped = offlineCause and 0x20 != 0,
            offlineErrorPresent = offlineCause and 0x40 != 0,
            recoverableError = errorCause and 0x04 != 0,
            cutterError = errorCause and 0x08 != 0,
            unrecoverableError = errorCause and 0x20 != 0,
            autoRecoverableError = errorCause and 0x40 != 0,
            paperNearEnd = paper and 0x0C == 0x0C,
            paperOut = paper and 0x60 == 0x60,
            protocolValid = protocolValid,
            rawStatus = bytes.copyOf(),
        )
    }
}

class PrinterStatusQueryException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

class TcpPrinterStatusClient(
    private val configuration: PrinterConfiguration,
) {
    fun query(
        purpose: PrinterStatusCheckPurpose = PrinterStatusCheckPurpose.MANUAL_DIAGNOSTIC,
        experimentalConfirmed: Boolean = false,
    ): Result<PrinterRealtimeStatus> = runCatching {
        require(configuration.host.isNotBlank()) { "IPアドレスまたはホスト名が未設定です" }
        require(configuration.port in 1..65535) { "プリンターポートが不正です" }
        require(configuration.profile.statusProtocol != PrinterStatusProtocol.NONE) {
            "選択中のプリンタープロファイルは状態取得に対応していません"
        }

        val capability = PrinterStatusCapabilityRegistry.forProfile(configuration.profile)
        when (capability.decision(purpose, experimentalConfirmed)) {
            PrinterStatusCheckDecision.ALLOWED -> Unit
            PrinterStatusCheckDecision.REQUIRES_EXPLICIT_CONFIRMATION,
            PrinterStatusCheckDecision.DENIED,
            -> throw PrinterStatusQueryException(
                PrinterStatusCapabilityRegistry.denialMessage(configuration.profile, purpose),
            )
        }

        val response = ByteArray(4)
        val checkedAt = System.currentTimeMillis()
        var elapsed = 0L
        try {
            elapsed = measureTimeMillis {
                Socket().use { socket ->
                    val timeout = configuration.timeoutMillis.coerceIn(1_000, 30_000)
                    socket.tcpNoDelay = true
                    socket.soTimeout = timeout
                    socket.connect(InetSocketAddress(configuration.host.trim(), configuration.port), timeout)
                    socket.getOutputStream().also { output ->
                        output.write(PrinterRealtimeStatusProtocol.requestBytes())
                        output.flush()
                    }
                    val input = socket.getInputStream()
                    var offset = 0
                    while (offset < response.size) {
                        val count = input.read(response, offset, response.size - offset)
                        if (count < 0) throw EOFException("ステータス応答が${offset}バイトで終了しました")
                        offset += count
                    }
                }
            }
        } catch (error: SocketTimeoutException) {
            throw PrinterStatusQueryException(
                "プリンターから状態応答がありません。状態方式、オフライン、通信設定を確認してください",
                error,
            )
        } catch (error: PrinterStatusQueryException) {
            throw error
        } catch (error: Throwable) {
            throw PrinterStatusQueryException(
                "プリンター状態を取得できませんでした：${error.message ?: error.javaClass.simpleName}",
                error,
            )
        }
        PrinterRealtimeStatusProtocol.parse(response, checkedAt, elapsed)
    }
}
