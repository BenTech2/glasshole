package com.glasshole.phone.plugins.nav

import android.content.Context

/**
 * Single-bit session flag controlling whether the phone-side
 * Google-Maps notification scrape ([NavPlugin]) should tee NAV_UPDATE
 * + NAV_END + start/stop the GPS speed source for the GlassNav
 * full-screen plugin.
 *
 * Set by [ShareDirectionsActivity] when the user picks "Navigate
 * on Glass" from Maps' share sheet. Cleared by [NavPlugin] when the
 * Maps nav notification disappears (trip ended) or by the user
 * stopping nav on glass.
 *
 * Keeping it as a SharedPreferences boolean (rather than a static)
 * means it survives process death — if BridgeService restarts mid-
 * trip, we don't lose the in-progress session.
 */
object GlassNavSession {
    private const val PREFS = "glassnav_session"
    private const val KEY_ACTIVE = "active"

    fun setActive(ctx: Context, active: Boolean) {
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ACTIVE, active).apply()
    }

    fun isActive(ctx: Context): Boolean =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ACTIVE, false)
}
