package com.banktotal.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [AccountEntity::class], version = 1, exportSchema = false)
abstract class BankDatabase : RoomDatabase() {
    abstract fun accountDao(): AccountDao

    companion object {
        @Volatile
        private var INSTANCE: BankDatabase? = null

        fun getInstance(context: Context): BankDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    BankDatabase::class.java,
                    "banktotal.db"
                ).build().also { INSTANCE = it }
            }
        }
    }
}
