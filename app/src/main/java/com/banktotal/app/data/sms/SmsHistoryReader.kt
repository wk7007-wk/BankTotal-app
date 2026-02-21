package com.banktotal.app.data.sms

import android.content.Context
import android.net.Uri
import android.util.Log
import com.banktotal.app.data.parser.SmsParserManager
import com.banktotal.app.data.repository.AccountRepository
import com.banktotal.app.service.BalanceNotificationHelper
import com.banktotal.app.widget.WidgetUpdateHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SmsHistoryReader(private val context: Context) {

    companion object {
        private const val TAG = "SmsHistoryReader"
        private const val SMS_URI = "content://sms/inbox"
        private const val PREFS_NAME = "banktotal_prefs"
        private const val KEY_FIRST_SCAN_DONE = "first_sms_scan_done"

        val BANK_SENDERS = setOf(
            "16449999", "15880000",  // KB국민
            "15991111",              // 하나
            "15666000", "15882222"   // 신협
        )
    }

    private val parserManager = SmsParserManager()
    private val repository = AccountRepository(context.applicationContext)

    data class ScanResult(
        val totalSmsRead: Int,
        val bankSmsFound: Int,
        val accountsUpdated: Int,
        val errors: List<String> = emptyList()
    )

    suspend fun scanInbox(maxMessages: Int = 1000): ScanResult = withContext(Dispatchers.IO) {
        var totalRead = 0
        var bankFound = 0
        var updated = 0
        val errors = mutableListOf<String>()

        val cursor = try {
            context.contentResolver.query(
                Uri.parse(SMS_URI),
                arrayOf("_id", "address", "body", "date"),
                null, null,
                "date ASC"
            )
        } catch (e: SecurityException) {
            Log.e(TAG, "READ_SMS permission not granted", e)
            errors.add("SMS 읽기 권한이 없습니다")
            return@withContext ScanResult(0, 0, 0, errors)
        }

        cursor?.use { c ->
            val addressIdx = c.getColumnIndexOrThrow("address")
            val bodyIdx = c.getColumnIndexOrThrow("body")
            val dateIdx = c.getColumnIndexOrThrow("date")

            val totalCount = c.count
            val startPos = if (totalCount > maxMessages) totalCount - maxMessages else 0
            if (startPos > 0) c.moveToPosition(startPos - 1)

            while (c.moveToNext()) {
                totalRead++
                val rawAddress = c.getString(addressIdx) ?: continue
                val body = c.getString(bodyIdx) ?: continue
                val smsDate = c.getLong(dateIdx)

                val normalized = rawAddress.replace("-", "").replace(" ", "")
                    .replace("+82", "0").trimStart('0')

                val isBankSender = BANK_SENDERS.any { normalized.endsWith(it) }
                if (!isBankSender || !body.contains("잔액")) continue

                bankFound++

                val parsed = parserManager.parse(rawAddress, body) ?: continue
                val withDate = parsed.copy(timestamp = smsDate)
                try {
                    repository.upsertFromSms(withDate)
                    updated++
                } catch (e: Exception) {
                    Log.e(TAG, "DB upsert failed: ${parsed.bankName}", e)
                    errors.add("${parsed.bankName} 저장 실패")
                }
            }
        }

        BalanceNotificationHelper.update(context)
        WidgetUpdateHelper.updateWidget(context)

        Log.i(TAG, "Scan: read=$totalRead, bank=$bankFound, updated=$updated")
        ScanResult(totalRead, bankFound, updated, errors)
    }

    fun isFirstScanDone(): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_FIRST_SCAN_DONE, false)
    }

    fun markFirstScanDone() {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_FIRST_SCAN_DONE, true).apply()
    }
}
