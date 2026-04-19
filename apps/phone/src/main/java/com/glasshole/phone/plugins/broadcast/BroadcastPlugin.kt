package com.glasshole.phone.plugins.broadcast

import android.content.Context
import android.util.Log
import com.glasshole.phone.AppLog
import com.glasshole.phone.plugin.PhonePlugin
import com.glasshole.phone.plugin.PluginSender
import com.glasshole.phone.plugins.chat.TwitchChatClient
import com.glasshole.phone.plugins.chat.YouTubeChatClient
import com.glasshole.sdk.PluginMessage
import org.json.JSONObject

/**
 * Phone side of the RTMP broadcaster. All configuration — including
 * any chat overlay settings — lives in this plugin's own prefs; nothing
 * is shared with the Chat plugin, so the two can ship independently.
 *
 * When the display mode is "chat", we spin up our own Twitch/YouTube
 * client (the classes are reused from the chat package — that's just
 * code, not settings) and pipe messages into the CHAT channel toward
 * the broadcast glass plugin. The camera RTMP stream is untouched —
 * only the on-glass view changes to the chat feed.
 */
class BroadcastPlugin : PhonePlugin {

    companion object {
        private const val TAG = "BroadcastPlugin"
        const val PREFS_NAME = "broadcast_settings"

        // Streaming
        const val KEY_URL = "url"
        const val KEY_WIDTH = "width"
        const val KEY_HEIGHT = "height"
        const val KEY_FPS = "fps"
        const val KEY_BITRATE_KBPS = "bitrate_kbps"
        const val KEY_AUDIO = "audio"
        const val KEY_DISPLAY = "display"

        // Chat overlay (fully local — no dependency on the Chat plugin)
        const val KEY_CHAT_PLATFORM = "chat_platform"       // "twitch" | "youtube"
        const val KEY_CHAT_TWITCH_CHANNEL = "chat_twitch_channel"
        const val KEY_CHAT_YOUTUBE_API_KEY = "chat_youtube_api_key"
        const val KEY_CHAT_YOUTUBE_VIDEO = "chat_youtube_video"
        const val KEY_CHAT_FONT_SIZE = "chat_font_size"
        const val DEFAULT_CHAT_FONT_SIZE = 14
        const val KEY_CHAT_MAX_MESSAGES = "chat_max_messages"
        const val DEFAULT_CHAT_MAX_MESSAGES = 200
        const val CHAT_MAX_MESSAGES_CAP = 1000

        @Volatile
        var instance: BroadcastPlugin? = null
            private set
    }

    override val pluginId: String = "broadcast"

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
        stopChatOverlay()
        instance = null
    }

    override fun onGlassConnectionChanged(connected: Boolean) {
        if (!connected) stopChatOverlay()
    }

    override fun onMessageFromGlass(message: PluginMessage) {
        when (message.type) {
            "START" -> handleStart()
            "STOP" -> handleStop()
            else -> Log.d(TAG, "Unknown message: ${message.type}")
        }
    }

    private fun prefs() =
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun handleStart() {
        val p = prefs()
        val url = p.getString(KEY_URL, "")?.trim().orEmpty()
        if (url.isEmpty()) {
            AppLog.log(TAG, "Broadcast: RTMP URL not set in plugin settings")
            return
        }
        val display = p.getString(KEY_DISPLAY, "viewfinder") ?: "viewfinder"
        val json = JSONObject().apply {
            put("url", url)
            put("width", p.getInt(KEY_WIDTH, 1280))
            put("height", p.getInt(KEY_HEIGHT, 720))
            put("fps", p.getInt(KEY_FPS, 30))
            put("bitrate_kbps", p.getInt(KEY_BITRATE_KBPS, 1500))
            put("audio", p.getBoolean(KEY_AUDIO, true))
            put("display", display)
        }.toString()
        sender(PluginMessage("CONFIG", json))
        AppLog.log(TAG, "Broadcast: config pushed to glass (display=$display)")

        if (display == "chat") {
            startChatOverlay()
        } else {
            stopChatOverlay()
        }
    }

    private fun handleStop() {
        stopChatOverlay()
        Log.d(TAG, "Glass stopped broadcast")
    }

    private fun startChatOverlay() {
        stopChatOverlay()

        val p = prefs()
        val platform = p.getString(KEY_CHAT_PLATFORM, "twitch") ?: "twitch"

        when (platform) {
            "twitch" -> {
                val channel = p.getString(KEY_CHAT_TWITCH_CHANNEL, "")?.trim().orEmpty()
                if (channel.isEmpty()) {
                    sendStatus("Chat overlay: Twitch channel not set")
                    return
                }
                twitch = TwitchChatClient(
                    channel = channel,
                    onMessage = { u, t, c -> sendChat(u, t, c) },
                    onStatus = { sendStatus(it) }
                ).also { it.start() }
            }
            "youtube" -> {
                val apiKey = p.getString(KEY_CHAT_YOUTUBE_API_KEY, "")?.trim().orEmpty()
                val video = p.getString(KEY_CHAT_YOUTUBE_VIDEO, "")?.trim().orEmpty()
                if (apiKey.isEmpty() || video.isEmpty()) {
                    sendStatus("Chat overlay: YouTube API key / URL not set")
                    return
                }
                val videoId = YouTubeChatClient.Url.extractVideoId(video)
                if (videoId == null) {
                    sendStatus("Chat overlay: invalid YouTube URL")
                    return
                }
                youtube = YouTubeChatClient(
                    apiKey = apiKey,
                    videoId = videoId,
                    onMessage = { u, t, c -> sendChat(u, t, c) },
                    onStatus = { sendStatus(it) }
                ).also { it.start() }
            }
        }
    }

    private fun stopChatOverlay() {
        twitch?.stop(); twitch = null
        youtube?.stop(); youtube = null
    }

    private fun sendChat(user: String, text: String, color: String) {
        val p = prefs()
        val fontSize = p.getInt(KEY_CHAT_FONT_SIZE, DEFAULT_CHAT_FONT_SIZE)
        val maxMsgs = p.getInt(KEY_CHAT_MAX_MESSAGES, DEFAULT_CHAT_MAX_MESSAGES)
        val payload = JSONObject().apply {
            put("user", user)
            put("text", text)
            if (color.isNotEmpty()) put("color", color)
            put("size", fontSize)
            put("maxMsgs", maxMsgs)
        }.toString()
        sender(PluginMessage("CHAT", payload))
    }

    private fun sendStatus(text: String) {
        val payload = JSONObject().apply { put("text", text) }.toString()
        sender(PluginMessage("CHAT_STATUS", payload))
    }
}
