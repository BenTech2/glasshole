package com.glasshole.phone.plugins.openclaw

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.glasshole.phone.AppLog
import com.glasshole.phone.plugin.PhonePlugin
import com.glasshole.phone.plugin.PluginSender
import com.glasshole.sdk.PluginMessage
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * OpenClaw kickstart plugin. The glass side has a single "open" activity
 * that fires a KICKSTART message at us; we POST the configured kickstart
 * text to the configured Telegram bot. The user then voice-replies in the
 * Telegram app on the phone — we don't bridge the reply chain here.
 *
 * All heavy lifting already lives in the standalone GlassClaw project;
 * this plugin is just the lightweight kickstart-only subset.
 */
class OpenClawPlugin : PhonePlugin {

    companion object {
        private const val TAG = "OpenClawPlugin"
        const val PREFS_NAME = "openclaw_settings"
        const val KEY_BOT_TOKEN = "bot_token"
        const val KEY_CHAT_ID = "chat_id"
        const val KEY_KICKSTART_MSG = "kickstart_message"
        const val DEFAULT_KICKSTART = "Hey, let's chat."

        @Volatile
        var instance: OpenClawPlugin? = null
            private set
    }

    override val pluginId: String = "openclaw"

    private lateinit var appContext: Context
    private lateinit var sender: PluginSender

    private val prefs: SharedPreferences
        get() = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun onCreate(context: Context, sender: PluginSender) {
        this.appContext = context.applicationContext
        this.sender = sender
        instance = this
    }

    override fun onDestroy() {
        instance = null
    }

    override fun onMessageFromGlass(message: PluginMessage) {
        when (message.type) {
            "KICKSTART" -> handleKickstart()
            else -> Log.d(TAG, "Unknown message: ${message.type}")
        }
    }

    private fun handleKickstart() {
        val token = prefs.getString(KEY_BOT_TOKEN, "")?.trim().orEmpty()
        val chatId = prefs.getString(KEY_CHAT_ID, "")?.trim().orEmpty()
        val text = prefs.getString(KEY_KICKSTART_MSG, DEFAULT_KICKSTART)
            ?.takeIf { it.isNotBlank() } ?: DEFAULT_KICKSTART

        if (token.isEmpty() || chatId.isEmpty()) {
            Log.w(TAG, "KICKSTART received but bot token / chat ID not configured")
            AppLog.log(TAG, "OpenClaw: configure bot token + chat ID in plugin settings")
            sender(PluginMessage("KICKSTART_ACK", JSONObject().apply {
                put("ok", false)
                put("error", "not_configured")
            }.toString()))
            return
        }

        Thread {
            val result = postTelegramMessage(token, chatId, text)
            sender(PluginMessage("KICKSTART_ACK", JSONObject().apply {
                put("ok", result.success)
                if (!result.success) put("error", result.error ?: "unknown")
            }.toString()))

            if (result.success) {
                AppLog.log(TAG, "OpenClaw: kickstart sent to chat $chatId")
            } else {
                AppLog.log(TAG, "OpenClaw: kickstart failed — ${result.error}")
            }
        }.start()
    }

    /**
     * Fire a one-off kickstart with explicit values (used by the settings
     * screen's "Send test" button before the user commits the changes).
     * Callback is invoked on a background thread.
     */
    fun sendTest(token: String, chatId: String, text: String, onResult: (Boolean, String?) -> Unit) {
        Thread {
            val r = postTelegramMessage(token.trim(), chatId.trim(), text)
            onResult(r.success, r.error)
        }.start()
    }

    data class PostResult(val success: Boolean, val error: String? = null)

    /**
     * POST /bot<token>/sendMessage with chat_id + text. Matches the
     * GlassClaw kickstart exactly — Telegram's API is JSON-over-HTTPS.
     */
    private fun postTelegramMessage(token: String, chatId: String, text: String): PostResult {
        val urlString = "https://api.telegram.org/bot$token/sendMessage"
        val body = JSONObject().apply {
            put("chat_id", chatId)
            put("text", text)
        }.toString()

        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(urlString).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 10_000
                readTimeout = 10_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            if (code in 200..299) {
                PostResult(success = true)
            } else {
                val err = connection.errorStream?.bufferedReader()?.use { it.readText() }
                Log.w(TAG, "Telegram HTTP $code: $err")
                PostResult(success = false, error = "HTTP $code")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Kickstart POST failed", e)
            PostResult(success = false, error = e.message)
        } finally {
            connection?.disconnect()
        }
    }
}
