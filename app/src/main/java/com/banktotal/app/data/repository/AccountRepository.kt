package com.banktotal.app.data.repository

import android.content.Context
import androidx.lifecycle.LiveData
import com.banktotal.app.data.db.AccountDao
import com.banktotal.app.data.db.AccountEntity
import com.banktotal.app.data.db.BankDatabase
import com.banktotal.app.widget.WidgetUpdateHelper

class AccountRepository(private val context: Context) {
    private val dao: AccountDao = BankDatabase.getInstance(context).accountDao()

    fun getAllAccounts(): LiveData<List<AccountEntity>> = dao.getAllAccounts()
    fun getActiveAccounts(): LiveData<List<AccountEntity>> = dao.getActiveAccounts()
    fun getTotalBalance(): LiveData<Long?> = dao.getTotalBalance()

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
        WidgetUpdateHelper.updateWidget(context)
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
        WidgetUpdateHelper.updateWidget(context)
    }

    suspend fun updateAccount(account: AccountEntity) {
        dao.update(account.copy(lastUpdated = System.currentTimeMillis()))
        WidgetUpdateHelper.updateWidget(context)
    }

    suspend fun deleteAccount(account: AccountEntity) {
        dao.delete(account)
        WidgetUpdateHelper.updateWidget(context)
    }

    suspend fun toggleActive(account: AccountEntity) {
        dao.update(account.copy(isActive = !account.isActive))
        WidgetUpdateHelper.updateWidget(context)
    }

    suspend fun getTotalBalanceSync(): Long = dao.getTotalBalanceSync() ?: 0L

    suspend fun getLastUpdateTime(): Long = dao.getLastUpdateTime() ?: 0L
}
