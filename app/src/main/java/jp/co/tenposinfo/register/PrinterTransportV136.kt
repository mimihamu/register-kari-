package jp.co.tenposinfo.register

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.core.content.ContextCompat
import java.io.IOException
import java.util.UUID

/**
 * Formal v2.5 §16.1 / §16.2 / H.10 transport contract.
 * TCP 9100 remains the default for backward compatibility; USB and classic
 * Bluetooth RFCOMM use the same PrinterGateway and print-result safety model.
 */
object PrinterTransportPolicyV136 {
    fun endpointDisplay(configuration: PrinterConfiguration): String = when (configuration.connectionType) {
        PrinterConnectionTypeV136.TCP_9100 -> configuration.host.trim().let { host ->
            if (host.isBlank()) "" else "$host:${configuration.port}"
        }
        PrinterConnectionTypeV136.USB -> configuration.usbDeviceName.trim()
        PrinterConnectionTypeV136.BLUETOOTH -> configuration.bluetoothAddress.trim().uppercase()
    }

    fun endpointKey(configuration: PrinterConfiguration): String = when (configuration.connectionType) {
        PrinterConnectionTypeV136.TCP_9100 ->
            "tcp:${configuration.host.trim().lowercase()}:${configuration.port}"
        PrinterConnectionTypeV136.USB ->
            "usb:${configuration.usbDeviceName.trim()}"
        PrinterConnectionTypeV136.BLUETOOTH ->
            "bluetooth:${configuration.bluetoothAddress.trim().uppercase()}"
    }

    fun isConfigured(configuration: PrinterConfiguration): Boolean = when (configuration.connectionType) {
        PrinterConnectionTypeV136.TCP_9100 ->
            configuration.host.isNotBlank() && configuration.port in 1..65535
        PrinterConnectionTypeV136.USB ->
            configuration.usbDeviceName.isNotBlank()
        PrinterConnectionTypeV136.BLUETOOTH ->
            configuration.bluetoothAddress.matches(BLUETOOTH_ADDRESS)
    }

    fun validate(configuration: PrinterConfiguration) {
        when (configuration.connectionType) {
            PrinterConnectionTypeV136.TCP_9100 -> {
                require(configuration.host.isNotBlank()) { "IPアドレスまたはホスト名を入力してください" }
                require(configuration.port in 1..65535) { "ポート番号は1～65535で入力してください" }
            }
            PrinterConnectionTypeV136.USB -> {
                require(configuration.usbDeviceName.isNotBlank()) { "USB機器名を入力してください" }
            }
            PrinterConnectionTypeV136.BLUETOOTH -> {
                require(configuration.bluetoothAddress.matches(BLUETOOTH_ADDRESS)) {
                    "BluetoothアドレスをAA:BB:CC:DD:EE:FF形式で入力してください"
                }
            }
        }
    }

    fun supportsRealtimeStatus(configuration: PrinterConfiguration): Boolean =
        configuration.connectionType == PrinterConnectionTypeV136.TCP_9100

    fun requiresNetwork(configuration: PrinterConfiguration): Boolean =
        configuration.connectionType == PrinterConnectionTypeV136.TCP_9100

    private val BLUETOOTH_ADDRESS = Regex("(?i)^[0-9A-F]{2}(:[0-9A-F]{2}){5}$")
}

object PrinterGatewayFactoryV136 {
    fun create(context: Context, configuration: PrinterConfiguration): PrinterGateway {
        PrinterTransportPolicyV136.validate(configuration)
        return when (configuration.connectionType) {
            PrinterConnectionTypeV136.TCP_9100 -> TcpEscPosPrinterGateway(
                host = configuration.host.trim(),
                port = configuration.port,
                timeoutMillis = configuration.timeoutMillis,
            )
            PrinterConnectionTypeV136.USB -> UsbEscPosPrinterGatewayV136(
                context = context.applicationContext,
                configuration = configuration,
            )
            PrinterConnectionTypeV136.BLUETOOTH -> BluetoothEscPosPrinterGatewayV136(
                context = context.applicationContext,
                configuration = configuration,
            )
        }
    }
}

private data class UsbBulkTargetV136(
    val device: UsbDevice,
    val usbInterface: UsbInterface,
    val endpoint: UsbEndpoint,
)

