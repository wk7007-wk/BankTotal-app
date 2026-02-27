package com.banktotal.app.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 매칭 판단 기록 (자동확인/수동확인 모두)
 *
 * [규칙]
 * - 최대 200건 유지 (초과 시 오래된 것부터 삭제)
 * - isAuto=true: autoConfirmSettle에 의한 자동매칭
 * - isAuto=false: 사용자가 직접 확인 버튼 누른 것
 */
@Entity(tableName = "match_logs")
data class MatchLogEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val counterparty: String,

    @ColumnInfo(name = "item_name")
    val itemName: String,

    @ColumnInfo(name = "tx_amount")
    val txAmount: Long,

    @ColumnInfo(name = "settle_amount")
    val settleAmount: Long,

    @ColumnInfo(name = "is_auto")
    val isAuto: Boolean,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis()
)
