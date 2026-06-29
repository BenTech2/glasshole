package com.glasshole.plugin.aiassistant.glass

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.GestureDetector
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import java.util.Locale

/**
 * Single-screen voice assistant. Flow:
 *
 *   open → Android SpeechRecognizer starts → "Listening…"
 *        → recognized text appears → ASK envelope sent to phone over BT
 *        → "Thinking…"
 *        → RESPONSE arrives via local broadcast → text shown + optional
 *          TextToSpeech reads it aloud
 *        → tap to ask again, swipe-down to exit
 *
 * The phone proxy (BridgeService.AiAssistantProxy) does the actual
 * provider HTTP call — glass just packages everything the proxy needs
 * (provider, API key, model, system prompt, temperature, max_tokens)
 * into the ASK envelope so the proxy is provider-agnostic.
 *
 * Settings live in [AiAssistantPluginService.PREFS_NAME] and are
 * populated by the phone-side dynamic-settings UI via the SDK's
 * PluginConfigHandler.
 */
class AssistantActivity : Activity() {

    companion object {
        private const val TAG = "AssistantActivity"
        private const val THINKING_TIMEOUT_MS = 60_000L
    }

    private lateinit var statusText: TextView
    private lateinit var transcriptText: TextView
    private lateinit var responseText: TextView
    private lateinit var responseScroll: ScrollView
    private lateinit var hintText: TextView
    private lateinit var swipeDetector: GestureDetector

    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private val thinkingTimeoutRunnable = Runnable {
        if (currentState == State.THINKING) {
            showError("Timed out waiting for the phone proxy")
        }
    }

    /** Tracks where we are in the cycle so taps know whether to
     *  re-listen (idle / done) or be ignored (mid-listen / thinking). */
    private enum class State { IDLE, LISTENING, THINKING, DONE, ERROR }
    private var currentState: State = State.IDLE

    private val replyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val kind = intent.getStringExtra(AiAssistantPluginService.EXTRA_KIND) ?: return
            val text = intent.getStringExtra(AiAssistantPluginService.EXTRA_TEXT) ?: ""
            mainHandler.removeCallbacks(thinkingTimeoutRunnable)
            when (kind) {
                "response" -> showResponse(text)
                "error" -> showError(text)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(16))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        statusText = TextView(this).apply {
            setTextColor(Color.parseColor("#FFEB3B"))
            textSize = 16f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            text = "Listening…"
        }
        col.addView(statusText)

        transcriptText = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 14f
            text = ""
            setPadding(0, dp(6), 0, 0)
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        col.addView(transcriptText)

        responseScroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            ).apply { topMargin = dp(8) }
            isVerticalFadingEdgeEnabled = true
        }
        responseText = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 17f
            setLineSpacing(0f, 1.15f)
        }
        responseScroll.addView(responseText)
        col.addView(responseScroll)

        hintText = TextView(this).apply {
            setTextColor(Color.parseColor("#90A4AE"))
            textSize = 11f
            gravity = Gravity.CENTER
            text = "tap to ask again  •  swipe down to exit"
            setPadding(0, dp(6), 0, 0)
            visibility = View.GONE
        }
        col.addView(hintText)

        root.addView(col)
        setContentView(root)

        swipeDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(
                e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float,
            ): Boolean {
                val dy = if (e1 != null) e2.y - e1.y else 0f
                val dx = if (e1 != null) e2.x - e1.x else 0f
                if (velocityY > 1200 && dy > 80 && Math.abs(dy) > Math.abs(dx) * 1.3f) {
                    finish(); return true
                }
                return false
            }
        })

        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                ttsReady = true
                tts?.language = Locale.getDefault()
            } else {
                Log.w(TAG, "TTS init failed (status=$status)")
            }
        }

        val filter = IntentFilter(AiAssistantPluginService.ACTION_REPLY)
        // SDK 26+ wants the export flag; pre-26 the 2-arg overload is fine.
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            registerReceiver(replyReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(replyReceiver, filter)
        }

        startListening()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        try { unregisterReceiver(replyReceiver) } catch (_: Exception) {}
        try { recognizer?.destroy() } catch (_: Exception) {}
        try { tts?.stop(); tts?.shutdown() } catch (_: Exception) {}
        super.onDestroy()
    }

    private fun startListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            showError("Speech recognition not available on this device")
            return
        }
        currentState = State.LISTENING
        statusText.text = "Listening…"
        statusText.setTextColor(Color.parseColor("#FFEB3B"))
        transcriptText.text = ""
        responseText.text = ""
        hintText.visibility = View.GONE

        try { recognizer?.destroy() } catch (_: Exception) {}
        val rec = SpeechRecognizer.createSpeechRecognizer(this)
        recognizer = rec
        rec.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {
                statusText.text = "Listening…"
            }
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {
                statusText.text = "Processing speech…"
            }
            override fun onError(error: Int) {
                showError("Speech recognizer: ${rec.describeError(error)}")
            }
            override fun onResults(results: Bundle?) {
                val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val best = list?.firstOrNull().orEmpty().trim()
                if (best.isEmpty()) {
                    showError("Didn't catch that — tap to try again")
                } else {
                    transcriptText.text = "You: $best"
                    sendAsk(best)
                }
            }
            override fun onPartialResults(partialResults: Bundle?) {
                val list = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val partial = list?.firstOrNull().orEmpty()
                if (partial.isNotEmpty()) {
                    transcriptText.text = "You: $partial"
                }
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toString())
            // Long pause window so the user can think mid-question.
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2_000L)
        }
        try {
            rec.startListening(intent)
        } catch (e: Exception) {
            showError("Could not start mic: ${e.message}")
        }
    }

    private fun SpeechRecognizer.describeError(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_AUDIO -> "audio error"
        SpeechRecognizer.ERROR_CLIENT -> "client error"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "needs RECORD_AUDIO permission"
        SpeechRecognizer.ERROR_NETWORK -> "network error"
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "network timeout"
        SpeechRecognizer.ERROR_NO_MATCH -> "no match — tap to try again"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "recognizer busy"
        SpeechRecognizer.ERROR_SERVER -> "server error"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "no speech detected — tap to try again"
        else -> "code $code"
    }

    private fun sendAsk(prompt: String) {
        currentState = State.THINKING
        statusText.text = "Thinking…"
        statusText.setTextColor(Color.parseColor("#4FC3F7"))
        responseText.text = ""

        val prefs = getSharedPreferences(AiAssistantPluginService.PREFS_NAME, MODE_PRIVATE)
        val provider = prefs.getString("provider", "openai") ?: "openai"
        val envelope = JSONObject().apply {
            put("prompt", prompt)
            put("provider", provider)
            put("openai_api_key", prefs.getString("openai_api_key", "").orEmpty())
            put("openai_model", prefs.getString("openai_model", "gpt-4o-mini").orEmpty())
            put("anthropic_api_key", prefs.getString("anthropic_api_key", "").orEmpty())
            put("anthropic_model",
                prefs.getString("anthropic_model", "claude-haiku-4-5-20251001").orEmpty())
            put("gemini_api_key", prefs.getString("gemini_api_key", "").orEmpty())
            put("gemini_model", prefs.getString("gemini_model", "gemini-2.5-flash").orEmpty())
            put("system_prompt", prefs.getString("system_prompt", "").orEmpty())
            put("temperature_pct", prefs.getInt("temperature_pct", 30))
            put("max_tokens", prefs.getInt("max_tokens", 400))
        }
        sendToPhone("ASK", envelope.toString())
        mainHandler.postDelayed(thinkingTimeoutRunnable, THINKING_TIMEOUT_MS)
    }

    private fun showResponse(payload: String) {
        currentState = State.DONE
        val text = try {
            JSONObject(payload).optString("text", payload)
        } catch (_: Exception) { payload }
        statusText.text = "Response"
        statusText.setTextColor(Color.parseColor("#A5D6A7"))
        responseText.text = text
        hintText.visibility = View.VISIBLE

        val prefs = getSharedPreferences(AiAssistantPluginService.PREFS_NAME, MODE_PRIVATE)
        if (prefs.getBoolean("voice_response", true) && ttsReady) {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "ai-reply")
        }
    }

    private fun showError(message: String) {
        currentState = State.ERROR
        statusText.text = "Error"
        statusText.setTextColor(Color.parseColor("#EF5350"))
        responseText.text = message
        hintText.visibility = View.VISIBLE
    }

    /** Glass-edition-agnostic phone send via the same MESSAGE_TO_PHONE
     *  broadcast the SDK's GlassPluginService uses internally. We don't
     *  have access to the SDK's protected sendToPhone from here, and
     *  binding the local plugin service just to forward one message
     *  isn't worth the code. Mirrors the chat plugin's pattern. */
    private fun sendToPhone(type: String, payload: String) {
        try {
            val intent = Intent("com.glasshole.glass.MESSAGE_TO_PHONE").apply {
                putExtra("plugin_id", "ai")
                putExtra("message_type", type)
                putExtra("payload", payload)
            }
            for (pkg in listOf(
                "com.glasshole.glassee2", "com.glasshole.glassee2.launcher",
                "com.glasshole.glassee1", "com.glasshole.glassee1.launcher",
                "com.glasshole.glassxe",  "com.glasshole.glassxe.launcher",
            )) {
                intent.setPackage(pkg)
                sendBroadcast(intent)
            }
        } catch (e: Exception) {
            Log.w(TAG, "sendToPhone failed: ${e.message}")
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_BACK -> { finish(); true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                // Idle / done / error states all mean "ready to ask again".
                // Mid-listen and mid-thinking ignore taps so we don't tear
                // down an in-flight request mid-way.
                if (currentState == State.LISTENING || currentState == State.THINKING) return true
                try { tts?.stop() } catch (_: Exception) {}
                startListening()
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { finish() }

    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        if (ev != null) swipeDetector.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
