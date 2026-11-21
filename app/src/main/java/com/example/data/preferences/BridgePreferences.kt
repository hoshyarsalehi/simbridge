package com.example.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.model.DeviceRole
import com.example.model.InternetShareMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.simBridgeDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "simbridge_prefs"
)

data class BridgeSettings(
    val deviceRole: DeviceRole = DeviceRole.COMPANION_NO_SIM,
    val internetShareMode: InternetShareMode = InternetShareMode.BLUETOOTH_PAN,
    val lastDeviceAddress: String = "",
    val lastDeviceName: String = "",
    val hotspotSsid: String = "",
    val hotspotPassword: String = "",
    val ringOnCompanion: Boolean = true,
    val vibrateOnCall: Boolean = true
)

class BridgePreferences(private val context: Context) {

    private object Keys {
        val DEVICE_ROLE = stringPreferencesKey("device_role")
        val INTERNET_SHARE_MODE = stringPreferencesKey("internet_share_mode")
        val LAST_DEVICE_ADDRESS = stringPreferencesKey("last_device_address")
        val LAST_DEVICE_NAME = stringPreferencesKey("last_device_name")
        val HOTSPOT_SSID = stringPreferencesKey("hotspot_ssid")
        val HOTSPOT_PASSWORD = stringPreferencesKey("hotspot_password")
        val RING_ON_COMPANION = booleanPreferencesKey("ring_on_companion")
        val VIBRATE_ON_CALL = booleanPreferencesKey("vibrate_on_call")
    }

    val settingsFlow: Flow<BridgeSettings> = context.simBridgeDataStore.data.map { prefs ->
        val role = prefs[Keys.DEVICE_ROLE]?.let { raw ->
            DeviceRole.entries.firstOrNull { it.name == raw }
        } ?: DeviceRole.COMPANION_NO_SIM

        val shareMode = prefs[Keys.INTERNET_SHARE_MODE]?.let { raw ->
            InternetShareMode.entries.firstOrNull { it.name == raw }
        } ?: InternetShareMode.BLUETOOTH_PAN

        BridgeSettings(
            deviceRole = role,
            internetShareMode = shareMode,
            lastDeviceAddress = prefs[Keys.LAST_DEVICE_ADDRESS] ?: "",
            lastDeviceName = prefs[Keys.LAST_DEVICE_NAME] ?: "",
            hotspotSsid = prefs[Keys.HOTSPOT_SSID] ?: "",
            hotspotPassword = prefs[Keys.HOTSPOT_PASSWORD] ?: "",
            ringOnCompanion = prefs[Keys.RING_ON_COMPANION] ?: true,
            vibrateOnCall = prefs[Keys.VIBRATE_ON_CALL] ?: true
        )
    }

    suspend fun setDeviceRole(role: DeviceRole) {
        context.simBridgeDataStore.edit { it[Keys.DEVICE_ROLE] = role.name }
    }

    suspend fun setInternetShareMode(mode: InternetShareMode) {
        context.simBridgeDataStore.edit { it[Keys.INTERNET_SHARE_MODE] = mode.name }
    }

    suspend fun setLastDevice(name: String, address: String) {
        context.simBridgeDataStore.edit {
            it[Keys.LAST_DEVICE_NAME] = name
            it[Keys.LAST_DEVICE_ADDRESS] = address
        }
    }

    suspend fun setHotspotCredentials(ssid: String, password: String) {
        context.simBridgeDataStore.edit {
            it[Keys.HOTSPOT_SSID] = ssid
            it[Keys.HOTSPOT_PASSWORD] = password
        }
    }

    suspend fun setRingOnCompanion(enabled: Boolean) {
        context.simBridgeDataStore.edit { it[Keys.RING_ON_COMPANION] = enabled }
    }

    suspend fun setVibrateOnCall(enabled: Boolean) {
        context.simBridgeDataStore.edit { it[Keys.VIBRATE_ON_CALL] = enabled }
    }
}
