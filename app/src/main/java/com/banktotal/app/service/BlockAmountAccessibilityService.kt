package com.banktotal.app.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.banktotal.app.data.repository.AccountRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class BlockAmountAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "BlockAmountAS"
        var instance: BlockAmountAccessibilityService? = null
            private set
    }

    override fun onServiceConnected() {
        instance = this
        serviceInfo = serviceInfo.apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                    AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            notificationTimeout = 500
        }
        Log.d(TAG, "접근성 서비스 연결됨")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val root = try { rootInActiveWindow } catch (_: Exception) { null } ?: return

        try {
            val blockNodes = root.findAccessibilityNodeInfosByText("BLOCK 금액")
            if (blockNodes.isNullOrEmpty()) {
                root.recycle()
                return
            }

            // "BLOCK 금액" 노드 발견 → 근처에서 숫자 찾기
            for (node in blockNodes) {
                val amount = findAmountNearNode(node)
                if (amount != null && amount > 0) {
                    Log.d(TAG, "BBQ BLOCK 금액 감지: ${amount}원")
                    CoroutineScope(Dispatchers.IO).launch {
                        val repo = AccountRepository(applicationContext)
                        repo.upsertBlockAmount("BBQ", amount)
                    }
                    break
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "처리 실패: ${e.message}")
        } finally {
            root.recycle()
        }
    }

    private fun findAmountNearNode(blockNode: AccessibilityNodeInfo): Long? {
        // 방법1: 부모 노드의 형제에서 금액 찾기
        val parent = blockNode.parent ?: return findAmountInTree(blockNode)
        val amount = findAmountInChildren(parent)
        parent.recycle()
        if (amount != null) return amount

        // 방법2: 부모의 부모에서 탐색
        val grandParent = try { blockNode.parent?.parent } catch (_: Exception) { null }
        if (grandParent != null) {
            val amt = findAmountInChildren(grandParent)
            grandParent.recycle()
            if (amt != null) return amt
        }

        return null
    }

    private fun findAmountInChildren(parent: AccessibilityNodeInfo): Long? {
        var foundBlock = false
        for (i in 0 until parent.childCount) {
            val child = parent.getChild(i) ?: continue
            val text = child.text?.toString() ?: ""

            if (text.contains("BLOCK 금액")) {
                foundBlock = true
                child.recycle()
                continue
            }

            // "BLOCK 금액" 이후의 숫자 텍스트
            if (foundBlock || true) {
                val amount = parseAmount(text)
                if (amount != null) {
                    child.recycle()
                    return amount
                }
                // 자식 노드도 탐색
                val childAmount = findAmountInChildren(child)
                child.recycle()
                if (childAmount != null) return childAmount
            } else {
                child.recycle()
            }
        }
        return null
    }

    private fun findAmountInTree(node: AccessibilityNodeInfo, depth: Int = 0): Long? {
        if (depth > 10) return null
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val text = child.text?.toString() ?: ""
            val amount = parseAmount(text)
            if (amount != null) {
                child.recycle()
                return amount
            }
            val result = findAmountInTree(child, depth + 1)
            child.recycle()
            if (result != null) return result
        }
        return null
    }

    private fun parseAmount(text: String): Long? {
        if (text.isBlank()) return null
        // "2,778,747" 또는 "2,778,747원" 패턴
        val match = Regex("""([\d,]+)\s*원?""").find(text.trim()) ?: return null
        val numStr = match.groupValues[1].replace(",", "")
        val num = numStr.toLongOrNull() ?: return null
        // 합리적인 범위 (1,000 이상)
        return if (num >= 1000) num else null
    }

    override fun onInterrupt() {
        Log.d(TAG, "접근성 서비스 중단됨")
        instance = null
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }
}
