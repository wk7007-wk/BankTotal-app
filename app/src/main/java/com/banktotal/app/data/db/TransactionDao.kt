package com.banktotal.app.data.db

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update

@Dao
interface TransactionDao {

    @Insert
    suspend fun insert(transaction: TransactionEntity)

    @Update
    suspend fun update(transaction: TransactionEntity)

    /** 출금 내역 최신순 (최근 200건) */
    @Query("SELECT * FROM transactions WHERE transaction_type = '출금' ORDER BY timestamp DESC LIMIT 200")
    suspend fun getWithdrawals(): List<TransactionEntity>

    /** 입금 내역 최신순 */
    @Query("SELECT * FROM transactions WHERE transaction_type = '입금' ORDER BY timestamp DESC LIMIT 200")
    suspend fun getDeposits(): List<TransactionEntity>

    /** 날짜 범위 조회 (timestamp ms 기준) */
    @Query("SELECT * FROM transactions WHERE timestamp >= :startMs AND timestamp < :endMs ORDER BY timestamp DESC")
    suspend fun getByDateRange(startMs: Long, endMs: Long): List<TransactionEntity>

    /** 날짜 범위 조회 LiveData */
    @Query("SELECT * FROM transactions WHERE timestamp >= :startMs AND timestamp < :endMs ORDER BY timestamp DESC")
    fun getByDateRangeLive(startMs: Long, endMs: Long): LiveData<List<TransactionEntity>>

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

    /** 카테고리(거래상대)별 합계 */
    @Query("""
        SELECT counterparty as category, SUM(amount) as total, COUNT(*) as count
        FROM transactions
        WHERE transaction_type = :type
          AND timestamp >= :startMs AND timestamp < :endMs
          AND is_transfer = 0 AND is_duplicate = 0
        GROUP BY counterparty
        ORDER BY total DESC
    """)
    suspend fun getCategorySummary(type: String, startMs: Long, endMs: Long): List<CategorySummary>

    /** 오늘 거래 중 특정 타입 */
    @Query("SELECT * FROM transactions WHERE transaction_type = :type AND timestamp >= :todayStartMs ORDER BY timestamp DESC")
    suspend fun getTodayByType(type: String, todayStartMs: Long): List<TransactionEntity>

    @Query("SELECT COUNT(*) FROM transactions")
    suspend fun count(): Int

    @Query("DELETE FROM transactions")
    suspend fun deleteAll()
}

data class DailyDeposit(
    val day: String,
    val total: Long
)

data class CategorySummary(
    val category: String,
    val total: Long,
    val count: Int
)
