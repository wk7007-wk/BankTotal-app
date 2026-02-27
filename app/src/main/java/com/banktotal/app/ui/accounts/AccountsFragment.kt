package com.banktotal.app.ui.accounts

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.banktotal.app.data.db.AccountEntity
import com.banktotal.app.data.db.BankDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DecimalFormat

class AccountsFragment : Fragment() {

    private val fmt = DecimalFormat("#,###")

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val root = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF121212.toInt())
            setPadding(12, 12, 12, 12)
        }
        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        loadAccounts(view as LinearLayout)
    }

    override fun onResume() {
        super.onResume()
        (view as? LinearLayout)?.let { loadAccounts(it) }
    }

    private fun loadAccounts(container: LinearLayout) {
        CoroutineScope(Dispatchers.IO).launch {
            val dao = BankDatabase.getInstance(requireContext()).accountDao()
            val accounts = dao.getAllAccountsSync()
            val total = dao.getTotalBalanceSync() ?: 0L
            val subtotal = dao.getSubtotalBalanceSync() ?: 0L

            withContext(Dispatchers.Main) {
                container.removeAllViews()

                // 합계
                addSummaryRow(container, "총합계", total)
                if (total != subtotal) addSummaryRow(container, "소계(양수)", subtotal)

                container.addView(View(requireContext()).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, 2
                    ).apply { topMargin = 12; bottomMargin = 12 }
                    setBackgroundColor(0xFF333333.toInt())
                })

                for (a in accounts) {
                    addAccountRow(container, a)
                }
            }
        }
    }

    private fun addSummaryRow(container: LinearLayout, label: String, amount: Long) {
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(8, 8, 8, 8)
        }
        row.addView(TextView(requireContext()).apply {
            text = label
            setTextColor(0xFF888888.toInt())
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        row.addView(TextView(requireContext()).apply {
            text = "${fmt.format(amount)}원"
            setTextColor(if (amount >= 0) 0xFF4fc3f7.toInt() else 0xFFef5350.toInt())
            textSize = 16f
        })
        container.addView(row)
    }

    private fun addAccountRow(container: LinearLayout, a: AccountEntity) {
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(8, 10, 8, 10)
            setBackgroundColor(0xFF1a1a1a.toInt())
        }
        val nameCol = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        nameCol.addView(TextView(requireContext()).apply {
            text = "${a.bankName} ${a.accountNumber}"
            setTextColor(0xFFaaaaaa.toInt())
            textSize = 12f
        })
        if (a.displayName.isNotEmpty()) {
            nameCol.addView(TextView(requireContext()).apply {
                text = a.displayName
                setTextColor(0xFF666666.toInt())
                textSize = 10f
            })
        }
        row.addView(nameCol)
        row.addView(TextView(requireContext()).apply {
            text = "${fmt.format(a.balance)}원"
            setTextColor(if (a.balance >= 0) 0xFF4fc3f7.toInt() else 0xFFef5350.toInt())
            textSize = 14f
        })
        container.addView(row)
        // 구분선
        container.addView(View(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
            setBackgroundColor(0xFF222222.toInt())
        })
    }
}
