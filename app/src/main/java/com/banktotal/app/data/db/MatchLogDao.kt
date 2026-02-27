package com.banktotal.app.data.db

import androidx.room.*

@Dao
interface MatchLogDao {

    @Insert
    suspend fun insert(log: MatchLogEntity)

    @Query("SELECT * FROM match_logs ORDER BY created_at DESC LIMIT 200")
    suspend fun getRecent(): List<MatchLogEntity>

    /** 200건 초과 시 오래된 것 삭제 */
    @Query("DELETE FROM match_logs WHERE id NOT IN (SELECT id FROM match_logs ORDER BY created_at DESC LIMIT 200)")
    suspend fun trimOld()

    @Query("SELECT COUNT(*) FROM match_logs")
    suspend fun count(): Int
}
