package com.glasshole.streamplayer.xe

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

enum class StreamPlatform {
    TWITCH, YOUTUBE, VIMEO, DAILYMOTION, REDDIT,
    DIRECT_HLS, DIRECT_VIDEO,
    WEBVIEW_FALLBACK
}

/** Resolved URL + whether it's HLS (vs progressive MP4/WEBM). */
data class ResolvedStream(val url: String, val isHls: Boolean)

object StreamResolver {

    // Conscrypt is installed in App.onCreate() so TLS 1.2+ just works.
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val JSON_MEDIA_TYPE = MediaType.parse("application/json")

    private const val GQL_URL = "https://gql.twitch.tv/gql"
    private const val CLIENT_ID = "kimne78kx3ncx6brgo4mv6wki5h1ko"

    private val DIRECT_VIDEO_EXTS = listOf(".mp4", ".webm", ".mkv", ".ts", ".mov", ".m4v")

    fun identify(url: String): StreamPlatform {
        val trimmed = url.trim()
        val lower = trimmed.lowercase()
        return when {
            lower.contains(".m3u8") -> StreamPlatform.DIRECT_HLS
            DIRECT_VIDEO_EXTS.any { lower.contains(it) } -> StreamPlatform.DIRECT_VIDEO
            extractTwitchChannel(trimmed) != null -> StreamPlatform.TWITCH
            extractYouTubeVideoId(trimmed) != null -> StreamPlatform.YOUTUBE
            extractVimeoId(trimmed) != null -> StreamPlatform.VIMEO
            extractDailymotionId(trimmed) != null -> StreamPlatform.DAILYMOTION
            isRedditUrl(trimmed) -> StreamPlatform.REDDIT
            else -> StreamPlatform.WEBVIEW_FALLBACK
        }
    }

    fun extractTwitchChannel(url: String): String? {
        val pattern = Regex("""(?:https?://)?(?:www\.|m\.)?twitch\.tv/([a-zA-Z0-9_]+)""")
        return pattern.find(url.trim())?.groupValues?.get(1)?.lowercase()
    }

    fun extractYouTubeVideoId(url: String): String? {
        val patterns = listOf(
            Regex("""(?:https?://)?(?:www\.)?youtube\.com/watch\?v=([a-zA-Z0-9_-]+)"""),
            Regex("""(?:https?://)?youtu\.be/([a-zA-Z0-9_-]+)"""),
            Regex("""(?:https?://)?(?:www\.)?youtube\.com/live/([a-zA-Z0-9_-]+)"""),
        )
        for (p in patterns) {
            val match = p.find(url.trim())
            if (match != null) return match.groupValues[1]
        }
        return null
    }

    fun extractVimeoId(url: String): String? {
        val pattern = Regex("""(?:https?://)?(?:www\.|player\.)?vimeo\.com/(?:video/)?(\d+)""")
        return pattern.find(url.trim())?.groupValues?.get(1)
    }

    fun extractDailymotionId(url: String): String? {
        val patterns = listOf(
            Regex("""(?:https?://)?(?:www\.)?dailymotion\.com/video/([a-zA-Z0-9]+)"""),
            Regex("""(?:https?://)?dai\.ly/([a-zA-Z0-9]+)"""),
        )
        for (p in patterns) {
            val match = p.find(url.trim())
            if (match != null) return match.groupValues[1]
        }
        return null
    }

    fun isRedditUrl(url: String): Boolean {
        val lower = url.trim().lowercase()
        return lower.contains("v.redd.it/") ||
            lower.contains("reddit.com/r/") ||
            (lower.contains("redd.it/") && !lower.contains("v.redd.it/"))
    }

    fun displayName(url: String): String {
        return when (identify(url)) {
            StreamPlatform.TWITCH -> extractTwitchChannel(url) ?: "Twitch"
            StreamPlatform.YOUTUBE -> "YouTube"
            StreamPlatform.VIMEO -> "Vimeo"
            StreamPlatform.DAILYMOTION -> "Dailymotion"
            StreamPlatform.REDDIT -> "Reddit"
            StreamPlatform.DIRECT_HLS -> "HLS Stream"
            StreamPlatform.DIRECT_VIDEO -> "Video"
            StreamPlatform.WEBVIEW_FALLBACK -> "Web"
        }
    }

