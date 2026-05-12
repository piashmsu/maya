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
import android.widget.Button
import android.widget.EditText
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
    private var chatPanelExpanded = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        startMyraForeground(NOTIFICATION_ID, buildNotification())
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

    private fun startMyraForeground(id: Int, notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
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

        val head = view.findViewById<View>(R.id.overlayHead)
        val panel = view.findViewById<View>(R.id.overlayChatPanel)

        attachDragAndTap(view, head, panel, params)
        view.findViewById<View>(R.id.overlayClose).setOnClickListener { hideOrb() }

        view.findViewById<Button>(R.id.overlayOpenMyra).setOnClickListener {
            openMain()
            collapsePanel(panel)
        }

        val input = view.findViewById<EditText>(R.id.overlayChatInput)
        view.findViewById<Button>(R.id.overlayChatSend).setOnClickListener {
            val msg = input.text?.toString()?.trim().orEmpty()
            if (msg.isNotEmpty()) {
                openMainWithQuery(msg)
                input.setText("")
                collapsePanel(panel)
            }
        }

        wm.addView(view, params)
        overlayView = view
        layoutParams = params
    }

    private fun togglePanel(panel: View) {
        chatPanelExpanded = !chatPanelExpanded
        panel.visibility = if (chatPanelExpanded) View.VISIBLE else View.GONE
        if (chatPanelExpanded) {
            // When the panel pops open we need keystrokes to reach our EditText —
            // FLAG_NOT_FOCUSABLE blocks the IME so we briefly drop it.
            val params = layoutParams ?: return
            params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
            runCatching { windowManager?.updateViewLayout(panel.rootView, params) }
        } else {
            val params = layoutParams ?: return
            params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            runCatching { windowManager?.updateViewLayout(panel.rootView, params) }
        }
    }

    private fun collapsePanel(panel: View) {
        if (!chatPanelExpanded) return
        togglePanel(panel)
    }

    private fun openMainWithQuery(query: String) {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(EXTRA_TEXT_QUERY, query)
        startActivity(intent)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachDragAndTap(
        rootView: View,
        head: View,
        panel: View,
        params: WindowManager.LayoutParams,
    ) {
        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        var dragged = false
        // We only listen on the orb itself so the chat-panel inputs can
        // still receive their own touch events normally.
        head.setOnTouchListener { _, event ->
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
                    wm.updateViewLayout(rootView, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragged) togglePanel(panel)
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
        const val EXTRA_TEXT_QUERY = "myra_text_query"

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
