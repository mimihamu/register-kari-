package jp.co.tenposinfo.register

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp

@Composable
fun SalesProductSearchDialogV135(
    products: List<Product>,
    onDismiss: () -> Unit,
    onRegister: (Product) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val results = remember(products, query) { ProductLookupPolicyV135.search(products, query).take(50) }
    val submitExact: () -> Unit = {
        ProductLookupPolicyV135.findExact(products, query)?.let { onRegister(it) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("商品検索") },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it.take(80) },
                    label = { Text("名称・かな・商品コード・バーコード") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { submitExact() }),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.width(8.dp))
                Text("${results.size}件表示（最大50件）", modifier = Modifier.padding(vertical = 6.dp))
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 260.dp)) {
                    items(results, key = { it.id }) { product ->
                        Row(
                            Modifier.fillMaxWidth().clickable { onRegister(product) }.padding(vertical = 8.dp),
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(product.name, fontWeight = FontWeight.Bold)
                                Text(buildString {
                                    append("コード ").append(product.id)
                                    if (product.kana.isNotBlank()) append(" / ").append(product.kana)
                                    if (product.barcode.isNotBlank()) append(" / JAN ").append(product.barcode)
                                })
                            }
                            Text("${product.unitPrice}円")
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = submitExact,
                enabled = ProductLookupPolicyV135.findExact(products, query) != null,
            ) { Text("コード一致を登録") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("閉じる") } },
    )
}
