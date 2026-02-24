package com.banktotal.app.service

import android.util.Log
import com.banktotal.app.data.parser.ParsedTransaction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

object FirebaseTransactionWriter {

    private const val TAG = "FirebaseTxWriter"
    private const val FIREBASE_BASE =
        "https://poskds-4ba60-default-rtdb.asia-southeast1.firebasedatabase.app"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // 중복 방지: 최근 저장한 거래 해시 (은행+계좌+금액+타입)
    private val recentHashes = LinkedHashSet<String>()
    private const val MAX_RECENT = 20

    private fun txHash(parsed: ParsedTransaction): String =
        "${parsed.bankName}_${parsed.accountNumber}_${parsed.transactionType}_${parsed.transactionAmount}"

    fun save(parsed: ParsedTransaction) {
        val hash = txHash(parsed)
        synchronized(recentHashes) {
            if (!recentHashes.add(hash)) {
                Log.d(TAG, "중복 거래 스킵: $hash")
                return
            }
            if (recentHashes.size > MAX_RECENT) recentHashes.remove(recentHashes.first())
        }
        // 10초 후 해시 제거 (같은 금액 연속 거래 허용)
        scope.launch {
            kotlinx.coroutines.delay(10000)
            synchronized(recentHashes) { recentHashes.remove(hash) }
        }
        scope.launch {
            try {
                val obj = JSONObject()
                obj.put("bank", parsed.bankName)
                obj.put("account", parsed.accountNumber)
                obj.put("type", parsed.transactionType)
                obj.put("amount", parsed.transactionAmount)
                obj.put("balance", parsed.balance)
                obj.put("counterparty", parsed.counterparty)
                obj.put("raw", parsed.rawSms)
                obj.put("ts", if (parsed.timestamp > 0) parsed.timestamp else System.currentTimeMillis())

                val conn = URL("$FIREBASE_BASE/banktotal/transactions.json")
                    .openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                OutputStreamWriter(conn.outputStream).use { it.write(obj.toString()) }
                val code = conn.responseCode
                conn.disconnect()
                if (code in 200..299) {
                    Log.d(TAG, "거래 저장: ${parsed.transactionType} ${parsed.transactionAmount} → HTTP $code")
                    LogWriter.tx("Firebase 거래 저장: ${parsed.bankName} ${parsed.transactionType} ${parsed.transactionAmount}원")
                } else {
                    Log.w(TAG, "거래 저장 실패: HTTP $code")
                    LogWriter.err("Firebase 거래 저장 실패: HTTP $code")
                }
            } catch (e: Exception) {
                Log.w(TAG, "거래 저장 실패: ${e.message}")
                LogWriter.err("Firebase 거래 저장 예외: ${e.message}")
            }
        }
    }

    fun saveAccountBalance(parsed: ParsedTransaction) {
        scope.launch {
            try {
                val key = "${parsed.bankName}_${parsed.accountNumber}"
                    .replace(Regex("[.#$/\\[\\]]"), "_")
                val obj = JSONObject()
                obj.put("bank", parsed.bankName)
                obj.put("account", parsed.accountNumber)
                obj.put("balance", parsed.balance)
                obj.put("updated", System.currentTimeMillis())

                val conn = URL("$FIREBASE_BASE/banktotal/accounts/$key.json")
                    .openConnection() as HttpURLConnection
                conn.requestMethod = "PUT"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                OutputStreamWriter(conn.outputStream).use { it.write(obj.toString()) }
                val code = conn.responseCode
                conn.disconnect()
                if (code !in 200..299) {
                    Log.w(TAG, "계좌 잔고 동기화 실패: HTTP $code")
                    LogWriter.err("Firebase 잔고 동기화 실패: HTTP $code")
                }
            } catch (e: Exception) {
                Log.w(TAG, "계좌 잔고 동기화 실패: ${e.message}")
                LogWriter.err("Firebase 잔고 동기화 예외: ${e.message}")
            }
        }
    }

    fun saveBlockBalance(bankName: String, balance: Long) {
        scope.launch {
            try {
                val key = "${bankName}_BLOCK".replace(Regex("[.#$/\\[\\]]"), "_")
                val obj = JSONObject()
                obj.put("bank", bankName)
                obj.put("account", "SFA BLOCK")
                obj.put("balance", balance)
                obj.put("updated", System.currentTimeMillis())

                val conn = URL("$FIREBASE_BASE/banktotal/accounts/$key.json")
                    .openConnection() as HttpURLConnection
                conn.requestMethod = "PUT"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                OutputStreamWriter(conn.outputStream).use { it.write(obj.toString()) }
                val code = conn.responseCode
                conn.disconnect()
                if (code in 200..299) {
                    Log.d(TAG, "SFA BLOCK 동기화: ${balance}원")
                }
            } catch (e: Exception) {
                Log.w(TAG, "SFA BLOCK 동기화 실패: ${e.message}")
            }
        }
    }
}
