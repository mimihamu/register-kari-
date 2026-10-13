package jp.co.tenposinfo.register

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class V136PrinterTransportTest {
    private fun source(relative: String): String = File(relative).readText()

    @Test
    fun tcpRemainsBackwardCompatibleDefault() {
        val configuration = PrinterConfiguration(host = " Printer.Local ", port = 9100)
        assertEquals(PrinterConnectionTypeV136.TCP_9100, configuration.connectionType)
        assertTrue(PrinterTransportPolicyV136.isConfigured(configuration))
        assertEquals("Printer.Local:9100", PrinterTransportPolicyV136.endpointDisplay(configuration))
        assertEquals("tcp:printer.local:9100", PrinterTransportPolicyV136.endpointKey(configuration))
        PrinterTransportPolicyV136.validate(configuration)
    }

    @Test
    fun usbAndBluetoothHaveStableTransportAddresses() {
        val usb = PrinterConfiguration(
            connectionType = PrinterConnectionTypeV136.USB,
            usbDeviceName = "/dev/bus/usb/001/004",
        )
        val bluetooth = PrinterConfiguration(
            connectionType = PrinterConnectionTypeV136.BLUETOOTH,
            bluetoothAddress = "aa:bb:cc:dd:ee:ff",
        )

        assertTrue(PrinterTransportPolicyV136.isConfigured(usb))
        assertTrue(PrinterTransportPolicyV136.isConfigured(bluetooth))
        assertEquals("/dev/bus/usb/001/004", PrinterTransportPolicyV136.endpointDisplay(usb))
        assertEquals("usb:/dev/bus/usb/001/004", PrinterTransportPolicyV136.endpointKey(usb))
        assertEquals("AA:BB:CC:DD:EE:FF", PrinterTransportPolicyV136.endpointDisplay(bluetooth))
        assertEquals("bluetooth:AA:BB:CC:DD:EE:FF", PrinterTransportPolicyV136.endpointKey(bluetooth))
        PrinterTransportPolicyV136.validate(usb)
        PrinterTransportPolicyV136.validate(bluetooth)
    }

    @Test
    fun invalidTransportAddressesFailClosed() {
        assertFalse(
            PrinterTransportPolicyV136.isConfigured(
                PrinterConfiguration(connectionType = PrinterConnectionTypeV136.USB),
            ),
        )
        assertFalse(
            PrinterTransportPolicyV136.isConfigured(
                PrinterConfiguration(
                    connectionType = PrinterConnectionTypeV136.BLUETOOTH,
                    bluetoothAddress = "not-a-mac",
                ),
            ),
        )
    }

    @Test
    fun profileSnapshotUsesConfiguredTransport() {
        val usb = PrinterConfiguration(
            name = "USBレシート",
            connectionType = PrinterConnectionTypeV136.USB,
            usbDeviceName = "/dev/bus/usb/001/004",
        )
        val bluetooth = PrinterConfiguration(
            name = "BTレシート",
            connectionType = PrinterConnectionTypeV136.BLUETOOTH,
            bluetoothAddress = "AA:BB:CC:DD:EE:FF",
        )

        assertEquals(PrinterConnectionTypeV136.USB, PrinterProfileContractV136.snapshot(usb).connectionType)
        assertEquals("/dev/bus/usb/001/004", PrinterProfileContractV136.snapshot(usb).address)
        assertEquals(PrinterConnectionTypeV136.BLUETOOTH, PrinterProfileContractV136.snapshot(bluetooth).connectionType)
        assertEquals("AA:BB:CC:DD:EE:FF", PrinterProfileContractV136.snapshot(bluetooth).address)
    }

    @Test
    fun allOperationalSendPathsUseTransportFactory() {
        val transport = source("src/main/java/jp/co/tenposinfo/register/PrinterTransportV136.kt")
        val store = source("src/main/java/jp/co/tenposinfo/register/AdminSettingsStore.kt")
        val automatic = source("src/main/java/jp/co/tenposinfo/register/AutomaticPrintWorker.kt")
        val queue = source("src/main/java/jp/co/tenposinfo/register/UnifiedPrintQueue.kt")
        val drawer = source("src/main/java/jp/co/tenposinfo/register/CashDrawerSafetyV136.kt")
        val receipt = source("src/main/java/jp/co/tenposinfo/register/Receipt.kt")

        assertTrue(transport.contains("UsbEscPosPrinterGatewayV136"))
        assertTrue(transport.contains("BluetoothEscPosPrinterGatewayV136"))
        assertTrue(transport.contains("TcpEscPosPrinterGateway("))
        assertTrue(store.contains("PrinterGatewayFactoryV136.create(appContext, configuration)"))
        assertTrue(automatic.contains("PrinterGatewayFactoryV136.create("))
        assertTrue(queue.contains("PrinterGatewayFactoryV136.create("))
        assertTrue(drawer.contains("PrinterGatewayFactoryV136.create(appContext, configuration)"))
        assertTrue(receipt.contains("PrinterGatewayFactoryV136.create(appContext, configuration)"))
    }

    @Test
    fun optionalUsbBluetoothSupportIsDeclaredAndShownInScr660() {
        val manifest = source("src/main/AndroidManifest.xml")
        val ui = source("src/main/java/jp/co/tenposinfo/register/AdminSettingsActivity.kt")

        assertTrue(manifest.contains("android.hardware.usb.host"))
        assertTrue(manifest.contains("android.permission.BLUETOOTH_CONNECT"))
        assertTrue(manifest.contains("android:required=\"false\""))
        assertTrue(ui.contains("PrinterConnectionTypeV136.entries"))
        assertTrue(ui.contains("接続中USB機器を選択・許可"))
        assertTrue(ui.contains("Bluetooth接続を許可"))
        assertTrue(ui.contains("Bluetoothアドレス"))
    }

    @Test
    fun nonTcpAutomaticPrintingDoesNotRequireNetworkOrTcpStatus() {
        val automatic = source("src/main/java/jp/co/tenposinfo/register/AutomaticPrintWorker.kt")
        val queue = source("src/main/java/jp/co/tenposinfo/register/UnifiedPrintQueue.kt")

        assertTrue(automatic.contains("NetworkType.NOT_REQUIRED"))
        assertTrue(automatic.contains("PrinterTransportPolicyV136.supportsRealtimeStatus(configuration)"))
        assertTrue(queue.contains("PrinterTransportPolicyV136.supportsRealtimeStatus(configuration)"))
    }
}
