package jp.co.tenposinfo.register.plus

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.window.DialogProperties
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.time.LocalDate

class TaxCategoryMasterActivityV135 : ComponentActivity() {
    private val store by lazy { ManagementTaxCategoryStoreV135(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configurePlusSystemBarsV148(window)
        setContent {
            PlusSafeAreaV148 {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    var rules by remember { mutableStateOf(store.listAll()) }
                    var editor by remember { mutableStateOf<ManagementTaxCategoryV135?>(null) }
                    var errorMessage by remember { mutableStateOf<String?>(null) }

                    TaxCategoryMasterScreenV135(
                        rules = rules,
                        onBack = ::finish,
                        onAdd = {
                            errorMessage = null
                            editor = newTaxCategoryDraftV135()
                        },
                        onRevise = { current ->
                            errorMessage = null
                            editor = revisionDraftV135(current)
                        },
                    )

                    editor?.let { initial ->
                        TaxCategoryEditorDialogV135(
                            initial = initial,
                            errorMessage = errorMessage,
                            onDismiss = {
                                editor = null
                                errorMessage = null
                            },
                            onSave = { candidate ->
                                runCatching { store.appendRevision(candidate) }
                                    .onSuccess {
                                        rules = store.listAll()
                                        editor = null
                                        errorMessage = null
                                    }
                                    .onFailure { error ->
                                        errorMessage = error.message ?: "税区分を保存できませんでした"
                                    }
                            },
                        )
                    }
                }
            }
            }
        }
    }

    override fun onDestroy() {
        store.close()
        super.onDestroy()
    }
}

@Composable
private fun TaxCategoryMasterScreenV135(
    rules: List<ManagementTaxCategoryV135>,
    onBack: () -> Unit,
    onAdd: () -> Unit,
    onRevise: (ManagementTaxCategoryV135) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        PlusTopBarV155(title = "税区分マスター", onBack = onBack)
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "税区分",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Button(onClick = onAdd) { Text("新規追加") }
        }
        Text(
            "変更は履歴を残して改定します。適用日は営業日で指定してください。",
            modifier = Modifier.padding(horizontal = 16.dp),
            style = MaterialTheme.typography.bodySmall,
        )
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(rules, key = { it.id }) { rule ->
                TaxCategoryRuleCardV135(rule = rule, onRevise = { onRevise(rule) })
            }
        }
    }
}

@Composable
private fun TaxCategoryRuleCardV135(
    rule: ManagementTaxCategoryV135,
    onRevise: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(rule.label, fontWeight = FontWeight.Bold)
                    Text(rule.taxKey, style = MaterialTheme.typography.labelSmall)
                }
                Text(if (rule.enabled) "使用中" else "停止", style = MaterialTheme.typography.labelMedium)
                TextButton(onClick = onRevise) { Text("改定") }
            }
            val rateText = if (rule.taxable) "${rule.ratePercent}% ${rule.mode.displayName}" else "非課税"
            Text("$rateText ／ 記号 ${rule.symbol}${if (rule.reduced) " ／ 軽減" else ""}")
            Text(
                "適用営業日 ${rule.effectiveFromBusinessDate} ～ ${rule.effectiveToBusinessDate.ifBlank { "継続" }}",
                style = MaterialTheme.typography.bodySmall,
            )
            if (rule.systemSeed) {
                Text("初期区分", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun TaxCategoryEditorDialogV135(
    initial: ManagementTaxCategoryV135,
    errorMessage: String?,
    onDismiss: () -> Unit,
    onSave: (ManagementTaxCategoryV135) -> Unit,
) {
    var key by remember(initial) { mutableStateOf(initial.taxKey) }
    var label by remember(initial) { mutableStateOf(initial.label) }
    var rate by remember(initial) { mutableStateOf(initial.ratePercent.toString()) }
    var mode by remember(initial) { mutableStateOf(initial.mode) }
    var reduced by remember(initial) { mutableStateOf(initial.reduced) }
    var symbol by remember(initial) { mutableStateOf(initial.symbol) }
    var effectiveFrom by remember(initial) { mutableStateOf(initial.effectiveFromBusinessDate) }
    var effectiveTo by remember(initial) { mutableStateOf(initial.effectiveToBusinessDate) }
    var enabled by remember(initial) { mutableStateOf(initial.enabled) }
    var showAdvanced by remember(initial) { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
        ),
        title = { Text(if (initial.taxKey.isBlank()) "税区分を追加" else "税区分を改定") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it },
                    label = { Text("税区分キー") },
                    enabled = initial.taxKey.isBlank(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = rate,
                    onValueChange = { rate = it.filter(Char::isDigit).take(3) },
                    label = { Text("税率（%）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("課税方式", fontWeight = FontWeight.SemiBold)
                ManagementTaxModeV135.entries.forEach { candidate ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = mode == candidate,
                            onClick = {
                                mode = candidate
                                if (candidate == ManagementTaxModeV135.NON_TAXABLE) {
                                    rate = "0"
                                    reduced = false
                                }
                            },
                        )
                        Text(candidate.displayName)
                    }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("軽減税率", modifier = Modifier.weight(1f))
                    Switch(
                        checked = reduced,
                        onCheckedChange = { reduced = it },
                        enabled = mode != ManagementTaxModeV135.NON_TAXABLE,
                    )
                }
                OutlinedTextField(
                    value = effectiveFrom,
                    onValueChange = { effectiveFrom = it.take(10) },
                    label = { Text("適用開始営業日 yyyy-MM-dd") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(onClick = { showAdvanced = !showAdvanced }) {
                    Text(if (showAdvanced) "詳細を閉じる" else "詳細設定")
                }
                if (showAdvanced) {
                    OutlinedTextField(
                        value = symbol,
                        onValueChange = { symbol = it.take(4) },
                        label = { Text("税記号") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("使用状態", modifier = Modifier.weight(1f))
                        Switch(checked = enabled, onCheckedChange = { enabled = it })
                    }
                    OutlinedTextField(
                        value = effectiveTo,
                        onValueChange = { effectiveTo = it.take(10) },
                        label = { Text("適用終了営業日（任意）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                errorMessage?.let { message ->
                    Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(
                        ManagementTaxCategoryV135(
                            taxKey = key,
                            label = label,
                            ratePercent = rate.toIntOrNull() ?: -1,
                            mode = mode,
                            reduced = reduced,
                            symbol = symbol,
                            effectiveFromBusinessDate = effectiveFrom,
                            effectiveToBusinessDate = effectiveTo,
                            enabled = enabled,
                            systemSeed = false,
                        ),
                    )
                },
            ) {
                Text("保存")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } },
    )
}

private fun newTaxCategoryDraftV135(): ManagementTaxCategoryV135 = ManagementTaxCategoryV135(
    taxKey = "",
    label = "",
    ratePercent = 10,
    mode = ManagementTaxModeV135.INCLUDED,
    reduced = false,
    symbol = "内",
    effectiveFromBusinessDate = LocalDate.now().toString(),
    enabled = true,
)

private fun revisionDraftV135(current: ManagementTaxCategoryV135): ManagementTaxCategoryV135 = current.copy(
    id = 0L,
    effectiveFromBusinessDate = LocalDate.now().toString(),
    effectiveToBusinessDate = "",
    systemSeed = false,
)