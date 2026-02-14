package com.banktotal.app.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.banktotal.app.data.db.AccountEntity
import com.banktotal.app.databinding.ItemAccountBinding
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AccountAdapter(
    private val onItemClick: (AccountEntity) -> Unit,
    private val onItemLongClick: (AccountEntity) -> Unit
) : ListAdapter<AccountEntity, AccountAdapter.ViewHolder>(DiffCallback) {

    private val decimalFormat = DecimalFormat("#,###")
    private val dateFormat = SimpleDateFormat("MM/dd HH:mm", Locale.KOREA)
    private val bankAbbr = mapOf("KB국민" to "k", "하나" to "ha", "신협" to "s", "신한" to "sh")

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemAccountBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(
        private val binding: ItemAccountBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(account: AccountEntity) {
            binding.tvBankName.text = bankAbbr[account.bankName] ?: account.bankName
            binding.tvDisplayName.text = account.displayName.ifEmpty {
                account.accountNumber
            }
            binding.tvBalance.text = decimalFormat.format(account.balance)

            val lastTx = if (account.lastTransactionType.isNotEmpty()) {
                "${account.lastTransactionType} ${decimalFormat.format(account.lastTransactionAmount)} · ${dateFormat.format(Date(account.lastUpdated))}"
            } else if (account.lastUpdated > 0) {
                dateFormat.format(Date(account.lastUpdated))
            } else {
                ""
            }
            binding.tvLastTransaction.text = lastTx

            binding.tvActiveStatus.text = if (account.isActive) "합산 포함" else "합산 제외"
            binding.tvActiveStatus.setTextColor(
                if (account.isActive) 0xFF4CAF50.toInt() else 0xFF999999.toInt()
            )

            if (!account.isActive) {
                binding.tvBalance.alpha = 0.5f
            } else {
                binding.tvBalance.alpha = 1.0f
            }

            binding.root.setOnClickListener { onItemClick(account) }
            binding.root.setOnLongClickListener {
                onItemLongClick(account)
                true
            }
        }
    }

    companion object DiffCallback : DiffUtil.ItemCallback<AccountEntity>() {
        override fun areItemsTheSame(oldItem: AccountEntity, newItem: AccountEntity) =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: AccountEntity, newItem: AccountEntity) =
            oldItem == newItem
    }
}
