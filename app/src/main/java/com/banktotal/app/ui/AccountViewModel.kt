package com.banktotal.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.banktotal.app.data.db.AccountEntity
import com.banktotal.app.data.repository.AccountRepository
import com.banktotal.app.data.sms.SmsHistoryReader
import kotlinx.coroutines.launch

class AccountViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = AccountRepository(application)
    private val smsHistoryReader = SmsHistoryReader(application)

    val allAccounts: LiveData<List<AccountEntity>> = repository.getAllAccounts()
    val totalBalance: LiveData<Long?> = repository.getTotalBalance()

    private val _isScanning = MutableLiveData(false)
    val isScanning: LiveData<Boolean> = _isScanning

    private val _scanResult = MutableLiveData<SmsHistoryReader.ScanResult?>()
    val scanResult: LiveData<SmsHistoryReader.ScanResult?> = _scanResult

    fun scanSmsHistory() {
        if (_isScanning.value == true) return
        _isScanning.value = true
        viewModelScope.launch {
            try {
                val result = smsHistoryReader.scanInbox()
                _scanResult.value = result
            } finally {
                _isScanning.value = false
            }
        }
    }

    fun runFirstScanIfNeeded() {
        if (smsHistoryReader.isFirstScanDone()) return
        _isScanning.value = true
        viewModelScope.launch {
            try {
                val result = smsHistoryReader.scanInbox()
                smsHistoryReader.markFirstScanDone()
                _scanResult.value = result
            } finally {
                _isScanning.value = false
            }
        }
    }

    fun clearScanResult() {
        _scanResult.value = null
    }

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
}
