package jp.co.tenposinfo.register

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class V137PairingWindowDeadlineTest {
    @Test
    fun delayedRegistrationDoesNotExtendTokenAdvertisementBeyondDeadline() {
        val start = 10_000L
        val end = start + CUSTOMER_DISPLAY_PAIRING_WINDOW_MS
        assertEquals(120_000L, CustomerDisplayPairingWindowPolicy.remainingMillis(start, end))
        assertEquals(20_000L, CustomerDisplayPairingWindowPolicy.remainingMillis(start + 100_000L, end))
        assertEquals(0L, CustomerDisplayPairingWindowPolicy.remainingMillis(end, end))
        assertEquals(0L, CustomerDisplayPairingWindowPolicy.remainingMillis(end + 10_000L, end))
    }
    @Test
    fun listenerCallbackCannotRestartTwoMinuteTimerAndOldUnregistrationCannotClearNewState() {
        val source = File("src/main/java/jp/co/tenposinfo/register/CustomerDisplayPairingNsd.kt").readText()
        val start = source.substringAfter("fun start(config: CustomerDisplayServerConfig)")
        assertTrue(start.indexOf("handler.postDelayed(stopRunnable, CUSTOMER_DISPLAY_PAIRING_WINDOW_MS)") <
            start.indexOf("nsdManager.registerService"))
        val callback = source.substringAfter("override fun onServiceRegistered")
            .substringBefore("override fun onRegistrationFailed")
        assertTrue(!callback.contains("handler.postDelayed(stopRunnable"))
        assertTrue(callback.contains("CustomerDisplayPairingWindowPolicy.remainingMillis"))
        assertTrue(source.contains("if (listener === this) registered = false"))
    }
}
