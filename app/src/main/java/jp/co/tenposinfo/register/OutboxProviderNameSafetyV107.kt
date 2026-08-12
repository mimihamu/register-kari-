package jp.co.tenposinfo.register

internal object OutboxProviderNameSafetyV107 {
    const val NAME_MISMATCH_MESSAGE =
        "送信先プロバイダが保存名を変更したため、指定したobject keyを確定できません。変更された名前のファイルは送信成功扱いにしません。"

    fun isExact(requestedName: String, actualName: String?): Boolean =
        actualName != null && requestedName == actualName
}

internal class OutboxProviderNameMismatchException(
    requestedName: String,
    actualName: String?,
) : OutboxDestinationCollisionException(
    fileName = requestedName,
    detail = "$NAME_PREFIX${actualName ?: "取得不可"}",
) {
    private companion object {
        const val NAME_PREFIX =
            "${OutboxProviderNameSafetyV107.NAME_MISMATCH_MESSAGE} 実際の保存名: "
    }
}
