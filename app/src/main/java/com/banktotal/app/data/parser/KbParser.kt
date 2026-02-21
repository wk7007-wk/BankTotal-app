package com.banktotal.app.data.parser

class KbParser : BankSmsParser {

    override fun canParse(sender: String, body: String): Boolean {
        return body.startsWith("[KB]")
    }

    override fun parse(body: String): ParsedTransaction? {
        val balanceRegex = Regex("""잔액(\d[\d,]*)""")
        val balanceMatch = balanceRegex.find(body) ?: return null
        val balance = balanceMatch.groupValues[1].replace(",", "").toLongOrNull() ?: return null

        val accountRegex = Regex("""(\d{3,4}-?\*+\d{1,4}|\d+\*+\d+)""")
        val accountNumber = accountRegex.find(body)?.value ?: "KB계좌"

        val isDeposit = body.contains("입금")
        val transactionType = if (isDeposit) "입금" else "출금"

        val amountRegex = if (isDeposit) {
            Regex("""입금\s*(\d[\d,]*)""")
        } else {
            Regex("""출금\s*(\d[\d,]*)""")
        }
        val amount = amountRegex.find(body)?.groupValues?.get(1)?.replace(",", "")?.toLongOrNull() ?: 0L

        return ParsedTransaction(
            bankName = "KB국민",
            accountNumber = accountNumber,
            balance = balance,
            transactionType = transactionType,
            transactionAmount = amount,
            counterparty = extractCounterparty(body, transactionType),
            rawSms = body
        )
    }
}
