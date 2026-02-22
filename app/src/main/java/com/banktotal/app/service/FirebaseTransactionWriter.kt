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

    fun save(parsed: ParsedTransaction) {
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
                } else {
                    Log.w(TAG, "거래 저장 실패: HTTP $code")
                }
            } catch (e: Exception) {
                Log.w(TAG, "거래 저장 실패: ${e.message}")
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
                }
            } catch (e: Exception) {
                Log.w(TAG, "계좌 잔고 동기화 실패: ${e.message}")
            }
        }
    }
}
