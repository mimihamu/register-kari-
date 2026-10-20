package jp.co.tenposinfo.register

import java.net.InetAddress
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class V137CustomerDisplayInboundLanAccessTest {
    @Test
    fun localPrivateAndLinkLocalPeersAreAllowed() {
        listOf(
            "127.0.0.1", "::1", "10.0.1.200", "10.255.255.254",
            "172.16.0.5", "172.31.255.254", "192.168.1.24",
            "169.254.8.10", "fe80::1234",
        ).forEach { literal ->
            assertTrue("Expected local peer $literal", CustomerDisplayServerPeerPolicy.allows(
                InetAddress.getByName(literal),
            ))
        }
    }

    @Test
    fun rejectPublicCgnatUnspecifiedAndNonLocalIpv6() {
        listOf(
            "8.8.8.8", "1.1.1.1", "100.64.2.1", "172.15.255.255",
            "172.32.0.1", "192.0.2.1", "192.167.1.1", "0.0.0.0",
            "224.0.0.1", "2001:4860:4860::8888", "fc00::1234", "::",
        ).forEach { literal ->
            assertFalse("Reject remote peer $literal", CustomerDisplayServerPeerPolicy.allows(
                InetAddress.getByName(literal),
            ))
        }
    }

    @Test
    fun absentSourceAddressRejected() {
        assertFalse(CustomerDisplayServerPeerPolicy.allows(null))
    }
}
