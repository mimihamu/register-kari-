package jp.co.tenposinfo.register

import android.app.AlertDialog
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.ComponentActivity
import java.text.NumberFormat
import java.util.Locale

/** COR-005: 元レシート・元売上No.を特定できない返品。 */
class ManualReturnActivityV135 : ComponentActivity() {
    private lateinit var coordinator: ManualReturnCoordinatorV135
    private lateinit var products: List<Product>
    private val lines = mutableListOf<ManualReturnLineRequestV135>()

    private lateinit var productSpinner: Spinner
    private lateinit var quantityInput: EditText
    private lateinit var linesText: TextView
    private lateinit var totalText: TextView
    private lateinit var reasonInput: EditText
    private lateinit var refundSpinner: Spinner
    private lateinit var managerPinInput: EditText
    private lateinit var reasonRequiredCheck: CheckBox

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureRegisterSystemBars(window)
        coordinator = ManualReturnCoordinatorV135(applicationContext)
        products = coordinator.products()
        setContentView(buildContent())
        refreshLines()
    }

    override fun onDestroy() {
        coordinator.close()
        super.onDestroy()
    }

    private fun buildContent(): ScrollView {
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(18), dp(24), dp(24))
        }
        scroll.addView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        root.addView(TextView(this).apply {
            text = "元取引なし返品（レシートなし返品）"
            textSize = 26f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = "元の売上No.を特定できない場合だけ使用します。返品数量・返品金額は負数取引として保存されます。責任者承認が必須です。"
            textSize = 15f
            setPadding(0, dp(6), 0, dp(16))
        })

        val productRow = horizontalRow()
        productSpinner = Spinner(this)
        val labels = if (products.isEmpty()) listOf("商品が登録されていません") else products.map { "${it.name}  ${yen(it.unitPrice)}  ${it.taxSymbol}" }
        productSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        productRow.addView(productSpinner, LinearLayout.LayoutParams(0, dp(54), 4f))
        quantityInput = EditText(this).apply {
            hint = "数量"
            inputType = InputType.TYPE_CLASS_NUMBER
            setText("1")
            gravity = Gravity.CENTER
        }
        productRow.addView(quantityInput, LinearLayout.LayoutParams(0, dp(54), 1f))
        productRow.addView(Button(this).apply {
            text = "返品商品に追加"
            setOnClickListener { addLine() }
        }, LinearLayout.LayoutParams(0, dp(54), 1.5f))
        root.addView(productRow)

        linesText = TextView(this).apply {
            textSize = 17f
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setBackgroundColor(0xFFF3F5F7.toInt())
        }
        root.addView(linesText, matchWrap(top = 12))

        totalText = TextView(this).apply {
            textSize = 24f
            gravity = Gravity.END
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, dp(8), 0, dp(8))
        }
        root.addView(totalText, matchWrap())

        root.addView(Button(this).apply {
            text = "返品商品をすべてクリア"
            setOnClickListener {
                lines.clear()
                refreshLines()
            }
        }, matchWrap())

        reasonInput = EditText(this).apply {
            hint = "返品理由"
            minLines = 2
        }
        root.addView(reasonInput, matchWrap(top = 14))

        refundSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@ManualReturnActivityV135,
                android.R.layout.simple_spinner_dropdown_item,
                ManualRefundMethodV135.entries.map { it.displayName },
            )
        }
        val refundRow = horizontalRow()
        refundRow.addView(TextView(this).apply {
            text = "返金方法"
            textSize = 17f
            gravity = Gravity.CENTER_VERTICAL
        }, LinearLayout.LayoutParams(0, dp(54), 1f))
        refundRow.addView(refundSpinner, LinearLayout.LayoutParams(0, dp(54), 3f))
        root.addView(refundRow, matchWrap(top = 12))

        managerPinInput = EditText(this).apply {
            hint = "責任者PIN（実行・設定変更時に必須）"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        }
        root.addView(managerPinInput, matchWrap(top = 12))

        val settingRow = horizontalRow()
        reasonRequiredCheck = CheckBox(this).apply {
            text = "返品理由を必須にする"
            textSize = 16f
            isChecked = coordinator.reasonRequired()
        }
        settingRow.addView(reasonRequiredCheck, LinearLayout.LayoutParams(0, dp(52), 2f))
        settingRow.addView(Button(this).apply {
            text = "理由必須設定を保存"
            setOnClickListener { saveReasonSetting() }
        }, LinearLayout.LayoutParams(0, dp(52), 1.3f))
        root.addView(settingRow, matchWrap(top = 8))

        root.addView(TextView(this).apply {
            text = "カード返金を選んだ場合、つぐレジ側には返金記録を残しますが、決済端末側の返金操作完了も必ず確認してください。"
            textSize = 14f
            setPadding(0, dp(8), 0, dp(12))
        })

        val actionRow = horizontalRow()
        actionRow.addView(Button(this).apply {
            text = "戻る"
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(0, dp(58), 1f))
        actionRow.addView(Button(this).apply {
            text = "責任者承認して返品確定"
            setOnClickListener { executeReturn() }
        }, LinearLayout.LayoutParams(0, dp(58), 2f).apply { marginStart = dp(12) })
        root.addView(actionRow, matchWrap(top = 8))
        return scroll
    }

    private fun addLine() {
        if (products.isEmpty()) return showError("返品対象の商品が登録されていません")
        val quantity = quantityInput.text.toString().trim().toIntOrNull()
            ?: return showError("数量を数字で入力してください")
        if (quantity <= 0) return showError("数量は1以上で入力してください")
        val product = products[productSpinner.selectedItemPosition.coerceIn(products.indices)]
        val existing = lines.indexOfFirst { it.product.id == product.id }
        if (existing >= 0) {
            lines[existing] = lines[existing].copy(quantity = lines[existing].quantity + quantity)
        } else {
            lines += ManualReturnLineRequestV135(product, quantity)
        }
        quantityInput.setText("1")
        refreshLines()
    }

    private fun refreshLines() {
        if (lines.isEmpty()) {
            linesText.text = "返品商品はまだありません"
            totalText.text = "返金合計  ¥0"
            return
        }
        linesText.text = lines.joinToString("\n") { line ->
            "${line.product.name} ${line.product.taxSymbol}   -${line.quantity} × ${yen(line.product.unitPrice)}"
        }
        val items = lines.map { CartItem(it.product, it.quantity) }
        val total = TaxEngine.calculate(items).grossAmount
        totalText.text = "返金合計  -${yen(total)}"
    }

    private fun saveReasonSetting() {
        runCatching {
            coordinator.setReasonRequired(reasonRequiredCheck.isChecked, managerPinInput.text.toString())
        }.onSuccess {
            toastDialog("設定保存", if (reasonRequiredCheck.isChecked) "返品理由を必須にしました" else "返品理由を任意にしました")
        }.onFailure { showError(it.message ?: "設定を保存できませんでした") }
    }

    private fun executeReturn() {
        val method = ManualRefundMethodV135.entries[refundSpinner.selectedItemPosition]
        val request = ManualReturnRequestV135(
            lines = lines.toList(),
            reason = reasonInput.text.toString(),
            refundMethod = method,
        )
        runCatching {
            coordinator.create(request, managerPinInput.text.toString())
        }.onSuccess { result ->
            AlertDialog.Builder(this)
                .setTitle("返品を確定しました")
                .setMessage("返品No. MR-${result.manualReturnId}\n返金額 ${yen(result.signedGrossAmount)}\n印刷ジョブ No.${result.printJobId}")
                .setPositiveButton("OK") { _, _ ->
                    lines.clear()
                    reasonInput.text.clear()
                    managerPinInput.text.clear()
                    refreshLines()
                }
                .show()
        }.onFailure { showError(it.message ?: "返品を確定できませんでした") }
    }

    private fun showError(message: String) = toastDialog("確認", message)

    private fun toastDialog(title: String, message: String) {
        AlertDialog.Builder(this).setTitle(title).setMessage(message).setPositiveButton("OK", null).show()
    }

    private fun horizontalRow() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    private fun matchWrap(top: Int = 0) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = dp(top) }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun yen(value: Long): String = NumberFormat.getCurrencyInstance(Locale.JAPAN).format(value)
}
