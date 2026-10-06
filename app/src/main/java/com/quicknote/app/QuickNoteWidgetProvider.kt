package com.quicknote.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

class QuickNoteWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetIds.forEach { update(context, manager, it) }
    }

    private fun update(context: Context, manager: AppWidgetManager, id: Int) {
        val localized = AppLocale.wrap(context)
        val views = RemoteViews(context.packageName, R.layout.quicknote_widget)
        views.setTextViewText(R.id.widget_title, localized.getString(R.string.app_name))
        views.setTextViewText(R.id.widget_note, localized.getString(R.string.widget_note))
        views.setTextViewText(R.id.widget_task, localized.getString(R.string.widget_task))
        views.setTextViewText(R.id.widget_voice, localized.getString(R.string.widget_voice))
        val palette = AppPalette.from(localized)
        views.setInt(R.id.widget_root, "setBackgroundColor", palette.surface)
        views.setTextColor(R.id.widget_title, palette.ink)
        views.setTextColor(R.id.widget_note, palette.teal)
        views.setTextColor(R.id.widget_task, palette.teal)
        views.setTextColor(R.id.widget_voice, palette.teal)
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
        fun refreshAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, QuickNoteWidgetProvider::class.java))
            if (ids.isNotEmpty()) QuickNoteWidgetProvider().onUpdate(context, manager, ids)
        }
    }
}
