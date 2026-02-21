package com.banktotal.app.data.parser

class ShinhanNotificationParser {

    companion object {
        const val SHINHAN_PACKAGE = "com.shinhan.sbanking"
    }

    fun canParse(packageName: String): Boolean {
        return packageName == SHINHAN_PACKAGE
    }

    fun parse(title: String?, text: String?): ParsedTransaction? {
        val content = listOfNotNull(title, text).joinToString(" ")
        if (!content.contains("잔액")) return null

        val balanceRegex = Regex("""잔액\s*([\d,]+)원""")
        val balanceMatch = balanceRegex.find(content) ?: return null
        val balance = balanceMatch.groupValues[1].replace(",", "").toLongOrNull() ?: return null

        val accountRegex = Regex("""(\d{3,4}-?\*+\d{1,4}|\d+\*+\d+)""")
        val accountNumber = accountRegex.find(content)?.value ?: "신한계좌"

        val isDeposit = content.contains("입금")
        val transactionType = if (isDeposit) "입금" else "출금"

        val amountRegex = if (isDeposit) {
            Regex("""입금\s*([\d,]+)원""")
        } else {
            Regex("""출금\s*([\d,]+)원""")
        }
        val amount = amountRegex.find(content)?.groupValues?.get(1)?.replace(",", "")?.toLongOrNull() ?: 0L

        return ParsedTransaction(
            bankName = "신한",
            accountNumber = accountNumber,
            balance = balance,
            transactionType = transactionType,
            transactionAmount = amount,
            counterparty = extractCounterparty(content, transactionType),
            rawSms = content
        )
    }
}
