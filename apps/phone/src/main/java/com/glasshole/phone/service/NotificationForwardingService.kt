package com.glasshole.phone.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * Generalized notification listener that forwards notifications from
 * user-selected apps to Glass via the BridgeService.
 */
class NotificationForwardingService : NotificationListenerService() {

    companion object {
        private const val TAG = "GlassHoleNLS"
        const val PREFS_NAME = "glasshole_notif_prefs"
        const val PREF_FORWARDED_APPS = "forwarded_apps"
        /** Subset of forwarded_apps whose silent / low-importance
         *  notifications also pass the global silent filter. */
        const val PREF_SILENT_ALLOWED_APPS = "silent_allowed_apps"

        @Volatile var instance: NotificationForwardingService? = null
    }

    @Volatile var latestReplyAction: Notification.Action? = null
    @Volatile private var latestNotificationKey: String? = null

    // Callback set by BridgeService
    var onNotificationForGlass: ((appName: String, title: String, text: String) -> Unit)? = null

    // Rich callback with optional base64 app icon. Preferred over the text-only
    // callback when both are set.
    var onRichNotificationForGlass: (
        (pkg: String, appName: String, title: String, text: String, iconBase64: String?) -> Unit
    )? = null

    // Actions-aware callback. Gets the full JSON (app/title/text/icon + key + actions)
    // ready to ship over BT in a single NOTIF message. Preferred over the legacy
    // callbacks if set.
    var onNotifWithActions: ((json: String) -> Unit)? = null

    // Fires when a forwarded notification is removed on the phone so the
    // glass-side Home active-notification list stays in sync.
    var onNotifRemoved: ((key: String) -> Unit)? = null

    // Pending action entries keyed by notifKey. Each entry maps actionId →
    // the PendingIntent (and RemoteInputs, if this is a reply action) to fire.
    // `kind` lets us pick the right invocation path in invokeAction() —
    // open_phone bypasses the PendingIntent and uses getLaunchIntentForPackage
    // to work around Android 14+ BAL restrictions.
    private data class PendingAction(
        val intent: PendingIntent?,
        val remoteInputs: Array<RemoteInput>?,
        val kind: String = "action",
        val pkg: String? = null,
        val url: String? = null
    )
    private val pendingActions =
        java.util.concurrent.ConcurrentHashMap<String, MutableMap<String, PendingAction>>()
    // LRU cap so long-lived listener doesn't leak. Each entry is a small map.
    private val pendingKeysOrder = java.util.concurrent.ConcurrentLinkedDeque<String>()
    private val PENDING_CAP = 32

    // Debug / test action handlers — registered by DebugActivity when it
    // posts a synthetic test notification. Consulted by invokeAction() before
    // the real PendingIntent registry.
    data class DebugHandlers(val map: Map<String, (replyText: String?) -> Unit>)
    private val debugHandlers =
        java.util.concurrent.ConcurrentHashMap<String, DebugHandlers>()

    fun registerDebugHandlers(notifKey: String, handlers: Map<String, (String?) -> Unit>) {
        debugHandlers[notifKey] = DebugHandlers(handlers)
    }

    /**
     * Register a real open_phone action for a synthetic debug notification
     * so it exercises the full relay launch path (URL ACTION_VIEW → package
     * launcher fallback) instead of just invoking a toast handler.
     */
    fun registerDebugOpenPhone(
        notifKey: String,
        actionId: String,
        pkg: String?,
        url: String?
    ) {
        val map = pendingActions.getOrPut(notifKey) { mutableMapOf() }
        map[actionId] = PendingAction(
            intent = null,
            remoteInputs = null,
            kind = "open_phone",
            pkg = pkg,
            url = url
        )
        pendingKeysOrder.remove(notifKey)
        pendingKeysOrder.addLast(notifKey)
        while (pendingKeysOrder.size > PENDING_CAP) {
            val oldest = pendingKeysOrder.pollFirst() ?: break
            pendingActions.remove(oldest)
        }
    }

    private var lastMessageText: String = ""
    private var lastMessageTime: Long = 0

    // Set of package names to forward (empty = forward all)
    private var forwardedApps: Set<String> = emptySet()
    private var silentAllowedApps: Set<String> = emptySet()

