package com.glasshole.phone.service

import android.app.Notification
import android.app.RemoteInput
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

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

        // Capture reply action
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

        val rich = onRichNotificationForGlass
        if (rich != null) {
            val iconBase64 = encodeAppIcon(pkg)
            rich(pkg, appName, title, messageText, iconBase64)
        } else {
            onNotificationForGlass?.invoke(appName, title, messageText)
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
