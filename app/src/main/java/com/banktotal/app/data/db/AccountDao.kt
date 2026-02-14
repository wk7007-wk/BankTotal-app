package com.banktotal.app.data.db

import androidx.lifecycle.LiveData
import androidx.room.*

@Dao
interface AccountDao {
    @Query("SELECT * FROM accounts ORDER BY bank_name, account_number")
    fun getAllAccounts(): LiveData<List<AccountEntity>>

    @Query("SELECT * FROM accounts WHERE is_active = 1")
    fun getActiveAccounts(): LiveData<List<AccountEntity>>

    @Query("SELECT SUM(balance) FROM accounts WHERE is_active = 1")
    fun getTotalBalance(): LiveData<Long?>

    @Query("SELECT SUM(balance) FROM accounts WHERE is_active = 1")
    suspend fun getTotalBalanceSync(): Long?

    @Query("SELECT * FROM accounts WHERE bank_name = :bankName AND account_number = :accountNumber LIMIT 1")
    suspend fun findAccount(bankName: String, accountNumber: String): AccountEntity?

    @Query("SELECT * FROM accounts WHERE id = :id")
    suspend fun getById(id: Long): AccountEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(account: AccountEntity): Long

    @Update
    suspend fun update(account: AccountEntity)

    @Delete
    suspend fun delete(account: AccountEntity)

    @Query("SELECT MAX(last_updated) FROM accounts")
    suspend fun getLastUpdateTime(): Long?

    @Query("SELECT * FROM accounts ORDER BY bank_name")
    suspend fun getAllAccountsSync(): List<AccountEntity>
}
