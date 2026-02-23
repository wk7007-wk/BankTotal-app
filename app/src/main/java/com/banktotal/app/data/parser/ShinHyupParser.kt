package com.banktotal.app.data.parser

class ShinHyupParser : BankSmsParser {

    override fun canParse(sender: String, body: String): Boolean {
        return body.contains("입출금안내") && body.contains("잔액")
    }

    override fun parse(body: String): ParsedTransaction? {
        val balanceRegex = Regex("""잔액\s*([\d,]+)원""")
        val balanceMatch = balanceRegex.find(body) ?: return null
        val balance = balanceMatch.groupValues[1].replace(",", "").toLongOrNull() ?: return null

        val accountRegex = Regex("""(\d{3,4}-?\*+\d{1,4}|\d+\*+\d+)""")
        val accountNumber = accountRegex.find(body)?.value ?: "신협계좌"

        val isDeposit = body.contains("입금")
        val transactionType = if (isDeposit) "입금" else "출금"

        // 금액 파싱: "금액 N원" (줄바꿈 형식) 우선, 없으면 "입금/출금 N원" (한줄 형식)
        val amountFromField = Regex("""금액\s*([\d,]+)원""").find(body)?.groupValues?.get(1)
        val amountInline = if (isDeposit) {
            Regex("""입금\s*([\d,]+)원""").find(body)?.groupValues?.get(1)
        } else {
            Regex("""출금\s*([\d,]+)원""").find(body)?.groupValues?.get(1)
        }
        val amount = (amountFromField ?: amountInline)?.replace(",", "")?.toLongOrNull() ?: 0L

        // 거래상대: "적요" 필드 우선, 없으면 기본 추출
        val counterparty = Regex("""적요\s+(.+)""").find(body)?.groupValues?.get(1)?.trim()
            ?: extractCounterparty(body, transactionType)

        return ParsedTransaction(
            bankName = "신협",
            accountNumber = accountNumber,
            balance = balance,
            transactionType = transactionType,
            transactionAmount = amount,
            counterparty = counterparty,
            rawSms = body
        )
    }
}
