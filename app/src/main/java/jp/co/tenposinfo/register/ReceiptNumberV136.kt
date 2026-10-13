package jp.co.tenposinfo.register

/**
 * v1.36 / RCPT-001: 販売レシート番号の表示規則。
 *
 * 売上の永続IDを番号の正本とし、仕様書標準例に合わせて6桁未満を0埋めする。
 * 6桁を超えたIDは切り捨てず、そのまま表示して一意性・追跡性を保持する。
 */
object ReceiptNumberV136 {
    fun format(saleId: Long): String {
        require(saleId >= 0L) { "saleId must not be negative" }
        return saleId.toString().padStart(6, '0')
    }
}
