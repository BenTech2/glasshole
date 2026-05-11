package com.glasshole.glass.sdk

import android.content.Context
import android.net.wifi.WifiManager
import android.text.format.Formatter
import android.util.Log

/**
 * Glue between [CameraStreamer] (raw frames) and [MjpegHttpServer]
 * (network egress) for the "live camera over LAN" debug feature.
 *
 * Lifecycle:
 *   start()  → resolve Wi-Fi IP, lazily build server + streamer,
 *              fail with [Status.NoWifi] if not on a Wi-Fi LAN
 *   stop()   → tear both down so the camera HAL is freed
 *
 * Idempotent: repeated start/stop calls are safe and the [token]
 * stays stable across stream restarts so phone-side callers can reuse
 * the URL.
 *
 * Lives in the SDK so all three glass editions can share it; the
 * caller (BluetoothListenerService) just decides when to start/stop
 * and forwards the URL string to the phone.
 */
class CameraLiveSession(
    private val context: Context,
    /** CW degrees the phone viewer should rotate frames after decoding.
     *  EE1's sensor is mounted 90° off the display, so we advertise
     *  this in the stream URL (as a `&rot=N` query param) and let the
     *  phone apply a single Bitmap matrix rotation — cleaner than
     *  rotating NV21 bytes on the glass, which had a chroma-alignment
     *  bug that produced subtly stretched frames on some preview
     *  sizes. EE2 reports 0° natively so it stays the default. */
    private val rotationDegrees: Int = 0
) {

    sealed class Status {
        data class Started(val url: String) : Status()
        object NoWifi : Status()
        object CameraFailed : Status()
    }

    companion object {
        private const val TAG = "CameraLiveSession"
    }

    private var server: MjpegHttpServer? = null
    private var streamer: CameraStreamer? = null

    @Synchronized
    fun start(): Status {
        val ip = wifiIp() ?: return Status.NoWifi
        val srv = server ?: MjpegHttpServer().also {
            it.start()
            server = it
        }
        if (streamer == null) {
            val s = CameraStreamer(
                onFrame = { bytes -> srv.pushFrame(bytes) }
            )
            if (!s.start()) {
                // Camera HAL may already be in use; release the server
                // so we don't leak the listening socket.
                stopInternal()
                return Status.CameraFailed
            }
            streamer = s
        }
        val url = srv.streamUrl(ip).let {
            if (rotationDegrees != 0) "$it&rot=$rotationDegrees" else it
        }
        return Status.Started(url)
    }

    @Synchronized
    fun stop() {
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
