package com.glasshole.phone.plugins.media

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadata
import android.net.Uri
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.util.Base64
import android.util.Log
import com.glasshole.phone.AppLog
import com.glasshole.phone.plugin.PhonePlugin
import com.glasshole.phone.plugin.PluginSender
import com.glasshole.phone.service.NotificationForwardingService
import com.glasshole.sdk.PluginMessage
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.IdentityHashMap
import java.util.concurrent.Executors

/**
 * Phone-side media-remote plugin. Uses MediaSessionManager (permission
 * granted by our NotificationListener registration) to watch the
 * currently active audio MediaSession on the phone. Every time a
 * Spotify / YouTube Music / Pocket Casts / etc. session changes
 * metadata or playback state, we forward a NOW_PLAYING snapshot to
 * the glass. Glass sends control commands back; we invoke
 * MediaController.transportControls.
 *
 * Session selection: if multiple sessions are active we prefer the
 * one that's currently playing. Ties resolved by position in the
 * OS-ordered list (most-recently-active first).
 */
class MediaPlugin : PhonePlugin {

    companion object {
        private const val TAG = "MediaPlugin"
        private const val ART_MAX_EDGE_PX = 200
    }

    override val pluginId: String = "media"

    private lateinit var appContext: Context
    private lateinit var sender: PluginSender
    private val main = Handler(Looper.getMainLooper())
    // Serialized worker — art decode / network fetches / JSON build all run
    // here so we never block the main thread or step on ourselves when a
    // rapid burst of metadata changes comes in.
    private val worker = Executors.newSingleThreadExecutor()

    private var mediaSessionManager: MediaSessionManager? = null
    private var listenerComponent: ComponentName? = null
    private var activeController: MediaController? = null
    private var lastArtKey: String = ""
    // Per-session callbacks. We register on EVERY active session so that any
    // session's state change can trigger re-selection — the OS-level
    // sessionsListener fires only on add/remove, not state flips, and
    // subscribing to just the bound session leaves us blind when a different
    // session goes paused→playing while ours is already paused.
    private val sessionCallbacks = IdentityHashMap<MediaController, MediaController.Callback>()

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { list ->
        try {
            bindToActive(list ?: emptyList())
        } catch (e: Exception) {
            Log.w(TAG, "sessions change failed: ${e.message}")
        }
    }

