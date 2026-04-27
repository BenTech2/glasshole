package com.glasshole.glassee2.home

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64

/**
 * Current in-progress Google Maps navigation step, as scraped from the
 * phone's Maps notification. Empty when no trip is active.
 */
data class NavState(
    val active: Boolean = false,
    val distance: String = "",
    val instruction: String = "",
    val eta: String = "",
    val iconBitmap: Bitmap? = null,
    // Trip progress 0..1, or -1 when the phone couldn't parse distance.
    // Glass treats -1 / active=false as "hide the bar".
    val progress: Double = -1.0
) {
    companion object {
        val EMPTY = NavState()

        fun fromJson(payload: String, previous: NavState): NavState {
            val json = try { org.json.JSONObject(payload) } catch (_: Exception) {
                return previous
            }
            val iconB64 = json.optString("icon", "")
            val bmp = if (iconB64.isNotEmpty()) {
                try {
                    val clean = iconB64.replace("\\", "")
                    val bytes = Base64.decode(clean, Base64.DEFAULT)
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: previous.iconBitmap
                } catch (_: Exception) {
                    previous.iconBitmap
                }
            } else previous.iconBitmap
            return NavState(
                active = true,
                distance = json.optString("distance", ""),
                instruction = json.optString("instruction", ""),
                eta = json.optString("eta", ""),
                iconBitmap = bmp,
                progress = json.optDouble("progress", -1.0)
            )
        }
    }
}
