package com.banktotal.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import com.banktotal.app.data.parser.SmsParserManager
import com.banktotal.app.data.repository.AccountRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class SmsBroadcastReceiver : BroadcastReceiver() {

    private val parserManager = SmsParserManager()

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages.isNullOrEmpty()) return

        val sender = messages[0].originatingAddress ?: ""
        val body = messages.joinToString("") { it.messageBody ?: "" }

        val parsed = parserManager.parse(sender, body) ?: return

        val repository = AccountRepository(context.applicationContext)
        val pendingResult = goAsync()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                repository.upsertFromSms(parsed)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
