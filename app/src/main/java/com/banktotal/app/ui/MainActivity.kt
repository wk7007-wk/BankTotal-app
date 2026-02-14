package com.banktotal.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import com.banktotal.app.R
import android.widget.Toast
import com.banktotal.app.data.db.AccountEntity
import com.banktotal.app.data.parser.SmsParserManager
import com.banktotal.app.data.parser.ShinhanNotificationParser
import com.banktotal.app.data.repository.AccountRepository
import com.banktotal.app.databinding.ActivityMainBinding
import com.banktotal.app.databinding.DialogAccountBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var viewModel: AccountViewModel
    private lateinit var adapter: AccountAdapter

    private val decimalFormat = DecimalFormat("#,###")
    private val dateFormat = SimpleDateFormat("MM/dd HH:mm", Locale.KOREA)

    private val smsPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (!allGranted) {
            AlertDialog.Builder(this)
                .setTitle("권한 필요")
                .setMessage("SMS 수신 권한이 없으면 은행 문자를 자동으로 읽을 수 없습니다.")
                .setPositiveButton("확인", null)
                .show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        viewModel = ViewModelProvider(this)[AccountViewModel::class.java]

        setupRecyclerView()
        observeData()
        setupButtons()
        requestPermissions()
    }

    private fun setupRecyclerView() {
        adapter = AccountAdapter(
            onItemClick = { account -> showEditDialog(account) },
            onItemLongClick = { account -> showAccountOptions(account) }
        )
        binding.rvAccounts.layoutManager = LinearLayoutManager(this)
        binding.rvAccounts.adapter = adapter
    }

    private fun observeData() {
        viewModel.allAccounts.observe(this) { accounts ->
            adapter.submitList(accounts)

            // 마지막 업데이트 시각
            val lastUpdate = accounts.maxOfOrNull { it.lastUpdated } ?: 0L
            binding.tvLastUpdated.text = if (lastUpdate > 0) {
                "마지막 업데이트: ${dateFormat.format(Date(lastUpdate))}"
            } else {
                "마지막 업데이트: --"
            }
        }

        viewModel.totalBalance.observe(this) { total ->
            val balance = total ?: 0L
            binding.tvTotalBalance.text = "${decimalFormat.format(balance)}원"
        }
    }

    private fun setupButtons() {
        binding.btnAdd.setOnClickListener { showAddDialog() }
        binding.btnTest.setOnClickListener { showTestMenu() }
    }

    private fun showTestMenu() {
        val options = arrayOf("SMS 파싱 테스트 (KB/하나/신협)", "신한 알림 테스트", "전체 테스트 + DB 저장")
        AlertDialog.Builder(this)
            .setTitle("테스트 메뉴")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> runSmsParsingTest()
                    1 -> runShinhanNotificationTest()
                    2 -> runFullTestAndSave()
                }
            }
            .show()
    }

    private fun runSmsParsingTest() {
        val parserManager = SmsParserManager()
        val shinhanParser = ShinhanNotificationParser()
        val repository = AccountRepository(applicationContext)

        val testCases = listOf(
            Triple("KB", "15880000", "[KB]입금 500,000원 123-***-456 잔액1,234,567원"),
            Triple("하나", "15991111", "하나은행 456-***-789 입금 300,000원 잔액 2,500,000원"),
            Triple("신협", "15882222", "입출금안내 신협 789-***-012 출금 50,000원 잔액 800,000원"),
        )

        val results = StringBuilder("=== SMS 파싱 테스트 ===\n\n")
        var successCount = 0

        for ((bank, sender, body) in testCases) {
            val parsed = parserManager.parse(sender, body)
            if (parsed != null) {
                successCount++
                results.append("[$bank] 성공\n")
                results.append("  은행: ${parsed.bankName}\n")
                results.append("  계좌: ${parsed.accountNumber}\n")
                results.append("  잔액: ${DecimalFormat("#,###").format(parsed.balance)}원\n")
                results.append("  거래: ${parsed.transactionType} ${DecimalFormat("#,###").format(parsed.transactionAmount)}원\n\n")
            } else {
                results.append("[$bank] 실패 - 파싱 불가\n\n")
            }
        }

        // 신한 알림 테스트
        val shinhanResult = shinhanParser.parse("신한은행", "456-***-321 입금 1,000,000원 잔액 5,000,000원")
        if (shinhanResult != null) {
            successCount++
            results.append("[신한 알림] 성공\n")
            results.append("  은행: ${shinhanResult.bankName}\n")
            results.append("  계좌: ${shinhanResult.accountNumber}\n")
            results.append("  잔액: ${DecimalFormat("#,###").format(shinhanResult.balance)}원\n")
            results.append("  거래: ${shinhanResult.transactionType} ${DecimalFormat("#,###").format(shinhanResult.transactionAmount)}원\n\n")
        } else {
            results.append("[신한 알림] 실패\n\n")
        }

        results.append("결과: $successCount/4 성공")

        // DB 저장 테스트
        AlertDialog.Builder(this)
            .setTitle("SMS 파싱 테스트 결과")
            .setMessage(results.toString())
            .setPositiveButton("DB에 저장 테스트") { _, _ ->
                CoroutineScope(Dispatchers.IO).launch {
                    for ((_, sender, body) in testCases) {
                        val parsed = parserManager.parse(sender, body) ?: continue
                        repository.upsertFromSms(
                            parsed.bankName, parsed.accountNumber,
                            parsed.balance, parsed.transactionType, parsed.transactionAmount
                        )
                    }
                    val sr = shinhanParser.parse("신한은행", "456-***-321 입금 1,000,000원 잔액 5,000,000원")
                    if (sr != null) {
                        repository.upsertFromSms(sr.bankName, sr.accountNumber, sr.balance, sr.transactionType, sr.transactionAmount)
                    }
                    launch(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "4개 테스트 계좌 DB 저장 완료!", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("닫기", null)
            .show()
    }

    private fun runShinhanNotificationTest() {
        val parser = ShinhanNotificationParser()
        val repository = AccountRepository(applicationContext)

        val testNotifications = listOf(
            Triple("입금 알림", "신한SOL", "123-***-456 입금 1,500,000원 잔액 8,200,000원"),
            Triple("출금 알림", "신한SOL", "123-***-456 출금 300,000원 잔액 7,900,000원"),
            Triple("이체 알림", "신한은행", "789-***-012 입금 50,000원 잔액 1,230,000원"),
        )

        val results = StringBuilder()
        results.append("=== 신한 알림 파싱 테스트 ===\n\n")
        results.append("패키지 감지: ${parser.canParse("com.shinhan.sbanking")}\n")
        results.append("다른 앱 무시: ${!parser.canParse("com.other.app")}\n\n")

        var successCount = 0
        val parsedList = mutableListOf<com.banktotal.app.data.parser.ParsedTransaction>()

        for ((label, title, text) in testNotifications) {
            val parsed = parser.parse(title, text)
            if (parsed != null) {
                successCount++
                parsedList.add(parsed)
                results.append("[$label] 성공\n")
                results.append("  계좌: ${parsed.accountNumber}\n")
                results.append("  잔액: ${decimalFormat.format(parsed.balance)}원\n")
                results.append("  ${parsed.transactionType} ${decimalFormat.format(parsed.transactionAmount)}원\n\n")
            } else {
                results.append("[$label] 실패\n\n")
            }
        }

        // NotificationListenerService 권한 상태
        val listenerEnabled = isNotificationListenerEnabled()
        results.append("---\n")
        results.append("알림 리스너 권한: ${if (listenerEnabled) "활성화" else "비활성화"}\n")
        results.append("파싱 결과: $successCount/${testNotifications.size} 성공\n")

        if (!listenerEnabled) {
            results.append("\n주의: 알림 접근 권한을 활성화해야\n실제 신한SOL 알림을 감지합니다.")
        }

        AlertDialog.Builder(this)
            .setTitle("신한 알림 테스트")
            .setMessage(results.toString())
            .setPositiveButton("DB에 저장") { _, _ ->
                CoroutineScope(Dispatchers.IO).launch {
                    for (parsed in parsedList) {
                        repository.upsertFromSms(
                            parsed.bankName, parsed.accountNumber,
                            parsed.balance, parsed.transactionType, parsed.transactionAmount
                        )
                    }
                    launch(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "신한 ${parsedList.size}건 DB 저장 완료!", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNeutralButton(if (!listenerEnabled) "권한 설정" else null) { _, _ ->
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
            .setNegativeButton("닫기", null)
            .show()
    }

    private fun runFullTestAndSave() {
        val parserManager = SmsParserManager()
        val shinhanParser = ShinhanNotificationParser()
        val repository = AccountRepository(applicationContext)

        CoroutineScope(Dispatchers.IO).launch {
            val smsTests = listOf(
                "15880000" to "[KB]입금 500,000원 123-***-456 잔액1,234,567원",
                "15991111" to "하나은행 456-***-789 입금 300,000원 잔액 2,500,000원",
                "15882222" to "입출금안내 신협 789-***-012 출금 50,000원 잔액 800,000원",
            )
            var count = 0
            for ((sender, body) in smsTests) {
                val parsed = parserManager.parse(sender, body) ?: continue
                repository.upsertFromSms(parsed.bankName, parsed.accountNumber, parsed.balance, parsed.transactionType, parsed.transactionAmount)
                count++
            }

            val shinhanTests = listOf(
                "신한SOL" to "123-***-456 입금 1,500,000원 잔액 8,200,000원",
                "신한은행" to "789-***-012 입금 50,000원 잔액 1,230,000원",
            )
            for ((title, text) in shinhanTests) {
                val parsed = shinhanParser.parse(title, text) ?: continue
                repository.upsertFromSms(parsed.bankName, parsed.accountNumber, parsed.balance, parsed.transactionType, parsed.transactionAmount)
                count++
            }

            launch(Dispatchers.Main) {
                Toast.makeText(this@MainActivity, "전체 $count 건 저장 완료! 총 잔액이 갱신됩니다.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showAddDialog() {
        val dialogBinding = DialogAccountBinding.inflate(LayoutInflater.from(this))

        AlertDialog.Builder(this, R.style.Theme_BankTotal)
            .setTitle("계좌 추가")
            .setView(dialogBinding.root)
            .setPositiveButton("추가") { _, _ ->
                val bankName = dialogBinding.etBankName.text.toString().trim()
                val accountNumber = dialogBinding.etAccountNumber.text.toString().trim()
                val displayName = dialogBinding.etDisplayName.text.toString().trim()
                val balance = dialogBinding.etBalance.text.toString().toLongOrNull() ?: 0L

                if (bankName.isNotEmpty() && accountNumber.isNotEmpty()) {
                    viewModel.addManualAccount(bankName, accountNumber, displayName, balance)
                }
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun showEditDialog(account: AccountEntity) {
        val dialogBinding = DialogAccountBinding.inflate(LayoutInflater.from(this))
        dialogBinding.etBankName.setText(account.bankName)
        dialogBinding.etAccountNumber.setText(account.accountNumber)
        dialogBinding.etDisplayName.setText(account.displayName)
        dialogBinding.etBalance.setText(account.balance.toString())

        AlertDialog.Builder(this, R.style.Theme_BankTotal)
            .setTitle("계좌 편집")
            .setView(dialogBinding.root)
            .setPositiveButton("저장") { _, _ ->
                val updatedAccount = account.copy(
                    bankName = dialogBinding.etBankName.text.toString().trim(),
                    accountNumber = dialogBinding.etAccountNumber.text.toString().trim(),
                    displayName = dialogBinding.etDisplayName.text.toString().trim(),
                    balance = dialogBinding.etBalance.text.toString().toLongOrNull() ?: account.balance
                )
                viewModel.updateAccount(updatedAccount)
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun showAccountOptions(account: AccountEntity) {
        val activeLabel = if (account.isActive) "합산 제외" else "합산 포함"
        val options = arrayOf(activeLabel, "삭제")

        AlertDialog.Builder(this)
            .setTitle(account.displayName.ifEmpty { account.accountNumber })
            .setItems(options) { _, which ->
                when (which) {
                    0 -> viewModel.toggleActive(account)
                    1 -> confirmDelete(account)
                }
            }
            .show()
    }

    private fun confirmDelete(account: AccountEntity) {
        AlertDialog.Builder(this)
            .setTitle("삭제 확인")
            .setMessage("${account.bankName} ${account.accountNumber} 계좌를 삭제하시겠습니까?")
            .setPositiveButton("삭제") { _, _ -> viewModel.deleteAccount(account) }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun requestPermissions() {
        // SMS 권한 요청
        val smsPermissions = arrayOf(
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_SMS
        )

        val needSmsPermission = smsPermissions.any {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (needSmsPermission) {
            smsPermissionLauncher.launch(smsPermissions)
        }

        // 알림 접근 권한 확인
        if (!isNotificationListenerEnabled()) {
            AlertDialog.Builder(this)
                .setTitle("알림 접근 권한")
                .setMessage("신한은행 알림을 읽으려면 알림 접근 권한이 필요합니다. 설정으로 이동하시겠습니까?")
                .setPositiveButton("설정으로 이동") { _, _ ->
                    startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                }
                .setNegativeButton("나중에", null)
                .show()
        }
    }

    private fun isNotificationListenerEnabled(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        return flat?.contains(packageName) == true
    }
}
