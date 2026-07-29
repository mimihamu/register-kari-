package jp.co.tenposinfo.register

import android.database.sqlite.SQLiteDatabase

inline fun SQLiteDatabase.runInTransaction(block: SQLiteDatabase.() -> Unit) {
    beginTransaction()
    try {
        block()
        setTransactionSuccessful()
    } finally {
        endTransaction()
    }
}
