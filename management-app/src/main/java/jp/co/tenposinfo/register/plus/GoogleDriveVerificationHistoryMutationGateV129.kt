package jp.co.tenposinfo.register.plus

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal object GoogleDriveVerificationHistoryMutationGateV129 {
    private val lock = ReentrantLock(true)

    fun <T> exclusive(block: () -> T): T = lock.withLock(block)
}
