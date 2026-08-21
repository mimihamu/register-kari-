package jp.co.tenposinfo.register

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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

enum class DocumentPrintKindV136(
    val displayName: String,
    val storageKey: String,
    val defaultAutoPrint: Boolean,
) {
    SALE_RECEIPT("レシート", "sale_receipt", true),
    RECEIPT_VOUCHER("領収書", "receipt_voucher", false),
    PROVISIONAL_RECEIPT("仮締め票", "provisional_receipt", false),
    INSPECTION("点検票", "inspection", true),
    SETTLEMENT("精算票", "settlement", true),
}

data class DocumentPrintSettingV136(
    val autoPrintEnabled: Boolean = true,
    val copies: Int = 1,
    val header: String = "",
    val footer: String = "",
)

class DocumentPrintSettingsStoreV136(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        "document_print_settings_v136",
        Context.MODE_PRIVATE,
    )

    fun load(kind: DocumentPrintKindV136): DocumentPrintSettingV136 = DocumentPrintSettingV136(
        autoPrintEnabled = preferences.getBoolean("${kind.storageKey}.auto", kind.defaultAutoPrint),
        copies = DocumentPrintSettingsPolicyV136.normalizeCopies(
            preferences.getInt("${kind.storageKey}.copies", 1),
        ),
        header = preferences.getString("${kind.storageKey}.header", "").orEmpty(),
        footer = preferences.getString("${kind.storageKey}.footer", "").orEmpty(),
    )

    fun save(kind: DocumentPrintKindV136, setting: DocumentPrintSettingV136) {
        preferences.edit()
            .putBoolean("${kind.storageKey}.auto", setting.autoPrintEnabled)
            .putInt("${kind.storageKey}.copies", DocumentPrintSettingsPolicyV136.normalizeCopies(setting.copies))
            .putString("${kind.storageKey}.header", setting.header.trim().take(200))
            .putString("${kind.storageKey}.footer", setting.footer.trim().take(200))
            .apply()
    }
}

object DocumentPrintSettingsPolicyV136 {
    const val MIN_COPIES = 1
    const val MAX_COPIES = 3

    fun normalizeCopies(value: Int): Int = value.coerceIn(MIN_COPIES, MAX_COPIES)

    fun kindFor(type: OperationDocumentType): DocumentPrintKindV136? = when (type) {
        OperationDocumentType.REVERSAL_RECEIPT -> null
        OperationDocumentType.HELD_TICKET_PROVISIONAL -> DocumentPrintKindV136.PROVISIONAL_RECEIPT
        OperationDocumentType.SETTLEMENT_REPORT -> DocumentPrintKindV136.SETTLEMENT
        OperationDocumentType.RECEIPT_VOUCHER -> DocumentPrintKindV136.RECEIPT_VOUCHER
    }

    fun decorateText(payloadText: String, setting: DocumentPrintSettingV136): String = buildList {
        setting.header.trim().takeIf(String::isNotBlank)?.let(::add)
        add(payloadText.trimEnd())
        setting.footer.trim().takeIf(String::isNotBlank)?.let(::add)
    }.joinToString("\n")

    fun applyToReceipt(
        receipt: ReceiptData,
        setting: DocumentPrintSettingV136,
    ): ReceiptData = receipt.copy(
        documentCopies = normalizeCopies(setting.copies),
        documentHeader = setting.header.trim(),
        documentFooter = setting.footer.trim(),
    )
}

@Composable
fun DocumentPrintSettingsPanelV136(receiptAutoPrintEnabled: Boolean) {
    val context = LocalContext.current.applicationContext
    val store = remember(context) { DocumentPrintSettingsStoreV136(context) }
    var selected by remember { mutableStateOf(DocumentPrintKindV136.SALE_RECEIPT) }
    var revision by remember { mutableIntStateOf(0) }
    val loaded = remember(selected, revision) { store.load(selected) }
    var autoPrint by remember(selected, revision) { mutableStateOf(loaded.autoPrintEnabled) }
    var copies by remember(selected, revision) { mutableIntStateOf(loaded.copies) }
    var header by remember(selected, revision) { mutableStateOf(loaded.header) }
    var footer by remember(selected, revision) { mutableStateOf(loaded.footer) }
    var message by remember { mutableStateOf("") }
    val effectiveAutoPrint = if (selected == DocumentPrintKindV136.SALE_RECEIPT) {
        receiptAutoPrintEnabled
    } else {
        autoPrint
    }

    Column(Modifier.fillMaxWidth()) {
        Text("文書別設定（RCP-016）", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        Text("文書ごとに自動印刷・部数・ヘッダ・フッタを設定します。", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            DocumentPrintKindV136.entries.take(3).forEach { kind ->
                OutlinedButton(onClick = { selected = kind; message = "" }, modifier = Modifier.weight(1f)) {
                    Text(kind.displayName)
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            DocumentPrintKindV136.entries.drop(3).forEach { kind ->
                OutlinedButton(onClick = { selected = kind; message = "" }, modifier = Modifier.weight(1f)) {
                    Text(kind.displayName)
                }
            }
            Spacer(Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth()) {
            Checkbox(
                checked = effectiveAutoPrint,
                onCheckedChange = { autoPrint = it },
                enabled = selected != DocumentPrintKindV136.SALE_RECEIPT,
            )
            Column {
                Text("${selected.displayName}を自動印刷")
                if (selected == DocumentPrintKindV136.SALE_RECEIPT) {
                    Text("レシート自動印刷は上のRCP-002設定を使用します。", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { copies = DocumentPrintSettingsPolicyV136.normalizeCopies(copies - 1) },
                modifier = Modifier.width(84.dp),
            ) { Text("－") }
            Text("部数 $copies", modifier = Modifier.weight(1f))
            OutlinedButton(
                onClick = { copies = DocumentPrintSettingsPolicyV136.normalizeCopies(copies + 1) },
                modifier = Modifier.width(84.dp),
            ) { Text("＋") }
        }
        OutlinedTextField(
            value = header,
            onValueChange = { header = it.take(200) },
            label = { Text("ヘッダ") },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = footer,
            onValueChange = { footer = it.take(200) },
            label = { Text("フッタ") },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(6.dp))
        Button(
            onClick = {
                store.save(
                    selected,
                    DocumentPrintSettingV136(
                        autoPrintEnabled = effectiveAutoPrint,
                        copies = copies,
                        header = header,
                        footer = footer,
                    ),
                )
                revision++
                message = "${selected.displayName}の文書別設定を保存しました"
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("文書別設定を保存") }
        if (message.isNotBlank()) Text(message, style = MaterialTheme.typography.bodySmall)
    }
}
