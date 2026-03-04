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

            // 입금은 AI 브리핑/매칭 불필요 — 간단 알림만
            if (parsed.transactionType == "입금") {
                val dao = BankDatabase.getInstance(context).accountDao()
                val subtotal = dao.getSubtotalBalanceSync() ?: 0L
                showFallback(context, parsed, subtotal)
                LogWriter.sys("정산 브리핑: 입금 → 간단 알림 (${parsed.bankName} +${fmt.format(parsed.transactionAmount)})")
                return@withContext
            }

            val apiKey = GeminiService.getApiKey()
            if (apiKey.isEmpty()) {
                LogWriter.err("정산 브리핑: Gemini 키 없음")
                return@withContext
            }

            // 데이터 수집
            val dao = BankDatabase.getInstance(context).accountDao()
            val subtotal = dao.getSubtotalBalanceSync() ?: 0L
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

            // 출금 매칭: 오늘 출금과 정산 항목 비교 (금액 ±15% 범위)
            val matched = mutableListOf<String>()
            val unmatched = mutableListOf<String>()
            val usedTx = mutableSetOf<Int>()
            var remainOut = 0L

            for (item in todayItems.filter { it.type != "입금" }) {
                val matchTx = todayWithdrawals.withIndex().firstOrNull { (i, tx) ->
                    i !in usedTx && kotlin.math.abs(tx.optLong("amount", 0) - item.amount) <= item.amount * 0.15
                }
                if (matchTx != null) {
                    usedTx.add(matchTx.index)
                    matched.add("✓ ${item.name} ${fmt.format(matchTx.value.optLong("amount",0))}원")
                } else {
                    unmatched.add("○ ${item.name} ${fmt.format(item.amount)}원 (미출금)")
                    remainOut += item.amount
                }
            }

            // 여유 계산 (입금 미포함 최악 시나리오)
            val marginToNegative = subtotal - remainOut

            // Gemini 프롬프트
            val defaultRules = """1. title (30자 이내): "남은 출금 XX만, 여유 YY만" 형태
2. body (3줄 이내): 남은 출금 합계, 마이너스까지 여유, 위험 시 경고
3. SFA/물류 금액 보정 금지 — BLOCK 금액 그대로 사용
4. 응답 형식: {"title":"...","body":"..."}
5. 반드시 순수 JSON만 응답"""

            val ruleText = if (rules != "null" && rules.isNotEmpty()) {
                rules.removeSurrounding("\"").replace("\\n", "\n")
            } else defaultRules

            val outSummary = buildString {
                if (matched.isNotEmpty()) { appendLine("[완료] ${matched.joinToString(", ")}") }
                if (unmatched.isNotEmpty()) { appendLine("[미출금] ${unmatched.joinToString(", ")}") }
            }

            val prompt = """정산 브리핑. 핵심만 간결하게.

[규칙]
$ruleText

[발생 거래] ${parsed.bankName} ${parsed.transactionType} ${fmt.format(parsed.transactionAmount)}원 (${parsed.counterparty})
[현재 소계] ${fmt.format(subtotal)}원
[오늘 입금] ${fmt.format(todayInTotal)}원 (${todayDeposits.size}건)
[오늘 출금] ${fmt.format(todayOutTotal)}원 (${todayWithdrawals.size}건)
[남은 출금] ${fmt.format(remainOut)}원
[여유] ${if (marginToNegative > 0) "${fmt.format(marginToNegative)}원" else "부족 ${fmt.format(-marginToNegative)}원"}
$outSummary
JSON으로 응답."""

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
