package jp.co.tenposinfo.register

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

object AutoBackupFailureNotificationPolicy {
    fun shouldNotify(
        notificationsEnabled: Boolean,
        result: AutoBackupResultState,
    ): Boolean = notificationsEnabled && result in setOf(
        AutoBackupResultState.FAILED,
        AutoBackupResultState.SKIPPED_LOW_STORAGE,
    )
}

object AutoBackupFailureNotificationCoordinator {
    private const val CHANNEL_ID = "data_backup_failures"
    private const val NOTIFICATION_ID = 12_023

    fun clear(context: Context) {
        NotificationManagerCompat.from(context.applicationContext).cancel(NOTIFICATION_ID)
    }

    fun apply(
        context: Context,
        reason: BackupCreationReason,
        result: AutoBackupResultState,
        detail: String?,
    ) {
        val appContext = context.applicationContext
        val settings = AutoBackupSettingsStore(appContext).load()
        if (result == AutoBackupResultState.CREATED) {
            clear(appContext)
            return
        }
        if (!AutoBackupFailureNotificationPolicy.shouldNotify(settings.failureNotificationsEnabled, result)) return
        if (!canPostNotification(appContext)) return
        postNotification(appContext, reason, result, detail)
    }

    private fun canPostNotification(context: Context): Boolean {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }

    private fun postNotification(
        context: Context,
        reason: BackupCreationReason,
        result: AutoBackupResultState,
        detail: String?,
    ) {
        createChannel(context)
        val intent = Intent(context, DataProtectionActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            23,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val lowStorage = result == AutoBackupResultState.SKIPPED_LOW_STORAGE
        val title = if (lowStorage) "つぐレジ：バックアップを容量不足で中止" else "つぐレジ：バックアップに失敗"
        val instruction = if (lowStorage) {
            "端末の空き容量を増やして、データ保全画面から再実行してください。"
        } else {
            "データ保全画面を開き、DB診断とエラー詳細を確認してください。"
        }
        val body = "${reason.displayName} / ${detail.orEmpty().ifBlank { result.displayName }}"
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$body\n$instruction"))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .build()
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    }

    private fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "データバックアップ異常",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "自動バックアップの失敗または容量不足を管理者へ通知"
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
}
