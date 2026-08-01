package jp.co.tenposinfo.register

import org.junit.Assert.assertEquals
import org.junit.Test

class V012PrinterNotificationPermissionTest {
    @Test
    fun android13RequiresRuntimePermissionWhenNotGranted() {
        assertEquals(
            PrinterNotificationPermissionState.RUNTIME_PERMISSION_REQUIRED,
            PrinterNotificationPermissionPolicy.evaluate(
                apiLevel = 33,
                runtimePermissionGranted = false,
                notificationsEnabled = true,
            ),
        )
    }

    @Test
    fun grantedPermissionEnablesNotifications() {
        assertEquals(
            PrinterNotificationPermissionState.ENABLED,
            PrinterNotificationPermissionPolicy.evaluate(
                apiLevel = 36,
                runtimePermissionGranted = true,
                notificationsEnabled = true,
            ),
        )
    }

    @Test
    fun preAndroid13DoesNotRequireRuntimePermission() {
        assertEquals(
            PrinterNotificationPermissionState.ENABLED,
            PrinterNotificationPermissionPolicy.evaluate(
                apiLevel = 32,
                runtimePermissionGranted = false,
                notificationsEnabled = true,
            ),
        )
    }

    @Test
    fun systemLevelDisableTakesPriority() {
        assertEquals(
            PrinterNotificationPermissionState.SYSTEM_DISABLED,
            PrinterNotificationPermissionPolicy.evaluate(
                apiLevel = 36,
                runtimePermissionGranted = false,
                notificationsEnabled = false,
            ),
        )
        assertEquals(
            PrinterNotificationPermissionState.SYSTEM_DISABLED,
            PrinterNotificationPermissionPolicy.evaluate(
                apiLevel = 32,
                runtimePermissionGranted = true,
                notificationsEnabled = false,
            ),
        )
    }
}
