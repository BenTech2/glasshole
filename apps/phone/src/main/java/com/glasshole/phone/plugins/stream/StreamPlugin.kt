package com.glasshole.phone.plugins.stream

import android.content.Context
import android.util.Log
import com.glasshole.phone.AppLog
import com.glasshole.phone.plugin.PhonePlugin
import com.glasshole.phone.plugin.PluginSender
import com.glasshole.sdk.PluginMessage
import org.json.JSONObject

class StreamPlugin : PhonePlugin {

    companion object {
        private const val TAG = "StreamPlugin"

        @Volatile
        var instance: StreamPlugin? = null
            private set
    }

    override val pluginId: String = "stream"

    private lateinit var sender: PluginSender

    override fun onCreate(context: Context, sender: PluginSender) {
        this.sender = sender
        instance = this
    }

    override fun onDestroy() {
        instance = null
    }

    override fun onMessageFromGlass(message: PluginMessage) {
        // Currently nothing flows back from glass; reserved for future status.
        Log.d(TAG, "Ignored message from glass: type=${message.type}")
    }

    fun sendUrl(url: String): Boolean {
        val payload = JSONObject().apply { put("url", url) }.toString()
        Log.i(TAG, "Forwarding URL to glass: $url")
        val ok = sender(PluginMessage("PLAY_URL", payload))
        AppLog.log("Stream", "PLAY_URL → glass: ${if (ok) "sent" else "DROPPED"} — $url")
        return ok
    }
}
