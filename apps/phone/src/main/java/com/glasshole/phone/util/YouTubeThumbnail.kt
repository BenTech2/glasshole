package com.glasshole.phone.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Pulls a JPEG thumbnail for a YouTube video off the i.ytimg.com CDN
 * and caches it on disk. Used by both the notification listener (when
 * a Shorts notif arrives without an `EXTRA_PICTURE`) and the Debug
 * replay path (when re-firing a captured Shorts notification that
 * was stored before the listener path knew about this fallback).
 *
 * The base64 output is intentionally identical in shape to what
 * `NotificationForwardingService.encodeScaledBitmapToBase64` produces,
 * so the glass card renders the same regardless of which path
 * supplied it.
 */
object YouTubeThumbnail {
    private const val TAG = "YtThumbnail"

    /** Pull the 11-character video id out of any common YouTube URL
     *  shape (watch?v=, /shorts/, /embed/, /live/, youtu.be/). */
    fun extractVideoId(text: String): String? {
        Regex("[?&]v=([A-Za-z0-9_-]{6,})").find(text)?.let { return it.groupValues[1] }
        Regex("/(shorts|embed|live)/([A-Za-z0-9_-]{6,})").find(text)?.let { return it.groupValues[2] }
        Regex("youtu\\.be/([A-Za-z0-9_-]{6,})").find(text)?.let { return it.groupValues[1] }
        return null
    }

    /**
     * Hunt for a YouTube video id across every plausible spot in a
     * notification.
     *
     * Picks the FIRST id found in this order:
     *   1. notification tag — YouTube embeds the 11-char video id as
     *      the prefix of the tag before "::" (e.g.
     *      "cXDywY8dh50::bd1c3abe-..."). Most reliable signal we have.
     *   2. visible text (title/text/big-text/sub-text/text-lines)
     *   3. any extras bundle value
     *   4. contentIntent → underlying intent → data uri / extras
     *      (reflection — works on most Android versions but blocked
     *      by the hidden-API blocklist on some recent Pixel builds)
     *
     * Returns null if the notification doesn't reference a YouTube
     * video at all (e.g. comment notifications).
     */
    fun findVideoIdInNotification(
        notification: android.app.Notification,
        extras: android.os.Bundle,
        title: CharSequence?,
        text: CharSequence?,
        tag: String?
    ): String? {
        // 1. tag prefix
        if (tag != null) extractVideoIdFromTag(tag)?.let { return it }

        // 2. visible text
        val visible = buildString {
            title?.let { append(it); append('\n') }
            text?.let { append(it); append('\n') }
            (extras.getCharSequence(android.app.Notification.EXTRA_BIG_TEXT)
                ?: extras.getCharSequence(android.app.Notification.EXTRA_SUB_TEXT)
                ?: extras.getCharSequence(android.app.Notification.EXTRA_INFO_TEXT))
                ?.let { append(it); append('\n') }
            extras.getCharSequenceArray(android.app.Notification.EXTRA_TEXT_LINES)?.forEach {
                append(it); append('\n')
            }
        }
        extractVideoId(visible)?.let { return it }

        // 3. any extras value
        scanBundleForVideoId(extras)?.let { return it }

        // 4. contentIntent reflection
        notification.contentIntent?.let { pi ->
            extractIdFromPendingIntent(pi)?.let { return it }
        }
        return null
    }

    /** YouTube notification tags are formatted `<videoId>::<uuid>` —
     *  match an 11-char id prefix terminated by "::" or end-of-string.
     *  The literal SUMMARY tag for a channel-group summary
     *  ("1416813651::SUMMARY::...") has a digits-only prefix so it
     *  doesn't match a real video id (which has at least one
     *  letter/`_`/`-`); we still let the regex match digits-only
     *  prefixes — the sufficient discriminator is whether
     *  `i.ytimg.com/vi/<id>/...` returns an actual JPEG. */
    fun extractVideoIdFromTag(tag: String): String? {
        return Regex("^([A-Za-z0-9_-]{11})(?:::|$|/)").find(tag)?.groupValues?.get(1)
    }

    private fun scanBundleForVideoId(bundle: android.os.Bundle): String? {
        for (key in bundle.keySet()) {
            val v = try { bundle.get(key) } catch (_: Exception) { null } ?: continue
            when (v) {
                is CharSequence -> extractVideoId(v.toString())?.let { return it }
                is String -> extractVideoId(v)?.let { return it }
                is android.os.Bundle -> scanBundleForVideoId(v)?.let { return it }
                is Array<*> -> for (item in v) {
                    if (item is CharSequence) extractVideoId(item.toString())?.let { return it }
                }
            }
        }
        return null
    }

