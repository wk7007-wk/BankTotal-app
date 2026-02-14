package com.banktotal.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.banktotal.app.service.BalanceNotificationHelper

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            BalanceNotificationHelper.createChannel(context)
            BalanceNotificationHelper.update(context)
        }
    }
}
