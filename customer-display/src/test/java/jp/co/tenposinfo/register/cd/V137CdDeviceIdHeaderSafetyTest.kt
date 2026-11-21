package jp.co.tenposinfo.register.cd

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class V137CdDeviceIdHeaderSafetyTest {
    @Test
    fun acceptOnlyUuidHeaders() {
        assertTrue(CustomerDisplayDeviceIdPolicy.isValid("00000000-0000-0000-0000-000000000001"))
        assertTrue(CustomerDisplayDeviceIdPolicy.isValid("AAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE"))
        listOf(
            "", "A".repeat(36), "-".repeat(36), "short",
            "00000000-0000-0000-0000-000000000001\r\nInjected: yes",
            "00000000000000000000000000000000",
            "00000000-0000-0000-0000-00000000000Z",
        ).forEach { assertFalse("Rejected ID: $it", CustomerDisplayDeviceIdPolicy.isValid(it)) }
    }

    @Test
    fun persistedIdentityCannotBeReplacedByPairingSettingsAndMaliciousIdIsNotSent() {
        val settings = File("src/main/java/jp/co/tenposinfo/register/cd/CustomerDisplayConnectionSettings.kt").readText()
        assertTrue(settings.contains(".putString(KEY_DISPLAY_DEVICE_ID, persistentId())"))
        assertTrue(settings.contains("CustomerDisplayDeviceIdPolicy.isValid(it)"))
        val client = File("src/main/java/jp/co/tenposinfo/register/cd/CustomerDisplayWebSocketClient.kt").readText()
        assertTrue(client.contains("if (CustomerDisplayDeviceIdPolicy.isValid(settings.displayDeviceId))"))
    }
}
