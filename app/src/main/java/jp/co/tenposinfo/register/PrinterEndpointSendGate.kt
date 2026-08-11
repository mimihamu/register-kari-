package jp.co.tenposinfo.register

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

/**
 * 同一TCPプリンターへのESC/POS送信をプロセス内で直列化する。
 *
 * 呼び出し元（自動Worker、手動印刷、再印字、MemoryPrinterGateway経由）に依存せず、
 * host:port単位で1本だけが実送信区間へ入れる。異なるendpointは相互にブロックしない。
 *
 * ロック待機が設定時間を超えた場合はTCP接続前に失敗するため、紙が出たか不明な状態にはしない。
 */
internal object PrinterEndpointSendGate {
    private val locks = ConcurrentHashMap<String, ReentrantLock>()

    fun <T> withPermit(
        host: String,
        port: Int,
        waitMillis: Long,
        block: () -> T,
    ): T {
        val endpoint = endpointKey(host, port)
        val lock = locks.computeIfAbsent(endpoint) { ReentrantLock(true) }
        val acquired = try {
            lock.tryLock(waitMillis.coerceAtLeast(1L), TimeUnit.MILLISECONDS)
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw PrinterEndpointBusyException(endpoint, interrupted = true, cause = error)
        }
        if (!acquired) {
            throw PrinterEndpointBusyException(endpoint, interrupted = false)
        }
        return try {
            block()
        } finally {
            lock.unlock()
        }
    }

    fun endpointKey(host: String, port: Int): String =
        "${host.trim().lowercase()}:$port"
}

internal class PrinterEndpointBusyException(
    endpoint: String,
    interrupted: Boolean,
    cause: Throwable? = null,
) : RuntimeException(
    if (interrupted) {
        "プリンター送信待ちが中断されました（$endpoint）"
    } else {
        "プリンター送信待ちタイムアウト：他の印刷処理が使用中です（$endpoint）"
    },
    cause,
)
