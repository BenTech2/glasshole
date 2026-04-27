package com.glasshole.phone.plugindir.worker

import android.content.Context
import android.util.Log
import com.glasshole.phone.AppLog
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Fire an HTTP POST when a specific message arrives from the glass
 * plugin. Used by plugins like OpenClaw whose phone-side work is a
 * single API call per trigger event.
 *
 * Expected params JSON:
 * ```
 * {
 *   "trigger":       "KICKSTART",              # message type to listen for
 *   "url":           "https://...",            # fully-substituted URL
 *   "content_type":  "application/json",       # optional; defaults to json
 *   "body":          { ... } | "string",       # JSON object or raw string
 *   "timeout_ms":    8000                      # optional
 * }
 * ```
 *
 * The worker quietly swallows errors — it logs but doesn't propagate
 * failures back to the glass plugin (yet). If you want status feedback,
 * emit a message via the `emit` callback after the POST.
 */
class HttpPostPrimitive : WorkerPrimitive {

    companion object {
        private const val TAG = "HttpPostPrimitive"
        private const val DEFAULT_TIMEOUT_MS = 8_000
    }

    private var triggerType: String = ""
    private var url: String = ""
    private var contentType: String = "application/json"
    private var body: Any? = null
    private var timeoutMs: Int = DEFAULT_TIMEOUT_MS

    /** Single-thread pool so concurrent triggers don't stomp each other. */
    private val executor = Executors.newSingleThreadExecutor()

    private var emit: ((type: String, payload: String) -> Unit)? = null

    override fun start(
        context: Context,
        params: JSONObject,
        emit: (type: String, payload: String) -> Unit
    ) {
        this.triggerType = params.optString("trigger")
        this.url = params.optString("url")
        this.contentType = params.optString("content_type", "application/json")
        this.body = params.opt("body")
        this.timeoutMs = params.optInt("timeout_ms", DEFAULT_TIMEOUT_MS)
        this.emit = emit
        Log.i(TAG, "armed: trigger=$triggerType url=$url")
    }

    override fun onMessage(type: String, payload: String) {
        if (triggerType.isNotEmpty() && type != triggerType) return
        if (url.isEmpty()) return
        executor.execute { fire(payload) }
    }

    override fun stop() {
        try { executor.shutdownNow() } catch (_: Exception) {}
        emit = null
    }

    private fun fire(incomingPayload: String) {
        try {
            val effectiveBody = buildBody(incomingPayload)
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", contentType)
            conn.outputStream.use { it.write(effectiveBody.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            // Consume and close the stream so the connection can be pooled.
            try {
                (if (code in 200..299) conn.inputStream else conn.errorStream)?.use { it.readBytes() }
            } catch (_: Exception) {}
            conn.disconnect()
            AppLog.log(TAG, "POST $url → $code")
        } catch (e: Exception) {
            AppLog.log(TAG, "POST $url failed: ${e.message}")
        }
    }

    /**
     * If body is a JSONObject, serialize to string. If a string, use as-is.
     * Otherwise empty body.
     */
    private fun buildBody(incomingPayload: String): String = when (val b = body) {
        is JSONObject -> b.toString()
        is JSONArray -> b.toString()
        is String -> b
        null -> ""
        else -> b.toString()
    }
}
