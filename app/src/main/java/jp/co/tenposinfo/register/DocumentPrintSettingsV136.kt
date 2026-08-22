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
import androidx.compose.ui.text.font.FontFamily
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

/**
 * 正式仕様 v2.5 §8.2 / §16.2 のレシート末尾文を一つの制約へ集約する。
 *
 * §8.2 は footerMessage の各行を 0～64 文字、§16.2 は印刷設定として最大10行とする。
 * Issue #137 #26 の旧「最大12行」は採用しない。中央寄せは従来チェックリストとの互換として
 * レンダリング規則に残すが、保存上限は正式仕様を優先する。
 */
object ReceiptFooterMessagePolicyV136 {
    const val MAX_LOGICAL_LINES = 10
    const val MAX_CODE_POINTS_PER_LINE = 64
    const val DEFAULT_MESSAGE = "ありがとうございました"

    fun cleanInput(value: String): String = buildString {
        value.replace("\r\n", "\n").replace('\r', '\n').forEach { char ->
            when {
                char == '\n' -> append(char)
                char == '\t' -> append(' ')
                !char.isISOControl() -> append(char)
            }
        }
    }

    fun lineCount(value: String): Int {
        val clean = cleanInput(value)
        return if (clean.isEmpty()) 0 else clean.split('\n').size
    }

    fun normalizeForSave(value: String): String {
        val clean = cleanInput(value)
        if (clean.isBlank()) return ""
        val lines = clean.split('\n')
        require(lines.size <= MAX_LOGICAL_LINES) {
            "レシート店舗固定文は最大${MAX_LOGICAL_LINES}行です"
        }
        val normalized = lines.mapIndexed { index, line ->
            val trimmed = line.trim()
            require(codePointCount(trimmed) <= MAX_CODE_POINTS_PER_LINE) {
                "レシート店舗固定文の${index + 1}行目は${MAX_CODE_POINTS_PER_LINE}文字以内です"
            }
            trimmed
        }
        return normalized.joinToString("\n").trimEnd()
    }

    /**
     * v1.36導入前のRCP-016設定は全文200文字までで、行数制約がなかった。
     * 既存値を読み込めなくしないため、旧値だけは内容を保ったまま64文字単位へ再配置する。
     */
    fun migrateLegacy(value: String): String = runCatching {
        normalizeForSave(value)
    }.getOrElse {
        val collapsed = cleanInput(value)
            .lineSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .joinToString(" ")
        if (collapsed.isBlank()) return@getOrElse ""
        val chunks = chunkByCodePoints(collapsed, MAX_CODE_POINTS_PER_LINE)
        require(chunks.size <= MAX_LOGICAL_LINES) {
            "旧レシート店舗固定文を${MAX_LOGICAL_LINES}行以内へ移行できません"
        }
        chunks.joinToString("\n")
    }

    fun renderLines(value: String, paper: ReceiptPaper): List<String> {
        val normalized = migrateLegacy(value)
        if (normalized.isBlank()) return emptyList()
        return normalized.split('\n').flatMap { logicalLine ->
            val wrapped = if (logicalLine.isBlank()) {
                listOf("")
            } else {
                ReceiptLineWrapV136.wrap(logicalLine, paper.charsPerLine)
            }
            wrapped.map { center(it, paper.charsPerLine) }
        }
    }

    fun preview(value: String, paper: ReceiptPaper): String =
        renderLines(value, paper).joinToString("\n")

    private fun center(value: String, width: Int): String {
        val logicalWidth = ReceiptLineWrapV136.displayWidth(value)
        val leftPadding = ((width - logicalWidth) / 2).coerceAtLeast(0)
        return " ".repeat(leftPadding) + value
    }

    private fun codePointCount(value: String): Int = value.codePointCount(0, value.length)

    private fun chunkByCodePoints(value: String, maxCodePoints: Int): List<String> {
        if (value.isEmpty()) return emptyList()
        val result = mutableListOf<String>()
        var start = 0
        while (start < value.length) {
            val remaining = value.codePointCount(start, value.length)
            val count = minOf(maxCodePoints, remaining)
            val end = value.offsetByCodePoints(start, count)
            result += value.substring(start, end)
            start = end
        }
        return result
    }
}

