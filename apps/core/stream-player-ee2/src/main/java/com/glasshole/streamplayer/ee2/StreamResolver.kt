package com.glasshole.streamplayer.ee2

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamType
import java.util.concurrent.TimeUnit

enum class StreamPlatform {
    TWITCH, YOUTUBE, YOUTUBE_PLAYLIST, VIMEO, DAILYMOTION, REDDIT,
    DIRECT_HLS, DIRECT_VIDEO,
    WEBVIEW_FALLBACK
}

/**
 * A resolved stream URL ready for ExoPlayer. `isAudioOnly` drives the
 * album-art overlay in PlayerActivity — YouTube Music tracks and other
 * audio-only streams get the thumbnail shown behind the player surface.
 */
data class ResolvedStream(
    val url: String,
    val isHls: Boolean,
    val title: String? = null,
    val artworkUrl: String? = null,
    val isAudioOnly: Boolean = false
)

/**
 * One entry in a queue of tracks to play in order. The source URL is a
 * YouTube (or YouTube Music) video URL that PlayerActivity resolves to
 * playable form lazily, right before it becomes current — this keeps a
 * 20-track playlist from blocking for 40s up front.
 */
data class QueueItem(
    val sourceUrl: String,
    val title: String? = null,
    val artworkUrl: String? = null
)

