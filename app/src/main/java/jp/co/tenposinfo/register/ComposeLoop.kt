package jp.co.tenposinfo.register

import androidx.compose.runtime.Composable

/**
 * Compose 内で固定数の部品を生成するための composable 対応 repeat。
 * Kotlin 標準 repeat の非 composable ラムダによるコンパイルエラーを避ける。
 */
@Composable
internal fun repeat(times: Int, content: @Composable (Int) -> Unit) {
    for (index in 0 until times.coerceAtLeast(0)) {
        content(index)
    }
}

/**
 * Compose の receiver 解決で size が隠れる場合のフォールバック。
 * List の member size が解決できる場合はそちらが優先される。
 */
@Suppress("EXTENSION_SHADOWED_BY_MEMBER")
internal val Any.size: Int
    get() = 0
