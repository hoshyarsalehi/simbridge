package com.example.system

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import com.example.model.NetworkTelemetryUi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.Inet4Address

class TetheringHelper(private val context: Context) {

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    private val wifiManager =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    private val _telemetry = MutableStateFlow(NetworkTelemetryUi())
    val telemetry: StateFlow<NetworkTelemetryUi> = _telemetry.asStateFlow()

    private var callbackRegistered = false

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = refreshTelemetry()
        override fun onLost(network: Network) = refreshTelemetry()
        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) =
            refreshTelemetry()
    }

    fun startMonitoring() {
        refreshTelemetry()
        if (callbackRegistered || connectivityManager == null) return
        try {
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            connectivityManager.registerNetworkCallback(request, networkCallback)
            callbackRegistered = true
        } catch (_: Exception) {
        }
    }

    fun stopMonitoring() {
        if (!callbackRegistered || connectivityManager == null) return
        try {
            connectivityManager.unregisterNetworkCallback(networkCallback)
        } catch (_: Exception) {
        } finally {
            callbackRegistered = false
        }
    }

    fun refreshTelemetry() {
        val cm = connectivityManager
        if (cm == null) {
            _telemetry.value = NetworkTelemetryUi()
            return
        }

        val activeNetwork = cm.activeNetwork
        val caps = activeNetwork?.let { cm.getNetworkCapabilities(it) }
        val linkProps = activeNetwork?.let { cm.getLinkProperties(it) }

        val hasInternet = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        val isWifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        val isBtPan = caps?.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) == true
        val isCellular = caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true

        val transportType = when {
            isBtPan -> "BLUETOOTH"
            isWifi -> "WIFI"
            isCellular -> "CELLULAR"
            else -> "OFFLINE"
        }

        val transportLabelFa = when (transportType) {
            "BLUETOOTH" -> "متصل از طریق اینترنت بلوتوث (PAN)"
            "WIFI" -> "متصل از طریق شبکه وای‌فای"
            "CELLULAR" -> "متصل به اینترنت دیتای سیم‌کارت"
            else -> "اتصال اینترنت برقرار نیست"
        }

        val localIp = linkProps?.linkAddresses
            ?.firstOrNull { it.address is Inet4Address }
            ?.address?.hostAddress

        @Suppress("DEPRECATION")
        val ssidRaw = if (isWifi) {
            wifiManager?.connectionInfo?.ssid?.replace("\"", "")?.takeIf {
                it.isNotBlank() && it != "<unknown ssid>"
            }
        } else null

        _telemetry.value = NetworkTelemetryUi(
            isInternetConnected = hasInternet,
            transportType = transportType,
            transportLabelFa = transportLabelFa,
            wifiSsid = ssidRaw,
            localIpAddress = localIp,
            isBluetoothPanConnected = isBtPan
        )
    }

    fun openTetheringSettings(): Boolean {
        val tetherIntent = Intent().apply {
            component = ComponentName(
                "com.android.settings",
                "com.android.settings.TetherSettings"
            )
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return tryStartActivity(tetherIntent) ||
            tryStartActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
    }

    fun openWifiSettings(): Boolean {
        val panelAction = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Settings.Panel.ACTION_WIFI
        } else {
            Settings.ACTION_WIFI_SETTINGS
        }
        return tryStartActivity(Intent(panelAction).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }) || tryStartActivity(Intent(Settings.ACTION_WIFI_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }

    fun openBluetoothSettings(): Boolean {
        return tryStartActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }

    private fun tryStartActivity(intent: Intent): Boolean {
        return try {
            context.startActivity(intent)
            true
        } catch (_: Exception) {
            false
        }
    }
}
