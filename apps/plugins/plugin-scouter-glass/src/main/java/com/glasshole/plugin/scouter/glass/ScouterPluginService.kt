package com.glasshole.plugin.scouter.glass

import android.util.Log
import com.glasshole.glass.sdk.GlassPluginMessage
import com.glasshole.glass.sdk.GlassPluginService

/**
 * The Scouter is a pure on-glass gimmick — there's no phone-side
 * counterpart yet, so this service exists mainly to register the
 * plugin in the directory (so it shows up in the app drawer and the
 * phone's plugin list). If we add phone-side controls later — say,
 * remote-trigger the scan, or exfiltrate captured "power level" logs
 * for posterity — they'd land here as new message types.
 */
class ScouterPluginService : GlassPluginService() {

    companion object {
        private const val TAG = "ScouterPlugin"
    }

    override val pluginId: String = "scouter"

    override fun onMessageFromPhone(message: GlassPluginMessage) {
        Log.d(TAG, "From phone: ${message.type}")
    }
}
