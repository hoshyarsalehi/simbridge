package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface BridgeDao {

    @Query("SELECT * FROM call_logs ORDER BY timestamp DESC LIMIT 50")
    fun observeCallLogs(): Flow<List<CallLogEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCallLog(entity: CallLogEntity)

    @Query("DELETE FROM call_logs")
    suspend fun clearCallLogs()

    @Query("SELECT * FROM sms_logs ORDER BY timestamp DESC LIMIT 100")
    fun observeSmsLogs(): Flow<List<SmsLogEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSmsLog(entity: SmsLogEntity)

    @Query("DELETE FROM sms_logs")
    suspend fun clearSmsLogs()
}
