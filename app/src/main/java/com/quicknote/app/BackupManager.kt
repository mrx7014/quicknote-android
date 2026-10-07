package com.quicknote.app

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.os.StatFs
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class BackupException(val messageRes: Int, cause: Throwable? = null) : Exception(null, cause)

object BackupManager {
    private const val MANIFEST = "manifest.json"
    private const val MAX_MANIFEST_BYTES = 2L * 1024 * 1024
    private const val MAX_BACKUP_BYTES = 512L * 1024 * 1024
    private const val MAX_ENTRIES = 10_000
    private const val MAX_ARCHIVE_ENTRIES = MAX_ENTRIES * 2 + 10
    private const val MAX_CONTENT_CHARS = 20_000
    private const val MAX_TITLE_CHARS = 120
    private const val MIN_FREE_BYTES = 10L * 1024 * 1024

    fun export(context: Context, uri: Uri): Int {
        val entries = try { EntryDb(context).use { it.all(includeArchived = true, includeDeleted = true) } }
        catch (error: Exception) { throw BackupException(R.string.backup_failed, error) }
        if (entries.size > MAX_ENTRIES) throw BackupException(R.string.backup_too_many_entries)
        val audioFiles = entries.mapNotNull { entry ->
            val path = entry.audioPath ?: return@mapNotNull null
            val audio = File(path)
            if (!audio.isFile || !audio.absolutePath.startsWith(context.filesDir.absolutePath + File.separator)) {
                throw BackupException(R.string.backup_missing_audio)
            }
            entry.id to audio
        }.toMap()
        var audioBytes = 0L
        audioFiles.values.forEach { audio ->
            val length = audio.length()
            if (length <= 0L) throw BackupException(R.string.backup_missing_audio)
            if (length > MAX_BACKUP_BYTES - audioBytes) throw BackupException(R.string.backup_too_large)
            audioBytes += length
        }
        val temp = File.createTempFile("quicknote_export_", ".zip", context.cacheDir)
        try {
            ZipOutputStream(temp.outputStream().buffered()).use { zip ->
                val array = JSONArray()
                entries.forEach { entry ->
                    validateEntry(entry)
                    val obj = JSONObject()
                        .put("type", entry.type)
                        .put("content", entry.content)
                        .put("createdAt", entry.createdAt)
                        .put("updatedAt", entry.updatedAt)
                        .put("done", entry.done)
                        .put("favorite", entry.favorite)
                        .put("tag", EntryDb.normalizeTags(entry.tag))
                        .put("title", entry.title ?: JSONObject.NULL)
                        .put("transcript", entry.transcript ?: JSONObject.NULL)
                        .put("pinned", entry.pinned)
                        .put("archived", entry.archived)
                        .put("deletedAt", entry.deletedAt ?: JSONObject.NULL)
                        .put("durationMs", entry.durationMs.coerceAtLeast(0L))
                    if (entry.remindAt != null) obj.put("remindAt", entry.remindAt) else obj.put("remindAt", JSONObject.NULL)
                    val audio = audioFiles[entry.id]
                    if (audio != null) {
                        val extension = audio.extension.filter(Char::isLetterOrDigit).take(8).ifBlank { "audio" }
                        obj.put("audioArchive", "media/${entry.id}.$extension")
                    } else obj.put("audioArchive", JSONObject.NULL)
                    array.put(obj)
                }
                val manifest = JSONObject()
                    .put("format", "quicknote-backup")
                    .put("version", 2)
                    .put("entries", array)
                    .toString()
                    .toByteArray(Charsets.UTF_8)
                if (manifest.size > MAX_MANIFEST_BYTES) throw BackupException(R.string.backup_manifest_too_large)
                zip.putNextEntry(ZipEntry(MANIFEST))
                zip.write(manifest)
                zip.closeEntry()
                entries.forEach { entry ->
                    val audio = audioFiles[entry.id] ?: return@forEach
                    val extension = audio.extension.filter(Char::isLetterOrDigit).take(8).ifBlank { "audio" }
                    zip.putNextEntry(ZipEntry("media/${entry.id}.$extension"))
                    audio.inputStream().buffered().use { it.copyToBounded(zip, audio.length()) }
                    zip.closeEntry()
                }
            }
            if (temp.length() > MAX_BACKUP_BYTES) throw BackupException(R.string.backup_too_large)
            val resolver = context.contentResolver
            try {
                resolver.openOutputStream(uri, "w")?.use { output -> temp.inputStream().buffered().use { it.copyTo(output) } }
                    ?: throw BackupException(R.string.backup_open_failed)
            } catch (error: Exception) {
                runCatching { DocumentsContract.deleteDocument(resolver, uri) }
                if (error is BackupException) throw error
                throw BackupException(R.string.backup_export_failed, error)
            }
            return entries.size
        } catch (error: BackupException) {
            throw error
        } catch (error: Exception) {
            Log.e("QuickNoteBackup", "Backup export failed", error)
            throw BackupException(R.string.backup_failed, error)
        } finally { temp.delete() }
    }

