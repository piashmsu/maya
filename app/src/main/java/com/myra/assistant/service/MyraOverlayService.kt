package com.myra.assistant.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.myra.assistant.MyraApplication
import com.myra.assistant.R
import com.myra.assistant.ui.main.MainActivity
import com.myra.assistant.ui.main.OrbAnimationView
import kotlin.math.abs

/**
 * Foreground service that draws a draggable MYRA orb on top of other apps.
 * Tap → open MainActivity. X → remove the orb.
 */
class MyraOverlayService : Service() {

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        startForeground(NOTIFICATION_ID, buildNotification())
        windowManager = getSystemService(WINDOW_SERVICE) as? WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SHOW_OVERLAY -> showOrb()
            ACTION_HIDE_OVERLAY -> hideOrb()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        hideOrb()
        isRunning = false
        super.onDestroy()
    }

    private fun startForeground(id: Int, notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(id, notification)
        }
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, MyraApplication.CHANNEL_OVERLAY)
            .setSmallIcon(R.drawable.ic_myra_notif)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notif_overlay_text))
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showOrb() {
        if (overlayView != null) return
        val wm = windowManager ?: return
        val inflater = getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
        val view = inflater.inflate(R.layout.overlay_orb, null, false)
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 40
            y = 320
        }
        val orb = view.findViewById<OrbAnimationView>(R.id.overlayOrb)
        orb.setOrbState(OrbAnimationView.State.IDLE)

        attachDragAndTap(view, params)
        view.findViewById<View>(R.id.overlayClose).setOnClickListener { hideOrb() }

        wm.addView(view, params)
        overlayView = view
        layoutParams = params
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachDragAndTap(view: View, params: WindowManager.LayoutParams) {
        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        var dragged = false
        view.setOnTouchListener { _, event ->
            val wm = windowManager ?: return@setOnTouchListener false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    dragged = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - touchX).toInt()
                    val dy = (event.rawY - touchY).toInt()
                    if (abs(dx) > 8 || abs(dy) > 8) dragged = true
                    params.x = startX + dx
                    params.y = startY + dy
                    wm.updateViewLayout(view, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragged) openMain()
                    true
                }
                else -> false
            }
        }
    }

    private fun openMain() {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        startActivity(intent)
    }

    private fun hideOrb() {
        val wm = windowManager
        val view = overlayView
        overlayView = null
        if (wm != null && view != null) {
            runCatching { wm.removeView(view) }
        }
    }

    companion object {
        @Volatile
        var isRunning: Boolean = false
            private set

        private const val NOTIFICATION_ID = 4712
        const val ACTION_SHOW_OVERLAY = "com.myra.SHOW_OVERLAY"
        const val ACTION_HIDE_OVERLAY = "com.myra.HIDE_OVERLAY"

        fun show(context: Context) {
            val i = Intent(context, MyraOverlayService::class.java).setAction(ACTION_SHOW_OVERLAY)
            androidx.core.content.ContextCompat.startForegroundService(context, i)
        }

        fun hide(context: Context) {
            val i = Intent(context, MyraOverlayService::class.java).setAction(ACTION_HIDE_OVERLAY)
            context.startService(i)
        }
    }
}
