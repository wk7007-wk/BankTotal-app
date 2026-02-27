package com.banktotal.app.ui.settle

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.banktotal.app.R
import com.banktotal.app.data.repository.SettleRepository
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import java.text.DecimalFormat

/**
 * 정산 항목 터치 시 메뉴 (네이티브 BottomSheet)
 * → WebView onclick 미발화 문제 근본 해결
 *
 * [규칙]
 * - 반복항목: "오늘 삭제" (당일만 제외), 일회성: "삭제" (완전삭제)
 * - SFA/BLOCK 항목은 금액 수정 불가
 */
class SettleMenuBottomSheet : BottomSheetDialogFragment() {

    var item: SettleRepository.SettleViewItem? = null
    var onToggleExclude: (() -> Unit)? = null
    var onDelete: (() -> Unit)? = null
    var onExcludeDay: (() -> Unit)? = null
    var onEdit: (() -> Unit)? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.bottom_sheet_settle_menu, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val item = this.item ?: return dismiss()
        val fmt = DecimalFormat("#,###")

        view.findViewById<TextView>(R.id.sheetTitle).text =
            "${item.name} ${if (item.type == "입금") "+" else "-"}${fmt.format(item.amount)}원"

        view.findViewById<TextView>(R.id.btnToggleExclude).apply {
            text = if (item.excluded) "포함으로 변경" else "제외하기"
            setOnClickListener { onToggleExclude?.invoke(); dismiss() }
        }

        view.findViewById<TextView>(R.id.btnDelete).apply {
            val isRecur = item.cycle != "none" && item.cycle != "once"
            text = if (isRecur) "오늘 삭제" else "삭제"
            setOnClickListener {
                if (isRecur) onExcludeDay?.invoke() else onDelete?.invoke()
                dismiss()
            }
        }

        view.findViewById<TextView>(R.id.btnEdit).apply {
            if (item.isBlock) {
                text = "수정 불가 (SFA)"
                setTextColor(0xFF555555.toInt())
                isEnabled = false
            } else {
                setOnClickListener { onEdit?.invoke(); dismiss() }
            }
        }
    }
}
