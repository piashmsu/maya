package com.myra.assistant.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.myra.assistant.MyraApplication
import com.myra.assistant.R
import com.myra.assistant.ui.main.MainActivity

/**
 * "MYRA help" during a phone call. While a call is active, this service spins
 * up a [SpeechRecognizer] tuned for the wake phrase. On trigger it launches
 * [MainActivity] with [EXTRA_FROM_IN_CALL] so the activity can switch its
 * audio routing to STREAM_VOICE_CALL — that way MYRA whispers the answer in
 * the user's earpiece without the other party hearing.
 *
 * Audio routing notes:
 * - Android does not officially let third-party apps capture the other
 *   party's voice from `VOICE_CALL` audio source. We just listen to the
 *   user's mic — which is the practical, safe behaviour.
 * - Without root some OEMs aggressively pause `SpeechRecognizer` while in a
 *   call. The root-setup whitelists MYRA, which lets it survive.
 */
class InCallAssistantService : Service() {

    private var recognizer: SpeechRecognizer? = null
    private var handler: Handler? = null
    private var cancelled = false
    private var lastTrigger = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        handler = Handler(Looper.getMainLooper())
        startInForeground()
        startRecognizerLoop()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        cancelled = true
        runCatching { recognizer?.cancel() }
        runCatching { recognizer?.destroy() }
        recognizer = null
        isRunning = false
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
            .Builder(this, MyraApplication.CHANNEL_CALL)
            .setSmallIcon(R.drawable.ic_myra_notif)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notif_in_call_text))
            .setContentIntent(launch)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    private fun startRecognizerLoop() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Log.w(TAG, "SpeechRecognizer not available — in-call MYRA disabled")
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
        // We deliberately require "help" so a normal mention of MYRA's name
        // during a call doesn't open the app mid-conversation.
        val matched = IN_CALL_TRIGGERS.any { lower.contains(it) }
        if (!matched) return
        val now = System.currentTimeMillis()
        if (now - lastTrigger < TRIGGER_COOLDOWN_MS) return
        lastTrigger = now
        // Make sure we don't fight the call audio when MYRA starts speaking.
        val am = ContextCompat.getSystemService(this, AudioManager::class.java)
        am?.mode = AudioManager.MODE_IN_COMMUNICATION
        val launch = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            putExtra(EXTRA_FROM_IN_CALL, true)
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
        private const val TAG = "InCallMyra"
        private const val NOTIFICATION_ID = 4714
        private const val TRIGGER_COOLDOWN_MS = 5_000L
        const val EXTRA_FROM_IN_CALL = "myra_from_in_call"

        private val IN_CALL_TRIGGERS = listOf(
            "myra help",
            "hey myra",
            "myra please",
            "myra translate",
            "myra summarize",
            "myra summarise",
        )

        @Volatile
        var isRunning: Boolean = false
            private set

        fun start(context: Context) {
            val i = Intent(context, InCallAssistantService::class.java)
            ContextCompat.startForegroundService(context, i)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, InCallAssistantService::class.java))
        }
    }
}
