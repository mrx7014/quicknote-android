package com.quicknote.app

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

object BackupManager {
    private const val MANIFEST = "manifest.json"
    private const val MAX_MANIFEST_BYTES = 2L * 1024 * 1024
    private const val MAX_BACKUP_BYTES = 512L * 1024 * 1024

    fun export(context: Context, uri: Uri): Int {
        val db = EntryDb(context)
        val items = try { db.all() } finally { db.close() }
        context.contentResolver.openOutputStream(uri, "w")?.use { raw ->
            ZipOutputStream(raw).use { zip ->
                val array = JSONArray()
                items.forEach { entry ->
                    val obj = JSONObject()
                        .put("type", entry.type).put("content", entry.content).put("createdAt", entry.createdAt)
                        .put("done", entry.done).put("favorite", entry.favorite).put("tag", entry.tag)
                    if (entry.remindAt != null) obj.put("remindAt", entry.remindAt)
                    val audio = entry.audioPath?.let(::File)?.takeIf { it.isFile }
                    if (audio != null) {
                        val archivePath = "media/${entry.id}${audio.extension.takeIf { it.isNotBlank() }?.let { ".$it" } ?: ".audio"}"
                        obj.put("audioArchive", archivePath)
                    } else obj.put("audioArchive", JSONObject.NULL)
                    array.put(obj)
                }
                val manifest = JSONObject().put("format", "quicknote-backup").put("version", 1).put("entries", array)
                zip.putNextEntry(ZipEntry(MANIFEST)); zip.write(manifest.toString().toByteArray(Charsets.UTF_8)); zip.closeEntry()
                items.forEach { entry ->
                    val audio = entry.audioPath?.let(::File)?.takeIf { it.isFile } ?: return@forEach
                    val name = "media/${entry.id}${audio.extension.takeIf { it.isNotBlank() }?.let { ".$it" } ?: ".audio"}"
                    zip.putNextEntry(ZipEntry(name)); audio.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
                }
            }
        } ?: error(context.getString(R.string.backup_open_failed))
        return items.size
    }

    fun restore(context: Context, uri: Uri): Int {
        val temp = File(context.cacheDir, "restore_${System.currentTimeMillis()}").apply { mkdirs() }
        val extracted = mutableMapOf<String, File>()
        var manifest: String? = null
        var total = 0L
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                ZipInputStream(input).use { zip ->
                    while (true) {
                        val item = zip.nextEntry ?: break
                        val name = item.name
                        require(!name.startsWith("/") && !name.contains("..") && !name.contains('\\')) { context.getString(R.string.backup_invalid) }
                        when {
                            name == MANIFEST -> {
                                val data = zip.readBytesLimited(MAX_MANIFEST_BYTES)
                                manifest = data.toString(Charsets.UTF_8)
                                total += data.size
                            }
                            name.startsWith("media/") && !item.isDirectory -> {
                                val safeName = name.removePrefix("media/")
                                require(safeName.isNotBlank() && !safeName.contains('/')) { context.getString(R.string.backup_invalid) }
                                val out = File(temp, safeName)
                                val count = out.outputStream().use { zip.copyLimitedTo(it, MAX_BACKUP_BYTES - total) }
                                total += count; extracted[name] = out
                            }
                        }
                        require(total <= MAX_BACKUP_BYTES) { context.getString(R.string.backup_too_large) }
                        zip.closeEntry()
                    }
                }
            } ?: error(context.getString(R.string.backup_open_failed))
            val root = JSONObject(manifest ?: error(context.getString(R.string.backup_invalid)))
            require(root.optString("format") == "quicknote-backup") { context.getString(R.string.backup_invalid) }
            val array = root.getJSONArray("entries")
            val importedMedia = mutableListOf<File>()
            val entries = mutableListOf<Entry>()
            var committed = false
            try {
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val archivePath = obj.optString("audioArchive").takeIf { it.isNotBlank() && it != "null" }
                    var audioPath: String? = null
                    if (archivePath != null) {
                        val source = extracted[archivePath] ?: error(context.getString(R.string.backup_missing_audio))
                        val extension = source.extension.filter { it.isLetterOrDigit() }.take(8).ifBlank { "audio" }
                        val target = File(context.filesDir, "restored_${System.currentTimeMillis()}_${i}.$extension")
                        source.copyTo(target, overwrite = true); importedMedia += target; audioPath = target.absolutePath
                    }
                    val type = obj.optString("type", "note")
                    require(type in setOf("note", "task", "voice")) { context.getString(R.string.backup_invalid) }
                    entries += Entry(
                        id = 0L, type = type, content = obj.optString("content"), audioPath = audioPath,
                        createdAt = obj.optLong("createdAt", System.currentTimeMillis()), done = obj.optBoolean("done"),
                        favorite = obj.optBoolean("favorite"), tag = obj.optString("tag"),
                        remindAt = if (obj.has("remindAt") && !obj.isNull("remindAt")) obj.optLong("remindAt") else null
                    )
                }
                val db = EntryDb(context)
                val oldEntries: List<Entry>
                val oldPaths: List<String>
                val restoredEntries: List<Entry>
                try {
                    oldEntries = db.all()
                    oldPaths = db.replaceAll(entries)
                    restoredEntries = db.all()
                } finally { db.close() }
                committed = true
                oldPaths.forEach { File(it).delete() }
                oldEntries.forEach { old ->
                    ReminderScheduler.cancel(context, old.id)
                    NotificationHelper.removeItem(context, old.id)
                    NotificationHelper.cancelReminder(context, old.id)
                }
                ReminderScheduler.rescheduleAll(context)
                restoredEntries.forEach { NotificationHelper.showItem(context, it) }
                return entries.size
            } catch (error: Throwable) {
                if (!committed) importedMedia.forEach { it.delete() }
                throw error
            }
        } finally { temp.deleteRecursively() }
    }

    private fun java.util.zip.ZipInputStream.readBytesLimited(limit: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192); var count = 0L
        while (true) { val n = read(buffer); if (n < 0) break; count += n; require(count <= limit) { "Backup manifest too large" }; out.write(buffer, 0, n) }
        return out.toByteArray()
    }

    private fun java.util.zip.ZipInputStream.copyLimitedTo(out: java.io.OutputStream, limit: Long): Long {
        val buffer = ByteArray(8192); var count = 0L
        while (true) { val n = read(buffer); if (n < 0) break; count += n; require(count <= limit) { "Backup is too large" }; out.write(buffer, 0, n) }
        return count
    }
}
