package com.banktotal.app.service

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

object LogWriter {

    private const val TAG = "LogWriter"
    private const val FIREBASE_BASE =
        "https://poskds-4ba60-default-rtdb.asia-southeast1.firebasedatabase.app"
    private const val MAX_LOGS = 200

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun tx(msg: String) = write("[TX]", msg)
    fun parse(msg: String) = write("[PARSE]", msg)
    fun err(msg: String) = write("[ERR]", msg)
    fun sys(msg: String) = write("[SYS]", msg)

    private fun write(tag: String, msg: String) {
        Log.d(TAG, "$tag $msg")
        scope.launch {
            try {
                val obj = JSONObject()
                obj.put("ts", System.currentTimeMillis())
                obj.put("tag", tag)
                obj.put("msg", msg)

                val conn = URL("$FIREBASE_BASE/banktotal/logs.json")
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
                    trimOldLogs()
                } else {
                    Log.w(TAG, "로그 저장 실패: HTTP $code")
                }
            } catch (e: Exception) {
                Log.w(TAG, "로그 저장 실패: ${e.message}")
            }
        }
    }

    private fun trimOldLogs() {
        try {
            val conn = URL("$FIREBASE_BASE/banktotal/logs.json?shallow=true")
                .openConnection() as HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            val json = conn.inputStream.bufferedReader().readText()
            conn.disconnect()

            if (json == "null" || json.isEmpty()) return
            val obj = JSONObject(json)
            val count = obj.length()
            if (count <= MAX_LOGS) return

            // 오래된 로그 키 가져와서 삭제
            val keysConn = URL("$FIREBASE_BASE/banktotal/logs.json?orderBy=\"ts\"&limitToFirst=${count - MAX_LOGS}")
                .openConnection() as HttpURLConnection
            keysConn.connectTimeout = 5000
            keysConn.readTimeout = 5000
            val keysJson = keysConn.inputStream.bufferedReader().readText()
            keysConn.disconnect()

            if (keysJson == "null" || keysJson.isEmpty()) return
            val keysObj = JSONObject(keysJson)
            val deleteObj = JSONObject()
            keysObj.keys().forEach { key -> deleteObj.put(key, JSONObject.NULL) }

            val delConn = URL("$FIREBASE_BASE/banktotal/logs.json")
                .openConnection() as HttpURLConnection
            delConn.requestMethod = "PATCH"
            delConn.setRequestProperty("Content-Type", "application/json")
            delConn.doOutput = true
            delConn.connectTimeout = 5000
            delConn.readTimeout = 5000
            OutputStreamWriter(delConn.outputStream).use { it.write(deleteObj.toString()) }
            delConn.responseCode
            delConn.disconnect()
        } catch (e: Exception) {
            Log.w(TAG, "로그 정리 실패: ${e.message}")
        }
    }
}
