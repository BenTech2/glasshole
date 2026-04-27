package com.glasshole.phone.plugins.nav

import android.app.Notification
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.service.notification.StatusBarNotification
import android.util.Base64
import android.util.Log
import com.glasshole.phone.plugin.PhonePlugin
import com.glasshole.phone.plugin.PluginSender
import com.glasshole.sdk.PluginMessage
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/**
 * Phone-side Nav plugin. Google Maps posts an ongoing foreground
 * notification while navigating; NotificationForwardingService calls
 * [handleMapsNotification] whenever that notification is updated or
 * [handleMapsRemoved] when it's dismissed (nav ended).
 *
 * We extract the turn arrow (largeIcon), distance (title), next-step
 * instruction (text), and ETA (subtext) and push them to the Nav
 * glass plugin as a NAV_UPDATE payload. On removal we send NAV_END
 * so the glass UI can revert to its idle state.
 *
 * This is notification-scraping — Google doesn't expose a nav API.
 * Fragile across Google Maps updates and locales; worth keeping the
 * parsing defensive and the payload shape tolerant of blank fields.
 */
class NavPlugin : PhonePlugin {

    companion object {
        private const val TAG = "NavPlugin"
        const val MAPS_PKG = "com.google.android.apps.maps"
        private const val ICON_MAX_PX = 96

        @Volatile
        var instance: NavPlugin? = null
            private set
    }

    override val pluginId: String = "nav"

    private lateinit var appContext: Context
    private lateinit var sender: PluginSender

    // Dedupe: skip pushing a NAV_UPDATE if nothing visibly changed
    // since the last one. Google Maps refreshes the notification at
    // ~1 Hz; only a subset of those refreshes change distance/instruction.
    private var lastSignature: String = ""

    // Trip-progress tracking. Google Maps' notification only exposes the
    // remaining leg distance (turn-by-turn), not the total trip. We infer
    // progress by taking the largest remaining distance we've seen this
    // trip as the trip length: progress = 1 - (current / max). Resets on
    // NAV_END. This drifts if the trip restarts without NAV_END, but the
    // worst case is the bar briefly re-zeroes as the new max is observed.
    private var tripMaxMeters: Double = 0.0

    override fun onCreate(context: Context, sender: PluginSender) {
        this.appContext = context.applicationContext
        this.sender = sender
        instance = this
    }

    override fun onDestroy() {
        instance = null
    }

    override fun onGlassConnectionChanged(connected: Boolean) {
        if (!connected) {
            lastSignature = ""
            tripMaxMeters = 0.0
        }
    }

    override fun onMessageFromGlass(message: PluginMessage) {
        when (message.type) {
            "REFRESH" -> {
                // Home just opened and it wants the latest nav state. The
                // dedup cache means Maps' next ~1 Hz notification tick
                // wouldn't re-push otherwise, so clear the cache and
                // replay the current Maps notification if we can find it.
                lastSignature = ""
                replayCurrentMapsNotification()
            }
            else -> Log.d(TAG, "Ignoring message from glass: ${message.type}")
        }
    }

    private fun replayCurrentMapsNotification() {
        try {
            val listener = com.glasshole.phone.service.NotificationForwardingService.instance
                ?: return
            val active = listener.activeNotifications ?: return
            val maps = active.firstOrNull { it.packageName == MAPS_PKG } ?: run {
                // No active Maps notification — nav has actually ended. Tell
                // Home to drop the card.
                sender(PluginMessage("NAV_END", ""))
                return
            }
            handleMapsNotification(maps)
        } catch (e: Exception) {
            Log.w(TAG, "Nav REFRESH replay failed: ${e.message}")
        }
    }

    fun handleMapsNotification(sbn: StatusBarNotification) {
        val extras = sbn.notification.extras
        val distance = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val instruction = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        val eta = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()?.trim().orEmpty()

        if (distance.isEmpty() && instruction.isEmpty()) {
            // Not a nav-state notification — could be a place suggestion or
            // a summary card. Ignore rather than pushing empty updates.
            return
        }

        val iconB64 = extractLargeIconBase64(sbn.notification)
        val signature = "$distance|$instruction|$eta|${iconB64.length}"
        if (signature == lastSignature) return
        lastSignature = signature

        val remainingMeters = parseDistanceMeters(distance)
        val progress: Double = if (remainingMeters != null) {
            if (remainingMeters > tripMaxMeters) tripMaxMeters = remainingMeters
            if (tripMaxMeters > 0.0) {
                (1.0 - remainingMeters / tripMaxMeters).coerceIn(0.0, 1.0)
            } else 0.0
        } else -1.0 // signal "unknown" to the glass so it can hide the bar

        val payload = JSONObject().apply {
            put("distance", distance)
            put("instruction", instruction)
            put("eta", eta)
            put("progress", progress)
            if (iconB64.isNotEmpty()) put("icon", iconB64)
        }.toString()

        sender(PluginMessage("NAV_UPDATE", payload))
    }

    fun handleMapsRemoved() {
        if (lastSignature.isEmpty()) return
        lastSignature = ""
        tripMaxMeters = 0.0
        sender(PluginMessage("NAV_END", ""))
    }

    /**
     * Parse Google Maps' remaining-distance label into meters. Handles the
     * unit strings Maps actually emits: "0.5 mi", "500 ft", "1.2 km",
     * "500 m". Returns null on anything unparseable so the caller can skip
     * progress rather than compute a bogus value.
     */
    private fun parseDistanceMeters(raw: String): Double? {
        if (raw.isEmpty()) return null
        val cleaned = raw.trim().lowercase().replace(",", ".")
        val match = Regex("""([0-9]+(?:\.[0-9]+)?)\s*([a-z]+)""").find(cleaned) ?: return null
        val value = match.groupValues[1].toDoubleOrNull() ?: return null
        return when (match.groupValues[2]) {
            "mi" -> value * 1609.344
            "ft" -> value * 0.3048
            "km" -> value * 1000.0
            "m"  -> value
            else -> null
        }
    }

    private fun extractLargeIconBase64(notification: Notification): String {
        val bmp: Bitmap = try {
            val largeIconObj = notification.extras.get(Notification.EXTRA_LARGE_ICON)
            when (largeIconObj) {
                is Bitmap -> largeIconObj
                is Icon -> drawableToBitmap(largeIconObj.loadDrawable(appContext))
                else -> null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Icon extract failed: ${e.message}")
            null
        } ?: return ""

        val scaled = scaleToFit(bmp, ICON_MAX_PX)
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.PNG, 100, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    private fun drawableToBitmap(drawable: Drawable?): Bitmap? {
        if (drawable == null) return null
        if (drawable is BitmapDrawable) return drawable.bitmap
        val w = drawable.intrinsicWidth.coerceAtLeast(1)
        val h = drawable.intrinsicHeight.coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        return bmp
    }

    private fun scaleToFit(source: Bitmap, maxEdgePx: Int): Bitmap {
        val longest = maxOf(source.width, source.height)
        if (longest <= maxEdgePx) return source
        val ratio = maxEdgePx.toFloat() / longest
        val w = (source.width * ratio).toInt().coerceAtLeast(1)
        val h = (source.height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(source, w, h, true)
    }
}
