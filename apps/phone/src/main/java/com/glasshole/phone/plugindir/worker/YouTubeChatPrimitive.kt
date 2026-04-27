package com.glasshole.phone.plugindir.worker

import android.content.Context
import android.net.Uri
import android.util.Log
import com.glasshole.phone.AppLog
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Polls YouTube Data API v3 liveChatMessages and emits each new chat
 * line to the glass plugin. The user supplies their own API key — free
 * tier quota is plenty for a single stream. Initial page of historical
 * messages is skipped so the glass viewer doesn't get a wall of backlog.
 *
 * Expected params:
 * ```
 * {
 *   "start_trigger": "START",
 *   "stop_trigger":  "STOP",
 *   "api_key":       "AIza…",
 *   "video":         "<full URL or 11-char video id>",
 *   "emit_type":     "CHAT",
 *   "status_type":   "STATUS"
 * }
 * ```
 */
class YouTubeChatPrimitive : WorkerPrimitive {

    companion object {
        private const val TAG = "YTChatPrim"
        private const val BASE = "https://www.googleapis.com/youtube/v3"
        private const val MIN_POLL_MS = 2_000L
        private const val FALLBACK_POLL_MS = 5_000L
        private val VID_REGEX = Regex("[A-Za-z0-9_-]{11}")
    }

    private var startTrigger: String = "START"
    private var stopTrigger: String = "STOP"
    private var apiKey: String = ""
    private var videoInput: String = ""
    private var emitType: String = "CHAT"
    private var statusType: String = "STATUS"

    private var emit: ((type: String, payload: String) -> Unit)? = null

    @Volatile private var running = false
    private var thread: Thread? = null

    override fun start(
        context: Context,
        params: JSONObject,
        emit: (type: String, payload: String) -> Unit
    ) {
        this.startTrigger = params.optString("start_trigger", "START")
        this.stopTrigger = params.optString("stop_trigger", "STOP")
        this.apiKey = params.optString("api_key").trim()
        this.videoInput = params.optString("video").trim()
        this.emitType = params.optString("emit_type", "CHAT")
        this.statusType = params.optString("status_type", "STATUS")
        this.emit = emit
        Log.i(TAG, "armed: video=$videoInput")
    }

    override fun onMessage(type: String, payload: String) {
        when (type) {
            startTrigger -> beginConnect()
            stopTrigger -> disconnect()
        }
    }

    override fun onConnectionChanged(connected: Boolean) {
        if (!connected) disconnect()
    }

    override fun stop() {
        disconnect()
        emit = null
    }

    private fun beginConnect() {
        if (apiKey.isEmpty() || videoInput.isEmpty()) {
            emitStatus("YouTube API key + live URL not set")
            AppLog.log(TAG, "settings incomplete")
            return
        }
        val videoId = extractVideoId(videoInput)
        if (videoId == null) {
            emitStatus("YouTube: invalid video URL")
            return
        }
        if (running) return
        running = true
        thread = Thread({ run(videoId) }, "YouTubeChatPrimitive").also { it.start() }
    }

    private fun disconnect() {
        running = false
        try { thread?.interrupt() } catch (_: Exception) {}
        thread = null
    }

    private fun run(videoId: String) {
        emitStatus("Resolving live chat for video $videoId…")

        val liveChatId = try {
            resolveLiveChatId(videoId)
        } catch (e: Exception) {
            Log.w(TAG, "Live chat ID resolve failed: ${e.message}")
            emitStatus("YouTube: ${e.message ?: "resolve failed"}")
            return
        }
        if (liveChatId.isNullOrEmpty()) {
            emitStatus("YouTube: video isn't a live stream with chat")
            return
        }

        emitStatus("Connected to YouTube live chat")
        var pageToken: String? = null
        // Skip the first page so we don't dump historical chat at the viewer.
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
                    val items = json.optJSONArray("items") ?: JSONArray()
                    for (i in 0 until items.length()) {
                        val item = items.getJSONObject(i)
                        val snippet = item.optJSONObject("snippet") ?: continue
                        val author = item.optJSONObject("authorDetails") ?: continue
                        val text = snippet.optJSONObject("textMessageDetails")
                            ?.optString("messageText")
                            ?: snippet.optString("displayMessage", "")
                        if (text.isBlank()) continue
                        val name = author.optString("displayName", "viewer")
                        // YouTube API doesn't expose user colour — pick a
                        // stable pseudo-colour from channel ID so the same
                        // user is the same colour every visit.
                        val channelId = author.optString("channelId", name)
                        val color = stableColor(channelId)
                        emitChat(name, text, color)
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
                    emitStatus("YouTube poll error — retrying")
                    safeSleep(FALLBACK_POLL_MS)
                }
            }
        }
    }

    private fun resolveLiveChatId(videoId: String): String? {
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
                // 403 is usually quota exceeded; 404 is chat ended. Stop
                // hard so we don't spam the endpoint forever.
                if (code == 403 || code == 404) {
                    running = false
                    emitStatus("YouTube: chat ended or quota exceeded")
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
     * Accept either a full YouTube URL or a bare 11-char video id.
     */
    private fun extractVideoId(input: String): String? {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return null
        if (VID_REGEX.matchEntire(trimmed) != null) return trimmed
        val uri = try { Uri.parse(trimmed) } catch (_: Exception) { return null }
        uri.getQueryParameter("v")?.let { if (VID_REGEX.matchEntire(it) != null) return it }
        val path = uri.lastPathSegment ?: return null
        if (VID_REGEX.matchEntire(path) != null) return path
        return null
    }

    private fun emitChat(user: String, text: String, color: String) {
        val json = JSONObject().apply {
            put("user", user)
            put("text", text)
            if (color.isNotEmpty()) put("color", color)
        }.toString()
        emit?.invoke(emitType, json)
    }

    private fun emitStatus(text: String) {
        val json = JSONObject().apply { put("text", text) }.toString()
        emit?.invoke(statusType, json)
    }
}
