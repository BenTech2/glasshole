package com.glasshole.phone.plugin

import android.content.Context
import com.glasshole.sdk.PluginMessage

typealias PluginSender = (PluginMessage) -> Boolean

/**
 * In-process plugin running inside the main GlassHole phone app.
 * Replaces the AIDL-bound PhonePluginService from the old multi-APK model.
 */
interface PhonePlugin {
    val pluginId: String

    fun onCreate(context: Context, sender: PluginSender) {}
    fun onDestroy() {}

    fun onMessageFromGlass(message: PluginMessage)
    fun onGlassConnectionChanged(connected: Boolean) {}
}
