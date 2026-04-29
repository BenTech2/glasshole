package com.glasshole.glassee2

import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.text.format.Formatter
import android.util.Log
import com.glasshole.glass.sdk.MjpegHttpServer

/**
 * EE2-only twin of [com.glasshole.glass.sdk.CameraLiveSession] for
 * MediaProjection-based screen capture. Lives here (not in the SDK)
 * because the SDK targets API 19 and MediaProjection is API 21+.
 *
 * Caller is responsible for first launching ProjectionConsentActivity
 * to get a result intent — without consent we can never start.
 */
class ScreenLiveSession(private val context: Context) {

    sealed class Status {
        data class Started(val url: String) : Status()
        object NoWifi : Status()
        object NoConsent : Status()
        object CaptureFailed : Status()
    }

    companion object {
        private const val TAG = "ScreenLiveSession"
    }

    private var server: MjpegHttpServer? = null
    private var streamer: ScreenStreamer? = null
    /** Filled in by the consent activity — null until granted. */
    @Volatile private var pendingResultCode: Int = 0
    @Volatile private var pendingResultData: Intent? = null

    fun setConsent(resultCode: Int, data: Intent?) {
        pendingResultCode = resultCode
        pendingResultData = data
    }

    @Synchronized
    fun start(onUserRevoked: () -> Unit): Status {
        val code = pendingResultCode
        val data = pendingResultData
        if (code == 0 || data == null) return Status.NoConsent

        val ip = wifiIp() ?: return Status.NoWifi
        val srv = server ?: MjpegHttpServer().also {
            it.start()
            server = it
        }
        if (streamer == null) {
            val s = ScreenStreamer(
                context = context,
                onFrame = { bytes -> srv.pushFrame(bytes) },
                onStopped = {
                    // Wrap so caller can react (send LIVE_SCREEN_ERR
                    // or just clean up). Don't recurse into stop()
                    // here — the streamer already released itself.
                    streamer = null
                    pendingResultData = null
                    pendingResultCode = 0
                    try { onUserRevoked() } catch (_: Exception) {}
                }
            )
            if (!s.start(code, data)) {
                stopInternal()
                return Status.CaptureFailed
            }
            streamer = s
        }
        return Status.Started(srv.streamUrl(ip))
    }

    @Synchronized
    fun stop() {
        // Always invalidate the consent — MediaProjection grants are
        // single-use on Android 14+, and on older versions the user
        // expects "stop sharing" to actually end the session.
        pendingResultData = null
        pendingResultCode = 0
        stopInternal()
    }

    private fun stopInternal() {
        try { streamer?.stop() } catch (e: Exception) {
            Log.w(TAG, "streamer.stop: ${e.message}")
        }
        streamer = null
        try { server?.stop() } catch (e: Exception) {
            Log.w(TAG, "server.stop: ${e.message}")
        }
        server = null
    }

    private fun wifiIp(): String? {
        return try {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val ip = wifi.connectionInfo?.ipAddress ?: 0
            if (ip == 0) null else Formatter.formatIpAddress(ip)
        } catch (_: Exception) { null }
    }
}
