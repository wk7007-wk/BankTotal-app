package com.banktotal.app.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 정산 항목별 날짜 상태 (제외/확인/이동 등)
 *
 * [규칙]
 * - itemKey 형식: "{settleItemId}_{yyyy-MM-dd}" (반복) 또는 "{settleItemId}" (비반복)
 *   → 날짜가 고정이라 다음날에도 제외 상태 유지됨
 *   → 오프셋 기반 키(id_0, id_1)는 사용 금지 (날짜 밀리면 상태 엉킴)
 * - manualOverride=true이면 자동제외 로직이 이 항목을 건드리면 안 됨
 * - dateShift 범위: -1 ~ 29 (29 초과, -2 이하 금지)
 * - status: "confirmed"=출금완료, "pending"=대기중, null=미설정
 */
@Entity(tableName = "settle_item_states")
data class SettleItemStateEntity(
    @PrimaryKey
    @ColumnInfo(name = "item_key")
    val itemKey: String,

    /** true이면 정산 합계에서 제외 (취소선 표시) */
    val excluded: Boolean = false,

    /** 날짜 이동 오프셋 (일). 범위: -1 ~ 29 */
    @ColumnInfo(name = "date_shift")
    val dateShift: Int = 0,

    /** confirmed / pending / null */
    val status: String? = null,

    /** true이면 자동제외 로직 무시 (사용자가 수동으로 토글한 경우) */
    @ColumnInfo(name = "manual_override")
    val manualOverride: Boolean = false,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis()
)
