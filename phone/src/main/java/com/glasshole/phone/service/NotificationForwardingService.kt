package com.glasshole.phone.service

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Intent
import android.content.SharedPreferences
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
        val notification = sbn.notification ?: return
        val extras = notification.extras ?: return

        // Only forward notifications from user-selected apps. Default (empty) = none.
        if (pkg !in forwardedApps) return

        // Skip own notifications
        if (pkg == "com.glasshole.phone") return

        // Skip group summaries
        if ((notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return

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

        Log.i(TAG, "Forwarding: $appName | $title | $messageText")

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
            val pictureBase64 = encodeBigPicture(notification, extras)
            val json = JSONObject().apply {
                put("key", notifKey)
                put("pkg", pkg)
                put("app", appName)
                put("title", title)
                put("text", messageText)
                if (iconBase64 != null) put("icon", iconBase64)
                if (pictureBase64 != null) put("picture", pictureBase64)
                put("actions", actionsJson)
            }.toString()
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

        // 3. Open on phone — use a URL ACTION_VIEW when the notification has
        // one (YouTube videos, shared links, news alerts), otherwise fall back
        // to getLaunchIntentForPackage. Both go through LaunchRelayActivity
        // which bypasses the Android 14+ BAL restriction on starting another
        // app's activity from a background service context.
        val bigText = notification.extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
        val summary = notification.extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT)?.toString()
        val haystack = listOfNotNull(title, text, bigText, summary).joinToString("\n")
        val detected = detectFirstUrl(haystack)
        if (detected != null || packageManager.getLaunchIntentForPackage(sourcePkg) != null) {
            val id = "a${idx++}"
            out[id] = PendingAction(
                intent = null,
                remoteInputs = null,
                kind = "open_phone",
                pkg = sourcePkg,
                url = detected
            )
            add(id, "Open on Phone", "open_phone")
        }

        // 4. Remaining non-reply actions. Skip labels that duplicate gestures
        // the user already has on glass (swipe down dismisses, and the host
        // app marks notifications read when the card closes).
        notification.actions?.forEach { action ->
            val isReply = action.remoteInputs != null && action.remoteInputs.isNotEmpty()
            if (isReply) return@forEach  // already added above
            val label = action.title?.toString().orEmpty()
            if (isSuppressedActionLabel(label)) return@forEach
            val id = "a${idx++}"
            out[id] = PendingAction(action.actionIntent, null, kind = "action")
            add(id, label.ifEmpty { "Action" }, "action")
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
            return try {
                val relay = Intent().apply {
                    setClassName(this@NotificationForwardingService, "com.glasshole.phone.LaunchRelayActivity")
                    if (entry.pkg != null) putExtra("target_pkg", entry.pkg)
                    if (entry.url != null) putExtra("target_url", entry.url)
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_MULTIPLE_TASK or
                        Intent.FLAG_ACTIVITY_NEW_DOCUMENT
                    )
                }
                val pi = PendingIntent.getActivity(
                    this,
                    (entry.pkg ?: "").hashCode(),
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
                Log.i(TAG, "Relay PI sent for ${entry.pkg} url=${entry.url}")
                true
            } catch (e: Exception) {
                Log.e(TAG, "Relay PI send failed: ${e.message}")
                false
            }
        }

        val pi = entry.intent ?: return false
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
    // Downscales to ~200px max edge and JPEG-compresses so the total base64
    // payload stays under ~20 KB to keep BT latency acceptable.
    private fun encodeBigPicture(notification: Notification, extras: Bundle): String? {
        val bmp: android.graphics.Bitmap? =
            extras.getParcelable(Notification.EXTRA_PICTURE) as? android.graphics.Bitmap
                ?: extras.getParcelable(Notification.EXTRA_LARGE_ICON_BIG) as? android.graphics.Bitmap
        if (bmp == null || bmp.width <= 0 || bmp.height <= 0) return null
        return try {
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

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (sbn.key == latestNotificationKey) {
            // Keep the reply action even after removal — it often still works
        }
    }

    private fun findReplyAction(notification: Notification): Notification.Action? {
        val actions = notification.actions ?: return null
        for (action in actions) {
            if (action.remoteInputs != null && action.remoteInputs.isNotEmpty()) {
                return action
            }
        }
        return null
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

    fun reloadForwardedApps() {
        loadForwardedApps()
    }

    private fun loadForwardedApps() {
        forwardedApps = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getStringSet(PREF_FORWARDED_APPS, emptySet()) ?: emptySet()
    }
}
