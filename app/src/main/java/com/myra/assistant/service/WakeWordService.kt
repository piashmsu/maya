package com.myra.assistant.service

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.myra.assistant.MyraApplication
import com.myra.assistant.R
import com.myra.assistant.ui.main.MainActivity

/**
 * Listens continuously for the wake phrase "Hey MYRA" using Android's
 * [SpeechRecognizer] in a self-restarting loop. When detected, opens
 * [MainActivity] so the live audio session can take over.
 *
 * Notes:
 * - Some devices stop on-device recognition when the screen is off; for those
 *   the user should disable battery optimisation (the root setup does this
 *   automatically).
 * - The service holds a foreground notification because Android terminates
 *   long-lived mic users otherwise.
 */
class WakeWordService : android.app.Service() {

    private var recognizer: SpeechRecognizer? = null
    private var handler: Handler? = null
    private var cancelled = false
    private var lastTriggerMillis = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        handler = Handler(Looper.getMainLooper())
        startInForeground()
        startRecognizerLoop()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        cancelled = true
        recognizer?.destroy()
        recognizer = null
        isRunning = false
        super.onDestroy()
    }

    private fun startInForeground() {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), type)
    }

    private fun buildNotification(): Notification {
        val launch = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, MyraApplication.CHANNEL_OVERLAY)
            .setContentTitle(getString(R.string.notif_wake_title))
            .setContentText(getString(R.string.notif_wake_text))
            .setSmallIcon(R.drawable.ic_myra_notif)
            .setOngoing(true)
            .setContentIntent(launch)
            .build()
    }

    private fun startRecognizerLoop() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            stopSelf()
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            stopSelf()
            return
        }
        recognizer = SpeechRecognizer.createSpeechRecognizer(this)
        recognizer?.setRecognitionListener(listener)
        beginListening()
    }

    private fun beginListening() {
        if (cancelled) return
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            }
        }
        runCatching { recognizer?.startListening(intent) }
    }

    private fun restartLater(delayMs: Long) {
        if (cancelled) return
        handler?.postDelayed({
            if (!cancelled) {
                runCatching { recognizer?.cancel() }
                beginListening()
            }
        }, delayMs)
    }

    private fun checkAndTrigger(text: String?) {
        text ?: return
        val lower = text.lowercase()
        val hit = WAKE_TOKENS.any { lower.contains(it) }
        if (!hit) return
        val now = System.currentTimeMillis()
        if (now - lastTriggerMillis < TRIGGER_COOLDOWN_MS) return
        lastTriggerMillis = now
        val launch = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            putExtra(EXTRA_WAKE_TRIGGERED, true)
        }
        startActivity(launch)
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onPartialResults(partialResults: Bundle?) {
            val matches =
                partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            checkAndTrigger(matches?.firstOrNull())
        }

        override fun onResults(results: Bundle?) {
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            checkAndTrigger(matches?.firstOrNull())
            restartLater(250L)
        }

        override fun onError(error: Int) {
            // SpeechRecognizer fires errors frequently (no match, no speech, etc.).
            // Just back off briefly and retry; this is how continuous listening
            // works on Android without third-party SDKs.
            val delay = when (error) {
                SpeechRecognizer.ERROR_NETWORK,
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> 3_000L
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> 1_000L
                else -> 400L
            }
            restartLater(delay)
        }
    }

    companion object {
        @JvmStatic
        var isRunning: Boolean = false
            private set

        const val EXTRA_WAKE_TRIGGERED = "wake_triggered"
        private const val NOTIFICATION_ID = 4242
        private const val TRIGGER_COOLDOWN_MS = 5_000L

        private val WAKE_TOKENS = listOf(
            "hey myra",
            "hi myra",
            "myra",
            "mira",
            "hey mira",
            "ok myra",
        )

        fun start(context: Context) {
            val intent = Intent(context, WakeWordService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, WakeWordService::class.java))
        }
    }
}
