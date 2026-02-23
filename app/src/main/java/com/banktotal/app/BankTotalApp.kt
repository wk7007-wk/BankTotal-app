package com.banktotal.app

import android.app.Application
import com.banktotal.app.data.db.BankDatabase
import com.banktotal.app.service.BalanceNotificationHelper
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BankTotalApp : Application() {
    val database: BankDatabase by lazy { BankDatabase.getInstance(this) }

    override fun onCreate() {
        super.onCreate()

        // 크래시 로그 파일 저장
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                val time = SimpleDateFormat("MM-dd HH:mm:ss", Locale.KOREA).format(Date())
                val log = "[$time] ${throwable.message}\n$sw\n"
                File("/sdcard/Download/BankTotal_crash.txt").appendText(log)
            } catch (_: Exception) {}
            defaultHandler?.uncaughtException(thread, throwable)
        }

        BalanceNotificationHelper.createChannel(this)
        BalanceNotificationHelper.update(this)
    }
}
