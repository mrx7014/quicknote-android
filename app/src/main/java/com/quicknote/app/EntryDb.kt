package com.quicknote.app

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class Entry(
    val id: Long,
    val type: String,
    val content: String,
    val audioPath: String?,
    val createdAt: Long,
    val done: Boolean
)

class EntryDb(context: Context) : SQLiteOpenHelper(context, "quicknote.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE entries (id INTEGER PRIMARY KEY AUTOINCREMENT, type TEXT NOT NULL, content TEXT NOT NULL, audio_path TEXT, created_at INTEGER NOT NULL, done INTEGER NOT NULL DEFAULT 0)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun add(type: String, content: String, audioPath: String? = null): Long {
        val values = ContentValues().apply {
            put("type", type)
            put("content", content)
            put("audio_path", audioPath)
            put("created_at", System.currentTimeMillis())
            put("done", 0)
        }
        return writableDatabase.insertOrThrow("entries", null, values)
    }

    fun all(): List<Entry> {
        val result = mutableListOf<Entry>()
        readableDatabase.query("entries", null, null, null, null, null, "created_at DESC").use { cursor ->
            while (cursor.moveToNext()) {
                result += Entry(
                    id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                    type = cursor.getString(cursor.getColumnIndexOrThrow("type")),
                    content = cursor.getString(cursor.getColumnIndexOrThrow("content")),
                    audioPath = cursor.getString(cursor.getColumnIndexOrThrow("audio_path")),
                    createdAt = cursor.getLong(cursor.getColumnIndexOrThrow("created_at")),
                    done = cursor.getInt(cursor.getColumnIndexOrThrow("done")) != 0
                )
            }
        }
        return result
    }

    fun setDone(id: Long, done: Boolean) {
        writableDatabase.update("entries", ContentValues().apply { put("done", if (done) 1 else 0) }, "id = ?", arrayOf(id.toString()))
    }

    fun delete(id: Long): Entry? {
        val item = all().firstOrNull { it.id == id }
        writableDatabase.delete("entries", "id = ?", arrayOf(id.toString()))
        return item
    }
}
