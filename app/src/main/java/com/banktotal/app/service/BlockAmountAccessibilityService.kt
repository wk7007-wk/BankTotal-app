package com.banktotal.app.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.banktotal.app.data.repository.AccountRepository
import com.banktotal.app.service.LogWriter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BlockAmountAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "BlockAmountAS"
        private const val LOG_FILE = "/sdcard/Download/BankTotal_log.txt"
        private const val MAX_LOG_SIZE = 500_000L
        private const val BBQ_PACKAGE = "com.hap.bbqsfa"
        var instance: BlockAmountAccessibilityService? = null
            private set
    }

    private val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.KOREA)
    private var lastDumpTime = 0L

    override fun onServiceConnected() {
        instance = this
        serviceInfo = serviceInfo.apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                    AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            notificationTimeout = 500
        }
        log("접근성 서비스 연결됨")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val pkg = event.packageName?.toString() ?: ""

        // BBQ SFA 앱만 처리
        if (pkg != BBQ_PACKAGE) return

        val root = try { rootInActiveWindow } catch (_: Exception) { null } ?: return

        try {
            // WebView라 findByText 안 됨 → 트리 직접 탐색
            val amount = findBlockAmountInTree(root)
            if (amount != null && amount > 0) {
                log("BBQ BLOCK 금액: ${amount}원 → DB 저장")
                LogWriter.tx("BBQ BLOCK 감지: ${amount}원")
                CoroutineScope(Dispatchers.IO).launch {
                    val repo = AccountRepository(applicationContext)
                    repo.upsertBlockAmount("BBQ", amount)
                }
            }
        } catch (e: Exception) {
            log("처리 실패: ${e.message}")
            LogWriter.err("BBQ 접근성 처리 실패: ${e.message}")
        } finally {
            root.recycle()
        }
    }

    /** 트리에서 id=blockAmount 노드 또는 "BLOCK 금액" 다음 숫자 찾기 */
    private fun findBlockAmountInTree(node: AccessibilityNodeInfo, depth: Int = 0): Long? {
        if (depth > 15) return null

        val id = node.viewIdResourceName ?: ""
        val text = node.text?.toString() ?: ""

        // 방법1: id에 "blockAmount" 포함
        if (id.contains("blockAmount")) {
            val amount = parseAmount(text)
            if (amount != null) return amount
        }

        // 방법2: "BLOCK 금액" 텍스트 발견 → 자식에서 숫자 찾기
        if (text.contains("BLOCK") && text.contains("금액")) {
            val childAmount = findFirstAmountInChildren(node)
            if (childAmount != null) return childAmount
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findBlockAmountInTree(child, depth + 1)
            child.recycle()
            if (result != null) return result
        }
        return null
    }

    /** 자식 노드에서 첫 번째 금액 찾기 */
    private fun findFirstAmountInChildren(parent: AccessibilityNodeInfo): Long? {
        for (i in 0 until parent.childCount) {
            val child = parent.getChild(i) ?: continue
            val text = child.text?.toString() ?: ""
            val amount = parseAmount(text)
            if (amount != null) {
                child.recycle()
                return amount
            }
            val childResult = findFirstAmountInChildren(child)
            child.recycle()
            if (childResult != null) return childResult
        }
        return null
    }

    private fun parseAmount(text: String): Long? {
        if (text.isBlank()) return null
        // "2,679,737 원" 또는 "2,679,737원" 또는 "2,679,737" 패턴
        val match = Regex("""([\d,]+)\s*원?""").find(text.trim()) ?: return null
        val numStr = match.groupValues[1].replace(",", "")
        val num = numStr.toLongOrNull() ?: return null
        return if (num >= 1000) num else null
    }

    private fun dumpTree(node: AccessibilityNodeInfo, depth: Int = 0): String {
        if (depth > 8) return ""
        val sb = StringBuilder()
        val indent = "  ".repeat(depth)
        val cls = node.className?.toString()?.substringAfterLast('.') ?: "?"
        val text = node.text?.toString() ?: ""
        val desc = node.contentDescription?.toString() ?: ""
        val id = node.viewIdResourceName ?: ""

        if (text.isNotEmpty() || desc.isNotEmpty() || id.isNotEmpty()) {
            sb.append("$indent[$cls] ")
            if (id.isNotEmpty()) sb.append("id=$id ")
            if (text.isNotEmpty()) sb.append("t=\"$text\" ")
            if (desc.isNotEmpty()) sb.append("d=\"$desc\" ")
            sb.append("\n")
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            sb.append(dumpTree(child, depth + 1))
            child.recycle()
        }
        return sb.toString()
    }

    private fun log(message: String) {
        val time = dateFormat.format(Date())
        val entry = "[$time] $message"
        Log.d(TAG, entry)
        try {
            val file = File(LOG_FILE)
            if (file.length() > MAX_LOG_SIZE) {
                val keep = file.readText().takeLast(MAX_LOG_SIZE.toInt() / 2)
                file.writeText(keep)
            }
            FileWriter(file, true).use { it.write("$entry\n") }
        } catch (_: Exception) {}
    }

    override fun onInterrupt() {
        log("접근성 서비스 중단됨")
        instance = null
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }
}
