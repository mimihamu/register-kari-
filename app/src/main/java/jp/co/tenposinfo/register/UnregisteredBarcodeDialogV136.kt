package jp.co.tenposinfo.register

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun UnregisteredBarcodeDialogV136(
    code: String,
    canOpenProductSettings: Boolean,
    onTemporaryProduct: (Product) -> Unit,
    onOpenProductSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    var unitPriceText by remember(code) { mutableStateOf("") }
    var taxCategory by remember(code) { mutableStateOf<TaxCategory?>(null) }
    var error by remember(code) { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("未登録バーコード") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("読取コード: $code")
                Text("仮商品を登録する場合は単価と税区分を指定してください。名称は「${TemporaryBarcodeProductPolicyV136.NAME}」になります。")
                OutlinedTextField(
                    value = unitPriceText,
                    onValueChange = { value ->
                        unitPriceText = value.filter(Char::isDigit).take(7)
                        error = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("仮商品単価") },
                    singleLine = true,
                )
                Text("税区分")
                TaxCategory.entries.forEach { category ->
                    OutlinedButton(
                        onClick = {
                            taxCategory = category
                            error = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (taxCategory == category) "✓ ${category.displayName}" else category.displayName)
                    }
                }
                error?.let { Text(it) }
            }
        },
        confirmButton = {
            Row {
                Button(
                    enabled = unitPriceText.toLongOrNull()?.let { it in 1..TemporaryBarcodeProductPolicyV136.MAX_UNIT_PRICE } == true && taxCategory != null,
                    onClick = {
                        runCatching {
                            TemporaryBarcodeProductPolicyV136.create(
                                code = code,
                                unitPrice = unitPriceText.toLong(),
                                taxCategory = requireNotNull(taxCategory),
                            )
                        }.onSuccess { product ->
                            onTemporaryProduct(product)
                            onDismiss()
                        }.onFailure { throwable ->
                            error = throwable.message ?: "仮商品を作成できません"
                        }
                    },
                ) { Text("仮商品") }
                if (canOpenProductSettings) {
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(
                        onClick = {
                            onOpenProductSettings()
                            onDismiss()
                        },
                    ) { Text("商品登録") }
                }
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) { Text("キャンセル") }
        },
    )
}
