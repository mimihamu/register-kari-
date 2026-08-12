package jp.co.tenposinfo.register

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class V103CustomerDisplayConnectionLivenessTest {
    private val root = File(System.getProperty("user.dir")).let { current ->
        if (File(current, "app").isDirectory) current else current.parentFile
    }

    @Test
    fun staleBoundaryClosesOnlyAfterFortyFiveSecondsWithoutTransportActivity() {
        val lastActivity = 1_000_000L
        assertFalse(
            CustomerDisplayConnectionLivenessPolicy.shouldClose(
                nowMillis = lastActivity + CustomerDisplayConnectionLivenessPolicy.STALE_AFTER_MS - 1L,
                lastTransportActivityAtMillis = lastActivity,
            ),
        )
        assertTrue(
            CustomerDisplayConnectionLivenessPolicy.shouldClose(
                nowMillis = lastActivity + CustomerDisplayConnectionLivenessPolicy.STALE_AFTER_MS,
                lastTransportActivityAtMillis = lastActivity,
            ),
        )
    }

    @Test
    fun livenessIntervalsStayCompatibleWithExistingCdHeartbeat() {
        assertTrue(CustomerDisplayConnectionLivenessPolicy.HANDSHAKE_TIMEOUT_MS == 5_000)
        assertTrue(CustomerDisplayConnectionLivenessPolicy.LIVENESS_CHECK_INTERVAL_MS == 15_000)
        assertTrue(CustomerDisplayConnectionLivenessPolicy.STALE_AFTER_MS == 45_000L)

        val clientSource = File(
            root,
            "customer-display/src/main/java/jp/co/tenposinfo/register/cd/CustomerDisplayWebSocketClient.kt",
        ).readText()
        assertTrue(clientSource.contains("const val HEARTBEAT_INTERVAL_MS = 15_000"))
        assertTrue(clientSource.contains("writeMaskedFrame(output, 0x9, HEARTBEAT_PAYLOAD)"))
    }

    @Test
    fun serverUsesFiniteHandshakeAndPostHandshakeReadTimeouts() {
        val source = serverSource()
        assertTrue(
            source.contains(
                "soTimeout = CustomerDisplayConnectionLivenessPolicy.HANDSHAKE_TIMEOUT_MS",
            ),
        )
        assertTrue(
            source.contains(
                "socket.soTimeout = CustomerDisplayConnectionLivenessPolicy.LIVENESS_CHECK_INTERVAL_MS",
            ),
        )
        assertFalse(source.contains("soTimeout = 0"))
    }

    @Test
    fun frameBoundaryTimeoutChecksTransportStalenessWithoutTakingWriterLock() {
        val source = serverSource()
        val readStart = source.indexOf("private fun readClientFrames")
        val requestStart = source.indexOf("private data class HttpRequest", readStart)
        assertTrue(readStart >= 0)
        assertTrue(requestStart > readStart)
        val readBody = source.substring(readStart, requestStart)
        assertTrue(readBody.contains("catch (_: SocketTimeoutException)"))
        assertTrue(readBody.contains("connection.isTransportStale(System.currentTimeMillis())"))
        assertTrue(readBody.contains("throw EOFException(\"customer display transport inactive\")"))
        assertFalse(readBody.contains("writeLock"))
    }

    @Test
    fun inboundAndSuccessfulOutboundTrafficRefreshTransportActivity() {
        val source = serverSource()
        assertTrue(source.contains("connection.markInboundActivity()"))
        assertTrue(source.contains("writeFrame(socket.getOutputStream(), 0x1"))
        assertTrue(source.contains("writeFrame(socket.getOutputStream(), opcode"))
        assertTrue(Regex("writeFrame\\(socket\\.getOutputStream\\(\\), 0x1[\\s\\S]*?markTransportActivity\\(\\)").containsMatchIn(source))
        assertTrue(Regex("writeFrame\\(socket\\.getOutputStream\\(\\), opcode[\\s\\S]*?markTransportActivity\\(\\)").containsMatchIn(source))
    }

    @Test
    fun releaseIdentityAndPhysicalVerificationDeferralAreExplicit() {
        val gradle = File(root, "app/build.gradle.kts").readText()
        assertTrue(gradle.contains("versionCode = 133"))
        assertTrue(gradle.contains("versionName = \"1.03.0-dev.1\""))

        val requirements = File(root, "docs/V1.03_CUSTOMER_DISPLAY_CONNECTION_LIVENESS.md")
        val notes = File(root, "docs/V1.03_RELEASE_NOTES.md")
        assertTrue(requirements.isFile)
        assertTrue(notes.isFile)
        assertTrue(requirements.readText().contains("最終総合実機試験"))
        assertTrue(notes.readText().contains("最終総合実機試験"))
    }

    private fun serverSource(): String = File(
        root,
        "app/src/main/java/jp/co/tenposinfo/register/CustomerDisplayWebSocketServer.kt",
    ).readText()
}
