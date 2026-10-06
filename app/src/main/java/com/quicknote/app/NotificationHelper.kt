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
    private const val CHANNEL_SERVICE = "popup_service"
    private const val PROMPT_ID = 1001
    private const val ITEM_BASE_ID = 10_000

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL_SAVED, "الملاحظات والمهام المحفوظة", NotificationManager.IMPORTANCE_LOW).apply {
                description = "تذكيرات بالعناصر المحفوظة في QuickNote"
                setShowBadge(true)
            })
            manager.createNotificationChannel(NotificationChannel(CHANNEL_UNLOCK, "تذكير التسجيل السريع", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "تذكير بفتح QuickNote عند فتح الهاتف إذا لم تُمنح صلاحية النافذة"
            })
            manager.createNotificationChannel(NotificationChannel(CHANNEL_SERVICE, "نافذة التسجيل عند الفتح", NotificationManager.IMPORTANCE_LOW).apply {
                description = "إشعار مستمر لإبقاء نافذة التسجيل العائمة جاهزة عند فتح الهاتف"
                setShowBadge(false)
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
            .setContentTitle("QuickNote جاهز")
            .setContentText("اضغط لتسجيل ملاحظة أو مهمة أو رسالة صوتية")
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openApp(context, PROMPT_ID))
            .build()
        context.getSystemService(NotificationManager::class.java).notify(PROMPT_ID, notification)
    }

    fun serviceNotification(context: Context): Notification {
        createChannels(context)
        return NotificationCompat.Builder(context, CHANNEL_SERVICE)
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setContentTitle("QuickNote — نافذة التسجيل مفعّلة")
            .setContentText("ستظهر بطاقة التسجيل بعد فتح الهاتف")
            .setContentIntent(openApp(context, 6001))
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    fun showItem(context: Context, entry: Entry) {
        if (!allowed(context)) return
        createChannels(context)
        val title = when (entry.type) {
            "task" -> if (entry.done) "مهمة مكتملة" else "مهمة جديدة"
            "voice" -> "رسالة صوتية"
            else -> "ملاحظة جديدة"
        }
        val content = if (entry.type == "voice") "اضغط لفتح التسجيل الصوتي المحفوظ" else entry.content
        val notification = NotificationCompat.Builder(context, CHANNEL_SAVED)
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setContentTitle(title)
            .setContentText(content.ifBlank { "محفوظة في QuickNote" })
            .setStyle(NotificationCompat.BigTextStyle().bigText(content.ifBlank { "محفوظة في QuickNote" }))
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
