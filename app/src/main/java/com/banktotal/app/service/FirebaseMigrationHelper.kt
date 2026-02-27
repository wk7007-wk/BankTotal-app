package com.banktotal.app.service

import android.content.Context
import com.banktotal.app.data.db.BankDatabase
import com.banktotal.app.data.db.MatchLogEntity
import com.banktotal.app.data.db.SfaDailyEntity
import com.banktotal.app.data.db.SettleItemEntity
import com.banktotal.app.data.db.SettleItemStateEntity
import com.banktotal.app.data.db.TransactionEntity
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Firebase → Room 1회 마이그레이션
 *
 * [규칙]
 * - 마이그레이션은 앱 최초 실행 시 1회만 (v3_migrated 플래그)
 * - Firebase 데이터를 Room에 INSERT — 기존 Room 데이터가 있으면 REPLACE
 * - 마이그레이션 실패 시 플래그 안 세움 → 다음 실행 시 재시도
 * - 이 클래스는 데이터 읽기만 함 (Firebase 삭제 금지)
 */
object FirebaseMigrationHelper {

    private const val FB = "https://poskds-4ba60-default-rtdb.asia-southeast1.firebasedatabase.app"
    private const val PREFS = "banktotal_prefs"
    private const val KEY_MIGRATED = "v3_migrated"

    suspend fun migrateIfNeeded(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_MIGRATED, false)) return

        LogWriter.sys("Firebase→Room 마이그레이션 시작")
        val db = BankDatabase.getInstance(context)

        try {
            var count = 0
            count += migrateManualSettle(db)
            count += migrateAutoSettle(db)
            count += migrateSfaDaily(db)
            count += migrateItemStates(context, db)
            count += migrateMatchLogs(context, db)

            prefs.edit().putBoolean(KEY_MIGRATED, true).apply()
            LogWriter.sys("Firebase→Room 마이그레이션 완료: ${count}건")
        } catch (e: Exception) {
            LogWriter.err("Firebase→Room 마이그레이션 실패: ${e.message}")
        }
    }

    private suspend fun migrateManualSettle(db: BankDatabase): Int {
        val json = fetchJson("$FB/banktotal/settle/manual.json") ?: return 0
        val dao = db.settleItemDao()
        var count = 0
        val keys = json.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val obj = json.optJSONObject(k) ?: continue
            dao.insert(
                SettleItemEntity(
                    id = k,
                    name = obj.optString("name", ""),
                    amount = obj.optLong("amount", 0),
                    type = obj.optString("type", "출금"),
                    cycle = obj.optString("cycle", "none"),
                    dayOfMonth = obj.optInt("dayOfMonth", 1),
                    dayOfWeek = obj.optInt("dayOfWeek", 0),
                    date = obj.optString("date", "").ifEmpty { null },
                    source = "manual",
                    isBlock = obj.optString("name", "").let { it == "물류" || it == "SFA" }
                )
            )
            count++
        }
        LogWriter.sys("manual settle 마이그레이션: ${count}건")
        return count
    }

    private suspend fun migrateAutoSettle(db: BankDatabase): Int {
        val json = fetchJson("$FB/banktotal/settle/auto.json") ?: return 0
        val dao = db.settleItemDao()
        var count = 0
        val keys = json.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val obj = json.optJSONObject(k) ?: continue
            dao.insert(
                SettleItemEntity(
                    id = k,
                    name = obj.optString("name", obj.optString("source", "고지서")),
                    amount = obj.optLong("amount", 0),
                    type = obj.optString("type", "출금"),
                    cycle = obj.optString("cycle", "none"),
                    dayOfMonth = obj.optInt("dayOfMonth", 1),
                    dayOfWeek = obj.optInt("dayOfWeek", 0),
                    date = obj.optString("dueDate", "").ifEmpty { obj.optString("date", "").ifEmpty { null } },
                    source = "auto"
                )
            )
            count++
        }
        LogWriter.sys("auto settle 마이그레이션: ${count}건")
        return count
    }

    private suspend fun migrateSfaDaily(db: BankDatabase): Int {
        val json = fetchJson("$FB/banktotal/sfa_daily.json") ?: return 0
        val dao = db.sfaDailyDao()
        var count = 0
        val keys = json.keys()
        while (keys.hasNext()) {
            val date = keys.next()
            val obj = json.optJSONObject(date) ?: continue
            dao.upsert(
                SfaDailyEntity(
                    date = date,
                    amount = obj.optLong("amount", 0),
                    timestamp = obj.optLong("ts", System.currentTimeMillis())
                )
            )
            count++
        }
        LogWriter.sys("sfa_daily 마이그레이션: ${count}건")
        return count
    }

    /** localStorage bt_item_state → Room (웹에서 이전한 경우) */
    private suspend fun migrateItemStates(context: Context, db: BankDatabase): Int {
        val json = fetchJson("$FB/banktotal/item_states.json") ?: return 0
        val dao = db.settleItemStateDao()
        var count = 0
        val keys = json.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val obj = json.optJSONObject(key) ?: continue
            dao.upsert(
                SettleItemStateEntity(
                    itemKey = key,
                    excluded = obj.optBoolean("ex", false),
                    dateShift = obj.optInt("sh", 0),
                    status = obj.optString("st", "").ifEmpty { null },
                    manualOverride = obj.optBoolean("_ov", false)
                )
            )
            count++
        }
        LogWriter.sys("item_states 마이그레이션: ${count}건")
        return count
    }

    /** localStorage bt_match_log → Room */
    private suspend fun migrateMatchLogs(context: Context, db: BankDatabase): Int {
        val json = fetchJson("$FB/banktotal/match_logs.json") ?: return 0
        val dao = db.matchLogDao()
        var count = 0
        if (json.has("logs")) {
            val arr = json.optJSONArray("logs")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    dao.insert(
                        MatchLogEntity(
                            counterparty = obj.optString("cp", ""),
                            itemName = obj.optString("name", ""),
                            txAmount = obj.optLong("txAmt", 0),
                            settleAmount = obj.optLong("settleAmt", 0),
                            isAuto = obj.optBoolean("auto", false),
                            createdAt = obj.optLong("ts", System.currentTimeMillis())
                        )
                    )
                    count++
                }
            }
        }
        LogWriter.sys("match_logs 마이그레이션: ${count}건")
        return count
    }

    private fun fetchJson(url: String): JSONObject? = try {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 5000; conn.readTimeout = 5000
        val text = conn.inputStream.bufferedReader().readText()
        conn.disconnect()
        if (text == "null" || text.isBlank()) null else JSONObject(text)
    } catch (_: Exception) { null }
}
