package com.example

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import com.example.bluetooth.BluetoothBridgeManager
import com.example.bluetooth.BridgeCoordinator
import com.example.bluetooth.BridgeForegroundService
import com.example.data.local.BridgeDatabase
import com.example.data.preferences.BridgePreferences
import com.example.data.repository.BridgeRepository
import com.example.system.TelephonyBridgeHelper
import com.example.system.TetheringHelper

class SimBridgeApplication : Application() {

    lateinit var repository: BridgeRepository
        private set

    lateinit var bluetoothManager: BluetoothBridgeManager
        private set

    lateinit var telephonyHelper: TelephonyBridgeHelper
        private set

    lateinit var tetheringHelper: TetheringHelper
        private set

    lateinit var coordinator: BridgeCoordinator
        private set

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()

        val database = BridgeDatabase.getInstance(this)
        val preferences = BridgePreferences(this)
        repository = BridgeRepository(database.bridgeDao(), preferences)
        bluetoothManager = BluetoothBridgeManager(this)
        telephonyHelper = TelephonyBridgeHelper(this)
        tetheringHelper = TetheringHelper(this)
        coordinator = BridgeCoordinator(
            context = this,
            repository = repository,
            bluetoothManager = bluetoothManager,
            telephonyHelper = telephonyHelper
        )
        coordinator.start()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

            val bridgeChannel = NotificationChannel(
                BridgeForegroundService.CHANNEL_BRIDGE_ID,
                getString(R.string.channel_bridge_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.channel_bridge_desc)
            }

            val alertsChannel = NotificationChannel(
                BridgeForegroundService.CHANNEL_ALERTS_ID,
                getString(R.string.channel_alerts_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = getString(R.string.channel_alerts_desc)
                enableVibration(true)
            }

            nm.createNotificationChannel(bridgeChannel)
            nm.createNotificationChannel(alertsChannel)
        }
    }
}
