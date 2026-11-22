package com.example.bluetooth

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import com.example.SimBridgeApplication
import com.example.model.BluetoothLinkState
import com.example.model.DeviceRole
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class BridgeForegroundService : Service() {

    companion object {
        const val CHANNEL_BRIDGE_ID = "simbridge_connection"
        const val CHANNEL_ALERTS_ID = "simbridge_alerts"
        const val NOTIF_SERVICE_ID = 1001
        const val NOTIF_ALERT_ID = 1002

        const val ACTION_START_HOST = "com.example.action.START_HOST"
        const val ACTION_CONNECT_COMPANION = "com.example.action.CONNECT_COMPANION"
        const val ACTION_STOP_BRIDGE = "com.example.action.STOP_BRIDGE"
        const val ACTION_STOP_RING = "com.example.action.STOP_RING"
        const val EXTRA_DEVICE_ADDRESS = "extra_device_address"

        private var activeRingtone: Ringtone? = null

        fun startHost(context: Context) {
            val intent = Intent(context, BridgeForegroundService::class.java).apply {
                action = ACTION_START_HOST
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun connectCompanion(context: Context, address: String) {
            val intent = Intent(context, BridgeForegroundService::class.java).apply {
                action = ACTION_CONNECT_COMPANION
                putExtra(EXTRA_DEVICE_ADDRESS, address)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stopBridge(context: Context) {
            val intent = Intent(context, BridgeForegroundService::class.java).apply {
                action = ACTION_STOP_BRIDGE
            }
            context.startService(intent)
        }

        fun triggerIncomingRingAlert(
            context: Context,
            callerLabel: String,
            playRingtone: Boolean,
            vibrate: Boolean
        ) {
            val openIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            val pendingOpen = PendingIntent.getActivity(
                context,
                1,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(context, CHANNEL_ALERTS_ID)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle("تماس ورودی از سیم‌کارت گوشی اول")
                .setContentText(callerLabel.ifBlank { "زنگ در گوشی میزبان" })
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setAutoCancel(true)
                .setContentIntent(pendingOpen)
                .build()

            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.notify(NOTIF_ALERT_ID, notification)

            if (playRingtone) {
                try {
                    activeRingtone?.stop()
                    val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                    val ringtone = RingtoneManager.getRingtone(context, uri)
                    activeRingtone = ringtone
                    ringtone?.play()
                } catch (_: Exception) {
                }
            }

            if (vibrate) {
                try {
                    val pattern = longArrayOf(0, 450, 250, 450)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                        vm?.defaultVibrator?.vibrate(VibrationEffect.createWaveform(pattern, 0))
                    } else {
                        @Suppress("DEPRECATION")
                        val v = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            v?.vibrate(VibrationEffect.createWaveform(pattern, 0))
                        } else {
                            @Suppress("DEPRECATION")
                            v?.vibrate(pattern, 0)
                        }
                    }
                } catch (_: Exception) {
                }
            }
        }

        fun stopIncomingRingAlert(context: Context) {
            try {
                activeRingtone?.stop()
                activeRingtone = null
            } catch (_: Exception) {
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                    vm?.defaultVibrator?.cancel()
                } else {
                    @Suppress("DEPRECATION")
                    val v = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                    v?.cancel()
                }
            } catch (_: Exception) {
            }
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.cancel(NOTIF_ALERT_ID)
        }

        fun showSmsRelayNotification(context: Context, title: String, body: String) {
            val openIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            val pendingOpen = PendingIntent.getActivity(
                context,
                2,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(context, CHANNEL_ALERTS_ID)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pendingOpen)
                .build()

            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.notify(NOTIF_ALERT_ID + 1, notification)
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        val app = application as SimBridgeApplication
        serviceScope.launch {
            combine(
                app.bluetoothManager.linkState,
                app.bluetoothManager.connectedDeviceName
            ) { state, deviceName ->
                buildServiceNotification(state, deviceName)
            }.collect { notification ->
                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                nm?.notify(NOTIF_SERVICE_ID, notification)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = application as SimBridgeApplication
        val notification = buildServiceNotification(
            app.bluetoothManager.linkState.value,
            app.bluetoothManager.connectedDeviceName.value
        )

        promoteToForeground(notification)

        // اگر سیستم سرویس را بعد از بسته شدن دوباره ساخته است، اتصال را خودکار برگردان
        if (intent == null) {
            serviceScope.launch {
                val settings = app.repository.settingsFlow.first()
                if (settings.deviceRole == DeviceRole.HOST_SIM) {
                    app.telephonyHelper.startObservingCallState()
                    app.bluetoothManager.startHostServer()
                } else if (settings.lastDeviceAddress.isNotBlank()) {
                    app.bluetoothManager.connectToHost(settings.lastDeviceAddress)
                }
            }
        }

        when (intent?.action) {
            ACTION_START_HOST -> {
                app.telephonyHelper.startObservingCallState()
                app.bluetoothManager.startHostServer()
            }
            ACTION_CONNECT_COMPANION -> {
                val addr = intent.getStringExtra(EXTRA_DEVICE_ADDRESS).orEmpty()
                if (addr.isNotBlank()) {
                    app.bluetoothManager.connectToHost(addr)
                }
            }
            ACTION_STOP_RING -> {
                stopIncomingRingAlert(this)
            }
            ACTION_STOP_BRIDGE -> {
                stopIncomingRingAlert(this)
                app.bluetoothManager.disconnect()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_STICKY
    }

    private fun promoteToForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            val micGranted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && micGranted) {
                // برای ادامه کار میکروفون وقتی برنامه بسته یا صفحه خاموش است
                type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
            try {
                startForeground(NOTIF_SERVICE_ID, notification, type)
            } catch (_: Exception) {
                try {
                    startForeground(
                        NOTIF_SERVICE_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                    )
                } catch (_: Exception) {
                    startForeground(NOTIF_SERVICE_ID, notification)
                }
            }
        } else {
            startForeground(NOTIF_SERVICE_ID, notification)
        }
    }

    private fun buildServiceNotification(
        state: BluetoothLinkState,
        deviceName: String?
    ): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingOpen = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val statusText = when (state) {
            BluetoothLinkState.CONNECTED -> getString(R.string.bridge_notif_connected, deviceName ?: "دستگاه")
            BluetoothLinkState.LISTENING -> getString(R.string.bridge_notif_host_waiting)
            BluetoothLinkState.CONNECTING -> "در حال برقراری اتصال بلوتوث…"
            BluetoothLinkState.ERROR -> "خطا در اتصال بلوتوث"
            BluetoothLinkState.DISCONNECTED -> "پل بلوتوث آماده است"
        }

        return NotificationCompat.Builder(this, CHANNEL_BRIDGE_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.bridge_notif_title))
            .setContentText(statusText)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(pendingOpen)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopIncomingRingAlert(this)
        serviceScope.cancel()
        super.onDestroy()
    }
}
