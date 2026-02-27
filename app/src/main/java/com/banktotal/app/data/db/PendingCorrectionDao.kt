package com.banktotal.app.data.db

import androidx.lifecycle.LiveData
import androidx.room.*

@Dao
interface PendingCorrectionDao {

    @Query("SELECT * FROM pending_corrections ORDER BY created_at DESC")
    fun getAll(): LiveData<List<PendingCorrectionEntity>>

    @Query("SELECT * FROM pending_corrections ORDER BY created_at DESC")
    suspend fun getAllSync(): List<PendingCorrectionEntity>

    @Insert
    suspend fun insert(correction: PendingCorrectionEntity)

    @Query("DELETE FROM pending_corrections WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM pending_corrections")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM pending_corrections")
    suspend fun count(): Int
}
