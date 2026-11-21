package jp.co.tenposinfo.register

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Debug
import android.os.Environment
import android.os.StatFs

/** Formal v2.5 DIAG-001 / DIAG-002: lightweight on-device performance diagnostics. */
data class PerformanceDiagnosticsSnapshotV136(
    val capturedAt: Long,
    val sdkInt: Int,
    val runtimeUsedBytes: Long,
    val runtimeMaxBytes: Long,
    val processPssBytes: Long?,
    val deviceAvailableBytes: Long?,
    val deviceTotalBytes: Long?,
    val storageAvailableBytes: Long?,
    val storageTotalBytes: Long?,
    val memorySource: String,
)

object PerformanceDiagnosticsV136 {
    fun capture(context: Context): PerformanceDiagnosticsSnapshotV136 {
        val runtime = Runtime.getRuntime()
        val runtimeUsed = (runtime.totalMemory() - runtime.freeMemory()).coerceAtLeast(0L)
        val runtimeMax = runtime.maxMemory().coerceAtLeast(0L)

        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo()
        runCatching { activityManager?.getMemoryInfo(memoryInfo) }

        val processPss = runCatching {
            Debug.getPss().toLong().coerceAtLeast(0L) * 1024L
        }.getOrNull()

        val storage = runCatching { StatFs(Environment.getDataDirectory().absolutePath) }.getOrNull()
        val source = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && processPss != null -> "Debug.getPss + ActivityManager"
            processPss != null -> "Debug.getPss fallback"
            else -> "Runtime fallback"
        }

        return PerformanceDiagnosticsSnapshotV136(
            capturedAt = System.currentTimeMillis(),
            sdkInt = Build.VERSION.SDK_INT,
            runtimeUsedBytes = runtimeUsed,
            runtimeMaxBytes = runtimeMax,
            processPssBytes = processPss,
            deviceAvailableBytes = memoryInfo.availMem.takeIf { it > 0L },
            deviceTotalBytes = memoryInfo.totalMem.takeIf { it > 0L },
            storageAvailableBytes = storage?.availableBytes?.takeIf { it >= 0L },
            storageTotalBytes = storage?.totalBytes?.takeIf { it > 0L },
            memorySource = source,
        )
    }

    fun formatBytes(bytes: Long?): String {
        if (bytes == null) return "取得不可"
        val mib = bytes / (1024.0 * 1024.0)
        return if (mib >= 1024.0) "%.2f GB".format(mib / 1024.0) else "%.1f MB".format(mib)
    }
}