    /**
     * Non-YouTube resolution. YouTube on XE still goes through the dedicated
     * WebView-based YouTubePlayerActivity (MainActivity routes it directly),
     * so it isn't handled here.
     */
    suspend fun resolve(url: String): Result<ResolvedStream> {
        val trimmed = url.trim()
        return when (identify(trimmed)) {
            StreamPlatform.DIRECT_HLS -> Result.success(ResolvedStream(trimmed, isHls = true))
            StreamPlatform.DIRECT_VIDEO -> Result.success(ResolvedStream(trimmed, isHls = false))
            StreamPlatform.TWITCH -> {
                val channel = extractTwitchChannel(trimmed)!!
                resolveTwitch(channel).map { ResolvedStream(it, isHls = true) }
            }
            StreamPlatform.YOUTUBE -> Result.failure(Exception("YouTube uses WebView player"))
            StreamPlatform.VIMEO -> resolveVimeo(extractVimeoId(trimmed)!!)
            StreamPlatform.DAILYMOTION -> resolveDailymotion(extractDailymotionId(trimmed)!!)
            StreamPlatform.REDDIT -> resolveReddit(trimmed)
            StreamPlatform.WEBVIEW_FALLBACK -> Result.failure(Exception("Needs WebView fallback"))
        }
    }

    // --- Twitch ---

    private suspend fun resolveTwitch(channel: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val token = getTwitchAccessToken(channel)
                ?: return@withContext Result.failure(Exception("Channel '$channel' is offline or doesn't exist"))

            val usherUrl = "https://usher.ttvnw.net/api/channel/hls/$channel.m3u8" +
                "?allow_source=true" +
                "&allow_audio_only=true" +
                "&fast_bread=true" +
                "&p=${(Math.random() * 999999).toInt()}" +
                "&player_backend=mediaplayer" +
                "&sig=${token.second}" +
                "&token=${java.net.URLEncoder.encode(token.first, "UTF-8")}"

            Result.success(usherUrl)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun getTwitchAccessToken(channel: String): Pair<String, String>? {
        val query = """
            query PlaybackAccessToken_Template(
                ${'$'}login: String!,
                ${'$'}playerType: String!,
                ${'$'}platform: String!
            ) {
                streamPlaybackAccessToken(
                    channelName: ${'$'}login,
                    params: {
                        platform: ${'$'}platform,
                        playerBackend: "mediaplayer",
                        playerType: ${'$'}playerType
                    }
                ) {
                    value
                    signature
                    __typename
                }
            }
        """.trimIndent()

        val gqlBody = JSONObject().apply {
            put("operationName", "PlaybackAccessToken_Template")
            put("query", query)
            put("variables", JSONObject().apply {
                put("login", channel)
                put("playerType", "site")
                put("platform", "web")
            })
        }

        val requestBody = RequestBody.create(JSON_MEDIA_TYPE, gqlBody.toString())

        val request = Request.Builder()
            .url(GQL_URL)
            .post(requestBody)
            .header("Client-ID", CLIENT_ID)
            .build()

        val response = client.newCall(request).execute()
        if (!response.isSuccessful) return null

        val body = response.body()?.string() ?: return null
        val json = JSONObject(body)
        val tokenData = json
            .optJSONObject("data")
            ?.optJSONObject("streamPlaybackAccessToken")
            ?: return null

        val token = tokenData.getString("value")
        val sig = tokenData.getString("signature")
        return Pair(token, sig)
    }

    // --- Vimeo ---

    private suspend fun resolveVimeo(id: String): Result<ResolvedStream> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("https://player.vimeo.com/video/$id/config")
                .header("Referer", "https://vimeo.com/")
                .build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                return@withContext Result.failure(Exception("Vimeo config HTTP ${response.code()}"))
            }
            val body = response.body()?.string()
                ?: return@withContext Result.failure(Exception("Empty Vimeo config"))
            val json = JSONObject(body)
            val files = json.optJSONObject("request")?.optJSONObject("files")
                ?: return@withContext Result.failure(Exception("No files in Vimeo config"))