    /**
     * `PendingIntent.getIntent()` is `@hide` but the underlying field
     * has been "key" on every API since 1; reflection has been the
     * stable workaround for years. If hidden-API restrictions block
     * it on a newer Android we just fail open.
     */
    private fun extractIdFromPendingIntent(pi: android.app.PendingIntent): String? {
        return try {
            val getIntentMethod = android.app.PendingIntent::class.java
                .getDeclaredMethod("getIntent")
                .apply { isAccessible = true }
            val intent = getIntentMethod.invoke(pi) as? android.content.Intent ?: return null
            // Data URI is the most common spot.
            intent.data?.toString()?.let { extractVideoId(it)?.let { id -> return id } }
            // Some apps stash the URL in an extra.
            intent.extras?.let { return scanBundleForVideoId(it) }
            null
        } catch (e: Exception) {
            Log.d(TAG, "PendingIntent reflection unavailable: ${e.message}")
            null
        }
    }

    private fun cacheFile(context: Context, videoId: String): File =
        File(File(context.cacheDir, "yt-thumbs"), "$videoId.jpg")

    /** Cache-only sync read — never touches the network. Returns the
     *  scaled base64 JPEG or null if not yet cached. Safe to call
     *  from the NLS callback. */
    fun getCachedEncodedPicture(context: Context, videoId: String): String? {
        val bytes = getCachedJpegBytes(context, videoId) ?: return null
        return encodeScaled(bytes)
    }

    fun getCachedJpegBytes(context: Context, videoId: String): ByteArray? {
        val cached = cacheFile(context, videoId)
        return if (cached.exists() && cached.length() > 0L) cached.readBytes() else null
    }

    /** Synchronous fetch + cache + encode — does network I/O. Callers
     *  must be on a worker thread. Returns null if no thumbnail is
     *  available for this video. */
    fun fetchAndEncodePicture(context: Context, videoId: String): String? {
        val bytes = fetchAndCache(context, videoId) ?: return null
        return encodeScaled(bytes)
    }

    /** Hits the YouTube CDN, writes to cache on success, and returns
     *  the JPEG bytes. Worker-thread only. */
    fun fetchAndCache(context: Context, videoId: String): ByteArray? {
        getCachedJpegBytes(context, videoId)?.let { return it }
        val downloaded = download(videoId) ?: return null
        try {
            val cached = cacheFile(context, videoId)
            cached.parentFile?.mkdirs()
            cached.writeBytes(downloaded)
        } catch (e: Exception) {
            Log.w(TAG, "cache write failed for $videoId: ${e.message}")
        }
        return downloaded
    }

    private fun download(videoId: String): ByteArray? {
        // Try maxres first; YouTube returns a tiny "no image" placeholder
        // when a resolution doesn't exist, so we filter by min size.
        val candidates = listOf(
            "https://i.ytimg.com/vi/$videoId/maxresdefault.jpg",
            "https://i.ytimg.com/vi/$videoId/hqdefault.jpg",
            "https://i.ytimg.com/vi/$videoId/mqdefault.jpg"
        )
        for (urlStr in candidates) {
            try {
                val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 4_000
                    readTimeout = 8_000
                    instanceFollowRedirects = true
                    setRequestProperty(
                        "User-Agent",
                        "GlassHole-Notif/1.0 (https://github.com/glasshole)"
                    )
                }
                try {
                    if (conn.responseCode == 200) {
                        val bytes = conn.inputStream.use { it.readBytes() }
                        // YouTube's "no thumbnail" filler is ~120 bytes.
                        if (bytes.size > 200) return bytes
                    }
                } finally { conn.disconnect() }
            } catch (_: Exception) { /* try next */ }
        }
        return null
    }

    private fun encodeScaled(jpegBytes: ByteArray): String? {
        return try {
            val bmp: Bitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
                ?: return null
            val maxEdge = 200
            val scale = maxEdge.toFloat() / maxOf(bmp.width, bmp.height).toFloat()
            val scaled = if (scale < 1f) {
                val w = (bmp.width * scale).toInt().coerceAtLeast(1)
                val h = (bmp.height * scale).toInt().coerceAtLeast(1)
                Bitmap.createScaledBitmap(bmp, w, h, true)
            } else bmp
            val stream = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 55, stream)
            if (scaled !== bmp) scaled.recycle()
            bmp.recycle()
            Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.w(TAG, "encode failed: ${e.message}")
            null
        }
    }
}
