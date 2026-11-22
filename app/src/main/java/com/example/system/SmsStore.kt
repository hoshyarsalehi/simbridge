package com.example.system

import android.app.role.RoleManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.BaseColumns
import android.provider.Telephony

/**
 * ذخیره پیامک‌های گوشی اول در بانک پیامک سیستمی گوشی دوم.
 * اندروید فقط به «برنامه پیامک پیش‌فرض» اجازه نوشتن در این بانک را می‌دهد.
 */
object SmsStore {

    enum class Result { STORED, DUPLICATE, NOT_DEFAULT, FAILED }

    fun isDefaultSmsApp(context: Context): Boolean =
        try {
            Telephony.Sms.getDefaultSmsPackage(context) == context.packageName
        } catch (_: Exception) {
            false
        }

    fun requestDefaultSmsIntent(context: Context): Intent? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = context.getSystemService(RoleManager::class.java)
            if (roleManager != null && roleManager.isRoleAvailable(RoleManager.ROLE_SMS)) {
                roleManager.createRequestRoleIntent(RoleManager.ROLE_SMS)
            } else {
                null
            }
        } else {
            Intent(Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT).apply {
                putExtra(Telephony.Sms.Intents.EXTRA_PACKAGE_NAME, context.packageName)
            }
        }
    }

    fun insertInbox(
        context: Context,
        address: String,
        body: String,
        date: Long,
        checkDuplicate: Boolean
    ): Result {
        if (!isDefaultSmsApp(context)) return Result.NOT_DEFAULT
        return try {
            val resolver = context.contentResolver
            if (checkDuplicate && alreadyStored(context, address, body, date)) {
                Result.DUPLICATE
            } else {
                val values = ContentValues().apply {
                    put(Telephony.Sms.ADDRESS, address)
                    put(Telephony.Sms.BODY, body)
                    put(Telephony.Sms.DATE, date)
                    put(Telephony.Sms.DATE_SENT, date)
                    put(Telephony.Sms.READ, 0)
                    put(Telephony.Sms.SEEN, 0)
                }
                val uri = resolver.insert(Telephony.Sms.Inbox.CONTENT_URI, values)
                if (uri != null) Result.STORED else Result.FAILED
            }
        } catch (_: Exception) {
            Result.FAILED
        }
    }

    fun insertSent(context: Context, address: String, body: String): Boolean {
        if (!isDefaultSmsApp(context)) return false
        return try {
            val values = ContentValues().apply {
                put(Telephony.Sms.ADDRESS, address)
                put(Telephony.Sms.BODY, body)
                put(Telephony.Sms.DATE, System.currentTimeMillis())
                put(Telephony.Sms.READ, 1)
                put(Telephony.Sms.SEEN, 1)
            }
            context.contentResolver.insert(Telephony.Sms.Sent.CONTENT_URI, values) != null
        } catch (_: Exception) {
            false
        }
    }

    // پیامی با همین فرستنده و متن، در بازه ±۱۰ دقیقه
    private fun alreadyStored(context: Context, address: String, body: String, date: Long): Boolean {
        return try {
            val window = 10L * 60L * 1000L
            context.contentResolver.query(
                Telephony.Sms.Inbox.CONTENT_URI,
                arrayOf(BaseColumns._ID),
                "${Telephony.Sms.ADDRESS}=? AND ${Telephony.Sms.BODY}=? AND ${Telephony.Sms.DATE}>=? AND ${Telephony.Sms.DATE}<=?",
                arrayOf(address, body, (date - window).toString(), (date + window).toString()),
                null
            )?.use { it.count > 0 } ?: false
        } catch (_: Exception) {
            false
        }
    }
}
