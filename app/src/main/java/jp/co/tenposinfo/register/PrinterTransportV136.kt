package jp.co.tenposinfo.register

/**
 * Formal v2.5 §16.1 / §16.2 transport addressing contract.
 * TCP 9100 remains the default for backward compatibility.
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

    private val BLUETOOTH_ADDRESS = Regex("(?i)^[0-9A-F]{2}(:[0-9A-F]{2}){5}$")
}
