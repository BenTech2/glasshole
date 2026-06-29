// SPDX-License-Identifier: MIT
package com.glasshole.phone.plugins.skytrack

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import androidx.core.content.ContextCompat
import com.glasshole.phone.AppLog
import com.glasshole.phone.plugin.PhonePlugin
import com.glasshole.phone.plugin.PluginSender
import com.glasshole.sdk.PluginMessage
import org.json.JSONArray
import org.json.JSONObject

/**
 * Phone-side counterpart for plugin-skytrack-glass.
 *
 *   REQ_TRACKER_START  → start polling OpenSky every `poll_interval_s`
 *                        seconds inside the bounding box implied by
 *                        the user's current GPS + `range_km`.
 *   REQ_TRACKER_STOP   → stop the loop (saves quota + battery).
 *
 * Responses to glass:
 *   AIRCRAFT_UPDATE { obs:{lat,lon}, ac:[{cs,lat,lon,alt,hdg,spd,co}…] }
 *   TRACKER_STATUS  { msg: "human text" }
 *
 * Settings come from the glass-side `skytrack_settings` prefs that
 * the dynamic schema UI writes via the standard SCHEMA_REQ /
 * CONFIG_WRITE flow. The glass writes; the phone reads back from
 * the glass at poll time by including the values in REQ_TRACKER_START.
 * Since we don't have that round-trip wired yet, we just read the
 * defaults — same values the user sees in the settings UI.
 */
class SkyTrackPhonePlugin : PhonePlugin {

    companion object {
        private const val TAG = "SkyTrackPhonePlugin"
    }

    override val pluginId: String = "skytrack"

    private lateinit var appContext: Context
    private var sender: PluginSender? = null
    private var loopThread: HandlerThread? = null
    private var loopHandler: Handler? = null
    @Volatile private var running: Boolean = false

    private val client = OpenSkyClient()

    /** Tracker configuration — pulled from the glass-side schema
     *  defaults for now. */
    private var rangeKm: Double = 50.0
    private var minAltitudeFt: Int = 0
    private var pollIntervalMs: Long = 30_000L

    override fun onCreate(context: Context, sender: PluginSender) {
        appContext = context.applicationContext
        this.sender = sender
    }

    override fun onDestroy() {
        stopLoop()
    }

    override fun onMessageFromGlass(message: PluginMessage) {
        when (message.type) {
            "REQ_TRACKER_START" -> {
                applySettings(message.payload)
                startLoop()
            }
            "REQ_TRACKER_STOP" -> stopLoop()
        }
    }

    /** Settings payload may be empty (initial REQ_TRACKER_START) or a
     *  JSON blob the glass shipped along — accept either. */
    private fun applySettings(payload: String) {
        if (payload.isBlank()) return
        try {
            val obj = JSONObject(payload)
            rangeKm = obj.optDouble("range_km", rangeKm)
            minAltitudeFt = obj.optInt("min_altitude_ft", minAltitudeFt)
            pollIntervalMs = obj.optLong("poll_interval_s", pollIntervalMs / 1000L) * 1000L
        } catch (_: Throwable) { /* ignore */ }
    }

    private fun startLoop() {
        if (running) return
        running = true
        val t = HandlerThread("SkyTrack-Phone").also { it.start() }
        loopThread = t
        loopHandler = Handler(t.looper).also { h ->
            h.post(loopTick)
        }
        AppLog.log(TAG, "Polling started: range=${rangeKm}km interval=${pollIntervalMs/1000}s")
        sendStatus("Started")
    }

    private fun stopLoop() {
        if (!running) return
        running = false
        loopHandler?.removeCallbacksAndMessages(null)
        loopThread?.quitSafely()
        loopThread = null
        loopHandler = null
        AppLog.log(TAG, "Polling stopped")
    }

    private val loopTick = object : Runnable {
        override fun run() {
            if (!running) return
            try {
                pollOnce()
            } catch (e: Throwable) {
                AppLog.log(TAG, "Poll iteration failed: ${e.message}")
            } finally {
                loopHandler?.postDelayed(this, pollIntervalMs)
            }
        }
    }

    private fun pollOnce() {
        val loc = readLocation()
        if (loc == null) {
            sendStatus("No GPS on phone — share location to enable")
            return
        }
        val bbox = OpenSkyClient.bbox(loc.latitude, loc.longitude, rangeKm)
        val states = client.fetchStates(
            latMin = bbox[0], lonMin = bbox[1],
            latMax = bbox[2], lonMax = bbox[3],
        )
        if (states == null) {
            sendStatus("OpenSky unreachable — retrying")
            return
        }
        val filtered = states.filter { ac ->
            val altM = ac.altMeters
            if (minAltitudeFt <= 0) return@filter true
            altM != null && altM * 3.281 >= minAltitudeFt
        }
        shipFeed(loc, filtered)
    }

    private fun readLocation(): Location? {
        val fineGranted = ContextCompat.checkSelfPermission(
            appContext, Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        val coarseGranted = ContextCompat.checkSelfPermission(
            appContext, Manifest.permission.ACCESS_COARSE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        if (!fineGranted && !coarseGranted) return null
        val lm = appContext.getSystemService(Context.LOCATION_SERVICE)
            as? LocationManager ?: return null
        return try {
            @Suppress("MissingPermission")
            val gps = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            @Suppress("MissingPermission")
            val net = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            listOfNotNull(gps, net).maxByOrNull { it.time }
        } catch (e: SecurityException) {
            null
        }
    }

    private fun shipFeed(obs: Location, states: List<OpenSkyClient.State>) {
        val ac = JSONArray()
        for (s in states) {
            ac.put(JSONObject().apply {
                put("cs", s.callsign)
                put("lat", s.lat)
                put("lon", s.lon)
                if (s.altMeters != null) put("alt", s.altMeters)
                if (s.headingDeg != null) put("hdg", s.headingDeg)
                if (s.velocityMps != null) put("spd", s.velocityMps)
                if (s.originCountry.isNotBlank()) put("co", s.originCountry)
            })
        }
        val payload = JSONObject().apply {
            put("obs", JSONObject().apply {
                put("lat", obs.latitude)
                put("lon", obs.longitude)
            })
            put("ac", ac)
            put("t", System.currentTimeMillis())
        }.toString()
        val ok = sender?.invoke(PluginMessage("AIRCRAFT_UPDATE", payload)) ?: false
        AppLog.log(TAG, "AIRCRAFT_UPDATE n=${states.size} sent=$ok")
    }

    private fun sendStatus(msg: String) {
        val payload = JSONObject().put("msg", msg).toString()
        sender?.invoke(PluginMessage("TRACKER_STATUS", payload))
    }
}