    override fun onCreate() {
        super.onCreate()
        instance = this
        loadForwardedApps()
        Log.i(TAG, "=== NotificationListener CREATED ===")
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.i(TAG, "=== NotificationListener CONNECTED ===")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val pkg = sbn.packageName
        Log.d(TAG, "onNotificationPosted: pkg=$pkg key=${sbn.key.take(80)}")
        val notification = sbn.notification ?: return
        val extras = notification.extras ?: return

        // Google Maps nav gets intercepted before the forwarding filter —
        // the Nav plugin consumes it as structured turn data, not as a
        // regular text notification. This runs regardless of whether the
        // user has Maps in their forwarded-apps list.
        if (pkg == com.glasshole.phone.plugins.nav.NavPlugin.MAPS_PKG) {
            try {
                com.glasshole.phone.plugins.nav.NavPlugin.instance?.handleMapsNotification(sbn)
            } catch (e: Exception) {
                Log.w(TAG, "Nav handle failed: ${e.message}")
            }
            return
        }

        // Only forward notifications from user-selected apps. Default (empty) = none.
        if (pkg !in forwardedApps) return

        // Skip own notifications
        if (pkg == "com.glasshole.phone") return

        // Skip group summaries
        if ((notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return

        // Skip notifications the originating app has marked LOCAL_ONLY —
        // these are explicitly for the phone screen and aren't meant to
        // be mirrored to companion devices (wearables, glass, etc.).
        // Catches Google Messages "Device pairing — sent from desktop"
        // and similar sync/status notifications even when the channel
        // importance is normal. Per-app silent allowance ALSO bypasses
        // this — if the user opted an app into "Allow silent" they want
        // its full output regardless of LOCAL_ONLY.
        if (pkg !in silentAllowedApps &&
            (notification.flags and Notification.FLAG_LOCAL_ONLY) != 0) {
            Log.d(TAG, "Skipping LOCAL_ONLY notification from $pkg")
            return
        }

        // Skip foreground-service status notifications — they're the
        // persistent "I'm running" indicator a service shows while it's
        // alive (Google Messages "Device pairing — connected to web",
        // Spotify offline-sync, download manager, location-sharing
        // sticky, etc.). The user already has them on the phone; we
        // don't need to spam the glass every time one re-renders.
        // FLAG_LOCAL_ONLY would be the correct signal but Google
        // Messages's pairing notification doesn't set it; FLAG_
        // FOREGROUND_SERVICE catches the same family. Per-app bypass
        // via silentAllowedApps for users who genuinely want these.
        if (pkg !in silentAllowedApps &&
            (notification.flags and Notification.FLAG_FOREGROUND_SERVICE) != 0) {
            Log.d(TAG, "Skipping FOREGROUND_SERVICE notification from $pkg")
            return
        }

        // Skip media playback "now playing" cards (YouTube, Spotify, Pocket Casts,
        // etc.). These are transport-control notifications attached to a
        // MediaSession — not user-facing alerts — so forwarding them to the
        // glass just spams the display every time the user opens a video. The
        // app's normal notifications (new video uploaded, subscription alerts,
        // etc.) carry a different category and still go through.
        if (notification.category == Notification.CATEGORY_TRANSPORT) return
        if (extras.containsKey(Notification.EXTRA_MEDIA_SESSION)) return

        // Skip silent / low-importance notifications. These are typically
        // background sync events ("you sent a message from desktop", "added
        // to playlist", reaction sync, etc.) that the user has explicitly
        // told their phone NOT to interrupt them with — forwarding them to
        // the glass would undo that preference and spam the heads-up
        // popup. On API 26+ the channel's importance is the source of
        // truth; below that we fall back to the legacy priority field.
        //
        // Per-app override: an app in silentAllowedApps bypasses this filter
        // — for users who want a specific quiet app's notifications even
        // though they're flagged silent on the phone.
        if (pkg !in silentAllowedApps) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channelId = notification.channelId
                if (channelId != null) {
                    // Try the direct path first (only works for our own
                    // app's channels on most builds). When that returns
                    // null, fall back to the listener's Ranking API
                    // which DOES expose the originating app's channel
                    // importance — required because most cross-app
                    // channels (e.g. Google Messages's
                    // bugle_connected_to_web_channel_v1) come back null
                    // through the direct call.
                    val nm = getSystemService(NotificationManager::class.java)
                    var importance = nm?.getNotificationChannel(channelId)?.importance
                        ?: NotificationManager.IMPORTANCE_UNSPECIFIED
                    if (importance == NotificationManager.IMPORTANCE_UNSPECIFIED) {
                        try {
                            val rankingMap = currentRanking
                            val ranking = android.service.notification.NotificationListenerService.Ranking()
                            if (rankingMap?.getRanking(sbn.key, ranking) == true) {
                                importance = ranking.channel?.importance
                                    ?: NotificationManager.IMPORTANCE_UNSPECIFIED
                            }
                        } catch (_: Exception) {}
                    }
                    if (importance != NotificationManager.IMPORTANCE_UNSPECIFIED &&
                        importance < NotificationManager.IMPORTANCE_DEFAULT) {
                        Log.d(TAG, "Skipping low-importance ($importance) channel from $pkg")
                        return
                    }
                }
            }
            @Suppress("DEPRECATION")
            if (notification.priority < Notification.PRIORITY_DEFAULT) return
        }

        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
        val messageText = if (!bigText.isNullOrEmpty()) bigText else text

        if (messageText.isEmpty()) return

        // Deduplicate
        val now = System.currentTimeMillis()
        if (messageText == lastMessageText && now - lastMessageTime < 2000) return
        lastMessageText = messageText
        lastMessageTime = now

        // Capture reply action (legacy — kept for older code paths)
        val replyAction = findReplyAction(notification)
        if (replyAction != null) {
            latestReplyAction = replyAction
            latestNotificationKey = sbn.key
        }

        // Get app name
        val appName = try {
            val appInfo = packageManager.getApplicationInfo(pkg, 0)
            packageManager.getApplicationLabel(appInfo).toString()
        } catch (_: Exception) {
            pkg
        }

        // Channel + flags telemetry helps diagnose "why did this make it
        // through the filters" without needing to repro under a debugger.
        val channelInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val cid = notification.channelId ?: "<no-channel>"
            val nm = getSystemService(NotificationManager::class.java)
            val imp = nm?.getNotificationChannel(cid)?.importance ?: -1
            "channel=$cid importance=$imp"
        } else "channel=<api<26>"
        Log.i(TAG, "Forwarding: $appName | $title | $messageText [$channelInfo flags=0x${notification.flags.toString(16)}]")
        logActionStructure(notification, pkg)

        // Build the structured actions list for this notification
        val notifKey = sbn.key
        val actionsMap = mutableMapOf<String, PendingAction>()
        val actionsJson = buildActionsJson(notification, pkg, title, messageText, actionsMap)