    fun restore(context: Context, uri: Uri): Int {
        val temp = File(context.cacheDir, "quicknote_restore_${System.currentTimeMillis()}").apply { mkdirs() }
        val extracted = mutableMapOf<String, File>()
        var manifestBytes: ByteArray? = null
        var totalBytes = 0L
        var archiveEntryCount = 0
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                ZipInputStream(input.buffered()).use { zip ->
                    while (true) {
                        val item = zip.nextEntry ?: break
                        if (++archiveEntryCount > MAX_ARCHIVE_ENTRIES) throw BackupException(R.string.backup_too_many_entries)
                        val name = item.name
                        if (!BackupValidation.isSafeZipName(name)) {
                            throw BackupException(R.string.backup_invalid)
                        }
                        when {
                            item.isDirectory -> Unit
                            name == MANIFEST -> {
                                if (manifestBytes != null) throw BackupException(R.string.backup_invalid)
                                manifestBytes = zip.readLimited(MAX_MANIFEST_BYTES)
                                totalBytes += manifestBytes!!.size
                            }
                            name.startsWith("media/") -> {
                                if (!BackupValidation.isSafeMediaName(name) || extracted.containsKey(name)) throw BackupException(R.string.backup_invalid)
                                val filename = name.removePrefix("media/")
                                val out = File(temp, filename)
                                val copied = out.outputStream().buffered().use { zip.copyLimitedTo(it, MAX_BACKUP_BYTES - totalBytes) }
                                totalBytes += copied
                                extracted[name] = out
                            }
                            else -> throw BackupException(R.string.backup_invalid)
                        }
                        if (totalBytes > MAX_BACKUP_BYTES) throw BackupException(R.string.backup_too_large)
                        zip.closeEntry()
                    }
                }
            } ?: throw BackupException(R.string.backup_open_failed)
            val root = try { JSONObject(String(manifestBytes ?: throw BackupException(R.string.backup_invalid), Charsets.UTF_8)) }
            catch (error: BackupException) { throw error }
            catch (error: Exception) { throw BackupException(R.string.backup_invalid, error) }
            if (root.optString("format") != "quicknote-backup") throw BackupException(R.string.backup_invalid)
            val version = root.optInt("version", -1)
            if (version !in 1..2) throw BackupException(R.string.backup_invalid_version)
            val array = try { root.getJSONArray("entries") } catch (error: Exception) { throw BackupException(R.string.backup_invalid, error) }
            if (array.length() > MAX_ENTRIES) throw BackupException(R.string.backup_too_many_entries)

            val referencedMedia = mutableSetOf<String>()
            var requiredMediaBytes = 0L
            for (index in 0 until array.length()) {
                val obj = try { array.getJSONObject(index) } catch (error: Exception) { throw BackupException(R.string.backup_invalid, error) }
                val archive = obj.stringOrNull("audioArchive") ?: continue
                if (!BackupValidation.isSafeMediaName(archive)) throw BackupException(R.string.backup_invalid)
                val media = extracted[archive] ?: throw BackupException(R.string.backup_missing_audio)
                if (media.length() <= 0L) throw BackupException(R.string.backup_missing_audio)
                if (media.length() > MAX_BACKUP_BYTES - requiredMediaBytes) throw BackupException(R.string.backup_too_large)
                requiredMediaBytes += media.length()
                referencedMedia += archive
            }
            if (referencedMedia != extracted.keys) throw BackupException(R.string.backup_invalid)
            if (StatFs(context.filesDir.absolutePath).availableBytes < requiredMediaBytes + MIN_FREE_BYTES) {
                throw BackupException(R.string.backup_storage_low)
            }

