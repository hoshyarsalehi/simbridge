package com.example.telecom

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.telecom.DisconnectCause
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import com.example.SimBridgeApplication
import com.example.model.CallBridgeState

/**
 * اتصال تماس‌های گوشی اول به سیستم تماس خود گوشی دوم (صفحه تماس، زنگ و صدای پیش‌فرض).
 * کاربر باید یک بار حساب «SimBridge» را در تنظیمات «حساب‌های تماس» فعال کند.
 */
object BridgeTelecom {

    private const val ACCOUNT_ID = "simbridge_host_sim"

    @Volatile
    var currentConnection: BridgeConnection? = null
        private set

    fun handle(context: Context): PhoneAccountHandle =
        PhoneAccountHandle(
            ComponentName(context.applicationContext, BridgeConnectionService::class.java),
            ACCOUNT_ID
        )

    private fun telecom(context: Context): TelecomManager? =
        context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager

    fun hasActiveConnection(): Boolean = currentConnection != null

    fun registerAccount(context: Context) {
        try {
            val account = PhoneAccount.builder(handle(context), "SimBridge")
                .setCapabilities(PhoneAccount.CAPABILITY_CALL_PROVIDER)
                .setShortDescription("تماس از طریق سیم‌کارت گوشی اول")
                .addSupportedUriScheme(PhoneAccount.SCHEME_TEL)
                .build()
            telecom(context)?.registerPhoneAccount(account)
        } catch (_: Exception) {
        }
    }

    fun unregisterAccount(context: Context) {
        try {
            telecom(context)?.unregisterPhoneAccount(handle(context))
        } catch (_: Exception) {
        }
    }

    @SuppressLint("MissingPermission")
    fun isAccountEnabled(context: Context): Boolean {
        return try {
            val account = telecom(context)?.getPhoneAccount(handle(context)) ?: return false
            val method = PhoneAccount::class.java.getMethod("isEnabled")
            (method.invoke(account) as? Boolean) ?: false
        } catch (_: Exception) {
            false
        }
    }

    fun callAccountSettingsIntent(): Intent =
        Intent(TelecomManager.ACTION_CHANGE_PHONE_ACCOUNTS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    /** زنگ تماس ورودی را روی صفحه تماس خود گوشی دوم نشان می‌دهد. */
    fun addIncomingCall(context: Context, number: String): Boolean {
        val tm = telecom(context) ?: return false
        return try {
            val extras = Bundle().apply {
                putParcelable(
                    TelecomManager.EXTRA_INCOMING_CALL_ADDRESS,
                    Uri.fromParts(PhoneAccount.SCHEME_TEL, number.ifBlank { "unknown" }, null)
                )
            }
            tm.addNewIncomingCall(handle(context), extras)
            true
        } catch (_: Exception) {
            false
        }
    }

    /** شماره‌گیری از داخل برنامه، با همان سیستم تماس گوشی. */
    @SuppressLint("MissingPermission")
    fun placeCall(context: Context, number: String): Boolean {
        if (!isAccountEnabled(context)) return false
        val tm = telecom(context) ?: return false
        return try {
            val extras = Bundle().apply {
                putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, handle(context))
            }
            tm.placeCall(Uri.fromParts(PhoneAccount.SCHEME_TEL, number, null), extras)
            true
        } catch (_: Exception) {
            false
        }
    }

    internal fun attach(connection: BridgeConnection) {
        currentConnection = connection
    }

    internal fun detach(connection: BridgeConnection) {
        if (currentConnection === connection) currentConnection = null
    }

    /** وضعیت تماس گوشی اول را به صفحه تماس سیستم منتقل می‌کند. */
    fun onHostCallState(context: Context, state: CallBridgeState) {
        val connection = currentConnection ?: return
        when (state) {
            CallBridgeState.ACTIVE_BRIDGE -> {
                if (connection.incoming && !connection.answeredLocally) {
                    // تماس روی خود گوشی اول پاسخ داده شد
                    connection.finish(DisconnectCause(DisconnectCause.LOCAL), notifyHost = false)
                } else if (!connection.reachedActive) {
                    connection.reachedActive = true
                    connection.setActive()
                    app(context).coordinator.startVoiceForTelecom()
                }
            }
            CallBridgeState.IDLE -> {
                val code = when {
                    connection.reachedActive -> DisconnectCause.REMOTE
                    connection.incoming -> DisconnectCause.MISSED
                    else -> DisconnectCause.CANCELED
                }
                connection.finish(DisconnectCause(code), notifyHost = false)
            }
            else -> Unit
        }
    }

    fun onLinkLost() {
        currentConnection?.finish(DisconnectCause(DisconnectCause.ERROR), notifyHost = false)
    }

    fun requestAnswer(context: Context) {
        app(context).coordinator.answerFromTelecom()
    }

    fun requestEnd(context: Context) {
        app(context).coordinator.endFromTelecom()
    }

    fun stopVoice(context: Context) {
        app(context).bluetoothManager.stopVoiceBridge()
    }

    private fun app(context: Context): SimBridgeApplication =
        context.applicationContext as SimBridgeApplication
}
