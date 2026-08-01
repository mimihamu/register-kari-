package jp.co.tenposinfo.register.cd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets

class CustomerDisplayPairingParserTest {
    private fun attributes(
        schema: String = CUSTOMER_DISPLAY_SCHEMA_VERSION.toString(),
        store: String = "サンプル居酒屋",
        path: String = CUSTOMER_DISPLAY_PATH,
        token: String = "0123456789abcdef0123456789abcdef",
    ): Map<String, ByteArray> = mapOf(
        "schema" to schema.toByteArray(StandardCharsets.UTF_8),
        "store" to store.toByteArray(StandardCharsets.UTF_8),
        "path" to path.toByteArray(StandardCharsets.UTF_8),
        "token" to token.toByteArray(StandardCharsets.UTF_8),
    )

    @Test
    fun validAdvertisementCreatesConnectionSettings() {
        val candidate = CustomerDisplayPairingParser.parse(
            serviceName = "つぐレジ-サンプル居酒屋",
            host = "192.168.1.50",
            port = 18080,
            attributes = attributes(),
        )

        requireNotNull(candidate)
        assertEquals("サンプル居酒屋", candidate.storeName)
        assertEquals("192.168.1.50", candidate.host)
        assertEquals(18080, candidate.port)
        assertEquals(CUSTOMER_DISPLAY_PATH, candidate.path)
        assertTrue(candidate.toConnectionSettings().autoConnect)
        assertTrue(candidate.toConnectionSettings().isConfigured)
    }

    @Test
    fun wrongSchemaIsRejected() {
        assertNull(
            CustomerDisplayPairingParser.parse(
                serviceName = "つぐレジ",
                host = "192.168.1.50",
                port = 18080,
                attributes = attributes(schema = "99"),
            ),
        )
    }

    @Test
    fun wrongPathIsRejected() {
        assertNull(
            CustomerDisplayPairingParser.parse(
                serviceName = "つぐレジ",
                host = "192.168.1.50",
                port = 18080,
                attributes = attributes(path = "/wrong"),
            ),
        )
    }

    @Test
    fun shortTokenIsRejected() {
        assertNull(
            CustomerDisplayPairingParser.parse(
                serviceName = "つぐレジ",
                host = "192.168.1.50",
                port = 18080,
                attributes = attributes(token = "short"),
            ),
        )
    }

    @Test
    fun discoveryTimeoutIsBounded() {
        assertEquals(12_000L, CUSTOMER_DISPLAY_DISCOVERY_TIMEOUT_MS)
    }
}
