package jp.co.tenposinfo.register

enum class PrinterStatusVerification(val displayName: String) {
    VENDOR_DOCUMENTED("メーカー仕様確認済み"),
    EXPERIMENTAL_COMPATIBILITY("互換モード・要実機確認"),
    UNSUPPORTED("状態取得なし"),
}

enum class PrinterStatusCheckPurpose {
    MANUAL_DIAGNOSTIC,
    SALES_MONITORING,
    AUTOMATIC_PREFLIGHT,
    SAFE_PRINT,
    SOAK_TEST,
}

data class PrinterStatusCapability(
    val profile: PrinterProfile,
    val implementationName: String,
    val verification: PrinterStatusVerification,
    val manualQueryAllowed: Boolean,
    val automaticQueryAllowed: Boolean,
    val soakTestAllowed: Boolean,
    val note: String,
) {
    fun decision(
        purpose: PrinterStatusCheckPurpose,
        experimentalConfirmed: Boolean = false,
    ): PrinterStatusCheckDecision = when (purpose) {
        PrinterStatusCheckPurpose.MANUAL_DIAGNOSTIC -> when {
            !manualQueryAllowed -> PrinterStatusCheckDecision.DENIED
            verification == PrinterStatusVerification.EXPERIMENTAL_COMPATIBILITY && !experimentalConfirmed ->
                PrinterStatusCheckDecision.REQUIRES_EXPLICIT_CONFIRMATION
            else -> PrinterStatusCheckDecision.ALLOWED
        }

        PrinterStatusCheckPurpose.SALES_MONITORING,
        PrinterStatusCheckPurpose.AUTOMATIC_PREFLIGHT,
        PrinterStatusCheckPurpose.SAFE_PRINT,
        -> if (automaticQueryAllowed) PrinterStatusCheckDecision.ALLOWED else PrinterStatusCheckDecision.DENIED

        PrinterStatusCheckPurpose.SOAK_TEST ->
            if (soakTestAllowed) PrinterStatusCheckDecision.ALLOWED else PrinterStatusCheckDecision.DENIED
    }
}

enum class PrinterStatusCheckDecision {
    ALLOWED,
    REQUIRES_EXPLICIT_CONFIRMATION,
    DENIED,
}

object PrinterStatusCapabilityRegistry {
    fun forProfile(profile: PrinterProfile): PrinterStatusCapability = when (profile) {
        PrinterProfile.EPSON_TM_JAPAN -> PrinterStatusCapability(
            profile = profile,
            implementationName = "EPSON DLE EOT n=1～4 一括取得",
            verification = PrinterStatusVerification.VENDOR_DOCUMENTED,
            manualQueryAllowed = true,
            automaticQueryAllowed = true,
            soakTestAllowed = true,
            note = "EPSON TM向け。4コマンド送信後、4バイト応答を解析します。",
        )

        PrinterProfile.STAR_ESC_POS -> PrinterStatusCapability(
            profile = profile,
            implementationName = "STAR ESC/POS DLE EOT互換試行",
            verification = PrinterStatusVerification.EXPERIMENTAL_COMPATIBILITY,
            manualQueryAllowed = true,
            automaticQueryAllowed = false,
            soakTestAllowed = false,
            note = "STARは機種・エミュレーションにより状態方式が異なるため、自動監視には使用しません。手動診断だけ明示確認後に試行します。",
        )

        PrinterProfile.GENERIC_ESC_POS -> PrinterStatusCapability(
            profile = profile,
            implementationName = "汎用ESC/POS DLE EOT互換試行",
            verification = PrinterStatusVerification.EXPERIMENTAL_COMPATIBILITY,
            manualQueryAllowed = true,
            automaticQueryAllowed = false,
            soakTestAllowed = false,
            note = "互換プリンターの応答形式は保証できないため、自動監視には使用しません。手動診断だけ明示確認後に試行します。",
        )
    }

    fun denialMessage(profile: PrinterProfile, purpose: PrinterStatusCheckPurpose): String {
        val capability = forProfile(profile)
        return when (purpose) {
            PrinterStatusCheckPurpose.MANUAL_DIAGNOSTIC ->
                "${profile.displayName}の状態取得は未検証です。診断画面で互換モード試行を明示確認してください"

            PrinterStatusCheckPurpose.SOAK_TEST ->
                "${profile.displayName}では状態応答の互換性が未確認のため、連続印刷試験を開始できません"

            PrinterStatusCheckPurpose.SALES_MONITORING ->
                "${profile.displayName}は状態自動監視の対象外です。印刷機能とは別に実機確認が必要です"

            PrinterStatusCheckPurpose.AUTOMATIC_PREFLIGHT ->
                "${profile.displayName}は印刷前状態確認が未検証です。自動印刷前診断を無効にするか、EPSON TMプロファイルを使用してください"

            PrinterStatusCheckPurpose.SAFE_PRINT ->
                "${profile.displayName}では安全印刷前の状態確認を実行できません。紙を確認し、確認済み強制印刷を使用してください"
        } + "（${capability.verification.displayName}）"
    }
}
