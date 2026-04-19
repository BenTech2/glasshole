package com.glasshole.plugin.calc.glass

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognizerIntent
import android.util.Log
import android.view.MotionEvent
import android.widget.TextView
import com.glasshole.glass.sdk.GlassPluginMessage

class CalcActivity : Activity() {

    companion object {
        private const val TAG = "CalcActivity"
        private const val REQUEST_VOICE = 1
        private const val AUTO_DISMISS_MS = 10000L
    }

    private lateinit var expressionText: TextView
    private lateinit var resultText: TextView
    private lateinit var hintText: TextView

    private val handler = Handler(Looper.getMainLooper())
    private val autoDismissRunnable = Runnable { finish() }

    private var pluginService: CalcGlassPluginService? = null
    private var serviceBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            // Service is in the same process, so we can cast directly
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            pluginService = null
            serviceBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_calc)

        expressionText = findViewById(R.id.expression_text)
        resultText = findViewById(R.id.result_text)
        hintText = findViewById(R.id.hint_text)

        launchVoiceInput()
    }

    override fun onDestroy() {
        handler.removeCallbacks(autoDismissRunnable)
        super.onDestroy()
    }

    override fun onGenericMotionEvent(event: MotionEvent?): Boolean {
        // Tap to calculate again
        if (event?.action == MotionEvent.ACTION_DOWN) {
            handler.removeCallbacks(autoDismissRunnable)
            launchVoiceInput()
            return true
        }
        return super.onGenericMotionEvent(event)
    }

    private fun launchVoiceInput() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Say a calculation")
        }
        try {
            startActivityForResult(intent, REQUEST_VOICE)
        } catch (e: Exception) {
            Log.e(TAG, "Voice recognition not available: ${e.message}")
            showError("Voice input not available")
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == REQUEST_VOICE) {
            if (resultCode == RESULT_OK && data != null) {
                val matches = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                if (!matches.isNullOrEmpty()) {
                    processSpoken(matches[0])
                } else {
                    showError("No speech detected")
                }
            } else {
                // User cancelled or error — dismiss
                finish()
            }
        }
    }

    private fun processSpoken(spoken: String) {
        try {
            val preprocessed = ExpressionParser.preprocess(spoken)
            val result = ExpressionParser.evaluate(preprocessed)

            // Format result: show as integer if whole number
            val resultStr = if (result == result.toLong().toDouble()) {
                result.toLong().toString()
            } else {
                // Up to 8 decimal places, strip trailing zeros
                "%.8f".format(result).trimEnd('0').trimEnd('.')
            }

            expressionText.text = preprocessed
            resultText.text = "= $resultStr"
            hintText.text = "Tap to calculate again"

            sendResultToPhone(preprocessed, resultStr)
            scheduleAutoDismiss()

        } catch (e: Exception) {
            Log.w(TAG, "Parse error for '$spoken': ${e.message}")
            showError("Could not parse: $spoken")
        }
    }

    private fun showError(message: String) {
        expressionText.text = message
        resultText.text = ""
        resultText.setTextColor(0xFFFF4444.toInt())
        hintText.text = "Tap to try again"
        scheduleAutoDismiss()
    }

    private fun scheduleAutoDismiss() {
        handler.removeCallbacks(autoDismissRunnable)
        handler.postDelayed(autoDismissRunnable, AUTO_DISMISS_MS)
    }

    private fun sendResultToPhone(expr: String, result: String) {
        try {
            val payload = org.json.JSONObject().apply {
                put("expr", expr)
                put("result", result)
                put("timestamp", System.currentTimeMillis())
            }.toString()

            // Send via broadcast (works on all Glass editions)
            val intent = Intent("com.glasshole.glass.MESSAGE_TO_PHONE").apply {
                putExtra("plugin_id", "calc")
                putExtra("message_type", "RESULT")
                putExtra("payload", payload)
            }
            for (pkg in listOf("com.glasshole.glassee1", "com.glasshole.glassxe", "com.glasshole.glassee2")) {
                intent.setPackage(pkg)
                sendBroadcast(intent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send result to phone: ${e.message}")
        }
    }
}
