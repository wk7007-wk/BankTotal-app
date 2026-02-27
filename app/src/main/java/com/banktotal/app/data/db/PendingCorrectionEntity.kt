package com.banktotal.app.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * AI 보정 대기큐 — 사용자 승인 전까지 적용 금지
 *
 * [규칙]
 * - AI가 금액을 직접 변경하면 절대 안 됨 → 여기에 쌓고 사용자가 예/아니오
 * - 0원 보정도 금지
 * - SFA 거래(제네시스/올원오픈주/토스(주)제/(주)제)는 보정 대상 아님
 * - 이름 무관한 금액 유사 매칭 금지 (거래상대↔항목명 관련성 필수)
 */
@Entity(tableName = "pending_corrections")
data class PendingCorrectionEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** 보정 대상 settle_items.id */
    @ColumnInfo(name = "settle_item_id")
    val settleItemId: String,

    @ColumnInfo(name = "item_name")
    val itemName: String,

    /** JSON: {"amount": 45000} 등 변경할 필드 */
    @ColumnInfo(name = "patch_json")
    val patchJson: String,

    /** 사용자에게 보여줄 설명 */
    val description: String,

    val counterparty: String = "",

    @ColumnInfo(name = "tx_amount")
    val txAmount: Long = 0,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis()
)
