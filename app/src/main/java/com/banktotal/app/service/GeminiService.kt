package com.banktotal.app.service

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * Gemini API를 통한 고지서/청구서 자동 감지.
 * 알림 텍스트 → Gemini 분석 → Firebase /banktotal/settle/auto 저장.
 */
object GeminiService {

    private const val TAG = "GeminiService"
    private const val FIREBASE_BASE =
        "https://poskds-4ba60-default-rtdb.asia-southeast1.firebasedatabase.app"
    private const val GEMINI_URL =
        "https://generativelanguage.googleapis.com/v1beta/models/gemini-3-flash-preview:generateContent"

    private const val BILL_PROMPT = """다음 알림 메시지를 분석하세요. 고지서/청구서/납부안내/결제예정/요금안내인지 판단하세요.

메시지: %s

고지서/청구서라면 다음 JSON으로 응답:
{"bill":true,"name":"항목명","amount":금액숫자,"dueDate":"YYYY-MM-DD","type":"출금"}

고지서가 아니면:
{"bill":false}

반드시 순수 JSON만 응답하세요. 마크다운이나 설명 없이 JSON만."""

    // 고지서 관련 키워드 (이 중 하나라도 포함 시 Gemini로 분석)
    private val BILL_KEYWORDS = listOf(
        "요금", "납부", "청구", "고지", "결제일", "자동이체", "미납",
        "전기세", "가스비", "수도요금", "통신료", "보험료", "관리비",
        "카드대금", "할부", "국민연금", "건강보험", "납기"
    )

    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        prefs = context.getSharedPreferences("banktotal_settings", Context.MODE_PRIVATE)
    }

    fun getApiKey(): String = prefs?.getString("gemini_api_key", "") ?: ""

    fun setApiKey(key: String) {
        prefs?.edit()?.putString("gemini_api_key", key)?.apply()
    }

    /** Firebase에서 키 로드 (앱 시작 시 1회, IO 스레드) */
    fun loadKeyFromFirebase() {
        if (getApiKey().isNotEmpty()) return
        try {
            val conn = URL("$FIREBASE_BASE/banktotal/settings/gemini_key.json")
                .openConnection() as HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            val resp = conn.inputStream.bufferedReader().readText()
            conn.disconnect()
            val key = resp.trim().removeSurrounding("\"")
            if (key.isNotEmpty() && key != "null") {
                setApiKey(key)
                Log.d(TAG, "Firebase에서 Gemini 키 로드 완료")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Firebase 키 로드 실패: ${e.message}")
        }
    }

    /** 알림 텍스트가 고지서 분석 대상인지 (키워드 포함 여부) */
    fun isBillCandidate(text: String): Boolean {
        return BILL_KEYWORDS.any { text.contains(it) }
    }

    /** Gemini API로 고지서 분석 후 Firebase에 저장 (IO 스레드에서 호출) */
    suspend fun detectAndSaveBill(text: String, source: String) {
        val key = getApiKey()
        if (key.isEmpty()) {
            Log.d(TAG, "API 키 없음, 고지서 감지 스킵")
            return
        }

        withContext(Dispatchers.IO) {
            try {
                val result = callGemini(key, String.format(BILL_PROMPT, text))
                if (result == null) {
                    Log.d(TAG, "Gemini 응답 없음")
                    return@withContext
                }

                // JSON 추출 (마크다운 코드블록 제거)
                val jsonStr = result
                    .replace(Regex("```json\\s*"), "")
                    .replace(Regex("```\\s*"), "")
                    .trim()

                val json = JSONObject(jsonStr)
                if (!json.optBoolean("bill", false)) {
                    Log.d(TAG, "고지서 아님: $text")
                    return@withContext
                }

                // Firebase에 저장
                val entry = JSONObject()
                entry.put("name", json.optString("name", "고지서"))
                entry.put("amount", json.optLong("amount", 0))
                entry.put("type", json.optString("type", "출금"))
                entry.put("source", source)
                entry.put("raw", text)
                entry.put("ts", System.currentTimeMillis())

                val dueDateStr = json.optString("dueDate", "")
                if (dueDateStr.isNotEmpty()) {
                    try {
                        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.KOREA)
                        entry.put("dueDate", sdf.parse(dueDateStr)?.time ?: 0L)
                    } catch (_: Exception) {}
                }

                val id = "sa_${System.currentTimeMillis()}"
                val conn = URL("$FIREBASE_BASE/banktotal/settle/auto/$id.json")
                    .openConnection() as HttpURLConnection
                conn.requestMethod = "PUT"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                OutputStreamWriter(conn.outputStream).use { it.write(entry.toString()) }
                val code = conn.responseCode
                conn.disconnect()

                if (code in 200..299) {
                    val name = json.optString("name", "고지서")
                    val amount = json.optLong("amount", 0)
                    Log.d(TAG, "고지서 감지 저장: $name ${amount}원")
                    LogWriter.tx("고지서 감지: $name ${amount}원 ($source)")
                } else {
                    Log.w(TAG, "고지서 저장 실패: HTTP $code")
                }
            } catch (e: Exception) {
                Log.w(TAG, "고지서 감지 실패: ${e.message}")
            }
        }
    }

    /** Gemini API 호출 */
    private fun callGemini(apiKey: String, prompt: String): String? {
        val reqBody = JSONObject()
        val contents = org.json.JSONArray()
        val content = JSONObject()
        val parts = org.json.JSONArray()
        val part = JSONObject()
        part.put("text", prompt)
        parts.put(part)
        content.put("parts", parts)
        contents.put(content)
        reqBody.put("contents", contents)

        val conn = URL("$GEMINI_URL?key=$apiKey")
            .openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.doOutput = true
        conn.connectTimeout = 10000
        conn.readTimeout = 15000
        OutputStreamWriter(conn.outputStream).use { it.write(reqBody.toString()) }

        val code = conn.responseCode
        if (code !in 200..299) {
            val err = conn.errorStream?.bufferedReader()?.readText() ?: ""
            conn.disconnect()
            Log.w(TAG, "Gemini API 에러: HTTP $code $err")
            return null
        }

        val resp = conn.inputStream.bufferedReader().readText()
        conn.disconnect()

        val respJson = JSONObject(resp)
        return respJson
            .optJSONArray("candidates")
            ?.optJSONObject(0)
            ?.optJSONObject("content")
            ?.optJSONArray("parts")
            ?.optJSONObject(0)
            ?.optString("text")
    }
}