        if (actionsMap.isNotEmpty()) {
            putPendingActions(notifKey, actionsMap)
        }

        val actionsAware = onNotifWithActions
        if (actionsAware != null) {
            val iconBase64 = encodeAppIcon(pkg)
            val pictureBase64 = encodeBigPicture(
                notification, extras, title, messageText, notifKey, sbn.tag
            )
            val titleIconBase64 = encodeTitleIcon(pkg, notification, extras)
            if (titleIconBase64 == null) {
                // Diagnostic: dump every avatar-relevant field for
                // notifications that didn't yield a title icon, so we
                // can see what X / Outlook / etc. actually populate.
                debugLogAvatarSources(pkg, notification, extras)
            }
            // Snag the video id (if any) so debug-replay can re-fetch
            // the thumbnail later without re-deriving it from extras.
            val videoId = com.glasshole.phone.util.YouTubeThumbnail
                .findVideoIdInNotification(notification, extras, title, messageText, sbn.tag)
            val dismissMs = getSharedPreferences("glasshole_prefs", Context.MODE_PRIVATE)
                .getLong("notif_timeout_ms", 12_000L)
            val json = JSONObject().apply {
                put("key", notifKey)
                put("pkg", pkg)
                put("app", appName)
                put("title", title)
                put("text", messageText)
                if (iconBase64 != null) put("icon", iconBase64)
                if (titleIconBase64 != null) put("title_icon", titleIconBase64)
                if (pictureBase64 != null) put("picture", pictureBase64)
                if (videoId != null) put("video_id", videoId)
                put("actions", actionsJson)
                put("dismissMs", dismissMs)
            }.toString()
            // Opt-in debug capture for replay testing. Off by default; the
            // Debug screen toggles it. Stores the same byte stream we just
            // built so a replay matches the original glass-side render.
            com.glasshole.phone.debug.NotificationReplayStore.capture(this, json)
            actionsAware(json)
            return
        }

