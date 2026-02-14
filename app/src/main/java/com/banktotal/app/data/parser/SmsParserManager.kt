package com.banktotal.app.data.parser

class SmsParserManager {
    private val parsers: List<BankSmsParser> = listOf(
        KbParser(),
        HanaParser(),
        ShinHyupParser()
    )

    fun parse(sender: String, body: String): ParsedTransaction? {
        for (parser in parsers) {
            if (parser.canParse(sender, body)) {
                return parser.parse(body)
            }
        }
        return null
    }
}
