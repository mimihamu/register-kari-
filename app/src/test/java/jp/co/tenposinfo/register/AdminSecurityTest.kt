package jp.co.tenposinfo.register

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdminSecurityTest {
    @Test
    fun pinIsSaltedHashedAndVerified() {
        val first = PinSecurity.encode("2468")
        val second = PinSecurity.encode("2468")

        assertNotEquals(first.salt, second.salt)
        assertNotEquals(first.hash, second.hash)
        assertTrue(PinSecurity.verify("2468", first.salt, first.hash))
        assertFalse(PinSecurity.verify("2469", first.salt, first.hash))
    }

    @Test(expected = IllegalArgumentException::class)
    fun pinRejectsNonNumericValue() {
        PinSecurity.encode("12ab")
    }

    @Test
    fun cashierAndManagerHaveDifferentDefaultPermissions() {
        val cashier = OperatorPermissionPolicy.defaults(OperatorRole.CASHIER)
        val manager = OperatorPermissionPolicy.defaults(OperatorRole.MANAGER)

        assertTrue(RegisterPermission.SALES in cashier)
        assertFalse(RegisterPermission.SETTINGS in cashier)
        assertTrue(RegisterPermission.SETTINGS in manager)
        assertTrue(RegisterPermission.REVERSAL in manager)
    }

    @Test
    fun printerIsUsableOnlyWhenEnabledAndAddressed() {
        assertFalse(PrinterConfiguration().usable)
        assertFalse(PrinterConfiguration(host = "192.168.1.10", enabled = false).usable)
        assertTrue(PrinterConfiguration(host = "192.168.1.10", port = 9100, enabled = true).usable)
    }
}
