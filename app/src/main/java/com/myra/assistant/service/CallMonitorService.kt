package com.myra.assistant.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.provider.ContactsContract
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.myra.assistant.MyraApplication
import com.myra.assistant.R
import com.myra.assistant.ui.main.MainActivity

/**
 * Foreground service that watches for incoming calls and pings MainActivity so
 * MYRA can announce them and listen for an "uthao" / "reject" command.
 */
class CallMonitorService : Service() {

    private var tm: TelephonyManager? = null
    private var listener: PhoneStateListener? = null
    private var lastState = TelephonyManager.CALL_STATE_IDLE
    private var lastAnnouncedNumber: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startMyraForeground(NOTIFICATION_ID, buildNotification())
        tm = ContextCompat.getSystemService(this, TelephonyManager::class.java)
        registerListener()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        tm?.listen(listener, PhoneStateListener.LISTEN_NONE)
        listener = null
        super.onDestroy()
    }

    private fun startMyraForeground(id: Int, notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL)
        } else {
            startForeground(id, notification)
        }
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, MyraApplication.CHANNEL_CALL)
            .setSmallIcon(R.drawable.ic_myra_notif)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notif_call_text))
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    @SuppressLint("MissingPermission")
    private fun registerListener() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "READ_PHONE_STATE not granted — call monitoring disabled")
            return
        }
        @Suppress("DEPRECATION")
        val l = object : PhoneStateListener() {
            override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                onState(state, phoneNumber)
            }
        }
        listener = l
        @Suppress("DEPRECATION")
        tm?.listen(l, PhoneStateListener.LISTEN_CALL_STATE)
    }

    private fun onState(state: Int, phoneNumber: String?) {
        if (state == lastState) return
        lastState = state
        when (state) {
            TelephonyManager.CALL_STATE_RINGING -> {
                val number = phoneNumber?.takeIf { it.isNotEmpty() }
                if (number == lastAnnouncedNumber) return
                lastAnnouncedNumber = number
                val caller = number?.let { resolveCallerName(it) } ?: number ?: "Unknown"
                val intent = Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    putExtra(EXTRA_INCOMING_CALL, true)
                    putExtra(EXTRA_CALLER_NAME, caller)
                }
                startActivity(intent)
            }
            TelephonyManager.CALL_STATE_IDLE -> {
                lastAnnouncedNumber = null
                sendBroadcast(Intent(ACTION_CALL_ENDED).setPackage(packageName))
            }
        }
    }

    private fun resolveCallerName(number: String): String? {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED
        ) return null
        val uri = Uri.withAppendedPath(
            ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
            Uri.encode(number),
        )
        val cr = contentResolver
        val cursor = cr.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)
            ?: return null
        cursor.use {
            if (it.moveToFirst()) {
                return it.getString(0)
            }
        }
        return null
    }

    companion object {
        private const val TAG = "CallMonitor"
        private const val NOTIFICATION_ID = 4711
        const val EXTRA_INCOMING_CALL = "INCOMING_CALL"
        const val EXTRA_CALLER_NAME = "CALLER_NAME"
        const val ACTION_CALL_ENDED = "com.myra.CALL_ENDED"

        fun start(context: Context) {
            val i = Intent(context, CallMonitorService::class.java)
            ContextCompat.startForegroundService(context, i)
        }
    }
}
