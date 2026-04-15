package com.glasshole.phone.plugins.notes

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.UUID

data class Note(
    val id: String,
    val text: String,
    val createdAt: Long,
    val updatedAt: Long
)

class NoteDatabase(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    companion object {
        private const val DB_NAME = "glasshole_notes.db"
        private const val DB_VERSION = 1
        private const val TABLE = "notes"
        private const val COL_ID = "id"
        private const val COL_TEXT = "text"
        private const val COL_CREATED_AT = "created_at"
        private const val COL_UPDATED_AT = "updated_at"
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE $TABLE (
                $COL_ID TEXT PRIMARY KEY,
                $COL_TEXT TEXT NOT NULL,
                $COL_CREATED_AT INTEGER NOT NULL,
                $COL_UPDATED_AT INTEGER NOT NULL
            )
        """.trimIndent())
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE")
        onCreate(db)
    }

    fun insertNote(text: String, timestamp: Long = System.currentTimeMillis()): Note {
        val id = UUID.randomUUID().toString()
        val values = ContentValues().apply {
            put(COL_ID, id)
            put(COL_TEXT, text)
            put(COL_CREATED_AT, timestamp)
            put(COL_UPDATED_AT, timestamp)
        }
        writableDatabase.insert(TABLE, null, values)
        return Note(id, text, timestamp, timestamp)
    }

    fun updateNote(id: String, text: String): Boolean {
        val now = System.currentTimeMillis()
        val values = ContentValues().apply {
            put(COL_TEXT, text)
            put(COL_UPDATED_AT, now)
        }
        return writableDatabase.update(TABLE, values, "$COL_ID = ?", arrayOf(id)) > 0
    }

    fun deleteNote(id: String): Boolean {
        return writableDatabase.delete(TABLE, "$COL_ID = ?", arrayOf(id)) > 0
    }

    fun getAllNotes(): List<Note> {
        val notes = mutableListOf<Note>()
        val cursor = readableDatabase.query(
            TABLE, null, null, null, null, null,
            "$COL_UPDATED_AT DESC"
        )
        cursor.use {
            while (it.moveToNext()) {
                notes.add(Note(
                    id = it.getString(it.getColumnIndexOrThrow(COL_ID)),
                    text = it.getString(it.getColumnIndexOrThrow(COL_TEXT)),
                    createdAt = it.getLong(it.getColumnIndexOrThrow(COL_CREATED_AT)),
                    updatedAt = it.getLong(it.getColumnIndexOrThrow(COL_UPDATED_AT))
                ))
            }
        }
        return notes
    }

    fun getNoteById(id: String): Note? {
        val cursor = readableDatabase.query(
            TABLE, null, "$COL_ID = ?", arrayOf(id),
            null, null, null
        )
        cursor.use {
            if (it.moveToFirst()) {
                return Note(
                    id = it.getString(it.getColumnIndexOrThrow(COL_ID)),
                    text = it.getString(it.getColumnIndexOrThrow(COL_TEXT)),
                    createdAt = it.getLong(it.getColumnIndexOrThrow(COL_CREATED_AT)),
                    updatedAt = it.getLong(it.getColumnIndexOrThrow(COL_UPDATED_AT))
                )
            }
        }
        return null
    }
}
