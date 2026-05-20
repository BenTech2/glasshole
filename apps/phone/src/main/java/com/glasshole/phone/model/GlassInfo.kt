package com.glasshole.phone.model

import org.json.JSONObject

data class GlassInfo(
    val battery: Int = -1,
    val charging: Boolean = false,
    val model: String = "",
    val androidVersion: String = "",
    val serial: String = "",
    /** GlassHole version running on the glass (BuildConfig.VERSION_NAME).
     *  Empty when talking to a pre-1.0.3 glass build that didn't include
     *  this field in the heartbeat. */
    val appVersion: String = "",
    /** Glass-side product flavor: "launcher" (replaces stock Glass home)
     *  or "standalone" (sits alongside it). Empty on older glass builds. */
    val flavor: String = "",
) {
    companion object {
        fun fromJson(json: String): GlassInfo {
            return try {
                val obj = JSONObject(json)
                GlassInfo(
                    battery = obj.optInt("battery", -1),
                    charging = obj.optBoolean("charging", false),
                    model = obj.optString("model", ""),
                    androidVersion = obj.optString("android", ""),
                    serial = obj.optString("serial", ""),
                    appVersion = obj.optString("app_version", ""),
                    flavor = obj.optString("flavor", ""),
                )
            } catch (_: Exception) {
                GlassInfo()
            }
        }
    }
}
