package com.quicknote.app

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** A local-only note, task, or voice memo. */
data class Entry(
    val id: Long,
    val type: String,
    val content: String,
    val audioPath: String?,
    val createdAt: Long,
    val done: Boolean,
    val favorite: Boolean = false,
    /** Comma-separated, normalized tags; each tag is at most 32 characters. */
    val tag: String = "",
    val remindAt: Long? = null,
    val title: String? = null,
    val transcript: String? = null,
    val updatedAt: Long = createdAt,
    val pinned: Boolean = false,
    val archived: Boolean = false,
    val deletedAt: Long? = null,
    val durationMs: Long = 0L
)

class EntryDb(context: Context) : SQLiteOpenHelper(context, "quicknote.db", null, 4) {
    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE entries (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                type TEXT NOT NULL,
                content TEXT NOT NULL,
                audio_path TEXT,
                created_at INTEGER NOT NULL,
                done INTEGER NOT NULL DEFAULT 0,
                favorite INTEGER NOT NULL DEFAULT 0,
                tag TEXT NOT NULL DEFAULT '',
                remind_at INTEGER,
                title TEXT,
                transcript TEXT,
                updated_at INTEGER NOT NULL DEFAULT 0,
                pinned INTEGER NOT NULL DEFAULT 0,
                archived INTEGER NOT NULL DEFAULT 0,
                deleted_at INTEGER,
                duration_ms INTEGER NOT NULL DEFAULT 0
            )""".trimIndent()
        )
        db.execSQL("CREATE INDEX entries_visible_order ON entries(deleted_at, archived, pinned DESC, updated_at DESC)")
        db.execSQL("CREATE INDEX entries_reminders ON entries(type, done, remind_at)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE entries ADD COLUMN favorite INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE entries ADD COLUMN tag TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE entries ADD COLUMN remind_at INTEGER")
        }
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE entries ADD COLUMN title TEXT")
            db.execSQL("ALTER TABLE entries ADD COLUMN transcript TEXT")
            db.execSQL("ALTER TABLE entries ADD COLUMN updated_at INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE entries ADD COLUMN pinned INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE entries ADD COLUMN archived INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE entries ADD COLUMN deleted_at INTEGER")
            db.execSQL("ALTER TABLE entries ADD COLUMN duration_ms INTEGER NOT NULL DEFAULT 0")
            db.execSQL("UPDATE entries SET updated_at = created_at WHERE updated_at = 0")
            // Preserve legacy voice labels/transcripts as editable titles; new transcripts have their own column.
            db.execSQL("UPDATE entries SET title = content, content = '' WHERE type = 'voice' AND content <> ''")
            db.execSQL("CREATE INDEX entries_visible_order ON entries(deleted_at, archived, pinned DESC, updated_at DESC)")
            db.execSQL("CREATE INDEX entries_reminders ON entries(type, done, remind_at)")
        }
        if (oldVersion < 4) {
            db.execSQL("""UPDATE entries SET
                transcript = CASE
                    WHEN content LIKE 'Voice memo%' OR content LIKE 'Voice recording%' OR content LIKE 'مذكرة صوتية%' OR content LIKE 'تسجيل صوتي%' OR content LIKE 'رسالة صوتية%' THEN transcript
                    ELSE COALESCE(transcript, content)
                END,
                title = CASE
                    WHEN title LIKE 'Voice memo%' OR title LIKE 'Voice recording%' OR title LIKE 'مذكرة صوتية%' OR title LIKE 'تسجيل صوتي%' OR title LIKE 'رسالة صوتية%' THEN NULL
                    ELSE title
                END,
                content = ''
                WHERE type = 'voice'""".trimIndent())
        }
    }

    fun add(
        type: String,
        content: String,
        audioPath: String? = null,
        tag: String = "",
        favorite: Boolean = false,
        remindAt: Long? = null,
        title: String? = null,
        transcript: String? = null,
        durationMs: Long = 0L
    ): Long {
        val now = System.currentTimeMillis()
        val values = ContentValues().apply {
            put("type", type)
            put("content", content)
            put("audio_path", audioPath)
            put("created_at", now)
            put("updated_at", now)
            put("done", 0)
            put("favorite", if (favorite) 1 else 0)
            put("tag", normalizeTags(tag))
            put("remind_at", remindAt)
            put("title", title)
            put("transcript", transcript)
            put("duration_ms", durationMs.coerceAtLeast(0L))
        }
        return writableDatabase.insertOrThrow("entries", null, values)
    }

    /** Returns live and archived rows by default; deleted rows require includeDeleted=true. */
    fun all(includeArchived: Boolean = false, includeDeleted: Boolean = false): List<Entry> {
        val where = buildList {
            if (!includeDeleted) add("deleted_at IS NULL")
            if (!includeArchived) add("archived = 0")
        }.joinToString(" AND ").ifBlank { null }
        val result = mutableListOf<Entry>()
        readableDatabase.query("entries", null, where, null, null, null, "pinned DESC, updated_at DESC, id DESC").use { cursor ->
            while (cursor.moveToNext()) result += cursor.toEntry()
        }
        return result
    }

    fun get(id: Long, includeDeleted: Boolean = false): Entry? {
        val selection = if (includeDeleted) "id = ?" else "id = ? AND deleted_at IS NULL"
        return readableDatabase.query("entries", null, selection, arrayOf(id.toString()), null, null, null).use { cursor ->
            if (cursor.moveToFirst()) cursor.toEntry() else null
        }
    }

    fun setDone(id: Long, done: Boolean) = update(id) {
        put("done", if (done) 1 else 0)
        // Keep remind_at so unchecking a task can restore a future reminder.
    }

    fun setFavorite(id: Long, favorite: Boolean) = update(id) { put("favorite", if (favorite) 1 else 0) }
    fun setPinned(id: Long, pinned: Boolean) = update(id) { put("pinned", if (pinned) 1 else 0) }
    fun setArchived(id: Long, archived: Boolean) = update(id) { put("archived", if (archived) 1 else 0) }
    fun setTag(id: Long, tag: String) = update(id) { put("tag", normalizeTags(tag)) }
    fun setReminder(id: Long, remindAt: Long?) = update(id) { if (remindAt == null) putNull("remind_at") else put("remind_at", remindAt) }
    fun setContent(id: Long, content: String) = update(id) { put("content", content) }
    fun setTitle(id: Long, title: String?) = update(id) { put("title", title?.trim()?.takeIf(String::isNotEmpty)) }
    fun setTranscript(id: Long, transcript: String?) = update(id) { put("transcript", transcript?.trim()?.takeIf(String::isNotEmpty)) }

    private inline fun update(id: Long, values: ContentValues.() -> Unit) {
        val now = System.currentTimeMillis()
        writableDatabase.update("entries", ContentValues().apply { values(); put("updated_at", now) }, "id = ? AND deleted_at IS NULL", arrayOf(id.toString()))
    }

    /** Soft-delete first so the UI can offer Undo; media stays intact until purged. */
    fun trash(id: Long): Entry? {
        val entry = get(id) ?: return null
        val now = System.currentTimeMillis()
        writableDatabase.update("entries", ContentValues().apply { put("deleted_at", now); put("updated_at", now) }, "id = ?", arrayOf(id.toString()))
        return entry.copy(deletedAt = now, updatedAt = now)
    }

    fun restoreDeleted(entry: Entry): Boolean {
        val values = entry.toContentValues().apply { putNull("deleted_at"); put("updated_at", System.currentTimeMillis()) }
        return writableDatabase.update("entries", values, "id = ?", arrayOf(entry.id.toString())) > 0
    }

    fun purge(id: Long): Entry? {
        val entry = get(id, includeDeleted = true) ?: return null
        writableDatabase.delete("entries", "id = ?", arrayOf(id.toString()))
        return entry
    }

    fun purgeExpiredTrash(cutoff: Long): List<Entry> {
        val expired = all(includeArchived = true, includeDeleted = true).filter { it.deletedAt != null && it.deletedAt < cutoff }
        val db = writableDatabase
        db.beginTransaction()
        try {
            expired.forEach { db.delete("entries", "id = ?", arrayOf(it.id.toString())) }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return expired
    }

    /** Replaces all data in a single SQLite transaction and returns old media paths for cleanup. */
    fun replaceAll(entries: List<Entry>): List<String> {
        val oldPaths = all(includeArchived = true, includeDeleted = true).mapNotNull { it.audioPath }
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("entries", null, null)
            entries.forEach { entry -> db.insertOrThrow("entries", null, entry.toContentValues(includeId = false)) }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return oldPaths
    }

    private fun Entry.toContentValues(includeId: Boolean = true) = ContentValues().apply {
        if (includeId && id > 0) put("id", id)
        put("type", type)
        put("content", content)
        put("audio_path", audioPath)
        put("created_at", createdAt)
        put("done", if (done) 1 else 0)
        put("favorite", if (favorite) 1 else 0)
        put("tag", normalizeTags(tag))
        put("remind_at", remindAt)
        put("title", title)
        put("transcript", transcript)
        put("updated_at", updatedAt)
        put("pinned", if (pinned) 1 else 0)
        put("archived", if (archived) 1 else 0)
        put("deleted_at", deletedAt)
        put("duration_ms", durationMs.coerceAtLeast(0L))
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
        remindAt = if (isNull(getColumnIndexOrThrow("remind_at"))) null else getLong(getColumnIndexOrThrow("remind_at")),
        title = getString(getColumnIndexOrThrow("title")),
        transcript = getString(getColumnIndexOrThrow("transcript")),
        updatedAt = getLong(getColumnIndexOrThrow("updated_at")),
        pinned = getInt(getColumnIndexOrThrow("pinned")) != 0,
        archived = getInt(getColumnIndexOrThrow("archived")) != 0,
        deletedAt = if (isNull(getColumnIndexOrThrow("deleted_at"))) null else getLong(getColumnIndexOrThrow("deleted_at")),
        durationMs = getLong(getColumnIndexOrThrow("duration_ms"))
    )

    companion object {
        fun normalizeTags(raw: String): String = TagNormalizer.normalize(raw)
    }
}
