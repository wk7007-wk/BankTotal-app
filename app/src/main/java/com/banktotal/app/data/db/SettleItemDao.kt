package com.banktotal.app.data.db

import androidx.lifecycle.LiveData
import androidx.room.*

@Dao
interface SettleItemDao {

    @Query("SELECT * FROM settle_items ORDER BY created_at")
    fun getAll(): LiveData<List<SettleItemEntity>>

    /** 백그라운드 서비스용 동기 조회 */
    @Query("SELECT * FROM settle_items ORDER BY created_at")
    suspend fun getAllSync(): List<SettleItemEntity>

    @Query("SELECT * FROM settle_items WHERE type = :type ORDER BY created_at")
    suspend fun getByType(type: String): List<SettleItemEntity>

    @Query("SELECT * FROM settle_items WHERE cycle != 'none' ORDER BY created_at")
    suspend fun getRecurring(): List<SettleItemEntity>

    @Query("SELECT * FROM settle_items WHERE id = :id")
    suspend fun getById(id: String): SettleItemEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: SettleItemEntity)

    @Update
    suspend fun update(item: SettleItemEntity)

    @Query("DELETE FROM settle_items WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT COUNT(*) FROM settle_items")
    suspend fun count(): Int
}
