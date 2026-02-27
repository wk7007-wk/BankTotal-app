package com.banktotal.app.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * SFA(BBQ BLOCK) 일일 금액
 *
 * [규칙]
 * - amount는 양수로 저장 (DB accounts 테이블에서는 음수지만 여기선 원본 양수)
 * - 보정/차감 절대 금지 — BLOCK 금액 그대로 사용
 * - 당일 최초 감지값만 저장 (이후 변동은 무시하지 않되, 초기값은 보존)
 */
@Entity(tableName = "sfa_daily")
data class SfaDailyEntity(
    @PrimaryKey
    val date: String,  // "yyyy-MM-dd"

    val amount: Long,

    val timestamp: Long = System.currentTimeMillis()
)
