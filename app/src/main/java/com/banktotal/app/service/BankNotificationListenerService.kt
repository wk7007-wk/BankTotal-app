package com.banktotal.app.service

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.banktotal.app.data.parser.ParsedTransaction
import com.banktotal.app.data.parser.ShinhanNotificationParser
import com.banktotal.app.data.parser.extractCounterparty
import com.banktotal.app.data.repository.AccountRepository
import com.banktotal.app.service.LogWriter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class BankNotificationListenerService : NotificationListenerService() {

    private val shinhanParser = ShinhanNotificationParser()

    // 삼성 메시지 패키지 (RCS/SMS 모두 처리)
    private val messagingPackages = setOf(
        "com.samsung.android.messaging",
        "com.google.android.apps.messaging",
        "com.android.mms"
    )

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        val packageName = sbn.packageName ?: return

        // 은행 관련 패키지만 처리 (PosDelay 등 무관한 앱 즉시 무시)
        if (packageName !in messagingPackages && !shinhanParser.canParse(packageName)) return

        val extras = sbn.notification.extras
        val title = extras.getString("android.title") ?: ""
        val text = extras.getCharSequence("android.text")?.toString() ?: ""

        LogWriter.tx("알림 수신: pkg=$packageName title=$title")

        val parsed: ParsedTransaction?

        if (shinhanParser.canParse(packageName)) {
            parsed = shinhanParser.parse(title, text)
        } else {
            parsed = parseMessageNotification(title, text)
        }

        if (parsed == null) {
            LogWriter.parse("파싱 실패: $text")
            return
        }

        LogWriter.parse("파싱 성공: ${parsed.bankName} ${parsed.transactionType} ${parsed.transactionAmount}원 잔액${parsed.balance}원")

        val repository = AccountRepository(applicationContext)
        CoroutineScope(Dispatchers.IO).launch {
            repository.upsertFromSms(parsed)
        }
    }

    /** 거래상대 추출 (여러 패턴 시도) */
    private fun extractSmartCounterparty(content: String, transactionType: String): String {
        // 1) 적요 필드 (신협 줄바꿈 형식)
        val jeokyo = Regex("""적요\s+(.+)""").find(content)?.groupValues?.get(1)?.trim()
        if (!jeokyo.isNullOrEmpty() && jeokyo.length <= 30) return jeokyo

        // 2) KB 형식: 계좌번호 다음 줄 ~ 출금/입금 앞 (예: 올원오픈이원규)
        val lines = content.replace("\r", "").split("\n").map { it.trim() }.filter { it.isNotEmpty() }
        val typeKeyword = if (transactionType == "입금") "입금" else "출금"
        for (i in lines.indices) {
            if (lines[i] == typeKeyword && i > 0) {
                val prev = lines[i - 1]
                // 계좌번호/날짜/금액이 아닌 텍스트면 거래상대
                if (!prev.matches(Regex("""[\d,.*\-/: ]+""")) && prev.length in 2..30) {
                    return prev
                }
            }
        }

        // 3) 기본: 금액~잔액 사이
        return extractCounterparty(content, transactionType)
    }

    private fun parseMessageNotification(title: String, text: String): ParsedTransaction? {
        val content = "$title $text"
        if (!content.contains("잔액")) return null

        val balanceRegex = Regex("""잔액\s*([\d,]+)원?""")
        val balanceMatch = balanceRegex.find(content) ?: return null
        val balance = balanceMatch.groupValues[1].replace(",", "").toLongOrNull() ?: return null

        val accountRegex = Regex("""(\d{3,4}-?\*+\d{1,4}|\d+\*+\d+)""")
        val accountNumber = accountRegex.find(content)?.value ?: ""

        val isDeposit = content.contains("입금")
        val transactionType = if (isDeposit) "입금" else "출금"

        val amountRegex = if (isDeposit) {
            Regex("""입금\s*([\d,]+)원?""")
        } else {
            Regex("""출금\s*([\d,]+)원?|금액\s*([\d,]+)원?""")
        }
        val amountMatch = amountRegex.find(content)
        val amountStr = amountMatch?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() } ?: "0"
        val amount = amountStr.replace(",", "").toLongOrNull() ?: 0L

        // 은행 식별 (누락 방지: 미식별 은행도 "기타"로 기록)
        val bankName = when {
            content.contains("[KB]") || content.contains("국민") -> "KB국민"
            content.contains("하나") -> "하나"
            content.contains("입출금안내") || content.contains("신협") -> "신협"
            content.contains("신한") -> "신한"
            content.contains("우리") -> "우리"
            content.contains("농협") || content.contains("NH") -> "농협"
            content.contains("기업") || content.contains("IBK") -> "기업"
            content.contains("카카오") -> "카카오"
            content.contains("토스") -> "토스"
            else -> "기타"
        }

        // 거래상대 추출: 적요 > KB라인 > 기본
        val counterparty = extractSmartCounterparty(content, transactionType)

        return ParsedTransaction(
            bankName = bankName,
            accountNumber = accountNumber.ifEmpty { "${bankName}계좌" },
            balance = balance,
            transactionType = transactionType,
            transactionAmount = amount,
            counterparty = counterparty,
            rawSms = content
        )
    }
}
