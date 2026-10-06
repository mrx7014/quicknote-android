package com.quicknote.app

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

object NotificationHelper {
    private const val CHANNEL_SAVED = "saved_items_v3"
    private const val CHANNEL_UNLOCK = "unlock_prompt_v3"
    private const val CHANNEL_SERVICE = "popup_service_v3"
    private const val CHANNEL_REMINDER = "task_reminders_v1"
    private const val PROMPT_ID = 1001
    private const val ITEM_BASE_ID = 10_000
    private const val REMINDER_BASE_ID = 20_000

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL_SAVED, context.getString(R.string.channel_saved), NotificationManager.IMPORTANCE_LOW).apply {
                description = context.getString(R.string.channel_saved_description); setShowBadge(true)
            })
            manager.createNotificationChannel(NotificationChannel(CHANNEL_UNLOCK, context.getString(R.string.channel_unlock), NotificationManager.IMPORTANCE_HIGH).apply { description = context.getString(R.string.channel_unlock_description) })
            manager.createNotificationChannel(NotificationChannel(CHANNEL_SERVICE, context.getString(R.string.channel_service), NotificationManager.IMPORTANCE_LOW).apply { description = context.getString(R.string.channel_service_description); setShowBadge(false) })
            manager.createNotificationChannel(NotificationChannel(CHANNEL_REMINDER, context.getString(R.string.channel_reminder), NotificationManager.IMPORTANCE_HIGH).apply { description = context.getString(R.string.channel_reminder_description) })
        }
    }

    private fun allowed(context: Context): Boolean = Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun openApp(context: Context, requestCode: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(context, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    fun showUnlockPrompt(context: Context) {
        if (!allowed(context)) return
        val c = AppLocale.wrap(context); createChannels(c)
        val notification = NotificationCompat.Builder(c, CHANNEL_UNLOCK)
            .setSmallIcon(android.R.drawable.ic_menu_edit).setContentTitle(c.getString(R.string.unlock_notification_title))
            .setContentText(c.getString(R.string.unlock_notification_text)).setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH).setAutoCancel(true).setContentIntent(openApp(c, PROMPT_ID)).build()
        c.getSystemService(NotificationManager::class.java).notify(PROMPT_ID, notification)
    }

    fun serviceNotification(context: Context): Notification {
        val c = AppLocale.wrap(context); createChannels(c)
        return NotificationCompat.Builder(c, CHANNEL_SERVICE).setSmallIcon(android.R.drawable.ic_menu_edit)
            .setContentTitle(c.getString(R.string.service_notification_title)).setContentText(c.getString(R.string.service_notification_text))
            .setContentIntent(openApp(c, QuickCaptureService.SERVICE_NOTIFICATION_ID)).setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true).setOnlyAlertOnce(true).build()
    }

    fun showItem(context: Context, entry: Entry) {
        if (!allowed(context)) return
        val c = AppLocale.wrap(context); createChannels(c)
        val title = when (entry.type) {
            "task" -> c.getString(if (entry.done) R.string.notification_done_task else R.string.notification_new_task)
            "voice" -> c.getString(R.string.notification_voice)
            else -> c.getString(R.string.notification_new_note)
        }
        val content = if (entry.type == "voice" && entry.content.startsWith(c.getString(R.string.item_voice))) c.getString(R.string.notification_open_voice) else entry.content
        val hidden = AppPreferences.hideNotificationContent(c)
        val actualTitle = if (hidden) c.getString(R.string.app_name) else title
        val actualContent = if (hidden) c.getString(R.string.notification_private) else content.ifBlank { c.getString(R.string.notification_saved_fallback) }
        val publicVersion = genericPublic(c)
        val notification = NotificationCompat.Builder(c, CHANNEL_SAVED).setSmallIcon(android.R.drawable.ic_menu_edit)
            .setContentTitle(actualTitle).setContentText(actualContent).setStyle(NotificationCompat.BigTextStyle().bigText(actualContent))
            .setContentIntent(openApp(c, ITEM_BASE_ID + entry.id.toInt())).setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setOngoing(entry.type == "task" && !entry.done).setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setPublicVersion(publicVersion).build()
        c.getSystemService(NotificationManager::class.java).notify(ITEM_BASE_ID + entry.id.toInt(), notification)
    }

    fun showTaskReminder(context: Context, entry: Entry) {
        if (!allowed(context)) return
        val c = AppLocale.wrap(context); createChannels(c)
        val hidden = AppPreferences.hideNotificationContent(c)
        val title = if (hidden) c.getString(R.string.app_name) else c.getString(R.string.task_reminder_title)
        val text = if (hidden) c.getString(R.string.notification_private) else entry.content
        val complete = actionIntent(c, TaskActionReceiver.ACTION_COMPLETE, entry.id, 31_000 + entry.id.toInt())
        val snooze = actionIntent(c, TaskActionReceiver.ACTION_SNOOZE, entry.id, 41_000 + entry.id.toInt())
        val notification = NotificationCompat.Builder(c, CHANNEL_REMINDER).setSmallIcon(android.R.drawable.ic_menu_edit)
            .setContentTitle(title).setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openApp(c, REMINDER_BASE_ID + entry.id.toInt())).setAutoCancel(true)
            .addAction(0, c.getString(R.string.task_complete_action), complete)
            .addAction(0, c.getString(R.string.task_snooze_action), snooze)
            .setCategory(NotificationCompat.CATEGORY_REMINDER).setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setPublicVersion(genericPublic(c)).build()
        c.getSystemService(NotificationManager::class.java).notify(REMINDER_BASE_ID + entry.id.toInt(), notification)
    }

    private fun actionIntent(context: Context, action: String, id: Long, request: Int): PendingIntent {
        val intent = Intent(context, TaskActionReceiver::class.java).setAction(action).putExtra(TaskReminderReceiver.EXTRA_ENTRY_ID, id)
        return PendingIntent.getBroadcast(context, request, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun genericPublic(context: Context) = NotificationCompat.Builder(context, CHANNEL_SAVED)
        .setSmallIcon(android.R.drawable.ic_menu_edit).setContentTitle(context.getString(R.string.app_name))
        .setContentText(context.getString(R.string.notification_private)).build()

    fun removeItem(context: Context, id: Long) { context.getSystemService(NotificationManager::class.java).cancel(ITEM_BASE_ID + id.toInt()) }
    fun cancelReminder(context: Context, id: Long) { context.getSystemService(NotificationManager::class.java).cancel(REMINDER_BASE_ID + id.toInt()) }

    fun refreshPrivacy(context: Context, entries: List<Entry>) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val active = manager.activeNotifications.map { it.id }.toSet()
        entries.forEach { entry ->
            showItem(context, entry)
            if (entry.type == "task" && active.contains(REMINDER_BASE_ID + entry.id.toInt())) showTaskReminder(context, entry)
        }
    }
}