        val rich = onRichNotificationForGlass
        if (rich != null) {
            val iconBase64 = encodeAppIcon(pkg)
            rich(pkg, appName, title, messageText, iconBase64)
        } else {
            onNotificationForGlass?.invoke(appName, title, messageText)
        }
    }

    // Build the actions list for a notification. Order of preference:
    //  1. Reply action (RemoteInput present)
    //  2. "Open on Glass" if a stream URL is detected (YouTube/Twitch)
    //  3. "Open on Phone" from contentIntent or any detected URL
    //  4. Remaining non-reply Notification.actions
    private fun buildActionsJson(
        notification: Notification,
        sourcePkg: String,
        title: String,
        text: String,
        out: MutableMap<String, PendingAction>
    ): JSONArray {
        val arr = JSONArray()
        var idx = 0
        fun add(id: String, label: String, type: String, extra: JSONObject? = null) {
            val obj = JSONObject().apply {
                put("id", id)
                put("label", label)
                put("type", type)
                if (extra != null) {
                    val keys = extra.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        put(k, extra.get(k))
                    }
                }
            }
            arr.put(obj)
        }

        // 1. Reply action
        val reply = findReplyAction(notification)
        if (reply != null) {
            val id = "a${idx++}"
            out[id] = PendingAction(reply.actionIntent, reply.remoteInputs, kind = "reply")
            add(id, reply.title?.toString() ?: "Reply", "reply")
        }

        // 2. Stream URL detection
        val url = detectFirstUrl("$title\n$text")
        if (url != null && isStreamUrl(url)) {
            add("stream", "Watch on Glass", "open_glass_stream",
                JSONObject().put("url", url))
        }

        // 3. "Open on phone" action removed — the LaunchRelayActivity
        // path for opening the source app from the glass action sheet was
        // unreliable across Android versions and notification types. Users
        // can still long-press the notif on their phone to handle it natively.

        // 4. Remaining non-reply actions. Skip labels that duplicate gestures
        // the user already has on glass (swipe down dismisses, and the host
        // app marks notifications read when the card closes). Reply-like
        // actions that don't expose a RemoteInput (Gmail, Outlook) get
        // relabeled "Reply on Phone" and routed like open_phone so the
        // glass UX is honest about what's about to happen.
        notification.actions?.forEach { action ->
            if (action.remoteInputs?.isNotEmpty() == true) return@forEach  // handled above
            val label = action.title?.toString().orEmpty()
            if (isSuppressedActionLabel(label)) return@forEach

            val semantic = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) action.semanticAction else 0
            val isActivity = try { action.actionIntent?.isActivity == true } catch (_: Throwable) { false }
            val looksLikeReply =
                semantic == Notification.Action.SEMANTIC_ACTION_REPLY ||
                label.lowercase().startsWith("reply")

            val id = "a${idx++}"
            if (looksLikeReply && isActivity) {
                out[id] = PendingAction(
                    intent = action.actionIntent,
                    remoteInputs = null,
                    kind = "open_phone",
                    pkg = sourcePkg
                )
                add(id, "Reply on Phone", "open_phone")
            } else {
                out[id] = PendingAction(
                    intent = action.actionIntent,
                    remoteInputs = null,
                    kind = "action",
                    pkg = sourcePkg
                )
                add(id, label.ifEmpty { "Action" }, "action")
            }
        }

        return arr
    }

    private fun isSuppressedActionLabel(label: String): Boolean {
        val l = label.lowercase().trim()
        if (l.isEmpty()) return false
        return l == "mark as read" ||
               l == "mark read" ||
               l == "dismiss" ||
               l == "clear" ||
               l == "delete" ||
               l == "archive" ||
               l.startsWith("mark as read") ||
               l.startsWith("mark read")
    }

    private val URL_REGEX = Regex("(https?://\\S+)")
    private fun detectFirstUrl(s: String): String? =
        URL_REGEX.find(s)?.value?.trimEnd('.', ',', ')', ']', '!', '?')

    private fun isStreamUrl(url: String): Boolean {
        val l = url.lowercase()
        return l.contains("youtube.com/watch") ||
               l.contains("youtu.be/") ||
               l.contains("youtube.com/shorts") ||
               l.contains("twitch.tv/")
    }

    private fun putPendingActions(key: String, actions: Map<String, PendingAction>) {
        pendingActions[key] = actions.toMutableMap()
        pendingKeysOrder.remove(key)
        pendingKeysOrder.addLast(key)
        while (pendingKeysOrder.size > PENDING_CAP) {
            val oldest = pendingKeysOrder.pollFirst() ?: break
            pendingActions.remove(oldest)
        }
    }

    /**
     * Invoke a previously-forwarded action. Called by BridgeService when the
     * glass sends back NOTIF_ACTION. Returns true on success.
     */
    /**
     * Open the source app on the phone. Android 14+ BAL blocks background
     * services from bringing other apps' tasks to foreground, and the NLS
     * we live in is NOT a BAL-exempt context. BridgeService IS —
     * it's a CONNECTED_DEVICE foreground service, which Android 15
     * explicitly exempts from BAL. So we delegate there first; if
     * BridgeService isn't available we fall back to the PendingIntent
     * relay from our own context (which works when BAL is already
     * granting us an exemption for some other reason, e.g. the target
     * app has a warm visible task).
     */
    private fun launchAppViaRelay(pkg: String?, url: String?): Boolean {
        BridgeService.instance?.let { bridge ->
            if (bridge.launchAppOnPhone(pkg, url)) return true
        }
        return launchAppViaRelayFromNls(pkg, url)
    }

    private fun launchAppViaRelayFromNls(pkg: String?, url: String?): Boolean {
        return try {
            val relay = Intent().apply {
                setClassName(this@NotificationForwardingService, "com.glasshole.phone.LaunchRelayActivity")
                if (pkg != null) putExtra("target_pkg", pkg)
                if (url != null) putExtra("target_url", url)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
            }
            val pi = PendingIntent.getActivity(
                this,
                (pkg ?: "").hashCode(),
                relay,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val optsBundle = try {
                val opts = android.app.ActivityOptions.makeBasic()
                if (android.os.Build.VERSION.SDK_INT >= 34) {
                    opts.pendingIntentBackgroundActivityStartMode =
                        android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                    opts.pendingIntentCreatorBackgroundActivityStartMode =
                        android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                } else if (android.os.Build.VERSION.SDK_INT >= 33) {
                    @Suppress("DEPRECATION")
                    opts.setPendingIntentBackgroundActivityLaunchAllowed(true)
                }
                opts.toBundle()
            } catch (_: Exception) { null }
            pi.send(this, 0, null, null, null, null, optsBundle)
            Log.i(TAG, "NLS relay PI sent for $pkg url=$url")
            true
        } catch (e: Exception) {
            Log.e(TAG, "NLS relay PI send failed: ${e.message}")
            false
        }
    }

    fun invokeAction(notifKey: String, actionId: String, replyText: String?): Boolean {
        // Debug handlers take precedence so synthetic test notifications
        // can round-trip without needing a real PendingIntent.
        debugHandlers[notifKey]?.map?.get(actionId)?.let { handler ->
            try {
                handler(replyText)
                Log.i(TAG, "Invoked debug handler $actionId on $notifKey")
                return true
            } catch (e: Exception) {
                Log.e(TAG, "Debug handler failed: ${e.message}")
                return false
            }
        }

        val entry = pendingActions[notifKey]?.get(actionId) ?: run {
            Log.w(TAG, "No pending action for $notifKey/$actionId")
            return false
        }

        // open_phone: route through LaunchRelayActivity via a PendingIntent.
        // Direct startActivity from the NLS is blocked by Android 14+ BAL
        // (balDontBringExistingBackgroundTaskStackToFg) regardless of flags,
        // but a PendingIntent we create ourselves and send from FGS state with
        // MODE_BACKGROUND_ACTIVITY_START_ALLOWED in both creator and sender
        // modes IS allowed. This is the same workaround Wear OS uses.
        if (entry.kind == "open_phone") {
            return launchAppViaRelay(entry.pkg, entry.url)
        }

        val pi = entry.intent ?: return false

        // For "action" entries that are activity-launch PendingIntents, we
        // can't reliably fire them from a background NLS context — Android
        // 14+ BAL blocks them when the PI's creator didn't opt into
        // MODE_BACKGROUND_ACTIVITY_START_ALLOWED (which 3rd-party apps
        // mostly don't). Outlook's "Reply" action is the canonical example:
        // it launches NotificationReplyActivity and the BAL log shows
        // balDontBringExistingBackgroundTaskStackToFg=true rejection. Fall
        // back to opening the source app via the relay so the user can
        // complete the action in-app.
        if (entry.kind == "action" && entry.pkg != null && replyText == null) {
            val isActivity = if (android.os.Build.VERSION.SDK_INT >= 31) {
                try { pi.isActivity } catch (_: Throwable) { false }
            } else false
            if (isActivity) {
                Log.i(TAG, "Activity action PI on $actionId — routing via LaunchRelay (${entry.pkg})")
                return launchAppViaRelay(entry.pkg, null)
            }
        }

        return try {
            val intent = Intent()
            val remoteInputs = entry.remoteInputs
            if (remoteInputs != null && replyText != null) {
                val bundle = Bundle()
                for (ri in remoteInputs) {
                    bundle.putCharSequence(ri.resultKey, replyText)
                }
                val riArray = remoteInputs.map { ri ->
                    RemoteInput.Builder(ri.resultKey).setLabel(ri.label).build()
                }.toTypedArray()
                RemoteInput.addResultsToIntent(riArray, intent, bundle)
            }
            // Reply PendingIntents usually go to a broadcast receiver, not an
            // activity, so BAL doesn't apply — but set the allow-BAL bundle
            // anyway in case the app's RemoteInput path is activity-based.
            val optsBundle = try {
                val opts = android.app.ActivityOptions.makeBasic()
                if (android.os.Build.VERSION.SDK_INT >= 34) {
                    opts.pendingIntentBackgroundActivityStartMode =
                        android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                } else if (android.os.Build.VERSION.SDK_INT >= 33) {
                    @Suppress("DEPRECATION")
                    opts.setPendingIntentBackgroundActivityLaunchAllowed(true)
                }
                opts.toBundle()
            } catch (_: Exception) { null }

            pi.send(this, 0, intent, null, null, null, optsBundle)
            Log.i(TAG, "Invoked action $actionId on $notifKey${if (replyText != null) " with reply" else ""}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "invokeAction failed: ${e.message}")
            false
        }
    }

    // Pull a preview image out of the notification if it has one.
    // Tries EXTRA_PICTURE (big-picture style — used by Messages MMS/RCS,
    // Discord image messages, Home camera snapshots) then EXTRA_LARGE_ICON_BIG.
    // Falls back to fetching a YouTube CDN thumbnail when the title/text
    // contains a YouTube URL (covers Shorts, which don't ship a Bitmap
    // in extras at all). Downscales to ~200px max edge and JPEG-compresses
    // so the total base64 payload stays under ~20 KB to keep BT latency
    // acceptable.
    private fun encodeBigPicture(
        notification: Notification,
        extras: Bundle,
        title: String,
        text: String,
        notifKey: String,
        tag: String?
    ): String? {
        val bmp: android.graphics.Bitmap? =
            extras.getParcelable(Notification.EXTRA_PICTURE) as? android.graphics.Bitmap
                ?: extras.getParcelable(Notification.EXTRA_LARGE_ICON_BIG) as? android.graphics.Bitmap
        if (bmp != null && bmp.width > 0 && bmp.height > 0) {
            return encodeScaledBitmapToBase64(bmp)
        }

        // YouTube fallback. Shorts notifications don't ship a Bitmap
        // in EXTRA_PICTURE / EXTRA_LARGE_ICON_BIG, and Shorts also
        // don't include the video URL in title/text — the URL lives
        // in the contentIntent's underlying Intent, which we reach
        // via reflection through findVideoIdInNotification().
        val videoId = com.glasshole.phone.util.YouTubeThumbnail
            .findVideoIdInNotification(notification, extras, title, text, tag)
            ?: return null

        // Sync cache hit (no network) — instant render.
        com.glasshole.phone.util.YouTubeThumbnail
            .getCachedEncodedPicture(this, videoId)
            ?.takeIf { it.isNotEmpty() }
            ?.let { return it }

        // Cache miss: kick off an async fetch so the glass card can
        // swap in the real thumb once it lands. The card still gets
        // a small avatar in the title row via `title_icon` (a
        // separate field), so it's never visually empty.
        Log.i(TAG, "yt-thumb cache miss for $videoId — async fetching")
        scheduleYtThumbFetch(videoId, notifKey)
        return null
    }

    /**
     * Encodes the best available channel/sender avatar as a small
     * base64 JPEG for the title-row slot on the glass card. Tries
     * sources in order — most-specific first:
     *
     *   1. EXTRA_LARGE_ICON Bitmap — the classic channel/sender icon
     *      (Slack, Discord, GitHub, Spotify, YouTube, etc.)
     *   2. notification.getLargeIcon() Icon — same slot but as the
     *      Icon-typed alternative used by newer apps (Outlook,
     *      Gmail, Messages on more recent Android)
     *   3. Last MessagingStyle message's senderPerson avatar — for
     *      DM-style apps (X, Telegram, Signal) where the per-message
     *      avatar lives inside EXTRA_MESSAGES instead of the
     *      notification root
     *   4. EXTRA_PEOPLE_LIST first Person's icon — fallback for
     *      contact-aware apps that don't ship a MessagingStyle
     *
     * Returns null only when none of the above produce an Icon /
     * Bitmap; the glass card then shows a blank avatar slot rather
     * than something misleading.
     */
    private fun encodeTitleIcon(
        pkg: String,
        notification: Notification,
        extras: Bundle
    ): String? {
        // 1. EXTRA_LARGE_ICON — Outlook stores this as an Icon while
        //    Slack / Discord / Spotify store as a Bitmap. Read the raw
        //    object and branch on actual type so we catch both.
        extrasAvatar(extras, Notification.EXTRA_LARGE_ICON)
            ?.let { encodeAvatarBitmap(it)?.let { b64 -> return b64 } }

        // 2. EXTRA_LARGE_ICON_BIG — same dual-type handling. Some apps
        //    only set the big variant.
        extrasAvatar(extras, Notification.EXTRA_LARGE_ICON_BIG)
            ?.let { encodeAvatarBitmap(it)?.let { b64 -> return b64 } }

        // 3. Notification-level Icon (API 23+) — for apps that set
        //    via Builder.setLargeIcon(Icon) but didn't mirror to extras.
        notification.getLargeIcon()?.let { iconToBitmap(it) }
            ?.let { encodeAvatarBitmap(it)?.let { b64 -> return b64 } }

        // 3. Last-message senderPerson from MessagingStyle (API 28+
        //    style key for the per-message Bundle[]).
        try {
            val msgs = extras.getParcelableArray(Notification.EXTRA_MESSAGES)
            if (msgs != null) {
                // Iterate newest → oldest so the top-of-the-stack
                // sender's avatar wins.
                for (i in msgs.indices.reversed()) {
                    val mb = msgs[i] as? Bundle ?: continue
                    val person = mb.getParcelable<android.app.Person>("sender_person") ?: continue
                    val icon = person.icon ?: continue
                    iconToBitmap(icon)
                        ?.let { encodeAvatarBitmap(it)?.let { b64 -> return b64 } }
                }
            }
        } catch (_: Exception) { /* fall through */ }

        // 4. EXTRA_PEOPLE_LIST — first Person's icon.
        try {
            val peopleList = extras.getParcelableArrayList<android.app.Person>(
                Notification.EXTRA_PEOPLE_LIST
            )
            peopleList?.firstOrNull()?.icon?.let { iconToBitmap(it) }
                ?.let { encodeAvatarBitmap(it)?.let { b64 -> return b64 } }
        } catch (_: Exception) { /* fall through */ }

        // 5. App icon — last-resort fallback so the title slot is
        //    always populated. Outlook (and any other app whose
        //    notifications get image-reduced by the system before
        //    they reach us — keys include android.reduced.images
        //    when this happened) can't surface the real sender
        //    avatar; rendering the source app's icon at least
        //    anchors the card visually.
        return try {
            val drawable = packageManager.getApplicationIcon(pkg)
            drawableToBitmap(drawable, 96)?.let { encodeAvatarBitmap(it) }
        } catch (_: Exception) {
            null
        }
    }

    private fun drawableToBitmap(
        drawable: android.graphics.drawable.Drawable,
        sizePx: Int
    ): android.graphics.Bitmap? {
        return try {
            val bmp = android.graphics.Bitmap.createBitmap(
                sizePx, sizePx, android.graphics.Bitmap.Config.ARGB_8888
            )
            val canvas = android.graphics.Canvas(bmp)
            drawable.setBounds(0, 0, sizePx, sizePx)
            drawable.draw(canvas)
            bmp
        } catch (e: Exception) {
            Log.w(TAG, "drawableToBitmap failed: ${e.message}")
            null
        }
    }

    /** Return a Bitmap for whatever EXTRA_LARGE_ICON / EXTRA_LARGE_ICON_BIG
     *  contains — Bitmap directly, or Icon rasterised through
     *  [iconToBitmap]. Returns null if the slot is empty or holds
     *  something we don't know how to render. */
    private fun extrasAvatar(extras: Bundle, key: String): android.graphics.Bitmap? {
        val raw = extras.get(key) ?: return null
        return when (raw) {
            is android.graphics.Bitmap -> raw
            is android.graphics.drawable.Icon -> iconToBitmap(raw)
            else -> {
                Log.w(TAG, "Unexpected $key type: ${raw.javaClass.name}")
                null
            }
        }
    }

    /** Print every avatar-relevant slot for a notification that didn't
     *  produce a title icon. Logs once per failing notif so we can
     *  reverse-engineer how X / Outlook / others ship their sender
     *  avatars. */
    private fun debugLogAvatarSources(
        pkg: String,
        notification: Notification,
        extras: Bundle
    ) {
        // Force the right ClassLoader for unparcelling — when extras
        // is read across an app boundary the default loader can fail
        // to resolve framework parcelables and silently return null.
        try { extras.classLoader = this.classLoader } catch (_: Exception) {}
        val sb = StringBuilder("avatar sources empty for $pkg: ")
        val rawLarge = extras.get(Notification.EXTRA_LARGE_ICON)
        val rawLargeBig = extras.get(Notification.EXTRA_LARGE_ICON_BIG)
        sb.append("largeIconRawType=").append(rawLarge?.javaClass?.name ?: "null")
        sb.append(" largeIconBigRawType=").append(rawLargeBig?.javaClass?.name ?: "null")
        sb.append(" notif.largeIcon=")
            .append(notification.getLargeIcon() != null)
        sb.append(" pictureBitmap=")
            .append(extras.getParcelable<android.graphics.Bitmap>(Notification.EXTRA_PICTURE) != null)
        try {
            val msgs = extras.getParcelableArray(Notification.EXTRA_MESSAGES)
            sb.append(" msgs=").append(msgs?.size ?: 0)
            if (msgs != null && msgs.isNotEmpty()) {
                val mb = msgs.last() as? Bundle
                val person = mb?.getParcelable<android.app.Person>("sender_person")
                sb.append(" lastMsgPerson=").append(person != null)
                sb.append(" lastMsgPersonIcon=").append(person?.icon != null)
            }
        } catch (e: Exception) { sb.append(" msgsErr=${e.message}") }
        try {
            val people = extras.getParcelableArrayList<android.app.Person>(
                Notification.EXTRA_PEOPLE_LIST
            )
            sb.append(" peopleList=").append(people?.size ?: 0)
            sb.append(" firstPersonIcon=").append(people?.firstOrNull()?.icon != null)
        } catch (e: Exception) { sb.append(" peopleErr=${e.message}") }
        // Dump every Bundle key so we can spot custom fields these apps
        // might use.
        sb.append(" extrasKeys=").append(extras.keySet().joinToString(","))
        Log.i(TAG, sb.toString())
    }

    /** Convert an [android.graphics.drawable.Icon] to a Bitmap by
     *  rasterising whatever its underlying drawable resolves to.
     *  Returns null if the icon can't be loaded (e.g. resource
     *  reference into a package we can't access). */
    private fun iconToBitmap(icon: android.graphics.drawable.Icon): android.graphics.Bitmap? {
        return try {
            val drawable = icon.loadDrawable(this) ?: return null
            val w = drawable.intrinsicWidth.takeIf { it > 0 } ?: 96
            val h = drawable.intrinsicHeight.takeIf { it > 0 } ?: 96
            val bmp = android.graphics.Bitmap.createBitmap(
                w, h, android.graphics.Bitmap.Config.ARGB_8888
            )
            val canvas = android.graphics.Canvas(bmp)
            drawable.setBounds(0, 0, w, h)
            drawable.draw(canvas)
            bmp
        } catch (e: Exception) {
            Log.w(TAG, "iconToBitmap failed: ${e.message}")
            null
        }
    }

    /** Scale + JPEG-encode a bitmap for the title-icon slot. Same
     *  pipeline the EXTRA_LARGE_ICON path used; pulled out so all
     *  fallbacks share it. */
    private fun encodeAvatarBitmap(bmp: android.graphics.Bitmap): String? {
        if (bmp.width <= 0 || bmp.height <= 0) return null
        return try {
            val targetPx = 96
            val scale = targetPx.toFloat() / maxOf(bmp.width, bmp.height).toFloat()
            val scaled = if (scale < 1f) {
                val w = (bmp.width * scale).toInt().coerceAtLeast(1)
                val h = (bmp.height * scale).toInt().coerceAtLeast(1)
                android.graphics.Bitmap.createScaledBitmap(bmp, w, h, true)
            } else bmp
            val stream = java.io.ByteArrayOutputStream()
            scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, stream)
            if (scaled !== bmp) scaled.recycle()
            android.util.Base64.encodeToString(stream.toByteArray(), android.util.Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.w(TAG, "title icon encode failed: ${e.message}")
            null
        }
    }

    private fun encodeScaledBitmapToBase64(bmp: android.graphics.Bitmap): String? = try {
        val maxEdge = 200
        val scale = maxEdge.toFloat() / maxOf(bmp.width, bmp.height).toFloat()
        val scaled = if (scale < 1f) {
            val w = (bmp.width * scale).toInt().coerceAtLeast(1)
            val h = (bmp.height * scale).toInt().coerceAtLeast(1)
            android.graphics.Bitmap.createScaledBitmap(bmp, w, h, true)
        } else bmp
        val stream = java.io.ByteArrayOutputStream()
        scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 55, stream)
        if (scaled !== bmp) scaled.recycle()
        val bytes = stream.toByteArray()
        Log.i(TAG, "Picture preview: ${bytes.size} B")
        android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
    } catch (e: Exception) {
        Log.w(TAG, "Picture encode failed: ${e.message}")
        null
    }

    // --- YouTube thumbnail fallback ---

    private val ytThumbExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "GlassHole-YtThumb").apply { isDaemon = true }
    }
    private val ytThumbInflight = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** Background-fetch the thumbnail for [videoId], then re-fire
     *  [notifKey] so the glass card re-renders with the picture in
     *  place. */
    private fun scheduleYtThumbFetch(videoId: String, notifKey: String) {
        if (!ytThumbInflight.add(videoId)) return
        ytThumbExecutor.submit {
            try {
                val bytes = com.glasshole.phone.util.YouTubeThumbnail
                    .fetchAndCache(this, videoId)
                if (bytes != null) {
                    Log.i(TAG, "yt-thumb cached: $videoId (${bytes.size} B) — re-firing notif")
                    reprocessActiveNotification(notifKey)
                } else {
                    Log.i(TAG, "yt-thumb fetch returned nothing for $videoId")
                }
            } catch (e: Exception) {
                Log.w(TAG, "yt-thumb fetch crashed: ${e.message}")
            } finally {
                ytThumbInflight.remove(videoId)
            }
        }
    }

    /** Look up the still-active notification by key and run our
     *  forwarding logic over it again — this time the cache hit path
     *  in [encodeBigPicture] will produce a picture. */
    private fun reprocessActiveNotification(notifKey: String) {
        try {
            val active = activeNotifications ?: return
            val sbn = active.firstOrNull { it.key == notifKey } ?: run {
                Log.d(TAG, "yt-thumb update: notification $notifKey already gone")
                return
            }
            onNotificationPosted(sbn)
        } catch (e: Exception) {
            Log.w(TAG, "reprocessActiveNotification failed: ${e.message}")
        }
    }

    private val iconCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    private fun encodeAppIcon(pkg: String): String? {
        iconCache[pkg]?.let { return it }
        return try {
            val drawable = packageManager.getApplicationIcon(pkg)
            val targetPx = 64
            val bitmap = android.graphics.Bitmap.createBitmap(
                targetPx, targetPx, android.graphics.Bitmap.Config.ARGB_8888
            )
            val canvas = android.graphics.Canvas(bitmap)
            drawable.setBounds(0, 0, targetPx, targetPx)
            drawable.draw(canvas)
            val stream = java.io.ByteArrayOutputStream()
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, stream)
            bitmap.recycle()
            val base64 = android.util.Base64.encodeToString(
                stream.toByteArray(), android.util.Base64.NO_WRAP
            )
            iconCache[pkg] = base64
            base64
        } catch (e: Exception) {
            Log.w(TAG, "Icon encode failed for $pkg: ${e.message}")
            null
        }
    }

    override fun onNotificationRemoved(
        sbn: StatusBarNotification,
        rankingMap: android.service.notification.NotificationListenerService.RankingMap?,
        reason: Int
    ) {
        // Nav-end signal needs to fire on ANY removal — Google Maps cancels
        // its nav notification when the trip ends regardless of how the
        // OS labels the cancel reason.
        if (sbn.packageName == com.glasshole.phone.plugins.nav.NavPlugin.MAPS_PKG) {
            try {
                com.glasshole.phone.plugins.nav.NavPlugin.instance?.handleMapsRemoved()
            } catch (e: Exception) {
                Log.w(TAG, "Nav remove failed: ${e.message}")
            }
            return
        }

        // For everything else: only honor user-driven removals. Many apps
        // (Slack, Messages, Discord, etc.) cancel-and-repost notifications
        // as background updates — replacement, group optimization, channel
        // tweaks. Forwarding *those* removals to the glass made entries
        // vanish from the drawer the user never dismissed.
        //
        // Three reasons survive the filter:
        //   REASON_CANCEL          — user swiped on the phone shade
        //   REASON_CANCEL_ALL      — user cleared all
        //   REASON_LISTENER_CANCEL — our own glass-side dismiss action
        if (reason != REASON_CANCEL &&
            reason != REASON_CANCEL_ALL &&
            reason != REASON_LISTENER_CANCEL) {
            return
        }

        if (sbn.packageName in forwardedApps) {
            try { onNotifRemoved?.invoke(sbn.key) } catch (e: Exception) {
                Log.w(TAG, "Notif-removed callback failed: ${e.message}")
            }
        }
    }

    private fun findReplyAction(notification: Notification): Notification.Action? {
        val actions = notification.actions ?: return null
        // Prefer actions that carry a real RemoteInput we can fire. Fall back
        // to semantic-marked reply actions without RemoteInput — we can't do
        // voice for those but at least we know the user's intent.
        for (action in actions) {
            if (action.remoteInputs != null && action.remoteInputs.isNotEmpty()) {
                return action
            }
        }
        for (action in actions) {
            val dataOnly = try { action.dataOnlyRemoteInputs } catch (_: Throwable) { null }
            if (dataOnly != null && dataOnly.isNotEmpty()) return action
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            for (action in actions) {
                if (action.semanticAction == Notification.Action.SEMANTIC_ACTION_REPLY) {
                    return action
                }
            }
        }
        return null
    }

    /**
     * Diagnostic: dump everything we could route based on. Helps figure out
     * why Gmail / Outlook / other email-ish apps aren't exposing voice-reply-
     * capable actions the way Messenger and Telegram do.
     */
    private fun logActionStructure(notification: Notification, sourcePkg: String) {
        val actions = notification.actions ?: return
        for ((i, a) in actions.withIndex()) {
            val hasRi = (a.remoteInputs?.isNotEmpty() == true)
            val hasDataRi = try { a.dataOnlyRemoteInputs?.isNotEmpty() == true } catch (_: Throwable) { false }
            val semantic = if (Build.VERSION.SDK_INT >= 28) a.semanticAction else -1
            val isActivity = try { a.actionIntent?.isActivity == true } catch (_: Throwable) { false }
            Log.i(
                TAG,
                "[$sourcePkg] action#$i title=\"${a.title}\" hasRemoteInput=$hasRi " +
                    "hasDataRemoteInput=$hasDataRi semanticAction=$semantic isActivityPI=$isActivity"
            )
        }
    }

    fun sendReply(text: String): Boolean {
        val action = latestReplyAction ?: return false
        val remoteInputs = action.remoteInputs ?: return false

        return try {
            val intent = Intent()
            val bundle = Bundle()
            for (ri in remoteInputs) {
                bundle.putCharSequence(ri.resultKey, text)
            }
            val riArray = remoteInputs.map { ri ->
                RemoteInput.Builder(ri.resultKey).setLabel(ri.label).build()
            }.toTypedArray()
            RemoteInput.addResultsToIntent(riArray, intent, bundle)
            action.actionIntent.send(this, 0, intent)
            Log.i(TAG, "Direct Reply sent: $text")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Direct Reply FAILED: ${e.message}")
            latestReplyAction = null
            false
        }
    }

    fun setForwardedApps(apps: Set<String>) {
        forwardedApps = apps
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
            .putStringSet(PREF_FORWARDED_APPS, apps)
            .apply()
    }

    fun getForwardedApps(): Set<String> = forwardedApps

    fun setSilentAllowedApps(apps: Set<String>) {
        silentAllowedApps = apps
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
            .putStringSet(PREF_SILENT_ALLOWED_APPS, apps)
            .apply()
    }

    fun getSilentAllowedApps(): Set<String> = silentAllowedApps

    fun reloadForwardedApps() {
        loadForwardedApps()
    }

    private fun loadForwardedApps() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        forwardedApps = prefs.getStringSet(PREF_FORWARDED_APPS, emptySet()) ?: emptySet()
        silentAllowedApps = prefs.getStringSet(PREF_SILENT_ALLOWED_APPS, emptySet()) ?: emptySet()
    }
}
