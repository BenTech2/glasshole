// SPDX-License-Identifier: MIT
package com.glasshole.phone.plugins.nav

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Patterns
import android.widget.Toast
import com.glasshole.phone.AppLog
import com.glasshole.phone.service.BridgeService
import com.glasshole.phone.service.PluginHostService
import org.json.JSONObject

/**
 * Share-sheet target for Google Maps URLs (and `geo:` / `maps.*`
 * links). Headless — extracts the destination from the share, opens
 * Google Maps on the phone with it, and ships the destination to
 * GlassNav on glass as a `DEST` plugin message.
 *
 * Glass-side `MainActivity` parses the DEST JSON identically to how
 * upstream's BluetoothMapsService delivered it from the Companion
 * APK, then launches `RouteActivity` to let the user pick walk /
 * bike / drive routing.
 *
 * Patterns handled:
 *  - https://maps.google.com/...
 *  - https://www.google.com/maps/...
 *  - https://maps.app.goo.gl/...   (Google's universal short link)
 *  - https://goo.gl/maps/...       (legacy short link)
 *  - geo:lat,lon                   (RFC 5870)
 */
class ShareDirectionsActivity : Activity() {

    companion object { private const val TAG = "ShareDirections" }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // First-step confirmation so we KNOW Android dispatched to us
        // when the user picked "Navigate on Glass". If this toast
        // doesn't appear, the share never reached this activity — the
        // intent filter / share-sheet item is the bug, not us.
        toast("GlassNav: received share")
        AppLog.log(
            TAG,
            "Share received: action=${intent?.action} type=${intent?.type} " +
                "data=${intent?.data} text-len=${intent?.getStringExtra(Intent.EXTRA_TEXT)?.length ?: 0}"
        )

        val url = extractUrl(intent)
        if (url == null) {
            AppLog.warn(TAG, "No URL found in share — action=${intent?.action}")
            toast("GlassNav: no Maps link in share")
            finishImmediately(); return
        }
        AppLog.log(TAG, "Share-to-GlassNav: $url")

        // Critical path runs on the main thread — fast, no network.
        // Pull the place name from the share text title (Google Maps
        // shares always put it on line 1 above the URL).
        val title = extractShareTitle(intent)
        startService(Intent(this, PluginHostService::class.java))

        // Auto-start GPS streaming to the glass. Without this,
        // GlassNav's `Utils.getRoute()` NPEs on `MainActivity.lastLocation`
        // when the user picks a travel mode (upstream's own FIXME).
        // The first fix arrives in 2–10 s, well before the user can
        // possibly tap walk/bike/drive in RouteActivity.
        try {
            SpeedTracker.start(applicationContext)
            AppLog.log(TAG, "Auto-started SpeedTracker for share-driven nav")
        } catch (e: Throwable) {
            AppLog.warn(TAG, "SpeedTracker.start threw: ${e.message}")
        }

        // (Intentionally NOT calling startMapsNavigation here — earlier
        // versions fired google.navigation:q=<dest> so phone Maps would
        // also start turn-by-turn, but that's annoying when the user
        // is wearing the glass and didn't ask for two nav screens.
        // Glass-side GlassNav does its own routing now via Valhalla;
        // the phone just streams GPS over BT and stays out of the way.)

        // Bring the GlassNav activity foreground on glass. The DEST
        // JSON below will follow as soon as we have coords.
        attemptOpenGlassNav(attemptsLeft = 10)

        toast(
            if (title.isNullOrBlank()) "GlassNav: sharing…"
            else "GlassNav: $title"
        )

        // Background: resolve the URL to lat/lon (NavRouter follows
        // short-link redirects + extracts @LAT,LON from the canonical
        // URL — works for businesses Nominatim doesn't know). Ship
        // the DEST JSON to glassnav.
        Thread {
            try {
                shipDestination(url, title)
            } catch (e: Throwable) {
                AppLog.warn(TAG, "shipDestination threw: ${e.message}")
                postToast("GlassNav: send failed (${e.javaClass.simpleName})")
            }
        }.apply { isDaemon = true; name = "GlassNavShipDest" }.start()

