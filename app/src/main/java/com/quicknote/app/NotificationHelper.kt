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
    private const val CHANNEL_SAVED = "saved_items"
    private const val CHANNEL_UNLOCK = "unlock_prompt"
    private const val PROMPT_ID = 1001
    private const val ITEM_BASE_ID = 10_000

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL_SAVED, "Saved notes and tasks", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Persistent reminders for items saved in QuickNote"
                setShowBadge(true)
            })
            manager.createNotificationChannel(NotificationChannel(CHANNEL_UNLOCK, "Quick capture on unlock", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "A reminder to quickly capture a note after unlocking your phone"
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
        createChannels(context)
        val notification = NotificationCompat.Builder(context, CHANNEL_UNLOCK)
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setContentTitle("QuickNote is ready")
            .setContentText("Tap to add a note, to-do, or voice note")
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openApp(context, PROMPT_ID))
            .build()
        context.getSystemService(NotificationManager::class.java).notify(PROMPT_ID, notification)
    }

    fun showItem(context: Context, entry: Entry) {
        if (!allowed(context)) return
        createChannels(context)
        val title = when (entry.type) {
            "task" -> if (entry.done) "Done" else "To-do"
            "voice" -> "Voice note"
            else -> "Note"
        }
        val content = if (entry.type == "voice") "Tap to open your saved recording" else entry.content
        val notification = NotificationCompat.Builder(context, CHANNEL_SAVED)
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setContentTitle(title)
            .setContentText(content.ifBlank { "Saved in QuickNote" })
            .setStyle(NotificationCompat.BigTextStyle().bigText(content.ifBlank { "Saved in QuickNote" }))
            .setContentIntent(openApp(context, ITEM_BASE_ID + entry.id.toInt()))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setOngoing(!entry.done)
            .setOnlyAlertOnce(true)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(ITEM_BASE_ID + entry.id.toInt(), notification)
    }

    fun removeItem(context: Context, id: Long) {
        context.getSystemService(NotificationManager::class.java).cancel(ITEM_BASE_ID + id.toInt())
    }
}
