package jp.co.tenposinfo.register

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import org.json.JSONObject
import java.io.File
import java.net.URL
import java.security.MessageDigest
import javax.net.ssl.HttpsURLConnection
import java.util.concurrent.atomic.AtomicBoolean

/** 明示操作で更新し、公式HTTPS、ハッシュ、APK ID、版、署名を照合してからOSに引き渡す。 */
class AppDistributionUpdateActivityV146 : Activity() {
    private val running = AtomicBoolean(false)
    private var pendingAfterPermission: File? = null
    private lateinit var info: TextView
    private lateinit var check: Button
    private lateinit var progress: ProgressBar
    private val appKey = "pos"
    private val baseUrl = "https://tenpos.jp/support2/update/tsuguregi/"
    private val maxApkBytes = 125_829_120L
    private data class Release(
        val packageName: String, val code: Long, val name: String,
        val apkUrl: String, val sha256: String, val size: Long, val message: String,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        window.statusBarColor = Color.rgb(23, 63, 107)
        @Suppress("DEPRECATION")
        window.navigationBarColor = Color.rgb(23, 63, 107)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        // Pin the full-bleed navy bar above the scroll body. Never inset the bar
        // with form padding: on compact tablets it looks like a white toolbar.
        val screen = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(23, 63, 107))
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.rgb(23, 63, 107))
        }
        header.addView(Button(this).apply {
            text = "戻る"
            contentDescription = "アプリ更新画面を閉じる"
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(dp(102), dp(52)).apply { leftMargin = dp(10) })
        header.addView(TextView(this).apply {
            text = "アプリの更新"
            textSize = 23f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), 0, dp(12), 0)
            setTypeface(null, android.graphics.Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, dp(62), 1f))
        screen.addView(header, LinearLayout.LayoutParams(-1, dp(62)))

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(20))
            setBackgroundColor(Color.rgb(247, 249, 252))
        }
        info = TextView(this).apply {
            text = buildString {
                append("現在のバージョン：").append(currentName())
                AppDistributionUpdateNoticeV147.lastAvailable(this@AppDistributionUpdateActivityV146)
                    ?.let { append("\n更新候補：").append(it) }
            }
            textSize = 17f
            setTextColor(Color.rgb(35, 55, 75))
            setPadding(0, dp(14), 0, dp(20))
        }
        root.addView(info, LinearLayout.LayoutParams(-1, -2))
        progress = ProgressBar(this).apply { visibility = View.GONE }
        root.addView(progress, LinearLayout.LayoutParams(dp(42), dp(42)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
        })
        check = Button(this).apply {
            text = "更新を確認"
            setOnClickListener { checkUpdate() }
        }
        root.addView(check, LinearLayout.LayoutParams(-1, dp(60)))
        if (appKey != "pos") {
            root.addView(android.widget.CheckBox(this).apply {
                text = "起動時に更新を確認"
                isChecked = AppDistributionUpdateNoticeV147.enabled(this@AppDistributionUpdateActivityV146)
                setOnCheckedChangeListener { _, checked ->
                    AppDistributionUpdateNoticeV147.setEnabled(this@AppDistributionUpdateActivityV146, checked)
                }
            }, LinearLayout.LayoutParams(-1, dp(52)))
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            root.addView(Button(this).apply {
                text = "更新通知を許可"
                setOnClickListener {
                    requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 147)
                }
            }, LinearLayout.LayoutParams(-1, dp(52)))
        }
        // Android 15+ system insets belong on the screen shell so the pinned
        // header remains below system icons while only the form can scroll.
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(Color.rgb(247, 249, 252))
            addView(root)
        }
        screen.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        ViewCompat.setOnApplyWindowInsetsListener(screen) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        setContentView(screen)
        ViewCompat.requestApplyInsets(screen)
    }

    override fun onResume() {
        super.onResume()
        val pending = pendingAfterPermission
        if (pending != null && pending.isFile && packageManager.canRequestPackageInstalls()) {
            pendingAfterPermission = null
            launchInstaller(pending)
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    @Suppress("DEPRECATION")
    private fun selfInfo(flags: Int = 0) = packageManager.getPackageInfo(packageName, flags)
    private fun currentName() = selfInfo().versionName.orEmpty()
    @Suppress("DEPRECATION")
    private fun currentCode(): Long = selfInfo().let {
        if (Build.VERSION.SDK_INT >= 28) it.longVersionCode else it.versionCode.toLong()
    }
    private fun track() = if (packageName.endsWith(".dev")) "dev" else "stable"
    private fun display(value: String, busy: Boolean) {
        if (isFinishing || isDestroyed) return
        info.text = value
        check.isEnabled = !busy
        progress.visibility = if (busy) View.VISIBLE else View.GONE
        if (!busy) running.set(false)
    }

    private fun checkUpdate() {
        if (!running.compareAndSet(false, true)) return
        display("更新情報を確認中…", true)
        Thread {
            val result = runCatching { latest() }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                result.fold(
                    onSuccess = { item ->
                        if (item.code <= currentCode()) {
                            display("最新です（" + currentName() + "）", false)
                        } else {
                            display("更新版：" + item.name, false)
                            AlertDialog.Builder(this)
                                .setTitle(item.name + " に更新")
                                .setMessage(item.message.ifBlank { "APKをダウンロードして検証します" })
                                .setPositiveButton("ダウンロード") { _, _ -> download(item) }
                                .setNegativeButton("後で", null)
                                .show()
                        }
                    },
                    onFailure = { display("更新確認できません：" + it.message, false) },
                )
            }
        }.start()
    }

    private fun latest(): Release {
        val json = JSONObject(readSmall(URL(baseUrl + "api/latest.php?app=" + appKey + "&track=" + track())))
        if (!json.optBoolean("success", false)) error("公開されている更新はありません")
        if (json.getString("app") != appKey || json.getString("channel") != track()) {
            error("アプリ種別が一致しません")
        }
        val item = Release(
            json.getString("packageName"),
            json.getLong("versionCode"),
            json.getString("versionName"),
            json.getString("apkUrl"),
            json.getString("sha256").lowercase(),
            json.getLong("size"),
            json.optString("message"),
        )
        if (item.packageName != packageName || item.code <= 0 || item.name.isBlank()) {
            error("更新先の識別情報が不正です")
        }
        if (item.size !in 1024L..maxApkBytes || !item.sha256.matches(Regex("[a-f0-9]{64}"))) {
            error("更新ファイルの検証情報が不正です")
        }
        if (!trusted(URL(item.apkUrl), "/support2/update/tsuguregi/downloads/" + appKey + "/")) {
            error("APKの配信元が不正です")
        }
        return item
    }

    private fun trusted(url: URL, path: String): Boolean =
        url.protocol == "https" && url.host.equals("tenpos.jp", ignoreCase = true) &&
            url.port == -1 && url.userInfo == null && url.ref == null &&
            url.path.startsWith(path) && !url.path.contains("..")

    private fun connect(url: URL): HttpsURLConnection {
        if (!trusted(url, "/support2/update/tsuguregi/")) error("許可されていない配信元です")
        val con = url.openConnection() as HttpsURLConnection
        con.instanceFollowRedirects = false
        con.connectTimeout = 12_000
        con.readTimeout = 30_000
        if (con.responseCode != 200) {
            val status = con.responseCode
            con.disconnect()
            error("サーバー応答：" + status)
        }
        return con
    }

    private fun readSmall(url: URL): String {
        val con = connect(url)
        try {
            con.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    if (output.size() + n > 65_536) error("更新情報が大きすぎます")
                    output.write(buffer, 0, n)
                }
                return output.toString("UTF-8")
            }
        } finally { con.disconnect() }
    }

    private fun download(item: Release) {
        if (!running.compareAndSet(false, true)) return
        display("APKを取得中…", true)
        Thread {
            val result = runCatching {
                val dir = File(cacheDir, "tsugu-updates").apply { mkdirs() }
                val partial = File(dir, "update.part")
                val apk = File(dir, "update.apk")
                partial.delete()
                try {
                    val sha = MessageDigest.getInstance("SHA-256")
                    val con = connect(URL(item.apkUrl))
                    try {
                        con.inputStream.use { input ->
                            partial.outputStream().use { output ->
                                val buf = ByteArray(32768)
                                var received = 0L
                                while (true) {
                                    val n = input.read(buf)
                                    if (n < 0) break
                                    received += n
                                    if (received > item.size || received > maxApkBytes) error("APKサイズが不正です")
                                    output.write(buf, 0, n)
                                    sha.update(buf, 0, n)
                                }
                                if (received != item.size) error("APKサイズが一致しません")
                            }
                        }
                    } finally { con.disconnect() }
                    val digest = sha.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
                    if (digest != item.sha256) error("APKのSHA-256が一致しません")
                    verifyApk(partial, item)
                    apk.delete()
                    if (!partial.renameTo(apk)) error("APK保存に失敗しました")
                    apk
                } catch (error: Exception) {
                    partial.delete()
                    throw error
                }
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                result.fold(
                    onSuccess = { apk ->
                        display("APK検証済み", false)
                        launchInstaller(apk)
                    },
                    onFailure = { display("更新できません：" + it.message, false) },
                )
            }
        }.start()
    }

    @Suppress("DEPRECATION")
    private fun verifyApk(file: File, item: Release) {
        val flags = if (Build.VERSION.SDK_INT >= 28)
            PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val parsed = packageManager.getPackageArchiveInfo(file.absolutePath, flags)
            ?: error("APKの形式が不正です")
        if (parsed.packageName != packageName || parsed.packageName != item.packageName) {
            error("別アプリのAPKです")
        }
        @Suppress("DEPRECATION")
        val incomingVersion = if (Build.VERSION.SDK_INT >= 28) parsed.longVersionCode else parsed.versionCode.toLong()
        if (incomingVersion != item.code || incomingVersion <= currentCode()) {
            error("APKのバージョンが不正です")
        }
        val installed = selfInfo(flags)
        val incoming = if (Build.VERSION.SDK_INT >= 28)
            parsed.signingInfo?.apkContentsSigners else parsed.signatures
        val existing = if (Build.VERSION.SDK_INT >= 28)
            installed.signingInfo?.apkContentsSigners else installed.signatures
        if (incoming.isNullOrEmpty() || existing.isNullOrEmpty() ||
            incoming.map { it.toCharsString() }.toSet() != existing.map { it.toCharsString() }.toSet()) {
            error("現在のアプリと署名が一致しません")
        }
    }

    private fun launchInstaller(apk: File) {
        if (Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) {
            pendingAfterPermission = apk
            AlertDialog.Builder(this)
                .setTitle("アプリのインストールを許可")
                .setMessage("Android設定でこのアプリからのインストールを許可してください。戻るとインストーラーを開きます")
                .setPositiveButton("設定を開く") { _, _ ->
                    startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + packageName)))
                }
                .setNegativeButton("戻る", null).show()
            return
        }
        pendingAfterPermission = null
        runCatching {
            val uri = FileProvider.getUriForFile(this, packageName + ".updates", apk)
            startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                clipData = ClipData.newRawUri("update", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        }.onFailure { display("インストーラーを起動できません：" + it.message, false) }
    }
}
