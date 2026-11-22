package com.example.data.repository

import com.example.data.local.BridgeDao
import com.example.data.local.CallLogEntity
import com.example.data.local.SmsLogEntity
import com.example.data.preferences.BridgePreferences
import com.example.data.preferences.BridgeSettings
import com.example.model.CallEventUi
import com.example.model.DeviceRole
import com.example.model.InternetShareMode
import com.example.model.SmsEventUi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class BridgeRepository(
    private val dao: BridgeDao,
    private val prefs: BridgePreferences
) {
    val settingsFlow: Flow<BridgeSettings> = prefs.settingsFlow

    val callLogsFlow: Flow<List<CallEventUi>> = dao.observeCallLogs().map { list ->
        list.map {
            CallEventUi(
                id = it.id,
                phoneNumber = it.phoneNumber,
                direction = it.direction,
                status = it.status,
                timestamp = it.timestamp
            )
        }
    }

    val smsLogsFlow: Flow<List<SmsEventUi>> = dao.observeSmsLogs().map { list ->
        list.map {
            SmsEventUi(
                id = it.id,
                peer = it.peer,
                body = it.body,
                direction = it.direction,
                status = it.status,
                timestamp = it.timestamp
            )
        }
    }

    suspend fun setDeviceRole(role: DeviceRole) = prefs.setDeviceRole(role)

    suspend fun setInternetShareMode(mode: InternetShareMode) = prefs.setInternetShareMode(mode)

    suspend fun saveLastDevice(name: String, address: String) = prefs.setLastDevice(name, address)

    suspend fun saveHotspotCredentials(ssid: String, password: String) =
        prefs.setHotspotCredentials(ssid, password)

    suspend fun setRingOnCompanion(enabled: Boolean) = prefs.setRingOnCompanion(enabled)

    suspend fun setVibrateOnCall(enabled: Boolean) = prefs.setVibrateOnCall(enabled)

    suspend fun setSilenceHostRinger(enabled: Boolean) = prefs.setSilenceHostRinger(enabled)

    suspend fun setSecurityCode(code: String) = prefs.setSecurityCode(code)

    suspend fun setHostAudioSource(source: Int) = prefs.setHostAudioSource(source)

    suspend fun addCallLog(phoneNumber: String, direction: String, status: String) {
        dao.insertCallLog(
            CallLogEntity(
                phoneNumber = phoneNumber,
                direction = direction,
                status = status,
                timestamp = System.currentTimeMillis()
            )
        )
    }

    suspend fun addSmsLog(peer: String, body: String, direction: String, status: String) {
        dao.insertSmsLog(
            SmsLogEntity(
                peer = peer,
                body = body,
                direction = direction,
                status = status,
                timestamp = System.currentTimeMillis()
            )
        )
    }

    suspend fun clearCallLogs() = dao.clearCallLogs()

    suspend fun clearSmsLogs() = dao.clearSmsLogs()
}
