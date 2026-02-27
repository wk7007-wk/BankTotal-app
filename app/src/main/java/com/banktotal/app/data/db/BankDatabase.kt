package com.banktotal.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * [규칙]
 * - version 올릴 때 반드시 Migration 추가 (fallbackToDestructiveMigration 절대 금지 — 데이터 소실)
 * - 새 Entity 추가 시 entities 배열 + DAO abstract fun 모두 추가
 * - ALTER TABLE ADD COLUMN에는 반드시 DEFAULT 값 (기존 행 호환)
 */
@Database(
    entities = [
        AccountEntity::class,
        TransactionEntity::class,
        SettleItemEntity::class,
        SettleItemStateEntity::class,
        SfaDailyEntity::class,
        PendingCorrectionEntity::class,
        MatchLogEntity::class
    ],
    version = 3,
    exportSchema = false
)
abstract class BankDatabase : RoomDatabase() {
    abstract fun accountDao(): AccountDao
    abstract fun transactionDao(): TransactionDao
    abstract fun settleItemDao(): SettleItemDao
    abstract fun settleItemStateDao(): SettleItemStateDao
    abstract fun sfaDailyDao(): SfaDailyDao
    abstract fun pendingCorrectionDao(): PendingCorrectionDao
    abstract fun matchLogDao(): MatchLogDao

    companion object {
        @Volatile
        private var INSTANCE: BankDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS transactions (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        bank_name TEXT NOT NULL,
                        transaction_type TEXT NOT NULL,
                        amount INTEGER NOT NULL,
                        timestamp INTEGER NOT NULL
                    )
                """)
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // --- transactions 테이블 확장 컬럼 ---
                database.execSQL("ALTER TABLE transactions ADD COLUMN account_number TEXT NOT NULL DEFAULT ''")
                database.execSQL("ALTER TABLE transactions ADD COLUMN balance INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE transactions ADD COLUMN counterparty TEXT NOT NULL DEFAULT ''")
                database.execSQL("ALTER TABLE transactions ADD COLUMN raw_sms TEXT NOT NULL DEFAULT ''")
                database.execSQL("ALTER TABLE transactions ADD COLUMN is_transfer INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE transactions ADD COLUMN is_duplicate INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE transactions ADD COLUMN firebase_key TEXT NOT NULL DEFAULT ''")

                // --- 새 테이블 5개 ---
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS settle_items (
                        id TEXT NOT NULL PRIMARY KEY,
                        name TEXT NOT NULL,
                        amount INTEGER NOT NULL DEFAULT 0,
                        type TEXT NOT NULL DEFAULT '출금',
                        cycle TEXT NOT NULL DEFAULT 'none',
                        day_of_month INTEGER NOT NULL DEFAULT 1,
                        day_of_week INTEGER NOT NULL DEFAULT 0,
                        date TEXT,
                        source TEXT NOT NULL DEFAULT 'manual',
                        is_block INTEGER NOT NULL DEFAULT 0,
                        created_at INTEGER NOT NULL DEFAULT 0,
                        updated_at INTEGER NOT NULL DEFAULT 0
                    )
                """)

                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS settle_item_states (
                        item_key TEXT NOT NULL PRIMARY KEY,
                        excluded INTEGER NOT NULL DEFAULT 0,
                        date_shift INTEGER NOT NULL DEFAULT 0,
                        status TEXT,
                        manual_override INTEGER NOT NULL DEFAULT 0,
                        updated_at INTEGER NOT NULL DEFAULT 0
                    )
                """)

                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS sfa_daily (
                        date TEXT NOT NULL PRIMARY KEY,
                        amount INTEGER NOT NULL,
                        timestamp INTEGER NOT NULL DEFAULT 0
                    )
                """)

                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS pending_corrections (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        settle_item_id TEXT NOT NULL,
                        item_name TEXT NOT NULL,
                        patch_json TEXT NOT NULL,
                        description TEXT NOT NULL,
                        counterparty TEXT NOT NULL DEFAULT '',
                        tx_amount INTEGER NOT NULL DEFAULT 0,
                        created_at INTEGER NOT NULL DEFAULT 0
                    )
                """)

                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS match_logs (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        counterparty TEXT NOT NULL,
                        item_name TEXT NOT NULL,
                        tx_amount INTEGER NOT NULL,
                        settle_amount INTEGER NOT NULL,
                        is_auto INTEGER NOT NULL DEFAULT 0,
                        created_at INTEGER NOT NULL DEFAULT 0
                    )
                """)
            }
        }

        fun getInstance(context: Context): BankDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    BankDatabase::class.java,
                    "banktotal.db"
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3).build().also { INSTANCE = it }
            }
        }
    }
}
