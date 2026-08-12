package jp.co.tenposinfo.register

import java.io.IOException

class OutboxProviderQueryUnavailableException(
    displayName: String,
) : IOException(
    "送信先の子項目を確認できません。書込みせず再試行します: $displayName",
)

object OutboxProviderQuerySafetyV109 {
    fun <T : Any> requireAvailable(displayName: String, value: T?): T =
        value ?: throw OutboxProviderQueryUnavailableException(displayName)
}
