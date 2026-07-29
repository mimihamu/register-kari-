package jp.co.tenposinfo.register

import androidx.compose.runtime.Composable

/** Compose 内で固定数の部品を生成するための composable 対応 repeat。 */
@Composable
internal fun repeat(times: Int, content: @Composable (Int) -> Unit) {
    for (index in 0 until times.coerceAtLeast(0)) {
        content(index)
    }
}

/** Enum.values() が返す配列をUI行へ分割する明示的な実装。 */
internal fun <T> Array<T>.chunked(size: Int): List<List<T>> {
    require(size > 0)
    val rows = mutableListOf<List<T>>()
    var start = 0
    while (start < this.size) {
        val end = (start + size).coerceAtMost(this.size)
        rows += (start until end).map { this[it] }
        start = end
    }
    return rows
}