        // Theme.NoDisplay requires finish() before onResume returns.
        finishImmediately()
    }

    /** Resolve URL/title → lat/lon → build the DEST JSON matching
     *  upstream GlassNav's MainActivity protocol:
     *      {"n":"<short>","dn":"<display>","la":<lat>,"lo":<lon>,"di":<distance_m>}
     *  and ship it to the glassnav plugin.
     *
     *  Cold-start race: when GlassNav is closed and the share kicks off
     *  LAUNCH_PACKAGE + DEST back-to-back, the glass-side plugin
     *  process / service binding takes ~1-2 s to come up. If DEST hits
     *  the launcher before AIDL is bound AND before the plugin's
     *  broadcast receiver is registered, the message is silently
     *  dropped. The buffer in GlassNavPluginService can't catch this
     *  because the service isn't even running yet. Sleeping 2 s here
     *  gives the plugin process time to spawn + bind + register its
     *  receiver before we ship DEST. URL resolution (Nominatim /
     *  redirect-following) usually takes a chunk of that anyway so
     *  the perceived delay is minimal. */
    private fun shipDestination(url: String, title: String?) {
        try { Thread.sleep(2000L) } catch (_: InterruptedException) { return }
        val point = NavRouter.resolveDestination(url)
            ?: title?.let { NavRouter.resolveDestination(it) }
        if (point == null) {
            AppLog.warn(TAG, "Could not resolve destination from url='$url' title='$title'")
            postToast("GlassNav: couldn't find that destination")
            return
        }
        val shortName = title?.takeIf { it.isNotBlank() } ?: "Shared destination"
        val displayName = title?.takeIf { it.isNotBlank() }
            ?: "${point.lat.format(5)}, ${point.lon.format(5)}"

        val payload = JSONObject().apply {
            put("n", shortName)
            put("dn", displayName)
            put("la", point.lat)
            put("lo", point.lon)
            // Distance is recomputed on glass from the user's live
            // location — leave 0 here.
            put("di", 0.0)
        }.toString()

        // Wait up to 3s for the BT bridge to connect (we just opened
        // the glass activity; the launcher's discovery may still be
        // binding the new plugin).
        for (i in 0 until 12) {
            val bridge = BridgeService.instance
            if (bridge != null && bridge.isConnected) {
                val ok = bridge.sendPluginMessage("glassnav", "DEST", payload)
                AppLog.log(TAG, "Sent DEST to glassnav (ok=$ok): $payload")
                postToast(
                    if (ok) "Sent to GlassNav: $shortName"
                    else "GlassNav: send failed"
                )
                return
            }
            try { Thread.sleep(250L) } catch (_: InterruptedException) { return }
        }
        AppLog.warn(TAG, "BT bridge never came up — DEST not shipped")
        postToast("GlassNav: glass not connected")
    }

    /** LAUNCH_PACKAGE → glassnav plugin foreground on glass. */
    private fun attemptOpenGlassNav(attemptsLeft: Int) {
        val bridge = BridgeService.instance
        if (bridge != null && bridge.isConnected) {
            bridge.sendPluginMessage(
                "base", "LAUNCH_PACKAGE",
                JSONObject().put("pkg", "com.glasshole.plugin.glassnav.glass").toString()
            )
            AppLog.log(TAG, "Asked glass to launch GlassNav")
            return
        }
        if (attemptsLeft <= 0) {
            AppLog.warn(TAG, "Glass not connected when LAUNCH_PACKAGE attempted")
            return
        }
        Handler(Looper.getMainLooper()).postDelayed(
            { attemptOpenGlassNav(attemptsLeft - 1) }, 250L
        )
    }

    private fun finishImmediately() {
        // Theme.NoDisplay activities MUST finish() before onResume
        // returns or Android throws IllegalStateException.
        try { finish() } catch (_: Throwable) {}
    }

    /** Pull a place-name "title" out of the share envelope. Google
     *  Maps share text is typically two lines:
     *      "Pike Place Market\nhttps://maps.app.goo.gl/abc"
     *  EXTRA_SUBJECT sometimes mirrors the first line. */
    private fun extractShareTitle(intent: Intent?): String? {
        if (intent == null) return null
        intent.getStringExtra(Intent.EXTRA_SUBJECT)?.takeIf { it.isNotBlank() }
            ?.let { return it.trim() }
        val text = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        for (line in text.lines()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            if (trimmed.startsWith("http") || trimmed.startsWith("geo:")) continue
            return trimmed
        }
        return null
    }

    /** Pull a URL/geo: from the share. */
    private fun extractUrl(intent: Intent?): String? {
        if (intent == null) return null
        intent.data?.toString()?.takeIf { it.isNotBlank() }?.let { return it }
        val text = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        if (text.isNotEmpty()) {
            val matcher = Patterns.WEB_URL.matcher(text)
            if (matcher.find()) return matcher.group()
            if (text.startsWith("geo:")) return text.trim()
        }
        return null
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
    private fun postToast(msg: String) {
        Handler(Looper.getMainLooper()).post { toast(msg) }
    }
    private fun Double.format(digits: Int): String = String.format("%.${digits}f", this)
}
