package com.banktotal.app.ui.settle

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.banktotal.app.R
import com.banktotal.app.data.db.PendingCorrectionEntity
import com.banktotal.app.ui.MainViewModel
import java.text.DecimalFormat

class SettleFragment : Fragment() {

    private val viewModel: MainViewModel by activityViewModels()
    private lateinit var adapter: SettleAdapter
    private val fmt = DecimalFormat("#,###")

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_settle, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val recycler = view.findViewById<RecyclerView>(R.id.settleRecycler)

        adapter = SettleAdapter(
            onAmountClick = { item -> showMenu(item) },
            onStatusClick = { key, status -> viewModel.setItemStatus(key, status) },
            onRowAction = { item -> handleRowAction(item) }
        )
        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.adapter = adapter

        viewModel.settleView.observe(viewLifecycleOwner) { result ->
            updateSummary(view, result)
            adapter.submitSettleItems(result.items)
        }

        viewModel.loadSettle()
    }

    override fun onResume() {
        super.onResume()
        viewModel.loadSettle() // 포그라운드 복귀 시 재동기
    }

    private fun updateSummary(view: View, result: com.banktotal.app.data.repository.SettleRepository.SettleViewResult) {
        val s = result.summary

        view.findViewById<TextView>(R.id.todayPredicted).apply {
            text = fmt.format(s.todayPredicted)
            setTextColor(when {
                s.todayPredicted > 0 -> 0xFF4fc3f7.toInt()
                s.todayPredicted < 0 -> 0xFFef5350.toInt()
                else -> 0xFFe0e0e0.toInt()
            })
        }

        view.findViewById<TextView>(R.id.thirtyDayPredicted).apply {
            text = fmt.format(s.thirtyDayPredicted)
            setTextColor(when {
                s.thirtyDayPredicted > 0 -> 0xFF4fc3f7.toInt()
                s.thirtyDayPredicted < 0 -> 0xFFef5350.toInt()
                else -> 0xFFe0e0e0.toInt()
            })
        }

        view.findViewById<TextView>(R.id.currentBalance).text = fmt.format(s.currentBalance)

        view.findViewById<TextView>(R.id.excludedCount).apply {
            if (s.excludedCount > 0) {
                visibility = View.VISIBLE
                text = "${s.excludedCount}건 제외중"
            } else {
                visibility = View.GONE
            }
        }

        // 보정 대기 배너
        val banner = view.findViewById<LinearLayout>(R.id.pendingBanner)
        if (result.pendingCorrections.isNotEmpty()) {
            banner.visibility = View.VISIBLE
            banner.removeAllViews()
            val titleTv = TextView(requireContext()).apply {
                text = "보정 대기 ${result.pendingCorrections.size}건"
                setTextColor(0xFFff9800.toInt())
                textSize = 11f
                setPadding(0, 0, 0, 8)
            }
            banner.addView(titleTv)

            for (pc in result.pendingCorrections) {
                banner.addView(createPendingRow(pc))
            }
        } else {
            banner.visibility = View.GONE
        }
    }

    private fun createPendingRow(pc: PendingCorrectionEntity): View {
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 4, 0, 4)
        }
        val desc = TextView(requireContext()).apply {
            text = pc.description
            setTextColor(0xFFdddddd.toInt())
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val yesBtn = TextView(requireContext()).apply {
            text = "예"
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xFF2e7d32.toInt())
            setPadding(20, 8, 20, 8)
            textSize = 12f
            setOnClickListener { viewModel.applyPendingCorrection(pc) }
        }
        val noBtn = TextView(requireContext()).apply {
            text = "아니오"
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xFF555555.toInt())
            setPadding(20, 8, 20, 8)
            textSize = 12f
            setOnClickListener { viewModel.dismissPendingCorrection(pc.id) }
        }
        row.addView(desc)
        row.addView(yesBtn)
        row.addView(noBtn)
        return row
    }

    /**
     * ✕ 버튼 동작:
     * - 이미 제외 → 포함 복원 (토글)
     * - 1회성(none/once) → 삭제
     * - 반복(daily/weekly/monthly) → 제외 (삭제 불가)
     */
    private fun handleRowAction(item: com.banktotal.app.data.repository.SettleRepository.SettleViewItem) {
        if (item.excluded) {
            // + 버튼: 포함 복원
            viewModel.toggleExclude(item.key)
        } else if (item.cycle == "none" || item.cycle == "once") {
            // 1회성: 삭제
            viewModel.deleteItem(item.itemId, item.cycle, "manual")
        } else {
            // 반복: 제외
            viewModel.toggleExclude(item.key)
        }
    }

    private fun showMenu(item: com.banktotal.app.data.repository.SettleRepository.SettleViewItem) {
        val sheet = SettleMenuBottomSheet().apply {
            this.item = item
            onToggleExclude = { viewModel.toggleExclude(item.key) }
            onDelete = { viewModel.deleteItem(item.itemId, item.cycle, "manual") }
            onEdit = { /* Phase 3에서 구현 */ }
        }
        sheet.show(childFragmentManager, "settle_menu")
    }
}
