package com.quicknote.app

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

object NotificationHelper {
    private const val CHANNEL_SAVED = "saved_inbox_v4"
    private const val CHANNEL_UNLOCK = "unlock_prompt_v4"
    private const val CHANNEL_SERVICE = "popup_service_v4"
    private const val CHANNEL_REMINDER = "task_reminders_v2"
    private const val PROMPT_ID = 1001
    private const val ITEM_INBOX_ID = 1002
    private const val OVERDUE_SUMMARY_ID = 1003
    private const val REMINDER_NAMESPACE = 0x51A7
    private const val PREF_LATEST_ITEM = "latest_item_notification_id"

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL_SAVED, context.getString(R.string.channel_saved), NotificationManager.IMPORTANCE_LOW).apply {
                description = context.getString(R.string.channel_saved_description)
                setShowBadge(false)
            })
            manager.createNotificationChannel(NotificationChannel(CHANNEL_UNLOCK, context.getString(R.string.channel_unlock), NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = context.getString(R.string.channel_unlock_description)
            })
            manager.createNotificationChannel(NotificationChannel(CHANNEL_SERVICE, context.getString(R.string.channel_service), NotificationManager.IMPORTANCE_LOW).apply {
                description = context.getString(R.string.channel_service_description)
                setShowBadge(false)
            })
            manager.createNotificationChannel(NotificationChannel(CHANNEL_REMINDER, context.getString(R.string.channel_reminder), NotificationManager.IMPORTANCE_HIGH).apply {
                description = context.getString(R.string.channel_reminder_description)
            })
        }
    }

    private fun allowed(context: Context): Boolean = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun stableId(namespace: Int, value: Long): Int {
        var mixed = value xor (value ushr 33)
        mixed *= -49064778989728563L
        mixed = mixed xor (mixed ushr 33)
        mixed *= -4265267296055464877L
        mixed = mixed xor (mixed ushr 33)
        val id = (mixed.toInt() xor namespace) and 0x7fffffff
        return if (id == 0) namespace else id
    }

    private fun openApp(context: Context, requestCode: Int, data: String? = null): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (data != null) intent.data = Uri.parse("quicknote://open/$data")
        return PendingIntent.getActivity(context, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    fun showUnlockPrompt(context: Context) {
        if (!allowed(context)) return
        val c = AppLocale.wrap(context)
        createChannels(c)
        val notification = NotificationCompat.Builder(c, CHANNEL_UNLOCK)
            .setSmallIcon(R.drawable.ic_stat_quicknote)
            .setColor(AppPalette.from(c).teal)
            .setContentTitle(c.getString(R.string.unlock_notification_title))
            .setContentText(c.getString(R.string.unlock_notification_text))
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(openApp(c, PROMPT_ID))
            .build()
        c.getSystemService(NotificationManager::class.java).notify(PROMPT_ID, notification)
    }

    fun serviceNotification(context: Context): Notification {
        val c = AppLocale.wrap(context)
        createChannels(c)
        return NotificationCompat.Builder(c, CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_stat_quicknote)
            .setColor(AppPalette.from(c).teal)
            .setContentTitle(c.getString(R.string.service_notification_title))
            .setContentText(c.getString(R.string.service_notification_text))
            .setContentIntent(openApp(c, QuickCaptureService.SERVICE_NOTIFICATION_ID))
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    /** One non-ongoing inbox notification, updated only after a user-visible item change. */
    fun showItem(context: Context, entry: Entry) {
        if (!allowed(context)) return
        val c = AppLocale.wrap(context)
        createChannels(c)
        val typeTitle = when (entry.type) {
            "task" -> c.getString(if (entry.done) R.string.notification_done_task else R.string.notification_new_task)
            "voice" -> entry.title?.takeIf(String::isNotBlank) ?: c.getString(R.string.notification_voice)
            else -> entry.title?.takeIf(String::isNotBlank) ?: c.getString(R.string.notification_new_note)
        }
        val rawContent = when {
            entry.type == "voice" && !entry.transcript.isNullOrBlank() -> entry.transcript
            entry.type == "voice" -> c.getString(R.string.notification_open_voice)
            else -> entry.content
        }
        val hidden = AppPreferences.hideNotificationContent(c) || AppPreferences.appLockEnabled(c)
        val title = if (hidden) c.getString(R.string.app_name) else typeTitle
        val content = if (hidden) c.getString(R.string.notification_private) else rawContent?.ifBlank { c.getString(R.string.notification_saved_fallback) }
            ?: c.getString(R.string.notification_saved_fallback)
        val notification = NotificationCompat.Builder(c, CHANNEL_SAVED)
            .setSmallIcon(R.drawable.ic_stat_quicknote)
            .setColor(AppPalette.from(c).teal)
            .setContentTitle(title)
            .setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setContentIntent(openApp(c, ITEM_INBOX_ID, "entry/${entry.id}"))
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(genericPublic(c))
            .build()
        c.getSharedPreferences("quicknote_preferences", Context.MODE_PRIVATE).edit().putLong(PREF_LATEST_ITEM, entry.id).apply()
        c.getSystemService(NotificationManager::class.java).notify(ITEM_INBOX_ID, notification)
    }

    fun showTaskReminder(context: Context, entry: Entry) {
        if (!allowed(context)) return
        val c = AppLocale.wrap(context)
        createChannels(c)
        val hidden = AppPreferences.hideNotificationContent(c) || AppPreferences.appLockEnabled(c)
        val title = if (hidden) c.getString(R.string.app_name) else c.getString(R.string.task_reminder_title)
        val body = if (hidden) c.getString(R.string.notification_private) else entry.content
        val notificationId = stableId(REMINDER_NAMESPACE, entry.id)
        val complete = actionIntent(c, TaskActionReceiver.ACTION_COMPLETE, entry.id, notificationId * 2)
        val snooze = actionIntent(c, TaskActionReceiver.ACTION_SNOOZE, entry.id, notificationId * 2 + 1)
        val notification = NotificationCompat.Builder(c, CHANNEL_REMINDER)
            .setSmallIcon(R.drawable.ic_stat_quicknote)
            .setColor(AppPalette.from(c).teal)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(openApp(c, notificationId, "reminder/${entry.id}"))
            .setAutoCancel(true)
            .addAction(0, c.getString(R.string.task_complete_action), complete)
            .addAction(0, c.getString(R.string.task_snooze_action), snooze)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(genericPublic(c))
            .build()
        c.getSystemService(NotificationManager::class.java).notify(notificationId, notification)
    }

    /** Posts one dismissible digest instead of firing every overdue alarm at boot. */
    fun showOverdueSummary(context: Context, count: Int): Boolean {
        if (count <= 0 || !allowed(context)) return false
        val c = AppLocale.wrap(context)
        createChannels(c)
        val body = c.resources.getQuantityString(R.plurals.overdue_summary_text, count, count)
        val notification = NotificationCompat.Builder(c, CHANNEL_REMINDER)
            .setSmallIcon(R.drawable.ic_stat_quicknote)
            .setColor(AppPalette.from(c).teal)
            .setContentTitle(c.getString(R.string.overdue_summary_title))
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(openApp(c, OVERDUE_SUMMARY_ID, "overdue"))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(genericPublic(c))
            .build()
        c.getSystemService(NotificationManager::class.java).notify(OVERDUE_SUMMARY_ID, notification)
        return true
    }

    private fun actionIntent(context: Context, action: String, id: Long, request: Int): PendingIntent {
        val intent = Intent(context, TaskActionReceiver::class.java)
            .setAction(action)
            .setData(Uri.parse("quicknote://task-action/$action/$id"))
            .putExtra(TaskReminderReceiver.EXTRA_ENTRY_ID, id)
        return PendingIntent.getBroadcast(context, request, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun genericPublic(context: Context) = NotificationCompat.Builder(context, CHANNEL_SAVED)
        .setSmallIcon(R.drawable.ic_stat_quicknote)
        .setColor(AppPalette.from(context).teal)
        .setContentTitle(context.getString(R.string.app_name))
        .setContentText(context.getString(R.string.notification_private))
        .build()

    fun removeItem(context: Context, id: Long) {
        val prefs = context.getSharedPreferences("quicknote_preferences", Context.MODE_PRIVATE)
        if (prefs.getLong(PREF_LATEST_ITEM, -1L) == id) {
            context.getSystemService(NotificationManager::class.java).cancel(ITEM_INBOX_ID)
            prefs.edit().remove(PREF_LATEST_ITEM).apply()
        }
    }

    fun cancelReminder(context: Context, id: Long) {
        context.getSystemService(NotificationManager::class.java).cancel(stableId(REMINDER_NAMESPACE, id))
    }

    /** Updates only notifications the system still considers active; dismissed confirmations stay dismissed. */
    fun refreshPrivacy(context: Context, entries: List<Entry>) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val activeIds = manager.activeNotifications.map { it.id }.toSet()
        if (ITEM_INBOX_ID in activeIds) {
            val latestId = context.getSharedPreferences("quicknote_preferences", Context.MODE_PRIVATE).getLong(PREF_LATEST_ITEM, -1L)
            entries.firstOrNull { it.id == latestId }?.let { showItem(context, it) } ?: manager.cancel(ITEM_INBOX_ID)
        }
        entries.filter { it.type == "task" && !it.done && stableId(REMINDER_NAMESPACE, it.id) in activeIds }
            .forEach { showTaskReminder(context, it) }
    }

    fun cancelAllEntryNotifications(context: Context, entry: Entry) {
        removeItem(context, entry.id)
        cancelReminder(context, entry.id)
    }
}
