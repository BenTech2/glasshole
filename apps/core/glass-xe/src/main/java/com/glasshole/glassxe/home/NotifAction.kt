package com.glasshole.glassxe.home

import org.json.JSONArray

/**
 * One actionable button a notification advertised. Matches the subset of
 * fields the phone forwards — id, human label, semantic type (reply /
 * open_phone / open_glass_stream / etc.), and an optional URL payload.
 * Used by both the popup and the drawer so their options overlays can
 * offer the same interactions.
 */
data class NotifAction(
    val id: String,
    val label: String,
    val type: String,
    val url: String?
) {
    companion object {
        fun parseArray(json: String?): List<NotifAction> {
            if (json.isNullOrEmpty()) return emptyList()
            return try {
                val arr = JSONArray(json)
                val out = ArrayList<NotifAction>(arr.length())
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    out.add(
                        NotifAction(
                            id = o.optString("id"),
                            label = o.optString("label"),
                            type = o.optString("type"),
                            url = o.optString("url").takeIf { it.isNotEmpty() }
                        )
                    )
                }
                out
            } catch (_: Exception) {
                emptyList()
            }
        }
    }
}
