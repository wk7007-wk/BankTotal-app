package com.banktotal.app.ui.settings

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment

/**
 * 설정 탭: 권한 상태 + WebView 로그뷰어
 * WebView는 로그 확인용으로만 유지
 */
class SettingsFragment : Fragment() {

    private var webView: WebView? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val root = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF121212.toInt())
        }

        // 권한 섹션
        addPermissionRow(root, "알림 접근") {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        addPermissionRow(root, "접근성 서비스") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        // 구분선
        root.addView(View(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 2
            ).apply { topMargin = 8; bottomMargin = 8 }
            setBackgroundColor(0xFF333333.toInt())
        })

        // 버전
        val version = try {
            requireContext().packageManager.getPackageInfo(requireContext().packageName, 0).versionName
        } catch (_: Exception) { "?" }
        root.addView(TextView(requireContext()).apply {
            text = "앱 버전: $version"
            setTextColor(0xFF666666.toInt())
            textSize = 12f
            setPadding(16, 8, 16, 8)
        })

        // WebView 로그뷰어
        root.addView(TextView(requireContext()).apply {
            text = "웹 대시보드 (로그/백업 확인)"
            setTextColor(0xFF888888.toInt())
            textSize = 11f
            setPadding(16, 12, 16, 4)
        })

        webView = WebView(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            setBackgroundColor(0xFF121212.toInt())
            webViewClient = WebViewClient()
            webChromeClient = WebChromeClient()
            loadUrl("https://wk7007-wk.github.io/BankTotal-app/")
        }
        root.addView(webView)

        return root
    }

    private fun addPermissionRow(container: LinearLayout, label: String, onClick: () -> Unit) {
        container.addView(TextView(requireContext()).apply {
            text = "$label  →  설정"
            setTextColor(0xFF4fc3f7.toInt())
            textSize = 14f
            setPadding(16, 14, 16, 14)
            setBackgroundColor(0xFF1a1a1a.toInt())
            setOnClickListener { onClick() }
        })
        container.addView(View(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
            setBackgroundColor(0xFF222222.toInt())
        })
    }

    override fun onDestroyView() {
        webView?.destroy()
        webView = null
        super.onDestroyView()
    }
}