            val entries = ArrayList<Entry>(array.length())
            val importedMedia = mutableListOf<File>()
            var committed = false
            try {
                for (index in 0 until array.length()) {
                    val obj = try { array.getJSONObject(index) } catch (error: Exception) { throw BackupException(R.string.backup_invalid, error) }
                    val type = obj.optString("type", "note")
                    if (type !in setOf("note", "task", "voice")) throw BackupException(R.string.backup_invalid)
                    val content = boundedString(obj, "content", MAX_CONTENT_CHARS, allowNull = false).orEmpty()
                    val title = boundedString(obj, "title", MAX_TITLE_CHARS, allowNull = true)
                    val transcript = boundedString(obj, "transcript", MAX_CONTENT_CHARS, allowNull = true)
                    val tags = boundedString(obj, "tag", 300, allowNull = true).orEmpty()
                    if (EntryDb.normalizeTags(tags) != tags.trim().trimEnd(',')) {
                        // Imports may contain legacy whitespace; normalize it but reject unbounded tag payloads.
                        if (tags.length > 300) throw BackupException(R.string.backup_field_too_long)
                    }
                    val normalizedTags = EntryDb.normalizeTags(tags)
                    val remindAt = obj.longOrNull("remindAt")
                    val deletedAt = if (version >= 2) obj.longOrNull("deletedAt") else null
                    val audioArchive = obj.stringOrNull("audioArchive")
                    var audioPath: String? = null
                    if (audioArchive != null) {
                        if (!BackupValidation.isSafeMediaName(audioArchive)) throw BackupException(R.string.backup_invalid)
                        val source = extracted[audioArchive] ?: throw BackupException(R.string.backup_missing_audio)
                        val extension = source.extension.filter(Char::isLetterOrDigit).take(8).ifBlank { "audio" }
                        val target = File.createTempFile("restored_", ".$extension", context.filesDir)
                        source.copyTo(target, overwrite = true)
                        importedMedia += target
                        audioPath = target.absolutePath
                    }
                    val createdAt = obj.optLong("createdAt", System.currentTimeMillis()).coerceAtLeast(0L)
                    val updatedAt = if (version >= 2) obj.optLong("updatedAt", createdAt).coerceAtLeast(createdAt) else createdAt
                    entries += Entry(
                        id = 0L,
                        type = type,
                        content = content,
                        audioPath = audioPath,
                        createdAt = createdAt,
                        done = obj.optBoolean("done", false),
                        favorite = obj.optBoolean("favorite", false),
                        tag = normalizedTags,
                        remindAt = remindAt,
                        title = title,
                        transcript = transcript,
                        updatedAt = updatedAt,
                        pinned = version >= 2 && obj.optBoolean("pinned", false),
                        archived = version >= 2 && obj.optBoolean("archived", false),
                        deletedAt = deletedAt,
                        durationMs = obj.optLong("durationMs", 0L).coerceAtLeast(0L)
                    )
                }
                val (oldEntries, oldAudioPaths) = EntryDb(context).use { database ->
                    val old = database.all(includeArchived = true, includeDeleted = true)
                    val oldPaths = database.replaceAll(entries)
                    committed = true
                    old to oldPaths
                }

                // These side effects are best-effort; the DB transaction already committed with a complete media set.
                oldEntries.forEach { old ->
                    runCatching { ReminderScheduler.cancel(context, old.id) }
                    runCatching { NotificationHelper.cancelAllEntryNotifications(context, old) }
                }
                runCatching { ReminderScheduler.rescheduleAll(context) }
                runCatching { QuickNoteWidgetProvider.refreshAll(context) }
                oldAudioPaths.forEach { path -> runCatching { File(path).delete() } }
                return entries.size
            } catch (error: Exception) {
                if (!committed) importedMedia.forEach { runCatching { it.delete() } }
                if (error is BackupException) throw error
                Log.e("QuickNoteBackup", "Backup restore failed", error)
                throw BackupException(R.string.backup_failed, error)
            }
        } catch (error: BackupException) {
            throw error
        } catch (error: Exception) {
            Log.e("QuickNoteBackup", "Could not read backup", error)
            throw BackupException(R.string.backup_invalid, error)
        } finally { temp.deleteRecursively() }
    }

    private fun validateEntry(entry: Entry) {
        if (entry.type !in setOf("note", "task", "voice")) throw BackupException(R.string.backup_invalid)
        if (entry.content.length > MAX_CONTENT_CHARS || (entry.title?.length ?: 0) > MAX_TITLE_CHARS || (entry.transcript?.length ?: 0) > MAX_CONTENT_CHARS) {
            throw BackupException(R.string.backup_field_too_long)
        }
        if (entry.tag.length > 300 || EntryDb.normalizeTags(entry.tag) != entry.tag) throw BackupException(R.string.backup_field_too_long)
    }

    private fun boundedString(obj: JSONObject, key: String, maxLength: Int, allowNull: Boolean): String? {
        val value = obj.opt(key)
        if (value == null || value == JSONObject.NULL) {
            if (!allowNull && key == "content") return ""
            return null
        }
        if (value !is String || value.length > maxLength) throw BackupException(R.string.backup_field_too_long)
        return value
    }

    private fun JSONObject.longOrNull(key: String): Long? {
        val value = opt(key)
        if (value == null || value == JSONObject.NULL) return null
        return try { (value as Number).toLong() } catch (_: Exception) { throw BackupException(R.string.backup_invalid) }
    }

    private fun JSONObject.stringOrNull(key: String): String? {
        val value = opt(key)
        if (value == null || value == JSONObject.NULL || value == "null") return null
        return value as? String ?: throw BackupException(R.string.backup_invalid)
    }

    private fun InputStream.readLimited(limit: Long): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var count = 0L
        while (true) {
            val n = read(buffer)
            if (n < 0) break
            count += n
            if (count > limit) throw BackupException(R.string.backup_manifest_too_large)
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    private fun InputStream.copyLimitedTo(output: OutputStream, limit: Long): Long {
        val buffer = ByteArray(8192)
        var count = 0L
        while (true) {
            val n = read(buffer)
            if (n < 0) break
            count += n
            if (count > limit) throw BackupException(R.string.backup_too_large)
            output.write(buffer, 0, n)
        }
        return count
    }

    private fun InputStream.copyToBounded(output: OutputStream, limit: Long) {
        val buffer = ByteArray(8192)
        var count = 0L
        while (true) {
            val n = read(buffer)
            if (n < 0) break
            count += n
            if (count > limit) throw BackupException(R.string.backup_too_large)
            output.write(buffer, 0, n)
        }
    }
}
