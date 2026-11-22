package com.example.system

import android.app.NotificationManager
import android.content.Context
import android.media.AudioManager
import android.os.Build

/**
 * خاموش کردن زنگ گوشی اول هنگام اتصال به گوشی دوم.
 * نیاز به دسترسی «مزاحم نشوید» دارد. حالت قبلی زنگ ذخیره و بعد از تماس برگردانده می‌شود.
 */
class RingerSilencer(private val context: Context) {

    private val prefs = context.getSharedPreferences("ringer_state", Context.MODE_PRIVATE)
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    fun hasPolicyAccess(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        return nm?.isNotificationPolicyAccessGranted == true
    }

    fun silence() {
        val am = audio ?: return
        if (!hasPolicyAccess()) return
        if (prefs.contains(KEY_SAVED_MODE)) return
        try {
            prefs.edit().putInt(KEY_SAVED_MODE, am.ringerMode).apply()
            am.ringerMode = AudioManager.RINGER_MODE_SILENT
        } catch (_: Exception) {
            prefs.edit().remove(KEY_SAVED_MODE).apply()
        }
    }

    fun restore() {
        val am = audio ?: return
        if (!prefs.contains(KEY_SAVED_MODE)) return
        val saved = prefs.getInt(KEY_SAVED_MODE, AudioManager.RINGER_MODE_NORMAL)
        prefs.edit().remove(KEY_SAVED_MODE).apply()
        try {
            am.ringerMode = saved
        } catch (_: Exception) {
        }
    }

    private companion object {
        const val KEY_SAVED_MODE = "saved_ringer_mode"
    }
}
