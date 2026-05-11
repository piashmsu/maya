package com.myra.assistant

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class MyraApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        registerNotificationChannels()
    }

    private fun registerNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_OVERLAY,
                getString(R.string.notif_channel_overlay),
                NotificationManager.IMPORTANCE_LOW
            )
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_CALL,
                getString(R.string.notif_channel_call),
                NotificationManager.IMPORTANCE_LOW
            )
        )
    }

    companion object {
        const val CHANNEL_OVERLAY = "myra_overlay_channel"
        const val CHANNEL_CALL = "myra_call_channel"

        @Volatile
        lateinit var instance: MyraApplication
            private set
    }
}
