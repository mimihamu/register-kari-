package jp.co.tenposinfo.register

/** Formal v2.5 BKP-004 restore-preflight decision model. */
enum class RestorePreflightDispositionV136 {
    PASS,
    MIGRATE,
    BLOCK,
}

data class RestoreDriveDestinationV136(
    val descriptorCaptured: Boolean,
    val connected: Boolean,
    val accountKey: String?,
    val folderName: String,
)

data class RestorePreflightInputsV136(
    val envelopeFormat: String,
    val contentFormat: String?,
    val backupAppVersion: String,
    val currentAppVersion: String,
    val backupDatabaseSchema: Int,
    val currentDatabaseSchema: Int,
    val backupStoreId: String,
    val currentStoreId: String,
    val backupTerminalId: String,
    val currentTerminalId: String,
    val hashVerified: Boolean,
    val requiredFreeBytes: Long,
    val availableFreeBytes: Long,
    val backupDrive: RestoreDriveDestinationV136?,
    val currentDrive: RestoreDriveDestinationV136,
)

data class RestorePreflightCheckV136(
    val code: String,
    val label: String,
    val disposition: RestorePreflightDispositionV136,
    val detail: String,
)

data class RestorePreflightDecisionV136(
    val checks: List<RestorePreflightCheckV136>,
) {
    val mayRestore: Boolean
        get() = checks.none { it.disposition == RestorePreflightDispositionV136.BLOCK }

    val blockingReasons: List<String>
        get() = checks.filter { it.disposition == RestorePreflightDispositionV136.BLOCK }
            .map { "${it.label}: ${it.detail}" }

    fun displayText(): String = buildString {
        append(if (mayRestore) "復元前検証: 実行可能" else "復元前検証: 拒否")
        checks.forEach { check ->
            val mark = when (check.disposition) {
                RestorePreflightDispositionV136.PASS -> "OK"
                RestorePreflightDispositionV136.MIGRATE -> "移行"
                RestorePreflightDispositionV136.BLOCK -> "拒否"
            }
            append("\n[$mark] ${check.label}: ${check.detail}")
        }
    }
}

object RestorePreflightPolicyV136 {
    fun evaluate(input: RestorePreflightInputsV136): RestorePreflightDecisionV136 =
        RestorePreflightDecisionV136(
            listOf(
                backupSchema(input),
                applicationVersion(input),
                databaseSchema(input),
                storeIdentity(input),
                terminalIdentity(input),
                hash(input),
                freeSpace(input),
                driveDestination(input),
            ),
        )

    private fun backupSchema(input: RestorePreflightInputsV136): RestorePreflightCheckV136 {
        val content = input.contentFormat
        return when {
            input.envelopeFormat != BackupEnvelopeV136.FORMAT -> block(
                "BKP_SCHEMA",
                "バックアップschema",
                "未対応の暗号化形式 ${input.envelopeFormat}",
            )
            content == null -> migrate(
                "BKP_SCHEMA",
                "バックアップschema",
                "${input.envelopeFormat} / DB-only旧形式。設定・画像は旧バックアップ範囲として移行します",
            )
            content != BackupContentBundleV136.FORMAT -> block(
                "BKP_SCHEMA",
                "バックアップschema",
                "未対応のcontent形式 $content",
            )
            else -> pass(
                "BKP_SCHEMA",
                "バックアップschema",
                "${input.envelopeFormat} / $content",
            )
        }
    }

    private fun applicationVersion(input: RestorePreflightInputsV136): RestorePreflightCheckV136 {
        if (input.backupAppVersion == input.currentAppVersion) {
            return pass("APP_VERSION", "アプリ版", input.currentAppVersion)
        }
        val backup = semanticCore(input.backupAppVersion)
        val current = semanticCore(input.currentAppVersion)
        if (backup == null || current == null) {
            return block(
                "APP_VERSION",
                "アプリ版",
                "版を安全に比較できません: backup=${input.backupAppVersion}, current=${input.currentAppVersion}",
            )
        }
        return when {
            compareVersion(backup, current) > 0 -> block(
                "APP_VERSION",
                "アプリ版",
                "バックアップ作成版 ${input.backupAppVersion} が現在版 ${input.currentAppVersion} より新しいため更新が必要です",
            )
            else -> migrate(
                "APP_VERSION",
                "アプリ版",
                "${input.backupAppVersion} → ${input.currentAppVersion}。schema互換性確認後に移行します",
            )
        }
    }

    private fun databaseSchema(input: RestorePreflightInputsV136): RestorePreflightCheckV136 = when {
        input.backupDatabaseSchema > input.currentDatabaseSchema -> block(
            "DB_SCHEMA",
            "DB schema",
            "backup=${input.backupDatabaseSchema}, current=${input.currentDatabaseSchema}。新しいアプリが必要です",
        )
        input.backupDatabaseSchema < input.currentDatabaseSchema -> migrate(
            "DB_SCHEMA",
            "DB schema",
            "${input.backupDatabaseSchema} → ${input.currentDatabaseSchema} を起動時migrationします",
        )
        else -> pass("DB_SCHEMA", "DB schema", input.currentDatabaseSchema.toString())
    }

    private fun storeIdentity(input: RestorePreflightInputsV136): RestorePreflightCheckV136 =
        if (input.backupStoreId == input.currentStoreId) {
            pass("STORE_ID", "storeId", input.currentStoreId)
        } else {
            block(
                "STORE_ID",
                "storeId",
                "店舗が一致しません: backup=${input.backupStoreId}, current=${input.currentStoreId}",
            )
        }

