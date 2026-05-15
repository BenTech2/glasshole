package com.glasshole.phone.plugins.stream

import android.content.Context
import android.util.Log
import com.glasshole.phone.AppLog
import com.glasshole.phone.plugin.PhonePlugin
import com.glasshole.phone.plugin.PluginSender
import com.glasshole.sdk.PluginMessage
import org.json.JSONObject

class StreamPlugin : PhonePlugin {

    companion object {
        private const val TAG = "StreamPlugin"

        @Volatile
        var instance: StreamPlugin? = null
            private set
    }

    override val pluginId: String = "stream"

    private lateinit var sender: PluginSender

    /** Callback for the main screen's Now Playing card. Set null when the
     *  card is detached so we don't leak the activity. */
    @Volatile var onPlaybackState: ((PlaybackState?) -> Unit)? = null

    @Volatile var lastState: PlaybackState? = null
        private set

    override fun onCreate(context: Context, sender: PluginSender) {
        this.sender = sender
        instance = this
    }

    override fun onDestroy() {
        instance = null
    }

    override fun onMessageFromGlass(message: PluginMessage) {
        when (message.type) {
            "PLAYBACK_STATE" -> {
                val state = try { PlaybackState.fromJson(message.payload) } catch (e: Exception) {
                    Log.w(TAG, "PLAYBACK_STATE parse failed: ${e.message}"); null
                } ?: return
                lastState = state
                onPlaybackState?.invoke(state)
            }
            "PLAYBACK_END" -> {
                lastState = null
                onPlaybackState?.invoke(null)
            }
            else -> Log.d(TAG, "Ignored message from glass: type=${message.type}")
        }
    }

    fun sendUrl(url: String): Boolean {
        // YouTube's share sheet attaches a `t=` query param when the user
        // shares "from current time" — extract and pass through to glass
        // as a separate startMs so the player can seekTo() it on load.
        val startMs = extractYouTubeStartMs(url) ?: 0L
        val payload = JSONObject().apply {
            put("url", url)
            if (startMs > 0L) put("startMs", startMs)
        }.toString()
        Log.i(TAG, "Forwarding URL to glass: $url (startMs=$startMs)")
        val ok = sender(PluginMessage("PLAY_URL", payload))
        val suffix = if (startMs > 0L) " (start=${startMs}ms)" else ""
        AppLog.log("Stream", "PLAY_URL → glass: ${if (ok) "sent" else "DROPPED"} — $url$suffix")
        return ok
    }

    /** Returns the `t=` parameter of a YouTube share URL converted to
     *  milliseconds, or null if the URL isn't YouTube or has no `t`.
     *  Accepts plain seconds (`120`, `120s`) and compound h/m/s
     *  (`1h2m30s`, `2m0s`). */
    private fun extractYouTubeStartMs(url: String): Long? {
        if (!url.contains("youtube.com") && !url.contains("youtu.be")) return null
        val t = try {
            android.net.Uri.parse(url).getQueryParameter("t")
        } catch (_: Exception) {
            null
        } ?: return null
        val seconds = parseTimestampSeconds(t) ?: return null
        return if (seconds > 0) seconds * 1000L else null
    }

    private fun parseTimestampSeconds(s: String): Long? {
        // Plain integer (with optional `s` suffix): "120" / "120s".
        s.trimEnd('s').toLongOrNull()?.let { return it }
        // Compound "1h2m30s" / "2m0s" / "30s".
        val m = Regex("^(?:(\\d+)h)?(?:(\\d+)m)?(?:(\\d+)s?)?$").matchEntire(s) ?: return null
        val h = m.groupValues[1].toLongOrNull() ?: 0L
        val mn = m.groupValues[2].toLongOrNull() ?: 0L
        val sec = m.groupValues[3].toLongOrNull() ?: 0L
        val total = h * 3600L + mn * 60L + sec
        return if (total > 0L) total else null
    }

    fun play(): Boolean = sender(PluginMessage("PLAY", ""))
    fun pause(): Boolean = sender(PluginMessage("PAUSE", ""))
    fun next(): Boolean = sender(PluginMessage("NEXT", ""))
    fun prev(): Boolean = sender(PluginMessage("PREV", ""))
    fun stop(): Boolean = sender(PluginMessage("STOP", ""))

    fun seekTo(positionMs: Long): Boolean {
        val payload = JSONObject().apply { put("positionMs", positionMs) }.toString()
        return sender(PluginMessage("SEEK", payload))
    }

    fun seekRelative(deltaMs: Long): Boolean {
        val payload = JSONObject().apply { put("deltaMs", deltaMs) }.toString()
        return sender(PluginMessage("SEEK_RELATIVE", payload))
    }
}

/**
 * Decoded mirror of the JSON pushed by glass-side PlayerActivity.
 * Updated every ~500ms while the player is in the foreground; also
 * dispatched on every command we send so the phone UI is responsive
 * even before the next tick lands.
 */
data class PlaybackState(
    val isPlaying: Boolean,
    val isLive: Boolean,
    val canSeek: Boolean,
    val positionMs: Long,
    val durationMs: Long,
    val title: String,
    val artworkUrl: String,
    val queueSize: Int,
    val cursor: Int
) {
    val hasQueue: Boolean get() = queueSize > 1

    companion object {
        fun fromJson(json: String): PlaybackState {
            val obj = JSONObject(json)
            return PlaybackState(
                isPlaying = obj.optBoolean("isPlaying", false),
                isLive = obj.optBoolean("isLive", false),
                canSeek = obj.optBoolean("canSeek", false),
                positionMs = obj.optLong("positionMs", 0L),
                durationMs = obj.optLong("durationMs", 0L),
                title = obj.optString("title", ""),
                artworkUrl = obj.optString("artworkUrl", ""),
                queueSize = obj.optInt("queueSize", 0),
                cursor = obj.optInt("cursor", 0)
            )
        }
    }
}
