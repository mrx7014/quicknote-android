package com.quicknote.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

object ReminderScheduler {
    private fun pending(context: Context, id: Long): PendingIntent {
        val intent = Intent(context, TaskReminderReceiver::class.java).putExtra(TaskReminderReceiver.EXTRA_ENTRY_ID, id)
        return PendingIntent.getBroadcast(context, id.toInt(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    fun schedule(context: Context, entryId: Long, at: Long?) {
        val alarms = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pending = pending(context, entryId)
        if (at == null) alarms.cancel(pending)
        else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
            else alarms.set(AlarmManager.RTC_WAKEUP, at, pending)
        }
    }

    fun cancel(context: Context, entryId: Long) = schedule(context, entryId, null)

    fun rescheduleAll(context: Context) {
        EntryDb(context).use { db -> db.all().filter { it.type == "task" && !it.done && it.remindAt != null }.forEach { schedule(context, it.id, it.remindAt) } }
    }
}
