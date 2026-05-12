package com.myra.assistant.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.myra.assistant.MyraApplication
import com.myra.assistant.R
import com.myra.assistant.ai.AudioEngine
import com.myra.assistant.ai.GeminiLiveClient
import com.myra.assistant.ai.SystemPrompts
import com.myra.assistant.data.ChatHistory
import com.myra.assistant.data.Prefs
import com.myra.assistant.ui.main.MainActivity

/**
 * Owns the long-lived Gemini Live WebSocket and the [AudioEngine] microphone /
 * speaker pair. Previously these lived inside [MainActivity], which meant the
 * voice session died the moment the user left the app. This service keeps the
 * session alive while MYRA is "on", regardless of what activity is foreground.
 *
 * UI components bind via [LocalBinder] and register a [Listener] to receive
 * connection / transcript / amplitude events. When the activity is destroyed
 * the service continues running — Gemini stays connected, the mic stays open,
 * MYRA can still hear and respond to "Hey MYRA" or in-call wake words.
 *
 * Started as a microphone-typed foreground service so Android 14+ does not
 * silently kill it when the screen is off or another app is foreground.
 */
class MyraVoiceService : Service() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val prefs by lazy { Prefs(this) }
    private val chatHistory by lazy { ChatHistory(this) }

    private var gemini: GeminiLiveClient? = null
    private var audio: AudioEngine? = null
    private val listeners = mutableListOf<Listener>()
    private var greetingSent = false
    private var isReady = false

    // Skip forwarding mic frames to Gemini during a ringing/active phone call
    // so the caller's voice doesn't accidentally leak into the WebSocket. The
    // in-call assistant service does its own separate listening.
    @Volatile
    private var suspendMicSend = false

    // Buffered transcripts — Gemini streams partial tokens; we accumulate until
    // onTurnComplete fires, then forward the full sentence to listeners.
    private val inputBuffer = StringBuilder()
    private val outputBuffer = StringBuilder()

    private val binder = LocalBinder()

    inner class LocalBinder : Binder() {
        val service: MyraVoiceService get() = this@MyraVoiceService
    }

    /** Callbacks consumed by [MainActivity] (and any other UI surface). */
    interface Listener {
        fun onConnected() {}
        fun onSetupComplete() {}
        fun onDisconnected(reason: String) {}
        fun onAmplitudeChanged(rms: Float) {}
        fun onSpeakingStarted() {}
        fun onSpeakingStopped() {}
        fun onUserTranscript(text: String) {}
        fun onMyraTranscript(text: String) {}
        fun onError(message: String) {}
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        instance = this
        startInForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_SESSION -> ensureSessionStarted()
            ACTION_STOP_SESSION -> stopSession()
            else -> ensureSessionStarted()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopSession()
        isRunning = false
        instance = null
        super.onDestroy()
    }

    // ---- Public API used by MainActivity --------------------------------

    fun addListener(l: Listener) {
        if (!listeners.contains(l)) listeners.add(l)
        // Replay the current connection state so a freshly-attached UI doesn't
        // sit there showing "disconnected" while the socket is already up.
        if (isReady) mainHandler.post { l.onSetupComplete() }
    }

    fun removeListener(l: Listener) {
        listeners.remove(l)
    }

    fun isOpen(): Boolean = gemini?.isOpen() == true

    fun sendText(text: String) {
        gemini?.sendText(text)
    }

    fun setMicMuted(muted: Boolean) {
        audio?.setMuted(muted)
    }

    /** Pause forwarding mic audio to Gemini (used during ringing/active calls). */
    fun setMicSuspended(suspended: Boolean) {
        suspendMicSend = suspended
    }

    /**
     * Reset the live session — useful when the user changes language / voice /
     * model in Settings. We tear down cleanly and start fresh so the new prefs
     * propagate without restarting the whole app.
     */
    fun reconnect() {
        stopSession()
        mainHandler.postDelayed({ ensureSessionStarted() }, 400L)
    }

    // ---- Session lifecycle ----------------------------------------------

    private fun ensureSessionStarted() {
        if (gemini != null) return
        if (prefs.apiKey.isBlank()) {
            dispatchError("Settings mein API key set kar do.")
            return
        }
        val live = GeminiLiveClient(this)
        val eng = AudioEngine(this)

        live.onConnected = {
            mainHandler.post { listeners.forEach { it.onConnected() } }
        }
        live.onSetupComplete = {
            eng.startRecording()
            eng.startPlayback()
            isReady = true
            mainHandler.post { listeners.forEach { it.onSetupComplete() } }
            if (!greetingSent) {
                greetingSent = true
                mainHandler.postDelayed({
                    val greeting = SystemPrompts.greeting(
                        prefs.personality,
                        prefs.userName,
                        prefs.language,
                    )
                    live.sendText(greeting)
                }, 600L)
            }
        }
        live.onDisconnected = { reason ->
            isReady = false
            mainHandler.post { listeners.forEach { it.onDisconnected(reason) } }
        }
        live.onAudioReceived = { pcm -> eng.queueAudio(pcm) }
        live.onInputTranscript = { text -> inputBuffer.append(text) }
        live.onOutputTranscript = { text -> outputBuffer.append(text) }
        live.onTurnComplete = { flushTranscripts() }
        live.onError = { _, msg -> dispatchError(msg) }

        eng.onMicChunk = { pcm ->
            if (!suspendMicSend) live.sendAudioBytes(pcm)
        }
        eng.onAmplitudeChanged = { rms ->
            mainHandler.post { listeners.forEach { it.onAmplitudeChanged(rms) } }
        }
        eng.onSpeakingStarted = {
            // Lower routing for the speaker — we want MYRA's voice on the
            // current output device (speaker, BT headset, earpiece).
            val am = ContextCompat.getSystemService(this, AudioManager::class.java)
            am?.mode = AudioManager.MODE_NORMAL
            mainHandler.post { listeners.forEach { it.onSpeakingStarted() } }
        }
        eng.onSpeakingStopped = {
            mainHandler.post { listeners.forEach { it.onSpeakingStopped() } }
        }

        gemini = live
        audio = eng
        live.connect()
    }

    private fun stopSession() {
        gemini?.disconnect()
        audio?.release()
        gemini = null
        audio = null
        isReady = false
        greetingSent = false
        inputBuffer.setLength(0)
        outputBuffer.setLength(0)
    }

    private fun flushTranscripts() {
        val userText = inputBuffer.toString().trim()
        val myraText = outputBuffer.toString().trim()
        inputBuffer.setLength(0)
        outputBuffer.setLength(0)
        if (userText.isNotEmpty()) {
            // Persist the message so chat history survives app restarts even
            // when the conversation happens with the UI in the background.
            chatHistory.append(
                com.myra.assistant.ui.main.ChatMessage(userText, isUser = true)
            )
            mainHandler.post {
                listeners.forEach { it.onUserTranscript(userText) }
            }
        }
        if (myraText.isNotEmpty()) {
            chatHistory.append(
                com.myra.assistant.ui.main.ChatMessage(myraText, isUser = false)
            )
            mainHandler.post {
                listeners.forEach { it.onMyraTranscript(myraText) }
            }
        }
    }

    private fun dispatchError(msg: String) {
        mainHandler.post { listeners.forEach { it.onError(msg) } }
    }

    // ---- Foreground notification ----------------------------------------

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
            .setContentText(getString(R.string.notif_voice_text))
            .setContentIntent(launch)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    companion object {
        const val ACTION_START_SESSION = "com.myra.voice.START"
        const val ACTION_STOP_SESSION = "com.myra.voice.STOP"
        private const val NOTIFICATION_ID = 4715

        @Volatile
        var isRunning: Boolean = false
            private set

        @Volatile
        var instance: MyraVoiceService? = null
            private set

        fun start(context: Context) {
            val i = Intent(context, MyraVoiceService::class.java)
                .setAction(ACTION_START_SESSION)
            ContextCompat.startForegroundService(context, i)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, MyraVoiceService::class.java))
        }
    }
}
