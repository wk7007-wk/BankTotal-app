package com.banktotal.app.data.db

import androidx.room.*

@Dao
interface SettleItemStateDao {

    @Query("SELECT * FROM settle_item_states WHERE item_key = :key")
    suspend fun getState(key: String): SettleItemStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(state: SettleItemStateEntity)

    @Query("SELECT * FROM settle_item_states WHERE excluded = 1")
    suspend fun getExcluded(): List<SettleItemStateEntity>

    @Query("SELECT * FROM settle_item_states WHERE status = 'confirmed'")
    suspend fun getConfirmed(): List<SettleItemStateEntity>

    @Query("SELECT * FROM settle_item_states")
    suspend fun getAll(): List<SettleItemStateEntity>

    @Query("DELETE FROM settle_item_states WHERE item_key = :key")
    suspend fun deleteByKey(key: String)

    /** 특정 settle_item에 속하는 모든 state 삭제 (항목 삭제 시) */
    @Query("DELETE FROM settle_item_states WHERE item_key LIKE :itemIdPrefix || '%'")
    suspend fun deleteByItemId(itemIdPrefix: String)
}
