package com.banktotal.app.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import com.banktotal.app.R
import com.banktotal.app.data.db.BankDatabase
import com.banktotal.app.data.parser.ParsedTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

object SettleBriefingHelper {

    private const val CHANNEL_ID = "settle_briefing"
    private const val NOTIF_ID = 1002
    private const val FB = "https://poskds-4ba60-default-rtdb.asia-southeast1.firebasedatabase.app"
    private val fmt = DecimalFormat("#,###")

    // 제네시스 키워드 (전각/반각 모두 매칭)
    private val genesisKw = listOf("제네시스", "genesis", "올원오픈주", "토스 (주)제", "(주)제")

    suspend fun show(context: Context, parsed: ParsedTransaction) = withContext(Dispatchers.IO) {
        try {
            createChannel(context)

            val dao = BankDatabase.getInstance(context).accountDao()
            val subtotal = dao.getSubtotalBalanceSync() ?: 0L

            val today = SimpleDateFormat("yyyy-MM-dd", Locale.KOREA).format(Date())
            val cal = Calendar.getInstance()
            val dow = cal.get(Calendar.DAY_OF_WEEK) - 1 // 0=일,1=월...6=토
            val dom = cal.get(Calendar.DAY_OF_MONTH)

            // Firebase에서 settle/manual + sfa_daily 가져오기
            val manual = fetchJson("$FB/banktotal/settle/manual.json")
            val sfaDay = fetchJson("$FB/banktotal/sfa_daily/$today.json")

            // 오늘 정산 항목 계산
            var settleIn = 0L
            var settleOut = 0L
            val names = mutableListOf<String>()

            if (manual != null) {
                val keys = manual.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val it = manual.optJSONObject(k) ?: continue
                    val name = it.optString("name", "")
                    var amount = it.optLong("amount", 0)
                    val type = it.optString("type", "출금")
                    val cycle = it.optString("cycle", "none")

                    val isToday = when (cycle) {
                        "daily" -> true
                        "monthly" -> it.optInt("dayOfMonth", 1) == dom
                        "weekly" -> it.optInt("dayOfWeek", 0) == dow
                        else -> false // none(단발) 은 날짜 매칭 복잡 → 스킵
                    }
                    if (!isToday || amount <= 0) continue

                    // SFA: daily 초기값 사용
                    if (name == "SFA" && sfaDay != null) {
                        val da = sfaDay.optLong("amount", 0)
                        if (da > 0) amount = da
                    }

                    if (type == "입금") {
                        settleIn += amount
                        names.add("+${fmtShort(amount)} $name")
                    } else {
                        settleOut += amount
                        names.add("-${fmtShort(amount)} $name")
                    }
                }
            }

            // 제네시스 매칭 체크
            val cpNorm = normFW(parsed.counterparty.lowercase())
            val isGenesis = genesisKw.any { cpNorm.contains(it.lowercase()) }
            val matchInfo = if (isGenesis) " [SFA 차감]" else ""

            val predicted = subtotal + settleIn - settleOut
            val txSign = if (parsed.transactionType == "입금") "+" else "-"
            val title = "${parsed.bankName} ${parsed.transactionType} ${txSign}${fmtShort(parsed.transactionAmount)}$matchInfo"
            val body = "소계 ${fmt.format(subtotal)} | 오늘예상 ${fmt.format(predicted)}\n${names.joinToString(", ").ifEmpty { "정산항목 없음" }}"

            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val notif = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle(title)
                .setContentText("소계 ${fmt.format(subtotal)} | 오늘예상 ${fmt.format(predicted)}")
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build()
            nm.notify(NOTIF_ID, notif)

            LogWriter.sys("정산 브리핑: $title | 예상${fmt.format(predicted)}")
        } catch (e: Exception) {
            LogWriter.err("정산 브리핑 실패: ${e.message}")
        }
    }

    private fun fetchJson(url: String): JSONObject? = try {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 3000
        conn.readTimeout = 3000
        val text = conn.inputStream.bufferedReader().readText()
        conn.disconnect()
        if (text == "null") null else JSONObject(text)
    } catch (_: Exception) { null }

    private fun fmtShort(v: Long): String {
        return if (v >= 10000) "${v / 10000}만" else fmt.format(v)
    }

    /** 전각→반각 변환 */
    private fun normFW(s: String): String = s
        .replace('（', '(').replace('）', ')')
        .replace('\u3000', ' ')

    private fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "정산 브리핑", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "거래 발생 시 정산 요약 알림"
            }
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(ch)
        }
    }
}
