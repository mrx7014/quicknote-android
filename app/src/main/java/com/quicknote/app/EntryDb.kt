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
    val done: Boolean,
    val favorite: Boolean = false,
    val tag: String = "",
    val remindAt: Long? = null
)

class EntryDb(context: Context) : SQLiteOpenHelper(context, "quicknote.db", null, 2) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE entries (id INTEGER PRIMARY KEY AUTOINCREMENT, type TEXT NOT NULL, content TEXT NOT NULL, audio_path TEXT, created_at INTEGER NOT NULL, done INTEGER NOT NULL DEFAULT 0, favorite INTEGER NOT NULL DEFAULT 0, tag TEXT NOT NULL DEFAULT '', remind_at INTEGER)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE entries ADD COLUMN favorite INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE entries ADD COLUMN tag TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE entries ADD COLUMN remind_at INTEGER")
        }
    }

    fun add(type: String, content: String, audioPath: String? = null, tag: String = "", favorite: Boolean = false, remindAt: Long? = null): Long {
        val values = ContentValues().apply {
            put("type", type); put("content", content); put("audio_path", audioPath)
            put("created_at", System.currentTimeMillis()); put("done", 0)
            put("favorite", if (favorite) 1 else 0); put("tag", tag); put("remind_at", remindAt)
        }
        return writableDatabase.insertOrThrow("entries", null, values)
    }

    fun all(): List<Entry> {
        val result = mutableListOf<Entry>()
        readableDatabase.query("entries", null, null, null, null, null, "created_at DESC").use { cursor ->
            while (cursor.moveToNext()) result += cursor.toEntry()
        }
        return result
    }

    fun get(id: Long): Entry? = readableDatabase.query("entries", null, "id = ?", arrayOf(id.toString()), null, null, null).use { cursor ->
        if (cursor.moveToFirst()) cursor.toEntry() else null
    }

    fun setDone(id: Long, done: Boolean) {
        writableDatabase.update("entries", ContentValues().apply { put("done", if (done) 1 else 0) }, "id = ?", arrayOf(id.toString()))
        if (done) setReminder(id, null)
    }

    fun setFavorite(id: Long, favorite: Boolean) {
        writableDatabase.update("entries", ContentValues().apply { put("favorite", if (favorite) 1 else 0) }, "id = ?", arrayOf(id.toString()))
    }

    fun setTag(id: Long, tag: String) {
        writableDatabase.update("entries", ContentValues().apply { put("tag", tag) }, "id = ?", arrayOf(id.toString()))
    }

    fun setReminder(id: Long, remindAt: Long?) {
        writableDatabase.update("entries", ContentValues().apply { if (remindAt == null) putNull("remind_at") else put("remind_at", remindAt) }, "id = ?", arrayOf(id.toString()))
    }

    fun setContent(id: Long, content: String) {
        writableDatabase.update("entries", ContentValues().apply { put("content", content) }, "id = ?", arrayOf(id.toString()))
    }

    fun delete(id: Long): Entry? {
        val item = get(id)
        writableDatabase.delete("entries", "id = ?", arrayOf(id.toString()))
        return item
    }

    /** Replaces all entries in a single SQLite transaction and returns old media paths for cleanup. */
    fun replaceAll(entries: List<Entry>): List<String> {
        val oldPaths = all().mapNotNull { it.audioPath }
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("entries", null, null)
            entries.forEach { entry ->
                val values = ContentValues().apply {
                    put("type", entry.type); put("content", entry.content); put("audio_path", entry.audioPath)
                    put("created_at", entry.createdAt); put("done", if (entry.done) 1 else 0)
                    put("favorite", if (entry.favorite) 1 else 0); put("tag", entry.tag)
                    if (entry.remindAt == null) putNull("remind_at") else put("remind_at", entry.remindAt)
                }
                db.insertOrThrow("entries", null, values)
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return oldPaths
    }

    private fun android.database.Cursor.toEntry() = Entry(
        id = getLong(getColumnIndexOrThrow("id")),
        type = getString(getColumnIndexOrThrow("type")),
        content = getString(getColumnIndexOrThrow("content")),
        audioPath = getString(getColumnIndexOrThrow("audio_path")),
        createdAt = getLong(getColumnIndexOrThrow("created_at")),
        done = getInt(getColumnIndexOrThrow("done")) != 0,
        favorite = getInt(getColumnIndexOrThrow("favorite")) != 0,
        tag = getString(getColumnIndexOrThrow("tag")).orEmpty(),
        remindAt = if (isNull(getColumnIndexOrThrow("remind_at"))) null else getLong(getColumnIndexOrThrow("remind_at"))
    )
}
