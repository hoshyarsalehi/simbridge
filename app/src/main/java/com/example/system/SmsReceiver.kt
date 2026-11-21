package com.example.system

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import com.example.SimBridgeApplication

class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        if (messages.isEmpty()) return

        val sender = messages.firstOrNull()?.displayOriginatingAddress
            ?: messages.firstOrNull()?.originatingAddress
            ?: "ناشناس"

        val fullBody = buildString {
            messages.forEach { msg ->
                append(msg.displayMessageBody ?: msg.messageBody ?: "")
            }
        }

        val timestamp = messages.firstOrNull()?.timestampMillis ?: System.currentTimeMillis()
        if (fullBody.isNotBlank()) {
            val app = context.applicationContext as? SimBridgeApplication
            app?.telephonyHelper?.onIncomingSmsReceived(
                sender = sender,
                body = fullBody,
                timestamp = timestamp
            )
        }
    }
}
