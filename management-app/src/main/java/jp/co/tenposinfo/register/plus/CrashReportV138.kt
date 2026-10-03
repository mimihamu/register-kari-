package jp.co.tenposinfo.register.plus

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess
import org.json.JSONArray
import org.json.JSONObject

class PlusApplicationV138 : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashReportRuntimeV138.install(this)
    }
}

object CrashReportRuntimeV138 {
    private const val REPORT_SCHEMA = 1
    private const val MAX_REPORT_BYTES = 512 * 1024L
    private const val REPORT_DIR = "crash-reports-v138"
    private const val PENDING_FILE = "pending-crash.json"
    private val installed = AtomicBoolean(false)
    private val handlingCrash = AtomicBoolean(false)

    @Volatile private var currentScreen = "PROCESS_START"
    @Volatile private var currentOperation = "PROCESS_START"

    fun install(context: Context) {
        val appContext = context.applicationContext
        if (!installed.compareAndSet(false, true)) return
        (appContext as? Application)?.registerActivityLifecycleCallbacks(CrashReportLifecycleV138)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            if (handlingCrash.compareAndSet(false, true)) runCatching { persist(appContext, thread, error) }
            if (previous != null) previous.uncaughtException(thread, error) else {
                Process.killProcess(Process.myPid())
                exitProcess(10)
            }
        }
    }

    fun markScreen(activity: Activity) {
        currentScreen = safeToken(activity.javaClass.name, "UNKNOWN_SCREEN")
        currentOperation = safeToken("SCREEN_RESUMED:${activity.javaClass.simpleName}", "SCREEN_RESUMED")
    }

    fun markOperation(code: String) { currentOperation = safeToken(code, "UNKNOWN_OPERATION") }

    fun hasPending(context: Context): Boolean = pendingFile(context).let { it.isFile && it.length() in 1..MAX_REPORT_BYTES }

    fun exportFileName(context: Context): String {
        val captured = pendingFile(context).takeIf { it.isFile }?.lastModified() ?: System.currentTimeMillis()
        return "tsuguregi-plus-crash-$captured.json"
    }

    fun pendingJson(context: Context): String {
        val file = pendingFile(context)
        require(file.isFile && file.length() in 1..MAX_REPORT_BYTES) { "保存可能な障害レポートがありません" }
        val bytes = file.readBytes()
        require(bytes.size.toLong() in 1..MAX_REPORT_BYTES) { "障害レポートが上限を超えています" }
        val text = bytes.toString(Charsets.UTF_8)
        JSONObject(text)
        return text
    }

    private fun persist(context: Context, thread: Thread, error: Throwable) {
        val packageInfo = runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()
        val versionCode = if (Build.VERSION.SDK_INT >= 28) packageInfo?.longVersionCode ?: 0L else {
            @Suppress("DEPRECATION") packageInfo?.versionCode?.toLong() ?: 0L
        }
        val report = JSONObject()
            .put("schemaVersion", REPORT_SCHEMA)
            .put("capturedAtEpochMs", System.currentTimeMillis())
            .put("packageName", context.packageName)
            .put("appVersionName", packageInfo?.versionName ?: "unknown")
            .put("appVersionCode", versionCode)
            .put("screen", currentScreen)
            .put("operation", currentOperation)
            .put("isMainThread", thread === android.os.Looper.getMainLooper().thread)
            .put("processUptimeMs", SystemClock.elapsedRealtime())
            .put("device", JSONObject()
                .put("manufacturer", safeToken(Build.MANUFACTURER, "unknown"))
                .put("model", safeToken(Build.MODEL, "unknown"))
                .put("sdkInt", Build.VERSION.SDK_INT)
                .put("release", safeToken(Build.VERSION.RELEASE, "unknown"))
                .put("abis", JSONArray(Build.SUPPORTED_ABIS.map { safeToken(it, "unknown") })))
            .put("exception", exceptionJson(error))
            .put("privacy", "NO_EXCEPTION_MESSAGE_NO_USER_INPUT_NO_ACCOUNT_OR_TRANSACTION_DATA")
        val bytes = report.toString(2).toByteArray(Charsets.UTF_8)
        require(bytes.size.toLong() in 1..MAX_REPORT_BYTES)
        val dir = reportDir(context).apply { mkdirs() }
        val temporary = File(dir, "$PENDING_FILE.tmp")
        val target = File(dir, PENDING_FILE)
        FileOutputStream(temporary).use { stream ->
            stream.write(bytes)
            stream.fd.sync()
        }
        runCatching {
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }.getOrElse {
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun exceptionJson(error: Throwable): JSONObject {
        val chain = JSONArray()
        var current: Throwable? = error
        var depth = 0
        while (current != null && depth < 8) {
            val frames = JSONArray()
            current.stackTrace.take(48).forEach { frame ->
                frames.put(JSONObject()
                    .put("class", safeToken(frame.className, "unknown"))
                    .put("method", safeToken(frame.methodName, "unknown"))
                    .put("file", safeToken(frame.fileName ?: "unknown", "unknown"))
                    .put("line", frame.lineNumber))
            }
            chain.put(JSONObject().put("type", safeToken(current.javaClass.name, "Throwable")).put("stack", frames))
            current = current.cause
            depth++
        }
        return JSONObject().put("type", safeToken(error.javaClass.name, "Throwable")).put("causeChain", chain)
    }

    private fun safeToken(value: String, fallback: String): String {
        val normalized = value.take(160).map { ch ->
            if (ch.isLetterOrDigit() || ch == '.' || ch == '_' || ch == '$' || ch == ':' || ch == '-' || ch == '/') ch else '_'
        }.joinToString("")
        return normalized.ifBlank { fallback }
    }

    private fun reportDir(context: Context): File = File(context.filesDir, REPORT_DIR)
    private fun pendingFile(context: Context): File = File(reportDir(context), PENDING_FILE)
}

private object CrashReportLifecycleV138 : Application.ActivityLifecycleCallbacks {
    override fun onActivityResumed(activity: Activity) {
        CrashReportRuntimeV138.markScreen(activity)
        CrashReportUiV138.ensurePendingButton(activity)
    }
    override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}

object CrashReportUiV138 {
    private const val TAG = "v138-crash-report-share"
    fun ensurePendingButton(activity: Activity) {
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        val existing = content.findViewWithTag<View>(TAG)
        if (!CrashReportRuntimeV138.hasPending(activity)) {
            if (existing != null) content.removeView(existing)
            return
        }
        if (existing != null) return
        val button = Button(activity).apply {
            tag = TAG
            text = "障害レポート"
            isAllCaps = false
            setOnClickListener {
                CrashReportRuntimeV138.markOperation("CRASH_REPORT_SHARE")
                runCatching {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "application/json"
                        putExtra(Intent.EXTRA_SUBJECT, CrashReportRuntimeV138.exportFileName(activity))
                        putExtra(Intent.EXTRA_TEXT, CrashReportRuntimeV138.pendingJson(activity))
                    }
                    activity.startActivity(Intent.createChooser(send, "障害調査レポートを共有"))
                }.onFailure { text = "障害レポート出力失敗" }
            }
        }
        content.addView(button, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.END or Gravity.BOTTOM).apply {
            val margin = (16 * activity.resources.displayMetrics.density).toInt()
            marginEnd = margin
            bottomMargin = margin
        })
    }
}
