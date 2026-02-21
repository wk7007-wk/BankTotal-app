package com.banktotal.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface TransactionDao {

    @Insert
    suspend fun insert(transaction: TransactionEntity)

    /** 출금 내역 최신순 (최근 200건) */
    @Query("SELECT * FROM transactions WHERE transaction_type = '출금' ORDER BY timestamp DESC LIMIT 200")
    suspend fun getWithdrawals(): List<TransactionEntity>

    /** 일별 입금 합계 (최근 60일) */
    @Query("""
        SELECT date(timestamp/1000, 'unixepoch', 'localtime') as day,
               SUM(amount) as total
        FROM transactions
        WHERE transaction_type = '입금'
        GROUP BY day
        ORDER BY day DESC
        LIMIT 60
    """)
    suspend fun getDailyDeposits(): List<DailyDeposit>

    @Query("DELETE FROM transactions")
    suspend fun deleteAll()
}

data class DailyDeposit(
    val day: String,
    val total: Long
)
