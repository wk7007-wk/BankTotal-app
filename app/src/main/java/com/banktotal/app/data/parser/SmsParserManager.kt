package com.banktotal.app.data.parser

class SmsParserManager {
    private val parsers: List<BankSmsParser> = listOf(
        KbParser(),
        HanaParser(),
        ShinHyupParser()
    )

    private val senderToBank = mapOf(
        "16449999" to "KB국민",
        "15880000" to "KB국민",
        "15991111" to "하나",
        "15666000" to "신협",
        "15882222" to "신협"
    )

    fun parse(sender: String, body: String): ParsedTransaction? {
        for (parser in parsers) {
            if (parser.canParse(sender, body)) {
                return parser.parse(body)
            }
        }

        // 발신번호 기반 fallback
        val normalized = sender.replace("-", "").replace(" ", "")
        val bankName = senderToBank.entries.find { (num, _) ->
            normalized.endsWith(num)
        }?.value

        if (bankName != null && body.contains("잔액")) {
            return parseGeneric(bankName, body)
        }

        return null
    }

    private fun parseGeneric(bankName: String, body: String): ParsedTransaction? {
        val balanceMatch = Regex("""잔액\s*([\d,]+)원?""").find(body) ?: return null
        val balance = balanceMatch.groupValues[1].replace(",", "").toLongOrNull() ?: return null

        val accountNumber = Regex("""(\d{3,4}-?\*+\d{1,4}|\d+\*+\d+)""").find(body)?.value
            ?: "${bankName}계좌"

        val isDeposit = body.contains("입금")
        val transactionType = if (isDeposit) "입금" else "출금"
        val amountRegex = if (isDeposit) Regex("""입금\s*([\d,]+)원?""") else Regex("""출금\s*([\d,]+)원?""")
        val amount = amountRegex.find(body)?.groupValues?.get(1)?.replace(",", "")?.toLongOrNull() ?: 0L

        return ParsedTransaction(
            bankName = bankName,
            accountNumber = accountNumber,
            balance = balance,
            transactionType = transactionType,
            transactionAmount = amount,
            counterparty = extractCounterparty(body, transactionType),
            rawSms = body
        )
    }
}
