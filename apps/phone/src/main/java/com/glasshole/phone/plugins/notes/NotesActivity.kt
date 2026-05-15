package com.glasshole.phone.plugins.notes

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.glasshole.phone.R
import com.google.android.material.floatingactionbutton.FloatingActionButton
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class NotesActivity : AppCompatActivity() {

    private lateinit var db: NoteDatabase
    private lateinit var listView: ListView
    private lateinit var emptyView: TextView
    private lateinit var adapter: NotesAdapter
    private var notes = mutableListOf<Note>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_notes)

        db = NotesPlugin.instance?.db ?: NoteDatabase(this)

        listView = findViewById(R.id.notesList)
        emptyView = findViewById(R.id.emptyView)
        val fab: FloatingActionButton = findViewById(R.id.fabNewNote)

        adapter = NotesAdapter()
        listView.adapter = adapter
        listView.emptyView = emptyView

        listView.setOnItemClickListener { _, _, position, _ ->
            showEditDialog(notes[position])
        }

        listView.setOnItemLongClickListener { _, _, position, _ ->
            showDeleteDialog(notes[position])
            true
        }

        fab.setOnClickListener { showCreateDialog() }

        handleDebugInsert(intent)
        refreshNotes()
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        handleDebugInsert(intent)
        refreshNotes()
    }

    /**
     * Debug entry point for inserting a note via adb:
     *   adb shell am start -n com.glasshole.phone/.plugins.notes.NotesActivity \
     *     -a com.glasshole.phone.DEBUG_INSERT_NOTE --es note_text "hello world"
     * Or load the text from a file on the device:
     *   adb shell am start -n com.glasshole.phone/.plugins.notes.NotesActivity \
     *     -a com.glasshole.phone.DEBUG_INSERT_NOTE --es note_file /sdcard/lorem.txt
     */
    private fun handleDebugInsert(intent: android.content.Intent?) {
        intent ?: return
        if (intent.action != "com.glasshole.phone.DEBUG_INSERT_NOTE") return
        val text = intent.getStringExtra("note_text") ?: run {
            val path = intent.getStringExtra("note_file") ?: return
            try {
                java.io.File(path).readText()
            } catch (e: Exception) {
                Toast.makeText(this, "Read failed: ${e.message}", Toast.LENGTH_LONG).show()
                return
            }
        }
        if (text.isBlank()) return
        db.insertNote(text)
        Toast.makeText(this, "Inserted ${text.length} chars", Toast.LENGTH_SHORT).show()
    }

    override fun onResume() {
        super.onResume()
        refreshNotes()
    }

    private fun refreshNotes() {
        notes.clear()
        notes.addAll(db.getAllNotes())
        adapter.notifyDataSetChanged()
    }

    private fun showCreateDialog() {
        val input = EditText(this).apply {
            hint = "Enter note text"
            minLines = 3
            setPadding(48, 32, 48, 32)
        }

        AlertDialog.Builder(this)
            .setTitle("New Note")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val text = input.text.toString().trim()
                if (text.isNotEmpty()) {
                    db.insertNote(text)
                    refreshNotes()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showEditDialog(note: Note) {
        // Glass-options first; the dialog itself stays a simple edit-or-
        // dismiss surface, but giving the user a quick action menu first
        // avoids cluttering the Save dialog with too many neutral-button
        // slots (Android only allows one).
        val options = arrayOf("Edit", "Send to Glass", "Teleprompter mode")
        AlertDialog.Builder(this)
            .setTitle(note.text.lines().firstOrNull()?.take(40) ?: "Note")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showEditTextDialog(note)
                    1 -> {
                        val updated = db.getNoteById(note.id) ?: return@setItems
                        val sent = NotesPlugin.instance?.sendNoteToGlass(updated) ?: false
                        val msg = if (sent) "Sent to Glass" else "Glass not connected"
                        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                    }
                    2 -> {
                        val updated = db.getNoteById(note.id) ?: return@setItems
                        val title = updated.text.lines().firstOrNull()?.take(50) ?: "Note"
                        val intent = android.content.Intent(
                            this, TeleprompterControlActivity::class.java
                        ).apply {
                            putExtra(TeleprompterControlActivity.EXTRA_NOTE_ID, updated.id)
                            putExtra(TeleprompterControlActivity.EXTRA_NOTE_TITLE, title)
                            putExtra(TeleprompterControlActivity.EXTRA_NOTE_TEXT, updated.text)
                        }
                        startActivity(intent)
                    }
                }
            }
            .show()
    }

    private fun showEditTextDialog(note: Note) {
        val input = EditText(this).apply {
            setText(note.text)
            minLines = 3
            setPadding(48, 32, 48, 32)
        }

        AlertDialog.Builder(this)
            .setTitle("Edit Note")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val text = input.text.toString().trim()
                if (text.isNotEmpty()) {
                    db.updateNote(note.id, text)
                    refreshNotes()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showDeleteDialog(note: Note) {
        val preview = note.text.lines().firstOrNull()?.take(40) ?: "this note"
        AlertDialog.Builder(this)
            .setTitle("Delete Note")
            .setMessage("Delete \"$preview\"?")
            .setPositiveButton("Delete") { _, _ ->
                db.deleteNote(note.id)
                refreshNotes()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private inner class NotesAdapter : BaseAdapter() {

        private val dateFormat = SimpleDateFormat("MMM d, yyyy h:mm a", Locale.getDefault())

        override fun getCount(): Int = notes.size
        override fun getItem(position: Int): Note = notes[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: LayoutInflater.from(this@NotesActivity)
                .inflate(android.R.layout.simple_list_item_2, parent, false)

            val note = notes[position]
            val title = view.findViewById<TextView>(android.R.id.text1)
            val subtitle = view.findViewById<TextView>(android.R.id.text2)

            title.text = note.text.lines().firstOrNull() ?: ""
            subtitle.text = dateFormat.format(Date(note.updatedAt))

            return view
        }
    }
}