class DocumentPrintSettingsStoreV136(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        "document_print_settings_v136",
        Context.MODE_PRIVATE,
    )

    fun load(kind: DocumentPrintKindV136): DocumentPrintSettingV136 {
        val defaultFooter = if (kind == DocumentPrintKindV136.SALE_RECEIPT) {
            ReceiptFooterMessagePolicyV136.DEFAULT_MESSAGE
        } else {
            ""
        }
        val storedFooter = preferences.getString("${kind.storageKey}.footer", defaultFooter).orEmpty()
        return DocumentPrintSettingV136(
            autoPrintEnabled = preferences.getBoolean("${kind.storageKey}.auto", kind.defaultAutoPrint),
            copies = DocumentPrintSettingsPolicyV136.normalizeCopies(
                kind,
                preferences.getInt("${kind.storageKey}.copies", 1),
            ),
            header = preferences.getString("${kind.storageKey}.header", "").orEmpty(),
            footer = if (kind == DocumentPrintKindV136.SALE_RECEIPT) {
                ReceiptFooterMessagePolicyV136.migrateLegacy(storedFooter)
            } else {
                storedFooter
            },
        )
    }

    fun save(kind: DocumentPrintKindV136, setting: DocumentPrintSettingV136) {
        val normalizedFooter = if (kind == DocumentPrintKindV136.SALE_RECEIPT) {
            ReceiptFooterMessagePolicyV136.normalizeForSave(setting.footer)
        } else {
            setting.footer.trim().take(200)
        }
        preferences.edit()
            .putBoolean("${kind.storageKey}.auto", setting.autoPrintEnabled)
            .putInt(
                "${kind.storageKey}.copies",
                DocumentPrintSettingsPolicyV136.normalizeCopies(kind, setting.copies),
            )
            .putString("${kind.storageKey}.header", setting.header.trim().take(200))
            .putString("${kind.storageKey}.footer", normalizedFooter)
            .apply()
    }
}

object DocumentPrintSettingsPolicyV136 {
    const val MIN_COPIES = 1
    const val MAX_COPIES = 3

    fun normalizeCopies(value: Int): Int = value.coerceIn(MIN_COPIES, MAX_COPIES)

    fun minimumCopies(kind: DocumentPrintKindV136): Int = when (kind) {
        DocumentPrintKindV136.INSPECTION,
        DocumentPrintKindV136.SETTLEMENT,
        -> 0
        else -> MIN_COPIES
    }

    fun normalizeCopies(kind: DocumentPrintKindV136, value: Int): Int =
        value.coerceIn(minimumCopies(kind), MAX_COPIES)

    fun kindFor(type: OperationDocumentType): DocumentPrintKindV136? = when (type) {
        OperationDocumentType.REVERSAL_RECEIPT -> null
        OperationDocumentType.HELD_TICKET_PROVISIONAL -> DocumentPrintKindV136.PROVISIONAL_RECEIPT
        OperationDocumentType.SETTLEMENT_REPORT -> DocumentPrintKindV136.SETTLEMENT
        OperationDocumentType.RECEIPT_VOUCHER -> DocumentPrintKindV136.RECEIPT_VOUCHER
    }

    fun decorateText(payloadText: String, setting: DocumentPrintSettingV136): String = buildList {
        setting.header.trim().takeIf { it.isNotBlank() }?.let(::add)
        add(payloadText.trimEnd())
        setting.footer.trim().takeIf { it.isNotBlank() }?.let(::add)
    }.joinToString("\n")