    private fun terminalIdentity(input: RestorePreflightInputsV136): RestorePreflightCheckV136 =
        if (input.backupTerminalId == input.currentTerminalId) {
            pass("TERMINAL_ID", "terminalId", input.currentTerminalId)
        } else {
            block(
                "TERMINAL_ID",
                "terminalId",
                "端末が一致しません: backup=${input.backupTerminalId}, current=${input.currentTerminalId}。予備端末移行はBKP-005の端末ID切替を選択してください",
            )
        }

    private fun hash(input: RestorePreflightInputsV136): RestorePreflightCheckV136 =
        if (input.hashVerified) pass("HASH", "hash", "SHA-256一致・暗号化payload検証済み")
        else block("HASH", "hash", "SHA-256または暗号化payload検証に失敗しました")

    private fun freeSpace(input: RestorePreflightInputsV136): RestorePreflightCheckV136 =
        if (input.availableFreeBytes >= input.requiredFreeBytes) {
            pass(
                "FREE_SPACE",
                "空き容量",
                "必要${formatBytes(input.requiredFreeBytes)} / 空き${formatBytes(input.availableFreeBytes)}",
            )
        } else {
            block(
                "FREE_SPACE",
                "空き容量",
                "不足しています: 必要${formatBytes(input.requiredFreeBytes)} / 空き${formatBytes(input.availableFreeBytes)}",
            )
        }

    private fun driveDestination(input: RestorePreflightInputsV136): RestorePreflightCheckV136 {
        val backup = input.backupDrive ?: return migrate(
            "DRIVE_DESTINATION",
            "Google Drive接続先",
            "旧バックアップには接続先識別情報がありません。復元後にDrive接続先を再確認してください",
        )
        val current = input.currentDrive
        if (!backup.descriptorCaptured) {
            return migrate(
                "DRIVE_DESTINATION",
                "Google Drive接続先",
                "バックアップ作成時の接続先識別情報がありません。復元後に再確認してください",
            )
        }
        if (!backup.connected && current.connected) {
            return block(
                "DRIVE_DESTINATION",
                "Google Drive接続先",
                "バックアップ作成時はDrive未接続ですが、現在は別の接続が有効です。誤送信防止のため現在のDrive接続を解除して再検証してください",
            )
        }
        if (backup.connected && current.connected) {
            val backupKey = backup.accountKey
            val currentKey = current.accountKey
            if (backupKey.isNullOrBlank() || currentKey.isNullOrBlank()) {
                return block(
                    "DRIVE_DESTINATION",
                    "Google Drive接続先",
                    "接続中アカウントを安全に同一確認できません。Drive接続を再確認してください",
                )
            }
            if (backupKey != currentKey) {
                return block(
                    "DRIVE_DESTINATION",
                    "Google Drive接続先",
                    "Driveアカウントが一致しません。誤送信防止のためバックアップ作成時のアカウントへ接続してください",
                )
            }
        }
        if (backup.connected && !current.connected) {
            return migrate(
                "DRIVE_DESTINATION",
                "Google Drive接続先",
                "バックアップ作成時はDrive接続あり。現在は未接続のため復元後に同じアカウントへ再認証が必要です。同期フォルダ=${backup.folderName}",
            )
        }
        return if (backup.folderName == current.folderName) {
            pass(
                "DRIVE_DESTINATION",
                "Google Drive接続先",
                "接続状態と同期フォルダが一致: ${backup.folderName}",
            )
        } else {
            migrate(
                "DRIVE_DESTINATION",
                "Google Drive接続先",
                "同期フォルダ ${current.folderName} → ${backup.folderName} をバックアップ設定へ移行します",
            )
        }
    }

    private fun semanticCore(version: String): IntArray? {
        val match = Regex("(\\d+)\\.(\\d+)\\.(\\d+)").find(version) ?: return null
        return intArrayOf(
            match.groupValues[1].toIntOrNull() ?: return null,
            match.groupValues[2].toIntOrNull() ?: return null,
            match.groupValues[3].toIntOrNull() ?: return null,
        )
    }

    private fun compareVersion(left: IntArray, right: IntArray): Int {
        for (index in 0..2) {
            if (left[index] != right[index]) return left[index].compareTo(right[index])
        }
        return 0
    }

    private fun formatBytes(bytes: Long): String =
        if (bytes >= 1024L * 1024L) "${bytes / (1024L * 1024L)}MB" else "${bytes / 1024L}KB"

    private fun pass(code: String, label: String, detail: String) =
        RestorePreflightCheckV136(code, label, RestorePreflightDispositionV136.PASS, detail)

    private fun migrate(code: String, label: String, detail: String) =
        RestorePreflightCheckV136(code, label, RestorePreflightDispositionV136.MIGRATE, detail)

    private fun block(code: String, label: String, detail: String) =
        RestorePreflightCheckV136(code, label, RestorePreflightDispositionV136.BLOCK, detail)
}

data class RestorePreflightReportV136(
    val verification: BackupVerification,
    val decision: RestorePreflightDecisionV136,
    val backupStoreId: String,
    val backupTerminalId: String,
    val currentStoreId: String,
    val currentTerminalId: String,
) {
    val mayRestore: Boolean get() = decision.mayRestore
    val blockingReasons: List<String> get() = decision.blockingReasons
    fun displayText(): String = decision.displayText()
}
