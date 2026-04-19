package com.glasshole.phone.plugins.chat

import android.net.Uri
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Polls YouTube's Data API v3 liveChatMessages endpoint. The user must
 * supply their own API key — the free tier gives 10k units/day which is
 * plenty for viewing a single stream. We do the videos.list → liveChatId
 * resolution once on start, then poll liveChatMessages.list respecting
 * the server-supplied pollingIntervalMillis.
 */
class YouTubeChatClient(
    private val apiKey: String,
    private val videoId: String,
    private val onMessage: (user: String, text: String, color: String) -> Unit,
    private val onStatus: (String) -> Unit
) {

    companion object {
        private const val TAG = "YouTubeChatClient"
        private const val BASE = "https://www.googleapis.com/youtube/v3"
        private const val MIN_POLL_MS = 2_000L
        private const val FALLBACK_POLL_MS = 5_000L
    }

    @Volatile private var running = false
    private var thread: Thread? = null

    fun start() {
        if (running) return
        running = true
        thread = Thread({ run() }, "YouTubeChatClient").also { it.start() }
    }

    fun stop() {
        running = false
        try { thread?.interrupt() } catch (_: Exception) {}
        thread = null
    }

    private fun run() {
        onStatus("Resolving live chat for video $videoId…")

        val liveChatId = try {
            resolveLiveChatId()
        } catch (e: Exception) {
            Log.w(TAG, "Live chat ID resolve failed: ${e.message}")
            onStatus("YouTube: ${e.message ?: "resolve failed"}")
            return
        }
        if (liveChatId.isNullOrEmpty()) {
            onStatus("YouTube: video isn't a live stream with chat")
            return
        }

        onStatus("Connected to YouTube live chat")
        var pageToken: String? = null
        // The first page returns the backlog — skip it so we don't dump a
        // wall of historical messages at the viewer. Start from the latest.
        var firstPage = true

        while (running) {
            try {
                val url = buildString {
                    append("$BASE/liveChat/messages?part=snippet,authorDetails")
                    append("&liveChatId=").append(URLEncoder.encode(liveChatId, "UTF-8"))
                    append("&key=").append(URLEncoder.encode(apiKey, "UTF-8"))
                    if (!pageToken.isNullOrEmpty()) {
                        append("&pageToken=").append(URLEncoder.encode(pageToken, "UTF-8"))
                    }
                }
                val json = fetch(url)
                if (json == null) {
                    safeSleep(FALLBACK_POLL_MS)
                    continue
                }

                if (!firstPage) {
                    val items = json.optJSONArray("items") ?: org.json.JSONArray()
                    for (i in 0 until items.length()) {
                        val item = items.getJSONObject(i)
                        val snippet = item.optJSONObject("snippet") ?: continue
                        val author = item.optJSONObject("authorDetails") ?: continue
                        val text = snippet.optJSONObject("textMessageDetails")
                            ?.optString("messageText")
                            ?: snippet.optString("displayMessage", "")
                        if (text.isBlank()) continue
                        val name = author.optString("displayName", "viewer")
                        // YouTube doesn't expose user colours via the API,
                        // so we pick a stable pseudo-colour from the channel
                        // ID so the same user is the same colour each time.
                        val channelId = author.optString("channelId", name)
                        val color = stableColor(channelId)
                        onMessage(name, text, color)
                    }
                }

                pageToken = json.optString("nextPageToken")
                val poll = json.optLong("pollingIntervalMillis", FALLBACK_POLL_MS)
                    .coerceAtLeast(MIN_POLL_MS)
                firstPage = false
                safeSleep(poll)
            } catch (e: Exception) {
                if (running) {
                    Log.w(TAG, "Poll error: ${e.message}")
                    onStatus("YouTube poll error — retrying")
                    safeSleep(FALLBACK_POLL_MS)
                }
            }
        }
    }

    private fun resolveLiveChatId(): String? {
        val url = "$BASE/videos?part=liveStreamingDetails" +
            "&id=" + URLEncoder.encode(videoId, "UTF-8") +
            "&key=" + URLEncoder.encode(apiKey, "UTF-8")
        val json = fetch(url) ?: throw Exception("videos.list returned nothing")
        val items = json.optJSONArray("items")
        if (items == null || items.length() == 0) {
            throw Exception("video not found")
        }
        val details = items.getJSONObject(0).optJSONObject("liveStreamingDetails")
            ?: throw Exception("not a live video")
        return details.optString("activeLiveChatId").takeIf { it.isNotEmpty() }
    }

    private fun fetch(urlString: String): JSONObject? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(urlString).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10_000
                readTimeout = 15_000
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                val err = conn.errorStream?.bufferedReader()?.use { it.readText() }
                Log.w(TAG, "HTTP $code: $err")
                // 403 is usually quota exceeded or livechat has ended — stop
                // polling hard so we don't spam the endpoint forever.
                if (code == 403 || code == 404) {
                    running = false
                    onStatus("YouTube: chat ended or quota exceeded")
                }
                return null
            }
            val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            JSONObject(body)
        } catch (e: Exception) {
            Log.w(TAG, "Fetch error: ${e.message}")
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun safeSleep(ms: Long) {
        try { Thread.sleep(ms) } catch (_: InterruptedException) {}
    }

    private fun stableColor(key: String): String {
        val palette = arrayOf(
            "#4FC3F7", "#81C784", "#FFB74D", "#BA68C8", "#F06292",
            "#E57373", "#4DB6AC", "#FFD54F", "#9575CD", "#AED581"
        )
        val h = key.hashCode()
        val idx = ((h % palette.size) + palette.size) % palette.size
        return palette[idx]
    }

    /**
     * Accept either a full YouTube URL or a bare video ID. Strip query
     * params etc. Returns null if we can't find something that looks
     * like an 11-char YouTube video ID.
     */
    object Url {
        private val VID_REGEX = Regex("[A-Za-z0-9_-]{11}")

        fun extractVideoId(input: String): String? {
            val trimmed = input.trim()
            if (trimmed.isEmpty()) return null
            // Bare ID
            if (VID_REGEX.matchEntire(trimmed) != null) return trimmed
            val uri = try { Uri.parse(trimmed) } catch (_: Exception) { return null }
            uri.getQueryParameter("v")?.let { if (VID_REGEX.matchEntire(it) != null) return it }
            val path = uri.lastPathSegment ?: return null
            if (VID_REGEX.matchEntire(path) != null) return path
            return null
        }
    }
}
