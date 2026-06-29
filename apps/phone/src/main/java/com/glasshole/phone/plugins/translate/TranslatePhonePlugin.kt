// SPDX-License-Identifier: MIT
package com.glasshole.phone.plugins.translate

import android.content.Context
import android.util.Base64
import android.util.Log
import com.glasshole.phone.AppLog
import com.glasshole.phone.plugin.PhonePlugin
import com.glasshole.phone.plugin.PluginSender
import com.glasshole.sdk.PluginMessage
import com.google.mlkit.nl.translate.TranslateLanguage
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * Phone-side counterpart for `plugin-translate-glass`. The glass
 * activity captures a single camera frame on tap, JPEG-encodes it,
 * base64s it, and ships it here as `TRANSLATE_REQUEST`. We run
 * ML Kit on-device OCR + translation and ship the blocks back as
 * `TRANSLATE_RESULT`.
 *
 * Settings (source / target language, display mode) live in
 * SharedPreferences keyed by [PREFS_NAME]; the glass-side activity
 * polls them via the standard plugin SCHEMA / CONFIG_READ /
 * CONFIG_WRITE flow handled by the glass-side plugin service.
 */
class TranslatePhonePlugin : PhonePlugin {

    companion object {
        private const val TAG = "TranslatePhonePlugin"
        const val PREFS_NAME = "translate_settings"
        const val KEY_SOURCE_LANG = "source_lang"
        const val KEY_TARGET_LANG = "target_lang"
        const val KEY_DISPLAY_MODE = "display_mode"  // "overlay" | "cards"
        const val DEFAULT_SOURCE = TranslateLanguage.JAPANESE
        const val DEFAULT_TARGET = TranslateLanguage.ENGLISH
        const val DEFAULT_DISPLAY = "overlay"
    }

    override val pluginId: String = "translate"

    private lateinit var appContext: Context
    private lateinit var sender: PluginSender
    /** Single-thread executor — ML Kit Tasks.await() blocks and we
     *  don't want to stall the BT reader thread. */
    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "TranslatePhonePlugin-worker").apply { isDaemon = true }
    }
    /** Cached engine — re-built when the user changes source
     *  language. Each engine wraps an ML Kit recognizer which is
     *  cheap-ish to keep around but should be closed if unused. */
    @Volatile private var engine: TranslateEngine? = null
    @Volatile private var engineSourceLang: String = ""

    override fun onCreate(context: Context, sender: PluginSender) {
        appContext = context.applicationContext
        this.sender = sender
    }

    override fun onDestroy() {
        engine?.close()
        engine = null
        worker.shutdown()
    }

    override fun onMessageFromGlass(message: PluginMessage) {
        when (message.type) {
            "TRANSLATE_REQUEST" -> handleTranslate(message.payload)
            else -> { /* ignore */ }
        }
    }

    private fun handleTranslate(payload: String) {
        // Off-thread — JPEG decode + OCR + N translations are all
        // CPU-heavy and would block the BT reader otherwise.
        worker.execute {
            try {
                // Payload is a JSON object: {src, tgt, jpeg(base64)}.
                // Glass owns the language settings (managed via the
                // dynamic-plugin schema UI); we just honour whatever
                // it sends.
                val obj = org.json.JSONObject(payload)
                val srcLang = obj.optString("src", DEFAULT_SOURCE)
                val tgtLang = obj.optString("tgt", DEFAULT_TARGET)
                val b64 = obj.optString("jpeg", "")
                if (b64.isBlank()) {
                    sendError("missing jpeg payload")
                    return@execute
                }

                val jpeg = try {
                    Base64.decode(b64, Base64.NO_WRAP or Base64.NO_PADDING)
                } catch (_: IllegalArgumentException) {
                    Base64.decode(b64, Base64.DEFAULT)
                }
                AppLog.log(TAG, "TRANSLATE_REQUEST: ${jpeg.size}-byte JPEG, $srcLang→$tgtLang")

                val eng = ensureEngine(srcLang)
                val blocks = eng.translate(jpeg, tgtLang)

                sendResult(blocks, srcLang, tgtLang)
            } catch (e: Throwable) {
                AppLog.log(TAG, "Translate failed: ${e.message}")
                sendError(e.message ?: "unknown error")
            }
        }
    }

    private fun ensureEngine(sourceLang: String): TranslateEngine {
        val existing = engine
        if (existing != null && engineSourceLang == sourceLang) return existing
        existing?.close()
        val fresh = TranslateEngine.forSource(sourceLang)
        engine = fresh
        engineSourceLang = sourceLang
        Log.i(TAG, "Built fresh engine for src=$sourceLang")
        return fresh
    }

    private fun sendResult(
        blocks: List<TranslateEngine.Block>,
        srcLang: String,
        tgtLang: String
    ) {
        val arr = JSONArray()
        for (b in blocks) {
            arr.put(JSONObject().apply {
                put("o", b.original)
                put("t", b.translated)
                put("l", b.bbox.left)
                put("u", b.bbox.top)    // u for 'up' — avoid 't' collision with translated
                put("r", b.bbox.right)
                put("b", b.bbox.bottom)
            })
        }
        val resp = JSONObject().apply {
            put("src", srcLang)
            put("tgt", tgtLang)
            put("blocks", arr)
        }
        val sent = sender(PluginMessage("TRANSLATE_RESULT", resp.toString()))
        Log.i(TAG, "TRANSLATE_RESULT sent=$sent blocks=${blocks.size}")
    }

    private fun sendError(message: String) {
        val resp = JSONObject().put("error", message).toString()
        sender(PluginMessage("TRANSLATE_ERROR", resp))
    }
}
