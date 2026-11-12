package jp.co.tenposinfo.register

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class V137CustomerDisplayIdentitiesTest {
    @Test
    fun posCarriesStableIdentifiersAndPerClientDisplayId() {
        val snapshot = CustomerDisplaySnapshotFactory.standby("店").copy(
            storeId = "store-101", registerTerminalId = "reg-1",
        )
        val legacy = JSONObject(snapshot.toJson())
        assertEquals("store-101", legacy.getString("storeId"))
        assertEquals("reg-1", legacy.getString("registerTerminalId"))
        val new = JSONObject(CustomerDisplayWireNegotiation.payload(
            snapshot.toJson(), true, "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
        ))
        assertEquals("1.0", new.getString("schemaVersion"))
        assertEquals("store-101", new.getString("storeId"))
        assertEquals("reg-1", new.getString("registerTerminalId"))
        assertEquals("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee", new.getString("displayDeviceId"))
        assertFalse(legacy.has("displayDeviceId"))
    }

    @Test
    fun simultaneousDisplaysReceiveTheirOwnIdentityOnly() {
        val payload = CustomerDisplaySnapshotFactory.standby("店").toJson()
        val one = JSONObject(CustomerDisplayWireNegotiation.payload(
            payload, true, "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
        ))
        val two = JSONObject(CustomerDisplayWireNegotiation.payload(
            payload, true, "ffffffff-bbbb-cccc-dddd-eeeeeeeeeeee",
        ))
        assertEquals("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee", one.getString("displayDeviceId"))
        assertEquals("ffffffff-bbbb-cccc-dddd-eeeeeeeeeeee", two.getString("displayDeviceId"))
        assertTrue(JSONObject(payload).isNull("storeId"))
    }
}
