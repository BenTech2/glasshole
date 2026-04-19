package com.glasshole.phone.model

import org.json.JSONObject

data class GlassInfo(
    val battery: Int = -1,
    val charging: Boolean = false,
    val model: String = "",
    val androidVersion: String = "",
    val serial: String = ""
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
                    serial = obj.optString("serial", "")
                )
            } catch (_: Exception) {
                GlassInfo()
            }
        }
    }
}
