package com.banktotal.app

import android.app.Application
import com.banktotal.app.data.db.BankDatabase
import com.banktotal.app.service.BalanceNotificationHelper

class BankTotalApp : Application() {
    val database: BankDatabase by lazy { BankDatabase.getInstance(this) }

    override fun onCreate() {
        super.onCreate()
        BalanceNotificationHelper.createChannel(this)
        BalanceNotificationHelper.update(this)
    }
}
