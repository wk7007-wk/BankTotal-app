package com.banktotal.app.data.repository

import android.content.Context
import com.banktotal.app.data.db.*
import com.banktotal.app.service.FirebaseBackupWriter
import com.banktotal.app.service.LogWriter
import java.text.SimpleDateFormat
import java.util.*

/**
 * 정산 핵심 비즈니스 로직 — 웹 generateAllItems()+renderSettle() 대체
 *
 * [규칙]
 * - Room DB = Single Source of Truth. Firebase는 백업만
 * - AI 금액 변경 절대 금지 → PendingCorrection 경유 필수
 * - SFA/물류 항목의 amount는 SfaDaily에서 대입 (직접 보정 금지)
 * - dateShift 범위: -1 ~ 29 (벗어나면 clamp)
 * - 자동제외 시 manualOverride=true인 항목은 건드리지 않음
 * - 중복 제거: 같은 날짜+이름+타입 → 뒤에서부터 제거 (첫 건 유지)
 * - settleNameMatch: 한글 2자/영문 3자 공통접두어 매칭
 */
class SettleRepository(context: Context) {

    private val db = BankDatabase.getInstance(context)
    private val settleDao = db.settleItemDao()
    private val stateDao = db.settleItemStateDao()
    private val sfaDao = db.sfaDailyDao()
    private val matchLogDao = db.matchLogDao()
    private val pendingDao = db.pendingCorrectionDao()
    private val accountDao = db.accountDao()

