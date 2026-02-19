package com.banktotal.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.viewModelScope
import com.banktotal.app.data.db.AccountEntity
import com.banktotal.app.data.repository.AccountRepository
import com.banktotal.app.service.BalanceNotificationHelper
import kotlinx.coroutines.launch

class AccountViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = AccountRepository(application)

    val allAccounts: LiveData<List<AccountEntity>> = repository.getAllAccounts()
    val totalBalance: LiveData<Long?> = repository.getTotalBalance()
    val subtotalBalance: LiveData<Long?> = repository.getSubtotalBalance()

    fun addManualAccount(bankName: String, accountNumber: String, displayName: String, balance: Long) {
        viewModelScope.launch { repository.addManualAccount(bankName, accountNumber, displayName, balance) }
    }

    fun updateAccount(account: AccountEntity) {
        viewModelScope.launch { repository.updateAccount(account) }
    }

    fun deleteAccount(account: AccountEntity) {
        viewModelScope.launch { repository.deleteAccount(account) }
    }

    fun toggleActive(account: AccountEntity) {
        viewModelScope.launch { repository.toggleActive(account) }
    }

    fun deleteAllAccounts() {
        viewModelScope.launch {
            repository.deleteAll()
            BalanceNotificationHelper.update(getApplication())
        }
    }
}
