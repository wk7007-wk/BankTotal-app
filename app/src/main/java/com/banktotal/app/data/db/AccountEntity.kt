package com.banktotal.app.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "accounts",
    indices = [Index(value = ["bank_name", "account_number"], unique = true)]
)
data class AccountEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "bank_name")
    val bankName: String,

    @ColumnInfo(name = "account_number")
    val accountNumber: String,

    @ColumnInfo(name = "display_name")
    val displayName: String = "",

    @ColumnInfo(name = "balance")
    val balance: Long = 0,

    @ColumnInfo(name = "last_transaction_type")
    val lastTransactionType: String = "",

    @ColumnInfo(name = "last_transaction_amount")
    val lastTransactionAmount: Long = 0,

    @ColumnInfo(name = "last_updated")
    val lastUpdated: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "is_manual")
    val isManual: Boolean = false,

    @ColumnInfo(name = "is_active")
    val isActive: Boolean = true
)
