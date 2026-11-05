package jp.co.tenposinfo.register.cd

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class V137CanonicalModeRenderTest {
    @Test
    fun canonicalChangeOverridesLegacyCompleteAndSurvivesCacheRoundTrip() {
        val source = CustomerDisplaySnapshot.initial().copy(
            mode = CustomerDisplayMode.COMPLETE,
            canonicalModeWire = "CHANGE",
            changeAmount = 700L,
        )
        val parsed = CustomerDisplaySnapshot.parse(source.toJson())
        assertEquals(CustomerDisplayMode.COMPLETE, parsed.mode)
        assertEquals("CHANGE", parsed.canonicalMode)
        assertEquals("CHANGE", JSONObject(parsed.toJson()).getString("canonicalMode"))
        assertEquals(700L, parsed.changeAmount)
    }

    @Test
    fun newCashReceivedModeIsRepresentedSeparatelyFromLegacyAccounting() {
        val legacy = CustomerDisplaySnapshot.initial().copy(mode = CustomerDisplayMode.ACCOUNTING)
        val source = legacy.copy(canonicalModeWire = "CASH_RECEIVED")
        assertEquals("PAYMENT", legacy.canonicalMode)
        assertEquals("CASH_RECEIVED", CustomerDisplaySnapshot.parse(source.toJson()).canonicalMode)
    }


    @Test
    fun formalCashReceivedModeWithoutCompatibilityAliasRemainsCashReceived() {
        val json = JSONObject(CustomerDisplaySnapshot.initial().toJson())
        json.remove("canonicalMode")
        json.put("mode", "CASH_RECEIVED")
        val parsed = CustomerDisplaySnapshot.parse(json.toString())
        assertEquals(CustomerDisplayMode.ACCOUNTING, parsed.mode)
        assertEquals("CASH_RECEIVED", parsed.canonicalMode)
    }

    @Test
    fun legacyCompletedSaleStillShowsChangeDuringOriginalCompletion() {
        val json = JSONObject(CustomerDisplaySnapshot.initial().copy(
            mode = CustomerDisplayMode.COMPLETE,
            changeAmount = 700L,
        ).toJson())
        json.remove("canonicalMode")
        val parsed = CustomerDisplaySnapshot.parse(json.toString())
        assertEquals("CHANGE", parsed.canonicalMode)
    }

    @Test
    fun unsupportedCanonicalModeMustNotFallbackToStaleAmount() {
        val root = JSONObject(CustomerDisplaySnapshot.initial().toJson())
        root.put("canonicalMode", "UNKNOWN")
        assertTrue(runCatching { CustomerDisplaySnapshot.parse(root.toString()) }.isFailure)
    }
}
