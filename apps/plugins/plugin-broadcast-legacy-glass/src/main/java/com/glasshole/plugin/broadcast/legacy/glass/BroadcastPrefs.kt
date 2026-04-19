package com.glasshole.plugin.broadcast.legacy.glass

import android.content.Context
import org.json.JSONObject

/** Last-known broadcast config mirrored on the glass from the phone. */
object BroadcastPrefs {

    private const val PREFS_NAME = "broadcast_config"
    private const val KEY_URL = "url"
    private const val KEY_WIDTH = "width"
    private const val KEY_HEIGHT = "height"
    private const val KEY_FPS = "fps"
    private const val KEY_BITRATE_KBPS = "bitrate_kbps"
    private const val KEY_AUDIO = "audio"
    private const val KEY_DISPLAY = "display"

    data class Config(
        val url: String,
        val width: Int,
        val height: Int,
        val fps: Int,
        val bitrateKbps: Int,
        val audio: Boolean,
        val displayMode: String
    )

    fun save(context: Context, json: String) {
        val obj = JSONObject(json)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_URL, obj.optString(KEY_URL, ""))
            .putInt(KEY_WIDTH, obj.optInt(KEY_WIDTH, 854))
            .putInt(KEY_HEIGHT, obj.optInt(KEY_HEIGHT, 480))
            .putInt(KEY_FPS, obj.optInt(KEY_FPS, 24))
            .putInt(KEY_BITRATE_KBPS, obj.optInt(KEY_BITRATE_KBPS, 800))
            .putBoolean(KEY_AUDIO, obj.optBoolean(KEY_AUDIO, true))
            .putString(KEY_DISPLAY, obj.optString(KEY_DISPLAY, "viewfinder"))
            .apply()
    }

    fun load(context: Context): Config? {
        val p = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val url = p.getString(KEY_URL, "")?.trim().orEmpty()
        if (url.isEmpty()) return null
        return Config(
            url = url,
            // Conservative defaults for EE1 / XE hardware — 480p24 streams
            // cleanly over the OMAP4 / early Snapdragon SoCs without
            // throttling the camera pipeline.
            width = p.getInt(KEY_WIDTH, 854),
            height = p.getInt(KEY_HEIGHT, 480),
            fps = p.getInt(KEY_FPS, 24),
            bitrateKbps = p.getInt(KEY_BITRATE_KBPS, 800),
            audio = p.getBoolean(KEY_AUDIO, true),
            displayMode = p.getString(KEY_DISPLAY, "viewfinder") ?: "viewfinder"
        )
    }
}
