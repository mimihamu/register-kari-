package jp.co.tenposinfo.register

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class V013CustomerDisplayPairingTest {
    private val config = CustomerDisplayServerConfig(
        enabled = true,
        port = 18080,
        path = CUSTOMER_DISPLAY_PATH,
        token = "0123456789abcdef0123456789abcdef",
        storeName = "サンプル居酒屋",
        completeSeconds = 5,
    )

    @Test
    fun pairingWindowIsLimitedToTwoMinutes() {
        assertEquals(120_000L, CUSTOMER_DISPLAY_PAIRING_WINDOW_MS)
    }

    @Test
    fun advertisedMetadataContainsRequiredConnectionFields() {
        val attributes = CustomerDisplayPairingCodec.attributes(config)

        assertEquals(CUSTOMER_DISPLAY_SCHEMA_VERSION.toString(), attributes[CustomerDisplayPairingCodec.ATTRIBUTE_SCHEMA])
        assertEquals("サンプル居酒屋", attributes[CustomerDisplayPairingCodec.ATTRIBUTE_STORE])
        assertEquals(CUSTOMER_DISPLAY_PATH, attributes[CustomerDisplayPairingCodec.ATTRIBUTE_PATH])
        assertEquals(config.token, attributes[CustomerDisplayPairingCodec.ATTRIBUTE_TOKEN])
    }

    @Test
    fun pairingUsesDedicatedDnsSdServiceType() {
        assertEquals("_tsuguregi-cd._tcp.", CUSTOMER_DISPLAY_PAIRING_SERVICE_TYPE)
        assertTrue(CustomerDisplayPairingCodec.serviceName("サンプル居酒屋").startsWith("つぐレジ-"))
    }
}
