package jp.co.tenposinfo.register

internal object OutboxDuplicateDisplayNameSafetyV108 {
    const val DUPLICATE_MESSAGE =
        "送信先に同名の項目が複数存在するため、object keyを一意に確定できません。既存項目は保護したため自動選択・削除しません。"

    fun requireUnique(displayName: String, matchCount: Int) {
        require(matchCount >= 0) { "同名候補数が不正です" }
        if (matchCount > 1) {
            throw OutboxDuplicateDisplayNameException(displayName)
        }
    }
}

internal class OutboxDuplicateDisplayNameException(
    displayName: String,
) : OutboxDestinationCollisionException(
    fileName = displayName,
    detail = OutboxDuplicateDisplayNameSafetyV108.DUPLICATE_MESSAGE,
)
