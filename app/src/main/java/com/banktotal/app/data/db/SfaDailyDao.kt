package com.banktotal.app.data.db

import androidx.room.*

@Dao
interface SfaDailyDao {

    @Query("SELECT * FROM sfa_daily WHERE date = :date")
    suspend fun getByDate(date: String): SfaDailyEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: SfaDailyEntity)

    @Query("SELECT * FROM sfa_daily ORDER BY date DESC LIMIT :limit")
    suspend fun getRecent(limit: Int = 30): List<SfaDailyEntity>
}
