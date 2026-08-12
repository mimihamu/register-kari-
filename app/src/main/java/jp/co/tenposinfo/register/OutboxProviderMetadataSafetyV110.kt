package jp.co.tenposinfo.register

import java.io.IOException

class OutboxProviderMetadataUnavailableException(
    metadataName: String,
) : IOException(
    "送信先のメタデータを確認できません: $metadataName。未検証の書込み結果を確定せず再試行します。",
)

object OutboxProviderMetadataSafetyV110 {
    fun <T : Any> requireAvailable(metadataName: String, value: T?): T =
        value ?: throw OutboxProviderMetadataUnavailableException(metadataName)
}
