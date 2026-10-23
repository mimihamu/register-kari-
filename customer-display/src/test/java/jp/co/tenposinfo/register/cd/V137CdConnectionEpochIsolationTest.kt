package jp.co.tenposinfo.register.cd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class V137CdConnectionEpochIsolationTest {
    private fun endpoint(host: String, port: Int = 18080, token: String = "0123456789abcdef0123456789abcdef") =
        CustomerDisplayConnectionSettings(host, port, token, true, "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")

    @Test
    fun oldWebSocketCallbacksCannotApplyAfterEndpointSwitch() {
        val guard = CustomerDisplayConnectionEpochGuard()
        val first = guard.start()
        assertTrue(guard.accepts(first))
        guard.invalidateCurrent() // User selected another register on UI thread.
        assertFalse(guard.accepts(first))
        val second = guard.start()
        assertTrue(guard.accepts(second))
        guard.invalidate(first) // Late old effect onDispose must not invalidate new connection.
        assertTrue(guard.accepts(second))
        guard.invalidate(second)
        assertFalse(guard.accepts(second))
    }

    @Test
    fun transportChangesAndCredentialsPartitionDurableSnapshotCache() {
        val original = endpoint("192.168.1.21")
        val same = endpoint("192.168.1.21")
        assertEquals(CustomerDisplaySnapshotTargetPolicy.fingerprint(original),
            CustomerDisplaySnapshotTargetPolicy.fingerprint(same))
        assertTrue(CustomerDisplaySnapshotCachePolicy.belongsTo(
            original, CustomerDisplaySnapshotTargetPolicy.fingerprint(same)))
        for (updated in listOf(
            endpoint("192.168.1.22"), endpoint("192.168.1.21", port = 18081),
            endpoint("192.168.1.21", token = "other-token-0123456789"),
        )) {
            assertNotEquals(CustomerDisplaySnapshotTargetPolicy.fingerprint(original),
                CustomerDisplaySnapshotTargetPolicy.fingerprint(updated))
            assertFalse(CustomerDisplaySnapshotCachePolicy.belongsTo(
                updated, CustomerDisplaySnapshotTargetPolicy.fingerprint(original)))
        }
        assertFalse(CustomerDisplaySnapshotCachePolicy.belongsTo(original, null))
    }

    @Test
    fun cacheWriteOccursOnlyAfterAcceptedLiveSnapshotOnMainThread() {
        val source = File("src/main/java/jp/co/tenposinfo/register/cd/MainActivity.kt").readText()
        assertTrue(source.contains("val connectionEpoch = remember { CustomerDisplayConnectionEpochGuard() }"))
        assertTrue(source.contains("if (connectionEpoch.accepts(epoch))"))
        assertTrue(source.contains("next.connected && next.snapshot === snapshot"))
        assertTrue(source.contains("snapshotStore.save(snapshot, boundSettings)"))
        assertTrue(source.contains("connectionEpoch.invalidateCurrent()"))
        assertTrue(source.contains("connectionEpoch.invalidate(epoch)"))
    }

    @Test
    fun persistedCacheNeverReturnsDifferentRegisterPreviousSale() {
        val source = File("src/main/java/jp/co/tenposinfo/register/cd/CustomerDisplaySnapshotStore.kt").readText()
        assertTrue(source.contains("CustomerDisplaySnapshotCachePolicy.belongsTo("))
        assertTrue(source.contains("KEY_CONNECTION_FINGERPRINT"))
        assertTrue(source.contains("if (!settings.isConfigured) return"))
    }
}
