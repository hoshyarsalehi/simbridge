package com.example.system

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.provider.Telephony
import com.example.SimBridgeApplication
import com.example.bluetooth.BridgeForegroundService

/** دریافت پیامک واقعی (اگر گوشی دوم سیم‌کارت داشته باشد) وقتی برنامه پیامک پیش‌فرض است. */
class SmsDeliverReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Telephony.Sms.Intents.SMS_DELIVER_ACTION) return
        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        if (messages.isEmpty()) return
        val sender = messages[0].displayOriginatingAddress
            ?: messages[0].originatingAddress
            ?: return
        val body = buildString {
            messages.forEach { append(it.displayMessageBody ?: it.messageBody ?: "") }
        }
        if (body.isBlank()) return
        SmsStore.insertInbox(
            context = context,
            address = sender,
            body = body,
            date = messages[0].timestampMillis,
            checkDuplicate = false
        )
        val name = ContactsHelper.lookupName(context, sender)
        BridgeForegroundService.showSmsRelayNotification(
            context = context,
            title = "پیامک جدید از ${name ?: sender}",
            body = body
        )
    }
}

/** برای واجد شرایط بودن به‌عنوان برنامه پیامک پیش‌فرض لازم است؛ MMS پشتیبانی نمی‌شود. */
class MmsDeliverReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        // MMS در این نسخه پشتیبانی نمی‌شود.
    }
}

/** پاسخ سریع پیامکی هنگام رد تماس؛ از طریق سیم‌کارت گوشی اول ارسال می‌شود. */
class RespondViaMessageService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val number = intent?.data?.schemeSpecificPart
            ?.substringBefore('?')
            ?.trim()
            .orEmpty()
        val text = intent?.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        if (number.isNotEmpty() && text.isNotEmpty()) {
            (application as SimBridgeApplication).coordinator.relaySmsToHost(number, text)
        }
        stopSelf(startId)
        return START_NOT_STICKY
    }
}
