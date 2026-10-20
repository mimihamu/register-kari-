package jp.co.tenposinfo.register.cd

import java.nio.charset.StandardCharsets
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class V137PrivateLanDestinationTest {
    private val bearer = "0123456789abcdef0123456789abcdef"

    private fun configured(host: String) = CustomerDisplayConnectionSettings(
        host = host, port = 18080, token = bearer, autoConnect = true,
    )

    @Test
    fun permitsPrivateRfc1918AndLinkLocalIpv4() {
        listOf("10.0.1.200", "10.255.255.254", "172.16.0.1", "172.31.255.254",
            "192.168.1.50", "169.254.10.5").forEach {
            assertTrue("Expected private host $it", CustomerDisplayPrivateLanHostPolicy.isPermitted(it))
            assertTrue(configured(it).isConfigured)
        }
    }

    @Test
    fun rejectsPublicCarrierGradeNatLoopbackMulticastAndNames() {
        listOf("8.8.8.8", "1.1.1.1", "100.64.1.1", "127.0.0.1", "0.0.0.0",
            "172.15.255.255", "172.32.0.1", "192.167.1.1", "224.0.0.1",
            "myshop.example.com", "localhost", "192.168.1.50.example.com",
            "192.168.001.20", "192.168.1.999", "192.168.1.1\\r\\nHost: bad.test",
            "").forEach {
            assertFalse("Must reject unsafe host $it", CustomerDisplayPrivateLanHostPolicy.isPermitted(it))
            assertFalse(configured(it).isConfigured)
        }
    }

    @Test
    fun supportsOnlyLinkLocalIpv6NotGlobalOrLoopback() {
        assertTrue(CustomerDisplayPrivateLanHostPolicy.isPermitted("fe80::1234"))
        assertTrue(CustomerDisplayPrivateLanHostPolicy.isPermitted("[fe80::1234]"))
        assertFalse(CustomerDisplayPrivateLanHostPolicy.isPermitted("2001:4860:4860::8888"))
        assertFalse(CustomerDisplayPrivateLanHostPolicy.isPermitted("::1"))
        assertFalse(CustomerDisplayPrivateLanHostPolicy.isPermitted("fc00::1"))
    }

    @Test
    fun maliciousDiscoveryCannotAddExternalPairingDestination() {
        val attrs = mapOf(
            "schema" to "1".toByteArray(StandardCharsets.UTF_8),
            "store" to "店".toByteArray(StandardCharsets.UTF_8),
            "path" to CUSTOMER_DISPLAY_PATH.toByteArray(StandardCharsets.UTF_8),
            "token" to bearer.toByteArray(StandardCharsets.UTF_8),
        )
        for (host in listOf("8.8.8.8", "example.com", "127.0.0.1", "192.168.0.1.evil.test")) {
            assertNull(CustomerDisplayPairingParser.parse("つぐレジ", host, 18080, attrs))
        }
        assertTrue(CustomerDisplayPairingParser.parse("つぐレジ", "192.168.0.30", 18080, attrs) != null)
    }

    @Test
    fun zoneIdsCannotInjectHttpHostHeaders() {
        listOf(
            "fe80::1%wlan0\r\nInjected: yes",
            "[fe80::1%wlan0\r\nInjected: yes]",
            "fe80::1%",
            "fe80::1%wlan0%evil",
            "fe80::1%zone with spaces",
            "fe80::1%wlan0/../../",
        ).forEach {
            assertFalse("Unsafe IPv6 zone: $it", CustomerDisplayPrivateLanHostPolicy.isPermitted(it))
        }
        assertTrue(CustomerDisplayPrivateLanHostPolicy.isPermitted("fe80::1%wlan0"))
    }

    @Test
    fun shortTokenCannotBypassLanGuard() {
        val short = CustomerDisplayConnectionSettings(
            host = "192.168.1.5", port = 18080, token = "short", autoConnect = true,
        )
        assertFalse(short.isConfigured)
    }
}
