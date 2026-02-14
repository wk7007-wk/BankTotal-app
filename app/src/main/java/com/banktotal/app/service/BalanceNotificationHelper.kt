package com.banktotal.app.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.banktotal.app.R
import com.banktotal.app.data.db.BankDatabase
import com.banktotal.app.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object BalanceNotificationHelper {

    private const val CHANNEL_ID = "banktotal_balance"
    private const val NOTIFICATION_ID = 1001
    private val decimalFormat = DecimalFormat("#,###")
    private val dateFormat = SimpleDateFormat("MM/dd HH:mm", Locale.KOREA)
    private val abbr = mapOf("KB국민" to "k", "하나" to "ha", "신협" to "s", "신한" to "sh")
    private val bankOrder = listOf("KB국민", "하나", "신협", "신한")

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "잔액 알림",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "계좌 잔액 합산 상주 알림"
                setShowBadge(false)
            }
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    fun update(context: Context) {
        CoroutineScope(Dispatchers.IO).launch {
            val dao = BankDatabase.getInstance(context).accountDao()
            val total = dao.getTotalBalanceSync() ?: 0L
            val accounts = dao.getAllAccountsSync()
            val now = dateFormat.format(Date())

            val parts = mutableListOf<String>()
            for (bank in bankOrder) {
                val acc = accounts.find { it.bankName == bank && it.isActive }
                val ab = abbr[bank] ?: bank
                if (acc != null) {
                    parts.add("$ab ${decimalFormat.format(acc.balance)}")
                } else {
                    parts.add("$ab ---")
                }
            }
            parts.add(now)
            val content = parts.joinToString(" | ")

            // 앱 열기 인텐트
            val openIntent = Intent(context, MainActivity::class.java)
            val openPendingIntent = PendingIntent.getActivity(
                context, 0, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            // 업데이트(스캔) 인텐트
            val scanIntent = Intent(context, MainActivity::class.java).apply {
                putExtra("action_scan_sms", true)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            val scanPendingIntent = PendingIntent.getActivity(
                context, 1, scanIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            createChannel(context)

            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle("${decimalFormat.format(total)}원")
                .setContentText(content)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOnlyAlertOnce(true)
                .setContentIntent(openPendingIntent)
                .addAction(
                    android.R.drawable.ic_popup_sync,
                    "업데이트",
                    scanPendingIntent
                )
                .build()

            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(NOTIFICATION_ID, notification)
        }
    }
}
