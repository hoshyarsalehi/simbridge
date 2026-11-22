package com.example.system

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * تست صدای تماس روی گوشی اول: در طول یک تماس زنده، همه روش‌های ضبط صدا را امتحان می‌کند
 * و مشخص می‌کند کدام روش روی همین گوشی صدای طرف مقابل را می‌گیرد.
 */
class CallAudioTester(private val context: Context) {

    data class SourceResult(
        val source: Int,
        val label: String,
        val supported: Boolean,
        val peak: Int
    ) {
        val audible: Boolean get() = supported && peak >= MIN_AUDIBLE_PEAK
    }

    companion object {
        const val SAMPLE_RATE = 16000
        const val RECORD_MS = 2500L
        const val MIN_AUDIBLE_PEAK = 400

        val CANDIDATES: List<Pair<Int, String>> = listOf(
            MediaRecorder.AudioSource.VOICE_DOWNLINK to "صدای مستقیم طرف مقابل",
            MediaRecorder.AudioSource.VOICE_CALL to "ضبط مستقیم تماس",
            MediaRecorder.AudioSource.VOICE_RECOGNITION to "میکروفون بدون حذف پژواک",
            MediaRecorder.AudioSource.MIC to "میکروفون معمولی",
            MediaRecorder.AudioSource.VOICE_COMMUNICATION to "میکروفون مکالمه"
        )
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    suspend fun run(onProgress: (String) -> Unit): List<SourceResult> =
        withContext(Dispatchers.IO) {
            val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            val previousSpeaker = audio?.isSpeakerphoneOn
            try {
                audio?.isSpeakerphoneOn = true
            } catch (_: Exception) {
            }
            val results = mutableListOf<SourceResult>()
            try {
                for ((source, label) in CANDIDATES) {
                    onProgress(label)
                    results.add(measure(source, label))
                }
            } finally {
                try {
                    previousSpeaker?.let { audio?.isSpeakerphoneOn = it }
                } catch (_: Exception) {
                }
            }
            results
        }

    @SuppressLint("MissingPermission")
    private fun measure(source: Int, label: String): SourceResult {
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(2048)
        var recorder: AudioRecord? = null
        return try {
            recorder = AudioRecord(
                source,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuf
            )
            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                SourceResult(source, label, false, 0)
            } else {
                recorder.startRecording()
                if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                    SourceResult(source, label, false, 0)
                } else {
                    val buf = ShortArray(1024)
                    var peak = 0
                    val end = System.currentTimeMillis() + RECORD_MS
                    while (System.currentTimeMillis() < end) {
                        val n = recorder.read(buf, 0, buf.size)
                        if (n <= 0) break
                        for (i in 0 until n) {
                            val a = abs(buf[i].toInt())
                            if (a > peak) peak = a
                        }
                    }
                    SourceResult(source, label, true, peak)
                }
            }
        } catch (_: Exception) {
            SourceResult(source, label, false, 0)
        } finally {
            try {
                recorder?.stop()
            } catch (_: Exception) {
            }
            try {
                recorder?.release()
            } catch (_: Exception) {
            }
        }
    }

    /** روش‌های مستقیم (تمیزتر و بدون پژواک) اولویت دارند؛ بعد بلندترین میکروفون. */
    fun chooseBest(results: List<SourceResult>): SourceResult? {
        val audible = results.filter { it.audible }
        if (audible.isEmpty()) return null
        audible.firstOrNull {
            it.source == MediaRecorder.AudioSource.VOICE_DOWNLINK ||
                it.source == MediaRecorder.AudioSource.VOICE_CALL
        }?.let { return it }
        return audible.maxByOrNull { it.peak }
    }
}
