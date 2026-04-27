package com.glasshole.glassee2.home

import android.content.Context

/**
 * Home's small preferences store. For M1 just the timezone the phone
 * pushes us on connect — later this will grow (notifications filter,
 * card order, etc).
 */
object HomePrefs {

    private const val PREFS_NAME = "glasshole_home"
    private const val KEY_HOME_TZ = "home_tz"
    private const val KEY_ADMIN_PROMPTED = "admin_prompted"
    private const val KEY_STORAGE_PROMPTED = "storage_prompted"
    private const val KEY_WRITE_SETTINGS_PROMPTED = "write_settings_prompted"

    fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getTimezone(context: Context): String =
        prefs(context).getString(KEY_HOME_TZ, "") ?: ""

    fun setTimezone(context: Context, tzId: String) {
        prefs(context).edit().putString(KEY_HOME_TZ, tzId).apply()
    }

    /** True once the user has seen the device-admin prompt at least once. */
    fun hasPromptedForAdmin(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ADMIN_PROMPTED, false)

    fun markPromptedForAdmin(context: Context) {
        prefs(context).edit().putBoolean(KEY_ADMIN_PROMPTED, true).apply()
    }

    /** Clear the "already prompted" flag so the dialog shows again on
     *  the next HomeActivity open. Called from the phone's debug screen. */
    fun resetAdminPrompt(context: Context) {
        prefs(context).edit().remove(KEY_ADMIN_PROMPTED).apply()
    }

    /** True once the user has seen the storage-access prompt at least once. */
    fun hasPromptedForStorage(context: Context): Boolean =
        prefs(context).getBoolean(KEY_STORAGE_PROMPTED, false)

    fun markPromptedForStorage(context: Context) {
        prefs(context).edit().putBoolean(KEY_STORAGE_PROMPTED, true).apply()
    }

    /** True once the user has seen the WRITE_SETTINGS prompt at least once. */
    fun hasPromptedForWriteSettings(context: Context): Boolean =
        prefs(context).getBoolean(KEY_WRITE_SETTINGS_PROMPTED, false)

    fun markPromptedForWriteSettings(context: Context) {
        prefs(context).edit().putBoolean(KEY_WRITE_SETTINGS_PROMPTED, true).apply()
    }
}
