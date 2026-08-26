package jp.co.tenposinfo.register

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class CrashReportExportActivityV138 : Activity() {
    private lateinit var status: TextView
    private lateinit var saveButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashReportRuntimeV138.markOperation("CRASH_REPORT_EXPORT_SCREEN")

        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        status = TextView(this).apply {
            textSize = 18f
            setPadding(0, dp(12), 0, dp(20))
        }
        saveButton = Button(this).apply {
            text = "障害調査レポートを保存"
            isAllCaps = false
            setOnClickListener {
                CrashReportRuntimeV138.markOperation("CRASH_REPORT_EXPORT_REQUEST")
                startActivityForResult(
                    Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "application/json"
                        putExtra(Intent.EXTRA_TITLE, CrashReportRuntimeV138.exportFileName(this@CrashReportExportActivityV138))
                    },
                    REQUEST_EXPORT,
                )
            }
        }
        val discardButton = Button(this).apply {
            text = "保存済み障害ログを破棄"
            isAllCaps = false
            setOnClickListener {
                CrashReportRuntimeV138.markOperation("CRASH_REPORT_DISCARD")
                status.text = if (CrashReportRuntimeV138.discardPending(this@CrashReportExportActivityV138)) {
                    "障害ログを破棄しました"
                } else {
                    "障害ログを破棄できませんでした"
                }
                refresh()
            }
        }
        val closeButton = Button(this).apply {
            text = "閉じる"
            isAllCaps = false
            setOnClickListener { finish() }
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(28), dp(24), dp(28), dp(24))
            addView(TextView(this@CrashReportExportActivityV138).apply {
                text = "障害調査レポート"
                textSize = 28f
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(TextView(this@CrashReportExportActivityV138).apply {
                text = "未処理例外の種類、画面、操作コード、アプリ版、端末情報、スタック位置だけを保存します。例外メッセージ、入力値、氏名、アカウント、売上・決済内容は保存しません。"
                textSize = 16f
                setPadding(0, dp(12), 0, dp(8))
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(status, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(saveButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)))
            addView(discardButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)).apply { topMargin = dp(10) })
            addView(closeButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)).apply { topMargin = dp(10) })
        }
        setContentView(root)
        refresh()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_EXPORT || resultCode != RESULT_OK) return
        val uri = data?.data ?: run {
            status.text = "保存先が選択されませんでした"
            return
        }
        val result = runCatching {
            contentResolver.openOutputStream(uri, "w")?.use { output ->
                CrashReportRuntimeV138.exportPending(this, output)
            } ?: error("保存先を開けません")
        }
        status.text = result.fold(
            onSuccess = { "保存完了: ${it.bytesWritten} bytes / SHA-256 ${it.sha256}" },
            onFailure = { "保存失敗: ${it.javaClass.simpleName}" },
        )
    }

    private fun refresh() {
        val pending = CrashReportRuntimeV138.hasPending(this)
        saveButton.isEnabled = pending
        if (!pending) status.text = "未出力の障害ログはありません"
    }

    private companion object {
        const val REQUEST_EXPORT = 13801
    }
}
