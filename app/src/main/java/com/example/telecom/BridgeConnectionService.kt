package com.example.telecom

import android.content.Context
import android.net.Uri
import android.telecom.Connection
import android.telecom.ConnectionRequest
import android.telecom.ConnectionService
import android.telecom.DisconnectCause
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import com.example.SimBridgeApplication
import com.example.model.BluetoothLinkState

class BridgeConnectionService : ConnectionService() {

    override fun onCreateOutgoingConnection(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest?
    ): Connection {
        val app = application as SimBridgeApplication
        val number = request?.address?.schemeSpecificPart.orEmpty()
        if (number.isBlank() ||
            app.bluetoothManager.linkState.value != BluetoothLinkState.CONNECTED
        ) {
            return Connection.createFailedConnection(
                DisconnectCause(DisconnectCause.ERROR, "ارتباط بلوتوث با گوشی اول برقرار نیست")
            )
        }
        val connection = BridgeConnection(applicationContext, incoming = false)
        connection.setAddress(request?.address, TelecomManager.PRESENTATION_ALLOWED)
        connection.setDialing()
        BridgeTelecom.attach(connection)
        app.coordinator.requestDial(number)
        return connection
    }

    override fun onCreateIncomingConnection(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest?
    ): Connection {
        val connection = BridgeConnection(applicationContext, incoming = true)
        val address = request?.address
            ?: request?.extras?.getParcelable<Uri>(TelecomManager.EXTRA_INCOMING_CALL_ADDRESS)
        connection.setAddress(address, TelecomManager.PRESENTATION_ALLOWED)
        connection.setRinging()
        BridgeTelecom.attach(connection)
        return connection
    }

    override fun onCreateIncomingConnectionFailed(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest?
    ) {
        // اگر سیستم تماس را نپذیرفت، زنگ جایگزین خود برنامه فعال می‌شود.
    }
}

class BridgeConnection(
    private val appContext: Context,
    val incoming: Boolean
) : Connection() {

    @Volatile
    var answeredLocally = false

    @Volatile
    var reachedActive = false

    init {
        setAudioModeIsVoip(true)
        setConnectionCapabilities(CAPABILITY_MUTE)
    }

    override fun onAnswer() {
        answeredLocally = true
        reachedActive = true
        setActive()
        BridgeTelecom.requestAnswer(appContext)
    }

    override fun onReject() {
        finish(DisconnectCause(DisconnectCause.REJECTED), notifyHost = true)
    }

    override fun onDisconnect() {
        finish(DisconnectCause(DisconnectCause.LOCAL), notifyHost = true)
    }

    override fun onAbort() {
        finish(DisconnectCause(DisconnectCause.LOCAL), notifyHost = true)
    }

    fun finish(cause: DisconnectCause, notifyHost: Boolean) {
        if (state == STATE_DISCONNECTED) return
        setDisconnected(cause)
        destroy()
        BridgeTelecom.detach(this)
        if (notifyHost) {
            BridgeTelecom.requestEnd(appContext)
        } else {
            BridgeTelecom.stopVoice(appContext)
        }
    }
}
