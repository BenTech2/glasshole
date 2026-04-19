package com.glasshole.plugin.notes.glass

import android.content.Intent
import android.util.Log
import com.glasshole.glass.sdk.GlassPluginMessage
import com.glasshole.glass.sdk.GlassPluginService
import org.json.JSONArray
import org.json.JSONObject

class NotesGlassPluginService : GlassPluginService() {

    companion object {
        private const val TAG = "NotesGlassPlugin"
    }

    override val pluginId: String = "notes"

    override fun onMessageFromPhone(message: GlassPluginMessage) {
        Log.d(TAG, "Message from phone: type=${message.type}")
        when (message.type) {
            "NOTE_CONTENT" -> handleNoteContent(message.payload)
            "NOTE_LIST" -> handleNoteList(message.payload)
            "NOTE_SAVED_ACK" -> handleSaveAck(message.payload)
            else -> Log.w(TAG, "Unknown message type: ${message.type}")
        }
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
