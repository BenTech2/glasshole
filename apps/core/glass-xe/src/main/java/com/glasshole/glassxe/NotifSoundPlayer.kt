package com.glasshole.glassxe

import android.content.Context
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File

/**
 * Decodes a notification sound ID into a playback action. XE mirror
 * of the EE2 file — see that copy for the full design note.
 */
object NotifSoundPlayer {

    private const val TAG = "NotifSoundPlayer"
    const val SOUND_DIR = "/sdcard/GlassHole/notif-sounds"

    val TONES: LinkedHashMap<String, Int> = linkedMapOf(
        "beep" to ToneGenerator.TONE_PROP_BEEP,
        "beep2" to ToneGenerator.TONE_PROP_BEEP2,
        "ack" to ToneGenerator.TONE_PROP_ACK,
        "nack" to ToneGenerator.TONE_PROP_NACK,
        "prompt" to ToneGenerator.TONE_PROP_PROMPT,
        "chime" to ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD,
    )

    fun play(soundId: String, volume: Int) {
        val vol = volume.coerceIn(0, 100)
        if (vol == 0) return
        when {
            soundId.isEmpty() || soundId == "default" -> playTone(ToneGenerator.TONE_PROP_BEEP, vol)
            soundId.startsWith("tone:") -> {
                val key = soundId.removePrefix("tone:")
                playTone(TONES[key] ?: ToneGenerator.TONE_PROP_BEEP, vol)
            }
            soundId.startsWith("file:") -> {
                val name = soundId.removePrefix("file:")
                playFile(name, vol)
            }
            else -> playTone(ToneGenerator.TONE_PROP_BEEP, vol)
        }
    }

    private fun playTone(toneType: Int, volume: Int) {
        try {
            val tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, volume)
            tone.startTone(toneType, 300)
            Handler(Looper.getMainLooper()).postDelayed({ tone.release() }, 500)
        } catch (e: Exception) {
            Log.w(TAG, "ToneGenerator failed: ${e.message}")
        }
    }

    private fun playFile(name: String, volume: Int) {
        val safe = name.substringAfterLast('/').substringAfterLast('\\')
        val file = File(SOUND_DIR, safe)
        if (!file.exists()) {
            Log.w(TAG, "Sound file missing: ${file.absolutePath}")
            return
        }
        try {
            val mp = MediaPlayer().apply {
                @Suppress("DEPRECATION")
                setAudioStreamType(AudioManager.STREAM_NOTIFICATION)
                setDataSource(file.absolutePath)
                val v = volume / 100f
                setVolume(v, v)
                setOnCompletionListener { it.release() }
                setOnErrorListener { p, _, _ -> p.release(); true }
                prepare()
                start()
            }
            Handler(Looper.getMainLooper()).postDelayed({
                try { if (mp.isPlaying) mp.stop(); mp.release() } catch (_: Exception) {}
            }, 5_000L)
        } catch (e: Exception) {
            Log.w(TAG, "MediaPlayer failed for ${file.absolutePath}: ${e.message}")
        }
    }
}
