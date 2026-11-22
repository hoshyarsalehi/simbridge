package com.example.system

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.Telephony
import android.telecom.TelecomManager
import android.telephony.PhoneStateListener
import android.telephony.SmsManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import com.example.model.CallBridgeState
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

data class ReceivedSmsPayload(
    val sender: String,
    val body: String,
    val timestamp: Long = System.currentTimeMillis()
)

data class CallStatePayload(
    val state: CallBridgeState,
    val phoneNumber: String
)

class TelephonyBridgeHelper(private val context: Context) {

    private val telephonyManager =
        context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager

    private val _hostCallState = MutableStateFlow(CallBridgeState.IDLE)
    val hostCallState: StateFlow<CallBridgeState> = _hostCallState.asStateFlow()

    private val _hostCallerNumber = MutableStateFlow("")
    val hostCallerNumber: StateFlow<String> = _hostCallerNumber.asStateFlow()

    private val _incomingCallEvents = MutableSharedFlow<CallStatePayload>(extraBufferCapacity = 16)
    val incomingCallEvents: SharedFlow<CallStatePayload> = _incomingCallEvents.asSharedFlow()

    private val _incomingSmsEvents = MutableSharedFlow<ReceivedSmsPayload>(extraBufferCapacity = 32)
    val incomingSmsEvents: SharedFlow<ReceivedSmsPayload> = _incomingSmsEvents.asSharedFlow()

    private var telephonyCallback: Any? = null
    @Suppress("DEPRECATION")
    private var legacyListener: PhoneStateListener? = null
    private var isRegistered = false

