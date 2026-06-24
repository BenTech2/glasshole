package com.glasshole.phone.plugins.nav

import android.content.Context
import com.glasshole.phone.AppLog
import com.glasshole.phone.plugin.PhonePlugin
import com.glasshole.phone.plugin.PluginSender
import com.glasshole.sdk.PluginMessage

/**
 * Phone-side counterpart for the glass GlassNav plugin. Currently
 * just handles `REQ_GPS_START` / `REQ_GPS_STOP` messages that
 * MainActivity sends from its onResume/onDestroy — driving the
 * GPS stream is the only thing the glass needs from us that isn't
 * already going through ShareDirectionsActivity or the companion.
 *
 * The rest of the dataplane (Maps-notification scrape → NAV_UPDATE,
 * companion search → DEST, etc.) is owned by NavPlugin /
 * GlassNavCompanionActivity / ShareDirectionsActivity — this class
 * exists only to receive `glassnav` plugin messages from glass.
 */
class GlassNavPhonePlugin : PhonePlugin {

    companion object { private const val TAG = "GlassNavPhonePlugin" }

    override val pluginId: String = "glassnav"

    private lateinit var appContext: Context

    override fun onCreate(context: Context, sender: PluginSender) {
        appContext = context.applicationContext
    }

    override fun onMessageFromGlass(message: PluginMessage) {
        when (message.type) {
            "REQ_GPS_START" -> {
                AppLog.log(TAG, "REQ_GPS_START — kicking SpeedTracker on")
                SpeedTracker.start(appContext)
            }
            "REQ_GPS_STOP" -> {
                AppLog.log(TAG, "REQ_GPS_STOP — stopping SpeedTracker")
                SpeedTracker.stop(appContext)
            }
            else -> { /* ignore */ }
        }
    }
}
