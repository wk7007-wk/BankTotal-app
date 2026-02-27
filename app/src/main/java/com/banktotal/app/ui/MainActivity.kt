package com.banktotal.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.banktotal.app.data.db.BankDatabase
import com.banktotal.app.service.BalanceNotificationHelper
import com.banktotal.app.service.GeminiService
import com.banktotal.app.service.LogWriter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView

    private val DASHBOARD_URL = "https://wk7007-wk.github.io/BankTotal-app/"

    private val notifPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ -> }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(com.banktotal.app.R.layout.activity_main)

        webView = findViewById(com.banktotal.app.R.id.webView)
        setupWebView()
        requestPermissions()
        BalanceNotificationHelper.update(this)
        GeminiService.init(this)
        CoroutineScope(Dispatchers.IO).launch { GeminiService.loadKeyFromFirebase() }
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

    // --- WebViewClient: 캐시 저장/오프라인 폴백 ---
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
                        val file = File(filesDir, "dashboard_cache.html")
                        file.writeText(decoded)
                    } catch (_: Exception) {}
                }
            }
        }
    }

    private fun loadCacheFallback(view: WebView?) {
        val file = File(filesDir, "dashboard_cache.html")
        if (file.exists()) {
            val html = file.readText()
            view?.loadDataWithBaseURL(DASHBOARD_URL, html, "text/html", "UTF-8", null)
            LogWriter.sys("[WebView] 오프라인 캐시 로드")
        } else {
            view?.loadData(
                "<html><body style='background:#121212;color:#888;text-align:center;padding-top:100px;font-family:sans-serif'>" +
                        "<h2>연결 실패</h2><p>인터넷 연결을 확인해주세요</p></body></html>",
                "text/html", "UTF-8"
            )
        }
    }

    // --- NativeBridge ---
    inner class NativeBridge {
        @JavascriptInterface
        fun getAccountsJson(): String {
            return runBlocking(Dispatchers.IO) {
                try {
                    val dao = BankDatabase.getInstance(applicationContext).accountDao()
                    val accounts = dao.getAllAccountsSync()
                    val arr = JSONArray()
                    accounts.forEach { a ->
                        val obj = JSONObject()
                        obj.put("id", a.id)
                        obj.put("bankName", a.bankName)
                        obj.put("accountNumber", a.accountNumber)
                        obj.put("displayName", a.displayName)
                        obj.put("balance", a.balance)
                        obj.put("lastTransactionType", a.lastTransactionType)
                        obj.put("lastTransactionAmount", a.lastTransactionAmount)
                        obj.put("lastUpdated", a.lastUpdated)
                        obj.put("isActive", a.isActive)
                        arr.put(obj)
                    }
                    arr.toString()
                } catch (e: Exception) {
                    "[]"
                }
            }
        }

        @JavascriptInterface
        fun getPermissions(): String {
            val obj = JSONObject()
            obj.put("notification", isNotificationListenerEnabled())
            obj.put("accessibility", isAccessibilityEnabled())
            return obj.toString()
        }

        @JavascriptInterface
        fun openNotificationSettings() {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }

        @JavascriptInterface
        fun openAccessibilitySettings() {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        @JavascriptInterface
        fun getAppVersion(): String {
            return try {
                packageManager.getPackageInfo(packageName, 0).versionName ?: ""
            } catch (_: Exception) { "" }
        }

        @JavascriptInterface
        fun getGeminiKey(): String = GeminiService.getApiKey()

        @JavascriptInterface
        fun setGeminiKey(key: String) = GeminiService.setApiKey(key)

        @JavascriptInterface
        fun getLatestImage(): String {
            return try {
                val dirs = listOf(
                    java.io.File("/sdcard/Pictures/Screenshots"),
                    java.io.File("/sdcard/DCIM/Screenshots"),
                    java.io.File("/sdcard/DCIM/Camera"),
                    java.io.File("/sdcard/Pictures"),
                    java.io.File("/sdcard/Download")
                )
                val latest = dirs.flatMap { dir ->
                    dir.listFiles()?.filter {
                        it.isFile && it.name.matches(Regex(".*\\.(jpg|jpeg|png|webp)$", RegexOption.IGNORE_CASE))
                    }?.toList() ?: emptyList()
                }.maxByOrNull { it.lastModified() }

                if (latest != null && latest.length() < 5 * 1024 * 1024) {
                    android.util.Base64.encodeToString(latest.readBytes(), android.util.Base64.NO_WRAP)
                } else ""
            } catch (e: Exception) { "" }
        }
        @JavascriptInterface
        fun captureScreen() {
            runOnUiThread {
                try {
                    val scale = webView.scale
                    val fullHeight = (webView.contentHeight * scale).toInt()
                    val height = maxOf(webView.height, fullHeight)
                    val bitmap = android.graphics.Bitmap.createBitmap(
                        webView.width, height, android.graphics.Bitmap.Config.ARGB_8888
                    )
                    val canvas = android.graphics.Canvas(bitmap)
                    webView.draw(canvas)
                    val file = java.io.File(
                        android.os.Environment.getExternalStoragePublicDirectory(
                            android.os.Environment.DIRECTORY_DOWNLOADS
                        ), "BankTotal_capture.png"
                    )
                    java.io.FileOutputStream(file).use {
                        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 85, it)
                    }
                    bitmap.recycle()
                    android.widget.Toast.makeText(this@MainActivity, "캡처 저장됨", android.widget.Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    android.widget.Toast.makeText(this@MainActivity, "캡처 실패", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
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

        if (!isNotificationListenerEnabled()) {
            AlertDialog.Builder(this)
                .setTitle("알림 접근 권한")
                .setMessage("은행 알림을 읽으려면 알림 접근 권한이 필요합니다.")
                .setPositiveButton("설정으로 이동") { _, _ ->
                    startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                }
                .setNegativeButton("나중에", null)
                .show()
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
