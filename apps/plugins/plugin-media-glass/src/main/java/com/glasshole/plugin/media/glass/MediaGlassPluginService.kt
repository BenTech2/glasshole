package com.glasshole.plugin.media.glass

import android.content.Intent
import android.util.Log
import com.glasshole.glass.sdk.GlassPluginMessage
import com.glasshole.glass.sdk.GlassPluginService

/**
 * Media plugin glass-side service. Phone's MediaPlugin watches the
 * active MediaSession (Spotify, YouTube Music, Pocket Casts, etc.)
 * and pushes NOW_PLAYING whenever the metadata / playback state
 * changes. We just forward to the visible MediaActivity via a local
 * broadcast so it can redraw.
 *
 * Control commands flow back through the existing plugin bridge —
 * MediaActivity sends TOGGLE / NEXT / PREV as MESSAGE_TO_PHONE
 * intents and the phone plugin invokes MediaController.transportControls.
 */
class MediaGlassPluginService : GlassPluginService() {

    companion object {
        private const val TAG = "MediaGlassPlugin"
        const val ACTION_NOW_PLAYING = "com.glasshole.plugin.media.glass.NOW_PLAYING"
        const val EXTRA_TITLE = "title"
        const val EXTRA_ARTIST = "artist"
        const val EXTRA_ALBUM = "album"
        const val EXTRA_APP_NAME = "app_name"
        const val EXTRA_PLAYING = "playing"
        const val EXTRA_HAS_SESSION = "has_session"
        const val EXTRA_ART_B64 = "art_b64"
        const val EXTRA_POSITION_MS = "position_ms"
        const val EXTRA_DURATION_MS = "duration_ms"
    }

    override val pluginId: String = "media"

    override fun onMessageFromPhone(message: GlassPluginMessage) {
        Log.i(TAG, "onMessageFromPhone type=${message.type} payloadLen=${message.payload.length}")
        when (message.type) {
            "NOW_PLAYING" -> forwardNowPlaying(message.payload)
            else -> Log.d(TAG, "Unknown message: ${message.type}")
        }
    }

    private fun forwardNowPlaying(payload: String) {
        try {
            val json = org.json.JSONObject(payload)
            val artLen = json.optString("art_b64", "").length
            Log.i(TAG, "forwardNowPlaying title='${json.optString("title", "")}' artLen=$artLen")
            val intent = Intent(ACTION_NOW_PLAYING).apply {
                setPackage(packageName)
                putExtra(EXTRA_HAS_SESSION, json.optBoolean("has_session", true))
                putExtra(EXTRA_TITLE, json.optString("title", ""))
                putExtra(EXTRA_ARTIST, json.optString("artist", ""))
                putExtra(EXTRA_ALBUM, json.optString("album", ""))
                putExtra(EXTRA_APP_NAME, json.optString("app_name", ""))
                putExtra(EXTRA_PLAYING, json.optBoolean("playing", false))
                putExtra(EXTRA_ART_B64, json.optString("art_b64", ""))
                putExtra(EXTRA_POSITION_MS, json.optLong("position_ms", 0L))
                putExtra(EXTRA_DURATION_MS, json.optLong("duration_ms", 0L))
            }
            sendBroadcast(intent)
        } catch (e: Exception) {
            Log.w(TAG, "Bad NOW_PLAYING payload: ${e.message}")
        }
    }
}
