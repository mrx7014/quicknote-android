package com.quicknote.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings

class UnlockReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!AppPreferences.popupEnabled(context)) return
        if (Settings.canDrawOverlays(context)) {
            try {
                val serviceIntent = Intent(context, QuickCaptureService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(serviceIntent)
                else context.startService(serviceIntent)
            } catch (_: Exception) {
                NotificationHelper.showUnlockPrompt(context)
            }
        } else {
            NotificationHelper.showUnlockPrompt(context)
        }
    }
}
