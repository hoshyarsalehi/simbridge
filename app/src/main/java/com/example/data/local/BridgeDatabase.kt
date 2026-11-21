package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [CallLogEntity::class, SmsLogEntity::class],
    version = 1,
    exportSchema = false
)
abstract class BridgeDatabase : RoomDatabase() {
    abstract fun bridgeDao(): BridgeDao

    companion object {
        @Volatile
        private var INSTANCE: BridgeDatabase? = null

        fun getInstance(context: Context): BridgeDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    BridgeDatabase::class.java,
                    "simbridge_local.db"
                ).build().also { INSTANCE = it }
            }
        }
    }
}
