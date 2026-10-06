package com.quicknote.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class UnlockReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_USER_PRESENT, Intent.ACTION_BOOT_COMPLETED -> NotificationHelper.showUnlockPrompt(context)
        }
    }
}
