package com.banktotal.app.data.parser

data class ParsedTransaction(
    val bankName: String,
    val accountNumber: String,
    val balance: Long,
    val transactionType: String,
    val transactionAmount: Long
)

interface BankSmsParser {
    fun canParse(sender: String, body: String): Boolean
    fun parse(body: String): ParsedTransaction?
}
