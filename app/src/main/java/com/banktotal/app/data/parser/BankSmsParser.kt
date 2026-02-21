package com.banktotal.app.data.parser

data class ParsedTransaction(
    val bankName: String,
    val accountNumber: String,
    val balance: Long,
    val transactionType: String,
    val transactionAmount: Long,
    val counterparty: String = "",
    val rawSms: String = ""
)

interface BankSmsParser {
    fun canParse(sender: String, body: String): Boolean
    fun parse(body: String): ParsedTransaction?
}

/** SMS 본문에서 거래상대 추출 (금액~잔액 사이 텍스트) */
fun extractCounterparty(body: String, transactionType: String): String {
    val keyword = if (transactionType == "입금") "입금" else "출금"
    val amountPattern = Regex("""$keyword\s*[\d,]+원?\s*""")
    val amountMatch = amountPattern.find(body) ?: return ""
    val afterAmount = body.substring(amountMatch.range.last + 1)
    val beforeBalance = afterAmount.substringBefore("잔액").trim()
    val cleaned = beforeBalance
        .replace(Regex("""\d{3,4}-?\*+\d{1,4}"""), "")
        .replace(Regex("""\d+\*+\d+"""), "")
        .replace("원", "")
        .trim()
    return if (cleaned.length in 1..30) cleaned else ""
}
