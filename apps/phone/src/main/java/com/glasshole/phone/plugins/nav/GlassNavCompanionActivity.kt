// SPDX-License-Identifier: MIT
package com.glasshole.phone.plugins.nav

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.glasshole.phone.AppLog
import com.glasshole.phone.R
import com.glasshole.phone.service.BridgeService
import com.glasshole.phone.service.PluginHostService
import org.json.JSONObject

/**
 * Phone-side companion for the glass GlassNav plugin. Replaces the
 * separate "GlassNav Companion" APK upstream uses — same role
 * (destination search + GPS streaming) but goes over the existing
 * GlassHole BT bridge instead of its own RFCOMM socket.
 *
 * Two surfaces:
 *   1. Search bar → Nominatim → result list → tap a result → ship as
 *      DEST message ({"n":"name","dn":"display","la":..,"lo":..,"di":..}).
 *      Glass-side MainActivity decodes the same JSON shape it used to
 *      get over the upstream RFCOMM socket.
 *   2. "Stream phone GPS to glass" toggle → starts [SpeedTracker]
 *      which encodes 32-byte binary LOC envelopes (lat/lon/alt
 *      double, speed/bearing float, big-endian) base64'd onto LOC
 *      plugin messages.
 */
class GlassNavCompanionActivity : AppCompatActivity() {

    companion object { private const val TAG = "GlassNavCompanion" }

    private lateinit var status: TextView
    private lateinit var query: EditText
    private lateinit var searchBtn: Button
    private lateinit var locStream: Switch
    private lateinit var results: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_glassnav_companion)
        status = findViewById(R.id.glassNavStatus)
        query = findViewById(R.id.glassNavQuery)
        searchBtn = findViewById(R.id.glassNavSearchBtn)
        locStream = findViewById(R.id.glassNavLocStream)
        results = findViewById(R.id.glassNavResults)

        // BridgeService keeps the BT pipe alive while we use it.
        startService(Intent(this, PluginHostService::class.java))

        searchBtn.setOnClickListener { triggerSearch() }
        query.setOnEditorActionListener { _, _, _ -> triggerSearch(); true }

        locStream.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) startStream() else stopStream()
        }
    }

    override fun onDestroy() {
        // Stop streaming when companion closes — user can re-enable
        // next time they open this screen.
        if (locStream.isChecked) SpeedTracker.stop(applicationContext)
        super.onDestroy()
    }

    private fun triggerSearch() {
        val q = query.text.toString().trim()
        if (q.isEmpty()) {
            status.text = "Type a destination first."
            return
        }
        status.text = "Searching…"
        results.removeAllViews()
        Thread {
            val hits = try { NavRouter.search(q) } catch (e: Exception) {
                AppLog.warn(TAG, "Search failed: ${e.message}"); emptyList()
            }
            Handler(Looper.getMainLooper()).post { renderResults(q, hits) }
        }.apply { isDaemon = true; name = "GlassNavSearch" }.start()
    }

    private fun renderResults(q: String, hits: List<NavRouter.GeoResult>) {
        if (hits.isEmpty()) {
            status.text = "No results for \"$q\"."
            return
        }
        status.text = "${hits.size} result${if (hits.size == 1) "" else "s"} — tap to send to glass."
        for (hit in hits) addResultRow(hit)
    }

    /** Single result row — keeps the layout in code so we don't need
     *  another XML file for what's a one-line item. */
    private fun addResultRow(hit: NavRouter.GeoResult) {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(10), dp(8), dp(10))
            isClickable = true
            background = androidx.core.content.ContextCompat
                .getDrawable(context, android.R.drawable.list_selector_background)
            setOnClickListener { sendDestination(hit) }
        }
        val name = TextView(this).apply {
            text = hit.shortName
            textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        val sub = TextView(this).apply {
            text = hit.displayName
            textSize = 11f
            setTextColor(0xFF90A4AE.toInt())
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        container.addView(name)
        container.addView(sub)
        val divider = View(this).apply {
            setBackgroundColor(0xFF263238.toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1
            )
        }
        results.addView(container)
        results.addView(divider)
    }

    private fun sendDestination(hit: NavRouter.GeoResult) {
        val bridge = BridgeService.instance
        if (bridge == null || !bridge.isConnected) {
            toast("Glass not connected — start GlassHole + pair first.")
            return
        }
        // Match the exact JSON shape upstream's GlassNav MainActivity
        // expects from the Companion app's RFCOMM socket:
        //   {"n":"short","dn":"display","la":<lat>,"lo":<lon>,"di":<distance_m>}
        // We don't know the user's current location at this moment in
        // the Companion (the glass already does), so distance is 0 —
        // RouteActivity on glass recomputes it from the user's live
        // fix anyway.
        val payload = JSONObject().apply {
            put("n", hit.shortName)
            put("dn", hit.displayName)
            put("la", hit.lat)
            put("lo", hit.lon)
            put("di", 0.0)
        }.toString()
        val ok = bridge.sendPluginMessage("glassnav", "DEST", payload)
        AppLog.log(TAG, "Sent DEST '${hit.shortName}' to glass (ok=$ok)")
        toast(
            if (ok) "Sent \"${hit.shortName}\" to Glass"
            else "Send failed — check BT link"
        )
    }

    private fun startStream() {
        val fine = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) {
            toast("Grant location permission first.")
            locStream.isChecked = false
            return
        }
        SpeedTracker.start(applicationContext)
        status.text = "Streaming phone GPS to glass."
        AppLog.log(TAG, "Location streaming ON")
    }

    private fun stopStream() {
        SpeedTracker.stop(applicationContext)
        status.text = "Search a destination + tap a result to send it to glass."
        AppLog.log(TAG, "Location streaming OFF")
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
