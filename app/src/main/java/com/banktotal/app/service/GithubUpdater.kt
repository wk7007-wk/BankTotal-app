package com.banktotal.app.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object GithubUpdater {
    private const val REPO = "BankTotal-app"
    private const val APK_NAME = "update.apk"

    fun check(context: Context, currentVersion: String) {
        Thread {
            try {
                val conn = URL("https://api.github.com/repos/wk7007-wk/$REPO/releases/latest").openConnection() as HttpURLConnection
                conn.setRequestProperty("Accept", "application/vnd.github.v3+json")
                conn.connectTimeout = 10000
                conn.readTimeout = 10000
                if (conn.responseCode != 200) { conn.disconnect(); return@Thread }
                val json = JSONObject(conn.inputStream.bufferedReader().readText())
                conn.disconnect()

                val tag = json.getString("tag_name").removePrefix("v")
                if (tag == currentVersion) return@Thread

                val assets = json.getJSONArray("assets")
                if (assets.length() == 0) return@Thread
                val url = assets.getJSONObject(0).getString("browser_download_url")

                showToast(context, "새 버전 v$tag 다운로드 중...")
                download(context, url)
            } catch (_: Exception) {}
        }.start()
    }

    private fun download(context: Context, apkUrl: String) {
        val file = File(context.filesDir, APK_NAME)
        if (file.exists()) file.delete()
        try {
            var url = apkUrl
            var redirects = 5
            var finalConn: HttpURLConnection? = null
            while (redirects-- > 0) {
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 30000; c.readTimeout = 60000; c.instanceFollowRedirects = false
                val code = c.responseCode
                if (code in listOf(301, 302, 307)) {
                    val loc = c.getHeaderField("Location"); c.disconnect()
                    if (loc.isNullOrEmpty()) break; url = loc; continue
                }
                if (code != 200) { c.disconnect(); return }
                finalConn = c; break
            }
            finalConn ?: return
            finalConn.inputStream.use { i -> file.outputStream().use { o -> i.copyTo(o) } }
            finalConn.disconnect()
            if (file.length() < 100_000) { file.delete(); return }

            android.os.Handler(android.os.Looper.getMainLooper()).post { install(context, file) }
        } catch (_: Exception) {}
    }

    private fun install(context: Context, file: File) {
        try {
            val uri = if (Build.VERSION.SDK_INT >= 24)
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            else Uri.fromFile(file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            }
            try { context.startActivity(intent) } catch (_: Exception) {}
            notify(context, intent)
        } catch (_: Exception) {}
    }

    private fun notify(context: Context, intent: Intent) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val ch = "app_update"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            nm.createNotificationChannel(NotificationChannel(ch, "앱 업데이트", NotificationManager.IMPORTANCE_HIGH))
        val pi = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        nm.notify(9999, NotificationCompat.Builder(context, ch)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("업데이트").setContentText("다운로드 완료 - 탭하여 설치")
            .setAutoCancel(true).setPriority(NotificationCompat.PRIORITY_HIGH).setContentIntent(pi).build())
    }

    private fun showToast(context: Context, msg: String) {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        }
    }
}