            files.optJSONObject("hls")?.optJSONObject("cdns")?.let { cdns ->
                val keys = cdns.keys()
                if (keys.hasNext()) {
                    val cdn = cdns.getJSONObject(keys.next())
                    val hls = cdn.optString("url", "")
                    if (hls.isNotEmpty()) {
                        return@withContext Result.success(ResolvedStream(hls, isHls = true))
                    }
                }
            }
            val progressive = files.optJSONArray("progressive")
            if (progressive != null && progressive.length() > 0) {
                var bestUrl = ""
                var bestHeight = -1
                for (i in 0 until progressive.length()) {
                    val entry = progressive.getJSONObject(i)
                    val h = entry.optInt("height", 0)
                    if (h > bestHeight) {
                        bestHeight = h
                        bestUrl = entry.optString("url", "")
                    }
                }
                if (bestUrl.isNotEmpty()) {
                    return@withContext Result.success(ResolvedStream(bestUrl, isHls = false))
                }
            }
            Result.failure(Exception("No Vimeo streams found"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // --- Dailymotion ---

    private suspend fun resolveDailymotion(id: String): Result<ResolvedStream> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("https://www.dailymotion.com/player/metadata/video/$id")
                .header("User-Agent", "Mozilla/5.0")
                .build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                return@withContext Result.failure(Exception("Dailymotion metadata HTTP ${response.code()}"))
            }
            val body = response.body()?.string()
                ?: return@withContext Result.failure(Exception("Empty Dailymotion response"))
            val json = JSONObject(body)
            val error = json.optJSONObject("error")
            if (error != null) {
                return@withContext Result.failure(
                    Exception("Dailymotion: ${error.optString("title", "error")}")
                )
            }
            val qualities = json.optJSONObject("qualities")
                ?: return@withContext Result.failure(Exception("No qualities in Dailymotion response"))
            val auto = qualities.optJSONArray("auto")
            if (auto != null && auto.length() > 0) {
                val first = auto.getJSONObject(0)
                val hls = first.optString("url", "")
                if (hls.isNotEmpty()) {
                    return@withContext Result.success(ResolvedStream(hls, isHls = true))
                }
            }
            Result.failure(Exception("No Dailymotion HLS URL"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // --- Reddit ---

    private suspend fun resolveReddit(url: String): Result<ResolvedStream> = withContext(Dispatchers.IO) {
        try {
            val lower = url.lowercase()

            val vReddIt = Regex("""https?://v\.redd\.it/([a-zA-Z0-9]+)""").find(lower)
            if (vReddIt != null) {
                val id = vReddIt.groupValues[1]
                val hls = "https://v.redd.it/$id/HLSPlaylist.m3u8"
                return@withContext Result.success(ResolvedStream(hls, isHls = true))
            }

            val jsonUrl = if (url.endsWith(".json")) url else "$url.json"
            val request = Request.Builder()
                .url(jsonUrl)
                .header("User-Agent", "GlassHoleStreamPlayer/0.3 (https://github.com/benharlett/glasshole)")
                .build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                return@withContext Result.failure(Exception("Reddit API HTTP ${response.code()}"))
            }
            val body = response.body()?.string()
                ?: return@withContext Result.failure(Exception("Empty Reddit response"))
            val firstObj = if (body.trim().startsWith("[")) {
                org.json.JSONArray(body).getJSONObject(0)
            } else {
                JSONObject(body)
            }
            val data = firstObj.optJSONObject("data")
                ?.optJSONArray("children")
                ?.optJSONObject(0)
                ?.optJSONObject("data")
                ?: return@withContext Result.failure(Exception("Reddit post has no data"))

            val video = data.optJSONObject("media")?.optJSONObject("reddit_video")
                ?: data.optJSONObject("secure_media")?.optJSONObject("reddit_video")
            if (video != null) {
                val hls = video.optString("hls_url", "")
                if (hls.isNotEmpty()) {
                    return@withContext Result.success(ResolvedStream(hls, isHls = true))
                }
                val fallback = video.optString("fallback_url", "")
                if (fallback.isNotEmpty()) {
                    return@withContext Result.success(ResolvedStream(fallback, isHls = false))
                }
            }
            val externalUrl = data.optString("url_overridden_by_dest", "").takeIf { it.isNotEmpty() }
                ?: data.optString("url", "")
            if (externalUrl.isNotEmpty() && externalUrl != url) {
                return@withContext resolve(externalUrl)
            }
            Result.failure(Exception("No video on this Reddit post"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
