package jp.co.tenposinfo.register

import java.io.FileNotFoundException
import java.io.IOException

enum class BackupSafFailureCategoryV147 {
    PERMISSION_DENIED,
    MEDIA_OR_DOCUMENT_UNAVAILABLE,
    IO_FAILURE,
}

/** BKP-009: turn SAF provider failures into user-visible errors instead of app crashes. */
object BackupSafAccessV147 {
    fun classify(error: Throwable): BackupSafFailureCategoryV147? = when (error) {
        is SecurityException -> BackupSafFailureCategoryV147.PERMISSION_DENIED
        is FileNotFoundException -> BackupSafFailureCategoryV147.MEDIA_OR_DOCUMENT_UNAVAILABLE
        is IOException -> BackupSafFailureCategoryV147.IO_FAILURE
        else -> null
    }

    fun userMessage(operation: String, error: Throwable): String? = when (classify(error)) {
        BackupSafFailureCategoryV147.PERMISSION_DENIED ->
            "$operation: 保存先または取込元へのアクセスが拒否されました"
        BackupSafFailureCategoryV147.MEDIA_OR_DOCUMENT_UNAVAILABLE ->
            "$operation: USB等の媒体が取り外されたか、ファイルを開けません"
        BackupSafFailureCategoryV147.IO_FAILURE ->
            "$operation: 外部媒体との入出力に失敗しました。接続状態と空き容量を確認してください"
        null -> null
    }

    fun <T> guard(operation: String, block: () -> T): T = try {
        block()
    } catch (error: SecurityException) {
        throw IllegalStateException(requireNotNull(userMessage(operation, error)), error)
    } catch (error: FileNotFoundException) {
        throw IllegalStateException(requireNotNull(userMessage(operation, error)), error)
    } catch (error: IOException) {
        throw IllegalStateException(requireNotNull(userMessage(operation, error)), error)
    }
}
