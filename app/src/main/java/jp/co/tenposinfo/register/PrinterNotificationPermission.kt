package jp.co.tenposinfo.register

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

enum class PrinterNotificationPermissionState {
    ENABLED,
    RUNTIME_PERMISSION_REQUIRED,
    SYSTEM_DISABLED,
}

object PrinterNotificationPermissionPolicy {
    const val RUNTIME_PERMISSION_API_LEVEL = 33

    fun evaluate(
        apiLevel: Int,
        runtimePermissionGranted: Boolean,
        notificationsEnabled: Boolean,
    ): PrinterNotificationPermissionState = when {
        !notificationsEnabled -> PrinterNotificationPermissionState.SYSTEM_DISABLED
        apiLevel >= RUNTIME_PERMISSION_API_LEVEL && !runtimePermissionGranted ->
            PrinterNotificationPermissionState.RUNTIME_PERMISSION_REQUIRED
        else -> PrinterNotificationPermissionState.ENABLED
    }
}

object PrinterNotificationPermissionStatus {
    fun read(context: Context): PrinterNotificationPermissionState {
        val appContext = context.applicationContext
        val permissionGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        return PrinterNotificationPermissionPolicy.evaluate(
            apiLevel = Build.VERSION.SDK_INT,
            runtimePermissionGranted = permissionGranted,
            notificationsEnabled = NotificationManagerCompat.from(appContext).areNotificationsEnabled(),
        )
    }
}
