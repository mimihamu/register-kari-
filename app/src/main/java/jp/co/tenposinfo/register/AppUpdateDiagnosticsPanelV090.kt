package jp.co.tenposinfo.register

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val UpdateDiagGreenV090 = Color(0xFF2E7D32)
private val UpdateDiagDangerV090 = Color(0xFFC62828)
private val UpdateDiagNavyV090 = Color(0xFF173F6B)

/**
 * SCR-767用。更新状態を読み取り専用で表示する。
 * 状態変更、証跡削除、DB書込みを行う操作は提供しない。
 */
@Composable
internal fun AppUpdateDiagnosticsPanelV090(
    appContext: Context,
    modifier: Modifier = Modifier,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    var diagnostics by remember { mutableStateOf(AppUpdateDiagnosticsV090.read(appContext)) }

    DisposableEffect(lifecycleOwner, appContext) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                diagnostics = AppUpdateDiagnosticsV090.read(appContext)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val statusText = when (diagnostics.state) {
        AppUpdateOperationalStateV090.SUCCESS_CONFIRMED -> "起動成功確定済み"
        AppUpdateOperationalStateV090.STARTUP_PENDING -> "起動成功確認中"
        AppUpdateOperationalStateV090.DB_HEALTH_BLOCKED -> "DB健全性NG・更新成功未確定"
        AppUpdateOperationalStateV090.LEDGER_NOT_ESTABLISHED -> "更新台帳未確立"
    }
    val statusColor = when (diagnostics.state) {
        AppUpdateOperationalStateV090.SUCCESS_CONFIRMED -> UpdateDiagGreenV090
        AppUpdateOperationalStateV090.STARTUP_PENDING,
        AppUpdateOperationalStateV090.DB_HEALTH_BLOCKED -> UpdateDiagDangerV090
        AppUpdateOperationalStateV090.LEDGER_NOT_ESTABLISHED -> Color.DarkGray
    }

    Column(modifier.fillMaxWidth()) {
        Spacer(Modifier.height(8.dp))
        Text("アプリ更新状態", fontWeight = FontWeight.Bold, color = UpdateDiagNavyV090)
        Text(statusText, color = statusColor, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        Text(
            "現在版: ${diagnostics.current.versionName} (${diagnostics.current.versionCode})",
            fontSize = 12.sp,
        )
        val lastSuccessful = diagnostics.lastSuccessful
        Text(
            if (lastSuccessful == null) {
                "最終成功版: 未記録"
            } else {
                "最終成功版: ${lastSuccessful.versionName} (${lastSuccessful.versionCode})" +
                    diagnostics.lastSuccessfulAt?.let { " / ${formatUpdateDiagTimeV090(it)}" }.orEmpty()
            },
            fontSize = 12.sp,
        )

        diagnostics.pending?.let { pending ->
            Text(
                "未完了: ${formatTransitionV090(pending)} / 起動試行 ${pending.attemptCount}回 / 開始 ${formatUpdateDiagTimeV090(pending.startedAt)}",
                color = UpdateDiagDangerV090,
                fontSize = 12.sp,
            )
        }
        diagnostics.incomplete?.let { incomplete ->
            Text(
                "前版未完了: ${formatTransitionV090(incomplete)} / 起動試行 ${incomplete.attemptCount}回",
                color = UpdateDiagDangerV090,
                fontSize = 12.sp,
            )
        }
        diagnostics.databaseHealthFailure?.let { failure ->
            Text(
                "DB健全性NG証跡: ${failure.target.versionName} (${failure.target.versionCode}) / ${formatUpdateDiagTimeV090(failure.checkedAt)}",
                color = UpdateDiagDangerV090,
                fontSize = 12.sp,
            )
            Text(failure.summary, color = UpdateDiagDangerV090, fontSize = 11.sp)
        }
    }
}

private fun formatTransitionV090(value: PendingAppStartupV088): String {
    val source = value.source?.let { "${it.versionName}(${it.versionCode})" } ?: "不明"
    return "$source → ${value.target.versionName}(${value.target.versionCode})"
}

private fun formatUpdateDiagTimeV090(value: Long): String =
    SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.JAPAN).format(Date(value))