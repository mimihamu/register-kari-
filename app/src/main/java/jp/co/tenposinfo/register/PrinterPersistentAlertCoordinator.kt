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

/**
 * 販売画面の状態監視から継続異常を管理者向けにエスカレーションする。
 * 状態取得の警告であり、印刷失敗や印刷キューの再送可否は変更しない。
 */
object PrinterPersistentAlertCoordinator {
    private const val PREFS_NAME = "printer_persistent_alert"
    private const val KEY_INCIDENT_STARTED_AT = "incident_started_at"
    private const val KEY_LAST_NOTIFIED_AT = "last_notified_at"
    private const val CHANNEL_ID = "printer_persistent_alerts"
    private const val NOTIFICATION_ID = 12_013

    fun apply(
        context: Context,
        snapshot: PrinterHealthSnapshot,
        nowMillis: Long = System.currentTimeMillis(),
    ): PrinterHealthSnapshot {
        val appContext = context.applicationContext
        val preferences = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val previous = PrinterPersistentAlertState(
            incidentStartedAt = preferences.getLong(KEY_INCIDENT_STARTED_AT, 0L),
            lastNotifiedAt = preferences.getLong(KEY_LAST_NOTIFIED_AT, 0L),
        )
        val decision = PrinterPersistentAlertPolicy.evaluate(previous, snapshot.level, nowMillis)

        if (!decision.active) {
            if (decision.clearNotification) {
                preferences.edit().clear().apply()
                NotificationManagerCompat.from(appContext).cancel(NOTIFICATION_ID)
            }
            return snapshot
        }

        var lastNotifiedAt = previous.lastNotifiedAt
        val notificationAllowed = canPostNotification(appContext)
        if (decision.notificationDue && notificationAllowed && postNotification(appContext, snapshot, decision.durationMillis)) {
            lastNotifiedAt = nowMillis
        }
        preferences.edit()
            .putLong(KEY_INCIDENT_STARTED_AT, decision.incidentStartedAt)
            .putLong(KEY_LAST_NOTIFIED_AT, lastNotifiedAt)
            .apply()

        val duration = durationLabel(decision.durationMillis)
        return if (decision.durationMillis >= PrinterPersistentAlertPolicy.ALERT_AFTER_MILLIS) {
            val notificationState = if (notificationAllowed) {
                "管理者通知済み"
            } else {
                "通知権限なし・画面で警告中"
            }
            snapshot.copy(
                title = "管理者確認：${snapshot.title}",
                detail = "${snapshot.detail} / 異常継続 $duration / $notificationState",
            )
        } else {
            snapshot.copy(
                detail = "${snapshot.detail} / 異常継続 $duration（1分で管理者通知）",
            )
        }
    }

    private fun canPostNotification(context: Context): Boolean {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }

    private fun postNotification(
        context: Context,
        snapshot: PrinterHealthSnapshot,
        durationMillis: Long,
    ): Boolean = runCatching {
        createChannel(context)
        val intent = Intent(context, PrinterStatusActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("つぐレジ：プリンター異常が継続")
            .setContentText("${snapshot.printerName} / ${snapshot.title} / ${durationLabel(durationMillis)}")
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "${snapshot.printerName}の異常が${durationLabel(durationMillis)}継続しています。" +
                        "プリンター診断を開き、用紙・カバー・LAN接続を確認してください。",
                ),
            )
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .build()
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        true
    }.getOrDefault(false)

    private fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "プリンター継続異常",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "プリンター異常が一定時間継続した場合の管理者向け通知"
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun durationLabel(durationMillis: Long): String {
        val totalSeconds = (durationMillis.coerceAtLeast(0L) / 1_000L)
        return if (totalSeconds < 60L) {
            "${totalSeconds}秒"
        } else {
            val minutes = totalSeconds / 60L
            val seconds = totalSeconds % 60L
            if (seconds == 0L) "${minutes}分" else "${minutes}分${seconds}秒"
        }
    }
}
