package com.banktotal.app.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 정산 항목 (settleManual + settleAuto 통합)
 *
 * [규칙]
 * - amount를 AI가 자동 변경하면 절대 안 됨 → 반드시 PendingCorrection 경유
 * - SFA/물류 항목은 isBlock=true, 금액은 SfaDaily에서 대입 (보정 금지)
 * - cycle=none인 항목의 date는 한 번만 유효 (지나면 자동제외 대상)
 * - dayOfMonth 범위: 1~31 (31일 없는 달은 마지막 날로 취급)
 * - dayOfWeek: 0=일 ~ 6=토 (Calendar.DAY_OF_WEEK - 1)
 * - source: "manual"=사용자 직접, "auto"=Gemini 고지서감지, "ai"=AI채팅 등록
 * - Firebase ID(sm_xxx)를 그대로 PK로 사용 → 마이그레이션 시 키 보존
 */
@Entity(tableName = "settle_items")
data class SettleItemEntity(
    @PrimaryKey
    val id: String,

    val name: String,

    /** 원 단위. AI가 직접 변경 금지 — PendingCorrection으로만 */
    val amount: Long = 0,

    /** "입금" 또는 "출금" */
    val type: String = "출금",

    /** none / once / daily / weekly / monthly */
    val cycle: String = "none",

    /** monthly일 때 납부일 (1~31). 31 초과 금지 */
    @ColumnInfo(name = "day_of_month")
    val dayOfMonth: Int = 1,

    /** weekly일 때 요일 (0=일~6=토). 7 이상 금지 */
    @ColumnInfo(name = "day_of_week")
    val dayOfWeek: Int = 0,

    /** once일 때 날짜 "yyyy-MM-dd". once 외에는 null */
    val date: String? = null,

    /** manual / auto / ai */
    val source: String = "manual",

    /** SFA/물류 BLOCK 항목 여부. true이면 금액=SfaDaily에서 대입 */
    @ColumnInfo(name = "is_block")
    val isBlock: Boolean = false,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis()
)
