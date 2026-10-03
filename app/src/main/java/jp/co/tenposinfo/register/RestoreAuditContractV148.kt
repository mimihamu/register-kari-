package jp.co.tenposinfo.register

internal object RestoreAuditContractV148 {
    fun totalCount(tableCounts: Map<String, Long>): Long {
        var total = 0L
        tableCounts.toSortedMap().forEach { (table, count) ->
            require(table.isNotBlank()) { "復元監査のテーブル名が空です" }
            require(count >= 0L) { "復元監査の件数が負数です: $table=$count" }
            total = Math.addExact(total, count)
        }
        return total
    }

    fun encodeTableCounts(tableCounts: Map<String, Long>): String = tableCounts.toSortedMap()
        .entries
        .joinToString(",") { (table, count) ->
            require(table.matches(Regex("[A-Za-z0-9_]+"))) { "復元監査のテーブル名が不正です: $table" }
            require(count >= 0L) { "復元監査の件数が負数です: $table=$count" }
            "$table:$count"
        }

    fun successDetail(plan: Map<String, String>, syncSummary: String): String =
        common(plan) + " / result=SUCCESS / " + sanitize(syncSummary, 1024)

    fun failureDetail(plan: Map<String, String>, reason: String, rollbackResult: String): String =
        common(plan) + " / result=FAILED / reason=${sanitize(reason, 768)} / rollback=${sanitize(rollbackResult, 768)}"

    private fun common(plan: Map<String, String>): String = buildString {
        append("source=").append(sanitize(plan["backup_file"].orEmpty().ifBlank { "UNKNOWN" }))
        append(" / sourceSha256=").append(sanitize(plan["database_sha256"].orEmpty().ifBlank { "UNKNOWN" }))
        append(" / sourceCreatedAt=").append(sanitize(plan["backup_created_at"].orEmpty().ifBlank { "UNKNOWN" }))
        append(" / restoredCount=").append(sanitize(plan["restore_record_count"].orEmpty().ifBlank { "UNKNOWN" }))
        append(" / tableCounts=").append(sanitize(plan["restore_table_counts"].orEmpty().ifBlank { "UNKNOWN" }, 2048))
        append(" / restoreMode=").append(sanitize(plan["restore_mode"].orEmpty().ifBlank { "UNKNOWN" }))
        append(" / storeId=").append(sanitize(plan["target_store_id"].orEmpty().ifBlank { "UNKNOWN" }))
        append(" / oldTerminalId=").append(sanitize(plan["source_terminal_id"].orEmpty().ifBlank { "UNKNOWN" }))
        append(" / newTerminalId=").append(sanitize(plan["target_terminal_id"].orEmpty().ifBlank { "UNKNOWN" }))
    }

    private fun sanitize(value: String, maxLength: Int = 512): String = value
        .replace('\n', ' ')
        .replace('\r', ' ')
        .replace('\u0000', ' ')
        .take(maxLength)
}
