package com.glasshole.glassxe.home

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.util.Base64

/**
 * Snapshot of whatever's currently playing on the phone. Held by
 * HomeActivity so the Media card can redraw any time without a fresh
 * push from the phone. Updated on every NOW_PLAYING message.
 */
data class MediaState(
    val hasSession: Boolean = false,
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val appName: String = "",
    val playing: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    /** elapsedRealtime() at the moment [positionMs] was captured. */
    val positionCapturedAtRealtime: Long = 0L,
    val artBitmap: Bitmap? = null,
    /** Title|album signature used to detect track changes. */
    val trackKey: String = ""
) {
    companion object {
        val EMPTY = MediaState()

        fun fromJson(
            payload: String,
            previous: MediaState
        ): MediaState {
            val json = try { org.json.JSONObject(payload) } catch (_: Exception) {
                return previous
            }
            val hasSession = json.optBoolean("has_session", false)
            if (!hasSession) return EMPTY.copy(positionCapturedAtRealtime = SystemClock.elapsedRealtime())

            // Empty string fields can come through during the window where
            // the MediaSession exists but its metadata hasn't populated yet —
            // fall back to whatever we last had for the same session rather
            // than wiping good state.
            val title = json.optString("title", "").ifEmpty { previous.title }
            val album = json.optString("album", "").ifEmpty { previous.album }
            val artist = json.optString("artist", "").ifEmpty { previous.artist }
            val trackKey = "$title|$album"
            val artB64 = json.optString("art_b64", "")
            val bmp = if (artB64.isNotEmpty()) {
                try {
                    // JSONObject on some devices leaves literal backslashes
                    // from \/ escapes — strip defensively before decode.
                    val clean = artB64.replace("\\", "")
                    val bytes = Base64.decode(clean, Base64.DEFAULT)
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: previous.artBitmap
                } catch (_: Exception) {
                    previous.artBitmap
                }
            } else if (trackKey == previous.trackKey) {
                // Same track, no new art in the payload — keep the cached art
                // so follow-up state pushes don't wipe the cover.
                previous.artBitmap
            } else {
                null
            }

            return MediaState(
                hasSession = true,
                title = title,
                artist = artist,
                album = album,
                appName = json.optString("app_name", ""),
                playing = json.optBoolean("playing", false),
                positionMs = json.optLong("position_ms", 0L),
                durationMs = json.optLong("duration_ms", 0L),
                positionCapturedAtRealtime = SystemClock.elapsedRealtime(),
                artBitmap = bmp,
                trackKey = trackKey
            )
        }
    }
}
