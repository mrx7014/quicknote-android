package com.quicknote.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build

object ReminderScheduler {
    private fun pending(context: Context, id: Long): PendingIntent {
        val intent = Intent(context, TaskReminderReceiver::class.java)
            .setData(Uri.parse("quicknote://reminder/$id"))
            .putExtra(TaskReminderReceiver.EXTRA_ENTRY_ID, id)
        return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    fun schedule(context: Context, entryId: Long, at: Long?) {
        val alarms = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pendingIntent = pending(context, entryId)
        if (at == null) alarms.cancel(pendingIntent)
        else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pendingIntent)
        else alarms.set(AlarmManager.RTC_WAKEUP, at, pendingIntent)
    }

    fun cancel(context: Context, entryId: Long) = schedule(context, entryId, null)

    fun rescheduleAll(context: Context) {
        EntryDb(context).use { db ->
            val now = System.currentTimeMillis()
            val tasks = db.all().filter { it.type == "task" && !it.done && it.remindAt != null }
            tasks.filter { it.remindAt!! > now }.forEach { schedule(context, it.id, it.remindAt) }
            val overdue = tasks.filter { it.remindAt!! <= now }
            if (overdue.isNotEmpty() && NotificationHelper.showOverdueSummary(context, overdue.size)) {
                overdue.forEach { db.setReminder(it.id, null); cancel(context, it.id) }
            }
        }
    }
}
