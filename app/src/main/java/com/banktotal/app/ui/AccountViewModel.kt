package com.banktotal.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.banktotal.app.data.repository.AccountRepository

class AccountViewModel(application: Application) : AndroidViewModel(application) {
    val repository = AccountRepository(application)
}