class UsbEscPosPrinterGatewayV136(
    context: Context,
    private val configuration: PrinterConfiguration,
) : PrinterGateway {
    private val appContext = context.applicationContext

    override fun send(payload: ByteArray): Result<Unit> = runCatching {
        PrinterEndpointSendGate.withPermit(
            endpoint = PrinterTransportPolicyV136.endpointKey(configuration),
            waitMillis = configuration.timeoutMillis.toLong(),
        ) {
            sendExclusive(payload)
        }
    }

    private fun sendExclusive(payload: ByteArray) {
        var phase = PrinterDeliveryPhase.CONNECTING
        try {
            val manager = appContext.getSystemService(Context.USB_SERVICE) as UsbManager
            val target = findBulkTarget(manager)
                ?: throw IOException("指定したUSBプリンターが見つかりません：${configuration.usbDeviceName}")
            require(manager.hasPermission(target.device)) {
                "USBプリンターの接続許可がありません。周辺機器設定からUSB接続を許可してください"
            }
            val connection = manager.openDevice(target.device)
                ?: throw IOException("USBプリンターを開けませんでした")
            try {
                if (!connection.claimInterface(target.usbInterface, true)) {
                    throw IOException("USBプリンターのインターフェースを使用できません")
                }
                try {
                    phase = PrinterDeliveryPhase.CONNECTED
                    phase = PrinterDeliveryPhase.WRITE_STARTED
                    var offset = 0
                    while (offset < payload.size) {
                        val length = minOf(16 * 1024, payload.size - offset)
                        val written = connection.bulkTransfer(
                            target.endpoint,
                            payload,
                            offset,
                            length,
                            configuration.timeoutMillis,
                        )
                        if (written <= 0) {
                            throw IOException("USB送信が完了しませんでした（offset=$offset）")
                        }
                        offset += written
                    }
                    phase = PrinterDeliveryPhase.FLUSHED
                } finally {
                    runCatching { connection.releaseInterface(target.usbInterface) }
                }
            } finally {
                connection.close()
            }
        } catch (error: Throwable) {
            if (error is PrinterTransportException) throw error
            throw PrinterTransportException(phase, error)
        }
    }

    private fun findBulkTarget(manager: UsbManager): UsbBulkTargetV136? {
        val cleanName = configuration.usbDeviceName.trim()
        val device = manager.deviceList.values.firstOrNull { it.deviceName == cleanName } ?: return null
        for (interfaceIndex in 0 until device.interfaceCount) {
            val usbInterface = device.getInterface(interfaceIndex)
            for (endpointIndex in 0 until usbInterface.endpointCount) {
                val endpoint = usbInterface.getEndpoint(endpointIndex)
                if (
                    endpoint.direction == UsbConstants.USB_DIR_OUT &&
                    endpoint.type == UsbConstants.USB_ENDPOINT_XFER_BULK
                ) {
                    return UsbBulkTargetV136(device, usbInterface, endpoint)
                }
            }
        }
        return null
    }
}

class BluetoothEscPosPrinterGatewayV136(
    context: Context,
    private val configuration: PrinterConfiguration,
) : PrinterGateway {
    private val appContext = context.applicationContext

    override fun send(payload: ByteArray): Result<Unit> = runCatching {
        PrinterEndpointSendGate.withPermit(
            endpoint = PrinterTransportPolicyV136.endpointKey(configuration),
            waitMillis = configuration.timeoutMillis.toLong(),
        ) {
            sendExclusive(payload)
        }
    }

    private fun sendExclusive(payload: ByteArray) {
        var phase = PrinterDeliveryPhase.CONNECTING
        try {
            if (
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                ContextCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_CONNECT) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                throw SecurityException(
                    "Bluetooth接続権限がありません。周辺機器設定からBluetooth接続を許可してください",
                )
            }
            val manager = appContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            val adapter = manager.adapter ?: throw IOException("Bluetoothを利用できない端末です")
            val device = adapter.getRemoteDevice(configuration.bluetoothAddress.trim())
            val socket = device.createRfcommSocketToServiceRecord(SPP_UUID)
            try {
                socket.connect()
                phase = PrinterDeliveryPhase.CONNECTED
                socket.outputStream.use { stream ->
                    phase = PrinterDeliveryPhase.WRITE_STARTED
                    stream.write(payload)
                    stream.flush()
                    phase = PrinterDeliveryPhase.FLUSHED
                }
            } finally {
                runCatching { socket.close() }
            }
        } catch (error: Throwable) {
            if (error is PrinterTransportException) throw error
            throw PrinterTransportException(phase, error)
        }
    }

    companion object {
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }
}
