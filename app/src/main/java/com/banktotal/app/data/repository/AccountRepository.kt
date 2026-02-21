package com.banktotal.app.data.repository

import android.content.Context
import androidx.lifecycle.LiveData
import com.banktotal.app.data.db.AccountDao
import com.banktotal.app.data.db.AccountEntity
import com.banktotal.app.data.db.BankDatabase
import com.banktotal.app.data.db.DailyDeposit
import com.banktotal.app.data.db.TransactionDao
import com.banktotal.app.data.db.TransactionEntity
import com.banktotal.app.service.BalanceNotificationHelper
import com.banktotal.app.widget.WidgetUpdateHelper

class AccountRepository(private val context: Context) {
    private val dao: AccountDao = BankDatabase.getInstance(context).accountDao()
    private val txDao: TransactionDao = BankDatabase.getInstance(context).transactionDao()

    fun getAllAccounts(): LiveData<List<AccountEntity>> = dao.getAllAccounts()
    fun getActiveAccounts(): LiveData<List<AccountEntity>> = dao.getActiveAccounts()
    fun getTotalBalance(): LiveData<Long?> = dao.getTotalBalance()
    fun getSubtotalBalance(): LiveData<Long?> = dao.getSubtotalBalance()

    private fun refreshDisplays() {
        WidgetUpdateHelper.updateWidget(context)
        BalanceNotificationHelper.update(context)
    }

    suspend fun upsertFromSms(
        bankName: String,
        accountNumber: String,
        balance: Long,
        transactionType: String,
        transactionAmount: Long
    ) {
        val existing = dao.findAccount(bankName, accountNumber)
        if (existing != null) {
            dao.update(
                existing.copy(
                    balance = balance,
                    lastTransactionType = transactionType,
                    lastTransactionAmount = transactionAmount,
                    lastUpdated = System.currentTimeMillis()
                )
            )
        } else {
            dao.insert(
                AccountEntity(
                    bankName = bankName,
                    accountNumber = accountNumber,
                    balance = balance,
                    lastTransactionType = transactionType,
                    lastTransactionAmount = transactionAmount,
                    lastUpdated = System.currentTimeMillis()
                )
            )
        }
        // 출금 내역만 기록 (입금은 통장간 이동이라 제외)
        if (transactionType == "출금") {
            txDao.insert(
                TransactionEntity(
                    bankName = bankName,
                    transactionType = transactionType,
                    amount = transactionAmount
                )
            )
        }
        refreshDisplays()
    }

    suspend fun addManualAccount(
        bankName: String,
        accountNumber: String,
        displayName: String,
        balance: Long
    ) {
        dao.insert(
            AccountEntity(
                bankName = bankName,
                accountNumber = accountNumber,
                displayName = displayName,
                balance = balance,
                isManual = true,
                lastUpdated = System.currentTimeMillis()
            )
        )
        refreshDisplays()
    }

    suspend fun updateAccount(account: AccountEntity) {
        dao.update(account.copy(lastUpdated = System.currentTimeMillis()))
        refreshDisplays()
    }

    suspend fun deleteAccount(account: AccountEntity) {
        dao.delete(account)
        refreshDisplays()
    }

    suspend fun toggleActive(account: AccountEntity) {
        dao.update(account.copy(isActive = !account.isActive))
        refreshDisplays()
    }

    suspend fun deleteAll() {
        dao.deleteAll()
        txDao.deleteAll()
        refreshDisplays()
    }

    suspend fun getTotalBalanceSync(): Long = dao.getTotalBalanceSync() ?: 0L

    suspend fun getSubtotalBalanceSync(): Long = dao.getSubtotalBalanceSync() ?: 0L

    suspend fun getLastUpdateTime(): Long = dao.getLastUpdateTime() ?: 0L

    suspend fun upsertBlockAmount(bankName: String, amount: Long) {
        val accountNumber = "BLOCK"
        val existing = dao.findAccount(bankName, accountNumber)
        val negativeAmount = -kotlin.math.abs(amount)
        if (existing != null) {
            dao.update(
                existing.copy(
                    balance = negativeAmount,
                    lastUpdated = System.currentTimeMillis()
                )
            )
        } else {
            dao.insert(
                AccountEntity(
                    bankName = bankName,
                    accountNumber = accountNumber,
                    displayName = "$bankName BLOCK",
                    balance = negativeAmount,
                    lastUpdated = System.currentTimeMillis()
                )
            )
        }
        refreshDisplays()
    }

    /** 출금 내역 (최근 200건) */
    suspend fun getWithdrawals(): List<TransactionEntity> = txDao.getWithdrawals()

    /** 일별 입금 합계 */
    suspend fun getDailyDeposits(): List<DailyDeposit> = txDao.getDailyDeposits()
}
