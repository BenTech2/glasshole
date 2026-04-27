package com.glasshole.plugin.notes.glass

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognizerIntent
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import com.glasshole.glass.sdk.GlassPluginConstants
import org.json.JSONObject

class DictateActivity : Activity() {

    companion object {
        private const val TAG = "NotesDictate"
        private const val REQUEST_VOICE = 1
        private const val AUTO_DISMISS_MS = 3000L
    }

    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        GlassBaseAppStarter.start(this)
        launchVoiceInput()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun launchVoiceInput() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak your note")
        }
        try {
            startActivityForResult(intent, REQUEST_VOICE)
        } catch (e: Exception) {
            Log.e(TAG, "Voice recognition not available: ${e.message}")
            showResult("Voice input not available")
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_VOICE) {
            if (resultCode == RESULT_OK && data != null) {
                val results = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                val text = results?.firstOrNull() ?: ""
                if (text.isNotEmpty()) {
                    sendNoteToPhone(text)
                    showResult("Note saved:\n$text")
                } else {
                    showResult("No speech detected")
                }
            } else {
                showResult("Cancelled")
            }
        }
    }

    private fun sendNoteToPhone(text: String) {
        val payload = JSONObject().apply {
            put("text", text)
            put("timestamp", System.currentTimeMillis())
        }.toString()

        // Send directly via broadcast to the GlassHole base app
        val intent = Intent(GlassPluginConstants.ACTION_MESSAGE_TO_PHONE).apply {
            putExtra(GlassPluginConstants.EXTRA_PLUGIN_ID, "notes")
            putExtra(GlassPluginConstants.EXTRA_MESSAGE_TYPE, "NOTE_SAVED")
            putExtra(GlassPluginConstants.EXTRA_PAYLOAD, payload)
        }
        // Send to whichever GlassHole base app is installed
        for (pkg in listOf("com.glasshole.glassee1", "com.glasshole.glassee2", "com.glasshole.glassxe")) {
            intent.setPackage(pkg)
            sendBroadcast(intent)
        }
        Log.i(TAG, "Note sent to phone: $text")
    }

    private fun showResult(message: String) {
        val textView = TextView(this).apply {
            text = message
            textSize = 24f
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xFF000000.toInt())
            gravity = Gravity.CENTER
            setPadding(32, 32, 32, 32)
        }
        setContentView(textView)

        handler.postDelayed({ finish() }, AUTO_DISMISS_MS)
    }
}
