package com.myra.assistant.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Starts the call-monitor service after device boot. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED ||
            intent?.action == Intent.ACTION_LOCKED_BOOT_COMPLETED
        ) {
            CallMonitorService.start(context)
        }
    }
}
