package com.banktotal.app.ui.settle

import android.graphics.Paint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.banktotal.app.R
import com.banktotal.app.data.repository.SettleRepository
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.*

/**
 * 정산 RecyclerView 어댑터
 *
 * [규칙]
 * - 금액 터치 → 네이티브 BottomSheet (WebView onclick 문제 해결)
 * - 제외 항목: opacity 35% + 취소선 (삭제 금지)
 * - 반복항목은 삭제 불가 표시 (제외만)
 */
class SettleAdapter(
    private val onAmountClick: (SettleRepository.SettleViewItem) -> Unit,
    private val onStatusClick: (String, String) -> Unit, // key, status
    private val onRowAction: (SettleRepository.SettleViewItem) -> Unit // ✕ 버튼: 1회성=삭제, 반복=제외토글
) : ListAdapter<SettleAdapter.ListItem, RecyclerView.ViewHolder>(DIFF) {

    companion object {
        private const val TYPE_DATE_HEADER = 0
        private const val TYPE_SETTLE_ROW = 1
        private val fmt = DecimalFormat("#,###")

        private val DIFF = object : DiffUtil.ItemCallback<ListItem>() {
            override fun areItemsTheSame(a: ListItem, b: ListItem) = a.id == b.id
            override fun areContentsTheSame(a: ListItem, b: ListItem) = a == b
        }
    }

    sealed class ListItem(val id: String) {
        data class DateHeader(val dateStr: String, val displayText: String, val isToday: Boolean) :
            ListItem("header_$dateStr")

        data class SettleRow(val item: SettleRepository.SettleViewItem) :
            ListItem("row_${item.key}")
    }

    fun submitSettleItems(items: List<SettleRepository.SettleViewItem>) {
        val list = mutableListOf<ListItem>()
        val dateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.KOREA)
        val today = dateFmt.format(Date())
        val dayNames = arrayOf("일", "월", "화", "수", "목", "금", "토")
        var lastDate = ""

        for (item in items) {
            if (item.dateStr != lastDate) {
                lastDate = item.dateStr
                val cal = Calendar.getInstance().apply { time = dateFmt.parse(item.dateStr)!! }
                val dow = dayNames[cal.get(Calendar.DAY_OF_WEEK) - 1]
                val m = cal.get(Calendar.MONTH) + 1
                val d = cal.get(Calendar.DAY_OF_MONTH)
                val suffix = if (item.dateStr == today) " 오늘" else ""
                list.add(ListItem.DateHeader(item.dateStr, "$m/$d(${dow})$suffix", item.dateStr == today))
            }
            list.add(ListItem.SettleRow(item))
        }
        submitList(list)
    }

    override fun getItemViewType(position: Int) = when (getItem(position)) {
        is ListItem.DateHeader -> TYPE_DATE_HEADER
        is ListItem.SettleRow -> TYPE_SETTLE_ROW
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_DATE_HEADER -> DateHeaderVH(inflater.inflate(R.layout.item_settle_date_header, parent, false))
            else -> SettleRowVH(inflater.inflate(R.layout.item_settle_row, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is ListItem.DateHeader -> (holder as DateHeaderVH).bind(item)
            is ListItem.SettleRow -> (holder as SettleRowVH).bind(item.item)
        }
    }

    inner class DateHeaderVH(view: View) : RecyclerView.ViewHolder(view) {
        private val text: TextView = view.findViewById(R.id.dateHeaderText)
        fun bind(header: ListItem.DateHeader) {
            text.text = header.displayText
            val cal = Calendar.getInstance()
            try {
                cal.time = SimpleDateFormat("yyyy-MM-dd", Locale.KOREA).parse(header.dateStr)!!
            } catch (_: Exception) {}
            text.setTextColor(when (cal.get(Calendar.DAY_OF_WEEK)) {
                Calendar.SUNDAY -> 0xFFef5350.toInt()
                Calendar.SATURDAY -> 0xFF4fc3f7.toInt()
                else -> 0xFF888888.toInt()
            })
            text.setBackgroundColor(if (header.isToday) 0x224fc3f7 else 0x08ffffff)
        }
    }

    inner class SettleRowVH(view: View) : RecyclerView.ViewHolder(view) {
        private val root: LinearLayout = view.findViewById(R.id.settleRowRoot)
        private val btnConfirmed: TextView = view.findViewById(R.id.btnConfirmed)
        private val btnPending: TextView = view.findViewById(R.id.btnPending)
        private val name: TextView = view.findViewById(R.id.itemName)
        private val amount: TextView = view.findViewById(R.id.itemAmount)
        private val balance: TextView = view.findViewById(R.id.itemBalance)
        private val btnAction: TextView = view.findViewById(R.id.btnRowAction)

        fun bind(item: SettleRepository.SettleViewItem) {
            val isIn = item.type == "입금"
            val alpha = if (item.excluded) 0.35f else 1f
            root.alpha = alpha

            // 배경
            root.setBackgroundColor(when {
                item.excluded -> 0x00000000
                item.status == "confirmed" -> 0x184fc3f7
                item.status == "pending" -> 0x18ff9800
                item.isBlock -> 0x11e91e63
                item.isPending -> 0x11ff9800
                else -> 0x00000000
            })

            // 상태 버튼
            val isCf = item.status == "confirmed"
            val isPd = item.status == "pending"
            btnConfirmed.setBackgroundColor(if (isCf) 0xFF4caf50.toInt() else 0xFF333333.toInt())
            btnConfirmed.setTextColor(if (isCf) 0xFFFFFFFF.toInt() else 0xFF888888.toInt())
            btnPending.setBackgroundColor(if (isPd) 0xFFff9800.toInt() else 0xFF333333.toInt())
            btnPending.setTextColor(if (isPd) 0xFFFFFFFF.toInt() else 0xFF888888.toInt())

            btnConfirmed.setOnClickListener { onStatusClick(item.key, "confirmed") }
            btnPending.setOnClickListener { onStatusClick(item.key, "pending") }

            // 항목명
            val tag = when {
                item.isPending -> " ⚡"
                item.autoExcludeReason != null -> " (${item.autoExcludeReason})"
                else -> ""
            }
            name.text = "${item.name}$tag"
            name.setTextColor(when {
                item.isBlock -> 0xFFe91e63.toInt()
                item.isPending -> 0xFFff9800.toInt()
                else -> 0xFFaaaaaa.toInt()
            })
            if (item.excluded) {
                name.paintFlags = name.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
            } else {
                name.paintFlags = name.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
            }

            // 금액
            val sign = if (isIn) "+" else "-"
            amount.text = "$sign${fmt.format(item.amount)}"
            amount.setTextColor(if (isIn) 0xFF4fc3f7.toInt() else 0xFFef5350.toInt())
            if (item.excluded) {
                amount.paintFlags = amount.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
            } else {
                amount.paintFlags = amount.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
            }
            amount.setOnClickListener { onAmountClick(item) }

            // 잔고
            balance.text = fmt.format(item.runningBalance)
            balance.setTextColor(when {
                item.runningBalance > 0 -> 0xFF4fc3f7.toInt()
                item.runningBalance < 0 -> 0xFFef5350.toInt()
                else -> 0xFFe0e0e0.toInt()
            })

            // ✕ 버튼: 1회성(none/once)=삭제, 반복=제외토글
            btnAction.setOnClickListener { onRowAction(item) }
            // 이미 제외된 항목은 + 표시 (포함 복원)
            btnAction.text = if (item.excluded) "+" else "✕"
            btnAction.setTextColor(if (item.excluded) 0xFF4caf50.toInt() else 0xFF666666.toInt())
        }
    }
}
