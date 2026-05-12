package com.myra.assistant.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import android.view.KeyEvent
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.myra.assistant.MyraApplication
import com.myra.assistant.R
import com.myra.assistant.ui.main.MainActivity

/**
 * Listens for Bluetooth-headphone media-button events and triggers MYRA on
 * a *double-tap* of the play/pause / headset-hook button. Most TWS earbuds
 * (Boat, Realme, OnePlus, Galaxy Buds, etc) emit this single key for their
 * play / pause / accept-call action.
 *
 * Implementation detail: we hold an active [MediaSessionCompat] so that
 * Android delivers media-button key events to us in priority order. The
 * session intentionally reports PLAYING with no audio — we just need it on
 * the system's "current media session" stack to receive the keys.
 *
 * The session is released on destroy so we don't permanently steal media
 * focus from real music apps.
 */
class BluetoothGestureService : Service() {

    private var session: MediaSessionCompat? = null
    private var lastTapMillis = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startInForeground()
        setupMediaSession()
        isRunning = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        isRunning = false
        runCatching {
            session?.isActive = false
            session?.release()
        }
        session = null
        super.onDestroy()
    }

    private fun startInForeground() {
        val launch = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification: Notification = NotificationCompat
            .Builder(this, MyraApplication.CHANNEL_OVERLAY)
            .setSmallIcon(R.drawable.ic_myra_notif)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notif_bluetooth_text))
            .setContentIntent(launch)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        // No foreground-service-type — this is a normal listener service,
        // it doesn't actually capture mic or use the camera.
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    private fun setupMediaSession() {
        val s = MediaSessionCompat(this, TAG)
        // Advertise PLAY/PAUSE so Android forwards those key events here.
        val state = PlaybackStateCompat.Builder()
            .setActions(
                PlaybackStateCompat.ACTION_PLAY_PAUSE
                    or PlaybackStateCompat.ACTION_PLAY
                    or PlaybackStateCompat.ACTION_PAUSE,
            )
            .setState(PlaybackStateCompat.STATE_PLAYING, 0L, 0f)
            .build()
        s.setPlaybackState(state)
        s.setCallback(callback)
        s.isActive = true
        session = s
    }

    private val callback = object : MediaSessionCompat.Callback() {
        override fun onMediaButtonEvent(intent: Intent): Boolean {
            val key: KeyEvent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT)
            }
            if (key == null || key.action != KeyEvent.ACTION_DOWN) {
                // We only care about DOWN — ignore UP and key-up duplicates.
                return super.onMediaButtonEvent(intent)
            }
            when (key.keyCode) {
                KeyEvent.KEYCODE_HEADSETHOOK,
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                KeyEvent.KEYCODE_MEDIA_PLAY,
                KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                    val now = System.currentTimeMillis()
                    val delta = now - lastTapMillis
                    lastTapMillis = now
                    if (delta in 60..DOUBLE_TAP_WINDOW_MS) {
                        triggerMyra()
                        // Reset so a 3rd tap won't immediately re-trigger.
                        lastTapMillis = 0L
                        return true
                    }
                }
                else -> Unit
            }
            return super.onMediaButtonEvent(intent)
        }
    }

    private fun triggerMyra() {
        Log.i(TAG, "Bluetooth double-tap → waking MYRA")
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            putExtra(EXTRA_FROM_BLUETOOTH, true)
        }
        startActivity(intent)
    }

    companion object {
        private const val TAG = "MyraBtGesture"
        private const val NOTIFICATION_ID = 4713
        private const val DOUBLE_TAP_WINDOW_MS = 600L
        const val EXTRA_FROM_BLUETOOTH = "myra_from_bt"

        @Volatile
        var isRunning: Boolean = false
            private set

        fun start(context: Context) {
            val i = Intent(context, BluetoothGestureService::class.java)
            ContextCompat.startForegroundService(context, i)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, BluetoothGestureService::class.java))
        }
    }
}