    fun hasPhoneStatePermission(): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_PHONE_STATE
        ) == PackageManager.PERMISSION_GRANTED

    fun hasCallLogPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CALL_LOG
        ) == PackageManager.PERMISSION_GRANTED

    fun hasCallPhonePermission(): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CALL_PHONE
        ) == PackageManager.PERMISSION_GRANTED

    fun hasAnswerCallsPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ANSWER_PHONE_CALLS
            ) == PackageManager.PERMISSION_GRANTED
        } else true

    fun hasSmsPermissions(): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.SEND_SMS
        ) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECEIVE_SMS
            ) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_SMS
            ) == PackageManager.PERMISSION_GRANTED

    fun startObservingCallState() {
        if (isRegistered || telephonyManager == null || !hasPhoneStatePermission()) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val callback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                    override fun onCallStateChanged(state: Int) {
                        updateFromTelephonyState(state, _hostCallerNumber.value)
                    }
                }
                telephonyManager.registerTelephonyCallback(context.mainExecutor, callback)
                telephonyCallback = callback
                isRegistered = true
            } else {
                @Suppress("DEPRECATION")
                val listener = object : PhoneStateListener() {
                    @Deprecated("Deprecated in Java")
                    override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                        updateFromTelephonyState(state, phoneNumber.orEmpty())
                    }
                }
                @Suppress("DEPRECATION")
                telephonyManager.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
                legacyListener = listener
                isRegistered = true
            }
        } catch (_: SecurityException) {
            isRegistered = false
        }
    }

    fun stopObservingCallState() {
        if (!isRegistered || telephonyManager == null) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (telephonyCallback as? TelephonyCallback)?.let {
                    telephonyManager.unregisterTelephonyCallback(it)
                }
                telephonyCallback = null
            } else {
                @Suppress("DEPRECATION")
                legacyListener?.let {
                    telephonyManager.listen(it, PhoneStateListener.LISTEN_NONE)
                }
                legacyListener = null
            }
        } catch (_: Exception) {
        } finally {
            isRegistered = false
        }
    }

    fun onBroadcastPhoneState(stateStr: String?, incomingNumber: String?) {
        val state = when (stateStr) {
            TelephonyManager.EXTRA_STATE_RINGING -> TelephonyManager.CALL_STATE_RINGING
            TelephonyManager.EXTRA_STATE_OFFHOOK -> TelephonyManager.CALL_STATE_OFFHOOK
            else -> TelephonyManager.CALL_STATE_IDLE
        }
        updateFromTelephonyState(state, incomingNumber.orEmpty())
    }

    fun onIncomingSmsReceived(sender: String, body: String, timestamp: Long) {
        _incomingSmsEvents.tryEmit(ReceivedSmsPayload(sender = sender, body = body, timestamp = timestamp))
    }

    private fun updateFromTelephonyState(state: Int, rawNumber: String) {
        val mapped = when (state) {
            TelephonyManager.CALL_STATE_RINGING -> CallBridgeState.INCOMING_RINGING
            TelephonyManager.CALL_STATE_OFFHOOK -> CallBridgeState.ACTIVE_BRIDGE
            else -> CallBridgeState.IDLE
        }
        val cleanNumber = rawNumber.trim()
        if (cleanNumber.isNotEmpty()) {
            _hostCallerNumber.value = cleanNumber
        } else if (mapped == CallBridgeState.IDLE) {
            _hostCallerNumber.value = ""
        }
        _hostCallState.value = mapped
        _incomingCallEvents.tryEmit(
            CallStatePayload(
                state = mapped,
                phoneNumber = if (mapped == CallBridgeState.IDLE) "" else _hostCallerNumber.value
            )
        )
    }

    @SuppressLint("MissingPermission")
    fun placeDirectCall(phoneNumber: String): Boolean {
        val clean = phoneNumber.trim()
        if (clean.isEmpty()) return false
        if (hasCallPhonePermission()) {
            // از پس‌زمینه هم کار می‌کند (بر خلاف باز کردن صفحه با Intent)
            try {
                val tm = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
                if (tm != null) {
                    tm.placeCall(Uri.fromParts("tel", clean, null), null)
                    return true
                }
            } catch (_: Exception) {
            }
        }
        val action = if (hasCallPhonePermission()) Intent.ACTION_CALL else Intent.ACTION_DIAL
        return try {
            val intent = Intent(action, Uri.parse("tel:${Uri.encode(clean)}")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (_: Exception) {
            false
        }
    }

    @SuppressLint("MissingPermission")
    fun answerIncomingCall(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && hasAnswerCallsPermission()) {
            try {
                val tm = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
                tm?.acceptRingingCall()
                enableSpeakerForBridge()
                return true
            } catch (_: Exception) {
            }
        }

        // Android 6 (API 23) - API 25 fallback: headset hook + ITelephony AIDL reflection
        try {
            val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            val now = SystemClock.uptimeMillis()
            audio?.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_HEADSETHOOK, 0))
            audio?.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_HEADSETHOOK, 0))
            enableSpeakerForBridge()
            return true
        } catch (_: Exception) {
        }

        return invokeLegacyTelephonyMethod("answerRingingCall")
    }

    @SuppressLint("MissingPermission")
    fun endCurrentCall(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && hasAnswerCallsPermission()) {
            try {
                val tm = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
                @Suppress("DEPRECATION")
                if (tm?.endCall() == true) return true
            } catch (_: Exception) {
            }
        }
        return invokeLegacyTelephonyMethod("endCall")
    }

    @Suppress("DEPRECATION")
    fun enableSpeakerForBridge() {
        try {
            val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            audio?.mode = AudioManager.MODE_IN_CALL
            audio?.isSpeakerphoneOn = true
        } catch (_: Exception) {
        }
    }

    private fun invokeLegacyTelephonyMethod(methodName: String): Boolean {
        val tm = telephonyManager ?: return false
        return try {
            val getITelephony = tm.javaClass.getDeclaredMethod("getITelephony")
            getITelephony.isAccessible = true
            val iTelephony = getITelephony.invoke(tm) ?: return false
            val method = iTelephony.javaClass.getDeclaredMethod(methodName)
            method.isAccessible = true
            method.invoke(iTelephony)
            true
        } catch (_: Exception) {
            false
        }
    }

    fun sendDirectSms(recipient: String, body: String): Boolean {
        val cleanRecipient = recipient.trim()
        if (cleanRecipient.isEmpty() || body.isEmpty()) return false
        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.SEND_SMS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return launchSystemSmsComposer(cleanRecipient, body)
        }
        return try {
            val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            } ?: return false

            val parts = smsManager.divideMessage(body)
            if (parts.size > 1) {
                smsManager.sendMultipartTextMessage(cleanRecipient, null, parts, null, null)
            } else {
                smsManager.sendTextMessage(cleanRecipient, null, body, null, null)
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    fun readRecentInboxSms(limit: Int = 12): List<ReceivedSmsPayload> {
        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_SMS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return emptyList()
        }
        val result = mutableListOf<ReceivedSmsPayload>()
        try {
            val cursor = context.contentResolver.query(
                Telephony.Sms.Inbox.CONTENT_URI,
                arrayOf(
                    Telephony.Sms.ADDRESS,
                    Telephony.Sms.BODY,
                    Telephony.Sms.DATE
                ),
                null,
                null,
                "${Telephony.Sms.DATE} DESC"
            )
            cursor?.use { c ->
                val addrIdx = c.getColumnIndex(Telephony.Sms.ADDRESS)
                val bodyIdx = c.getColumnIndex(Telephony.Sms.BODY)
                val dateIdx = c.getColumnIndex(Telephony.Sms.DATE)
                while (c.moveToNext() && result.size < limit) {
                    val sender = if (addrIdx >= 0) c.getString(addrIdx).orEmpty() else ""
                    val body = if (bodyIdx >= 0) c.getString(bodyIdx).orEmpty() else ""
                    val date = if (dateIdx >= 0) c.getLong(dateIdx) else System.currentTimeMillis()
                    if (body.isNotBlank()) {
                        result.add(ReceivedSmsPayload(sender = sender, body = body, timestamp = date))
                    }
                }
            }
        } catch (_: Exception) {
        }
        return result
    }

    fun launchSystemSmsComposer(recipient: String, body: String): Boolean {
        val cleanRecipient = recipient.trim()
        if (cleanRecipient.isEmpty()) return false
        return try {
            val intent = Intent(
                Intent.ACTION_SENDTO,
                Uri.parse("smsto:${Uri.encode(cleanRecipient)}")
            ).apply {
                putExtra("sms_body", body)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (_: Exception) {
            false
        }
    }
}
