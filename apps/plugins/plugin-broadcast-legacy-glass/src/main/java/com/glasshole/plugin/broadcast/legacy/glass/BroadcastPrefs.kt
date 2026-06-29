package com.glasshole.plugin.broadcast.legacy.glass

import android.content.Context

/**
 * Last-known broadcast config mirrored on the glass from the phone.
 *
 * Values land in [PREFS_NAME] via the SDK's PluginConfigHandler on
 * every CONFIG_WRITE — the helper dumps schema keys verbatim, so
 * fields like `resolution` arrive as a "WxH" string and `fps` as a
 * "24"/"30" string. We parse them here in [load] so the caller still
 * gets clean ints out the other side.
 */
object BroadcastPrefs {

    const val PREFS_NAME = BroadcastGlassPluginService.PREFS_NAME

    data class Config(
        val url: String,
        val width: Int,
        val height: Int,
        val fps: Int,
        val bitrateKbps: Int,
        val audio: Boolean,
        val displayMode: String
    )

    fun load(context: Context): Config? {
        val p = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val url = p.getString("url", "")?.trim().orEmpty()
        if (url.isEmpty()) return null
        // Conservative defaults match what the old hand-rolled CONFIG
        // path used — 480p24 streams cleanly over the OMAP4 / early
        // Snapdragon SoCs without throttling the camera pipeline.
        val (w, h) = parseResolution(p.getString("resolution", "") ?: "")
            ?: Pair(854, 480)
        val fps = (p.getString("fps", null)?.toIntOrNull())
            ?: p.getInt("fps", 24)  // older builds wrote fps as an int
        return Config(
            url = url,
            width = w,
            height = h,
            fps = fps,
            bitrateKbps = p.getInt("bitrate_kbps", 800),
            audio = p.getBoolean("audio", true),
            displayMode = p.getString("display", "viewfinder") ?: "viewfinder"
        )
    }

    private fun parseResolution(raw: String): Pair<Int, Int>? {
        val parts = raw.split("x")
        if (parts.size != 2) return null
        val w = parts[0].trim().toIntOrNull() ?: return null
        val h = parts[1].trim().toIntOrNull() ?: return null
        return Pair(w, h)
    }
}