    fun applyToReceipt(
        receipt: ReceiptData,
        setting: DocumentPrintSettingV136,
    ): ReceiptData = receipt.copy(
        documentCopies = normalizeCopies(setting.copies),
        documentHeader = setting.header.trim(),
        documentFooter = ReceiptFooterMessagePolicyV136.migrateLegacy(setting.footer),
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
    var previewPaper by remember { mutableStateOf(ReceiptPaper.MM58) }
    var message by remember { mutableStateOf("") }
    val effectiveAutoPrint = if (selected == DocumentPrintKindV136.SALE_RECEIPT) {
        receiptAutoPrintEnabled
    } else {
        autoPrint
    }
    val receiptFooterValidation = if (selected == DocumentPrintKindV136.SALE_RECEIPT) {
        runCatching { ReceiptFooterMessagePolicyV136.normalizeForSave(footer) }
    } else {
        Result.success(footer)
    }
    val draftSetting = DocumentPrintSettingV136(
        autoPrintEnabled = effectiveAutoPrint,
        copies = copies,
        header = header,
        footer = footer,
    )
    val previewResult = runCatching {
        DocumentPrintPreviewV136.render(selected, draftSetting, previewPaper)
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
                onClick = { copies = DocumentPrintSettingsPolicyV136.normalizeCopies(selected, copies - 1) },
                modifier = Modifier.width(84.dp),
            ) { Text("－") }
            Text(
                if (copies == 0) "部数 0（電子保存のみ）" else "部数 $copies",
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(
                onClick = { copies = DocumentPrintSettingsPolicyV136.normalizeCopies(selected, copies + 1) },
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
            onValueChange = {
                footer = if (selected == DocumentPrintKindV136.SALE_RECEIPT) {
                    ReceiptFooterMessagePolicyV136.cleanInput(it)
                } else {
                    it.take(200)
                }
            },
            label = {
                Text(
                    if (selected == DocumentPrintKindV136.SALE_RECEIPT) {
                        "店舗固定文（最大10行・1行64文字）"
                    } else {
                        "フッタ"
                    },
                )
            },
            modifier = Modifier.fillMaxWidth(),
        )
        if (selected == DocumentPrintKindV136.SALE_RECEIPT) {
            Text(
                "${ReceiptFooterMessagePolicyV136.lineCount(footer)}/${ReceiptFooterMessagePolicyV136.MAX_LOGICAL_LINES}行・中央寄せ",
                style = MaterialTheme.typography.bodySmall,
            )
            receiptFooterValidation.exceptionOrNull()?.message?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }

        Spacer(Modifier.height(8.dp))
        Text("印刷プレビュー（SCR-640・保存前）", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        Text("編集中のヘッダ・フッタを保存せず確認できます。", style = MaterialTheme.typography.bodySmall)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { previewPaper = ReceiptPaper.MM58 },
                modifier = Modifier.weight(1f),
            ) { Text(if (previewPaper == ReceiptPaper.MM58) "● 58mm" else "58mm") }
            OutlinedButton(
                onClick = { previewPaper = ReceiptPaper.MM80 },
                modifier = Modifier.weight(1f),
            ) { Text(if (previewPaper == ReceiptPaper.MM80) "● 80mm" else "80mm") }
        }
        Text(
            "${previewPaper.widthMm}mm / ${previewPaper.charsPerLine}論理桁 / ${DocumentPrintPreviewV136.previewDotWidth(previewPaper)}dot標準",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Bold,
        )
        previewResult.fold(
            onSuccess = { preview ->
                Text(
                    preview.ifBlank { "（印字内容なし）" },
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                )
            },
            onFailure = { error ->
                Text(
                    error.message ?: "プレビューを生成できませんでした",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            },
        )

        Spacer(Modifier.height(6.dp))
        Button(
            onClick = {
                runCatching {
                    store.save(
                        selected,
                        DocumentPrintSettingV136(
                            autoPrintEnabled = effectiveAutoPrint,
                            copies = copies,
                            header = header,
                            footer = footer,
                        ),
                    )
                }.onSuccess {
                    revision++
                    message = "${selected.displayName}の文書別設定を保存しました"
                }.onFailure { error ->
                    message = error.message ?: "文書別設定を保存できませんでした"
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("文書別設定を保存") }
        if (message.isNotBlank()) Text(message, style = MaterialTheme.typography.bodySmall)
        if (selected == DocumentPrintKindV136.SALE_RECEIPT) {
            ReceiptStampSettingsPanelV136()
        }
    }
}