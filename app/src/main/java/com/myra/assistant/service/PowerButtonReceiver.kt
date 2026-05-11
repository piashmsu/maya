package com.myra.assistant.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock

/**
 * Detects a double-press of the power button (two SCREEN_OFF / SCREEN_ON
 * events within [DOUBLE_TAP_WINDOW_MS]) and shows the MYRA overlay orb.
 */
class PowerButtonReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_SCREEN_OFF, Intent.ACTION_SCREEN_ON -> handleEvent(context)
        }
    }

    private fun handleEvent(context: Context) {
        val now = SystemClock.uptimeMillis()
        val gap = now - lastEventAt
        lastEventAt = now
        eventCount = if (gap < DOUBLE_TAP_WINDOW_MS) eventCount + 1 else 1
        if (eventCount >= 4) {
            // 4 events = OFF + ON + OFF + ON within window = "double press".
            eventCount = 0
            MyraOverlayService.show(context)
        }
    }

    companion object {
        private const val DOUBLE_TAP_WINDOW_MS = 600L
        private var lastEventAt = 0L
        private var eventCount = 0
    }
}
