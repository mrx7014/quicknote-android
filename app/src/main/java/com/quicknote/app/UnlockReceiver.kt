package com.quicknote.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings

class UnlockReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED)) return
        ReminderScheduler.rescheduleAll(context)
        if (!AppPreferences.popupEnabled(context) || !Settings.canDrawOverlays(context)) return
        try {
            val serviceIntent = Intent(context, QuickCaptureService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(serviceIntent)
            else context.startService(serviceIntent)
        } catch (_: Exception) {
            // Startup prompts are intentionally avoided; the user can re-enable the feature in Settings.
        }
    }
}
