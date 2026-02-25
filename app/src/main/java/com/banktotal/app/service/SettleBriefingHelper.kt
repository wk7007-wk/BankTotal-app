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
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
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
    private const val GEMINI_URL = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent"
    private val fmt = DecimalFormat("#,###")
    private const val COOLDOWN_MS = 180_000L // 3분 쿨다운
    private var lastBriefingTime = 0L
    private var pendingParsed: ParsedTransaction? = null

    suspend fun show(context: Context, parsed: ParsedTransaction) = withContext(Dispatchers.IO) {
        try {
            // 쿨다운: 3분 이내 연속 거래 → 마지막 건만 브리핑 (주유소 선승인 등)
            val now = System.currentTimeMillis()
            if (now - lastBriefingTime < COOLDOWN_MS) {
                pendingParsed = parsed
                LogWriter.sys("정산 브리핑: 쿨다운 (${(COOLDOWN_MS - (now - lastBriefingTime)) / 1000}초 남음)")
                // 쿨다운 끝나면 마지막 건으로 브리핑
                kotlinx.coroutines.delay(COOLDOWN_MS - (now - lastBriefingTime) + 500)
                if (pendingParsed != parsed) return@withContext // 더 새로운 거래가 있으면 스킵
            }
            lastBriefingTime = System.currentTimeMillis()
            pendingParsed = null

            createChannel(context)

            val apiKey = GeminiService.getApiKey()
            if (apiKey.isEmpty()) {
                LogWriter.err("정산 브리핑: Gemini 키 없음")
                return@withContext
            }

            // 데이터 수집
            val dao = BankDatabase.getInstance(context).accountDao()
            val subtotal = dao.getSubtotalBalanceSync() ?: 0L
            val accounts = dao.getAllAccountsSync()

            val today = SimpleDateFormat("yyyy-MM-dd", Locale.KOREA).format(Date())
            val cal = Calendar.getInstance()
            val dow = cal.get(Calendar.DAY_OF_WEEK) - 1
            val dom = cal.get(Calendar.DAY_OF_MONTH)
            val dayName = arrayOf("일","월","화","수","목","금","토")[dow]

            val manual = fetchJson("$FB/banktotal/settle/manual.json")
            val sfaDay = fetchJson("$FB/banktotal/sfa_daily/$today.json")
            val rules = fetchText("$FB/banktotal/ai_rules/briefing.json")
            val allTx = fetchJson("$FB/banktotal/transactions.json")

            // 오늘 자정 기준 타임스탬프
            val todayCal = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }
            val todayStart = todayCal.timeInMillis

            // 오늘 거래 추출
            val todayTxList = mutableListOf<JSONObject>()
            if (allTx != null) {
                val txKeys = allTx.keys()
                while (txKeys.hasNext()) {
                    val tk = txKeys.next()
                    val tx = allTx.optJSONObject(tk) ?: continue
                    if (tx.optLong("ts", 0) >= todayStart) todayTxList.add(tx)
                }
            }
            val todayWithdrawals = todayTxList.filter { it.optString("type") == "출금" }
            val todayDeposits = todayTxList.filter { it.optString("type") == "입금" }
            val todayOutTotal = todayWithdrawals.sumOf { it.optLong("amount", 0) }
            val todayInTotal = todayDeposits.sumOf { it.optLong("amount", 0) }

            // 오늘 정산 항목 목록 + 매칭 분석
            data class SettleItem(val name: String, val type: String, val amount: Long, val cycle: String)
            val todayItems = mutableListOf<SettleItem>()
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
                        else -> false
                    }
                    if (!isToday) continue

                    if (name == "SFA" && sfaDay != null) {
                        val da = sfaDay.optLong("amount", 0)
                        if (da > 0) amount = da
                    }

                    todayItems.add(SettleItem(name, type, amount, cycle))
                }
            }

            // 매칭: 오늘 출금과 정산 항목 비교 (금액 ±10% 범위)
            val matched = mutableListOf<String>()
            val unmatched = mutableListOf<String>()
            val usedTx = mutableSetOf<Int>()
            var remainOut = 0L
            var expectIn = 0L

            for (item in todayItems) {
                if (item.type == "입금") {
                    // 입금 항목: 오늘 입금에서 매칭 확인
                    val matchTx = todayDeposits.withIndex().firstOrNull { (i, tx) ->
                        i !in usedTx && kotlin.math.abs(tx.optLong("amount", 0) - item.amount) <= item.amount * 0.15
                    }
                    if (matchTx != null) {
                        usedTx.add(matchTx.index)
                        matched.add("✓ ${item.name} 입금 ${fmt.format(matchTx.value.optLong("amount",0))}원 (${item.amount.let { fmt.format(it) }}원 예정)")
                    } else {
                        unmatched.add("○ ${item.name} 입금 ${fmt.format(item.amount)}원 (미입금)")
                        expectIn += item.amount
                    }
                } else {
                    // 출금 항목: 오늘 출금에서 매칭 확인
                    val matchTx = todayWithdrawals.withIndex().firstOrNull { (i, tx) ->
                        i !in usedTx && kotlin.math.abs(tx.optLong("amount", 0) - item.amount) <= item.amount * 0.15
                    }
                    if (matchTx != null) {
                        usedTx.add(matchTx.index)
                        matched.add("✓ ${item.name} 출금 ${fmt.format(matchTx.value.optLong("amount",0))}원 (${item.amount.let { fmt.format(it) }}원 예정)")
                    } else {
                        unmatched.add("○ ${item.name} 출금 ${fmt.format(item.amount)}원 (미출금)")
                        remainOut += item.amount
                    }
                }
            }

            // 미매칭 거래 (정산 항목에 없는 오늘 거래)
            val unknownTx = mutableListOf<String>()
            todayWithdrawals.withIndex().filter { it.index !in usedTx }.forEach {
                unknownTx.add("? 출금 ${fmt.format(it.value.optLong("amount",0))}원 ${it.value.optString("counterparty","")}")
            }
            todayDeposits.withIndex().filter { it.index !in usedTx }.forEach {
                unknownTx.add("? 입금 ${fmt.format(it.value.optLong("amount",0))}원 ${it.value.optString("counterparty","")}")
            }

            // 예상 잔고 계산
            val expectedBalance = subtotal - remainOut + expectIn
            val marginToNegative = subtotal - remainOut // 입금 미포함 최악 시나리오

            // 계좌 잔고 요약
            val acctSummary = accounts.filter { it.isActive }.joinToString(", ") {
                "${it.bankName} ${fmt.format(it.balance)}원"
            }

            // Gemini 프롬프트
            val defaultRules = """1. 이 거래가 오늘 정산 항목 중 어떤 것과 매칭되는지 판단
2. 제네시스/genesis/올원오픈주/토스(주)제 → SFA 차감 거래
3. 매칭되면 어떤 항목인지, 남은 금액 안내
4. 매칭 안 되면 미확인 거래로 안내
5. 한줄 요약 (40자 이내) + 상세 (5줄 이내)
6. 상세에 반드시 포함: 예상잔고, 마이너스까지 여유, 매칭/미매칭 현황
7. 응답 형식: {"title":"한줄요약","body":"상세내용"}
8. 반드시 순수 JSON만 응답"""

            val ruleText = if (rules != "null" && rules.isNotEmpty()) {
                rules.removeSurrounding("\"").replace("\\n", "\n")
            } else defaultRules

            val matchSummary = buildString {
                if (matched.isNotEmpty()) { appendLine("[매칭 완료]"); matched.forEach { appendLine(it) } }
                if (unmatched.isNotEmpty()) { appendLine("[미처리]"); unmatched.forEach { appendLine(it) } }
                if (unknownTx.isNotEmpty()) { appendLine("[미확인 거래]"); unknownTx.forEach { appendLine(it) } }
            }

            val prompt = """당신은 정산 브리핑 AI입니다.

[규칙]
$ruleText

[발생 거래]
은행: ${parsed.bankName}
유형: ${parsed.transactionType}
금액: ${fmt.format(parsed.transactionAmount)}원
거래상대: ${parsed.counterparty}
잔액: ${fmt.format(parsed.balance)}원

[현재 계좌]
소계(양수만): ${fmt.format(subtotal)}원
$acctSummary

[오늘($today ${dayName}요일) 정산 현황]
오늘 입금: ${fmt.format(todayInTotal)}원 (${todayDeposits.size}건)
오늘 출금: ${fmt.format(todayOutTotal)}원 (${todayWithdrawals.size}건)
남은 예정 출금: ${fmt.format(remainOut)}원
남은 예정 입금: ${fmt.format(expectIn)}원

[예상 잔고]
현재 소계: ${fmt.format(subtotal)}원
예상 잔고(입금 포함): ${fmt.format(expectedBalance)}원
최악 잔고(입금 제외): ${fmt.format(marginToNegative)}원
마이너스까지: ${if (marginToNegative > 0) "${fmt.format(marginToNegative)}원 여유" else "이미 부족 ${fmt.format(-marginToNegative)}원"}

[매칭 상세]
$matchSummary

[SFA 오늘 초기값]
${if (sfaDay != null) "${fmt.format(sfaDay.optLong("amount", 0))}원" else "미설정"}

JSON으로 응답하세요."""

            val result = callGemini(apiKey, prompt)
            if (result == null) {
                LogWriter.err("정산 브리핑: Gemini 응답 없음")
                showFallback(context, parsed, subtotal)
                return@withContext
            }

            val jsonStr = result.replace(Regex("```json\\s*"), "").replace(Regex("```\\s*"), "").trim()
            val json = JSONObject(jsonStr)
            val title = json.optString("title", "${parsed.bankName} ${parsed.transactionType}")
            val body = json.optString("body", "소계 ${fmt.format(subtotal)}원")

            showNotification(context, title, body)
            LogWriter.sys("정산 브리핑: $title")

        } catch (e: Exception) {
            LogWriter.err("정산 브리핑 실패: ${e.message}")
            // 폴백: 기본 알림
            try {
                val dao = BankDatabase.getInstance(context).accountDao()
                val sub = dao.getSubtotalBalanceSync() ?: 0L
                showFallback(context, parsed, sub)
            } catch (_: Exception) {}
        }
    }

    /** Gemini 실패 시 기본 알림 */
    private fun showFallback(context: Context, parsed: ParsedTransaction, subtotal: Long) {
        val sign = if (parsed.transactionType == "입금") "+" else "-"
        showNotification(
            context,
            "${parsed.bankName} ${parsed.transactionType} $sign${fmtShort(parsed.transactionAmount)}",
            "소계 ${fmt.format(subtotal)}원"
        )
    }

    private fun callGemini(apiKey: String, prompt: String): String? {
        val reqBody = JSONObject()
        val contents = JSONArray()
        val content = JSONObject()
        val parts = JSONArray()
        parts.put(JSONObject().put("text", prompt))
        content.put("parts", parts)
        contents.put(content)
        reqBody.put("contents", contents)

        val conn = URL("$GEMINI_URL?key=$apiKey").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.doOutput = true
        conn.connectTimeout = 8000
        conn.readTimeout = 10000
        OutputStreamWriter(conn.outputStream).use { it.write(reqBody.toString()) }

        val code = conn.responseCode
        if (code !in 200..299) {
            conn.disconnect()
            return null
        }
        val resp = conn.inputStream.bufferedReader().readText()
        conn.disconnect()
        return JSONObject(resp)
            .optJSONArray("candidates")
            ?.optJSONObject(0)
            ?.optJSONObject("content")
            ?.optJSONArray("parts")
            ?.optJSONObject(0)
            ?.optString("text")
    }

    private fun fetchJson(url: String): JSONObject? = try {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 3000; conn.readTimeout = 3000
        val text = conn.inputStream.bufferedReader().readText()
        conn.disconnect()
        if (text == "null") null else JSONObject(text)
    } catch (_: Exception) { null }

    private fun fetchText(url: String): String = try {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 3000; conn.readTimeout = 3000
        val text = conn.inputStream.bufferedReader().readText()
        conn.disconnect(); text
    } catch (_: Exception) { "" }

    private fun fmtShort(v: Long): String = if (v >= 10000) "${v / 10000}만" else fmt.format(v)

    private fun showNotification(context: Context, title: String, body: String) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notif = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(title)
            .setContentText(body.split("\n").first())
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        nm.notify(NOTIF_ID, notif)
    }

    private fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "정산 브리핑", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "거래 발생 시 AI 정산 브리핑"
            }
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(ch)
        }
    }
}
