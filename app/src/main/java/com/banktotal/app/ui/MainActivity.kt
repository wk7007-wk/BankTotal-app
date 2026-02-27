package com.banktotal.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.banktotal.app.R
import com.banktotal.app.data.db.BankDatabase
import com.banktotal.app.data.db.SettleItemEntity
import com.banktotal.app.data.db.SettleItemStateEntity
import com.banktotal.app.data.db.SfaDailyEntity
import com.banktotal.app.data.repository.SettleRepository
import com.banktotal.app.service.BalanceNotificationHelper
import com.banktotal.app.service.FirebaseBackupWriter
import com.banktotal.app.service.FirebaseMigrationHelper
import com.banktotal.app.service.GeminiService
import com.banktotal.app.service.LogWriter
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.DecimalFormat

/**
 * 하이브리드 구조: WebView(UI) + Room DB(데이터)
 *
 * [규칙]
 * - WebView는 기존 GitHub Pages UI 그대로 사용
 * - NativeBridge로 Room DB 읽기/쓰기 제공
 * - 터치 문제 → NativeBridge.showItemMenu() → 네이티브 BottomSheet
 * - Firebase SSE는 accounts/transactions/logs에만 유지
 * - settle/sfa 데이터는 NativeBridge → Room DB가 Single Source of Truth
 */
