package com.banktotal.app.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.banktotal.app.R
import com.banktotal.app.data.repository.AccountRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object WidgetUpdateHelper {

    private val decimalFormat = DecimalFormat("#,###")
    private val dateFormat = SimpleDateFormat("MM/dd HH:mm", Locale.KOREA)

    fun updateWidget(context: Context) {
        val appWidgetManager = AppWidgetManager.getInstance(context)
        val widgetComponent = ComponentName(context, BalanceWidgetProvider::class.java)
        val widgetIds = appWidgetManager.getAppWidgetIds(widgetComponent)

        if (widgetIds.isEmpty()) return

        CoroutineScope(Dispatchers.IO).launch {
            val repository = AccountRepository(context.applicationContext)
            val totalBalance = repository.getTotalBalanceSync()
            val subtotalBalance = repository.getSubtotalBalanceSync()
            val lastUpdate = repository.getLastUpdateTime()

            val hasNegative = totalBalance != subtotalBalance
            val balanceText = if (hasNegative) {
                "소계 ${decimalFormat.format(subtotalBalance)}원\n합계 ${decimalFormat.format(totalBalance)}원"
            } else {
                "${decimalFormat.format(totalBalance)}원"
            }

            val views = RemoteViews(context.packageName, R.layout.widget_balance)
            views.setTextViewText(R.id.widget_total_balance, balanceText)

            val updateText = if (lastUpdate > 0) {
                "업데이트: ${dateFormat.format(Date(lastUpdate))}"
            } else {
                "업데이트: --"
            }
            views.setTextViewText(R.id.widget_last_updated, updateText)

            val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            if (launchIntent != null) {
                val pendingIntent = android.app.PendingIntent.getActivity(
                    context, 0, launchIntent,
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
                )
                views.setOnClickPendingIntent(R.id.widget_root, pendingIntent)
            }

            for (widgetId in widgetIds) {
                appWidgetManager.updateAppWidget(widgetId, views)
            }
        }
    }
}
