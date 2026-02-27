package com.banktotal.app.service

import android.util.Log
import com.banktotal.app.data.db.SettleItemEntity
import com.banktotal.app.data.db.SettleItemStateEntity
import com.banktotal.app.data.db.SfaDailyEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * Room → Firebase 백업 쓰기 (비동기, 실패해도 무관)
 *
 * [규칙]
 * - Firebase는 백업일 뿐 — 실패해도 Room 데이터에 영향 없음
 * - 앱→Firebase 단방향만 (Firebase→앱 동기화 금지)
 * - 이 클래스에서 Room 읽기/쓰기 절대 금지 (쓰기는 호출자 책임)
 */
object FirebaseBackupWriter {

    private const val TAG = "FbBackup"
    private const val FB = "https://poskds-4ba60-default-rtdb.asia-southeast1.firebasedatabase.app"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun backupSettleItem(item: SettleItemEntity) {
        scope.launch {
            try {
                val path = if (item.source == "auto") "settle/auto" else "settle/manual"
                val obj = JSONObject().apply {
                    put("name", item.name)
                    put("amount", item.amount)
                    put("type", item.type)
                    put("cycle", item.cycle)
                    put("dayOfMonth", item.dayOfMonth)
                    put("dayOfWeek", item.dayOfWeek)
                    if (item.date != null) put("date", item.date)
                    put("source", item.source)
                    if (item.isBlock) put("isBlock", true)
                }
                putJson("$FB/banktotal/$path/${item.id}.json", obj)
            } catch (e: Exception) {
                Log.w(TAG, "settle backup 실패: ${e.message}")
            }
        }
    }

    fun deleteSettleItem(id: String, source: String) {
        scope.launch {
            try {
                val path = if (source == "auto") "settle/auto" else "settle/manual"
                deleteJson("$FB/banktotal/$path/$id.json")
            } catch (e: Exception) {
                Log.w(TAG, "settle delete backup 실패: ${e.message}")
            }
        }
    }

    fun backupSfaDaily(entity: SfaDailyEntity) {
        scope.launch {
            try {
                val obj = JSONObject().apply {
                    put("amount", entity.amount)
                    put("ts", entity.timestamp)
                }
                putJson("$FB/banktotal/sfa_daily/${entity.date}.json", obj)
            } catch (e: Exception) {
                Log.w(TAG, "sfa backup 실패: ${e.message}")
            }
        }
    }

    fun backupItemState(state: SettleItemStateEntity) {
        scope.launch {
            try {
                val safeKey = state.itemKey.replace(Regex("[.#$/\\[\\]]"), "_")
                val obj = JSONObject().apply {
                    put("ex", state.excluded)
                    put("sh", state.dateShift)
                    if (state.status != null) put("st", state.status)
                    if (state.manualOverride) put("_ov", true)
                }
                putJson("$FB/banktotal/item_states/$safeKey.json", obj)
            } catch (e: Exception) {
                Log.w(TAG, "item_state backup 실패: ${e.message}")
            }
        }
    }

    private fun putJson(url: String, obj: JSONObject) {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "PUT"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.doOutput = true
        conn.connectTimeout = 5000
        conn.readTimeout = 5000
        OutputStreamWriter(conn.outputStream).use { it.write(obj.toString()) }
        conn.responseCode
        conn.disconnect()
    }

    private fun deleteJson(url: String) {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "DELETE"
        conn.connectTimeout = 5000
        conn.readTimeout = 5000
        conn.responseCode
        conn.disconnect()
    }
}