class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private val DASHBOARD_URL = "https://wk7007-wk.github.io/BankTotal-app/"
    private val fmt = DecimalFormat("#,###")

    private val notifPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ -> }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)
        setupWebView()
        requestPermissions()
        BalanceNotificationHelper.update(this)
        GeminiService.init(this)
        CoroutineScope(Dispatchers.IO).launch {
            GeminiService.loadKeyFromFirebase()
            FirebaseMigrationHelper.migrateIfNeeded(applicationContext)
        }
        LogWriter.sys("앱 시작")
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent?.getBooleanExtra("action_refresh", false) == true) {
            BalanceNotificationHelper.update(this)
            intent.removeExtra("action_refresh")
            webView.evaluateJavascript("if(window.refreshFromNative)refreshFromNative()", null)
        }
    }

    private fun setupWebView() {
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.databaseEnabled = true
        webView.settings.cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
        webView.addJavascriptInterface(NativeBridge(), "NativeBridge")
        webView.webViewClient = DashboardWebViewClient()
        webView.webChromeClient = WebChromeClient()
        webView.setBackgroundColor(0xFF121212.toInt())
        webView.loadUrl(DASHBOARD_URL + "?v=" + System.currentTimeMillis())
    }

    private inner class DashboardWebViewClient : WebViewClient() {
        private var loadStartTime = 0L
        private var hasError = false

        override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
            loadStartTime = System.currentTimeMillis()
            hasError = false
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            val duration = System.currentTimeMillis() - loadStartTime
            if (!hasError && url?.startsWith("http") == true) {
                LogWriter.sys("[WebView] 로딩 완료: ${duration}ms")
                saveCachePage(view)
            }
        }

        override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
            if (request?.isForMainFrame == true) {
                hasError = true
                LogWriter.err("[WebView] 로딩 실패: ${error?.description}")
                loadCacheFallback(view)
            }
        }
    }

    private fun saveCachePage(view: WebView?) {
        view?.evaluateJavascript("document.documentElement.outerHTML") { html ->
            if (html != null && html.length > 100) {
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val decoded = html
                            .removePrefix("\"").removeSuffix("\"")
                            .replace("\\n", "\n").replace("\\\"", "\"")
                            .replace("\\/", "/").replace("\\u003C", "<")
                        File(filesDir, "dashboard_cache.html").writeText(decoded)
                    } catch (_: Exception) {}
                }
            }
        }
    }

    private fun loadCacheFallback(view: WebView?) {
        val file = File(filesDir, "dashboard_cache.html")
        if (file.exists()) {
            view?.loadDataWithBaseURL(DASHBOARD_URL, file.readText(), "text/html", "UTF-8", null)
            LogWriter.sys("[WebView] 오프라인 캐시 로드")
        } else {
            view?.loadData(
                "<html><body style='background:#121212;color:#888;text-align:center;padding-top:100px;font-family:sans-serif'>" +
                        "<h2>연결 실패</h2><p>인터넷 연결을 확인해주세요</p></body></html>",
                "text/html", "UTF-8"
            )
        }
    }

    // ======================================================================
    // NativeBridge — WebView JS ↔ Room DB
    // ======================================================================
    inner class NativeBridge {

        // --- 기존 메서드 (유지) ---

        @JavascriptInterface
        fun getAccountsJson(): String = runBlocking(Dispatchers.IO) {
            try {
                val dao = BankDatabase.getInstance(applicationContext).accountDao()
                val accounts = dao.getAllAccountsSync()
                val arr = JSONArray()
                accounts.forEach { a ->
                    arr.put(JSONObject().apply {
                        put("id", a.id)
                        put("bankName", a.bankName)
                        put("accountNumber", a.accountNumber)
                        put("displayName", a.displayName)
                        put("balance", a.balance)
                        put("lastTransactionType", a.lastTransactionType)
                        put("lastTransactionAmount", a.lastTransactionAmount)
                        put("lastUpdated", a.lastUpdated)
                        put("isActive", a.isActive)
                    })
                }
                arr.toString()
            } catch (_: Exception) { "[]" }
        }

        @JavascriptInterface
        fun getPermissions(): String {
            val obj = JSONObject()
            obj.put("notification", isNotificationListenerEnabled())
            obj.put("accessibility", isAccessibilityEnabled())
            return obj.toString()
        }

        @JavascriptInterface
        fun openNotificationSettings() { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }

        @JavascriptInterface
        fun openAccessibilitySettings() { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }

        @JavascriptInterface
        fun getAppVersion(): String = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: ""
        } catch (_: Exception) { "" }

        @JavascriptInterface
        fun getGeminiKey(): String = GeminiService.getApiKey()

        @JavascriptInterface
        fun setGeminiKey(key: String) = GeminiService.setApiKey(key)

        @JavascriptInterface
        fun getLatestImage(): String = try {
            val dirs = listOf(
                File("/sdcard/Pictures/Screenshots"), File("/sdcard/DCIM/Screenshots"),
                File("/sdcard/DCIM/Camera"), File("/sdcard/Pictures"), File("/sdcard/Download")
            )
            val latest = dirs.flatMap { dir ->
                dir.listFiles()?.filter {
                    it.isFile && it.name.matches(Regex(".*\\.(jpg|jpeg|png|webp)$", RegexOption.IGNORE_CASE))
                }?.toList() ?: emptyList()
            }.maxByOrNull { it.lastModified() }
            if (latest != null && latest.length() < 5 * 1024 * 1024) {
                android.util.Base64.encodeToString(latest.readBytes(), android.util.Base64.NO_WRAP)
            } else ""
        } catch (_: Exception) { "" }

        @JavascriptInterface
        fun captureScreen() {
            runOnUiThread {
                try {
                    val scale = webView.scale
                    val fullHeight = (webView.contentHeight * scale).toInt()
                    val height = maxOf(webView.height, fullHeight)
                    val bitmap = android.graphics.Bitmap.createBitmap(webView.width, height, android.graphics.Bitmap.Config.ARGB_8888)
                    val canvas = android.graphics.Canvas(bitmap)
                    webView.draw(canvas)
                    val file = File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), "BankTotal_capture.png")
                    java.io.FileOutputStream(file).use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 85, it) }
                    bitmap.recycle()
                    android.widget.Toast.makeText(this@MainActivity, "캡처 저장됨", android.widget.Toast.LENGTH_SHORT).show()
                } catch (_: Exception) {
                    android.widget.Toast.makeText(this@MainActivity, "캡처 실패", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }

        // ======================================================================
        // 새 메서드: Room DB settle 읽기/쓰기
        // ======================================================================

        /** Room settle_items → JS 배열 (settleManual 형식) */
        @JavascriptInterface
        fun getSettleItemsJson(): String = runBlocking(Dispatchers.IO) {
            try {
                val items = BankDatabase.getInstance(applicationContext).settleItemDao().getAllSync()
                val arr = JSONArray()
                items.forEach { item ->
                    arr.put(JSONObject().apply {
                        put("_id", item.id)
                        put("name", item.name)
                        put("amount", item.amount)
                        put("type", item.type)
                        put("cycle", item.cycle)
                        put("dayOfMonth", item.dayOfMonth)
                        put("dayOfWeek", item.dayOfWeek)
                        if (item.date != null) put("date", item.date)
                        put("source", item.source)
                        if (item.isBlock) put("isBlock", true)
                    })
                }
                arr.toString()
            } catch (_: Exception) { "[]" }
        }

        /** Room sfa_daily → JS 객체 {"2026-02-27": {amount: 123, ts: ...}} */
        @JavascriptInterface
        fun getSfaDailyJson(): String = runBlocking(Dispatchers.IO) {
            try {
                val list = BankDatabase.getInstance(applicationContext).sfaDailyDao().getRecent(60)
                val obj = JSONObject()
                list.forEach { sfa ->
                    obj.put(sfa.date, JSONObject().apply {
                        put("amount", sfa.amount)
                        put("ts", sfa.timestamp)
                    })
                }
                obj.toString()
            } catch (_: Exception) { "{}" }
        }

        /** Room settle_item_states → JS 객체 {"key": {ex:bool, sh:int, st:str, _ov:bool}} */
        @JavascriptInterface
        fun getItemStatesJson(): String = runBlocking(Dispatchers.IO) {
            try {
                val states = BankDatabase.getInstance(applicationContext).settleItemStateDao().getAll()
                val obj = JSONObject()
                states.forEach { s ->
                    obj.put(s.itemKey, JSONObject().apply {
                        put("ex", s.excluded)
                        put("sh", s.dateShift)
                        put("st", s.status ?: "")
                        if (s.manualOverride) put("_ov", true)
                    })
                }
                obj.toString()
            } catch (_: Exception) { "{}" }
        }

        /** Room에 정산항목 추가/수정 (JS에서 호출) */
        @JavascriptInterface
        fun saveSettleItem(jsonStr: String) {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val j = JSONObject(jsonStr)
                    val item = SettleItemEntity(
                        id = j.optString("_id", "sm_${System.currentTimeMillis()}"),
                        name = j.optString("name", ""),
                        amount = j.optLong("amount", 0),
                        type = j.optString("type", "출금"),
                        cycle = j.optString("cycle", "none"),
                        dayOfMonth = j.optInt("dayOfMonth", 1).coerceIn(1, 31),
                        dayOfWeek = j.optInt("dayOfWeek", 0).coerceIn(0, 6),
                        date = j.optString("date", "").ifEmpty { null },
                        source = j.optString("source", "manual"),
                        isBlock = j.optBoolean("isBlock", false)
                    )
                    BankDatabase.getInstance(applicationContext).settleItemDao().insert(item)
                    FirebaseBackupWriter.backupSettleItem(item)
                } catch (e: Exception) {
                    LogWriter.err("saveSettleItem 실패: ${e.message}")
                }
            }
        }

        /** Room에서 정산항목 삭제 */
        @JavascriptInterface
        fun deleteSettleItemNative(id: String) {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val db = BankDatabase.getInstance(applicationContext)
                    val item = db.settleItemDao().getById(id)
                    db.settleItemDao().deleteById(id)
                    db.settleItemStateDao().deleteByItemId(id)
                    val source = item?.source ?: "manual"
                    FirebaseBackupWriter.deleteSettleItem(id, source)
                } catch (e: Exception) {
                    LogWriter.err("deleteSettleItem 실패: ${e.message}")
                }
            }
        }

        /** Room에 항목 상태 저장 */
        @JavascriptInterface
        fun saveItemStateNative(jsonStr: String) {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val j = JSONObject(jsonStr)
                    val state = SettleItemStateEntity(
                        itemKey = j.getString("key"),
                        excluded = j.optBoolean("ex", false),
                        dateShift = j.optInt("sh", 0).coerceIn(-1, 29),
                        status = j.optString("st", "").ifEmpty { null },
                        manualOverride = j.optBoolean("_ov", false)
                    )
                    BankDatabase.getInstance(applicationContext).settleItemStateDao().upsert(state)
                    FirebaseBackupWriter.backupItemState(state)
                } catch (e: Exception) {
                    LogWriter.err("saveItemState 실패: ${e.message}")
                }
            }
        }

        /** Room에 SFA 금액 저장 */
        @JavascriptInterface
        fun saveSfaDailyNative(date: String, amount: Long) {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val entity = SfaDailyEntity(date = date, amount = amount)
                    BankDatabase.getInstance(applicationContext).sfaDailyDao().upsert(entity)
                    FirebaseBackupWriter.backupSfaDaily(entity)
                } catch (e: Exception) {
                    LogWriter.err("saveSfaDaily 실패: ${e.message}")
                }
            }
        }

        /** Room에 settle_items 있는지 (마이그레이션 완료 확인) */
        @JavascriptInterface
        fun hasRoomData(): Boolean = runBlocking(Dispatchers.IO) {
            try {
                BankDatabase.getInstance(applicationContext).settleItemDao().count() > 0
            } catch (_: Exception) { false }
        }

        /**
         * 네이티브 BottomSheet 메뉴 표시 (터치 문제 해결)
         * JS에서 settleItemMenu() 대신 호출
         */
        @JavascriptInterface
        fun showItemMenu(key: String, id: String, amount: Long, isSfa: Boolean, sfaKey: String, cycle: String) {
            runOnUiThread { showNativeBottomSheet(key, id, amount, isSfa, sfaKey, cycle) }
        }
    }

    // --- 네이티브 BottomSheet (WebView 터치 문제 근본 해결) ---
    private fun showNativeBottomSheet(key: String, id: String, amount: Long, isSfa: Boolean, sfaKey: String, cycle: String) {
        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.bottom_sheet_settle_menu, null)

        view.findViewById<TextView>(R.id.sheetTitle).text = "${fmt.format(amount)}원"

        view.findViewById<TextView>(R.id.btnToggleExclude).setOnClickListener {
            webView.evaluateJavascript("toggleItem('$key')", null)
            dialog.dismiss()
        }

        view.findViewById<TextView>(R.id.btnDelete).apply {
            val isRecur = cycle != "none" && cycle != "once"
            text = if (isRecur) "오늘 삭제" else "삭제"
            setOnClickListener {
                if (isRecur) {
                    // 인라인 JS — 당일 숨김 (hid=true로 리스트에서 완전 제거)
                    val safeKey = key.replace("'", "\\'")
                    webView.evaluateJavascript(
                        "try{var s=ist('$safeKey');s.hid=true;s.ex=true;s._ov=true;saveItemState();renderSettle();}catch(e){}", null
                    )
                } else {
                    webView.evaluateJavascript("deleteSettleItem('$id')", null)
                }
                dialog.dismiss()
            }
        }

        view.findViewById<TextView>(R.id.btnEdit).apply {
            if (isSfa) {
                text = "수정 불가 (SFA)"
                setTextColor(0xFF555555.toInt())
                isEnabled = false
            } else {
                setOnClickListener {
                    webView.evaluateJavascript("promptEditAmount('$key','$id',$amount)", null)
                    dialog.dismiss()
                }
            }
        }

        dialog.setContentView(view)
        dialog.show()
    }

    // --- 권한 ---
    private fun requestPermissions() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            val perms = mutableListOf<String>()
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) perms.add(Manifest.permission.POST_NOTIFICATIONS)
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES)
                != PackageManager.PERMISSION_GRANTED) perms.add(Manifest.permission.READ_MEDIA_IMAGES)
            if (perms.isNotEmpty()) notifPermissionLauncher.launch(perms.toTypedArray())
        } else if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
                notifPermissionLauncher.launch(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE))
            }
        }
    }

    private fun isNotificationListenerEnabled(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        return flat?.contains(packageName) == true
    }

    private fun isAccessibilityEnabled(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        return flat?.contains(packageName) == true
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }
}
