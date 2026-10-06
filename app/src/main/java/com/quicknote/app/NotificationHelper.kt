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
    private const val CHANNEL_SAVED = "saved_items_v2"
    private const val CHANNEL_UNLOCK = "unlock_prompt_v2"
    private const val CHANNEL_SERVICE = "popup_service_v2"
    private const val PROMPT_ID = 1001
    private const val ITEM_BASE_ID = 10_000

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL_SAVED, context.getString(R.string.channel_saved), NotificationManager.IMPORTANCE_LOW).apply {
                description = context.getString(R.string.channel_saved_description); setShowBadge(true)
            })
            manager.createNotificationChannel(NotificationChannel(CHANNEL_UNLOCK, context.getString(R.string.channel_unlock), NotificationManager.IMPORTANCE_HIGH).apply {
                description = context.getString(R.string.channel_unlock_description)
            })
            manager.createNotificationChannel(NotificationChannel(CHANNEL_SERVICE, context.getString(R.string.channel_service), NotificationManager.IMPORTANCE_LOW).apply {
                description = context.getString(R.string.channel_service_description); setShowBadge(false)
            })
        }
    }

    private fun allowed(context: Context): Boolean = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun openApp(context: Context, requestCode: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(context, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    fun showUnlockPrompt(context: Context) {
        if (!allowed(context)) return
        val localized = AppLocale.wrap(context)
        createChannels(localized)
        val notification = NotificationCompat.Builder(localized, CHANNEL_UNLOCK)
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setContentTitle(localized.getString(R.string.unlock_notification_title))
            .setContentText(localized.getString(R.string.unlock_notification_text))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openApp(localized, PROMPT_ID))
            .build()
        localized.getSystemService(NotificationManager::class.java).notify(PROMPT_ID, notification)
    }

    fun serviceNotification(context: Context): Notification {
        val localized = AppLocale.wrap(context)
        createChannels(localized)
        return NotificationCompat.Builder(localized, CHANNEL_SERVICE)
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setContentTitle(localized.getString(R.string.service_notification_title))
            .setContentText(localized.getString(R.string.service_notification_text))
            .setContentIntent(openApp(localized, QuickCaptureService.SERVICE_NOTIFICATION_ID))
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    fun showItem(context: Context, entry: Entry) {
        if (!allowed(context)) return
        val localized = AppLocale.wrap(context)
        createChannels(localized)
        val title = when (entry.type) {
            "task" -> localized.getString(if (entry.done) R.string.notification_done_task else R.string.notification_new_task)
            "voice" -> localized.getString(R.string.notification_voice)
            else -> localized.getString(R.string.notification_new_note)
        }
        val content = if (entry.type == "voice") localized.getString(R.string.notification_open_voice) else entry.content
        val fallback = localized.getString(R.string.notification_saved_fallback)
        val notification = NotificationCompat.Builder(localized, CHANNEL_SAVED)
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setContentTitle(title)
            .setContentText(content.ifBlank { fallback })
            .setStyle(NotificationCompat.BigTextStyle().bigText(content.ifBlank { fallback }))
            .setContentIntent(openApp(localized, ITEM_BASE_ID + entry.id.toInt()))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setOngoing(entry.type == "task" && !entry.done)
            .setOnlyAlertOnce(true)
            .build()
        localized.getSystemService(NotificationManager::class.java).notify(ITEM_BASE_ID + entry.id.toInt(), notification)
    }

    fun removeItem(context: Context, id: Long) {
        context.getSystemService(NotificationManager::class.java).cancel(ITEM_BASE_ID + id.toInt())
    }
}
