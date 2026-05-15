package com.glasshole.plugin.notes.glass

import android.content.Intent
import android.util.Log
import com.glasshole.glass.sdk.GlassPluginMessage
import com.glasshole.glass.sdk.GlassPluginService
import com.glasshole.glass.sdk.PluginConfigHandler
import org.json.JSONArray
import org.json.JSONObject

class NotesGlassPluginService : GlassPluginService() {

    companion object {
        private const val TAG = "NotesGlassPlugin"
        const val PREFS_NAME = "notes_settings"

        /** Static handle so the teleprompter activity can call back into
         *  the plugin bridge (sendToPhone) for state echo without having
         *  to bind the service itself. Null when the service isn't
         *  running. */
        @Volatile
        var instance: NotesGlassPluginService? = null
            private set
    }

    override val pluginId: String = "notes"

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    // Routes SCHEMA_REQ / CONFIG_READ / CONFIG_WRITE for the dynamic
    // settings UI. The schema is the res/raw/plugin_schema.json we ship,
    // and current values live in SharedPreferences("notes_settings").
    private val configHandler by lazy {
        PluginConfigHandler(
            context = this,
            prefsName = PREFS_NAME,
            schemaResId = R.raw.plugin_schema,
            send = { type, payload ->
                sendToPhone(GlassPluginMessage(type, payload))
            }
        )
    }

    override fun onMessageFromPhone(message: GlassPluginMessage) {
        Log.d(TAG, "Message from phone: type=${message.type}")
        if (configHandler.handle(message)) return
        when (message.type) {
            "NOTE_CONTENT" -> handleNoteContent(message.payload)
            "NOTE_LIST" -> handleNoteList(message.payload)
            "NOTE_SAVED_ACK" -> handleSaveAck(message.payload)
            "TELEPROMPTER_START" -> handleTeleprompterStart(message.payload)
            "TELEPROMPTER_CONTROL" -> handleTeleprompterControl(message.payload)
            "TELEPROMPTER_STOP" -> handleTeleprompterStop()
            else -> Log.w(TAG, "Unknown message type: ${message.type}")
        }
    }

    private fun handleTeleprompterStart(payload: String) {
        try {
            val json = JSONObject(payload)
            val intent = Intent(this, TeleprompterActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra(TeleprompterActivity.EXTRA_TEXT, json.optString("text", ""))
                if (json.has("speedPxPerSec")) putExtra(
                    TeleprompterActivity.EXTRA_SPEED_PX_PER_SEC,
                    json.getDouble("speedPxPerSec").toFloat()
                )
                if (json.has("fontSp")) putExtra(
                    TeleprompterActivity.EXTRA_FONT_SP,
                    json.getDouble("fontSp").toFloat()
                )
                if (json.has("playing")) putExtra(
                    TeleprompterActivity.EXTRA_PLAYING,
                    json.getBoolean("playing")
                )
            }
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Teleprompter start failed: ${e.message}")
        }
    }

    private fun handleTeleprompterControl(payload: String) {
        // Re-fire as a local broadcast so the activity (which holds the
        // text + scroll state) can apply the change without needing a
        // bind back into this service.
        val intent = Intent(TeleprompterActivity.ACTION_CONTROL).apply {
            setPackage(packageName)
            putExtra("payload", payload)
        }
        sendBroadcast(intent)
    }

    private fun handleTeleprompterStop() {
        val intent = Intent(TeleprompterActivity.ACTION_STOP).apply {
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    /** Called by TeleprompterActivity after every state change so the
     *  phone-side control panel can reflect what's actually happening
     *  on glass (including local tap-to-pause / speed adjustments). */
    fun sendTeleprompterState(payload: String) {
        sendToPhone(GlassPluginMessage("TELEPROMPTER_STATE", payload))
    }

    private fun handleNoteContent(payload: String) {
        try {
            val json = JSONObject(payload)
            val text = json.getString("text")
            val intent = Intent(this, NoteViewActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(NoteViewActivity.EXTRA_NOTE_TEXT, text)
                putExtra(NoteViewActivity.EXTRA_NOTE_ID, json.optString("id", ""))
            }
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show note: ${e.message}")
        }
    }

    private fun handleNoteList(payload: String) {
        try {
            val intent = Intent(this, NoteListActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(NoteListActivity.EXTRA_NOTES_JSON, payload)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show note list: ${e.message}")
        }
    }

    private fun handleSaveAck(payload: String) {
        Log.i(TAG, "Note save acknowledged: $payload")
    }

    /** Send a note request to the phone side */
    fun requestNote(id: String) {
        sendToPhone(GlassPluginMessage("NOTE_REQ", JSONObject().apply {
            put("id", id)
        }.toString()))
    }

    /** Request the full note list from the phone */
    fun requestNoteList() {
        sendToPhone(GlassPluginMessage("NOTE_LIST_REQ", ""))
    }

    /** Send a dictated note to the phone for saving */
    fun saveNote(text: String) {
        sendToPhone(GlassPluginMessage("NOTE_SAVED", JSONObject().apply {
            put("text", text)
            put("timestamp", System.currentTimeMillis())
        }.toString()))
    }
}
