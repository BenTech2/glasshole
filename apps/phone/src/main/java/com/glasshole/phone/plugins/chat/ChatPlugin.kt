package com.glasshole.phone.plugins.chat

import android.content.Context
import android.util.Log
import com.glasshole.phone.AppLog
import com.glasshole.phone.plugin.PhonePlugin
import com.glasshole.phone.plugin.PluginSender
import com.glasshole.sdk.PluginMessage
import org.json.JSONObject

/**
 * Hosts the Twitch / YouTube live chat connection for the glass viewer.
 *
 * Connection lifecycle is driven entirely by the glass ChatActivity —
 * when it sends START we open the configured stream; when it sends
 * STOP (or disconnects) we tear down. We never run the client
 * in the background so there's no phantom socket / API quota drain
 * while the user isn't actively viewing.
 */
class ChatPlugin : PhonePlugin {

    companion object {
        private const val TAG = "ChatPlugin"
        const val PREFS_NAME = "chat_settings"
        const val KEY_PLATFORM = "platform"   // "twitch" | "youtube"
        const val KEY_TWITCH_CHANNEL = "twitch_channel"
        const val KEY_YOUTUBE_API_KEY = "youtube_api_key"
        const val KEY_YOUTUBE_VIDEO = "youtube_video"
        const val KEY_FONT_SIZE = "font_size"
        const val DEFAULT_FONT_SIZE = 14
        const val KEY_MAX_MESSAGES = "max_messages"
        const val DEFAULT_MAX_MESSAGES = 200
        const val MAX_MESSAGES_CAP = 1000

        @Volatile
        var instance: ChatPlugin? = null
            private set
    }

    override val pluginId: String = "chat"

    private lateinit var appContext: Context
    private lateinit var sender: PluginSender

    private var twitch: TwitchChatClient? = null
    private var youtube: YouTubeChatClient? = null

    override fun onCreate(context: Context, sender: PluginSender) {
        this.appContext = context.applicationContext
        this.sender = sender
        instance = this
    }

    override fun onDestroy() {
        stopAll()
        instance = null
    }

    override fun onGlassConnectionChanged(connected: Boolean) {
        if (!connected) stopAll()
    }

    override fun onMessageFromGlass(message: PluginMessage) {
        when (message.type) {
            "START" -> startFromSettings()
            "STOP" -> stopAll()
            else -> Log.d(TAG, "Unknown message: ${message.type}")
        }
    }

    private fun startFromSettings() {
        stopAll()

        val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val platform = prefs.getString(KEY_PLATFORM, "twitch") ?: "twitch"

        when (platform) {
            "twitch" -> startTwitch(prefs)
            "youtube" -> startYouTube(prefs)
            else -> sendStatus("Chat: unknown platform '$platform'")
        }
    }

    private fun startTwitch(prefs: android.content.SharedPreferences) {
        val channel = prefs.getString(KEY_TWITCH_CHANNEL, "")?.trim().orEmpty()
        if (channel.isEmpty()) {
            sendStatus("Twitch channel not set — open plugin settings on phone")
            AppLog.log(TAG, "Chat: Twitch channel not configured")
            return
        }
        twitch = TwitchChatClient(
            channel = channel,
            onMessage = { user, text, color -> sendChat(user, text, color) },
            onStatus = { sendStatus(it) }
        ).also { it.start() }
        AppLog.log(TAG, "Chat: Twitch #$channel started")
    }

    private fun startYouTube(prefs: android.content.SharedPreferences) {
        val apiKey = prefs.getString(KEY_YOUTUBE_API_KEY, "")?.trim().orEmpty()
        val video = prefs.getString(KEY_YOUTUBE_VIDEO, "")?.trim().orEmpty()
        if (apiKey.isEmpty() || video.isEmpty()) {
            sendStatus("YouTube API key + live URL not set")
            AppLog.log(TAG, "Chat: YouTube settings incomplete")
            return
        }
        val videoId = YouTubeChatClient.Url.extractVideoId(video)
        if (videoId == null) {
            sendStatus("YouTube: invalid video URL")
            return
        }
        youtube = YouTubeChatClient(
            apiKey = apiKey,
            videoId = videoId,
            onMessage = { user, text, color -> sendChat(user, text, color) },
            onStatus = { sendStatus(it) }
        ).also { it.start() }
        AppLog.log(TAG, "Chat: YouTube $videoId started")
    }

    private fun stopAll() {
        twitch?.stop(); twitch = null
        youtube?.stop(); youtube = null
    }

    private fun sendChat(user: String, text: String, color: String) {
        val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val payload = JSONObject().apply {
            put("user", user)
            put("text", text)
            if (color.isNotEmpty()) put("color", color)
            put("size", prefs.getInt(KEY_FONT_SIZE, DEFAULT_FONT_SIZE))
            put("maxMsgs", prefs.getInt(KEY_MAX_MESSAGES, DEFAULT_MAX_MESSAGES))
        }.toString()
        sender(PluginMessage("CHAT", payload))
    }

    private fun sendStatus(text: String) {
        val payload = JSONObject().apply { put("text", text) }.toString()
        sender(PluginMessage("STATUS", payload))
    }
}