object StreamResolver {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    @Volatile private var newPipeInitialised = false

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
            // Playlist wins over single-video: a watch?v=X&list=PL... URL is
            // treated as a playlist so the whole list auto-queues.
            extractYouTubePlaylistId(trimmed) != null -> StreamPlatform.YOUTUBE_PLAYLIST
            extractYouTubeVideoId(trimmed) != null -> StreamPlatform.YOUTUBE
            extractVimeoId(trimmed) != null -> StreamPlatform.VIMEO
            extractDailymotionId(trimmed) != null -> StreamPlatform.DAILYMOTION
            isRedditUrl(trimmed) -> StreamPlatform.REDDIT
            else -> StreamPlatform.WEBVIEW_FALLBACK
        }
    }

    /** YouTube playlist ID, whether on the normal site or music.youtube.com. */
    fun extractYouTubePlaylistId(url: String): String? {
        val patterns = listOf(
            Regex("""(?:https?://)?(?:www\.|music\.)?youtube\.com/(?:playlist|watch)\?[^ ]*list=([a-zA-Z0-9_-]+)"""),
            Regex("""(?:https?://)?(?:www\.|music\.)?youtube\.com/playlist\?list=([a-zA-Z0-9_-]+)"""),
        )
        for (p in patterns) {
            val match = p.find(url.trim())
            if (match != null) return match.groupValues[1]
        }
        return null
    }

    fun isYouTubeMusicUrl(url: String): Boolean {
        val lower = url.trim().lowercase()
        return lower.contains("://music.youtube.com") || lower.contains("://m.music.youtube.com")
    }

    fun extractTwitchChannel(url: String): String? {
        val pattern = Regex("""(?:https?://)?(?:www\.|m\.)?twitch\.tv/([a-zA-Z0-9_]+)""")
        return pattern.find(url.trim())?.groupValues?.get(1)?.lowercase()
    }

    fun extractYouTubeVideoId(url: String): String? {
        val patterns = listOf(
            Regex("""(?:https?://)?(?:www\.|music\.)?youtube\.com/watch\?[^ ]*v=([a-zA-Z0-9_-]+)"""),
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
        val patterns = listOf(
            Regex("""(?:https?://)?(?:www\.|player\.)?vimeo\.com/(?:video/)?(\d+)"""),
        )
        for (p in patterns) {
            val match = p.find(url.trim())
            if (match != null) return match.groupValues[1]
        }
        return null
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
            lower.contains("redd.it/") && !lower.contains("v.redd.it/")
    }

    fun displayName(url: String): String {
        return when (identify(url)) {
            StreamPlatform.TWITCH -> extractTwitchChannel(url) ?: "Twitch"
            StreamPlatform.YOUTUBE -> if (isYouTubeMusicUrl(url)) "YouTube Music" else "YouTube"
            StreamPlatform.YOUTUBE_PLAYLIST ->
                if (isYouTubeMusicUrl(url)) "YouTube Music playlist" else "YouTube playlist"
            StreamPlatform.VIMEO -> "Vimeo"
            StreamPlatform.DAILYMOTION -> "Dailymotion"
            StreamPlatform.REDDIT -> "Reddit"
            StreamPlatform.DIRECT_HLS -> "HLS Stream"
            StreamPlatform.DIRECT_VIDEO -> "Video"
            StreamPlatform.WEBVIEW_FALLBACK -> "Web"
        }
    }

    suspend fun resolve(url: String): Result<ResolvedStream> {
        val trimmed = url.trim()
        return when (identify(trimmed)) {
            StreamPlatform.DIRECT_HLS -> Result.success(ResolvedStream(trimmed, isHls = true))
            StreamPlatform.DIRECT_VIDEO -> Result.success(ResolvedStream(trimmed, isHls = false))
            StreamPlatform.TWITCH -> {
                val channel = extractTwitchChannel(trimmed)!!
                resolveTwitch(channel).map { ResolvedStream(it, isHls = true) }
            }
            StreamPlatform.YOUTUBE -> resolveYouTube(trimmed)
            StreamPlatform.YOUTUBE_PLAYLIST ->
                // Shouldn't be called directly — playlists go through
                // resolvePlaylist + lazy per-track resolve — but if someone
                // does, play the first track so they get something.
                resolvePlaylist(trimmed).mapCatching { items ->
                    val first = items.firstOrNull()
                        ?: throw Exception("Empty playlist")
                    resolveYouTube(first.sourceUrl).getOrThrow()
                        .copy(title = first.title, artworkUrl = first.artworkUrl)
                }
            StreamPlatform.VIMEO -> resolveVimeo(extractVimeoId(trimmed)!!)
            StreamPlatform.DAILYMOTION -> resolveDailymotion(extractDailymotionId(trimmed)!!)
            StreamPlatform.REDDIT -> resolveReddit(trimmed)
            StreamPlatform.WEBVIEW_FALLBACK -> Result.failure(Exception("Needs WebView fallback"))
        }
    }

    /**
     * Expand a YouTube / YouTube Music playlist URL into its tracklist via
     * NewPipe. Only video URLs + titles + thumbnails are returned — the
     * actual playable URLs are resolved lazily per-track in PlayerActivity.
     */
    suspend fun resolvePlaylist(url: String): Result<List<QueueItem>> = withContext(Dispatchers.IO) {
        try {
            ensureNewPipeInit()
            val info = PlaylistInfo.getInfo(ServiceList.YouTube, url)
            val items = info.relatedItems?.map { item ->
                val art = item.thumbnails?.firstOrNull()?.url
                QueueItem(
                    sourceUrl = item.url,
                    title = item.name,
                    artworkUrl = art
                )
            } ?: emptyList()
            if (items.isEmpty()) {
                Result.failure(Exception("Playlist has no playable items"))
            } else {
                Result.success(items)
            }
        } catch (e: Exception) {
            Result.failure(e)
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

        val request = Request.Builder()
            .url(GQL_URL)
            .post(gqlBody.toString().toRequestBody("application/json".toMediaType()))
            .header("Client-ID", CLIENT_ID)
            .build()

        val response = client.newCall(request).execute()
        if (!response.isSuccessful) return null

        val body = response.body?.string() ?: return null
        val json = JSONObject(body)
        val tokenData = json
            .optJSONObject("data")
            ?.optJSONObject("streamPlaybackAccessToken")
            ?: return null

        val token = tokenData.getString("value")
        val sig = tokenData.getString("signature")
        return Pair(token, sig)
    }

    // --- YouTube ---

    private suspend fun resolveYouTube(url: String): Result<ResolvedStream> = withContext(Dispatchers.IO) {
        try {
            ensureNewPipeInit()
            val canonical = canonicalizeYouTubeUrl(url)
            val info = StreamInfo.getInfo(ServiceList.YouTube, canonical)

            val title = info.name
            val artworkUrl = info.thumbnails?.firstOrNull()?.url
            // Treat YouTube Music URLs and audio-only streams as music so
            // the player overlays album art instead of showing black.
            val isAudioOnly = isYouTubeMusicUrl(url) ||
                info.streamType == StreamType.AUDIO_STREAM ||
                info.streamType == StreamType.AUDIO_LIVE_STREAM

            if (info.streamType == StreamType.LIVE_STREAM || info.streamType == StreamType.AUDIO_LIVE_STREAM) {
                val hls = info.hlsUrl
                if (!hls.isNullOrEmpty()) {
                    return@withContext Result.success(ResolvedStream(
                        url = hls, isHls = true,
                        title = title, artworkUrl = artworkUrl, isAudioOnly = isAudioOnly
                    ))
                }
            }

            // For YouTube Music / audio-only: prefer audioStreams — smaller,
            // cheaper to stream, no wasted video decode on glass.
            if (isAudioOnly) {
                val audioStreams = info.audioStreams
                val best = audioStreams?.firstOrNull()
                if (best != null) {
                    return@withContext Result.success(ResolvedStream(
                        url = best.content, isHls = false,
                        title = title, artworkUrl = artworkUrl, isAudioOnly = true
                    ))
                }
            }

            val streams = info.videoStreams
            if (streams.isNullOrEmpty()) {
                return@withContext Result.failure(Exception("No playable YouTube streams"))
            }
            val best = streams.first()
            Result.success(ResolvedStream(
                url = best.content, isHls = false,
                title = title, artworkUrl = artworkUrl, isAudioOnly = isAudioOnly
            ))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun canonicalizeYouTubeUrl(url: String): String {
        val id = extractYouTubeVideoId(url) ?: return url
        // Normalise to plain youtube.com so NewPipe handles music.youtube.com
        // tracks the same as regular YouTube videos — same video IDs, same
        // underlying streams.
        return "https://www.youtube.com/watch?v=$id"
    }

    private fun ensureNewPipeInit() {
        if (!newPipeInitialised) {
            synchronized(this) {
                if (!newPipeInitialised) {
                    NewPipe.init(NewPipeDownloader.getInstance())
                    newPipeInitialised = true
                }
            }
        }
    }

    // --- Vimeo ---
    // Public config endpoint. Embed-locked videos will 403; those fall through
    // to WebView at the PlayerActivity error handler.

    private suspend fun resolveVimeo(id: String): Result<ResolvedStream> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("https://player.vimeo.com/video/$id/config")
                .header("Referer", "https://vimeo.com/")
                .build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                return@withContext Result.failure(Exception("Vimeo config HTTP ${response.code}"))
            }
            val body = response.body?.string()
                ?: return@withContext Result.failure(Exception("Empty Vimeo config"))
            val json = JSONObject(body)
            val files = json.optJSONObject("request")?.optJSONObject("files")
                ?: return@withContext Result.failure(Exception("No files in Vimeo config"))

            // Prefer HLS
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
            // Fall back to progressive
            val progressive = files.optJSONArray("progressive")
            if (progressive != null && progressive.length() > 0) {
                // Highest-quality entry is usually first but pick max-height to be safe.
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
                return@withContext Result.failure(Exception("Dailymotion metadata HTTP ${response.code}"))
            }
            val body = response.body?.string()
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
    // Handles:
    //   - https://v.redd.it/{id}           → HLS at {id}/HLSPlaylist.m3u8
    //   - https://www.reddit.com/r/.../comments/... → fetch .json + pull reddit_video
    //   - https://redd.it/{postId}          → fetch .json similarly

    private suspend fun resolveReddit(url: String): Result<ResolvedStream> = withContext(Dispatchers.IO) {
        try {
            val lower = url.lowercase()

            // Direct v.redd.it — build the HLS master URL if one wasn't given.
            val vReddIt = Regex("""https?://v\.redd\.it/([a-zA-Z0-9]+)""").find(lower)
            if (vReddIt != null) {
                val id = vReddIt.groupValues[1]
                val hls = "https://v.redd.it/$id/HLSPlaylist.m3u8"
                return@withContext Result.success(ResolvedStream(hls, isHls = true))
            }

            // Reddit post URL — append .json to get post metadata.
            val jsonUrl = if (url.endsWith(".json")) url else "$url.json"
            val request = Request.Builder()
                .url(jsonUrl)
                .header("User-Agent", "GlassHoleStreamPlayer/0.3 (https://github.com/benharlett/glasshole)")
                .build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                return@withContext Result.failure(Exception("Reddit API HTTP ${response.code}"))
            }
            val body = response.body?.string()
                ?: return@withContext Result.failure(Exception("Empty Reddit response"))
            // Reddit returns a top-level array [listing_of_post, listing_of_comments].
            // If the caller gave a listing URL we still get an array; handle both shapes.
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
            // Some Reddit posts wrap external video as secure_media with oembed, not a
            // reddit_video — grab the originating URL and let the top-level resolver
            // re-identify it (covers crossposts to YouTube, Twitch, etc.).
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
