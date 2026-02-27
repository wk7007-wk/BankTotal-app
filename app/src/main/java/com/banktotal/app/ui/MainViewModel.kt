package com.banktotal.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.banktotal.app.data.db.BankDatabase
import com.banktotal.app.data.db.PendingCorrectionEntity
import com.banktotal.app.data.db.SettleItemEntity
import com.banktotal.app.data.db.SettleItemStateEntity
import com.banktotal.app.data.repository.SettleRepository
import com.banktotal.app.service.FirebaseBackupWriter
import com.banktotal.app.service.LogWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val settleRepo = SettleRepository(app)
    private val db = BankDatabase.getInstance(app)

    private val _settleView = MutableLiveData<SettleRepository.SettleViewResult>()
    val settleView: LiveData<SettleRepository.SettleViewResult> = _settleView

    private val _aiResponse = MutableLiveData<String>()
    val aiResponse: LiveData<String> = _aiResponse

    fun loadSettle() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val result = settleRepo.generateSettleView()
                _settleView.postValue(result)
            } catch (e: Exception) {
                LogWriter.err("정산 로드 실패: ${e.message}")
            }
        }
    }

    fun setItemStatus(key: String, status: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val existing = settleRepo.getItemState(key)
            val newState = SettleItemStateEntity(
                itemKey = key,
                excluded = existing?.excluded ?: false,
                dateShift = existing?.dateShift ?: 0,
                status = status,
                manualOverride = true // 사용자 직접 조작 → 자동제외 무시
            )
            settleRepo.setItemState(key, newState)
            loadSettle()
        }
    }

    fun toggleExclude(key: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val existing = settleRepo.getItemState(key)
            val newState = SettleItemStateEntity(
                itemKey = key,
                excluded = !(existing?.excluded ?: false),
                dateShift = existing?.dateShift ?: 0,
                status = existing?.status,
                manualOverride = true
            )
            settleRepo.setItemState(key, newState)
            loadSettle()
        }
    }

    /** 반복항목은 삭제 불가 — 제외만 가능 */
    fun deleteItem(itemId: String, cycle: String, source: String) {
        if (cycle != "none" && cycle != "once") {
            LogWriter.sys("반복항목 삭제 시도 차단: $itemId")
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            settleRepo.deleteItem(itemId, source)
            loadSettle()
        }
    }

    fun addItem(item: SettleItemEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            settleRepo.addItem(item)
            loadSettle()
        }
    }

    fun applyPendingCorrection(correction: PendingCorrectionEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val patch = org.json.JSONObject(correction.patchJson)
                val existing = db.settleItemDao().getById(correction.settleItemId) ?: return@launch
                val updated = existing.copy(
                    amount = patch.optLong("amount", existing.amount),
                    updatedAt = System.currentTimeMillis()
                )
                db.settleItemDao().update(updated)
                FirebaseBackupWriter.backupSettleItem(updated)
                db.pendingCorrectionDao().deleteById(correction.id)
                LogWriter.sys("보정 적용: ${correction.description}")
                loadSettle()
            } catch (e: Exception) {
                LogWriter.err("보정 적용 실패: ${e.message}")
            }
        }
    }

    fun dismissPendingCorrection(id: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            db.pendingCorrectionDao().deleteById(id)
            loadSettle()
        }
    }
}
