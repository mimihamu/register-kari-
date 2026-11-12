package jp.co.tenposinfo.register.cd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class V137Ipv6DestinationFormattingTest {
    @Test
    fun ipv4HostHeaderAndSocketAddressRemainUnchanged() {
        val connection = CustomerDisplayConnectionSettings(
            host = "192.168.100.5", port = 18080,
            token = "0123456789abcdef", autoConnect = true,
        )
        assertEquals("192.168.100.5", connection.socketHost)
        assertEquals("192.168.100.5:18080", connection.httpHost)
        assertTrue(connection.isConfigured)
    }

    @Test
    fun bracketedIpv6UsesRawSocketLiteralButBracketsInHttpHost() {
        val connection = CustomerDisplayConnectionSettings(
            host = "[fe80::beef]", port = 18080,
            token = "0123456789abcdef", autoConnect = true,
        )
        assertTrue(connection.isConfigured)
        assertEquals("fe80::beef", connection.socketHost)
        assertEquals("[fe80::beef]:18080", connection.httpHost)
        assertEquals("[fe80::beef]:18080$CUSTOMER_DISPLAY_PATH", connection.displayAddress)
    }

    @Test
    fun bareIpv6AlsoGetsProperHostHeaderBrackets() {
        val connection = CustomerDisplayConnectionSettings(
            host = "fe80::1", port = 18080,
            token = "0123456789abcdef", autoConnect = true,
        )
        assertTrue(connection.isConfigured)
        assertEquals("[fe80::1]:18080", connection.httpHost)
    }
}
