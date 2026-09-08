package jp.co.tenposinfo.register

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun ReceiptTextStampSettingsPanelV136() {
    val context = LocalContext.current.applicationContext
    val store = remember(context) { ReceiptTextStampSettingsStoreV136(context) }
    var revision by remember { mutableIntStateOf(0) }
    val loaded = remember(revision) { store.load() }
    var lines by remember(revision) { mutableStateOf(loaded.lines) }
    var message by remember { mutableStateOf("") }

    fun update(field: ReceiptTextStampFieldV136, transform: (ReceiptTextStampLineSettingV136) -> ReceiptTextStampLineSettingV136) {
        lines = lines.map { if (it.field == field) transform(it) else it }
    }

    Column(Modifier.fillMaxWidth()) {
        Spacer(Modifier.height(12.dp))
        Text("文字スタンプ（SCR-720）", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        Text(
            "店舗名・支店名・住所・電話・登録番号を行単位でON/OFF、中央/左、太字、倍率指定します。変更は次回印刷から反映します。",
            style = MaterialTheme.typography.bodySmall,
        )
        lines.forEach { line ->
            Spacer(Modifier.height(8.dp))
            Text(line.field.displayName, fontWeight = FontWeight.Bold)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.weight(0.8f)) {
                    Checkbox(
                        checked = line.enabled,
                        onCheckedChange = { checked -> update(line.field) { it.copy(enabled = checked) } },
                    )
                    Text("印字")
                }
                OutlinedButton(
                    onClick = {
                        update(line.field) {
                            it.copy(
                                alignment = if (it.alignment == ReceiptTextStampAlignmentV136.CENTER) {
                                    ReceiptTextStampAlignmentV136.LEFT
                                } else {
                                    ReceiptTextStampAlignmentV136.CENTER
                                },
                            )
                        }
                    },
                    modifier = Modifier.weight(1.1f),
                ) { Text(line.alignment.displayName) }
                OutlinedButton(
                    onClick = { update(line.field) { it.copy(bold = !it.bold) } },
                    modifier = Modifier.weight(0.9f),
                ) { Text(if (line.bold) "太字 ON" else "太字 OFF") }
                OutlinedButton(
                    onClick = {
                        update(line.field) {
                            it.copy(
                                magnification = if (it.magnification >= ReceiptTextStampPolicyV136.MAX_MAGNIFICATION) {
                                    ReceiptTextStampPolicyV136.MIN_MAGNIFICATION
                                } else {
                                    it.magnification + 1
                                },
                            )
                        }
                    },
                    modifier = Modifier.weight(0.8f),
                ) { Text("×${line.magnification}") }
            }
        }
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = {
                runCatching { store.save(ReceiptTextStampSettingsV136(lines = lines, stampVersion = loaded.stampVersion)) }
                    .onSuccess {
                        revision++
                        message = "文字スタンプ設定を保存しました（version ${it.stampVersion}）"
                    }
                    .onFailure { message = it.message ?: "文字スタンプ設定を保存できませんでした" }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("文字スタンプ設定を保存") }
        if (message.isNotBlank()) Text(message, style = MaterialTheme.typography.bodySmall)
    }
}
