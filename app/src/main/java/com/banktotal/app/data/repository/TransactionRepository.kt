package com.banktotal.app.data.repository

import android.content.Context
import com.banktotal.app.data.db.BankDatabase
import com.banktotal.app.data.db.CategorySummary
import com.banktotal.app.data.db.TransactionEntity
import java.util.Calendar

/**
 * 거래 내역 조회 + 이체/중복 감지
 *
 * [규칙]
 * - 이체/중복 감지는 마킹만 (삭제 절대 금지)
 * - isTransfer: 동일금액 + 5분이내 + 다른은행 + 입출금반대
 * - isDuplicate: 같은은행 + 금액 + 타입, 60초이내
 * - 제외 대상도 UI에 표시 (opacity 35% + 취소선)
 */
class TransactionRepository(context: Context) {

    private val dao = BankDatabase.getInstance(context).transactionDao()

    suspend fun getByDateRange(startMs: Long, endMs: Long): List<TransactionEntity> =
        dao.getByDateRange(startMs, endMs)

    suspend fun getWithdrawals(): List<TransactionEntity> = dao.getWithdrawals()

    suspend fun getDeposits(): List<TransactionEntity> = dao.getDeposits()

    suspend fun getCategorySummary(type: String, startMs: Long, endMs: Long): List<CategorySummary> =
        dao.getCategorySummary(type, startMs, endMs)

    /** 오늘 자정부터의 거래 */
    suspend fun getTodayTransactions(type: String): List<TransactionEntity> {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        return dao.getTodayByType(type, cal.timeInMillis)
    }

    /**
     * 이체/중복 감지 후 마킹 (기존 markTransactions 포팅)
     * 새 거래 INSERT 후 호출
     */
    suspend fun markTransferAndDuplicate(newTx: TransactionEntity) {
        val fiveMinAgo = newTx.timestamp - 300_000
        val oneMinAgo = newTx.timestamp - 60_000
        val recent = dao.getByDateRange(fiveMinAgo, newTx.timestamp + 1000)

        for (other in recent) {
            if (other.id == newTx.id) continue

            // 이체 감지: 동일금액 + 다른은행 + 반대타입
            if (other.amount == newTx.amount &&
                other.bankName != newTx.bankName &&
                other.transactionType != newTx.transactionType &&
                !other.isTransfer
            ) {
                dao.update(other.copy(isTransfer = true))
                dao.update(newTx.copy(isTransfer = true))
                return
            }

            // 중복 감지: 같은은행 + 금액 + 타입 + 60초이내
            if (other.bankName == newTx.bankName &&
                other.amount == newTx.amount &&
                other.transactionType == newTx.transactionType &&
                other.timestamp >= oneMinAgo &&
                !other.isDuplicate
            ) {
                dao.update(newTx.copy(isDuplicate = true))
                return
            }
        }
    }
}