    private val dateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.KOREA)

    // --- 뷰 모델용 데이터 클래스 ---

    data class SettleViewItem(
        val key: String,          // state 조회 키
        val itemId: String,       // settle_items.id
        val name: String,
        val amount: Long,
        val type: String,         // "입금" / "출금"
        val dateMs: Long,         // 날짜 timestamp (0시 기준)
        val dateStr: String,      // "yyyy-MM-dd"
        val cycle: String,
        val excluded: Boolean,
        val status: String?,      // confirmed / pending / null
        val manualOverride: Boolean,
        val isBlock: Boolean,
        val isPending: Boolean,   // cycle=none (1회성)
        val autoExcludeReason: String?, // "출금확인", "이월삭제" 등
        val runningBalance: Long  // 이 항목까지의 누적 잔고
    )

    data class SettleSummary(
        val currentBalance: Long,
        val todayPredicted: Long,
        val thirtyDayPredicted: Long,
        val excludedCount: Int
    )

    data class SettleViewResult(
        val summary: SettleSummary,
        val items: List<SettleViewItem>,
        val pendingCorrections: List<PendingCorrectionEntity>
    )

    /**
     * 30일 정산 뷰 생성 — 웹의 generateAllItems()+renderSettle() 전체 대체
     */
    suspend fun generateSettleView(days: Int = 30): SettleViewResult {
        val allItems = settleDao.getAllSync()
        val allStates = stateDao.getAll().associateBy { it.itemKey }
        val currentBalance = accountDao.getSubtotalBalanceSync() ?: 0L
        val pending = pendingDao.getAllSync()

        val now = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val todayMs = now.timeInMillis

        // 1. 항목 전개 (generateAllItems 포팅)
        val expanded = mutableListOf<MutableSettleItem>()
        for (item in allItems) {
            expandItem(item, now, days, allStates, expanded)
        }

        // 2. SFA 금액 대입
        applySfaAmounts(expanded)

        // 3. 자동제외: confirmed 출금 + 지난 입금
        applyAutoExclusion(expanded, allStates, todayMs)

        // 4. 중복 제거
        removeDuplicates(expanded)

        // 5. 날짜순 정렬 + 날짜 내 정렬 (활성→제외, 입금→출금)
        expanded.sortWith(compareBy<MutableSettleItem> { it.dateMs }
            .thenBy { if (it.excluded) 1 else 0 }
            .thenBy { if (it.type == "입금") 0 else 1 })

        // 6. 누적 잔고 계산
        var bal = currentBalance
        val result = expanded.map { item ->
            val delta = if (item.type == "입금") item.amount else -item.amount
            if (!item.excluded) bal += delta
            item.toViewItem(bal)
        }

        // 7. 요약
        val included = result.filter { !it.excluded }
        val todayIncluded = included.filter { it.dateStr == dateFmt.format(Date(todayMs)) }
        val todayIn = todayIncluded.filter { it.type == "입금" }.sumOf { it.amount }
        val todayOut = todayIncluded.filter { it.type == "출금" }.sumOf { it.amount }
        val totalIn = included.filter { it.type == "입금" }.sumOf { it.amount }
        val totalOut = included.filter { it.type == "출금" }.sumOf { it.amount }

        val summary = SettleSummary(
            currentBalance = currentBalance,
            todayPredicted = currentBalance + todayIn - todayOut,
            thirtyDayPredicted = currentBalance + totalIn - totalOut,
            excludedCount = result.count { it.excluded }
        )

        return SettleViewResult(summary, result, pending)
    }

    /** 단일 항목을 날짜별로 전개 */
    private fun expandItem(
        item: SettleItemEntity,
        now: Calendar,
        days: Int,
        states: Map<String, SettleItemStateEntity>,
        out: MutableList<MutableSettleItem>
    ) {
        val todayMs = now.timeInMillis

        if (item.cycle == "none") {
            val st = states[item.id]
            val shift = (st?.dateShift ?: 0).coerceIn(-1, 29)
            val d = todayMs + shift * 86400000L
            out.add(MutableSettleItem(
                key = item.id, itemId = item.id, name = item.name,
                amount = item.amount, type = item.type, dateMs = d,
                cycle = "none", excluded = st?.excluded == true,
                status = st?.status, manualOverride = st?.manualOverride == true,
                isBlock = item.isBlock, isPending = true
            ))
            return
        }

        // 반복항목: 과거 1일(이월용) ~ days일 탐색
        val lookBack = if (item.cycle == "weekly" || item.cycle == "monthly") 1 else 0
        for (i in -lookBack until days) {
            val cal = (now.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, i) }
            val match = when (item.cycle) {
                "once" -> {
                    if (item.date != null) {
                        dateFmt.format(cal.time) == item.date
                    } else false
                }
                "daily" -> true
                "monthly" -> cal.get(Calendar.DAY_OF_MONTH) == item.dayOfMonth
                "weekly" -> (cal.get(Calendar.DAY_OF_WEEK) - 1) == item.dayOfWeek
                else -> false
            }
            if (!match) continue

            val dateKey = dateFmt.format(cal.time)
            val stateKey = "${item.id}_$dateKey"
            val st = states[stateKey]
            val shift = (st?.dateShift ?: 0).coerceIn(-1, 29)
            val adjDay = (i + shift).coerceIn(0, 29)
            val adjMs = todayMs + adjDay * 86400000L

            out.add(MutableSettleItem(
                key = stateKey, itemId = item.id, name = item.name,
                amount = item.amount, type = item.type, dateMs = adjMs,
                cycle = item.cycle, excluded = st?.excluded == true,
                status = st?.status, manualOverride = st?.manualOverride == true,
                isBlock = item.isBlock, isPending = false
            ))
        }
    }

    /** SFA/물류 항목에 당일 BLOCK 금액 대입 (보정 금지 — 그대로 사용) */
    private suspend fun applySfaAmounts(items: MutableList<MutableSettleItem>) {
        for (item in items) {
            if (!item.isBlock && item.name != "물류" && item.name != "SFA") continue
            val dateStr = dateFmt.format(Date(item.dateMs))
            val sfa = sfaDao.getByDate(dateStr)
            if (sfa != null && sfa.amount > 0) {
                item.name = "SFA"
                item.amount = sfa.amount
                item.isBlock = true
            }
        }
    }

    /**
     * 자동제외 규칙:
     * - confirmed 출금 → 이미 잔고에 반영, 이중차감 방지
     * - 날짜 지난 입금 → 이미 잔고에 반영
     * - 날짜 지난 출금 → 오늘로 이월 (제외 안 함)
     * - manualOverride=true이면 자동제외 절대 안 함
     */
    private fun applyAutoExclusion(
        items: MutableList<MutableSettleItem>,
        states: Map<String, SettleItemStateEntity>,
        todayMs: Long
    ) {
        for (item in items) {
            if (item.manualOverride) continue

            // confirmed 출금 → 자동제외
            if (item.status == "confirmed" && item.type == "출금" && !item.excluded) {
                item.excluded = true
                item.autoExcludeReason = "출금확인"
            }

            // 날짜 지난 항목
            if (item.dateMs < todayMs && !item.excluded) {
                if (item.type == "입금") {
                    item.excluded = true
                    item.autoExcludeReason = "이월삭제"
                } else {
                    item.dateMs = todayMs // 출금은 오늘로 이월
                }
            }
        }
    }

    /** 같은 날짜+이름+타입 → 첫 건만 유지 (뒤에서부터 제거) */
    private fun removeDuplicates(items: MutableList<MutableSettleItem>) {
        val seen = mutableSetOf<String>()
        val iter = items.listIterator(items.size)
        while (iter.hasPrevious()) {
            val item = iter.previous()
            val dk = "${dateFmt.format(Date(item.dateMs))}_${item.name}_${item.type}"
            if (!seen.add(dk)) {
                iter.remove()
            }
        }
    }

    // --- CRUD ---

    suspend fun addItem(item: SettleItemEntity) {
        settleDao.insert(item)
        FirebaseBackupWriter.backupSettleItem(item)
    }

    suspend fun updateItem(item: SettleItemEntity) {
        settleDao.update(item)
        FirebaseBackupWriter.backupSettleItem(item)
    }

    suspend fun deleteItem(id: String, source: String) {
        settleDao.deleteById(id)
        stateDao.deleteByItemId(id)
        FirebaseBackupWriter.deleteSettleItem(id, source)
    }

    suspend fun setItemState(key: String, state: SettleItemStateEntity) {
        stateDao.upsert(state)
        FirebaseBackupWriter.backupItemState(state)
    }

    suspend fun getItemState(key: String): SettleItemStateEntity? = stateDao.getState(key)

    /**
     * 정산 자동확인: 새 출금 발생 시 당일 정산 항목과 이름 매칭
     *
     * [규칙]
     * - settleNameMatch: 한글 2자/영문 3자 공통접두어
     * - 매칭되면 status=confirmed → 자동제외 대상
     * - 매칭 결과는 match_logs에 기록 (가이드 강화용)
     */
    suspend fun autoConfirmSettle(counterparty: String, txAmount: Long) {
        if (counterparty.length < 2) return

        val todayDom = Calendar.getInstance().get(Calendar.DAY_OF_MONTH)
        val todayDate = dateFmt.format(Date())
        val items = settleDao.getAllSync()

        for (item in items) {
            if (item.cycle != "monthly" || item.dayOfMonth != todayDom) continue
            if (!settleNameMatch(counterparty, item.name)) continue

            val stateKey = "${item.id}_$todayDate"
            val existing = stateDao.getState(stateKey)
            if (existing?.status == "confirmed") continue

            val newState = SettleItemStateEntity(
                itemKey = stateKey,
                excluded = existing?.excluded ?: false,
                dateShift = existing?.dateShift ?: 0,
                status = "confirmed",
                manualOverride = existing?.manualOverride ?: false
            )
            stateDao.upsert(newState)
            FirebaseBackupWriter.backupItemState(newState)

            LogWriter.sys("[CONFIRM] 자동확인: $counterparty→${item.name} ${txAmount}원")

            matchLogDao.insert(MatchLogEntity(
                counterparty = counterparty,
                itemName = item.name,
                txAmount = txAmount,
                settleAmount = item.amount,
                isAuto = true
            ))
            matchLogDao.trimOld()
        }
    }

    /**
     * 이름 매칭: 한글 2자 / 영문 3자 공통접두어
     * 예: KT7710872402→kt7710(O), 청호나이스→청호렌탈(O)
     */
    fun settleNameMatch(cp: String, name: String): Boolean {
        val a = cp.lowercase()
        val b = name.lowercase()
        if (a.length < 2 || b.length < 2) return false
        if (a.contains(b) || b.contains(a)) return true
        var i = 0
        while (i < a.length && i < b.length && a[i] == b[i]) i++
        val threshold = if (a.firstOrNull()?.let { it in 'a'..'z' } == true) 3 else 2
        return i >= threshold
    }

    // --- 내부 가변 모델 ---

    private data class MutableSettleItem(
        val key: String,
        val itemId: String,
        var name: String,
        var amount: Long,
        val type: String,
        var dateMs: Long,
        val cycle: String,
        var excluded: Boolean,
        val status: String?,
        val manualOverride: Boolean,
        var isBlock: Boolean,
        val isPending: Boolean,
        var autoExcludeReason: String? = null
    ) {
        fun toViewItem(runningBalance: Long) = SettleViewItem(
            key = key, itemId = itemId, name = name, amount = amount,
            type = type, dateMs = dateMs,
            dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.KOREA).format(Date(dateMs)),
            cycle = cycle, excluded = excluded, status = status,
            manualOverride = manualOverride, isBlock = isBlock,
            isPending = isPending, autoExcludeReason = autoExcludeReason,
            runningBalance = runningBalance
        )
    }
}
