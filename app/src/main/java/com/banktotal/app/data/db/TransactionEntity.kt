package com.banktotal.app.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 거래 내역 (v3 확장)
 *
 * [규칙]
 * - isTransfer: 동일금액+5분이내+다른은행+입출금반대 → true (합산제외, 삭제 금지)
 * - isDuplicate: 같은은행+금액+타입, 60초이내 → true (합산제외, 삭제 금지)
 * - 제외 대상도 절대 삭제하지 않음 — 음영처리(opacity 35%)로만 표시
 * - firebaseKey: Firebase POST 시 반환된 키 (백업 추적용, null 가능)
 */
@Entity(tableName = "transactions")
data class TransactionEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "bank_name")
    val bankName: String,

    @ColumnInfo(name = "transaction_type")
    val transactionType: String,

    @ColumnInfo(name = "amount")
    val amount: Long,

    @ColumnInfo(name = "timestamp")
    val timestamp: Long = System.currentTimeMillis(),

    // --- v3 확장 컬럼 (기본값 필수 — 기존 행 호환) ---

    @ColumnInfo(name = "account_number", defaultValue = "")
    val accountNumber: String = "",

    @ColumnInfo(name = "balance", defaultValue = "0")
    val balance: Long = 0,

    @ColumnInfo(name = "counterparty", defaultValue = "")
    val counterparty: String = "",

    @ColumnInfo(name = "raw_sms", defaultValue = "")
    val rawSms: String = "",

    @ColumnInfo(name = "is_transfer", defaultValue = "0")
    val isTransfer: Boolean = false,

    @ColumnInfo(name = "is_duplicate", defaultValue = "0")
    val isDuplicate: Boolean = false,

    @ColumnInfo(name = "firebase_key", defaultValue = "")
    val firebaseKey: String = ""
)
