package com.glasshole.phone.plugins.notes

import android.content.Context
import android.util.Log
import com.glasshole.phone.plugin.PhonePlugin
import com.glasshole.phone.plugin.PluginSender
import com.glasshole.sdk.PluginMessage
import org.json.JSONArray
import org.json.JSONObject

class NotesPlugin : PhonePlugin {

    companion object {
        private const val TAG = "NotesPlugin"

        @Volatile
        var instance: NotesPlugin? = null
            private set
    }

    override val pluginId: String = "notes"

    private lateinit var appContext: Context
    private lateinit var sender: PluginSender

    val db: NoteDatabase by lazy { NoteDatabase(appContext) }

    override fun onCreate(context: Context, sender: PluginSender) {
        this.appContext = context
        this.sender = sender
        instance = this
    }

    override fun onDestroy() {
        instance = null
    }

    override fun onMessageFromGlass(message: PluginMessage) {
        Log.d(TAG, "Message from Glass: type=${message.type}")
        when (message.type) {
            "NOTE_SAVED" -> handleNoteSaved(message.payload)
            "NOTE_LIST_REQ" -> handleNoteListRequest()
            "NOTE_REQ" -> handleNoteRequest(message.payload)
            else -> Log.w(TAG, "Unknown message type: ${message.type}")
        }
    }

    private fun handleNoteSaved(payload: String) {
        try {
            val json = JSONObject(payload)
            val text = json.getString("text")
            val timestamp = json.optLong("timestamp", System.currentTimeMillis())
            val note = db.insertNote(text, timestamp)
            Log.i(TAG, "Note saved: ${note.id}")

            sender(PluginMessage("NOTE_SAVED_ACK", JSONObject().apply {
                put("id", note.id)
                put("success", true)
            }.toString()))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save note: ${e.message}")
        }
    }

    private fun handleNoteListRequest() {
        try {
            val notes = db.getAllNotes()
            val jsonArray = JSONArray()
            for (note in notes) {
                jsonArray.put(JSONObject().apply {
                    put("id", note.id)
                    put("title", note.text.lines().firstOrNull()?.take(50) ?: "")
                    put("createdAt", note.createdAt)
                    put("updatedAt", note.updatedAt)
                })
            }
            sender(PluginMessage("NOTE_LIST", jsonArray.toString()))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send note list: ${e.message}")
        }
    }

    private fun handleNoteRequest(payload: String) {
        try {
            val json = JSONObject(payload)
            val id = json.getString("id")
            val note = db.getNoteById(id)
            if (note != null) {
                sender(PluginMessage("NOTE_CONTENT", JSONObject().apply {
                    put("id", note.id)
                    put("text", note.text)
                    put("createdAt", note.createdAt)
                    put("updatedAt", note.updatedAt)
                }.toString()))
            } else {
                Log.w(TAG, "Note not found: $id")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send note: ${e.message}")
        }
    }

    fun sendNoteToGlass(note: Note): Boolean {
        return sender(PluginMessage("NOTE_CONTENT", JSONObject().apply {
            put("id", note.id)
            put("text", note.text)
            put("createdAt", note.createdAt)
            put("updatedAt", note.updatedAt)
        }.toString()))
    }
}
