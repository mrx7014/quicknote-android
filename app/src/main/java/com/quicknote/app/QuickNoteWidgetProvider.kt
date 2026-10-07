package com.quicknote.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.widget.RemoteViews
import java.util.concurrent.Executors

class QuickNoteWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        refreshAll(context)
    }

    private fun update(context: Context, manager: AppWidgetManager, id: Int, entries: List<Entry>) {
        val localized = AppLocale.wrap(context)
        val views = RemoteViews(context.packageName, R.layout.quicknote_widget)
        val palette = AppPalette.from(localized)
        views.setTextViewText(R.id.widget_title, localized.getString(R.string.app_name))
        views.setTextViewText(R.id.widget_note, localized.getString(R.string.widget_note))
        views.setTextViewText(R.id.widget_task, localized.getString(R.string.widget_task))
        views.setTextViewText(R.id.widget_voice, localized.getString(R.string.widget_voice))
        views.setTextColor(R.id.widget_title, palette.ink)
        views.setTextColor(R.id.widget_note, palette.teal)
        views.setTextColor(R.id.widget_task, palette.teal)
        views.setTextColor(R.id.widget_voice, palette.teal)
        views.setTextColor(R.id.widget_recent, palette.muted)
        views.setTextColor(R.id.widget_meta, palette.muted)
        val background = if (palette.dark) R.drawable.widget_background_dark else R.drawable.widget_background
        val button = if (palette.dark) R.drawable.widget_button_background_dark else R.drawable.widget_button_background
        views.setInt(R.id.widget_root, "setBackgroundResource", background)
        listOf(R.id.widget_note, R.id.widget_task, R.id.widget_voice).forEach { views.setInt(it, "setBackgroundResource", button) }

        val visible = entries.filter { !it.archived && it.deletedAt == null }
        val latest = visible.maxByOrNull { it.updatedAt }
        val hidden = AppPreferences.hideNotificationContent(localized) || AppPreferences.appLockEnabled(localized) || AppPreferences.protectScreenContent(localized)
        val latestText = when {
            latest == null -> localized.getString(R.string.widget_no_items)
            hidden -> localized.getString(R.string.notification_private)
            latest.type == "voice" -> latest.title?.takeIf(String::isNotBlank) ?: latest.transcript?.takeIf(String::isNotBlank) ?: localized.getString(R.string.item_voice)
            else -> latest.title?.takeIf(String::isNotBlank) ?: latest.content
        }
        views.setTextViewText(R.id.widget_recent, latestText)
        val openTasks = visible.count { it.type == "task" && !it.done }
        views.setTextViewText(R.id.widget_meta, localized.resources.getQuantityString(R.plurals.widget_open_tasks, openTasks, openTasks))
        if (latest != null) {
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(MainActivity.EXTRA_OPEN_ENTRY_ID, latest.id)
            }
            val pending = PendingIntent.getActivity(context, 7010 + id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            views.setOnClickPendingIntent(R.id.widget_recent, pending)
        }
        setCaptureIntent(context, views, id * 10 + 1, R.id.widget_note, "note")
        setCaptureIntent(context, views, id * 10 + 2, R.id.widget_task, "task")
        setCaptureIntent(context, views, id * 10 + 3, R.id.widget_voice, "voice")
        manager.updateAppWidget(id, views)
    }

    private fun setCaptureIntent(context: Context, views: RemoteViews, request: Int, viewId: Int, type: String) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(MainActivity.EXTRA_CAPTURE_TYPE, type)
        }
        val pending = PendingIntent.getActivity(context, request, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        views.setOnClickPendingIntent(viewId, pending)
    }

    companion object {
        private val executor = Executors.newSingleThreadExecutor()

        fun refreshAll(context: Context) {
            val appContext = context.applicationContext
            executor.execute {
                val manager = AppWidgetManager.getInstance(appContext)
                val ids = manager.getAppWidgetIds(ComponentName(appContext, QuickNoteWidgetProvider::class.java))
                if (ids.isEmpty()) return@execute
                val entries = EntryDb(appContext).use { it.all(includeArchived = true, includeDeleted = true) }
                val provider = QuickNoteWidgetProvider()
                ids.forEach { provider.update(appContext, manager, it, entries) }
            }
        }
    }
}
