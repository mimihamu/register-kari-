package jp.co.tenposinfo.register.plus

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import org.json.JSONObject
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Lightweight start-up check, never a foreground sales or checkout dialog.
 * The installer always remains an explicit user decision in AppDistributionUpdateActivityV146.
 */
internal object AppDistributionUpdateNoticeV147 {
    private const val PREFS = "tsuguregi_update_notice_v147"
    private const val CHANNEL = "tsuguregi_apk_updates"
    private const val NOTIFICATION_ID = 147
    private const val SUCCESS_INTERVAL = 24 * 60 * 60 * 1000L
    private const val RETRY_INTERVAL = 6 * 60 * 60 * 1000L
    private val running = AtomicBoolean(false)

    fun enabled(context: Context): Boolean =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean("auto_check", true)

    fun setEnabled(context: Context, checked: Boolean) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean("auto_check", checked).apply()
    }

    fun lastAvailable(context: Context): String? =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("available_name", null)

    fun maybeCheck(context: Context, app: String, permitted: Boolean = true) {
        if (!permitted || !enabled(context)) return
        val appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val last = prefs.getLong("last_attempt", 0)
        val interval = if (prefs.getBoolean("last_succeeded", false)) SUCCESS_INTERVAL else RETRY_INTERVAL
        if (last > 0 && now >= last && now - last < interval) return
        if (!running.compareAndSet(false, true)) return
        prefs.edit().putLong("last_attempt", now).apply()

        Thread({
            try {
                val channel = if (appContext.packageName.endsWith(".dev")) "dev" else "stable"
                val url = URL("https://tenpos.jp/support2/update/tsuguregi/api/latest.php?app=$app&track=$channel")
                if (url.protocol != "https" || url.host != "tenpos.jp" || url.port != -1) return@Thread
                val conn = url.openConnection() as HttpsURLConnection
                conn.instanceFollowRedirects = false
                conn.connectTimeout = 10_000
                conn.readTimeout = 12_000
                try {
                    if (conn.responseCode != 200) return@Thread
                    val payload = conn.inputStream.use { input ->
                        val out = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(4096)
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            if (out.size() + n > 65_536) error("Response too large")
                            out.write(buffer, 0, n)
                        }
                        out.toString("UTF-8")
                    }
                    val json = JSONObject(payload)
                    if (!json.optBoolean("success") ||
                        json.optString("app") != app ||
                        json.optString("channel") != channel ||
                        json.optString("packageName") != appContext.packageName) return@Thread
                    val code = json.optLong("versionCode", -1L)
                    val name = json.optString("versionName").take(64)
                    val apkUrl = URL(json.optString("apkUrl"))
                    if (apkUrl.protocol != "https" || apkUrl.host != "tenpos.jp" ||
                        apkUrl.port != -1 || apkUrl.userInfo != null ||
                        !apkUrl.path.startsWith("/support2/update/tsuguregi/downloads/$app/") ||
                        !apkUrl.path.endsWith(".apk") || apkUrl.path.contains("..") ||
                        !json.optString("sha256").matches(Regex("[a-fA-F0-9]{64}")) ||
                        json.optLong("size") !in 1024L..125_829_120L ||
                        code <= 0 || name.isBlank()) return@Thread

                    prefs.edit().putBoolean("last_succeeded", true).apply()
                    @Suppress("DEPRECATION")
                    val installedInfo = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
                    @Suppress("DEPRECATION")
                    val installedCode = if (Build.VERSION.SDK_INT >= 28)
                        installedInfo.longVersionCode else installedInfo.versionCode.toLong()
                    if (code > installedCode) {
                        prefs.edit().putString("available_name", name)
                            .putLong("available_code", code).apply()
                        notifyAvailable(appContext, name)
                    } else {
                        prefs.edit().remove("available_name").remove("available_code").apply()
                        (appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                            .cancel(NOTIFICATION_ID)
                    }
                } finally { conn.disconnect() }
            } catch (_: Exception) {
                // Offline, invalid or unconfigured server: never interrupt selling or customer display.
            } finally {
                running.set(false)
            }
        }, "tsugu-update-check").start()
    }

    private fun notifyAvailable(context: Context, name: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (!manager.areNotificationsEnabled()) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "つぐレジ アプリ更新", NotificationManager.IMPORTANCE_LOW),
        )
        val intent = Intent(context, AppDistributionUpdateActivityV146::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            context, NOTIFICATION_ID, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("新しいアプリがあります")
            .setContentText("バージョン $name")
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }
}
