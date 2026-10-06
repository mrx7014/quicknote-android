package com.quicknote.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class TaskReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(EXTRA_ENTRY_ID, -1L)
        if (id < 0) return
        EntryDb(context).use { db ->
            val entry = db.get(id) ?: return
            if (entry.type == "task" && !entry.done) NotificationHelper.showTaskReminder(context, entry)
        }
    }
    companion object { const val EXTRA_ENTRY_ID = "entry_id" }
}

class TaskActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(TaskReminderReceiver.EXTRA_ENTRY_ID, -1L)
        if (id < 0) return
        EntryDb(context).use { db ->
            val entry = db.get(id) ?: return
            when (intent.action) {
                ACTION_COMPLETE -> {
                    db.setDone(id, true)
                    ReminderScheduler.cancel(context, id)
                    db.get(id)?.let { NotificationHelper.showItem(context, it) }
                    NotificationHelper.cancelReminder(context, id)
                }
                ACTION_SNOOZE -> {
                    val next = System.currentTimeMillis() + SNOOZE_MILLIS
                    db.setReminder(id, next)
                    ReminderScheduler.schedule(context, id, next)
                    NotificationHelper.cancelReminder(context, id)
                }
            }
        }
    }
    companion object {
        const val ACTION_COMPLETE = "com.quicknote.app.COMPLETE_TASK"
        const val ACTION_SNOOZE = "com.quicknote.app.SNOOZE_TASK"
        private const val SNOOZE_MILLIS = 10 * 60 * 1000L
    }
}