    private fun makeCallback(controller: MediaController) = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            // Any session's state change can mean the user paused one app and
            // started another — re-pick from the full list.
            reevaluateActiveSessions()
        }
        override fun onMetadataChanged(metadata: MediaMetadata?) {
            if (controller === activeController) {
                lastArtKey = ""
                push()
            }
        }
        override fun onSessionDestroyed() {
            sessionCallbacks.remove(controller)
            if (controller === activeController) {
                activeController = null
                lastArtKey = ""
            }
            // sessionsListener will fire and re-bind from surviving sessions.
        }
    }

    /**
     * Re-run session selection against the full active-sessions list.
     * Returns true if it successfully (re)bound to a session, false if
     * no sessions are available and the caller should announce empty.
     */
    private fun reevaluateActiveSessions(): Boolean {
        val mgr = mediaSessionManager ?: return false
        val cmp = listenerComponent ?: return false
        return try {
            val sessions = mgr.getActiveSessions(cmp)
            if (sessions.isEmpty()) {
                false
            } else {
                bindToActive(sessions)
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    override fun onCreate(context: Context, sender: PluginSender) {
        this.appContext = context.applicationContext
        this.sender = sender
        try {
            mediaSessionManager = appContext.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
            listenerComponent = ComponentName(appContext, NotificationForwardingService::class.java)
            val mgr = mediaSessionManager
            val cmp = listenerComponent
            if (mgr != null && cmp != null) {
                mgr.addOnActiveSessionsChangedListener(sessionsListener, cmp, main)
                bindToActive(mgr.getActiveSessions(cmp))
            }
        } catch (e: SecurityException) {
            AppLog.log(TAG, "Media: notification listener not granted yet — enable GlassHole in Settings → Notifications")
        } catch (e: Exception) {
            Log.w(TAG, "onCreate failed: ${e.message}")
        }
    }

    override fun onDestroy() {
        try { mediaSessionManager?.removeOnActiveSessionsChangedListener(sessionsListener) } catch (_: Exception) {}
        for ((c, cb) in sessionCallbacks) {
            try { c.unregisterCallback(cb) } catch (_: Exception) {}
        }
        sessionCallbacks.clear()
        activeController = null
        lastArtKey = ""
        worker.shutdown()
    }

    override fun onGlassConnectionChanged(connected: Boolean) {
        if (connected) push() // re-send current state so a fresh connection sees now-playing
    }

    override fun onMessageFromGlass(message: PluginMessage) {
        when (message.type) {
            "REFRESH" -> {
                // The art dedup cache skips resending when title/album are
                // unchanged. On an explicit REFRESH (glass activity just
                // opened), the glass almost certainly doesn't have the art
                // yet — force a re-send so the opening shows cover art.
                lastArtKey = ""
                push()
            }
            "TOGGLE" -> {
                val state = activeController?.playbackState?.state
                if (state == PlaybackState.STATE_PLAYING) {
                    sendMediaKey(KeyEvent.KEYCODE_MEDIA_PAUSE)
                } else {
                    sendMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY)
                }
            }
            "PLAY" -> sendMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY)
            "PAUSE" -> sendMediaKey(KeyEvent.KEYCODE_MEDIA_PAUSE)
            "NEXT" -> sendMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT)
            "PREV" -> sendMediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            else -> Log.d(TAG, "Unknown message: ${message.type}")
        }
    }

    private fun sendMediaKey(keycode: Int) {
        val controller = activeController ?: return
        // Simulate a hardware media key press. Apps handle these uniformly
        // (YouTube Music, Spotify, Pocket Casts, etc.) whereas transportControls
        // callbacks are per-app and some implementations restart the track
        // on the first skipToNext after a pause/reopen.
        val eventTime = SystemClock.uptimeMillis()
        val down = KeyEvent(eventTime, eventTime, KeyEvent.ACTION_DOWN, keycode, 0)
        val up = KeyEvent(eventTime, eventTime, KeyEvent.ACTION_UP, keycode, 0)
        try {
            controller.dispatchMediaButtonEvent(down)
            controller.dispatchMediaButtonEvent(up)
        } catch (e: Exception) {
            Log.w(TAG, "dispatchMediaButtonEvent failed: ${e.message}")
        }
    }

    private fun bindToActive(controllers: List<MediaController>) {
        watchAllSessions(controllers)

        if (controllers.isEmpty()) {
            activeController = null
            lastArtKey = ""
            pushEmpty()
            return
        }

        // Sticky selection: keep following the currently-bound app only if
        // it's still actively playing (or nothing else is playing). This
        // prevents flip-flopping when a notification tone briefly grabs
        // media focus, but lets us switch away when the user pauses one
        // app and starts another — e.g. pausing YouTube and opening
        // YouTube Music.
        val currentPkg = activeController?.packageName
        if (currentPkg != null) {
            val stickySession = controllers.firstOrNull { it.packageName == currentPkg }
            val stickyPlaying =
                stickySession?.playbackState?.state == PlaybackState.STATE_PLAYING
            val otherPlaying = controllers.any {
                it.packageName != currentPkg &&
                    it.playbackState?.state == PlaybackState.STATE_PLAYING
            }
            if (stickySession != null && (stickyPlaying || !otherPlaying)) {
                if (stickySession !== activeController) {
                    activeController = stickySession
                    lastArtKey = ""
                }
                push()
                return
            }
        }

        // No sticky match — pick a new session. Prefer one currently playing;
        // fall back to most-recently-active (first in OS-ordered list).
        val preferred = controllers.firstOrNull {
            it.playbackState?.state == PlaybackState.STATE_PLAYING
        } ?: controllers.first()

        if (preferred !== activeController) {
            activeController = preferred
            lastArtKey = ""
        }
        push()
    }

    private fun watchAllSessions(controllers: List<MediaController>) {
        val incoming = IdentityHashMap<MediaController, Unit>()
        for (c in controllers) incoming[c] = Unit
        val iter = sessionCallbacks.entries.iterator()
        while (iter.hasNext()) {
            val entry = iter.next()
            if (entry.key !in incoming) {
                try { entry.key.unregisterCallback(entry.value) } catch (_: Exception) {}
                iter.remove()
            }
        }
        for (c in controllers) {
            if (c !in sessionCallbacks) {
                val cb = makeCallback(c)
                try {
                    c.registerCallback(cb, main)
                    sessionCallbacks[c] = cb
                } catch (e: Exception) {
                    Log.w(TAG, "registerCallback failed for ${c.packageName}: ${e.message}")
                }
            }
        }
    }

    private fun push() {
        val controller = activeController ?: run { pushEmpty(); return }
        // Snapshot the MediaController state on the main thread (it's the
        // thread the callback was registered on) so the worker sees a
        // consistent view even if the session updates again mid-encode.
        val metadata = controller.metadata
        val state = controller.playbackState
        val playing = state?.state == PlaybackState.STATE_PLAYING
        val appName = resolveAppName(controller.packageName)
        // PlaybackState.getPosition() is the position at lastPositionUpdateTime —
        // some apps (YouTube Music in particular) only refresh that on user
        // actions, so the raw value can be many seconds stale. Extrapolate to
        // wall-clock now using playbackSpeed so the glass receives a fresh
        // anchor every push instead of jumping backwards each cycle.
        val rawPos = state?.position ?: 0L
        val position = if (state != null && playing && rawPos >= 0L) {
            val elapsed = SystemClock.elapsedRealtime() - state.lastPositionUpdateTime
            rawPos + (elapsed * state.playbackSpeed).toLong()
        } else rawPos.coerceAtLeast(0L)
        val duration = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L

        worker.execute {
            val title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty()
            val artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
                ?: metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST).orEmpty()
            val album = metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM).orEmpty()

            val artB64 = try { encodeArt(metadata) } catch (e: Exception) {
                Log.w(TAG, "encodeArt failed: ${e.message}"); ""
            }

            // Track-transition crosstalk: between songs, YT Music briefly
            // emits a metadata snapshot where title is empty but album/artist
            // are still populated (sometimes with the *new* track's values).
            // If we forward that, glass falls back to the previous title and
            // we render last song's title alongside next song's artist —
            // looks like the wrong song. Gate on title alone and wait for the
            // follow-up onMetadataChanged tick that brings the real title.
            if (title.isEmpty()) {
                Log.d(TAG, "Skipping push: empty title for $appName")
                return@execute
            }

            val json = JSONObject().apply {
                put("has_session", true)
                put("title", title)
                put("artist", artist)
                put("album", album)
                put("app_name", appName)
                put("playing", playing)
                put("position_ms", position)
                put("duration_ms", duration)
                if (artB64.isNotEmpty()) put("art_b64", artB64)
            }.toString()

            sender(PluginMessage("NOW_PLAYING", json))
        }
    }

    private fun pushEmpty() {
        val json = JSONObject().apply { put("has_session", false) }.toString()
        sender(PluginMessage("NOW_PLAYING", json))
    }

    private fun encodeArt(metadata: MediaMetadata?): String {
        if (metadata == null) return ""
        val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty()
        val album = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM).orEmpty()
        val key = "$title|$album"
        if (key == lastArtKey && key.isNotEmpty()) return "" // art unchanged; don't re-send

        // Apps provide art in one of several places, in order of preference:
        //   1. Embedded Bitmap on ALBUM_ART / ART / DISPLAY_ICON
        //   2. URI on ALBUM_ART_URI / ART_URI / DISPLAY_ICON_URI — could be
        //      content:// (Spotify does this), http(s):// (some podcast apps),
        //      or file://. We resolve all three.
        val bmp: Bitmap? =
            metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)
                ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
                ?: loadBitmapFromUri(metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI))
                ?: loadBitmapFromUri(metadata.getString(MediaMetadata.METADATA_KEY_ART_URI))
                ?: loadBitmapFromUri(metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI))

        if (bmp == null) {
            dumpMetadata(metadata)
            Log.d(TAG, "No art found for '$title' / '$album' (checked bitmap + 3 URI keys)")
            return ""
        }

        lastArtKey = key
        val scaled = scaleToFit(bmp, ART_MAX_EDGE_PX)
        val out = ByteArrayOutputStream()
        // JPEG keeps this under ~15 KB — album art burns the BT pipe quickly
        // otherwise, and glass doesn't need pixel-perfect cover.
        scaled.compress(Bitmap.CompressFormat.JPEG, 75, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    private fun loadBitmapFromUri(uriStr: String?): Bitmap? {
        if (uriStr.isNullOrEmpty()) return null
        val uri = try { Uri.parse(uriStr) } catch (_: Exception) { return null }
        val scheme = uri.scheme?.lowercase() ?: return null
        return try {
            when (scheme) {
                "content", "file", "android.resource" -> {
                    appContext.contentResolver.openInputStream(uri)?.use {
                        BitmapFactory.decodeStream(it)
                    }
                }
                "http", "https" -> {
                    val conn = java.net.URL(uriStr).openConnection() as java.net.HttpURLConnection
                    conn.connectTimeout = 3000
                    conn.readTimeout = 5000
                    conn.instanceFollowRedirects = true
                    conn.inputStream.use { BitmapFactory.decodeStream(it) }
                }
                else -> null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Art URI load failed ($scheme): ${e.message}")
            null
        }
    }

    private fun scaleToFit(source: Bitmap, maxEdge: Int): Bitmap {
        val longest = maxOf(source.width, source.height)
        if (longest <= maxEdge) return source
        val ratio = maxEdge.toFloat() / longest
        val w = (source.width * ratio).toInt().coerceAtLeast(1)
        val h = (source.height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(source, w, h, true)
    }

    private fun dumpMetadata(metadata: MediaMetadata) {
        try {
            val keys = metadata.keySet()
            Log.i(TAG, "--- MediaMetadata dump (${keys.size} keys) ---")
            for (k in keys) {
                val v = try {
                    metadata.getString(k) ?: metadata.getBitmap(k)?.let {
                        "Bitmap(${it.width}x${it.height})"
                    } ?: "null"
                } catch (_: Exception) { "<unreadable>" }
                Log.i(TAG, "  $k = $v")
            }
        } catch (e: Exception) {
            Log.w(TAG, "dumpMetadata failed: ${e.message}")
        }
    }

    private fun resolveAppName(pkg: String?): String {
        if (pkg.isNullOrEmpty()) return ""
        return try {
            val pm = appContext.packageManager
            val info = pm.getApplicationInfo(pkg, 0)
            pm.getApplicationLabel(info).toString()
        } catch (_: Exception) {
            pkg
        }
    }
}
